package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val pauseJson = Json { ignoreUnknownKeys = true }

/**
 * Whether the device owner has paused remote control (design doc §8.8 revision: the only user
 * control over the transport is Pause, not Start/Stop — it starts automatically once signed in
 * and accessible, and stays running across reboots/app updates). [resumeAtEpochMs] is the wall-clock
 * deadline for a timed pause ("for 1 hour", "until tomorrow"); `null` while [isPaused] means paused
 * indefinitely ("until I resume").
 */
@Serializable
data class PauseState(
    val isPaused: Boolean = false,
    val resumeAtEpochMs: Long? = null,
) {
    /**
     * Whether the device is actually paused right now. A timed pause whose deadline has already
     * passed is treated as resumed even though the stored flag hasn't been explicitly cleared yet
     * — the caller (the transport's step gate, the autostart check, the UI) always asks "now",
     * never trusts [isPaused] alone.
     */
    fun isEffectivePause(nowEpochMs: Long): Boolean =
        isPaused &&
            (resumeAtEpochMs == null || nowEpochMs < resumeAtEpochMs)

    companion object {
        fun fromJson(json: String): PauseState = pauseJson.decodeFromString(serializer(), json)

        fun fromJsonOrDefault(json: String): PauseState =
            try {
                fromJson(json)
            } catch (_: Exception) {
                PauseState()
            }
    }

    fun toJson(): String = pauseJson.encodeToString(serializer(), this)
}
