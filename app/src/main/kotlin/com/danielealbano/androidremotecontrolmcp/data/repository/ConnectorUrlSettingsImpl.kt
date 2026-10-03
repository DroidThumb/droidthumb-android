package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.services.identity.ConnectorSecretCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ConnectorUrlSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val crypto: ConnectorSecretCrypto,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : ConnectorUrlSettings {
        override val connectorUrl: Flow<String?> =
            dataStore.data.map { prefs ->
                val encrypted = prefs[CONNECTOR_URL_KEY] ?: return@map null
                runCatching { crypto.decrypt(encrypted) }.getOrNull()
            }

        override suspend fun getConnectorUrl(): String? = connectorUrl.first()

        override suspend fun updateConnectorUrl(url: String) {
            val hadPrevious = getConnectorUrl() != null
            dataStore.edit { prefs -> prefs[CONNECTOR_URL_KEY] = crypto.encrypt(url) }
            // Fixed sentinel old/new values — never the real URL — so the secret never reaches
            // SettingsChangeLogger's in-memory coalescing state, not even transiently.
            settingsChangeLogger.submit("connector_url", "", "x") { _, _ ->
                if (hadPrevious) "Connector URL regenerated" else "Connector URL received"
            }
        }

        private companion object {
            private val CONNECTOR_URL_KEY = stringPreferencesKey("connector_url_encrypted")
        }
    }
