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

    /** Returns the current transport configuration as a one-shot read. */
    suspend fun getTransportConfig(): TransportConfig

    /** Updates the transport enabled toggle. */
    suspend fun updateTransportEnabled(enabled: Boolean)

    /** Updates the server host (the podman gateway IP when pointed at redroid). */
    suspend fun updateTransportHost(host: String)

    /** Updates the server port. */
    suspend fun updateTransportPort(port: Int)

    /** Updates whether the transport connects over TLS (wss vs ws) — the same scheme covers the
     *  WebSocket handshake and the one-time registration call, since both are the same single
     *  listener behind Caddy's TLS termination on the public hostname. */
    suspend fun updateTransportTls(tls: Boolean)
}
