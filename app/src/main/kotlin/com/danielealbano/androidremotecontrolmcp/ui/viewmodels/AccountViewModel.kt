package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.TransportConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.PauseSettings
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
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
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** State of the sign-in -> claim-token -> claim_account chain (design doc D-33). */
sealed interface AccountClaimState {
    data object Idle : AccountClaimState

    data object SigningIn : AccountClaimState

    data object Claiming : AccountClaimState

    data class Claimed(
        val accountId: String,
    ) : AccountClaimState

    /** The claim is ready to send but the transport isn't `Connected` yet — the claim goes over
     *  the WebSocket once the status flow next emits `Connected` (plan A1b: wait on that event,
     *  no timer, no polling). */
    data object WaitingForConnection : AccountClaimState

    /** A different account already claimed this device — a real, expected outcome, not an error. */
    data object AlreadyClaimedByOther : AccountClaimState

    data class Failed(
        val message: String,
    ) : AccountClaimState
}

/** State of fetching the account's AI connections (D-37/D-38). */
sealed interface ConnectionsState {
    data object Idle : ConnectionsState

    data object Loading : ConnectionsState

    data class Loaded(
        val connections: List<AccountConnection>,
    ) : ConnectionsState

    data class Failed(
        val message: String,
    ) : ConnectionsState
}

/** Display-only fields for the account avatar menu (plan 70 US3) — parsed client-side from the
 *  signed-in Google credential, refreshed whenever a fresh one is obtained; never persisted or
 *  sent anywhere itself (the server only ever sees the id_token). */
data class AccountProfile(
    val displayName: String?,
    val email: String?,
    val profilePictureUri: String?,
)

/** The single source of truth for the app's sign-in state (plan A1a): derived only from the
 *  server's answer to `POST /v1/accounts/devices` after a fresh silent Google id token — never
 *  from timers, cached flags or blind retries. Home, the avatar menu and the This device card
 *  all read this one state. */
sealed interface AccountSessionState {
    data object Checking : AccountSessionState

    /** [claimed] `true` means this phone's derived device id is in the server's returned device
     *  list, and [device] is that device's own record; `false` (and a `null` [device]) means the
     *  server accepted the token but this phone isn't linked to the account yet. */
    data class SignedIn(
        val claimed: Boolean,
        val device: AccountDevice? = null,
    ) : AccountSessionState

    data object SignedOut : AccountSessionState

    data class Unreachable(
        val reason: String,
    ) : AccountSessionState

    data class ServerError(
        val httpStatus: Int,
    ) : AccountSessionState
}

