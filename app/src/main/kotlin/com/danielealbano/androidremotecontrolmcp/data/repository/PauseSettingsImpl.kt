package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

/** [PauseSettings] backed by the same Preferences DataStore as [SettingsRepositoryImpl], same
 *  pattern as [TransportSettingsImpl]/[AccountSettingsImpl]. */
class PauseSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : PauseSettings {
        override val pauseState: Flow<PauseState> =
            dataStore.data.map { prefs ->
                val json = prefs[PAUSE_STATE_KEY] ?: return@map PauseState()
                runCatching { PauseState.fromJson(json) }.getOrDefault(PauseState())
            }

        override suspend fun getPauseState(): PauseState = pauseState.first()

        override suspend fun pauseUntil(resumeAtEpochMs: Long?) {
            dataStore.edit { prefs ->
                prefs[PAUSE_STATE_KEY] = PauseState(isPaused = true, resumeAtEpochMs = resumeAtEpochMs).toJson()
            }
            settingsChangeLogger.submit("pause_state", "", "x") { _, _ ->
                if (resumeAtEpochMs == null) {
                    "Remote control paused until resumed"
                } else {
                    "Remote control paused until ${Instant.ofEpochMilli(resumeAtEpochMs)}"
                }
            }
        }

        override suspend fun resume() {
            dataStore.edit { prefs -> prefs[PAUSE_STATE_KEY] = PauseState().toJson() }
            settingsChangeLogger.submit("pause_state", "x", "") { _, _ -> "Remote control resumed" }
        }

        private companion object {
            private val PAUSE_STATE_KEY = stringPreferencesKey("pause_state")
        }
    }
