package com.danielealbano.androidremotecontrolmcp.utils

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Utility functions for checking and requesting Android permissions.
 */
object PermissionUtils {
    private const val TAG = "MCP:PermissionUtils"
    private const val ENABLED_SERVICES_SEPARATOR = ':'
    private const val COMPONENT_SEPARATOR = '/'
    private const val SHORT_FORM_CLASS_PREFIX = '.'

    /** AOSP Settings' undocumented extra keys that make the accessibility settings screen
     *  highlight/scroll to a specific service row (used below API 31, where the per-service
     *  details screen doesn't exist). */
    internal const val FRAGMENT_ARGS_KEY_EXTRA = ":settings:fragment_args_key"
    internal const val SHOW_FRAGMENT_ARGS_EXTRA = ":settings:show_fragment_args"

    /**
     * Checks whether a specific accessibility service is currently enabled.
     *
     * Reads the `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` system setting
     * and checks if the given service class is listed.
     *
     * @param context Application context.
     * @param serviceClass The accessibility service class to check (e.g., `McpAccessibilityService::class.java`).
     * @return `true` if the service is enabled, `false` otherwise.
     */
    fun isAccessibilityServiceEnabled(
        context: Context,
        serviceClass: Class<*>,
    ): Boolean {
        val enabledServices =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

        return isServiceListed(enabledServices, context.packageName, serviceClass)
    }

