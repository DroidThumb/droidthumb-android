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
@Suppress("TooManyFunctions")
interface EventChannelSettings {
    /** Observes the current event channel configuration. */
    val eventChannelConfig: Flow<EventChannelConfig>

    /** Returns the current event channel configuration as a one-shot read. */
    suspend fun getEventChannelConfig(): EventChannelConfig

    /** Updates the event channel enabled toggle. */
    suspend fun updateEventChannelEnabled(enabled: Boolean)

    /** Updates the event channel endpoint URL. */
    suspend fun updateEventChannelEndpointUrl(url: String)

    /** Updates the event channel auth token. */
    suspend fun updateEventChannelAuthToken(token: String)

    /** Generates a new random event channel auth token (UUID), persists it, and returns it. */
    suspend fun generateNewEventChannelAuthToken(): String

    /**
     * Validates an endpoint URL.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated URL, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateEndpointUrl(url: String): Result<String>

    /** Updates the notification channel enabled toggle. */
    suspend fun updateNotificationChannelEnabled(enabled: Boolean)

    /** Updates the notification filter mode. */
    suspend fun updateNotificationFilterMode(mode: NotificationFilterMode)

    /** Updates the set of app package names for notification filtering. */
    suspend fun updateNotificationFilterApps(apps: Set<String>)
}
