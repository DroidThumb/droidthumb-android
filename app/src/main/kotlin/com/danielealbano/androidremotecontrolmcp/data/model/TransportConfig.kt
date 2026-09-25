package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val transportJson = Json { ignoreUnknownKeys = true }

/**
 * The device WebSocket transport's settings (M2): where the server is (`host`/`port` — the podman
 * gateway when pointed at redroid, plan 01 §0/§0.1; a real relay address later), whether the
 * connection should be held open, and this device's stable identity (`hello.device_id`).
 */
@Serializable
data class TransportConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val deviceId: String = "",
) {
    companion object {
        /** Matches `droidthumb-server`'s `DEVICE_PORT` default (`src/config.ts`). */
        const val DEFAULT_PORT = 4001

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