@HiltViewModel
class AccountViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val googleSignInClient: GoogleSignInClient,
        private val accountApiClient: AccountApiClient,
        private val transportClient: DeviceTransportClient,
        private val pauseSettings: PauseSettings,
        private val deviceIdentityKeyStore: DeviceIdentityKeyStore,
        deviceInfoProvider: DeviceInfoProvider,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        /** This phone's own human-readable name ("This device" section, plan 70 US3) — a plain
         *  sync value, not a flow, since [DeviceInfoProvider] reads immutable `Build.*` fields. */
        val deviceModel: String = deviceInfoProvider.deviceModel

        val accountId: StateFlow<String?> =
            settingsRepository.accountId
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        private val _claimState = MutableStateFlow<AccountClaimState>(AccountClaimState.Idle)
        val claimState: StateFlow<AccountClaimState> = _claimState.asStateFlow()

        private val _connectionsState = MutableStateFlow<ConnectionsState>(ConnectionsState.Idle)
        val connectionsState: StateFlow<ConnectionsState> = _connectionsState.asStateFlow()

        private val _accountProfile = MutableStateFlow<AccountProfile?>(null)
        val accountProfile: StateFlow<AccountProfile?> = _accountProfile.asStateFlow()

        private val _sessionState = MutableStateFlow<AccountSessionState>(AccountSessionState.Checking)
        val sessionState: StateFlow<AccountSessionState> = _sessionState.asStateFlow()

        /** Single-flight guard for [checkSession]: only one authoritative check at a time — a new
         *  trigger while one is in flight does not start another. */
        private val sessionCheckInFlight = AtomicBoolean(false)

        /** Every [AccountSessionState] transition is logged (plan A1b) — state class names only;
         *  never tokens, emails, codes or screen content. */
        private fun setSessionState(state: AccountSessionState) {
            Logger.i(
                TAG,
                "Session state: ${_sessionState.value::class.java.simpleName} -> ${state::class.java.simpleName}",
            )
            _sessionState.value = state
        }

        /** Runs the full Google sign-in -> claim-token -> claim_account chain (design doc D-33).
         *  `context` must be an Activity context — Credential Manager's account picker UI needs it.
         *
         *  Plan A1b: every step is guarded — a failure shows which step failed and the real reason,
         *  and never leaves the state on "Signing in". Cancellation (the coroutine cancelled while
         *  Google's sheet was in front, or the Activity went away) is NOT shown as an error: the
         *  state is left alone and the next A1a session check, when the app is next in front, shows
         *  the real state. The claim goes over the WebSocket: [sendClaimWhenConnected] waits on the
         *  status flow's next `Connected` emission when the transport isn't connected yet, and
         *  sends nothing at all when the connection is paused. */
        fun signInAndClaim(context: Context) {
            viewModelScope.launch(ioDispatcher) {
                _claimState.value = AccountClaimState.SigningIn
                Logger.i(TAG, "Sign-in started")
                val config = settingsRepository.getTransportConfig()
                val idToken =
                    obtainInteractiveIdToken(context, googleSignInClient, _accountProfile, _claimState)
                        ?: return@launch
                val claimToken =
                    mintClaimTokenForSignIn(idToken, accountApiClient, config, _claimState) ?: return@launch
                _claimState.value = AccountClaimState.Claiming
                val claimResult =
                    guardedStep("Claim", _claimState) {
                        sendClaimWhenConnected(claimToken, transportClient, pauseSettings, _claimState)
                    } ?: return@launch
                finishClaim(context, idToken, claimResult)
            }
        }

        /** Applies the server's claim reply (plan A1b): the reply decides. A rejection shows the
         *  server's own reason; the bounded reply wait expiring is reported as a failure and never
         *  retried on its own. */
        private suspend fun finishClaim(
            context: Context,
            idToken: String,
            claimResult: ClaimResult,
        ) {
            when (claimResult) {
                is ClaimResult.Claimed -> {
                    settingsRepository.updateAccountId(claimResult.accountId)
                    _claimState.value = AccountClaimState.Claimed(claimResult.accountId)
                    Logger.i(TAG, "Claim ok")
                    // HomeScreen's own LaunchedEffect(accountId) guards on claimState not yet
                    // being Claimed (it only auto-refreshes once, on a cold start) - by the time
                    // its recomposition sees the new accountId, claimState above is already
                    // Claimed, so that effect never fires for a fresh claim. Both loads here
                    // are this path's own responsibility, not something the effect also covers.
                    // Reuse the SAME idToken this interactive sign-in just obtained, rather than
                    // going through loadConnections(allowInteractive = true)/freshIdTokenOrNull,
                    // which would try Credential Manager's *silent* request first - immediately
                    // after an interactive grant, Google's own account-authorization state isn't
                    // guaranteed to have propagated yet, so that silent attempt can fail and fall
                    // straight back to a SECOND interactive picker, right after the first one
                    // (founder phone-test feedback, PR #9 round 1 - "double sign-in").
                    loadConnectionsWithToken(idToken)
                    // The This device card's state comes from the same authoritative session
                    // check everything else reads, not a second device-list call with this
                    // token (plan A1a/A1b: after a sign-in completes, the A1a check runs).
                    checkSession(context)
                }

                is ClaimResult.Rejected -> {
                    Logger.w(TAG, "Claim rejected by the server")
                    _claimState.value =
                        if (claimResult.reason == "already_claimed") {
                            AccountClaimState.AlreadyClaimedByOther
                        } else {
                            // The server's own reason is shown, verbatim (plan A1b).
                            AccountClaimState.Failed("Claim rejected: ${claimResult.reason}")
                        }
                }

                is ClaimResult.TimedOut -> {
                    // The bounded wait for the reply expired. Reported as a failure, never
                    // retried on its own (plan A1b).
                    Logger.w(TAG, "Claim timed out waiting for the server's reply")
                    _claimState.value = AccountClaimState.Failed("The server didn't answer. Try again.")
                }

                is ClaimResult.NotConnected -> {
                    // sendClaimWhenConnected only sends once Connected, so this is a narrow
                    // race with session teardown. Shown as a failure, never silently retried.
                    Logger.w(TAG, "Claim not sent: the transport dropped before the send")
                    _claimState.value = AccountClaimState.Failed("Not connected to the server")
                }
            }
        }

        /** Fetches the account's AI connections, re-using an already-signed-in Google account
         *  silently (no UI) wherever Credential Manager allows it — see [GoogleSignInClient.signIn].
         *  [allowInteractive] must be `false` for any call this device owner didn't directly
         *  trigger (e.g. an automatic refresh on opening the app) — being claimed is this device's
         *  own persisted, durable state (design doc D-33), unrelated to whether Credential Manager
         *  still considers the Google session "silent"; an automatic background refresh must never
         *  surface Google's own account-picker UI on its own, or every cold start (app reopened,
         *  phone rebooted) would look like being signed out and asked to sign back in again, purely
         *  because a background connections-list refresh happened to need a fresh ID token
         *  (founder feedback, PR #8 round 5). `true` is for the cases the user actually asked for
         *  this: the "Retry" button, and right after [signInAndClaim] itself just finished its own
         *  interactive sign-in. */
        fun loadConnections(
            context: Context,
            allowInteractive: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) {
                _connectionsState.value = ConnectionsState.Loading
                val idToken = freshIdTokenOrNull(context, allowInteractive)
                if (idToken == null) {
                    _connectionsState.value = ConnectionsState.Failed("Sign in to view your AI connections")
                    return@launch
                }
                loadConnectionsWithToken(idToken)
            }
        }

        /** Does the actual fetch once a caller already has a usable `idToken` in hand - [loadConnections]
         *  delegates to this after obtaining one itself; [signInAndClaim] calls it directly with the
         *  token its own interactive sign-in just produced, skipping a second Credential Manager
         *  round-trip entirely. */
        private suspend fun loadConnectionsWithToken(idToken: String) {
            _connectionsState.value = ConnectionsState.Loading
            val config = settingsRepository.getTransportConfig()
            when (val result = accountApiClient.listConnections(config.host, config.port, config.tls, idToken)) {
                is ConnectionsResult.Success -> {
                    _connectionsState.value = ConnectionsState.Loaded(result.connections)
                }

                is ConnectionsResult.Failed -> {
                    _connectionsState.value = ConnectionsState.Failed(result.message)
                }
            }
        }

        fun renameConnection(
            context: Context,
            clientId: String,
            displayName: String,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val idToken = freshIdTokenOrNull(context, allowInteractive = true) ?: return@launch
                val config = settingsRepository.getTransportConfig()
                val result =
                    accountApiClient.renameConnection(
                        config.host,
                        config.port,
                        config.tls,
                        idToken,
                        clientId,
                        displayName,
                    )
                when (result) {
                    RenameResult.Updated, RenameResult.NotFound -> {
                        val current = _connectionsState.value
                        if (current is ConnectionsState.Loaded) {
                            _connectionsState.value =
                                ConnectionsState.Loaded(
                                    current.connections.map {
                                        if (it.clientId == clientId) it.copy(displayName = displayName) else it
                                    },
                                )
                        }
                    }

                    is RenameResult.Failed -> {
                        Unit
                    } // leave the list as-is; the detail screen's own retry is the recovery path
                }
            }
        }

        /** The authoritative session check (plan A1a): one silent Google id token fetch, then
         *  `POST /v1/accounts/devices`. Runs when Home starts, when the app resumes, after a
         *  sign-in completes and when the user taps Retry. Single-flight via
         *  [sessionCheckInFlight]: a trigger while one check is in flight starts nothing. There
         *  is deliberately NO retry, timer or second attempt anywhere: one trigger is one
         *  attempt, and the state shown always comes from this check's result. */
        @Suppress("TooGenericExceptionCaught")
        fun checkSession(context: Context) {
            if (!sessionCheckInFlight.compareAndSet(false, true)) return
            viewModelScope.launch(ioDispatcher) {
                try {
                    setSessionState(AccountSessionState.Checking)
                    val idToken =
                        when (val signIn = googleSignInClient.signIn(context, filterByAuthorizedAccounts = true)) {
                            is GoogleSignInResult.Success -> {
                                // The silent fetch also repopulates the in-memory profile, so the
                                // avatar is right even straight after process death.
                                _accountProfile.value =
                                    AccountProfile(signIn.displayName, signIn.email, signIn.profilePictureUri)
                                signIn.idToken
                            }

                            is GoogleSignInResult.NoCredential -> {
                                setSessionState(AccountSessionState.SignedOut)
                                return@launch
                            }

                            is GoogleSignInResult.Failed -> {
                                setSessionState(AccountSessionState.Unreachable(signIn.message))
                                return@launch
                            }
                        }

                    val config = settingsRepository.getTransportConfig()
                    when (val result = accountApiClient.listDevices(config.host, config.port, config.tls, idToken)) {
                        is DevicesResult.Success -> {
                            // currentDeviceId() touches the Android Keystore, which can throw on
                            // real devices; the outer catch maps that to Unreachable (its class)
                            // so the state never sticks on Checking.
                            val thisDeviceId = currentDeviceId()
                            val device = result.devices.firstOrNull { it.deviceId == thisDeviceId }
                            setSessionState(
                                AccountSessionState.SignedIn(claimed = device != null, device = device),
                            )
                        }

                        is DevicesResult.Failed -> {
                            setSessionState(sessionStateFor(result))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setSessionState(AccountSessionState.Unreachable(e::class.java.simpleName))
                } finally {
                    sessionCheckInFlight.set(false)
                }
            }
        }

        /** Clears this device's local account association ("Sign out", plan 70 US3's account
         *  avatar menu) — mirrors [GoogleSignInClient.signOut]'s own contract: nothing server-side
         *  is un-claimed, so signing back in with the same Google account reaches this same device. */
        fun signOut(context: Context) {
            viewModelScope.launch(ioDispatcher) {
                googleSignInClient.signOut(context)
                settingsRepository.clearAccountId()
                _accountProfile.value = null
                _claimState.value = AccountClaimState.Idle
                _connectionsState.value = ConnectionsState.Idle
                // The next authoritative check (next Home resume / Retry) re-derives this from the
                // server; SignedOut is the state this device is in until then (no credential left).
                setSessionState(AccountSessionState.SignedOut)
            }
        }

        private fun currentDeviceId(): String {
            val publicKeyDer = Base64.getDecoder().decode(deviceIdentityKeyStore.ensurePublicKeyBase64())
            return deriveDeviceId(publicKeyDer)
        }

        fun revokeConnection(
            context: Context,
            clientId: String,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val idToken = freshIdTokenOrNull(context, allowInteractive = true) ?: return@launch
                val config = settingsRepository.getTransportConfig()
                when (accountApiClient.revokeConnection(config.host, config.port, config.tls, idToken, clientId)) {
                    RevokeResult.Revoked, RevokeResult.NotFound -> {
                        // NotFound means it's already gone (e.g. revoked elsewhere) - the list
                        // should not show it either way, so the outcome for this screen is the same.
                        val current = _connectionsState.value
                        if (current is ConnectionsState.Loaded) {
                            _connectionsState.value =
                                ConnectionsState.Loaded(current.connections.filterNot { it.clientId == clientId })
                        }
                    }

                    is RevokeResult.Failed -> {
                        Unit
                    } // leave the list as-is; the row's own retry is the recovery path
                }
            }
        }

        /** [GoogleSignInClient.signIn] silently first (`filterByAuthorizedAccounts = true`); only
         *  shows the account picker if that finds nothing AND [allowInteractive] permits it — see
         *  [loadConnections]'s own doc comment for why an automatic call must pass `false`. */
        private suspend fun freshIdTokenOrNull(
            context: Context,
            allowInteractive: Boolean,
        ): String? {
            val silent = googleSignInClient.signIn(context, filterByAuthorizedAccounts = true)
            if (silent is GoogleSignInResult.Success) {
                _accountProfile.value = AccountProfile(silent.displayName, silent.email, silent.profilePictureUri)
                return silent.idToken
            }
            val interactive =
                if (allowInteractive) {
                    googleSignInClient.signIn(context, filterByAuthorizedAccounts = false)
                } else {
                    null
                }
            if (interactive is GoogleSignInResult.Success) {
                _accountProfile.value =
                    AccountProfile(interactive.displayName, interactive.email, interactive.profilePictureUri)
            }
            return (interactive as? GoogleSignInResult.Success)?.idToken
        }

        private companion object {
            private const val STOP_TIMEOUT_MS = 5000L
        }
    }