    /**
     * Opens the Android Accessibility Settings screen.
     *
     * @param context Application context. Uses [Intent.FLAG_ACTIVITY_NEW_TASK]
     *   so this can be called from non-Activity contexts.
     */
    fun openAccessibilitySettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    /**
     * Builds an intent that opens the accessibility settings page for this app's own
     * accessibility service directly, so a user can re-enable the service in one tap.
     *
     * Pure and testable: the [sdkInt] parameter is passed in by callers (Build.VERSION.SDK_INT)
     * so unit tests can drive both branches without a Robolectric device.
     *
     * On API 31+ this is `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` targeting our service's
     * [ComponentName] via [Intent.EXTRA_COMPONENT_NAME]. Below API 31 the per-service details
     * screen does not exist, so this returns the plain accessibility-settings intent carrying
     * AOSP's undocumented fragment-args extras, which make the Settings app highlight and scroll
     * to our service's row when it opens.
     *
     * @param packageName The app's package name (`context.packageName`).
     * @param serviceClass The accessibility service class (e.g. `McpAccessibilityService::class.java`).
     * @param sdkInt The device's `Build.VERSION.SDK_INT`.
     */
    fun buildAccessibilityServiceSettingsIntent(
        packageName: String,
        serviceClass: Class<*>,
        sdkInt: Int,
    ): Intent {
        // NOTE: ComponentName(packageName, serviceClass.name), NOT ComponentName(serviceClass) -
        // the latter resolves the class's own package (a subpackage here), which would flatten to
        // the wrong "package/class" key for ENABLED_ACCESSIBILITY_SERVICES matching.
        val componentName = ComponentName(packageName, serviceClass.name)
        return if (sdkInt >= Build.VERSION_CODES.S) {
            // API 31+ per-service details screen. NOTE: the action string is
            // Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS in AOSP, but that constant is not
            // exposed by the public SDK stubs (verified against the compile SDK's android.jar),
            // so the equivalent literal is used here. If a build doesn't resolve it,
            // openAccessibilityServiceSettings falls back to the plain accessibility settings.
            Intent().apply {
                setAction("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)
            }
        } else {
            Intent().apply {
                setAction(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                val key = componentName.flattenToString()
                putExtra(FRAGMENT_ARGS_KEY_EXTRA, key)
                putExtra(
                    SHOW_FRAGMENT_ARGS_EXTRA,
                    Bundle().apply { putString(FRAGMENT_ARGS_KEY_EXTRA, key) },
                )
            }
        }
    }

    /**
     * Opens the accessibility settings page for this app's own accessibility service directly.
     *
     * Uses [buildAccessibilityServiceSettingsIntent] on API 31+ and falls back to
     * [openAccessibilitySettings] (the plain accessibility settings screen) below API 31, or
     * whenever the details intent fails to resolve (some OEM Settings builds don't handle it).
     *
     * @param context Any context; `FLAG_ACTIVITY_NEW_TASK` is added so non-Activity contexts work.
     * @param serviceClass The accessibility service class (e.g. `McpAccessibilityService::class.java`).
     */
    fun openAccessibilityServiceSettings(
        context: Context,
        serviceClass: Class<*>,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                context.startActivity(
                    buildAccessibilityServiceSettingsIntent(
                        context.packageName,
                        serviceClass,
                        Build.VERSION.SDK_INT,
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                )
                return
            } catch (e: ActivityNotFoundException) {
                // Log and fall through to the plain accessibility settings screen below.
                Log.w(TAG, "Accessibility details settings not resolvable; falling back", e)
            }
        }
        openAccessibilitySettings(context)
    }

    /**
     * Checks whether the `POST_NOTIFICATIONS` runtime permission is granted.
     *
     * @param context Application context.
     * @return `true` if notification permission is granted, `false` otherwise.
     */
    fun isNotificationPermissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks whether a specific notification listener service is currently enabled.
     *
     * Reads the `Settings.Secure` `enabled_notification_listeners` system setting
     * and checks if the given service class is listed.
     *
     * @param context Application context.
     * @param serviceClass The notification listener service class to check.
     * @return `true` if the service is enabled, `false` otherwise.
     */
    fun isNotificationListenerEnabled(
        context: Context,
        serviceClass: Class<*>,
    ): Boolean {
        val enabledListeners =
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false

        return isServiceListed(enabledListeners, context.packageName, serviceClass)
    }

    /**
     * Checks whether [serviceClass] appears in a colon-separated list of flattened
     * component names ([Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] or
     * `enabled_notification_listeners`).
     *
     * Android stores component names in either the fully-qualified form
     * (`package/package.path.ServiceClass`) or the leading-dot short form
     * (`package/.path.ServiceClass`), expanding the short form against the package
     * internally. This matcher mirrors `ComponentName.unflattenFromString`: a class
     * part beginning with `.` is resolved against its own package part before the
     * comparison, so both encodings of the same component match. Entries with no
     * separator or an empty class part are rejected; an entry with an empty package
     * part is parsed but can never equal a non-empty package, so none of these match.
     *
     * @param enabledEntries Raw colon-separated setting value.
     * @param expectedPackage The package the service belongs to (`context.packageName`).
     * @param serviceClass The service class to look for.
     * @return `true` if any entry resolves to [expectedPackage] + [serviceClass], `false` otherwise.
     */
    private fun isServiceListed(
        enabledEntries: String,
        expectedPackage: String,
        serviceClass: Class<*>,
    ): Boolean {
        val expectedClass = serviceClass.name

        return enabledEntries
            .split(ENABLED_SERVICES_SEPARATOR)
            .any { entry -> componentMatches(entry, expectedPackage, expectedClass) }
    }

    /**
     * Returns `true` when [flattenedComponent] (a single `package/class` entry) resolves
     * to [expectedPackage] and [expectedClass].
     *
     * The separator validation and leading-dot expansion mirror
     * [android.content.ComponentName.unflattenFromString]: an entry with no `/`, or with
     * an empty class part, is rejected; a class part beginning with `.` is prefixed with
     * its own package part. An empty package part is parsed like AOSP but can never equal
     * [expectedPackage] (`context.packageName` is never empty), so such entries never match.
     */
    private fun componentMatches(
        flattenedComponent: String,
        expectedPackage: String,
        expectedClass: String,
    ): Boolean {
        val separatorIndex = flattenedComponent.indexOf(COMPONENT_SEPARATOR)
        if (separatorIndex < 0 || separatorIndex >= flattenedComponent.length - 1) {
            return false
        }

        val packagePart = flattenedComponent.substring(0, separatorIndex)
        val classPart = flattenedComponent.substring(separatorIndex + 1)
        val resolvedClass =
            if (classPart[0] == SHORT_FORM_CLASS_PREFIX) {
                packagePart + classPart
            } else {
                classPart
            }

        return packagePart == expectedPackage && resolvedClass == expectedClass
    }

    /**
     * Opens the Android Notification Listener Settings screen.
     *
     * @param context Application context. Uses [Intent.FLAG_ACTIVITY_NEW_TASK]
     *   so this can be called from non-Activity contexts.
     */
    fun openNotificationListenerSettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    /**
     * Opens this app's own App Info screen — the universal fallback that always resolves, used
     * both by the accessibility onboarding step's restricted-settings detour and by
     * [com.danielealbano.androidremotecontrolmcp.ui.components.OemBackgroundGuidanceSection]'s own
     * settings button when an OEM's undocumented background-manager activity fails to resolve.
     *
     * @param context Application context. Uses [Intent.FLAG_ACTIVITY_NEW_TASK]
     *   so this can be called from non-Activity contexts.
     */
    fun openAppInfoSettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }
}
