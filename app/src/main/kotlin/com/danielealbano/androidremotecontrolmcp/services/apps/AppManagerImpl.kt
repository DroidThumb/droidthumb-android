package com.danielealbano.androidremotecontrolmcp.services.apps

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
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
    ) : AppManager {
        override suspend fun openApp(packageId: String): Result<Unit> =
            try {
                val intent =
                    context.packageManager.getLaunchIntentForPackage(packageId)
                        ?: return Result.failure(
                            IllegalArgumentException("No launchable activity found for package '$packageId'"),
                        )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Launched application: $packageId")
                Result.success(Unit)
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "Activity not found for package: $packageId", e)
                Result.failure(e)
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception launching package: $packageId", e)
                Result.failure(e)
            }

        override suspend fun closeApp(packageId: String): Result<Unit> {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.killBackgroundProcesses(packageId)
            Log.i(TAG, "Requested kill of background processes for: $packageId")
            return Result.success(Unit)
        }

        companion object {
            private const val TAG = "MCP:AppManager"
        }
    }
