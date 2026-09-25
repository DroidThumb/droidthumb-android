package com.danielealbano.androidremotecontrolmcp.services.power

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Opens the battery-optimization settings list rather than requesting the one-tap system
 * exemption dialog: the dialog needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which Google Play
 * restricts to a narrow set of use cases and F-Droid flags outright. This was previously the
 * `foss`-only implementation; the app is single-flavour since the demolition pass, so it is now
 * the only one.
 */
class BatteryOptimizationManagerImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : BatteryOptimizationManager {
        override fun isIgnoringBatteryOptimizations(): Boolean = context.isIgnoringBatteryOptimizations()

        override fun requestExemption() {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
