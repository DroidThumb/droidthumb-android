package com.danielealbano.androidremotecontrolmcp.ui.navigation

sealed class TopLevelRoute(
    val route: String,
) {
    data object Home : TopLevelRoute("home")

    data object Logs : TopLevelRoute("logs")

    data object Settings : TopLevelRoute("settings")

    data object About : TopLevelRoute("about")
}

sealed class SettingsRoute(
    val route: String,
) {
    data object Index : SettingsRoute("settings/index")

    data object Permissions : SettingsRoute("settings/permissions")
}

/** Home's own nested destinations (plan 70 US3 Task 3.6): the account avatar's and AI-clients
 *  section's rows open screens that live under Home's own back stack, not promoted to top-level
 *  tabs — unlike Logs, which this plan DOES promote (see [TopLevelRoute.Logs]). */
sealed class HomeRoute(
    val route: String,
) {
    data object Index : HomeRoute("home/index")

    data object AddAiClient : HomeRoute("home/add_ai_client")

    data object ClaudeInstructions : HomeRoute("home/add_ai_client/claude")

    data object ChatGptInstructions : HomeRoute("home/add_ai_client/chatgpt")

    data object CustomMcpInstructions : HomeRoute("home/add_ai_client/custom")

    /** Carries the tapped connection's `clientId` as a nav argument. */
    data object AiClientDetail : HomeRoute("home/ai_client/{clientId}") {
        const val ARG_CLIENT_ID = "clientId"

        fun routeFor(clientId: String) = "home/ai_client/$clientId"
    }
}