private const val TAG = "MCP:Account"

/** Runs one chain step (plan A1b): any exception other than cancellation is caught, logged with
 *  the step name plus the exception class and message, and shown as a Failed claim state naming
 *  the step — the state never stays on "Signing in" after a failure. Cancellation is rethrown so
 *  it is not shown as an error. Top-level so [AccountViewModel] stays within detekt's
 *  function-count limit; it touches only what it is given. */
@Suppress("TooGenericExceptionCaught")
private suspend fun <T> guardedStep(
    step: String,
    claimState: MutableStateFlow<AccountClaimState>,
    block: suspend () -> T,
): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "Sign-in step \"$step\" failed: ${e::class.java.simpleName}: ${e.message}")
        claimState.value =
            AccountClaimState.Failed("$step failed: ${e.message ?: e::class.java.simpleName}")
        null
    }

/** The Google credential step of the sign-in chain (plan A1b): one interactive sign-in. `null`
 *  means the chain stops here (the Failed state was already set). */
private suspend fun obtainInteractiveIdToken(
    context: Context,
    googleSignInClient: GoogleSignInClient,
    accountProfile: MutableStateFlow<AccountProfile?>,
    claimState: MutableStateFlow<AccountClaimState>,
): String? =
    when (
        val result =
            guardedStep("Google sign-in", claimState) {
                googleSignInClient.signIn(context, filterByAuthorizedAccounts = false)
            }
    ) {
        null -> {
            null
        }

        is GoogleSignInResult.Success -> {
            accountProfile.value =
                AccountProfile(result.displayName, result.email, result.profilePictureUri)
            Logger.i(TAG, "Google sign-in ok")
            result.idToken
        }

        is GoogleSignInResult.NoCredential -> {
            Logger.w(TAG, "Sign-in stopped at Google sign-in: no credential")
            claimState.value = AccountClaimState.Failed("No Google account was selected")
            null
        }

        is GoogleSignInResult.Failed -> {
            Logger.w(TAG, "Sign-in stopped at Google sign-in: ${result.message}")
            claimState.value = AccountClaimState.Failed("Google sign-in failed: ${result.message}")
            null
        }
    }

