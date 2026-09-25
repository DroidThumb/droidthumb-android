package com.danielealbano.androidremotecontrolmcp

import android.app.Application
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.apps.AppIconCache
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class McpApplication : Application() {
    @Inject
    lateinit var appIconCache: AppIconCache

    override fun onCreate() {
        super.onCreate()
        appIconCache.preload()
        Log.i(TAG, "Application initialized")
    }

    companion object {
        private const val TAG = "MCP:Application"
    }
}
