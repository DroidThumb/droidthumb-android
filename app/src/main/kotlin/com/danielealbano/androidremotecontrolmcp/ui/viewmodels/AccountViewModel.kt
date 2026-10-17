package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

        /** Runs the full Google sign-in -> claim-token -> claim_account chain (design doc D-33).
         *  `context` must be an Activity context — Credential Manager's account picker UI needs it. */
        fun signInAndClaim(context: Context) {
            viewModelScope.launch(ioDispatcher) {
                _claimState.value = AccountClaimState.SigningIn
                val idToken =
                    when (val result = googleSignInClient.signIn(context, filterByAuthorizedAccounts = false)) {
                        is GoogleSignInResult.Success -> {
                            _accountProfile.value =
                                AccountProfile(result.displayName, result.email, result.profilePictureUri)
                            result.idToken
                        }

                        is GoogleSignInResult.NoCredential -> {
                            _claimState.value = AccountClaimState.Failed("No Google account was selected")
                            return@launch
                        }

                        is GoogleSignInResult.Failed -> {
                            _claimState.value = AccountClaimState.Failed(result.message)
                            return@launch
                        }
                    }

                val config = settingsRepository.getTransportConfig()
                val claimToken =
                    when (val result = accountApiClient.mintClaimToken(config.host, config.port, config.tls, idToken)) {
                        is ClaimTokenResult.Success -> {
                            result.claimToken
                        }

                        is ClaimTokenResult.Failed -> {
                            _claimState.value = AccountClaimState.Failed(result.message)
                            return@launch
                        }
                    }

                _claimState.value = AccountClaimState.Claiming
                when (val result = transportClient.claimAccount(claimToken)) {
                    is ClaimResult.Claimed -> {
                        settingsRepository.updateAccountId(result.accountId)
                        _claimState.value = AccountClaimState.Claimed(result.accountId)
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
                        _claimState.value =
                            if (result.reason == "already_claimed") {
                                AccountClaimState.AlreadyClaimedByOther
                            } else {
                                AccountClaimState.Failed("Sign-in link expired — try again")
                            }
                    }

                    is ClaimResult.TimedOut -> {
                        _claimState.value = AccountClaimState.Failed("No response from the server")
                    }

                    is ClaimResult.NotConnected -> {
                        _claimState.value = AccountClaimState.Failed("Not connected to the server")
                    }
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
                    _sessionState.value = AccountSessionState.Checking
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
                                _sessionState.value = AccountSessionState.SignedOut
                                return@launch
                            }

                            is GoogleSignInResult.Failed -> {
                                _sessionState.value = AccountSessionState.Unreachable(signIn.message)
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
                            _sessionState.value =
                                AccountSessionState.SignedIn(claimed = device != null, device = device)
                        }

                        is DevicesResult.Failed -> {
                            _sessionState.value = sessionStateFor(result)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _sessionState.value = AccountSessionState.Unreachable(e::class.java.simpleName)
                } finally {
                    sessionCheckInFlight.set(false)
                }
            }
        }

        /** Maps a failed device-list call to the session state it means — [ApiErrorKind.Rejected]
         *  (HTTP 401/403) means the token was rejected, so SignedOut; [ApiErrorKind.Server] is the
         *  server's own problem; [ApiErrorKind.Network] is an IOException of any kind (the client
         *  records the exception's class for logging) — a short reason for the screen. */
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
                _sessionState.value = AccountSessionState.SignedOut
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
