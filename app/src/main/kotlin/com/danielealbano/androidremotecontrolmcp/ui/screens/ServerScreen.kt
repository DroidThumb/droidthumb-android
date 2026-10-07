@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.components.AccountCard
import com.danielealbano.androidremotecontrolmcp.ui.components.BatteryOptimizationCard
import com.danielealbano.androidremotecontrolmcp.ui.components.CalloutCard
import com.danielealbano.androidremotecontrolmcp.ui.components.ServerLogsSection
import com.danielealbano.androidremotecontrolmcp.ui.components.TransportStatusCard
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountClaimState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.LogsViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.TransportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    onNavigateToPermissions: () -> Unit,
    onShowAllLogs: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
    transportViewModel: TransportViewModel = hiltViewModel(),
    accountViewModel: AccountViewModel = hiltViewModel(),
) {
    val logsViewModel: LogsViewModel = hiltViewModel()
    val context = LocalContext.current

    val recentServerLogs by logsViewModel.recentServerLogs.collectAsStateWithLifecycle()

    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
    val isBatteryOptimizationIgnored by viewModel.isBatteryOptimizationIgnored.collectAsStateWithLifecycle()

    val transportStatus by transportViewModel.transportStatus.collectAsStateWithLifecycle()
    val pauseState by transportViewModel.pauseState.collectAsStateWithLifecycle()

    val accountId by accountViewModel.accountId.collectAsStateWithLifecycle()
    val claimState by accountViewModel.claimState.collectAsStateWithLifecycle()
    val connectionsState by accountViewModel.connectionsState.collectAsStateWithLifecycle()

    // Loads the connections list once this device already has a claimed account (a fresh claim
    // triggers its own load right after succeeding, in the view model) - covers reopening the app
    // on a device that was claimed in an earlier session. allowInteractive=false: this fires on
    // its own, not from a tap, so it must never put up Google's own account-picker UI by itself -
    // being claimed is this device's own durable, persisted state and must survive the app being
    // closed or the phone rebooting without looking like a sign-out (founder feedback, PR #8
    // round 5); a stale Credential Manager session here just means the connections list shows its
    // own "Sign in to view" retry affordance instead of the full list, not a surprise sign-in UI.
    LaunchedEffect(accountId) {
        if (accountId != null && claimState !is AccountClaimState.Claimed) {
            accountViewModel.loadConnections(context, allowInteractive = false)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_server)) },
            windowInsets = WindowInsets(0),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            if (!isAccessibilityEnabled) {
                PermissionWarningCard(onClick = onNavigateToPermissions)
                Spacer(Modifier.height(16.dp))
            }

            if (!isBatteryOptimizationIgnored) {
                BatteryOptimizationCard(
                    onRequestExemption = { viewModel.requestBatteryOptimizationExemption() },
                )
                Spacer(Modifier.height(16.dp))
            }

            AccountCard(
                accountId = accountId,
                claimState = claimState,
                connectionsState = connectionsState,
                onSignInClick = { accountViewModel.signInAndClaim(context) },
                onRetryConnectionsClick = { accountViewModel.loadConnections(context, allowInteractive = true) },
                onRevokeConnection = { clientId, _ -> accountViewModel.revokeConnection(context, clientId) },
            )

            Spacer(Modifier.height(16.dp))

            TransportStatusCard(
                status = transportStatus,
                pauseState = pauseState,
                onPauseFor1Hour = transportViewModel::pauseFor1Hour,
                onPauseUntilTomorrow = transportViewModel::pauseUntilTomorrow,
                onPauseIndefinitely = transportViewModel::pauseIndefinitely,
                onResumeClick = transportViewModel::resume,
            )

            Spacer(Modifier.height(16.dp))

            ServerLogsSection(
                logs = recentServerLogs,
                onShowMore = onShowAllLogs,
            )
        }
    }
}

@Composable
private fun PermissionWarningCard(onClick: () -> Unit) {
    CalloutCard(
        icon = Icons.Default.Warning,
        title = stringResource(R.string.permission_warning_title),
    ) {
        TextButton(onClick = onClick) {
            Text(stringResource(R.string.permission_warning_action))
        }
    }
}
