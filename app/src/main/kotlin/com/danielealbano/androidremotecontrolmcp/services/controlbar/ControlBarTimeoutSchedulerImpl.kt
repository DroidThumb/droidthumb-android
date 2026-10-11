package com.danielealbano.androidremotecontrolmcp.services.controlbar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** See [ControlBarTimeoutScheduler]'s doc comment for why this exists alongside (not instead of)
 *  [ControlBarCoordinator]'s own coroutine timers. [SystemClock.elapsedRealtime] (not wall-clock
 *  time) is the correct base for [AlarmManager.ELAPSED_REALTIME_WAKEUP] - it doesn't jump on a
 *  user-initiated clock change the way `System.currentTimeMillis()` would. */
@Singleton
class ControlBarTimeoutSchedulerImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ControlBarTimeoutScheduler {
        private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)
        private val fallbackEndAction = ControlBarTimeoutReceiver.ACTION_FALLBACK_END
        private val endedAutoCloseAction = ControlBarTimeoutReceiver.ACTION_ENDED_AUTO_CLOSE

        override fun scheduleFallbackEnd(
            delayMs: Long,
            token: Long,
        ) = schedule(fallbackEndAction, delayMs, token)

        override fun cancelFallbackEnd() = cancel(fallbackEndAction)

        override fun scheduleEndedAutoClose(
            delayMs: Long,
            token: Long,
        ) = schedule(endedAutoCloseAction, delayMs, token)

        override fun cancelEndedAutoClose() = cancel(endedAutoCloseAction)

        /** [AlarmManager.setAndAllowWhileIdle] (NOT `setExactAndAllowWhileIdle`) - the exact variant
         *  needs `SCHEDULE_EXACT_ALARM`, a sensitive special-access permission that is NOT granted
         *  by default on API 31+ and must be separately enabled by the owner in system Settings,
         *  for a feature that doesn't need exact delivery - this is a worst-case backstop, not a
         *  clock/calendar alarm, so a few seconds/minutes of OS-imposed slack is fine. Found live on
         *  redroid: requesting the exact variant without that permission throws a
         *  `SecurityException` from EVERY call to [ControlBarCoordinator.beginStep] (since it's
         *  reached by every op's own fallback-timer arm), breaking every tap/`type_text` step, not
         *  just this backstop - [runCatching] below is a second, independent layer of defense so a
         *  missing permission (or any other `AlarmManager` failure on a given OEM) can never again
         *  take down step dispatch, only silently drop this best-effort backstop. */
        private fun schedule(
            action: String,
            delayMs: Long,
            token: Long,
        ) {
            val manager = alarmManager ?: return
            runCatching {
                manager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs,
                    pendingIntentFor(action, token),
                )
            }
        }

        /** `token` doesn't matter for a cancel - [PendingIntent] equality (what `AlarmManager
         *  .cancel` matches against) is based on action/component/data/categories, never on
         *  `Intent` extras, so any value finds the same pending alarm `schedule` registered. */
        private fun cancel(action: String) {
            runCatching { alarmManager?.cancel(pendingIntentFor(action, token = 0L)) }
        }

        private fun pendingIntentFor(
            action: String,
            token: Long,
        ): PendingIntent {
            val intent =
                Intent(context, ControlBarTimeoutReceiver::class.java)
                    .setAction(action)
                    .putExtra(ControlBarTimeoutReceiver.EXTRA_TOKEN, token)
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
