package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** [AccountSettings] backed by the same Preferences DataStore as [SettingsRepositoryImpl], same
 *  pattern as [TransportSettingsImpl]. */
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
                if (hadPrevious) {
                    "Device re-signed-in for the signed-in account"
                } else {
                    "Device signed in to the account"
                }
            }
        }

        override suspend fun clearAccountId() {
            dataStore.edit { prefs -> prefs.remove(ACCOUNT_ID_KEY) }
            settingsChangeLogger.submit("account_id", "x", "") { _, _ -> "Signed out of the account" }
        }

        override val pendingSignOut: Flow<Boolean> =
            dataStore.data.map { prefs -> prefs[PENDING_SIGN_OUT_KEY] ?: false }

        override suspend fun getPendingSignOut(): Boolean = pendingSignOut.first()

        override suspend fun setPendingSignOut(pending: Boolean) {
            dataStore.edit { prefs -> prefs[PENDING_SIGN_OUT_KEY] = pending }
            settingsChangeLogger.submit(
                "pending_sign_out",
                if (pending) "" else "x",
                if (pending) "x" else "",
            ) { _, _ ->
                if (pending) "Sign-out pending server acknowledgement" else "Sign-out acknowledged by the server"
            }
        }

        private companion object {
            private val ACCOUNT_ID_KEY = stringPreferencesKey("account_id")
            private val PENDING_SIGN_OUT_KEY = booleanPreferencesKey("pending_sign_out")
        }
    }
