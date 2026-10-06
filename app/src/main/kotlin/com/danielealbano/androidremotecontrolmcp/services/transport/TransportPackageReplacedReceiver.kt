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
 * Receives `MY_PACKAGE_REPLACED` (this app was updated) and re-starts the device transport
 * (design doc §8.8 revision) — an app update kills every running process, including
 * [TransportService], so without this the connection would stay down until the next manual
 * action, which no longer exists (Start/Stop was removed; the only user control is Pause). Same
 * `goAsync`/timeout shape as [TransportBootReceiver].
 */
@AndroidEntryPoint
class TransportPackageReplacedReceiver : BroadcastReceiver() {
    @Inject lateinit var transportAutoStart: TransportAutoStart

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(SETTINGS_READ_TIMEOUT_MS) {
                    transportAutoStart.maybeStart(context)
                }
            } catch (e: TimeoutCancellationException) {
                Logger.e(TAG, "Timed out reading settings for transport auto-start on update: ${e.message}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Logger.e(TAG, "Could not read settings for transport auto-start on update: ${e.message}")
            } catch (e: IllegalStateException) {
                // Includes ForegroundServiceStartNotAllowedException (Android 12+).
                Logger.e(TAG, "Could not start the transport on update: ${e.message}")
            } catch (e: SecurityException) {
                Logger.e(TAG, "Not allowed to start the transport on update: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        private const val TAG = "MCP:TransportPackageReplacedReceiver"
        private const val SETTINGS_READ_TIMEOUT_MS = 10_000L
    }
}
