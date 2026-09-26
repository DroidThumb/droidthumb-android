package com.danielealbano.androidremotecontrolmcp.services.apps

/**
 * Manages application lifecycle operations: listing, launching, and closing apps.
 */
interface AppManager {
    /**
     * Launches an application by its package ID.
     *
     * @param packageId The application package name (e.g., "com.example.app").
     * @param fresh When true, clears the app's existing task first (`FLAG_ACTIVITY_CLEAR_TASK`)
     *   so it starts at its launcher activity instead of resuming wherever it was left — M3's
     *   "known start state" fix (droidthumb-server/docs/decisions-log.md): `launch_app` used to
     *   always resume the existing task, so a flow authored against "the app just opened" could
     *   silently land on whatever screen a previous session left it on.
     * @return [Result.success] if the app was launched, [Result.failure] if not found
     *         or the app has no launchable activity.
     */
    suspend fun openApp(
        packageId: String,
        fresh: Boolean = false,
    ): Result<Unit>

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
