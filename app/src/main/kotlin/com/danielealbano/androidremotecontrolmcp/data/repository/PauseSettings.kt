package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import kotlinx.coroutines.flow.Flow

/**
 * Pause slice of the settings surface (design doc §8.8 revision): the only user control over the
 * transport is Pause, not Start/Stop — see [PauseState] for what "paused" means. Same split as
 * [TransportSettings]/[AccountSettings].
 */
interface PauseSettings {
    val pauseState: Flow<PauseState>

    suspend fun getPauseState(): PauseState

    /** Pauses until [resumeAtEpochMs] (a timed pause), or indefinitely when `null` ("until I
     *  resume") — the caller computes the deadline; this layer only persists it. */
    suspend fun pauseUntil(resumeAtEpochMs: Long?)

    suspend fun resume()
}
