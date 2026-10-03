package com.danielealbano.androidremotecontrolmcp.services.transport

import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.ConnectorUrlSettings
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceIdentityKeyStore
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceInfoProvider
import com.danielealbano.androidremotecontrolmcp.services.identity.deriveDeviceId
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Challenge
import com.danielealbano.androidremotecontrolmcp.wireprotocol.ChallengeResponse
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Hello
import com.danielealbano.androidremotecontrolmcp.wireprotocol.RegenerateSecret
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SecretRegenerated
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Step
import com.danielealbano.androidremotecontrolmcp.wireprotocol.StepDispatcher
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Welcome
import com.danielealbano.androidremotecontrolmcp.wireprotocol.WireMessage
import com.danielealbano.androidremotecontrolmcp.wireprotocol.wireJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
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
     *  rejection (wrong subprotocol, 4000-4003, or a transport error), not a mid-session drop. */
    data class Rejected(
        val closeCode: Short?,
        val reason: String,
    ) : TransportStatus

    data class Reconnecting(
        val attempt: Int,
        val delayMs: Long,
    ) : TransportStatus
}

/** `welcome.latest_app_version`/`minimum_supported_app_version`/`download_url` — kept for milestone
 *  4's update-prompt UI; nothing reads this yet (android#5 item 4). */
data class UpdateInfo(
    val latestAppVersion: String?,
    val minimumSupportedAppVersion: String?,
    val downloadUrl: String?,
)

interface DeviceTransportClient {
    val status: StateFlow<TransportStatus>
    val updateInfo: StateFlow<UpdateInfo?>

    fun start(
        host: String,
        port: Int,
        tls: Boolean,
    )

    fun stop()

    /** Sends `regenerate_secret` on the current connection and awaits `secret_regenerated`, up to a
     *  5s timeout — the server sends NO reply at all when the per-device rate limit (10/hour) is
     *  exceeded, so a bounded wait is the only way to detect that (server#16). Returns `false`
     *  immediately, with no wait, when not currently `Connected`. */
    suspend fun regenerateSecret(): Boolean
}

/**
 * The M2/M3 outbound WebSocket client: offers `droidthumb.v1`, sends `hello`, answers a `challenge`
 * with the Keystore-signed nonce (D-27) before `welcome` can arrive, dispatches inbound `step`s
 * through [StepDispatcher], and carries [RegenerateSecret]/[SecretRegenerated] once connected.
 * Reconnects with capped exponential backoff on any drop. `device_id` is never stored — it's
 * [deriveDeviceId] applied to the Keystore public key, recomputed whenever needed (cheap: one
 * base64 decode and one SHA-256 hash, no I/O).
 *
 * Registration (`POST /devices/register`) happens once per [start] — not on every reconnect — via
 * [ensureRegistered]'s [registered] latch: re-registering is idempotent server-side but rate-limited
 * (30/hour/IP), and the reconnect loop can retry indefinitely, so repeating it on every attempt
 * risks exhausting that budget on ordinary reconnect churn. A registration that hasn't succeeded yet
 * is retried on the next loop iteration (the same backoff schedule already governing WS reconnects);
 * one that has succeeded (or that fatally can't — a `device_id` mismatch) is never retried again for
 * the lifetime of this [start] call.
 */
