package com.danielealbano.androidremotecontrolmcp.services.channel.listeners

import com.danielealbano.androidremotecontrolmcp.data.model.ChannelEventFactory
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationFilterMode
import com.danielealbano.androidremotecontrolmcp.services.channel.EventDispatcher
import com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Listens for notification change events from [McpNotificationListenerService] and
 * forwards them to the channel plugin via [EventDispatcher], applying the configured
 * filter (ALL / WHITELIST / BLACKLIST).
 *
 * Constructed manually by EventChannelService because it requires the service's
 * CoroutineScope (only available at runtime, not at Hilt injection time).
 */
class NotificationEventListener(
    private val eventDispatcher: EventDispatcher,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    @Volatile
    private var currentConfig: NotificationChannelConfig = NotificationChannelConfig()

    fun start(config: NotificationChannelConfig) {
        currentConfig = config
        job =
            scope.launch {
                McpNotificationListenerService.notificationChangeEvents.collect { event ->
                    val cfg = currentConfig
                    val forward = shouldForward(event.notification.packageName, cfg)
                    Logger.d(
                        TAG,
                        "Event: ${event.notification.appName} - forward=$forward mode=${cfg.filterMode}",
                    )
                    if (forward) {
                        val channelEvent =
                            ChannelEventFactory.notification(event.notification, event.eventType.name.lowercase())
                        val result = eventDispatcher.dispatch(channelEvent)
                        Logger.d(TAG, "Dispatched: ${result.isSuccess}")
                    }
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun updateConfig(config: NotificationChannelConfig) {
        currentConfig = config
    }

    private fun shouldForward(
        packageName: String,
        config: NotificationChannelConfig,
    ): Boolean =
        when (config.filterMode) {
            NotificationFilterMode.ALL -> true
            NotificationFilterMode.WHITELIST -> packageName in config.filterApps
            NotificationFilterMode.BLACKLIST -> packageName !in config.filterApps
        }

    companion object {
        private const val TAG = "MCP:NotifEventListener"
    }
}
