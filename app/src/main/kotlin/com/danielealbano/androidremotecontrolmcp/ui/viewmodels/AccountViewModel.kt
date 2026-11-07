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
import com.danielealbano.androidremotecontrolmcp.services.account.DevicesResult
import com.danielealbano.androidremotecontrolmcp.services.account.GoogleSignInClient
import com.danielealbano.androidremotecontrolmcp.services.account.GoogleSignInResult
import com.danielealbano.androidremotecontrolmcp.services.account.RenameResult
import com.danielealbano.androidremotecontrolmcp.services.account.SignInTokenResult
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceIdentityKeyStore
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceInfoProvider
import com.danielealbano.androidremotecontrolmcp.services.identity.deriveDeviceId
import com.danielealbano.androidremotecontrolmcp.services.transport.AiConnectionsResult
import com.danielealbano.androidremotecontrolmcp.services.transport.AiRevokeResult
import com.danielealbano.androidremotecontrolmcp.services.transport.DeviceTransportClient
import com.danielealbano.androidremotecontrolmcp.services.transport.SignInResult
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

/** State of the sign-in -> sign-in-token -> sign_in chain (design doc D-33). */
sealed interface AccountSignInState {
    data object Idle : AccountSignInState

    data object SigningIn : AccountSignInState

    data object SigningInToServer : AccountSignInState

    data class SignedIn(
        val accountId: String,
    ) : AccountSignInState

    /** The sign-in is ready to send but the transport isn't `Connected` yet — the sign-in goes
     *  over the WebSocket once the status flow next emits `Connected` (plan A1b: wait on that
     *  event, no timer, no polling). */
    data object WaitingForConnection : AccountSignInState

    /** The server has no accounts (`welcome.accounts == false`, or `sign_in_rejected` with reason
     *  `accounts_unsupported`) — sign-in isn't offered and nothing was sent. */
    data object AccountsUnsupported : AccountSignInState

    data class Failed(
        val message: String,
    ) : AccountSignInState
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

