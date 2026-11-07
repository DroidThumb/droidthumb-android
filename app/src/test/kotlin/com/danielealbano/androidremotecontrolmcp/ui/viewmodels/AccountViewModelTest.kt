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
import com.danielealbano.androidremotecontrolmcp.services.account.DevicesResult
import com.danielealbano.androidremotecontrolmcp.services.account.GoogleSignInClient
import com.danielealbano.androidremotecontrolmcp.services.account.GoogleSignInResult
import com.danielealbano.androidremotecontrolmcp.services.account.RenameResult
import com.danielealbano.androidremotecontrolmcp.services.account.SignInTokenResult
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceIdentityKeyStore
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceInfoProvider
import com.danielealbano.androidremotecontrolmcp.services.identity.deriveDeviceId
import com.danielealbano.androidremotecontrolmcp.services.transport.AiConnectionsResult
import com.danielealbano.androidremotecontrolmcp.services.transport.DeviceTransportClient
import com.danielealbano.androidremotecontrolmcp.services.transport.SignInResult
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
    private val transportStatusFlow =
        MutableStateFlow<TransportStatus>(TransportStatus.Connected(2, accounts = true, accountId = null))

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
    fun `signIn runs the full chain and ends SignedIn on success`() =
        runTest {
            val publicKeyBytes = byteArrayOf(1, 2, 3)
            every { deviceIdentityKeyStore.ensurePublicKeyBase64() } returns
                Base64.getEncoder().encodeToString(publicKeyBytes)
            val thisDeviceId = deriveDeviceId(publicKeyBytes)
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintSignInToken("h", 1, false, "id-token") } returns
                SignInTokenResult.Success(signInToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.signIn("clt_x") } returns SignInResult.SignedIn("acc_1")
            coEvery { transportClient.listAiConnections() } returns AiConnectionsResult.Connections(emptyList())
            coEvery { accountApiClient.listDevices("h", 1, false, "id-token") } returns
                DevicesResult.Success(listOf(AccountDevice(thisDeviceId, "2026-10-01", null)), deviceLimit = null)

            viewModel.signIn(context)
            advanceUntilIdle()

            assertEquals(AccountSignInState.SignedIn("acc_1"), viewModel.signInState.value)
            coVerify { settingsRepository.updateAccountId("acc_1") }
            // Plan A1 review: the post-sign-in authoritative check reuses the id token the
            // interactive sign-in just obtained and makes NO Google request at all — the silent
            // fetch is never called on this path.
            coVerify(exactly = 0) { googleSignInClient.signInSilently(context) }
            coVerify(exactly = 1) { googleSignInClient.signInInteractively(context) }
            coVerify(exactly = 1) { accountApiClient.listDevices("h", 1, false, "id-token") }
            // Plan A1b: after a successful sign-in the A1a session check runs, so the sign-in state
            // comes from the server's device list, not from the sign-in's own outcome alone.
            assertEquals(
                AccountSessionState.SignedIn(
                    linked = true,
                    device = AccountDevice(thisDeviceId, "2026-10-01", null),
                ),
                viewModel.sessionState.value,
            )
        }

    @Test
    fun `sign_in_rejected with accounts_unsupported surfaces AccountsUnsupported`() =
        runTest {
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintSignInToken(any(), any(), any(), any()) } returns
                SignInTokenResult.Success(signInToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.signIn("clt_x") } returns SignInResult.Rejected("accounts_unsupported")

            viewModel.signIn(context)
            advanceUntilIdle()

            assertEquals(AccountSignInState.AccountsUnsupported, viewModel.signInState.value)
            coVerify(exactly = 0) { settingsRepository.updateAccountId(any()) }
        }

    @Test
    fun `a welcome with accounts false surfaces AccountsUnsupported and sends nothing`() =
        runTest {
            transportStatusFlow.value = TransportStatus.Connected(2, accounts = false, accountId = null)
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintSignInToken(any(), any(), any(), any()) } returns
                SignInTokenResult.Success(signInToken = "clt_x", accountId = "acc_1")

            viewModel.signIn(context)
            advanceUntilIdle()

            assertEquals(AccountSignInState.AccountsUnsupported, viewModel.signInState.value)
            coVerify(exactly = 0) { transportClient.signIn(any()) }
            coVerify(exactly = 0) { settingsRepository.updateAccountId(any()) }
        }

    @Test
    fun `signIn stops at Failed when Google sign-in itself fails`() =
        runTest {
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Failed("denied")

            viewModel.signIn(context)
            advanceUntilIdle()

            assertEquals(AccountSignInState.Failed("Google sign-in failed: denied"), viewModel.signInState.value)
            coVerify(exactly = 0) { accountApiClient.mintSignInToken(any(), any(), any(), any()) }
        }

    @Test
    fun `sign-in requested while Connecting waits for Connected, then sends the sign-in exactly once`() =
        runTest {
            // Plan A1b: the sign-in goes over the WebSocket. While the transport isn't Connected the
            // state shows WaitingForConnection ("Connecting to the server…") and the sign-in is sent
            // exactly once, only after the status flow emits Connected - no timer, no polling.
            transportStatusFlow.value = TransportStatus.Connecting
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintSignInToken("h", 1, false, "id-token") } returns
                SignInTokenResult.Success(signInToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.signIn("clt_x") } returns SignInResult.SignedIn("acc_1")
            // The SignedIn branch runs the connections load (over the phone connection) and the
            // authoritative session check, which reuses the sign-in's own id token (plan A1 review).
            coEvery { transportClient.listAiConnections() } returns AiConnectionsResult.Connections(emptyList())
            coEvery { accountApiClient.listDevices("h", 1, false, "id-token") } returns
                DevicesResult.Success(devices = emptyList(), deviceLimit = null)

            viewModel.signIn(context)
            advanceUntilIdle()

            assertEquals(AccountSignInState.WaitingForConnection, viewModel.signInState.value)
            coVerify(exactly = 0) { transportClient.signIn(any()) }

            transportStatusFlow.value = TransportStatus.Connected(2, accounts = true, accountId = null)
            advanceUntilIdle()

            coVerify(exactly = 1) { transportClient.signIn("clt_x") }
            assertEquals(AccountSignInState.SignedIn("acc_1"), viewModel.signInState.value)
        }

    @Test
    fun `sign-in after Replaced restarts the stopped transport, then sends the sign-in`() =
        runTest {
            // A 4004 close stopped the transport's reconnect loop for good, so the plain
            // wait-for-Connected path would hang forever; the sign-in restarts it from the
            // settings' transport config instead, and the Connected that brings sends the sign-in.
            transportStatusFlow.value = TransportStatus.Replaced
            every { transportClient.start(any(), any(), any()) } answers {
                transportStatusFlow.value = TransportStatus.Connected(2, accounts = true, accountId = null)
            }
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintSignInToken("h", 1, false, "id-token") } returns
                SignInTokenResult.Success(signInToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.signIn("clt_x") } returns SignInResult.SignedIn("acc_1")
            coEvery { transportClient.listAiConnections() } returns AiConnectionsResult.Connections(emptyList())
            coEvery { accountApiClient.listDevices("h", 1, false, "id-token") } returns
                DevicesResult.Success(devices = emptyList(), deviceLimit = null)

            viewModel.signIn(context)
            advanceUntilIdle()

            coVerify(exactly = 1) { transportClient.start("h", 1, false) }
            coVerify(exactly = 1) { transportClient.signIn("clt_x") }
            assertEquals(AccountSignInState.SignedIn("acc_1"), viewModel.signInState.value)
        }

    @Test
    fun `sign-in requested while paused shows the paused message and sends nothing`() =
        runTest {
            coEvery { pauseSettings.getPauseState() } returns PauseState(isPaused = true, resumeAtEpochMs = null)
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintSignInToken("h", 1, false, "id-token") } returns
                SignInTokenResult.Success(signInToken = "clt_x", accountId = "acc_1")

            viewModel.signIn(context)
            advanceUntilIdle()

            assertEquals(
                AccountSignInState.Failed("Your connection is paused. Resume it to link this phone."),
                viewModel.signInState.value,
            )
            coVerify(exactly = 0) { transportClient.signIn(any()) }
        }

    @Test
    fun `sign_in_rejected shows the server's reason`() =
        runTest {
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintSignInToken(any(), any(), any(), any()) } returns
                SignInTokenResult.Success(signInToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.signIn("clt_x") } returns SignInResult.Rejected("token_expired")

            viewModel.signIn(context)
            advanceUntilIdle()

            assertEquals(
                AccountSignInState.Failed("Sign-in rejected: token_expired"),
                viewModel.signInState.value,
            )
        }

    @Test
    fun `an exception at the Google credential step shows that step's reason and is not stuck in SigningIn`() =
        runTest {
            coEvery { googleSignInClient.signInInteractively(context) } throws
                RuntimeException("credential manager broke")

            viewModel.signIn(context)
            advanceUntilIdle()

            assertEquals(
                AccountSignInState.Failed("Google sign-in failed: credential manager broke"),
                viewModel.signInState.value,
            )
        }

    @Test
    fun `an exception at the sign-in token step shows that step's reason`() =
        runTest {
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintSignInToken(any(), any(), any(), any()) } throws
                RuntimeException("mint exploded")

            viewModel.signIn(context)
            advanceUntilIdle()

            assertEquals(
                AccountSignInState.Failed("Sign-in token failed: mint exploded"),
                viewModel.signInState.value,
            )
            coVerify(exactly = 0) { transportClient.signIn(any()) }
        }

    @Test
    fun `an exception at the sign-in step shows that step's reason`() =
        runTest {
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintSignInToken(any(), any(), any(), any()) } returns
                SignInTokenResult.Success(signInToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.signIn("clt_x") } throws RuntimeException("socket gone")

            viewModel.signIn(context)
            advanceUntilIdle()

            assertEquals(
                AccountSignInState.Failed("Sign-in failed: socket gone"),
                viewModel.signInState.value,
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

            viewModel.signIn(context)
            advanceUntilIdle()

            assertEquals(AccountSignInState.SigningIn, viewModel.signInState.value)
        }

    @Test
    fun `the sign-in's bounded reply wait expiring shows the no-answer message and never retries`() =
        runTest {
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintSignInToken(any(), any(), any(), any()) } returns
                SignInTokenResult.Success(signInToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.signIn("clt_x") } returns SignInResult.TimedOut

            viewModel.signIn(context)
            advanceUntilIdle()

            assertEquals(
                AccountSignInState.Failed("The server didn't answer. Try again."),
                viewModel.signInState.value,
            )
            coVerify(exactly = 1) { transportClient.signIn(any()) }
        }

    @Test
    fun `a cancelled sign-in sheet shows no error and returns to Idle for the next tap`() =
        runTest {
            // The user closed Google's sheet without signing in — a choice, not an error (plan
            // A1 review). Nothing is shown and the state is back to Idle, so the step shows its
            // sign-in button again instead of a stuck spinner.
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Cancelled

            viewModel.signIn(context)
            advanceUntilIdle()

            assertEquals(AccountSignInState.Idle, viewModel.signInState.value)
            coVerify(exactly = 0) { accountApiClient.mintSignInToken(any(), any(), any(), any()) }
            coVerify(exactly = 0) { transportClient.signIn(any()) }
        }

    @Test
    fun `a second tap after a cancelled sheet starts a new sign-in`() =
        runTest {
            // The single-flight guard must be released when the sheet is cancelled, so a second
            // tap starts a fresh sign-in chain.
            coEvery { googleSignInClient.signInInteractively(context) } returnsMany
                listOf(GoogleSignInResult.Cancelled, GoogleSignInResult.Success("id-token"))
            coEvery { accountApiClient.mintSignInToken("h", 1, false, "id-token") } returns
                SignInTokenResult.Success(signInToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.signIn("clt_x") } returns SignInResult.SignedIn("acc_1")
            coEvery { transportClient.listAiConnections() } returns AiConnectionsResult.Connections(emptyList())
            coEvery { accountApiClient.listDevices("h", 1, false, "id-token") } returns
                DevicesResult.Success(devices = emptyList(), deviceLimit = null)

            viewModel.signIn(context)
            advanceUntilIdle()
            assertEquals(AccountSignInState.Idle, viewModel.signInState.value)

            viewModel.signIn(context)
            advanceUntilIdle()

            coVerify(exactly = 2) { googleSignInClient.signInInteractively(context) }
            coVerify(exactly = 1) { accountApiClient.mintSignInToken(any(), any(), any(), any()) }
            assertEquals(AccountSignInState.SignedIn("acc_1"), viewModel.signInState.value)
        }

    @Test
    fun `a second signIn while one is in flight sends nothing`() =
        runTest {
            // One tap is one attempt (plan A1 review): while the first chain is still running
            // (suspended waiting for the sign-in token), a second tap starts nothing.
            coEvery { googleSignInClient.signInInteractively(context) } returns
                GoogleSignInResult.Success("id-token")
            val gate = CompletableDeferred<Unit>()
            coEvery { accountApiClient.mintSignInToken(any(), any(), any(), any()) } coAnswers {
                gate.await()
                SignInTokenResult.Success(signInToken = "clt_x", accountId = "acc_1")
            }

            viewModel.signIn(context)
            // The first chain is suspended inside mintSignInToken; a second tap must start nothing.
            viewModel.signIn(context)
            advanceUntilIdle()
            coVerify(exactly = 1) { googleSignInClient.signInInteractively(context) }

            gate.complete(Unit)
            advanceUntilIdle()
            coVerify(exactly = 1) { googleSignInClient.signInInteractively(context) }
            coVerify(exactly = 1) { accountApiClient.mintSignInToken(any(), any(), any(), any()) }
        }

    @Test
    fun `checkSession reports SignedIn linked when the device list includes this phone`() =
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
                    linked = true,
                    device = AccountDevice(thisDeviceId, "2026-10-01", null),
                ),
                viewModel.sessionState.value,
            )
        }

    @Test
    fun `checkSession reports SignedIn not linked when the device list does not include this phone`() =
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
                AccountSessionState.SignedIn(linked = false, device = null),
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
    fun `signOut clears the local account association and asks the transport to sign out`() =
        runTest {
            coEvery { googleSignInClient.signOut(context) } returns Unit
            coEvery { transportClient.signOut() } returns true

            viewModel.signOut(context)
            advanceUntilIdle()

            coVerify { googleSignInClient.signOut(context) }
            coVerify { settingsRepository.clearAccountId() }
            coVerify { transportClient.signOut() }
            assertEquals(AccountSignInState.Idle, viewModel.signInState.value)
            assertEquals(ConnectionsState.Idle, viewModel.connectionsState.value)
            assertEquals(AccountSessionState.SignedOut, viewModel.sessionState.value)
        }
}
