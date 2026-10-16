package com.danielealbano.androidremotecontrolmcp.services.power

/** Access to the background-run states that can silently kill the connection. */
interface BatteryOptimizationManager {
    /** True when the app is exempt from Doze battery optimization. */
    fun isIgnoringBatteryOptimizations(): Boolean

    /** True when Android has restricted the app's background activity (API 28+). */
    fun isBackgroundRestricted(): Boolean

    /**
     * Launch the flavor-appropriate exemption flow (direct request on gms, settings list on foss).
     * MUST be called while the app is in the foreground (invoked from a button tap). Implementations
     * start the Activity from the injected application context with FLAG_ACTIVITY_NEW_TASK, so no
     * Activity reference is passed in.
     */
    fun requestExemption()
}
