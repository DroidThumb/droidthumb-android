package com.danielealbano.androidremotecontrolmcp.services.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.utils.Logger
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
 * Receives `BOOT_COMPLETED` and re-starts the device transport (design doc §8.8 revision) — same
 * `goAsync` pattern as [com.danielealbano.androidremotecontrolmcp.services.channel.EventChannelBootReceiver],
 * extended beyond the default 10s broadcast-receiver lifetime so [TransportAutoStart] can read
 * settings from DataStore.
 */
@AndroidEntryPoint
class TransportBootReceiver : BroadcastReceiver() {
    @Inject lateinit var transportAutoStart: TransportAutoStart

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
                    transportAutoStart.maybeStart(context)
                }
            } catch (e: TimeoutCancellationException) {
                Logger.e(TAG, "Timed out reading settings for transport auto-start on boot: ${e.message}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Logger.e(TAG, "Could not read settings for transport auto-start on boot: ${e.message}")
            } catch (e: IllegalStateException) {
                // Includes ForegroundServiceStartNotAllowedException (Android 12+).
                Logger.e(TAG, "Could not start the transport on boot: ${e.message}")
            } catch (e: SecurityException) {
                Logger.e(TAG, "Not allowed to start the transport on boot: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        private const val TAG = "MCP:TransportBootReceiver"
        private const val SETTINGS_READ_TIMEOUT_MS = 10_000L
    }
}
