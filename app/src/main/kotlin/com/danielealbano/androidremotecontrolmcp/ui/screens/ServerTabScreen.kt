@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.danielealbano.androidremotecontrolmcp.ui.navigation.ServerRoute
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.TransportViewModel

@Composable
fun ServerTabScreen(
    onNavigateToPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
    // Passed down from MainScreen (Activity-scoped) rather than obtained here - this composable's
    // own NavHost recreates its NavBackStackEntry (and anything hiltViewModel()'d against it) from
    // scratch every time this whole screen leaves and re-enters composition, which would silently
    // reset sign-in/claim state on every tab switch (see MainScreen's own doc comment).
    accountViewModel: AccountViewModel = hiltViewModel(),
    transportViewModel: TransportViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = ServerRoute.Index.route,
        modifier = modifier,
    ) {
        composable(ServerRoute.Index.route) {
            ServerScreen(
                onNavigateToPermissions = onNavigateToPermissions,
                onShowAllLogs = { navController.navigate(ServerRoute.Logs.route) },
                viewModel = viewModel,
                accountViewModel = accountViewModel,
                transportViewModel = transportViewModel,
            )
        }
        composable(ServerRoute.Logs.route) {
            LogsScreen(onBack = { navController.popBackStack() })
        }
    }
}