    /** [linked] `true` means this phone's derived device id is in the server's returned device
     *  list, and [device] is that device's own record; `false` (and a `null` [device]) means the
     *  server accepted the token but this phone isn't signed in to the account yet. */
    data class SignedIn(
        val linked: Boolean,
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

        private val _signInState = MutableStateFlow<AccountSignInState>(AccountSignInState.Idle)
        val signInState: StateFlow<AccountSignInState> = _signInState.asStateFlow()

        private val _connectionsState = MutableStateFlow<ConnectionsState>(ConnectionsState.Idle)
        val connectionsState: StateFlow<ConnectionsState> = _connectionsState.asStateFlow()

        private val _accountProfile = MutableStateFlow<AccountProfile?>(null)
        val accountProfile: StateFlow<AccountProfile?> = _accountProfile.asStateFlow()

        private val _sessionState = MutableStateFlow<AccountSessionState>(AccountSessionState.Checking)
        val sessionState: StateFlow<AccountSessionState> = _sessionState.asStateFlow()

        /** Why the last revoke failed, shown on the AI client detail screen under Remove — `null`
         *  when no revoke has failed since the last attempt started. Cleared when a revoke starts. */
        private val _revokeError = MutableStateFlow<String?>(null)
        val revokeError: StateFlow<String?> = _revokeError.asStateFlow()

        /** Single-flight guard for [checkSession]: only one authoritative check at a time — a new
         *  trigger while one is in flight does not start another. */
        private val sessionCheckInFlight = AtomicBoolean(false)

        /** Single-flight guard for [signIn]: only one sign-in chain at a time — a tap
         *  while one is in flight (e.g. still waiting for the connection) starts nothing, so the
         *  server can never receive two sign-ins from one button (plan A1 review). */
        private val signInInFlight = AtomicBoolean(false)

        /** Every [AccountSessionState] transition is logged (plan A1b) — state class names only;
         *  never tokens, emails, codes or screen content. */
        private fun setSessionState(state: AccountSessionState) {
            Logger.i(
                TAG,
                "Session state: ${_sessionState.value::class.java.simpleName} -> ${state::class.java.simpleName}",
            )
            _sessionState.value = state
        }

        /** Runs the full Google sign-in -> sign-in-token -> sign_in chain (design doc D-33).
         *  `context` must be an Activity context — Credential Manager's account picker UI needs it.
         *
         *  Plan A1b: every step is guarded — a failure shows which step failed and the real reason,
         *  and never leaves the state on "Signing in". Cancellation (the coroutine cancelled while
         *  Google's sheet was in front, or the Activity went away) is NOT shown as an error: the
         *  state is left alone and the next A1a session check, when the app is next in front, shows
         *  the real state. The sign-in goes over the WebSocket: [sendSignInWhenConnected] waits on
         *  the status flow's next `Connected` emission when the transport isn't connected yet,
         *  sends nothing at all when the connection is paused, and sends nothing either when the
         *  server reports it has no accounts. */
        fun signIn(context: Context) {
            if (!signInInFlight.compareAndSet(false, true)) {
                Logger.i(TAG, "Sign-in ignored: one is already in flight")
                return
            }
            viewModelScope.launch(ioDispatcher) {
                try {
                    _signInState.value = AccountSignInState.SigningIn
                    Logger.i(TAG, "Sign-in started")
                    val config = settingsRepository.getTransportConfig()
                    val idToken =
                        obtainInteractiveIdToken(context, googleSignInClient, _accountProfile, _signInState)
                            ?: return@launch
                    val signInToken =
                        mintSignInTokenForSignIn(idToken, accountApiClient, config, _signInState) ?: return@launch
                    _signInState.value = AccountSignInState.SigningInToServer
                    val signInResult =
                        guardedStep("Sign-in", _signInState) {
                            sendSignInWhenConnected(signInToken, transportClient, config, pauseSettings, _signInState)
                        } ?: return@launch
                    finishSignIn(idToken, signInResult)
                } finally {
                    signInInFlight.set(false)
                }
            }
        }

        /** Applies the server's sign-in reply (plan A1b): the reply decides. A rejection shows the
         *  server's own reason (`accounts_unsupported` is surfaced as its own state); the bounded
         *  reply wait expiring is reported as a failure and never retried on its own. */
        private suspend fun finishSignIn(
            idToken: String,
            signInResult: SignInResult,
        ) {
            when (signInResult) {
                is SignInResult.SignedIn -> {
                    settingsRepository.updateAccountId(signInResult.accountId)
                    _signInState.value = AccountSignInState.SignedIn(signInResult.accountId)
                    Logger.i(TAG, "Sign-in ok")
                    // HomeScreen's own LaunchedEffect(accountId) guards on signInState not yet
                    // being SignedIn (it only auto-refreshes once, on a cold start) - by the time
                    // its recomposition sees the new accountId, signInState above is already
                    // SignedIn, so that effect never fires for a fresh sign-in. Both loads here
                    // are this path's own responsibility, not something the effect also covers.
                    // Reuse the moment of a successful sign-in to refresh the connections list:
                    // it travels over the phone connection, which is now authenticated as this
                    // account, so no extra Google round-trip is involved.
                    loadConnectionsOverConnection()
                    // The This device card's state comes from the same authoritative session
                    // check everything else reads (plan A1a/A1b) — but with the id token the
                    // sign-in just obtained (plan A1 review): right after an interactive grant a
                    // fresh SILENT Google fetch can fail, which used to show "signed out"
                    // straight after signing in. Same server call, same state mapping, no extra
                    // Google request.
                    authoritativeSessionCheckWithToken(
                        sessionCheckInFlight,
                        ::setSessionState,
                        settingsRepository,
                        accountApiClient,
                        deviceIdentityKeyStore,
                        idToken,
                    )
                }

                is SignInResult.Rejected -> {
                    Logger.w(TAG, "Sign-in rejected by the server")
                    _signInState.value =
                        if (signInResult.reason == "accounts_unsupported") {
                            AccountSignInState.AccountsUnsupported
                        } else {
                            // The server's own reason is shown, verbatim (plan A1b).
                            AccountSignInState.Failed("Sign-in rejected: ${signInResult.reason}")
                        }
                }

                is SignInResult.TimedOut -> {
                    // The bounded wait for the reply expired. Reported as a failure, never
                    // retried on its own (plan A1b).
                    Logger.w(TAG, "Sign-in timed out waiting for the server's reply")
                    _signInState.value = AccountSignInState.Failed("The server didn't answer. Try again.")
                }

                is SignInResult.NotConnected -> {
                    // sendSignInWhenConnected only sends once Connected, so this is a narrow
                    // race with session teardown. Shown as a failure, never silently retried.
                    Logger.w(TAG, "Sign-in not sent: the transport dropped before the send")
                    _signInState.value = AccountSignInState.Failed("Not connected to the server")
                }
            }
        }

        /** Fetches the account's AI connections over the phone connection
         *  ([DeviceTransportClient.listAiConnections]) — the server identifies the account from
         *  the sign-in on this phone, so no Google token is involved. The Google credential is
         *  refreshed silently (no UI, never a picker) purely to keep the avatar's display-only
         *  profile fresh; its failure never blocks or fails the list itself. */
        fun loadConnections(context: Context) {
            viewModelScope.launch(ioDispatcher) {
                when (val signIn = googleSignInClient.signInSilently(context)) {
                    is GoogleSignInResult.Success -> {
                        _accountProfile.value =
                            AccountProfile(signIn.displayName, signIn.email, signIn.profilePictureUri)
                    }

                    // Any other silent outcome (no credential, cancelled, failed) leaves the
                    // profile as-is: the list itself travels over the phone connection and
                    // doesn't need Google at all.
                    else -> {
                        Unit
                    }
                }
                loadConnectionsOverConnection()
            }
        }

        /** The actual fetch over the phone connection; [loadConnections] delegates to this, and
         *  [finishSignIn] calls it directly the moment the sign-in succeeds. */
        private suspend fun loadConnectionsOverConnection() {
            _connectionsState.value = ConnectionsState.Loading
            when (val result = transportClient.listAiConnections()) {
                is AiConnectionsResult.Connections -> {
                    _connectionsState.value =
                        ConnectionsState.Loaded(
                            result.connections.map {
                                AccountConnection(
                                    it.clientId,
                                    it.clientName,
                                    it.displayName,
                                    it.imageUrl,
                                    it.connectedAt,
                                    it.lastUsedAt,
                                )
                            },
                        )
                }

                is AiConnectionsResult.Error -> {
                    // The server's own error code is shown, verbatim (not_signed_in when the
                    // phone is signed out).
                    _connectionsState.value =
                        ConnectionsState.Failed(result.message ?: result.code)
                }

                is AiConnectionsResult.TimedOut -> {
                    _connectionsState.value = ConnectionsState.Failed("The server didn't answer. Try again.")
                }

                is AiConnectionsResult.NotConnected -> {
                    _connectionsState.value = ConnectionsState.Failed("Not connected to the server")
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
                        when (val signIn = googleSignInClient.signInSilently(context)) {
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

                            is GoogleSignInResult.Cancelled -> {
                                // The silent request never shows Google UI on its own, so this
                                // is unreachable in practice; not an error either way.
                                Logger.w(TAG, "Silent sign-in unexpectedly reported cancelled")
                                return@launch
                            }

                            is GoogleSignInResult.Failed -> {
                                setSessionState(AccountSessionState.Unreachable(signIn.message))
                                return@launch
                            }
                        }

                    applyServerSessionState(
                        settingsRepository,
                        accountApiClient,
                        deviceIdentityKeyStore,
                        idToken,
                        ::setSessionState,
                    )
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
         *  avatar menu) and sends `sign_out` over the transport. Mirrors
         *  [GoogleSignInClient.signOut]'s own contract: signing back in with the same Google
         *  account reaches this same device. Local sign-out happens immediately, whether or not
         *  the transport's `signed_out` reply arrives (the transport records a pending sign-out
         *  in that case and replays it on the next connection). */
        fun signOut(context: Context) {
            viewModelScope.launch(ioDispatcher) {
                // Persisted FIRST: if the process dies mid-sign-out or the connection drops, the
                // flag is already on disk, so the next connection replays sign_out (code-review
                // round 2). The transport also sets it itself (idempotent), before its Connected
                // check, so an offline sign-out is still recorded either way.
                settingsRepository.setPendingSignOut(true)
                googleSignInClient.signOut(context)
                settingsRepository.clearAccountId()
                _accountProfile.value = null
                _signInState.value = AccountSignInState.Idle
                _connectionsState.value = ConnectionsState.Idle
                // The next authoritative check (next Home resume / Retry) re-derives this from the
                // server; SignedOut is the state this device is in until then (no credential left).
                setSessionState(AccountSessionState.SignedOut)
                transportClient.signOut()
            }
        }

        /** Revokes one AI connection over the phone connection
         *  ([DeviceTransportClient.revokeAiConnection]); `clientId` is the connection's
         *  `client_id` as listed by [loadConnections]. Fire-and-forget: it only updates
         *  [connectionsState] on success — the detail screen's own retry is the recovery path. */
        fun revokeConnection(clientId: String) {
            viewModelScope.launch(ioDispatcher) {
                _revokeError.value = null
                when (val result = transportClient.revokeAiConnection(clientId)) {
                    AiRevokeResult.Revoked -> {
                        removeConnectionFromList(clientId)
                    }

                    is AiRevokeResult.Error -> {
                        if (result.code == "not_found") {
                            // Already gone (e.g. revoked elsewhere) — the list should not show
                            // it either way, so the outcome for this screen is the same.
                            removeConnectionFromList(clientId)
                        } else {
                            // The server's own error is shown, verbatim, same pattern as the
                            // connections list's failure message.
                            _revokeError.value = result.message ?: result.code
                        }
                    }

                    AiRevokeResult.TimedOut -> {
                        _revokeError.value = "The server didn't answer. Try again."
                    }

                    AiRevokeResult.NotConnected -> {
                        _revokeError.value = "Not connected to the server"
                    }
                }
            }
        }

        private fun removeConnectionFromList(clientId: String) {
            val current = _connectionsState.value
            if (current is ConnectionsState.Loaded) {
                _connectionsState.value =
                    ConnectionsState.Loaded(current.connections.filterNot { it.clientId == clientId })
            }
        }

        /** [GoogleSignInClient.signInSilently] first; only shows the interactive sheet if that
         *  finds nothing AND [allowInteractive] permits it — see [loadConnections]'s own doc
         *  comment for why an automatic call must pass `false`. */
        private suspend fun freshIdTokenOrNull(
            context: Context,
            allowInteractive: Boolean,
        ): String? {
            val silent = googleSignInClient.signInSilently(context)
            if (silent is GoogleSignInResult.Success) {
                _accountProfile.value = AccountProfile(silent.displayName, silent.email, silent.profilePictureUri)
                return silent.idToken
            }
            val interactive =
                if (allowInteractive) {
                    googleSignInClient.signInInteractively(context)
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
 *  the step name plus the exception class and message, and shown as a Failed sign-in state naming
 *  the step — the state never stays on "Signing in" after a failure. Cancellation is rethrown so
 *  it is not shown as an error. Top-level so [AccountViewModel] stays within detekt's
 *  function-count limit; it touches only what it is given. */
@Suppress("TooGenericExceptionCaught")
private suspend fun <T> guardedStep(
    step: String,
    signInState: MutableStateFlow<AccountSignInState>,
    block: suspend () -> T,
): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "Sign-in step \"$step\" failed: ${e::class.java.simpleName}: ${e.message}")
        signInState.value =
            AccountSignInState.Failed("$step failed: ${e.message ?: e::class.java.simpleName}")
        null
    }

/** The Google credential step of the sign-in chain (plan A1b): one interactive sign-in —
 *  exactly one `GetSignInWithGoogleOption` request and one sheet, never a second attempt (plan
 *  A1 review). `null` means the chain stops here. A user-cancelled sheet is a choice, not an
 *  error: nothing is shown and the state returns to Idle, so the step shows its sign-in button. */
private suspend fun obtainInteractiveIdToken(
    context: Context,
    googleSignInClient: GoogleSignInClient,
    accountProfile: MutableStateFlow<AccountProfile?>,
    signInState: MutableStateFlow<AccountSignInState>,
): String? =
    when (
        val result =
            guardedStep("Google sign-in", signInState) {
                googleSignInClient.signInInteractively(context)
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

        is GoogleSignInResult.Cancelled -> {
            // Not an error: the user closed the sheet. Back to Idle, so the step shows its
            // sign-in button again (a bare spinner would never clear), and the next session
            // check (next resume / Retry) shows the real state.
            Logger.i(TAG, "Sign-in stopped at Google sign-in: cancelled by the user")
            signInState.value = AccountSignInState.Idle
            null
        }

        is GoogleSignInResult.NoCredential -> {
            Logger.w(TAG, "Sign-in stopped at Google sign-in: no credential")
            signInState.value = AccountSignInState.Failed("No Google account was selected")
            null
        }

        is GoogleSignInResult.Failed -> {
            Logger.w(TAG, "Sign-in stopped at Google sign-in: ${result.message}")
            signInState.value = AccountSignInState.Failed("Google sign-in failed: ${result.message}")
            null
        }
    }

/** The sign-in-token step of the sign-in chain (plan A1b). `null` means the chain stops here. */
private suspend fun mintSignInTokenForSignIn(
    idToken: String,
    accountApiClient: AccountApiClient,
    config: TransportConfig,
    signInState: MutableStateFlow<AccountSignInState>,
): String? =
    when (
        val result =
            guardedStep("Sign-in token", signInState) {
                accountApiClient.mintSignInToken(config.host, config.port, config.tls, idToken)
            }
    ) {
        null -> {
            null
        }

        is SignInTokenResult.Success -> {
            Logger.i(TAG, "Sign-in token ok")
            result.signInToken
        }

        is SignInTokenResult.Failed -> {
            Logger.w(TAG, "Sign-in stopped at sign-in token: ${result.message}")
            signInState.value = AccountSignInState.Failed("Sign-in token failed: ${result.message}")
            null
        }
    }

/** Sends the sign-in over the WebSocket (plan A1b). If the transport is already
 *  [TransportStatus.Connected] the sign-in is sent straight away — unless that connection
 *  reported `accounts == false`, in which case nothing is sent at all and the state shows
 *  [AccountSignInState.AccountsUnsupported]; otherwise the state shows
 *  [AccountSignInState.WaitingForConnection] ("Connecting to the server…") and this waits for the
 *  status StateFlow's NEXT `Connected` emission — no timer, no polling — so the sign-in is sent
 *  exactly once, only after `Connected` (the `accounts` flag of that emission is checked the
 *  same way, by [connectedForSignIn]). If the owner paused the connection (the app's Pause),
 *  nothing is sent and the paused message is shown. When the transport sits on
 *  [TransportStatus.Replaced] (its reconnect loop stopped after a 4004 close), it is restarted
 *  from `config` first so that wait can actually be satisfied. The existing bounded wait for the
 *  server's reply inside [DeviceTransportClient.signIn] stays as-is. */
private suspend fun sendSignInWhenConnected(
    signInToken: String,
    transportClient: DeviceTransportClient,
    config: TransportConfig,
    pauseSettings: PauseSettings,
    signInState: MutableStateFlow<AccountSignInState>,
): SignInResult? =
    if (pauseSettings.getPauseState().isEffectivePause(System.currentTimeMillis())) {
        Logger.w(TAG, "Sign-in not sent: the connection is paused")
        signInState.value = AccountSignInState.Failed("Your connection is paused. Resume it to link this phone.")
        null
    } else {
        if (transportClient.status.value is TransportStatus.Replaced) {
            Logger.i(TAG, "Restarting the transport stopped by a 4004 close so sign-in can connect")
            transportClient.start(config.host, config.port, config.tls)
        }
        val connected = connectedForSignIn(transportClient, signInState)
        if (connected != null) {
            Logger.i(TAG, "Sending the sign-in")
            transportClient.signIn(signInToken)
        } else {
            null
        }
    }

/** Resolves the `Connected` snapshot the sign-in is sent against, or `null` — after setting
 *  [AccountSignInState.AccountsUnsupported] — when the resolved connection reports
 *  `accounts == false`. When the transport isn't `Connected` yet the state shows
 *  [AccountSignInState.WaitingForConnection] and the next `Connected` emission is awaited; its
 *  `accounts` flag is checked exactly the same way. Top-level so [AccountViewModel] stays within
 *  detekt's function-count limit. */
private suspend fun connectedForSignIn(
    transportClient: DeviceTransportClient,
    signInState: MutableStateFlow<AccountSignInState>,
): TransportStatus.Connected? {
    val current = transportClient.status.value
    val connected =
        current as? TransportStatus.Connected
            ?: run {
                Logger.i(TAG, "Sign-in waiting for the transport to connect")
                signInState.value = AccountSignInState.WaitingForConnection
                transportClient.status
                    .filterIsInstance<TransportStatus.Connected>()
                    .first()
                    .also { Logger.i(TAG, "Transport connected; sending the sign-in") }
            }
    if (connected.accounts) return connected
    Logger.w(TAG, "Sign-in not sent: the server has no accounts")
    signInState.value = AccountSignInState.AccountsUnsupported
    return null
}

/** The authoritative session check for a token already in hand (plan A1 review) — the shared
 *  server half of [AccountViewModel.checkSession]: the same `POST /v1/accounts/devices` call and
 *  the same mapping onto [AccountSessionState], with NO Google request. Used by [checkSession]'s
 *  chain and by [AccountViewModel.finishSignIn] after a successful sign-in, with the id token the
 *  interactive sign-in itself just obtained (a fresh silent fetch right after an interactive grant
 *  can fail). Single-flight via [sessionCheckInFlight]: if another check is in flight, this one
 *  starts nothing. Top-level so [AccountViewModel] stays within detekt's function-count limit. */
@Suppress("TooGenericExceptionCaught")
private suspend fun authoritativeSessionCheckWithToken(
    sessionCheckInFlight: AtomicBoolean,
    setState: (AccountSessionState) -> Unit,
    settingsRepository: SettingsRepository,
    accountApiClient: AccountApiClient,
    deviceIdentityKeyStore: DeviceIdentityKeyStore,
    idToken: String,
) {
    if (!sessionCheckInFlight.compareAndSet(false, true)) {
        Logger.i(TAG, "Session check with the sign-in token skipped: one already in flight")
        return
    }
    try {
        setState(AccountSessionState.Checking)
        applyServerSessionState(
            settingsRepository,
            accountApiClient,
            deviceIdentityKeyStore,
            idToken,
            setState,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // applyServerSessionState touches the Android Keystore via [currentDeviceId], which can
        // throw on real devices; map that to Unreachable (its class) so the state never sticks
        // on Checking and the chain never crashes.
        Logger.w(TAG, "Session check with the sign-in token failed: ${e::class.java.simpleName}")
        setState(AccountSessionState.Unreachable(e::class.java.simpleName))
    } finally {
        sessionCheckInFlight.set(false)
    }
}

/** The shared server half: one device-list call and its mapping onto the session state —
 *  [AccountViewModel.checkSession] and [authoritativeSessionCheckWithToken] use exactly this one
 *  function. */
private suspend fun applyServerSessionState(
    settingsRepository: SettingsRepository,
    accountApiClient: AccountApiClient,
    deviceIdentityKeyStore: DeviceIdentityKeyStore,
    idToken: String,
    setState: (AccountSessionState) -> Unit,
) {
    val config = settingsRepository.getTransportConfig()
    when (val result = accountApiClient.listDevices(config.host, config.port, config.tls, idToken)) {
        is DevicesResult.Success -> {
            val thisDeviceId = currentDeviceId(deviceIdentityKeyStore)
            val device = result.devices.firstOrNull { it.deviceId == thisDeviceId }
            setState(AccountSessionState.SignedIn(linked = device != null, device = device))
        }

        is DevicesResult.Failed -> {
            setState(sessionStateFor(result))
        }
    }
}

/** This phone's derived device id — a real Android Keystore read, which can throw on devices. */
private fun currentDeviceId(deviceIdentityKeyStore: DeviceIdentityKeyStore): String {
    val publicKeyDer = Base64.getDecoder().decode(deviceIdentityKeyStore.ensurePublicKeyBase64())
    return deriveDeviceId(publicKeyDer)
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
