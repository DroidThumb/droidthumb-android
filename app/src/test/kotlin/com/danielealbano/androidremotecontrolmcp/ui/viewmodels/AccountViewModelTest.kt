package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.model.TransportConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.account.AccountApiClient
import com.danielealbano.androidremotecontrolmcp.services.account.AccountConnection
import com.danielealbano.androidremotecontrolmcp.services.account.AccountDevice
import com.danielealbano.androidremotecontrolmcp.services.account.ApiErrorKind
import com.danielealbano.androidremotecontrolmcp.services.account.ClaimTokenResult
import com.danielealbano.androidremotecontrolmcp.services.account.ConnectionsResult
import com.danielealbano.androidremotecontrolmcp.services.account.DevicesResult
import com.danielealbano.androidremotecontrolmcp.services.account.GoogleSignInClient
import com.danielealbano.androidremotecontrolmcp.services.account.GoogleSignInResult
import com.danielealbano.androidremotecontrolmcp.services.account.RenameResult
import com.danielealbano.androidremotecontrolmcp.services.account.RevokeResult
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceIdentityKeyStore
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceInfoProvider
import com.danielealbano.androidremotecontrolmcp.services.identity.deriveDeviceId
import com.danielealbano.androidremotecontrolmcp.services.transport.ClaimResult
import com.danielealbano.androidremotecontrolmcp.services.transport.DeviceTransportClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("AccountViewModel")
class AccountViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val googleSignInClient = mockk<GoogleSignInClient>()
    private val accountApiClient = mockk<AccountApiClient>()
    private val transportClient = mockk<DeviceTransportClient>()
    private val deviceIdentityKeyStore = mockk<DeviceIdentityKeyStore>(relaxed = true)
    private val deviceInfoProvider = mockk<DeviceInfoProvider>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private val accountIdFlow = MutableStateFlow<String?>(null)
    private val transportConfig = TransportConfig(host = "h", port = 1, tls = false)

    private lateinit var viewModel: AccountViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepository.accountId } returns accountIdFlow
        coEvery { settingsRepository.getTransportConfig() } returns transportConfig
        viewModel =
            AccountViewModel(
                settingsRepository,
                googleSignInClient,
                accountApiClient,
                transportClient,
                deviceIdentityKeyStore,
                deviceInfoProvider,
                testDispatcher,
            )
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `signInAndClaim runs the full chain and ends Claimed on success`() =
        runTest {
            val publicKeyBytes = byteArrayOf(1, 2, 3)
            every { deviceIdentityKeyStore.ensurePublicKeyBase64() } returns
                Base64.getEncoder().encodeToString(publicKeyBytes)
            val thisDeviceId = deriveDeviceId(publicKeyBytes)
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = false) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { accountApiClient.mintClaimToken("h", 1, false, "id-token") } returns
                ClaimTokenResult.Success(claimToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.claimAccount("clt_x") } returns ClaimResult.Claimed("acc_1")
            coEvery { accountApiClient.listConnections("h", 1, false, "id-token") } returns
                ConnectionsResult.Success(emptyList())
            coEvery { accountApiClient.listDevices("h", 1, false, "silent-token") } returns
                DevicesResult.Success(listOf(AccountDevice(thisDeviceId, "2026-10-01", null)), deviceLimit = null)

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(AccountClaimState.Claimed("acc_1"), viewModel.claimState.value)
            coVerify { settingsRepository.updateAccountId("acc_1") }
            // Plan A1b: after a successful claim the A1a session check runs, so the sign-in state
            // comes from the server's device list, not from the claim's own outcome alone.
            assertEquals(
                AccountSessionState.SignedIn(
                    claimed = true,
                    device = AccountDevice(thisDeviceId, "2026-10-01", null),
                ),
                viewModel.sessionState.value,
            )
        }

    @Test
    fun `signInAndClaim surfaces AlreadyClaimedByOther, not a generic failure`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = false) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintClaimToken(any(), any(), any(), any()) } returns
                ClaimTokenResult.Success(claimToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.claimAccount("clt_x") } returns ClaimResult.Rejected("already_claimed")

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(AccountClaimState.AlreadyClaimedByOther, viewModel.claimState.value)
            coVerify(exactly = 0) { settingsRepository.updateAccountId(any()) }
        }

    @Test
    fun `signInAndClaim stops at Failed when Google sign-in itself fails`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = false) } returns
                GoogleSignInResult.Failed("denied")

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(AccountClaimState.Failed("denied"), viewModel.claimState.value)
            coVerify(exactly = 0) { accountApiClient.mintClaimToken(any(), any(), any(), any()) }
        }

    @Test
    fun `loadConnections tries silently first, only shows the picker if that finds nothing`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { accountApiClient.listConnections("h", 1, false, "silent-token") } returns
                ConnectionsResult.Success(listOf(AccountConnection("c1", "Claude", "Claude", null, "2026-10-01")))

            viewModel.loadConnections(context, allowInteractive = true)
            advanceUntilIdle()

            val expected =
                ConnectionsState.Loaded(listOf(AccountConnection("c1", "Claude", "Claude", null, "2026-10-01")))
            assertEquals(expected, viewModel.connectionsState.value)
            coVerify(exactly = 0) { googleSignInClient.signIn(context, filterByAuthorizedAccounts = false) }
        }

    @Test
    fun `loadConnections falls back to the picker when silent fails and interactive is allowed`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.NoCredential
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = false) } returns
                GoogleSignInResult.Success("interactive-token")
            coEvery { accountApiClient.listConnections("h", 1, false, "interactive-token") } returns
                ConnectionsResult.Success(emptyList())

            viewModel.loadConnections(context, allowInteractive = true)
            advanceUntilIdle()

            assertTrue(viewModel.connectionsState.value is ConnectionsState.Loaded)
        }

    @Test
    fun `loadConnections never shows the picker when interactive is not allowed, even if silent finds nothing`() =
        runTest {
            // An automatic, non-user-initiated refresh (app reopened, phone rebooted) must never
            // put up Google's own account-picker UI on its own - being claimed is this device's
            // own durable state and must not look like a sign-out just because a background
            // connections refresh needed a fresh token (founder feedback, PR #8 round 5).
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.NoCredential

            viewModel.loadConnections(context, allowInteractive = false)
            advanceUntilIdle()

            assertEquals(
                ConnectionsState.Failed("Sign in to view your AI connections"),
                viewModel.connectionsState.value,
            )
            coVerify(exactly = 0) { googleSignInClient.signIn(context, filterByAuthorizedAccounts = false) }
        }

    @Test
    fun `revokeConnection removes the row from the loaded list on success`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("token")
            coEvery { accountApiClient.listConnections("h", 1, false, "token") } returns
                ConnectionsResult.Success(listOf(AccountConnection("c1", "Claude", "Claude", null, "2026-10-01")))
            viewModel.loadConnections(context, allowInteractive = true)
            advanceUntilIdle()

            coEvery { accountApiClient.revokeConnection("h", 1, false, "token", "c1") } returns RevokeResult.Revoked

            viewModel.revokeConnection(context, "c1")
            advanceUntilIdle()

            assertEquals(ConnectionsState.Loaded(emptyList()), viewModel.connectionsState.value)
        }

    @Test
    fun `renameConnection sends the new display name and updates connectionsState`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("token")
            coEvery { accountApiClient.listConnections("h", 1, false, "token") } returns
                ConnectionsResult.Success(listOf(AccountConnection("c1", "Claude", "Claude", null, "2026-10-01")))
            viewModel.loadConnections(context, allowInteractive = true)
            advanceUntilIdle()

            coEvery { accountApiClient.renameConnection("h", 1, false, "token", "c1", "My Claude") } returns
                RenameResult.Updated

            viewModel.renameConnection(context, "c1", "My Claude")
            advanceUntilIdle()

            assertEquals(
                ConnectionsState.Loaded(listOf(AccountConnection("c1", "Claude", "My Claude", null, "2026-10-01"))),
                viewModel.connectionsState.value,
            )
        }

    @Test
    fun `renameConnection on a failed server response leaves connectionsState unchanged`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("token")
            val loaded =
                ConnectionsResult.Success(listOf(AccountConnection("c1", "Claude", "Claude", null, "2026-10-01")))
            coEvery { accountApiClient.listConnections("h", 1, false, "token") } returns loaded
            viewModel.loadConnections(context, allowInteractive = true)
            advanceUntilIdle()

            coEvery { accountApiClient.renameConnection("h", 1, false, "token", "c1", "My Claude") } returns
                RenameResult.Failed("HTTP 500")

            viewModel.renameConnection(context, "c1", "My Claude")
            advanceUntilIdle()

            assertEquals(
                ConnectionsState.Loaded(listOf(AccountConnection("c1", "Claude", "Claude", null, "2026-10-01"))),
                viewModel.connectionsState.value,
            )
        }

    @Test
    fun `checkSession reports SignedIn claimed when the device list includes this phone`() =
        runTest {
            val publicKeyBytes = byteArrayOf(1, 2, 3)
            every { deviceIdentityKeyStore.ensurePublicKeyBase64() } returns
                Base64.getEncoder().encodeToString(publicKeyBytes)
            val thisDeviceId = deriveDeviceId(publicKeyBytes)
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { accountApiClient.listDevices("h", 1, false, "silent-token") } returns
                DevicesResult.Success(
                    devices =
                        listOf(
                            AccountDevice(thisDeviceId, "2026-10-01", null),
                            AccountDevice("dt_other", "2026-09-01", "2026-09-02"),
                        ),
                    deviceLimit = null,
                )

            viewModel.checkSession(context)
            advanceUntilIdle()

            assertEquals(
                AccountSessionState.SignedIn(
                    claimed = true,
                    device = AccountDevice(thisDeviceId, "2026-10-01", null),
                ),
                viewModel.sessionState.value,
            )
        }

    @Test
    fun `checkSession reports SignedIn not claimed when the device list does not include this phone`() =
        runTest {
            val publicKeyBytes = byteArrayOf(1, 2, 3)
            every { deviceIdentityKeyStore.ensurePublicKeyBase64() } returns
                Base64.getEncoder().encodeToString(publicKeyBytes)
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { accountApiClient.listDevices("h", 1, false, "silent-token") } returns
                DevicesResult.Success(
                    devices = listOf(AccountDevice("dt_other", "2026-09-01", "2026-09-02")),
                    deviceLimit = null,
                )

            viewModel.checkSession(context)
            advanceUntilIdle()

            assertEquals(
                AccountSessionState.SignedIn(claimed = false, device = null),
                viewModel.sessionState.value,
            )
        }

    @Test
    fun `checkSession reports SignedOut when the silent fetch finds no credential`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.NoCredential

            viewModel.checkSession(context)
            advanceUntilIdle()

            assertEquals(AccountSessionState.SignedOut, viewModel.sessionState.value)
            coVerify(exactly = 0) { accountApiClient.listDevices(any(), any(), any(), any()) }
        }

    @Test
    fun `checkSession reports SignedOut on HTTP 401 and 403`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { accountApiClient.listDevices("h", 1, false, "silent-token") } returns
                DevicesResult.Failed(kind = ApiErrorKind.Rejected, message = "HTTP 401", httpStatus = 401)

            viewModel.checkSession(context)
            advanceUntilIdle()
            assertEquals(AccountSessionState.SignedOut, viewModel.sessionState.value)

            coEvery { accountApiClient.listDevices("h", 1, false, "silent-token") } returns
                DevicesResult.Failed(kind = ApiErrorKind.Rejected, message = "HTTP 403", httpStatus = 403)
            viewModel.checkSession(context)
            advanceUntilIdle()
            assertEquals(AccountSessionState.SignedOut, viewModel.sessionState.value)
        }

    @Test
    fun `checkSession reports Unreachable when the devices call fails with an IOException`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { accountApiClient.listDevices("h", 1, false, "silent-token") } returns
                DevicesResult.Failed(
                    kind = ApiErrorKind.Network,
                    message = "Connection refused",
                    exceptionClass = "ConnectException",
                )

            viewModel.checkSession(context)
            advanceUntilIdle()

            assertEquals(AccountSessionState.Unreachable("Connection refused"), viewModel.sessionState.value)
        }

    @Test
    fun `checkSession reports ServerError(500) when the server fails`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { accountApiClient.listDevices("h", 1, false, "silent-token") } returns
                DevicesResult.Failed(kind = ApiErrorKind.Server, message = "HTTP 500", httpStatus = 500)

            viewModel.checkSession(context)
            advanceUntilIdle()

            assertEquals(AccountSessionState.ServerError(500), viewModel.sessionState.value)
        }

    @Test
    fun `checkSession reports Unreachable with the exception class when the identity key store throws`() =
        runTest {
            // Real-device failure mode (former "This device" spinner that never stopped):
            // ensurePublicKeyBase64 is a real Android Keystore call, and any unexpected throw must
            // not leave the session state stuck on Checking.
            every { deviceIdentityKeyStore.ensurePublicKeyBase64() } throws RuntimeException("keystore unavailable")
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { accountApiClient.listDevices("h", 1, false, "silent-token") } returns
                DevicesResult.Success(devices = emptyList(), deviceLimit = null)

            viewModel.checkSession(context)
            advanceUntilIdle()

            assertEquals(AccountSessionState.Unreachable("RuntimeException"), viewModel.sessionState.value)
        }

    @Test
    fun `a second trigger while a check is in flight starts nothing`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("silent-token")
            val gate = CompletableDeferred<Unit>()
            coEvery { accountApiClient.listDevices("h", 1, false, "silent-token") } coAnswers {
                gate.await()
                DevicesResult.Success(devices = emptyList(), deviceLimit = null)
            }

            viewModel.checkSession(context)
            // The first check is suspended inside listDevices; a second trigger must start nothing.
            viewModel.checkSession(context)
            advanceUntilIdle()

            coVerify(exactly = 1) { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) }
            gate.complete(Unit)
            advanceUntilIdle()
            coVerify(exactly = 1) { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) }
        }

    @Test
    fun `checkSession repopulates the in-memory profile from the silent fetch`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success(
                    idToken = "silent-token",
                    displayName = "Test Person",
                    email = "test@example.com",
                    profilePictureUri = null,
                )
            coEvery { accountApiClient.listDevices("h", 1, false, "silent-token") } returns
                DevicesResult.Success(devices = emptyList(), deviceLimit = null)

            viewModel.checkSession(context)
            advanceUntilIdle()

            assertEquals(
                AccountProfile(displayName = "Test Person", email = "test@example.com", profilePictureUri = null),
                viewModel.accountProfile.value,
            )
        }

    @Test
    fun `signOut clears the local account association without touching the server`() =
        runTest {
            coEvery { googleSignInClient.signOut(context) } returns Unit

            viewModel.signOut(context)
            advanceUntilIdle()

            coVerify { googleSignInClient.signOut(context) }
            coVerify { settingsRepository.clearAccountId() }
            assertEquals(AccountClaimState.Idle, viewModel.claimState.value)
            assertEquals(ConnectionsState.Idle, viewModel.connectionsState.value)
            assertEquals(AccountSessionState.SignedOut, viewModel.sessionState.value)
        }
}
