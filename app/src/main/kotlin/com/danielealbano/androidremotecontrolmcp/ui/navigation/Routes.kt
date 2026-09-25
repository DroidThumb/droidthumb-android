package com.danielealbano.androidremotecontrolmcp.ui.navigation

sealed class TopLevelRoute(
    val route: String,
) {
    data object Server : TopLevelRoute("server")

    data object Settings : TopLevelRoute("settings")

    data object About : TopLevelRoute("about")
}

sealed class SettingsRoute(
    val route: String,
) {
    data object Index : SettingsRoute("settings/index")

    data object Permissions : SettingsRoute("settings/permissions")

    data object ChannelSettings : SettingsRoute("settings/channel")

    data object NotificationFilter : SettingsRoute("settings/channel/notification_filter")

}

sealed class ServerRoute(
    val route: String,
) {
    data object Index : ServerRoute("server/index")

    data object Logs : ServerRoute("server/logs")
}
