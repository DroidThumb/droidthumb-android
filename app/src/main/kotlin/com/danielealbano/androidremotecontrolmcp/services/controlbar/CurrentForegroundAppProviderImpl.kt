package com.danielealbano.androidremotecontrolmcp.services.controlbar

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.apps.AppLabelResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves "what's in the foreground right now" for [ControlBarCoordinator]'s return-target
 *  capture - the launcher package is resolved once and cached (it never changes at runtime). */
@Singleton
class CurrentForegroundAppProviderImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val appLabelResolver: AppLabelResolver,
    ) : CurrentForegroundAppProvider {
        private val launcherPackage: String? by lazy { resolveLauncherPackage() }

        override fun current(): ReturnTarget {
            val pkg = accessibilityServiceProvider.getCurrentPackageName()
            return if (pkg == null || pkg == launcherPackage) {
                ReturnTarget(packageName = null, label = "Home")
            } else {
                ReturnTarget(packageName = pkg, label = appLabelResolver.labelFor(pkg))
            }
        }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private fun resolveLauncherPackage(): String? =
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val resolved =
                    context.packageManager.resolveActivity(
                        homeIntent,
                        PackageManager.ResolveInfoFlags.of(0),
                    )
                resolved?.activityInfo?.packageName
            } catch (e: Exception) {
                null
            }
    }
