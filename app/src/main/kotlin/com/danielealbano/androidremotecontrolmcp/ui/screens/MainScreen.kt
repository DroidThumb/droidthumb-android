@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute
import com.danielealbano.androidremotecontrolmcp.ui.navigation.TopLevelRoute
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.TransportViewModel

@Composable
fun MainScreen(
    onRequestNotificationPermission: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
    // Hoisted here (not obtained via hiltViewModel() inside HomeTabScreen's own NavHost) so
    // sign-in/claim state, the connections list and the live transport status all survive
    // switching away to another tab and back - the NavHost's own NavBackStackEntry (and every
    // hiltViewModel() scoped to it) is torn down and rebuilt from scratch every time
    // HomeTabScreen itself leaves and re-enters composition, which was silently resetting
    // AccountViewModel's claimState to Idle on every tab switch, re-triggering a full Google
    // sign-in prompt each time (founder feedback, PR #8 round 4) even though the device was
    // already signed in and claimed.
    accountViewModel: AccountViewModel = hiltViewModel(),
    transportViewModel: TransportViewModel = hiltViewModel(),
) {
    var selectedTabRoute by rememberSaveable { mutableStateOf(TopLevelRoute.Home.route) }
    var pendingSettingsRoute by rememberSaveable { mutableStateOf<String?>(null) }

    // Back on the Logs/Settings/About tab returns to the Home tab instead of leaving the app. The
    // settings NavHost registers its own back callback AFTER this one, so it wins while its back
    // stack is non-empty: back inside a settings sub-screen still pops to the settings index first.
    BackHandler(enabled = selectedTabRoute != TopLevelRoute.Home.route) {
        selectedTabRoute = TopLevelRoute.Home.route
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple(TopLevelRoute.Home, Icons.Default.Home, stringResource(R.string.tab_home)),
                    Triple(TopLevelRoute.Logs, Icons.AutoMirrored.Filled.List, stringResource(R.string.tab_logs)),
                    Triple(TopLevelRoute.Settings, Icons.Default.Settings, stringResource(R.string.tab_settings)),
                    Triple(TopLevelRoute.About, Icons.Default.Info, stringResource(R.string.tab_about)),
                ).forEach { (route, icon, label) ->
                    NavigationBarItem(
                        selected = selectedTabRoute == route.route,
                        onClick = { selectedTabRoute = route.route },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { paddingValues ->
        when (selectedTabRoute) {
            TopLevelRoute.Logs.route -> {
                LogsScreen(modifier = Modifier.padding(paddingValues))
            }

            TopLevelRoute.Settings.route -> {
                SettingsScreen(
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    pendingRoute = pendingSettingsRoute,
                    onPendingRouteConsumed = { pendingSettingsRoute = null },
                    modifier = Modifier.padding(paddingValues),
                    viewModel = viewModel,
                )
            }

            TopLevelRoute.About.route -> {
                AboutScreen(modifier = Modifier.padding(paddingValues))
            }

            else -> {
                HomeTabScreen(
                    onNavigateToPermissions = {
                        pendingSettingsRoute = SettingsRoute.Permissions.route
                        selectedTabRoute = TopLevelRoute.Settings.route
                    },
                    modifier = Modifier.padding(paddingValues),
                    viewModel = viewModel,
                    accountViewModel = accountViewModel,
                    transportViewModel = transportViewModel,
                )
            }
        }
    }
}
