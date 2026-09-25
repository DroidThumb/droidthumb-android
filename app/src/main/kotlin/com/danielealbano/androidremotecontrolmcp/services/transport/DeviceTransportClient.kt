package com.danielealbano.androidremotecontrolmcp.services.transport

import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Hello
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Step
import com.danielealbano.androidremotecontrolmcp.wireprotocol.StepDispatcher
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Welcome
import com.danielealbano.androidremotecontrolmcp.wireprotocol.WireMessage
import com.danielealbano.androidremotecontrolmcp.wireprotocol.wireJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Connection lifecycle, mirroring [com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus]'s
 *  existing shape for this codebase's other outbound connection (the Event Channel). */
sealed interface TransportStatus {
    data object Idle : TransportStatus

    data object Connecting : TransportStatus

    data class Connected(
        val protocolVersion: Int,
    ) : TransportStatus

    /** The connection closed (or never opened) before a `welcome` arrived — a handshake-level
     *  rejection (wrong subprotocol, 4000/4001, or a transport error), not a mid-session drop. */
    data class Rejected(
        val closeCode: Short?,
        val reason: String,
    ) : TransportStatus

    data class Reconnecting(
        val attempt: Int,
        val delayMs: Long,
    ) : TransportStatus
}

interface DeviceTransportClient {
    val status: StateFlow<TransportStatus>

    fun start(
        host: String,
        port: Int,
        deviceId: String,
    )

    fun stop()
}

/**
 * The M2 outbound WebSocket client (design doc M2, mvp-handover §4 item 1): offers the
 * `droidthumb.v1` subprotocol, sends `hello`, awaits `welcome` before doing anything else, and
 * dispatches every inbound `step` through [StepDispatcher]. Reconnects with capped exponential
 * backoff on any drop; a handshake rejection (4000/4001) still backs off rather than retrying in a
 * tight loop, since retrying against an unsupported-version server is expected to keep failing.
 */
@Singleton
class DeviceTransportClientImpl
    @Inject
    constructor(
        private val stepDispatcher: StepDispatcher,
    ) : DeviceTransportClient {
        private val _status = MutableStateFlow<TransportStatus>(TransportStatus.Idle)
        override val status: StateFlow<TransportStatus> = _status.asStateFlow()

        private var job: Job? = null
        private val client by lazy { HttpClient(OkHttp) { install(WebSockets) } }

        override fun start(
            host: String,
            port: Int,
            deviceId: String,
        ) {
            stop()
            job =
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    var attempt = 0
                    while (isActive) {
                        _status.value =
                            if (attempt == 0) {
                                TransportStatus.Connecting
                            } else {
                                TransportStatus.Reconnecting(attempt, backoffMs(attempt))
                            }
                        if (attempt > 0) delay(backoffMs(attempt))
                        val welcomed = runSession(host, port, deviceId)
                        attempt = if (welcomed) 0 else attempt + 1
                    }
                }
        }

        /** Returns true iff a `welcome` was received this session — used to reset backoff on a
         *  session that connected properly even if it later dropped. */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun runSession(
            host: String,
            port: Int,
            deviceId: String,
        ): Boolean {
            var welcomed = false
            try {
                client.webSocket(
                    method = HttpMethod.Get,
                    host = host,
                    port = port,
                    path = "/device",
                    request = { header(HttpHeaders.SecWebSocketProtocol, SUBPROTOCOL) },
                ) {
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), helloFor(deviceId))))
                    for (frame in incoming) {
                        val message = decodeOrNull(frame) ?: continue
                        when (message) {
                            is Welcome -> {
                                welcomed = true
                                _status.value = TransportStatus.Connected(message.protocolVersion)
                            }

                            is Step -> {
                                val reply = stepDispatcher.dispatch(message)
                                send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), reply)))
                            }

                            // Hello/StepResult/StepError never arrive server -> device
                            else -> {}
                        }
                    }
                    // `incoming` completed — the server closed the connection (gracefully or not).
                    // This is the ONLY place a 4000/4001 close is observable: Ktor's client
                    // WebSocket does not throw for a normal close frame, it just ends the channel.
                    if (!welcomed) {
                        val reason = closeReason.await()
                        _status.value =
                            TransportStatus.Rejected(reason?.code, reason?.message ?: "closed before welcome")
                    }
                }
            } catch (e: CancellationException) {
                // stop() cancelling this session's job (e.g. mid-handshake, before welcome) resumes
                // the suspended incoming.receive()/send() with this — rethrow rather than reporting
                // Rejected, which would race stop()'s own synchronous Idle write and could leave the
                // UI stuck showing "Rejected" after the user pressed Stop.
                throw e
            } catch (e: Exception) {
                // A genuine transport failure (upgrade refused, socket error, DNS failure, etc.) —
                // distinct from a graceful close, which is handled above.
                if (!welcomed) _status.value = TransportStatus.Rejected(null, e.message ?: "connection failed")
                Logger.w(TAG, "Transport session ended: ${e.message}")
            }
            return welcomed
        }

        /** Decodes one inbound frame, or null for a non-text frame or a malformed payload (logged
         *  and skipped rather than tearing down the whole session over one bad frame). */
        @Suppress("TooGenericExceptionCaught")
        private fun decodeOrNull(frame: Frame): WireMessage? {
            if (frame !is Frame.Text) return null
            return try {
                wireJson.decodeFromString(WireMessage.serializer(), frame.readText())
            } catch (e: Exception) {
                Logger.w(TAG, "Ignoring malformed frame: ${e.message}")
                null
            }
        }

        private fun helloFor(deviceId: String) =
            Hello(
                protocolVersion = 1,
                apkVersion = BuildConfig.VERSION_NAME,
                deviceId = deviceId,
                capabilities = emptyList(),
                mode = "live",
                flowManifest = emptyList(),
            )

        override fun stop() {
            job?.cancel()
            job = null
            _status.value = TransportStatus.Idle
        }

        private fun backoffMs(attempt: Int): Long {
            val schedule = BACKOFF_SCHEDULE_MS
            return schedule.getOrElse(attempt - 1) { schedule.last() }
        }

        companion object {
            private const val TAG = "MCP:DeviceTransport"
            const val SUBPROTOCOL = "droidthumb.v1"
            private val BACKOFF_SCHEDULE_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        }
    }
