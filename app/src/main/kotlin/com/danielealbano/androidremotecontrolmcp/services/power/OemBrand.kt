package com.danielealbano.androidremotecontrolmcp.services.power

import android.content.ComponentName
import android.content.Intent

/**
 * Several Android OEMs run their own background-app manager on top of (not instead of) the
 * standard `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` exemption this app already requests —
 * allowing that standard exemption is not enough on these brands to keep the transport connection
 * alive while the phone is idle (founder phone-test feedback, PR #9 round 1, reproduced on a
 * OnePlus). [oemSettingsIntent]'s component names are the OEMs' own system-app activities,
 * community-documented (not part of the public Android SDK, so unofficial and can change across
 * an OEM's own software updates) — every caller MUST try/catch around `startActivity` with these
 * and fall back to the standard battery-settings screen if resolution fails, never assume success.
 */
enum class OemBrand {
    XIAOMI,
    ONEPLUS_OPPO_REALME,
    VIVO,
    HUAWEI_HONOR,
    SAMSUNG,
    OTHER,
}

fun detectOemBrand(manufacturer: String): OemBrand {
    val m = manufacturer.lowercase()
    return when {
        m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> OemBrand.XIAOMI
        m.contains("oneplus") || m.contains("oppo") || m.contains("realme") -> OemBrand.ONEPLUS_OPPO_REALME
        m.contains("vivo") || m.contains("iqoo") -> OemBrand.VIVO
        m.contains("huawei") || m.contains("honor") -> OemBrand.HUAWEI_HONOR
        m.contains("samsung") -> OemBrand.SAMSUNG
        else -> OemBrand.OTHER
    }
}

/** `null` for [OemBrand.OTHER] — nothing extra to offer beyond the standard exemption. Builds the
 *  `Intent` via the `component` property, not the chaining `setComponent(...)` method - the
 *  Android Gradle Plugin's unit-test stub jar (`isReturnDefaultValues = true`) returns `null` from
 *  stubbed non-primitive methods including `setComponent`, which would make every call here look
 *  like it returned null in a JVM unit test even though the real on-device method correctly
 *  returns `this` for chaining - found exactly that way while writing this function's own test. */
fun oemSettingsIntent(brand: OemBrand): Intent? {
    val component =
        when (brand) {
            OemBrand.XIAOMI -> {
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
            }

            OemBrand.ONEPLUS_OPPO_REALME -> {
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                )
            }

            OemBrand.VIVO -> {
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                )
            }

            OemBrand.HUAWEI_HONOR -> {
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                )
            }

            OemBrand.SAMSUNG -> {
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                )
            }

            OemBrand.OTHER -> {
                return null
            }
        }
    return Intent().apply { this.component = component }
}

/** `null` for [OemBrand.OTHER]. */
fun oemGuidanceText(brand: OemBrand): String? =
    when (brand) {
        OemBrand.XIAOMI -> {
            "MIUI can still stop DroidThumb in the background even after allowing the battery exemption above. " +
                "Open Security › Permissions › Autostart and turn DroidThumb on."
        }

        OemBrand.ONEPLUS_OPPO_REALME -> {
            "OnePlus/Oppo/Realme phones manage background apps separately from the battery exemption above. " +
                "Open Settings › Battery › App auto-launch (or App management › Auto-launch apps) and allow DroidThumb."
        }

        OemBrand.VIVO -> {
            "Vivo phones manage background apps separately from the battery exemption above. " +
                "Open Settings › Battery › Background power consumption management and allow DroidThumb."
        }

        OemBrand.HUAWEI_HONOR -> {
            "Huawei/Honor phones manage background apps separately from the battery exemption above. " +
                "Open Settings › Battery › App launch and enable auto-launch/manage manually for DroidThumb."
        }

        OemBrand.SAMSUNG -> {
            "Samsung can also put unused apps to sleep. Open Settings › Battery › Background usage limits and " +
                "make sure DroidThumb isn't in the sleeping apps list."
        }

        OemBrand.OTHER -> {
            null
        }
    }
