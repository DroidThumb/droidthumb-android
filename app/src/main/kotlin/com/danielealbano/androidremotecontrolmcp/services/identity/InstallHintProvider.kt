package com.danielealbano.androidremotecontrolmcp.services.identity

import android.content.Context
import android.provider.Settings
import javax.inject.Inject
import javax.inject.Singleton

/** `sign_in.install_hint` — Android's per-app device ID (ANDROID_ID), which the server keeps only
 *  a keyed hash of to recognise the same phone after a reinstall (sign-in.schema.json). Behind an
 *  interface (not a `Settings.Secure` read directly in `DeviceTransportClientImpl`) so the
 *  handshake is unit-testable without a real Android device; ANDROID_ID is per-app-scope and
 *  readable without any permission. */
interface InstallHintProvider {
    val installHint: String
}

@Singleton
class DefaultInstallHintProvider
    @Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    ) : InstallHintProvider {
        override val installHint: String =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    }