/** The claim-token step of the sign-in chain (plan A1b). `null` means the chain stops here. */
private suspend fun mintClaimTokenForSignIn(
    idToken: String,
    accountApiClient: AccountApiClient,
    config: TransportConfig,
    claimState: MutableStateFlow<AccountClaimState>,
): String? =
    when (
        val result =
            guardedStep("Claim token", claimState) {
                accountApiClient.mintClaimToken(config.host, config.port, config.tls, idToken)
            }
    ) {
        null -> {
            null
        }

        is ClaimTokenResult.Success -> {
            Logger.i(TAG, "Claim token ok")
            result.claimToken
        }

        is ClaimTokenResult.Failed -> {
            Logger.w(TAG, "Sign-in stopped at claim token: ${result.message}")
            claimState.value = AccountClaimState.Failed("Claim token failed: ${result.message}")
            null
        }
    }

/** Sends the claim over the WebSocket (plan A1b). If the transport is already
 *  [TransportStatus.Connected] the claim is sent straight away; otherwise the state shows
 *  [AccountClaimState.WaitingForConnection] ("Connecting to the server…") and this waits for the
 *  status StateFlow's NEXT `Connected` emission — no timer, no polling — so the claim is sent
 *  exactly once, only after `Connected`. If the owner paused the connection (the app's Pause),
 *  nothing is sent and the paused message is shown. The existing bounded wait for the server's
 *  reply inside [DeviceTransportClient.claimAccount] stays as-is; `null` here means the chain is
 *  done (the state was already set). */
