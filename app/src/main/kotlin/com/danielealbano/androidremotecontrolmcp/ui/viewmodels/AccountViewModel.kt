package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.account.AccountApiClient
import com.danielealbano.androidremotecontrolmcp.services.account.AccountConnection
import com.danielealbano.androidremotecontrolmcp.services.account.ClaimTokenResult
import com.danielealbano.androidremotecontrolmcp.services.account.ConnectionsResult
import com.danielealbano.androidremotecontrolmcp.services.account.GoogleSignInClient
import com.danielealbano.androidremotecontrolmcp.services.account.GoogleSignInResult
import com.danielealbano.androidremotecontrolmcp.services.account.RevokeResult
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

@HiltViewModel
class AccountViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val googleSignInClient: GoogleSignInClient,
        private val accountApiClient: AccountApiClient,
        private val transportClient: DeviceTransportClient,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val accountId: StateFlow<String?> =
            settingsRepository.accountId
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        private val _claimState = MutableStateFlow<AccountClaimState>(AccountClaimState.Idle)
        val claimState: StateFlow<AccountClaimState> = _claimState.asStateFlow()

        private val _connectionsState = MutableStateFlow<ConnectionsState>(ConnectionsState.Idle)
        val connectionsState: StateFlow<ConnectionsState> = _connectionsState.asStateFlow()

        /** Runs the full Google sign-in -> claim-token -> claim_account chain (design doc D-33).
         *  `context` must be an Activity context — Credential Manager's account picker UI needs it. */
        fun signInAndClaim(context: Context) {
            viewModelScope.launch(ioDispatcher) {
                _claimState.value = AccountClaimState.SigningIn
                val idToken =
                    when (val result = googleSignInClient.signIn(context, filterByAuthorizedAccounts = false)) {
                        is GoogleSignInResult.Success -> result.idToken
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
                        is ClaimTokenResult.Success -> result.claimToken
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
                        loadConnections(context)
                    }
                    is ClaimResult.Rejected ->
                        _claimState.value =
                            if (result.reason == "already_claimed") {
                                AccountClaimState.AlreadyClaimedByOther
                            } else {
                                AccountClaimState.Failed("Sign-in link expired — try again")
                            }
                    is ClaimResult.TimedOut -> _claimState.value = AccountClaimState.Failed("No response from the server")
                    is ClaimResult.NotConnected -> _claimState.value = AccountClaimState.Failed("Not connected to the server")
                }
            }
        }

        /** Fetches the account's AI connections, re-using an already-signed-in Google account
         *  silently (no UI) wherever Credential Manager allows it — see [GoogleSignInClient.signIn]. */
        fun loadConnections(context: Context) {
            viewModelScope.launch(ioDispatcher) {
                _connectionsState.value = ConnectionsState.Loading
                val idToken = freshIdTokenOrNull(context)
                if (idToken == null) {
                    _connectionsState.value = ConnectionsState.Failed("Sign in to view your AI connections")
                    return@launch
                }
                val config = settingsRepository.getTransportConfig()
                when (val result = accountApiClient.listConnections(config.host, config.port, config.tls, idToken)) {
                    is ConnectionsResult.Success -> _connectionsState.value = ConnectionsState.Loaded(result.connections)
                    is ConnectionsResult.Failed -> _connectionsState.value = ConnectionsState.Failed(result.message)
                }
            }
        }

        fun revokeConnection(
            context: Context,
            clientId: String,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val idToken = freshIdTokenOrNull(context) ?: return@launch
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
                    is RevokeResult.Failed -> Unit // leave the list as-is; the row's own retry is the recovery path
                }
            }
        }

        /** [GoogleSignInClient.signIn] silently first (`filterByAuthorizedAccounts = true`); only
         *  shows the account picker if that finds nothing — keeps viewing/refreshing the
         *  connections list from demanding an interactive sign-in every time, days later. */
        private suspend fun freshIdTokenOrNull(context: Context): String? {
            val silent = googleSignInClient.signIn(context, filterByAuthorizedAccounts = true)
            if (silent is GoogleSignInResult.Success) return silent.idToken
            val interactive = googleSignInClient.signIn(context, filterByAuthorizedAccounts = false)
            return (interactive as? GoogleSignInResult.Success)?.idToken
        }

        private companion object {
            private const val STOP_TIMEOUT_MS = 5000L
        }
    }
