package com.danielealbano.androidremotecontrolmcp.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Kill-switch slice (round 3 phone-feedback regression, plan 71): whether the floating control
 * bar overlay (D-31/D-39) is shown at all. Default `false` - see [ControlBarSettingsImpl] - found
 * live on a real OnePlus: every tap/`type_text` step failed with "Only the original thread that
 * created a view hierarchy can touch its views" because the bar's touch-passthrough toggle ran off
 * the main thread, breaking automation entirely. This setting lets the bar be disabled with NO
 * behavior difference versus a build that never had it, while that (and other lifecycle
 * regressions) are fixed and re-verified before the bar defaults back on.
 */
interface ControlBarSettings {
    val controlBarEnabled: Flow<Boolean>

    suspend fun getControlBarEnabled(): Boolean

    suspend fun setControlBarEnabled(enabled: Boolean)
}
