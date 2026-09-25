package com.danielealbano.androidremotecontrolmcp.services.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.danielealbano.androidremotecontrolmcp.R
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
            ACTION_STOP -> handleStop()
        }
        return START_STICKY
    }

    private fun handleStart() {
        started = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        serviceScope.launch {
            val config = settingsRepository.getTransportConfig()
            if (config.host.isBlank()) {
                Logger.e(TAG, "Cannot start: server host is empty")
                stopSelf()
                return@launch
            }

            transportClient.start(config.host, config.port, config.deviceId)
            Logger.i(TAG, "Transport started, target=${config.host}:${config.port}")

            serviceScope.launch {
                transportClient.status.collect { _serviceStatus.value = it }
            }

            settingsRepository.transportConfig.collect { newConfig ->
                if (!newConfig.enabled) {
                    handleStop()
                    return@collect
                }
                if (newConfig.host != config.host || newConfig.port != config.port) {
                    transportClient.start(newConfig.host, newConfig.port, newConfig.deviceId)
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

    private fun buildForegroundNotification(): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setContentTitle("Remote control active")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

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
        const val ACTION_STOP = "com.danielealbano.androidremotecontrolmcp.transport.STOP"

        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "transport_status"
        private const val TAG = "MCP:TransportService"

        private val _serviceStatus = MutableStateFlow<TransportStatus>(TransportStatus.Idle)
        val serviceStatus: StateFlow<TransportStatus> = _serviceStatus.asStateFlow()
    }
}
