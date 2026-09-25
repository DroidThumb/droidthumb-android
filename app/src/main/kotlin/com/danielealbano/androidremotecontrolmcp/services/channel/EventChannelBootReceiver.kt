package com.danielealbano.androidremotecontrolmcp.services.channel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.inject.Inject

/**
 * Receives BOOT_COMPLETED and starts [EventChannelService] when the Event Channel is enabled and
 * has an endpoint (the "Auto-start at boot" switch in the channel settings).
 *
 * This is the Event Channel half of the former `BootCompletedReceiver`, which also auto-started the
 * on-device MCP server; that half was removed with the server (docs/plans/demolition.md).
 *
 * Uses [goAsync] to extend the broadcast receiver's lifecycle beyond the default 10-second limit,
 * allowing a coroutine to read settings from DataStore.
 */
@AndroidEntryPoint
class EventChannelBootReceiver : BroadcastReceiver() {
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(SETTINGS_READ_TIMEOUT_MS) {
                    val channelConfig = settingsRepository.getEventChannelConfig()
                    if (shouldAutoStart(channelConfig)) {
                        val channelIntent =
                            Intent(context, EventChannelService::class.java).apply {
                                action = EventChannelService.ACTION_START
                            }
                        context.startForegroundService(channelIntent)
                        Log.i(TAG, "Event channel auto-started on boot")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Timed out reading Event Channel settings on boot", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "Could not read Event Channel settings on boot", e)
            } catch (e: IllegalStateException) {
                // Includes ForegroundServiceStartNotAllowedException (Android 12+).
                Log.e(TAG, "Could not start the Event Channel on boot", e)
            } catch (e: SecurityException) {
                Log.e(TAG, "Not allowed to start the Event Channel on boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MCP:ChannelBootReceiver"
        private const val SETTINGS_READ_TIMEOUT_MS = 10_000L
    }
}

/** The channel auto-starts on boot only when it is enabled and has an endpoint to send to. */
internal fun shouldAutoStart(config: EventChannelConfig): Boolean = config.enabled && config.endpointUrl.isNotBlank()
