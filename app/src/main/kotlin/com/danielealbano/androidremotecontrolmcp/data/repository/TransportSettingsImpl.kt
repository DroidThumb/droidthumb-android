package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TransportConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private typealias ConfigChange = Pair<TransportConfig, TransportConfig>

/** The out-of-the-box server (design doc D-33/§8.8): staging for debug builds, production for
 *  release — the user is never asked to type a host/port/TLS. An existing install with no saved
 *  value yet (a fresh install, or one from before this default existed) picks this up the same way
 *  a brand-new one does, since it's exactly what [transportConfig] below falls back to. */
private val DEFAULT_TRANSPORT_CONFIG =
    TransportConfig(
        host = BuildConfig.DEFAULT_SERVER_HOST,
        port = BuildConfig.DEFAULT_SERVER_PORT,
        tls = BuildConfig.DEFAULT_SERVER_TLS,
    )

/** [TransportSettings] backed by the same Preferences DataStore as [SettingsRepositoryImpl] (which
 *  delegates these members here). */
class TransportSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : TransportSettings {
        override val transportConfig: Flow<TransportConfig> =
            dataStore.data.map { prefs ->
                val json = prefs[TRANSPORT_CONFIG_KEY] ?: return@map DEFAULT_TRANSPORT_CONFIG
                // A corrupted/unparseable saved value is just as "nothing usable was saved" as no
                // value at all - falls back to the same build-aware default, not the bare one
                // fromJsonOrDefault's own signature can't know about.
                runCatching { TransportConfig.fromJson(json) }.getOrDefault(DEFAULT_TRANSPORT_CONFIG)
            }

        override suspend fun getTransportConfig(): TransportConfig = transportConfig.first()

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

        override suspend fun updateTransportTls(tls: Boolean) {
            val (old, new) = updateConfig { it.copy(tls = tls) }
            settingsChangeLogger.submit("transport_tls", old.tls.toString(), new.tls.toString()) { _, n ->
                "Remote control TLS ${if (n.toBoolean()) "enabled" else "disabled"}"
            }
        }

        private companion object {
            private val TRANSPORT_CONFIG_KEY = stringPreferencesKey("transport_config")
        }
    }
