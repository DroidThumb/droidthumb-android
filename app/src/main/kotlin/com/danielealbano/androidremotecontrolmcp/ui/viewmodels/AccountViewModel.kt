package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.account.AccountApiClient
import com.danielealbano.androidremotecontrolmcp.services.account.AccountConnection
import com.danielealbano.androidremotecontrolmcp.services.account.AccountDevice
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Base64
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

/** State of fetching this device's own claimed-device record (plan 70 US3's "This device"
 *  section; `droidthumb-server` plan 05 US3). */
sealed interface ThisDeviceState {
    data object Idle : ThisDeviceState

    data object Loading : ThisDeviceState

    data class Loaded(
        /** `null` if the server's device list doesn't (yet) include this device — e.g. right
         *  after a fresh claim, before this device's own registration round-trips. */
        val device: AccountDevice?,
        val deviceLimit: Int?,
    ) : ThisDeviceState

    data class Failed(
        val message: String,
    ) : ThisDeviceState
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

        private val _thisDeviceState = MutableStateFlow<ThisDeviceState>(ThisDeviceState.Idle)
        val thisDeviceState: StateFlow<ThisDeviceState> = _thisDeviceState.asStateFlow()

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
                        loadConnections(context, allowInteractive = true)
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
        }

        fun renameConnection(
            context: Context,
            clientId: String,
            displayName: String,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val idToken = freshIdTokenOrNull(context, allowInteractive = true) ?: return@launch
                val config = settingsRepository.getTransportConfig()
                when (
                    accountApiClient.renameConnection(config.host, config.port, config.tls, idToken, clientId, displayName)
                ) {
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

        /** This device's own claimed-device record (plan 70 US3's "This device" section): filters
         *  the account's full device list down to the one whose id matches this phone's own
         *  (derived locally from its identity key, same as [DeviceTransportClient]'s handshake —
         *  no new server concept, just a new list call). */
        fun loadThisDevice(context: Context) {
            viewModelScope.launch(ioDispatcher) {
                _thisDeviceState.value = ThisDeviceState.Loading
                val idToken = freshIdTokenOrNull(context, allowInteractive = false)
                if (idToken == null) {
                    _thisDeviceState.value = ThisDeviceState.Failed("Sign in to view this device")
                    return@launch
                }
                val config = settingsRepository.getTransportConfig()
                when (val result = accountApiClient.listDevices(config.host, config.port, config.tls, idToken)) {
                    is DevicesResult.Success -> {
                        val thisDeviceId = currentDeviceId()
                        _thisDeviceState.value =
                            ThisDeviceState.Loaded(
                                device = result.devices.firstOrNull { it.deviceId == thisDeviceId },
                                deviceLimit = result.deviceLimit,
                            )
                    }

                    is DevicesResult.Failed -> {
                        _thisDeviceState.value = ThisDeviceState.Failed(result.message)
                    }
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
                _thisDeviceState.value = ThisDeviceState.Idle
            }
        }

        private fun currentDeviceId(): String =
            deriveDeviceId(Base64.getDecoder().decode(deviceIdentityKeyStore.ensurePublicKeyBase64()))

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
            if (!allowInteractive) return null
            val interactive = googleSignInClient.signIn(context, filterByAuthorizedAccounts = false)
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
