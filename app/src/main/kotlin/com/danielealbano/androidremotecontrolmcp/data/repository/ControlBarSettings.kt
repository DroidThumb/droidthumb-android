package com.danielealbano.androidremotecontrolmcp.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Kill-switch slice (round 3 phone-feedback regression, plan 71): whether the floating control
 * bar overlay (D-31/D-39) is shown at all - see [ControlBarSettingsImpl] for the current default
 * and why. Exists because every tap/`type_text` step was once found, live on a real OnePlus, to
 * fail with "Only the original thread that created a view hierarchy can touch its views" (the
 * bar's touch-passthrough toggle ran off the main thread, breaking automation entirely) - setting
 * this to `false` disables the bar with NO behavior difference versus a build that never had it,
 * an escape hatch kept for the same class of regression in the future.
 */
interface ControlBarSettings {
    val controlBarEnabled: Flow<Boolean>

    suspend fun getControlBarEnabled(): Boolean

    suspend fun setControlBarEnabled(enabled: Boolean)
}
