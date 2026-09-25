package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.TransportConfig
import kotlinx.coroutines.flow.Flow

/**
 * Device-transport (M2 WebSocket client) slice of the settings surface, same split as
 * [EventChannelSettings] for the same reason: keeps [SettingsRepositoryImpl] small while
 * [SettingsRepository] still presents one unified API to callers.
 */
interface TransportSettings {
    /** Observes the current transport configuration. */
    val transportConfig: Flow<TransportConfig>

    /**
     * Returns the current transport configuration as a one-shot read. On first-ever read (no
     * `deviceId` persisted yet), generates and persists a stable device id — `hello.device_id`
     * must survive process/app restarts, not regenerate on every launch.
     */
    suspend fun getTransportConfig(): TransportConfig

    /** Updates the transport enabled toggle. */
    suspend fun updateTransportEnabled(enabled: Boolean)

    /** Updates the server host (the podman gateway IP when pointed at redroid). */
    suspend fun updateTransportHost(host: String)

    /** Updates the server port. */
    suspend fun updateTransportPort(port: Int)
}
