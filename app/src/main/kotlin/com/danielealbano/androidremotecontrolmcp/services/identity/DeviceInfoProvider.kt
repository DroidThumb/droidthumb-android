package com.danielealbano.androidremotecontrolmcp.services.identity

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/** Real values for `hello.android_version`/`hello.device_model` — behind an interface (not
 *  `Build.*` read directly in `DeviceTransportClientImpl`) so the handshake is unit-testable
 *  without real `Build` values. */
interface DeviceInfoProvider {
    val androidVersion: Int
    val deviceModel: String
}

// hello.schema.json device_model maxLength (droidthumb-protocol) — truncated defensively so an
// unusually long Build.MANUFACTURER/Build.MODEL can never make hello schema-invalid.
internal const val MAX_DEVICE_MODEL_LENGTH = 128

/** Extracted so the manufacturer+model/trim/truncate rule is unit-testable without a real `Build`
 *  value. */
internal fun formatDeviceModel(
    manufacturer: String,
    model: String,
): String = "$manufacturer $model".trim().take(MAX_DEVICE_MODEL_LENGTH)

/** `deviceModel` is manufacturer + model per android#5 ("Google Pixel 8", not just "Pixel 8"). */
@Singleton
class DefaultDeviceInfoProvider
    @Inject
    constructor() : DeviceInfoProvider {
        override val androidVersion: Int = Build.VERSION.SDK_INT
        override val deviceModel: String = formatDeviceModel(Build.MANUFACTURER, Build.MODEL)
    }
