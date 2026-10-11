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
 *  pattern as [PauseSettingsImpl]/[TransportSettingsImpl].
 *
 *  Default `true` (round 3 phone-feedback regression, re-enabled): the kill-switch shipped
 *  default-OFF while the root cause (the touch-passthrough toggle running off the main thread,
 *  breaking every tap/`type_text` step) and the Stop/dismiss lifecycle bugs were fixed. Both are
 *  now fixed and verified live on redroid - a real coordinate tap and `type_text` both succeed
 *  with the bar visible, Stop correctly ends the session into Done and dismisses, and the glow is
 *  removed along with the bar - so the bar defaults back on. An existing install with no saved
 *  value yet (a fresh install, or one from before this setting existed) gets this default the same
 *  way a brand-new one does. */
class ControlBarSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : ControlBarSettings {
        override val controlBarEnabled: Flow<Boolean> =
            dataStore.data.map { prefs -> prefs[CONTROL_BAR_ENABLED_KEY] ?: true }

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
