package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationFilterMode
import kotlinx.coroutines.flow.Flow

/**
 * Event-channel slice of the settings surface. Split out of [SettingsRepository] so its
 * (per-mutation-logged) implementation lives in its own class, keeping [SettingsRepositoryImpl]
 * within detekt's LargeClass budget. [SettingsRepository] extends this interface, so callers see a
 * single unified settings API and [SettingsRepositoryImpl] delegates these members to
 * [EventChannelSettingsImpl].
 */
interface EventChannelSettings {
    /** Observes the current event channel configuration. */
    val eventChannelConfig: Flow<EventChannelConfig>

    /** Returns the current event channel configuration as a one-shot read. */
    suspend fun getEventChannelConfig(): EventChannelConfig

    /** Updates the event channel enabled toggle. */
    suspend fun updateEventChannelEnabled(enabled: Boolean)

    /** Updates the notification channel enabled toggle. */
    suspend fun updateNotificationChannelEnabled(enabled: Boolean)

    /** Updates the notification filter mode. */
    suspend fun updateNotificationFilterMode(mode: NotificationFilterMode)

    /** Updates the set of app package names for notification filtering. */
    suspend fun updateNotificationFilterApps(apps: Set<String>)
}
