package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** [AccountSettings] backed by the same Preferences DataStore as [SettingsRepositoryImpl], same
 *  pattern as [TransportSettingsImpl]/[EventChannelSettingsImpl]. */
class AccountSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : AccountSettings {
        override val accountId: Flow<String?> =
            dataStore.data.map { prefs -> prefs[ACCOUNT_ID_KEY] }

        override suspend fun getAccountId(): String? = accountId.first()

        override suspend fun updateAccountId(accountId: String) {
            val hadPrevious = getAccountId() != null
            dataStore.edit { prefs -> prefs[ACCOUNT_ID_KEY] = accountId }
            settingsChangeLogger.submit("account_id", "", "x") { _, _ ->
                if (hadPrevious) "Device re-claimed for the signed-in account" else "Device claimed for the signed-in account"
            }
        }

        private companion object {
            private val ACCOUNT_ID_KEY = stringPreferencesKey("account_id")
        }
    }
