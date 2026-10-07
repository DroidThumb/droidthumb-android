@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList")

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
import com.danielealbano.androidremotecontrolmcp.ui.components.AccountAvatarMenu
import com.danielealbano.androidremotecontrolmcp.ui.components.AiClientsSection
import com.danielealbano.androidremotecontrolmcp.ui.components.BatteryOptimizationCard
import com.danielealbano.androidremotecontrolmcp.ui.components.CalloutCard
import com.danielealbano.androidremotecontrolmcp.ui.components.HomeStatusIndicator
import com.danielealbano.androidremotecontrolmcp.ui.components.SavedFlowsSection
import com.danielealbano.androidremotecontrolmcp.ui.components.ThisDeviceSection
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountClaimState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.TransportViewModel

/**
 * The Home tab's main screen (plan 70 US3) — replaces the old "Server" tab's card stack with a
 * single status indicator plus the account-centric sections the approved mockup's `Main.dc.html`
 * shows (AI clients, Saved flows, This device). The permission/battery warning cards aren't in
 * the mockup (which only depicts the already-set-up happy path) but stay, above the status
 * indicator, same as before — losing visibility into a revoked permission would be a real
 * regression the mockup simply didn't need to depict.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToPermissions: () -> Unit,
    onNavigateToClientDetail: (clientId: String) -> Unit,
    onNavigateToAddClient: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
    transportViewModel: TransportViewModel = hiltViewModel(),
    accountViewModel: AccountViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
    val isBatteryOptimizationIgnored by viewModel.isBatteryOptimizationIgnored.collectAsStateWithLifecycle()

    val transportStatus by transportViewModel.transportStatus.collectAsStateWithLifecycle()
    val pauseState by transportViewModel.pauseState.collectAsStateWithLifecycle()

    val accountId by accountViewModel.accountId.collectAsStateWithLifecycle()
    val claimState by accountViewModel.claimState.collectAsStateWithLifecycle()
    val connectionsState by accountViewModel.connectionsState.collectAsStateWithLifecycle()
    val accountProfile by accountViewModel.accountProfile.collectAsStateWithLifecycle()
    val thisDeviceState by accountViewModel.thisDeviceState.collectAsStateWithLifecycle()

    // Same durable-claim semantics as the old ServerScreen: an automatic refresh on reopening the
    // app must never put up Google's own account-picker UI by itself (founder feedback, PR #8
    // round 5) - allowInteractive=false here, same as before.
    LaunchedEffect(accountId) {
        if (accountId != null && claimState !is AccountClaimState.Claimed) {
            accountViewModel.loadConnections(context, allowInteractive = false)
            accountViewModel.loadThisDevice(context)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_home)) },
            windowInsets = WindowInsets(0),
            actions = {
                AccountAvatarMenu(
                    profile = accountProfile,
                    onSignInClick = { accountViewModel.signInAndClaim(context) },
                    onSignOutClick = { accountViewModel.signOut(context) },
                    modifier = Modifier.padding(end = 12.dp),
                )
            },
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

            HomeStatusIndicator(
                status = transportStatus,
                pauseState = pauseState,
                onPauseFor1Hour = transportViewModel::pauseFor1Hour,
                onPauseUntilTomorrow = transportViewModel::pauseUntilTomorrow,
                onPauseIndefinitely = transportViewModel::pauseIndefinitely,
                onResumeClick = transportViewModel::resume,
            )

            Spacer(Modifier.height(20.dp))

            AiClientsSection(
                connectionsState = connectionsState,
                onClientClick = onNavigateToClientDetail,
                onAddClientClick = onNavigateToAddClient,
            )

            Spacer(Modifier.height(20.dp))

            SavedFlowsSection()

            Spacer(Modifier.height(20.dp))

            ThisDeviceSection(
                deviceModel = accountViewModel.deviceModel,
                thisDeviceState = thisDeviceState,
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
