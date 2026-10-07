package com.danielealbano.androidremotecontrolmcp.services.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.data.model.PauseDeadlines
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the Pause/Resume action buttons on the persistent "Remote control" notification
 * (design doc §8.8 revision) — the same three options the Home status indicator's in-app pause
 * dialog offers. [TransportService] observes [SettingsRepository.pauseState] itself and rebuilds
 * the notification on any change, so a tap here updates it the same way an in-app tap does.
 */
@AndroidEntryPoint
class TransportPauseActionReceiver : BroadcastReceiver() {
    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var transportAutoStart: TransportAutoStart

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val resumeAtEpochMs =
            when (intent.action) {
                ACTION_PAUSE_1H -> {
                    System.currentTimeMillis() + ONE_HOUR_MS
                }

                ACTION_PAUSE_UNTIL_TOMORROW -> {
                    PauseDeadlines.startOfNextLocalDayEpochMs()
                }

                ACTION_PAUSE_INDEFINITELY -> {
                    null
                }

                // Resuming only clears PauseSettings - it does not by itself guarantee the service
                // is running (it never was, if the owner paused before ever signing in).
                // TransportAutoStart re-runs the same signed-in+accessible+not-paused check
                // completing onboarding does.
                ACTION_RESUME -> {
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        settingsRepository.resume()
                        transportAutoStart.maybeStart(context)
                    }
                    return
                }

                else -> {
                    Logger.w(TAG, "Ignoring unknown action: ${intent.action}")
                    return
                }
            }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settingsRepository.pauseUntil(resumeAtEpochMs)
        }
    }

    companion object {
        const val ACTION_PAUSE_1H = "com.danielealbano.androidremotecontrolmcp.transport.PAUSE_1H"
        const val ACTION_PAUSE_UNTIL_TOMORROW = "com.danielealbano.androidremotecontrolmcp.transport.PAUSE_TOMORROW"
        const val ACTION_PAUSE_INDEFINITELY = "com.danielealbano.androidremotecontrolmcp.transport.PAUSE_INDEFINITE"
        const val ACTION_RESUME = "com.danielealbano.androidremotecontrolmcp.transport.RESUME"

        private const val TAG = "MCP:TransportPauseAction"
        private const val ONE_HOUR_MS = 60 * 60 * 1000L
    }
}