private suspend fun sendClaimWhenConnected(
    claimToken: String,
    transportClient: DeviceTransportClient,
    pauseSettings: PauseSettings,
    claimState: MutableStateFlow<AccountClaimState>,
): ClaimResult? {
    if (pauseSettings.getPauseState().isEffectivePause(System.currentTimeMillis())) {
        Logger.w(TAG, "Claim not sent: the connection is paused")
        claimState.value = AccountClaimState.Failed("Your connection is paused. Resume it to link this phone.")
        return null
    }
    if (transportClient.status.value !is TransportStatus.Connected) {
        Logger.i(TAG, "Claim waiting for the transport to connect")
        claimState.value = AccountClaimState.WaitingForConnection
        transportClient.status.filterIsInstance<TransportStatus.Connected>().first()
        Logger.i(TAG, "Transport connected; sending the claim")
    } else {
        Logger.i(TAG, "Sending the claim")
    }
    return transportClient.claimAccount(claimToken)
}

/** Maps a failed device-list call to the session state it means — [ApiErrorKind.Rejected] (HTTP
 *  401/403) means the token was rejected, so SignedOut; [ApiErrorKind.Server] is the server's own
 *  problem; [ApiErrorKind.Network] is an IOException of any kind (the client records the
 *  exception's class for logging) — a short reason for the screen. */
private fun sessionStateFor(result: DevicesResult.Failed): AccountSessionState =
    when (result.kind) {
        ApiErrorKind.Rejected -> {
            AccountSessionState.SignedOut
        }

        ApiErrorKind.Server -> {
            AccountSessionState.ServerError(result.httpStatus ?: 0)
        }

        ApiErrorKind.Network -> {
            AccountSessionState.Unreachable(
                result.message ?: result.exceptionClass ?: "network unreachable",
            )
        }
    }
