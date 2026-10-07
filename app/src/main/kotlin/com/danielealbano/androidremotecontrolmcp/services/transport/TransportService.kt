package com.danielealbano.androidremotecontrolmcp.services.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Foreground service holding the M2 device transport open, same shape as
 *  [com.danielealbano.androidremotecontrolmcp.services.channel.EventChannelService] — `START_STICKY`,
 *  a persistent low-importance notification, driven by [SettingsRepository]'s transport config. */
@AndroidEntryPoint
class TransportService : Service() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var transportClient: DeviceTransportClient

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Guards against a second ACTION_START (e.g. a redelivered START_STICKY intent after process
    // death) launching a second, permanently-running pair of collectors alongside the first.
    @Volatile
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> if (!started) handleStart()
        }
        return START_STICKY
    }

    private fun handleStart() {
        started = true
        createNotificationChannel()
        // Built from the un-paused default rather than a synchronous DataStore read — startForeground
        // must run within 5s of onStartCommand (service lifecycle rule), and the pauseState collector
        // below corrects this within the same start-up burst if the device was actually paused.
        startForeground(
            NOTIFICATION_ID,
            buildForegroundNotification(PauseState()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        serviceScope.launch {
            val config = settingsRepository.getTransportConfig()
            if (config.host.isBlank()) {
                Logger.e(TAG, "Cannot start: server host is empty")
                stopSelf()
                return@launch
            }

            transportClient.start(config.host, config.port, config.tls)
            Logger.i(TAG, "Transport started, target=${config.host}:${config.port}")

            serviceScope.launch {
                transportClient.status.collect { _serviceStatus.value = it }
            }

            // Reflects a pause toggled from either the in-app control or a notification action
            // button into the notification itself — both paths only ever write PauseSettings,
            // never touch the notification directly.
            serviceScope.launch {
                settingsRepository.pauseState.collect { pauseState ->
                    updateNotification(pauseState)
                }
            }

            settingsRepository.transportConfig.collect { newConfig ->
                if (!newConfig.enabled) {
                    handleStop()
                    return@collect
                }
                if (newConfig.host != config.host || newConfig.port != config.port || newConfig.tls != config.tls) {
                    transportClient.start(newConfig.host, newConfig.port, newConfig.tls)
                }
            }
        }
    }

    private fun handleStop() {
        started = false
        transportClient.stop()
        _serviceStatus.value = TransportStatus.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Remote Control", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(pauseState: PauseState) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildForegroundNotification(pauseState))
    }

    /** Not-paused: a "Pause" trio (1 hour / until tomorrow / indefinitely) so the owner never
     *  needs to open the app to pause it. Paused: a single "Resume", and the title names when
     *  (or whether) it comes back on its own — the only state this notification needs to
     *  communicate now that Start/Stop no longer exists (design doc §8.8 revision). */
    private fun buildForegroundNotification(pauseState: PauseState): Notification {
        val paused = pauseState.isEffectivePause(System.currentTimeMillis())
        val builder =
            Notification
                .Builder(this, CHANNEL_ID)
                .setContentTitle(if (paused) pausedTitle(pauseState) else "Remote control active")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
        if (paused) {
            builder.addAction(action("Resume", TransportPauseActionReceiver.ACTION_RESUME, RC_RESUME))
        } else {
            builder.addAction(action("1 hour", TransportPauseActionReceiver.ACTION_PAUSE_1H, RC_PAUSE_1H))
            builder.addAction(
                action("Until tomorrow", TransportPauseActionReceiver.ACTION_PAUSE_UNTIL_TOMORROW, RC_PAUSE_TOMORROW),
            )
            builder.addAction(
                action("Pause", TransportPauseActionReceiver.ACTION_PAUSE_INDEFINITELY, RC_PAUSE_INDEFINITE),
            )
        }
        return builder.build()
    }

    private fun pausedTitle(pauseState: PauseState): String {
        val resumeAt = pauseState.resumeAtEpochMs ?: return "Remote control paused"
        val time =
            DateTimeFormatter
                .ofPattern("MMM d, HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(resumeAt))
        return "Remote control paused until $time"
    }

    private fun action(
        label: String,
        intentAction: String,
        requestCode: Int,
    ): Notification.Action {
        val intent = Intent(this, TransportPauseActionReceiver::class.java).apply { action = intentAction }
        val pendingIntent =
            PendingIntent.getBroadcast(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val icon = Icon.createWithResource(this, R.drawable.ic_notification)
        return Notification.Action.Builder(icon, label, pendingIntent).build()
    }

    override fun onDestroy() {
        started = false
        transportClient.stop()
        serviceScope.cancel()
        _serviceStatus.value = TransportStatus.Idle
        Logger.i(TAG, "Transport service destroyed")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.danielealbano.androidremotecontrolmcp.transport.START"

        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "transport_status"
        private const val TAG = "MCP:TransportService"

        private const val RC_PAUSE_1H = 1
        private const val RC_PAUSE_TOMORROW = 2
        private const val RC_PAUSE_INDEFINITE = 3
        private const val RC_RESUME = 4

        private val _serviceStatus = MutableStateFlow<TransportStatus>(TransportStatus.Idle)
        val serviceStatus: StateFlow<TransportStatus> = _serviceStatus.asStateFlow()
    }
}
