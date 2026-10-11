package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** [ControlBarSettings] backed by the same Preferences DataStore as [SettingsRepositoryImpl], same
 *  pattern as [PauseSettingsImpl]/[TransportSettingsImpl]. Default `false` - see that interface's
 *  own doc comment for why; an existing install with no saved value yet (a fresh install, or one
 *  from before this setting existed) gets the safe default the same way a brand-new one does. */
class ControlBarSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : ControlBarSettings {
        override val controlBarEnabled: Flow<Boolean> =
            dataStore.data.map { prefs -> prefs[CONTROL_BAR_ENABLED_KEY] ?: false }

        override suspend fun getControlBarEnabled(): Boolean = controlBarEnabled.first()

        override suspend fun setControlBarEnabled(enabled: Boolean) {
            val old = getControlBarEnabled()
            dataStore.edit { prefs -> prefs[CONTROL_BAR_ENABLED_KEY] = enabled }
            settingsChangeLogger.submit("control_bar_enabled", old.toString(), enabled.toString()) { _, n ->
                "Floating control bar ${if (n.toBoolean()) "enabled" else "disabled"}"
            }
        }

        private companion object {
            private val CONTROL_BAR_ENABLED_KEY = booleanPreferencesKey("control_bar_enabled")
        }
    }
