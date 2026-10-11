package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

/** [PauseSettings] backed by the same Preferences DataStore as [SettingsRepositoryImpl], same
 *  pattern as [TransportSettingsImpl]/[AccountSettingsImpl].
 *
 *  [pauseHoldCapMs] default `60_000L` (round 4 phone-feedback redesign): while paused, a held step
 *  waits for Resume instead of failing immediately - a cap exists because an AI client's own
 *  tool-call timeout is finite and unknown to this app, so holding forever would eventually read
 *  to the AI as a hang indistinguishable from a crashed device, worse than a clear, fast
 *  `device_paused` rejection. 60s was chosen as a starting point reasonably likely to survive a
 *  short "let me just fix this CAPTCHA/2FA myself" pause without yet being so long that it risks
 *  that hang-like read on a client with a shorter timeout than hoped - no visible-UI toggle exists
 *  for it yet (same as [ControlBarSettingsImpl]'s kill-switch), only this persisted value, kept
 *  configurable from day one specifically because the real number worth landing on depends on
 *  empirical, as-yet-ongoing testing of what real MCP clients (claude.ai among them) actually
 *  tolerate - see `docs/droidthumb-design-doc.md` (droidthumb-server) for that finding. */
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

        override val pauseHoldCapMs: Flow<Long> =
            dataStore.data.map { prefs -> prefs[PAUSE_HOLD_CAP_MS_KEY] ?: DEFAULT_PAUSE_HOLD_CAP_MS }

        override suspend fun getPauseHoldCapMs(): Long = pauseHoldCapMs.first()

        /** @throws IllegalArgumentException if [capMs] isn't positive (code-review finding: a
         *  zero/negative value makes `withTimeoutOrNull(capMs)` in `DeviceTransportClientImpl
         *  .awaitResumeOrCapExpired` behave as an immediate, always-expired timeout - not a crash,
         *  but a nonsensical persisted value no caller should be able to set). */
        override suspend fun setPauseHoldCapMs(capMs: Long) {
            require(capMs > 0) { "pauseHoldCapMs must be positive, got $capMs" }
            val old = getPauseHoldCapMs()
            dataStore.edit { prefs -> prefs[PAUSE_HOLD_CAP_MS_KEY] = capMs }
            settingsChangeLogger.submit("pause_hold_cap_ms", old.toString(), capMs.toString()) { _, n ->
                "Pause hold cap set to ${n}ms"
            }
        }

        private companion object {
            private val PAUSE_STATE_KEY = stringPreferencesKey("pause_state")
            private val PAUSE_HOLD_CAP_MS_KEY = longPreferencesKey("pause_hold_cap_ms")
            const val DEFAULT_PAUSE_HOLD_CAP_MS = 60_000L
        }
    }
