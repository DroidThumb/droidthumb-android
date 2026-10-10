package com.danielealbano.androidremotecontrolmcp.services.apps

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AppLabelResolverImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AppLabelResolver {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        override fun labelFor(packageName: String): String =
            try {
                val flags = PackageManager.ApplicationInfoFlags.of(0)
                val appInfo = context.packageManager.getApplicationInfo(packageName, flags)
                context.packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }
    }
