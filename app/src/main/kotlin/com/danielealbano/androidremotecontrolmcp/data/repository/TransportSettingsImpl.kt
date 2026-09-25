package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.TransportConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

private typealias ConfigChange = Pair<TransportConfig, TransportConfig>

/** [TransportSettings] backed by the same Preferences DataStore as [SettingsRepositoryImpl] (which
 *  delegates these members here), same pattern as [EventChannelSettingsImpl]. */
class TransportSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : TransportSettings {
        override val transportConfig: Flow<TransportConfig> =
            dataStore.data.map { prefs ->
                val json = prefs[TRANSPORT_CONFIG_KEY] ?: return@map TransportConfig()
                TransportConfig.fromJsonOrDefault(json)
            }

        override suspend fun getTransportConfig(): TransportConfig {
            val current = transportConfig.first()
            if (current.deviceId.isNotEmpty()) return current
            // First-ever read: mint and persist a stable device id so hello.device_id survives
            // restarts rather than regenerating (and thus looking like a new device) every launch.
            val withDeviceId = current.copy(deviceId = UUID.randomUUID().toString())
            dataStore.edit { prefs -> prefs[TRANSPORT_CONFIG_KEY] = withDeviceId.toJson() }
            return withDeviceId
        }

        private suspend fun updateConfig(transform: (TransportConfig) -> TransportConfig): ConfigChange {
            val current = getTransportConfig()
            val updated = transform(current)
            dataStore.edit { prefs -> prefs[TRANSPORT_CONFIG_KEY] = updated.toJson() }
            return current to updated
        }

        override suspend fun updateTransportEnabled(enabled: Boolean) {
            val (old, new) = updateConfig { it.copy(enabled = enabled) }
            settingsChangeLogger.submit("transport_enabled", old.enabled.toString(), new.enabled.toString()) { _, n ->
                "Remote control ${if (n.toBoolean()) "enabled" else "disabled"}"
            }
        }

        override suspend fun updateTransportHost(host: String) {
            val (old, new) = updateConfig { it.copy(host = host) }
            settingsChangeLogger.submit("transport_host", old.host, new.host) { o, n ->
                "Remote control server host changed $o → $n"
            }
        }

        override suspend fun updateTransportPort(port: Int) {
            val (old, new) = updateConfig { it.copy(port = port) }
            settingsChangeLogger.submit("transport_port", old.port.toString(), new.port.toString()) { o, n ->
                "Remote control server port changed $o → $n"
            }
        }

        private companion object {
            private val TRANSPORT_CONFIG_KEY = stringPreferencesKey("transport_config")
        }
    }
