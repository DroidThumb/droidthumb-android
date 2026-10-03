package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val transportJson = Json { ignoreUnknownKeys = true }

/**
 * The device WebSocket transport's settings (M2): where the server is (`host`/`port` — the podman
 * gateway when pointed at redroid, plan 01 §0/§0.1; a real relay address later), whether TLS is
 * used, and whether the connection should be held open. `hello.device_id` is not stored here — it's
 * derived on demand from the Keystore public key (services/identity/DeviceId.kt).
 */
@Serializable
data class TransportConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val tls: Boolean = false,
) {
    companion object {
        /** Matches `droidthumb-server`'s single-listener default (`src/config.ts`'s `mcpPort`) —
         *  MCP, events, device registration and the device WebSocket are all on this one port now
         *  (no more separate device-relay port). */
        const val DEFAULT_PORT = 4000

        fun fromJson(json: String): TransportConfig = transportJson.decodeFromString(serializer(), json)

        fun fromJsonOrDefault(json: String): TransportConfig =
            try {
                fromJson(json)
            } catch (_: Exception) {
                TransportConfig()
            }
    }

    fun toJson(): String = transportJson.encodeToString(serializer(), this)
}
