package com.danielealbano.androidremotecontrolmcp.services.apps

import com.danielealbano.androidremotecontrolmcp.data.model.AppInfo

/**
 * Manages application lifecycle operations: listing, launching, and closing apps.
 */
interface AppManager {
    /**
     * Launches an application by its package ID.
     *
     * @param packageId The application package name (e.g., "com.example.app").
     * @return [Result.success] if the app was launched, [Result.failure] if not found
     *         or the app has no launchable activity.
     */
    suspend fun openApp(packageId: String): Result<Unit>

    /**
     * Kills a background application process.
     *
     * Uses [android.app.ActivityManager.killBackgroundProcesses].
     * This only works for apps that are currently in the background.
     * For foreground apps, use the `press_home` MCP tool first to send
     * the app to the background, then call this method.
     *
     * @param packageId The application package name.
     * @return [Result.success] always (killBackgroundProcesses is fire-and-forget).
     */
    suspend fun closeApp(packageId: String): Result<Unit>
}
