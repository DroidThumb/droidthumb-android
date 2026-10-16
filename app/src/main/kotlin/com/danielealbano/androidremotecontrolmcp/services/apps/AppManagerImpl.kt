package com.danielealbano.androidremotecontrolmcp.services.apps

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Default implementation of [AppManager] backed by Android's [PackageManager]
 * and [ActivityManager].
 */
@Suppress("TooGenericExceptionCaught")
class AppManagerImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) : AppManager {
        override suspend fun openApp(
            packageId: String,
            fresh: Boolean,
        ): Result<Unit> =
            try {
                val intent =
                    context.packageManager.getLaunchIntentForPackage(packageId)
                        ?: return Result.failure(
                            IllegalArgumentException("No launchable activity found for package '$packageId'"),
                        )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (fresh) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(intent)
                // 16 Oct phone test: startActivity returning does NOT mean the app is in front -
                // Firefox once stayed in the background while the step chain carried on against
                // the wrong screen. Poll the tracked foreground package (the same
                // AccessibilityServiceProvider source every other foreground read uses) until the
                // requested package is actually in front, then - one retry - declare failure.
                if (awaitForegroundPackage(packageId, FRONT_WAIT_FIRST_MS)) {
                    Log.i(TAG, "Launched application: $packageId (fresh=$fresh)")
                    return Result.success(Unit)
                }
                Log.w(TAG, "$packageId not in front after launch; retrying startActivity once")
                context.startActivity(intent)
                if (awaitForegroundPackage(packageId, FRONT_WAIT_RETRY_MS)) {
                    Log.i(TAG, "Launched application on retry: $packageId (fresh=$fresh)")
                    return Result.success(Unit)
                }
                val foregroundPackage = accessibilityServiceProvider.getCurrentPackageName()
                Log.e(TAG, "App did not come to the front: $packageId (foreground=$foregroundPackage)")
                Result.failure(
                    IllegalStateException(
                        "$packageId did not come to the front; ${foregroundPackage ?: "unknown"} is in front",
                    ),
                )
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "Activity not found for package: $packageId", e)
                Result.failure(e)
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception launching package: $packageId", e)
                Result.failure(e)
            }

        /** Polls the current foreground package every [FRONT_POLL_INTERVAL_MS] for up to
         *  [timeoutMs] total (coroutine delays, never blocking the thread). Reads through
         *  [AccessibilityServiceProvider.getCurrentPackageName] - the same tracked-foreground
         *  source the control bar and the tools use. */
        private suspend fun awaitForegroundPackage(
            packageId: String,
            timeoutMs: Long,
        ): Boolean {
            var waitedMs = 0L
            while (true) {
                if (accessibilityServiceProvider.getCurrentPackageName() == packageId) {
                    return true
                }
                if (waitedMs >= timeoutMs) {
                    return false
                }
                delay(FRONT_POLL_INTERVAL_MS)
                waitedMs += FRONT_POLL_INTERVAL_MS
            }
        }

        override suspend fun closeApp(packageId: String): Result<Unit> {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.killBackgroundProcesses(packageId)
            Log.i(TAG, "Requested kill of background processes for: $packageId")
            return Result.success(Unit)
        }

        companion object {
            private const val TAG = "MCP:AppManager"

            /** Foreground-poll cadence and the two wait budgets (first attempt, retry) - see
             *  [openApp]. */
            internal const val FRONT_POLL_INTERVAL_MS = 100L
            internal const val FRONT_WAIT_FIRST_MS = 5000L
            internal const val FRONT_WAIT_RETRY_MS = 3000L
        }
    }
