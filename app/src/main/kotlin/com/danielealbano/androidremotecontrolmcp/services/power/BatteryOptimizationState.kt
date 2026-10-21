package com.danielealbano.androidremotecontrolmcp.services.power

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager

internal fun Context.isIgnoringBatteryOptimizations(): Boolean =
    (getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(packageName)

/**
 * True when Android has restricted this app's background activity — separate from the Doze
 * exemption above, and the likely reason Home showed no card while the connection still dropped
 * (17 Oct phone test). `isBackgroundRestricted()` exists since API 28; minSdk is 33, so no
 * version guard is needed.
 */
internal fun Context.isBackgroundRestricted(): Boolean =
    (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
