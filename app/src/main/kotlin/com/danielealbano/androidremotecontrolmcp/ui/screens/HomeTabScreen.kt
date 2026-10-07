@file:Suppress("FunctionNaming", "LongParameterList")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.danielealbano.androidremotecontrolmcp.ui.navigation.HomeRoute
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.TransportViewModel

/**
 * Home's own nested NavHost (plan 70 US3 Task 3.6): the account avatar's and AI-clients section's
 * rows open screens that live under Home's own back stack — unlike Logs, which this plan promotes
 * to a top-level tab with no nested NavHost of its own (see [MainScreen]). [viewModel]/
 * [accountViewModel]/[transportViewModel] are passed down from [MainScreen] (Activity-scoped)
 * rather than obtained here - this NavHost recreates its own NavBackStackEntry (and anything
 * `hiltViewModel()`'d against it) from scratch every time this whole tab leaves and re-enters
 * composition, which would silently reset sign-in/claim state on every tab switch (see
 * [MainScreen]'s own doc comment).
 */
@Composable
fun HomeTabScreen(
    onNavigateToPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
    accountViewModel: AccountViewModel = hiltViewModel(),
    transportViewModel: TransportViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = HomeRoute.Index.route,
        modifier = modifier,
    ) {
        composable(HomeRoute.Index.route) {
            HomeScreen(
                onNavigateToPermissions = onNavigateToPermissions,
                onNavigateToClientDetail = { clientId ->
                    navController.navigate(HomeRoute.AiClientDetail.routeFor(clientId))
                },
                onNavigateToAddClient = { navController.navigate(HomeRoute.AddAiClient.route) },
                viewModel = viewModel,
                accountViewModel = accountViewModel,
                transportViewModel = transportViewModel,
            )
        }
        composable(
            HomeRoute.AiClientDetail.route,
            arguments = listOf(navArgument(HomeRoute.AiClientDetail.ARG_CLIENT_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val clientId = backStackEntry.arguments?.getString(HomeRoute.AiClientDetail.ARG_CLIENT_ID).orEmpty()
            AiClientDetailScreen(
                clientId = clientId,
                onBack = { navController.popBackStack() },
                accountViewModel = accountViewModel,
            )
        }
        composable(HomeRoute.AddAiClient.route) {
            AddAiClientScreen(
                onBack = { navController.popBackStack() },
                onSelectClaude = { navController.navigate(HomeRoute.ClaudeInstructions.route) },
                onSelectChatGpt = { navController.navigate(HomeRoute.ChatGptInstructions.route) },
                onSelectCustomMcp = { navController.navigate(HomeRoute.CustomMcpInstructions.route) },
            )
        }
        composable(HomeRoute.ClaudeInstructions.route) {
            ClaudeConnectorInstructionsScreen(onBack = { navController.popBackStack() })
        }
        composable(HomeRoute.ChatGptInstructions.route) {
            ChatGptConnectorInstructionsScreen(onBack = { navController.popBackStack() })
        }
        composable(HomeRoute.CustomMcpInstructions.route) {
            CustomMcpConnectorInstructionsScreen(onBack = { navController.popBackStack() })
        }
    }
}
