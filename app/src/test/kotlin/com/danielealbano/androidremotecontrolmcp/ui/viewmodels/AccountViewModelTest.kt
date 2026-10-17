package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import com.danielealbano.androidremotecontrolmcp.data.model.TransportConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.PauseSettings
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
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
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
    private val pauseSettings = mockk<PauseSettings>()
    private val deviceIdentityKeyStore = mockk<DeviceIdentityKeyStore>(relaxed = true)
    private val deviceInfoProvider = mockk<DeviceInfoProvider>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private val accountIdFlow = MutableStateFlow<String?>(null)
    private val transportConfig = TransportConfig(host = "h", port = 1, tls = false)
    private val transportStatusFlow = MutableStateFlow<TransportStatus>(TransportStatus.Connected(1))

    private lateinit var viewModel: AccountViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepository.accountId } returns accountIdFlow
        coEvery { settingsRepository.getTransportConfig() } returns transportConfig
        coEvery { pauseSettings.getPauseState() } returns PauseState()
        every { transportClient.status } returns transportStatusFlow
        viewModel =
            AccountViewModel(
                settingsRepository,
                googleSignInClient,
                accountApiClient,
                transportClient,
                pauseSettings,
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
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintClaimToken("h", 1, false, "id-token") } returns
                ClaimTokenResult.Success(claimToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.claimAccount("clt_x") } returns ClaimResult.Claimed("acc_1")
            coEvery { accountApiClient.listConnections("h", 1, false, "id-token") } returns
                ConnectionsResult.Success(emptyList())
            coEvery { accountApiClient.listDevices("h", 1, false, "id-token") } returns
                DevicesResult.Success(listOf(AccountDevice(thisDeviceId, "2026-10-01", null)), deviceLimit = null)

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(AccountClaimState.Claimed("acc_1"), viewModel.claimState.value)
            coVerify { settingsRepository.updateAccountId("acc_1") }
            // Plan A1 review: the post-claim authoritative check reuses the id token the
            // interactive sign-in just obtained and makes NO Google request at all — the silent
            // fetch is never called on this path.
            coVerify(exactly = 0) { googleSignInClient.signInSilently(context) }
            coVerify(exactly = 1) { googleSignInClient.signInInteractively(context) }
            coVerify(exactly = 1) { accountApiClient.listDevices("h", 1, false, "id-token") }
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
            coEvery { googleSignInClient.signInInteractively(context) } returns
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
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Failed("denied")

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(AccountClaimState.Failed("Google sign-in failed: denied"), viewModel.claimState.value)
            coVerify(exactly = 0) { accountApiClient.mintClaimToken(any(), any(), any(), any()) }
        }

    @Test
    fun `claim requested while Connecting waits for Connected, then sends the claim exactly once`() =
        runTest {
            // Plan A1b: the claim goes over the WebSocket. While the transport isn't Connected the
            // state shows WaitingForConnection ("Connecting to the server…") and the claim is sent
            // exactly once, only after the status flow emits Connected - no timer, no polling.
            transportStatusFlow.value = TransportStatus.Connecting
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintClaimToken("h", 1, false, "id-token") } returns
                ClaimTokenResult.Success(claimToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.claimAccount("clt_x") } returns ClaimResult.Claimed("acc_1")
            // The Claimed branch runs the connections load and the authoritative session check,
            // which now reuses the sign-in's own id token (plan A1 review).
            coEvery { accountApiClient.listConnections("h", 1, false, "id-token") } returns
                ConnectionsResult.Success(emptyList())
            coEvery { accountApiClient.listDevices("h", 1, false, "id-token") } returns
                DevicesResult.Success(devices = emptyList(), deviceLimit = null)

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(AccountClaimState.WaitingForConnection, viewModel.claimState.value)
            coVerify(exactly = 0) { transportClient.claimAccount(any()) }

            transportStatusFlow.value = TransportStatus.Connected(1)
            advanceUntilIdle()

            coVerify(exactly = 1) { transportClient.claimAccount("clt_x") }
            assertEquals(AccountClaimState.Claimed("acc_1"), viewModel.claimState.value)
        }

    @Test
    fun `claim requested while paused shows the paused message and sends nothing`() =
        runTest {
            coEvery { pauseSettings.getPauseState() } returns PauseState(isPaused = true, resumeAtEpochMs = null)
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintClaimToken("h", 1, false, "id-token") } returns
                ClaimTokenResult.Success(claimToken = "clt_x", accountId = "acc_1")

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(
                AccountClaimState.Failed("Your connection is paused. Resume it to link this phone."),
                viewModel.claimState.value,
            )
            coVerify(exactly = 0) { transportClient.claimAccount(any()) }
        }

    @Test
    fun `claim_rejected shows the server's reason`() =
        runTest {
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintClaimToken(any(), any(), any(), any()) } returns
                ClaimTokenResult.Success(claimToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.claimAccount("clt_x") } returns ClaimResult.Rejected("token_expired")

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(
                AccountClaimState.Failed("Claim rejected: token_expired"),
                viewModel.claimState.value,
            )
        }

    @Test
    fun `an exception at the Google credential step shows that step's reason and is not stuck in SigningIn`() =
        runTest {
            coEvery { googleSignInClient.signInInteractively(context) } throws
                RuntimeException("credential manager broke")

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(
                AccountClaimState.Failed("Google sign-in failed: credential manager broke"),
                viewModel.claimState.value,
            )
        }

    @Test
    fun `an exception at the claim token step shows that step's reason`() =
        runTest {
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintClaimToken(any(), any(), any(), any()) } throws
                RuntimeException("mint exploded")

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(
                AccountClaimState.Failed("Claim token failed: mint exploded"),
                viewModel.claimState.value,
            )
            coVerify(exactly = 0) { transportClient.claimAccount(any()) }
        }

    @Test
    fun `an exception at the claim step shows that step's reason`() =
        runTest {
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintClaimToken(any(), any(), any(), any()) } returns
                ClaimTokenResult.Success(claimToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.claimAccount("clt_x") } throws RuntimeException("socket gone")

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(
                AccountClaimState.Failed("Claim failed: socket gone"),
                viewModel.claimState.value,
            )
        }

    @Test
    fun `cancellation while Google's sheet is in front shows no error`() =
        runTest {
            // The coroutine cancelled (Activity went away, sheet dismissed): not an error. The
            // state is left as it is; the next A1a check on resume shows the real state.
            coEvery { googleSignInClient.signInInteractively(context) } coAnswers {
                throw CancellationException("sheet dismissed")
            }

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(AccountClaimState.SigningIn, viewModel.claimState.value)
        }

    @Test
    fun `the claim's bounded reply wait expiring shows the no-answer message and never retries`() =
        runTest {
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintClaimToken(any(), any(), any(), any()) } returns
                ClaimTokenResult.Success(claimToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.claimAccount("clt_x") } returns ClaimResult.TimedOut

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(
                AccountClaimState.Failed("The server didn't answer. Try again."),
                viewModel.claimState.value,
            )
            coVerify(exactly = 1) { transportClient.claimAccount(any()) }
        }

    @Test
    fun `a cancelled sign-in sheet shows no error and returns to Idle for the next tap`() =
        runTest {
            // The user closed Google's sheet without signing in — a choice, not an error (plan
            // A1 review). Nothing is shown and the state is back to Idle, so the step shows its
            // sign-in button again instead of a stuck spinner.
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Cancelled

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(AccountClaimState.Idle, viewModel.claimState.value)
            coVerify(exactly = 0) { accountApiClient.mintClaimToken(any(), any(), any(), any()) }
            coVerify(exactly = 0) { transportClient.claimAccount(any()) }
        }

    @Test
    fun `a second tap after a cancelled sheet starts a new sign-in`() =
        runTest {
            // The single-flight guard must be released when the sheet is cancelled, so a second
            // tap starts a fresh sign-in chain.
            coEvery { googleSignInClient.signInInteractively(context) } returnsMany
                listOf(GoogleSignInResult.Cancelled, GoogleSignInResult.Success("id-token"))
            coEvery { accountApiClient.mintClaimToken("h", 1, false, "id-token") } returns
                ClaimTokenResult.Success(claimToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.claimAccount("clt_x") } returns ClaimResult.Claimed("acc_1")
            coEvery { accountApiClient.listConnections("h", 1, false, "id-token") } returns
                ConnectionsResult.Success(emptyList())
            coEvery { accountApiClient.listDevices("h", 1, false, "id-token") } returns
                DevicesResult.Success(devices = emptyList(), deviceLimit = null)

            viewModel.signInAndClaim(context)
            advanceUntilIdle()
            assertEquals(AccountClaimState.Idle, viewModel.claimState.value)

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            coVerify(exactly = 2) { googleSignInClient.signInInteractively(context) }
            coVerify(exactly = 1) { accountApiClient.mintClaimToken(any(), any(), any(), any()) }
            assertEquals(AccountClaimState.Claimed("acc_1"), viewModel.claimState.value)
        }

    @Test
    fun `a second signInAndClaim while one is in flight sends nothing`() =
        runTest {
            // One tap is one attempt (plan A1 review): while the first chain is still running
            // (suspended waiting for the claim token), a second tap starts nothing.
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            val gate = CompletableDeferred<Unit>()
            coEvery { accountApiClient.mintClaimToken(any(), any(), any(), any()) } coAnswers {
                gate.await()
                ClaimTokenResult.Success(claimToken = "clt_x", accountId = "acc_1")
            }

            viewModel.signInAndClaim(context)
            // The first chain is suspended inside mintClaimToken; a second tap must start nothing.
            viewModel.signInAndClaim(context)
            advanceUntilIdle()
            coVerify(exactly = 1) { googleSignInClient.signInInteractively(context) }

            gate.complete(Unit)
            advanceUntilIdle()
            coVerify(exactly = 1) { googleSignInClient.signInInteractively(context) }
            coVerify(exactly = 1) { accountApiClient.mintClaimToken(any(), any(), any(), any()) }
        }

    @Test
    fun `loadConnections tries silently first, only shows the picker if that finds nothing`() =
        runTest {
            coEvery { googleSignInClient.signInSilently(context) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { accountApiClient.listConnections("h", 1, false, "silent-token") } returns
                ConnectionsResult.Success(listOf(AccountConnection("c1", "Claude", "Claude", null, "2026-10-01")))

            viewModel.loadConnections(context, allowInteractive = true)
            advanceUntilIdle()

            val expected =
                ConnectionsState.Loaded(listOf(AccountConnection("c1", "Claude", "Claude", null, "2026-10-01")))
            assertEquals(expected, viewModel.connectionsState.value)
            coVerify(exactly = 0) { googleSignInClient.signInInteractively(context) }
        }

    @Test
    fun `loadConnections falls back to the picker when silent fails and interactive is allowed`() =
        runTest {
            coEvery { googleSignInClient.signInSilently(context) } returns
                GoogleSignInResult.NoCredential
            coEvery { googleSignInClient.signInInteractively(context) } returns
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
            coEvery { googleSignInClient.signInSilently(context) } returns
                GoogleSignInResult.NoCredential

            viewModel.loadConnections(context, allowInteractive = false)
            advanceUntilIdle()

            assertEquals(
                ConnectionsState.Failed("Sign in to view your AI connections"),
                viewModel.connectionsState.value,
            )
            coVerify(exactly = 0) { googleSignInClient.signInInteractively(context) }
        }

    @Test
    fun `revokeConnection removes the row from the loaded list on success`() =
        runTest {
            coEvery { googleSignInClient.signInSilently(context) } returns
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
            coEvery { googleSignInClient.signInSilently(context) } returns
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
            coEvery { googleSignInClient.signInSilently(context) } returns
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
            coEvery { googleSignInClient.signInSilently(context) } returns
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
            coEvery { googleSignInClient.signInSilently(context) } returns
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
            coEvery { googleSignInClient.signInSilently(context) } returns
                GoogleSignInResult.NoCredential

            viewModel.checkSession(context)
            advanceUntilIdle()

            assertEquals(AccountSessionState.SignedOut, viewModel.sessionState.value)
            coVerify(exactly = 0) { accountApiClient.listDevices(any(), any(), any(), any()) }
        }

    @Test
    fun `checkSession reports Unreachable when the silent sign-in itself fails`() =
        runTest {
            // A silent credential error (offline, a Google-side error) is a failure with its
            // reason — never "signed out" (plan A1 review): only NoCredential means SignedOut.
            coEvery { googleSignInClient.signInSilently(context) } returns
                GoogleSignInResult.Failed("Google sign-in failed - please try again")

            viewModel.checkSession(context)
            advanceUntilIdle()

            assertEquals(
                AccountSessionState.Unreachable("Google sign-in failed - please try again"),
                viewModel.sessionState.value,
            )
            coVerify(exactly = 0) { accountApiClient.listDevices(any(), any(), any(), any()) }
        }

    @Test
    fun `checkSession reports SignedOut on HTTP 401 and 403`() =
        runTest {
            coEvery { googleSignInClient.signInSilently(context) } returns
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
            coEvery { googleSignInClient.signInSilently(context) } returns
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
            coEvery { googleSignInClient.signInSilently(context) } returns
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
            coEvery { googleSignInClient.signInSilently(context) } returns
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
            coEvery { googleSignInClient.signInSilently(context) } returns
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

            coVerify(exactly = 1) { googleSignInClient.signInSilently(context) }
            gate.complete(Unit)
            advanceUntilIdle()
            coVerify(exactly = 1) { googleSignInClient.signInSilently(context) }
        }

    @Test
    fun `checkSession repopulates the in-memory profile from the silent fetch`() =
        runTest {
            coEvery { googleSignInClient.signInSilently(context) } returns
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