@Singleton
class DeviceTransportClientImpl
    @Inject
    constructor(
        private val stepDispatcher: StepDispatcher,
        private val deviceIdentityKeyStore: DeviceIdentityKeyStore,
        private val deviceInfoProvider: DeviceInfoProvider,
        private val registrationClient: DeviceRegistrationClient,
        private val connectorUrlSettings: ConnectorUrlSettings,
    ) : DeviceTransportClient {
        private val _status = MutableStateFlow<TransportStatus>(TransportStatus.Idle)
        override val status: StateFlow<TransportStatus> = _status.asStateFlow()

        private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
        override val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

        private var job: Job? = null
        private val client by lazy { HttpClient(OkHttp) { install(WebSockets) } }
        private val registered = AtomicBoolean(false)

        @Volatile
        private var currentOutbox: SendChannel<WireMessage>? = null

        private val regenerateMutex = Mutex()

        @Volatile
        private var pendingRegenerate: CompletableDeferred<String>? = null

        private fun currentDeviceId(): String {
            val publicKeyDer = Base64.getDecoder().decode(deviceIdentityKeyStore.ensurePublicKeyBase64())
            return deriveDeviceId(publicKeyDer)
        }

        override fun start(
            host: String,
            port: Int,
            tls: Boolean,
        ) {
            stop()
            registered.set(false)
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
                        ensureRegistered(host, port, tls)
                        val welcomed = runSession(host, port, tls)
                        attempt = if (welcomed) 0 else attempt + 1
                    }
                }
        }

        private suspend fun ensureRegistered(
            host: String,
            port: Int,
            tls: Boolean,
        ) {
            if (registered.get()) return
            val publicKey = deviceIdentityKeyStore.ensurePublicKeyBase64()
            val expectedId = currentDeviceId()
            when (val result = registrationClient.register(host, port, tls, publicKey)) {
                is DeviceRegistrationResult.Success -> {
                    if (result.deviceId != expectedId) {
                        Logger.e(TAG, "Registration returned a device_id that doesn't match the derived one")
                        registered.set(true) // can never succeed; stop burning the rate-limit budget retrying
                        return
                    }
                    registered.set(true)
                    result.connectorUrl?.let { connectorUrlSettings.updateConnectorUrl(it) }
                }

                is DeviceRegistrationResult.RateLimited -> {
                    Logger.w(TAG, "Registration rate-limited, retry-after=${result.retryAfterSeconds}")
                }

                is DeviceRegistrationResult.Failed -> {
                    Logger.w(TAG, "Registration failed: ${result.message}")
                }
            }
        }

        /** Returns true iff a `welcome` was received this session — used to reset backoff on a
         *  session that connected properly even if it later dropped. */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun runSession(
            host: String,
            port: Int,
            tls: Boolean,
        ): Boolean {
            var welcomed = false
            try {
                client.webSocket(
                    method = HttpMethod.Get,
                    host = host,
                    port = port,
                    path = "/device",
                    request = {
                        header(HttpHeaders.SecWebSocketProtocol, SUBPROTOCOL)
                        if (tls) url.protocol = URLProtocol.WSS
                    },
                ) {
                    val outbox = Channel<WireMessage>(Channel.BUFFERED)
                    currentOutbox = outbox

                    try {
                        send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), helloFor())))
                        launch {
                            for (message in outbox) {
                                send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), message)))
                            }
                        }
                        for (frame in incoming) {
                            val message = decodeOrNull(frame) ?: continue
                            if (handleIncomingMessage(message)) welcomed = true
                        }
                        // `incoming` completed — the server closed the connection. This is the ONLY
                        // place a 4000-4003 close is observable (Ktor's client WS doesn't throw for a
                        // normal close frame, it just ends the channel).
                        if (!welcomed) {
                            val reason = closeReason.await()
                            Logger.w(TAG, "Closed before welcome: code=${reason?.code} message=${reason?.message}")
                            _status.value =
                                TransportStatus.Rejected(reason?.code, reason?.message ?: "closed before welcome")
                        }
                    } finally {
                        outbox.close()
                        if (currentOutbox === outbox) currentOutbox = null
                    }
                }
            } catch (e: CancellationException) {
                // stop() cancelling this session's job resumes a suspended incoming.receive()/send()
                // with this — rethrow rather than reporting Rejected, which would race stop()'s own
                // synchronous Idle write.
                throw e
            } catch (e: Exception) {
                if (!welcomed) _status.value = TransportStatus.Rejected(null, e.message ?: "connection failed")
                Logger.w(TAG, "Transport session ended: ${e.message}")
            }
            return welcomed
        }

        /** Handles one decoded inbound message for the current session. Returns `true` iff
         *  `message` was [Welcome] — `runSession` uses that to set its own `welcomed` flag, since
         *  this is a separate function (not a local one) specifically so detekt's per-function
         *  complexity/length limits measure it apart from `runSession`'s own connection-lifecycle
         *  logic. */
        private suspend fun DefaultClientWebSocketSession.handleIncomingMessage(message: WireMessage): Boolean {
            when (message) {
                is Challenge -> {
                    val signature = deviceIdentityKeyStore.signNonce(message.nonce)
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), ChallengeResponse(signature))))
                }

                is Welcome -> {
                    Logger.i(TAG, "Connected: protocol_version=${message.protocolVersion}")
                    _updateInfo.value =
                        UpdateInfo(message.latestAppVersion, message.minimumSupportedAppVersion, message.downloadUrl)
                    _status.value = TransportStatus.Connected(message.protocolVersion)
                    return true
                }

                is Step -> {
                    val reply = stepDispatcher.dispatch(message)
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), reply)))
                }

                is SecretRegenerated -> {
                    connectorUrlSettings.updateConnectorUrl(message.connectorUrl)
                    pendingRegenerate?.complete(message.connectorUrl)
                }

                // Hello/ChallengeResponse/RegenerateSecret/StepResult/StepError never arrive
                // server -> device
                else -> {}
            }
            return false
        }

        override suspend fun regenerateSecret(): Boolean =
            regenerateMutex.withLock {
                if (_status.value !is TransportStatus.Connected) return@withLock false
                val outbox = currentOutbox ?: return@withLock false
                val deferred = CompletableDeferred<String>()
                pendingRegenerate = deferred
                // A narrow session-teardown race (currentOutbox closed between the check above and
                // this send) makes trySend fail — checked explicitly so that case returns
                // immediately instead of waiting out the full 5s timeout for a message that was
                // never actually sent.
                if (!outbox.trySend(RegenerateSecret).isSuccess) {
                    pendingRegenerate = null
                    return@withLock false
                }
                val result = withTimeoutOrNull(REGENERATE_TIMEOUT_MS) { deferred.await() }
                pendingRegenerate = null
                result != null
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

        private fun helloFor(): Hello =
            Hello(
                protocolVersion = 1,
                apkVersion = BuildConfig.VERSION_NAME,
                deviceId = currentDeviceId(),
                capabilities = emptyList(),
                mode = "live",
                flowManifest = emptyList(),
                androidVersion = deviceInfoProvider.androidVersion,
                deviceModel = deviceInfoProvider.deviceModel,
            )

        override fun stop() {
            job?.cancel()
            job = null
            currentOutbox = null
            pendingRegenerate = null
            _status.value = TransportStatus.Idle
        }

        private fun backoffMs(attempt: Int): Long {
            val schedule = BACKOFF_SCHEDULE_MS
            return schedule.getOrElse(attempt - 1) { schedule.last() }
        }

        companion object {
            private const val TAG = "MCP:DeviceTransport"
            const val SUBPROTOCOL = "droidthumb.v1"
            private const val REGENERATE_TIMEOUT_MS = 5_000L
            private val BACKOFF_SCHEDULE_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        }
    }
