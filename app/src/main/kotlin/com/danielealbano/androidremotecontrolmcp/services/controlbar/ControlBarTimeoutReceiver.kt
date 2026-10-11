package com.danielealbano.androidremotecontrolmcp.services.controlbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Delivery target for [ControlBarTimeoutSchedulerImpl]'s `AlarmManager` alarms - see
 *  [ControlBarTimeoutScheduler]'s doc comment. Explicit-only (no `<intent-filter>`,
 *  `android:exported="false"`): nothing but this app's own `PendingIntent`s ever target it. */
@AndroidEntryPoint
class ControlBarTimeoutReceiver : BroadcastReceiver() {
    @Inject lateinit var controlBarCoordinator: ControlBarCoordinator

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val token = intent.getLongExtra(EXTRA_TOKEN, NO_TOKEN)
        when (intent.action) {
            ACTION_FALLBACK_END -> controlBarCoordinator.onFallbackTimerFired(token)
            ACTION_ENDED_AUTO_CLOSE -> controlBarCoordinator.onEndedAutoCloseFired(token)
            else -> Logger.w(TAG, "Ignoring unknown action: ${intent.action}")
        }
    }

    companion object {
        const val ACTION_FALLBACK_END = "com.danielealbano.androidremotecontrolmcp.controlbar.FALLBACK_END"
        const val ACTION_ENDED_AUTO_CLOSE = "com.danielealbano.androidremotecontrolmcp.controlbar.ENDED_AUTO_CLOSE"

        /** See [ControlBarTimeoutScheduler]'s doc comment on `token` for why this exists - staleness
         *  detection for an alarm the OS already dispatched before an in-process cancel could stop
         *  it. */
        const val EXTRA_TOKEN = "token"
        private const val NO_TOKEN = -1L
        private const val TAG = "MCP:ControlBarTimeout"
    }
}
