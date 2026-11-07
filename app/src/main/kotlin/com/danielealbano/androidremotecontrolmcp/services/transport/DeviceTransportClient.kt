package com.danielealbano.androidremotecontrolmcp.services.transport

import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.AccountSettings
import com.danielealbano.androidremotecontrolmcp.data.repository.ConnectorUrlSettings
import com.danielealbano.androidremotecontrolmcp.data.repository.PauseSettings
import com.danielealbano.androidremotecontrolmcp.services.controlbar.ControlBarCoordinator
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceIdentityKeyStore
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceInfoProvider
import com.danielealbano.androidremotecontrolmcp.services.identity.InstallHintProvider
import com.danielealbano.androidremotecontrolmcp.services.identity.deriveDeviceId
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import com.danielealbano.androidremotecontrolmcp.wireprotocol.AiConnection
import com.danielealbano.androidremotecontrolmcp.wireprotocol.AiConnectionRevoked
import com.danielealbano.androidremotecontrolmcp.wireprotocol.AiConnectionsList
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Challenge
import com.danielealbano.androidremotecontrolmcp.wireprotocol.ChallengeResponse
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Hello
import com.danielealbano.androidremotecontrolmcp.wireprotocol.ListAiConnections
import com.danielealbano.androidremotecontrolmcp.wireprotocol.RegenerateSecret
import com.danielealbano.androidremotecontrolmcp.wireprotocol.RevokeAiConnection
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SecretRegenerated
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignIn
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignInRejected
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignOut
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignedIn
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignedOut
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Step
import com.danielealbano.androidremotecontrolmcp.wireprotocol.StepDispatcher
import com.danielealbano.androidremotecontrolmcp.wireprotocol.StepError
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
import okhttp3.OkHttpClient
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Connection lifecycle for this device's outbound transport connection. */
sealed interface TransportStatus {
    data object Idle : TransportStatus

    data object Connecting : TransportStatus

    data class Connected(
        val protocolVersion: Int,
        /** The welcome's `accounts` flag — `true` when the server has accounts and this phone can
         *  be signed in to one; `false` means every `sign_in` is rejected with
         *  `accounts_unsupported` and sign-in is hidden. */
        val accounts: Boolean,
        /** The welcome's `account_id` — the account signed in on this phone, or `null` when the
         *  phone is signed out or the server has no accounts. */
        val accountId: String?,
    ) : TransportStatus

    /** The connection closed (or never opened) before a `welcome` arrived — a handshake-level
     *  rejection (wrong subprotocol, 4000-4003, or a transport error), not a mid-session drop. */
    data class Rejected(
        val closeCode: Short?,
        val reason: String,
    ) : TransportStatus

    /** An established connection was closed with 4004 (`CLOSE_REPLACED`): another phone signed in
     *  with `replace_device_id` and retired this one. The reconnect loop stops for good, the local
     *  account association is cleared (no `sign_out` is sent — the server already moved this
     *  phone), and sign-in stays possible only through an explicit restart of the transport. */
    data object Replaced : TransportStatus

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

/** Reply to [DeviceTransportClient.signIn] — mirrors the wire's own [SignedIn]/[SignInRejected]
 *  shape, plus [TimedOut] for the same "no reply within the bound" case [regenerateSecret] handles
 *  by returning `false`. */
sealed interface SignInResult {
    data class SignedIn(
        val accountId: String,
    ) : SignInResult

    data class Rejected(
        val reason: String,
    ) : SignInResult

    data object TimedOut : SignInResult

    /** Not currently [TransportStatus.Connected] — nothing was sent, no wait occurred. */
    data object NotConnected : SignInResult
}

/** Reply to [DeviceTransportClient.listAiConnections] — the wire's own
 *  [AiConnectionsList]/[AiConnectionsError] shape, plus [AiConnectionsResult.TimedOut] for the
 *  same "no reply within the bound" case [regenerateSecret] handles by returning `false`. */
sealed interface AiConnectionsResult {
    data class Connections(
        val connections: List<AiConnection>,
    ) : AiConnectionsResult

    data class Error(
        val code: String,
        val message: String?,
    ) : AiConnectionsResult

    data object TimedOut : AiConnectionsResult

    /** Not currently [TransportStatus.Connected] — nothing was sent, no wait occurred. */
    data object NotConnected : AiConnectionsResult
}

/** Reply to [DeviceTransportClient.revokeAiConnection]. */
sealed interface AiRevokeResult {
    data object Revoked : AiRevokeResult

    data class Error(
        val code: String,
        val message: String?,
    ) : AiRevokeResult

    data object TimedOut : AiRevokeResult

    /** Not currently [TransportStatus.Connected] — nothing was sent, no wait occurred. */
    data object NotConnected : AiRevokeResult
}

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

    /** Sends `sign_in { account_token, install_hint }` on the current connection and awaits
     *  `signed_in` or `sign_in_rejected`, up to a 5s timeout — `accountToken` is the short-lived,
     *  single-use token from `POST /v1/accounts/sign-in-token`, not the raw Google/OAuth token;
     *  `install_hint` comes from [InstallHintProvider]. */
    suspend fun signIn(accountToken: String): SignInResult

    /** Sends `sign_out` on the current connection and awaits `signed_out`, up to the same 5s
     *  bound as [signIn]. Returns `false` immediately, with no wait, when not currently
     *  `Connected` or when the send failed. The pending flag (via [AccountSettings.pendingSignOut])
     *  is set FIRST — before the Connected check — so a sign-out requested while the phone is
     *  offline is still recorded; the caller signs out locally regardless of the outcome, and a
     *  sign-out whose `signed_out` never arrived stays recorded as pending so the next connection
     *  sends `sign_out` first. */
    suspend fun signOut(): Boolean

    /** Sends `list_ai_connections` on the current connection and awaits `ai_connections_list`,
     *  up to a 5s timeout. Returns [AiConnectionsResult.NotConnected] immediately, with no wait,
     *  when not currently `Connected`. */
    suspend fun listAiConnections(): AiConnectionsResult

    /** Sends `revoke_ai_connection { connection_id }` on the current connection and awaits
     *  `ai_connection_revoked`, up to a 5s timeout. `connectionId` is the connection's
     *  `client_id` as listed by [listAiConnections]. Returns [AiRevokeResult.NotConnected]
     *  immediately, with no wait, when not currently `Connected`. */
    suspend fun revokeAiConnection(connectionId: String): AiRevokeResult
}

/**
 * The outbound WebSocket client: offers `droidthumb.v2`, sends `hello`, answers a `challenge`
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
    @Suppress("LongParameterList")
    @Inject
    constructor(
        private val stepDispatcher: StepDispatcher,
        private val deviceIdentityKeyStore: DeviceIdentityKeyStore,
        private val deviceInfoProvider: DeviceInfoProvider,
        private val registrationClient: DeviceRegistrationClient,
        private val connectorUrlSettings: ConnectorUrlSettings,
        private val pauseSettings: PauseSettings,
        private val controlBarCoordinator: ControlBarCoordinator,
        private val installHintProvider: InstallHintProvider,
        private val accountSettings: AccountSettings,
    ) : DeviceTransportClient {
        private val _status = MutableStateFlow<TransportStatus>(TransportStatus.Idle)
        override val status: StateFlow<TransportStatus> = _status.asStateFlow()

        private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
        override val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

        private var job: Job? = null
        private val client by lazy {
            HttpClient(OkHttp) {
                engine { preconfigured = transportOkHttpClient() }
                install(WebSockets)
            }
        }
        private val registered = AtomicBoolean(false)

        @Volatile
        private var currentOutbox: SendChannel<WireMessage>? = null

        private val regenerateMutex = Mutex()

        @Volatile
        private var pendingRegenerate: CompletableDeferred<String>? = null

        private val signInMutex = Mutex()

        @Volatile
        private var pendingSignIn: CompletableDeferred<SignInResult>? = null

        private val signOutMutex = Mutex()

        @Volatile
        private var pendingSignOutReply: CompletableDeferred<Unit>? = null

        private val aiListMutex = Mutex()

        @Volatile
        private var pendingAiConnections: CompletableDeferred<AiConnectionsResult>? = null

        private val aiRevokeMutex = Mutex()

        @Volatile
        private var pendingAiRevoke: CompletableDeferred<AiRevokeResult>? = null

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
                        val delayMs =
                            if (attempt == 0) {
                                0L
                            } else {
                                BACKOFF_SCHEDULE_MS.getOrElse(attempt - 1) { BACKOFF_SCHEDULE_MS.last() }
                            }
                        _status.value =
                            if (attempt == 0) {
                                TransportStatus.Connecting
                            } else {
                                TransportStatus.Reconnecting(attempt, delayMs)
                            }
                        if (attempt > 0) delay(delayMs)
                        ensureRegistered(host, port, tls)
                        val session = runSession(host, port, tls)
                        if (session.replaced) break
                        attempt = if (session.welcomed) 0 else attempt + 1
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
            val expectedId = currentDeviceId(deviceIdentityKeyStore)
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

        /** Runs one connection attempt. `welcomed` resets the reconnect backoff on a session that
         *  connected properly even if it later dropped; `replaced` (a 4004 close on an established
         *  session, applied by [handleSessionClose]) stops the reconnect loop for good. */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun runSession(
            host: String,
            port: Int,
            tls: Boolean,
        ): SessionOutcome {
            var welcomed = false
            var replaced = false
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
                        send(
                            Frame.Text(
                                wireJson.encodeToString(
                                    WireMessage.serializer(),
                                    helloFor(deviceInfoProvider, currentDeviceId(deviceIdentityKeyStore)),
                                ),
                            ),
                        )
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
                        if (handleSessionClose(welcomed, _status, accountSettings)) replaced = true
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
            return SessionOutcome(welcomed, replaced)
        }

        /** Handles one decoded inbound message for the current session. Returns `true` iff
         *  `message` was [Welcome] — `runSession` uses that to set its own `welcomed` flag, since
         *  this is a separate function (not a local one) specifically so detekt's per-function
         *  complexity/length limits measure it apart from `runSession`'s own connection-lifecycle
         *  logic.
         *
         *  Code-review finding, round 4 pause redesign, accepted as-is rather than fixed: a `Step`
         *  held while paused (`runStepCancellable`) now blocks this whole function - and therefore
         *  the `for (frame in incoming)` loop in `runSession` that calls it - for up to
         *  `PauseSettings.getPauseHoldCapMs` (default 60s) before the NEXT inbound frame
         *  (`SecretRegenerated`, `SignedIn`/`SignInRejected`, or the server closing the socket) is even
         *  read. Previously a paused step replied instantly, so this was a non-issue. Accepted
         *  because: only one step is ever in flight per connection by design (serialized dispatch,
         *  documented on `registerCurrentStep`) and the device owner driving a session while ALSO
         *  mid-`regenerateSecret()`/`signIn()` on the very same connection is already an edge
         *  case; a real fix (processing other inbound messages concurrently with a held step) is a
         *  larger structural change than this round's scope. */
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
                    // A sign-out requested while the phone was offline is replayed as the first
                    // app-driven message on the new connection — before Connected is published, so
                    // callers never see a Connected window where sign_out was still owed; the
                    // pending flag clears only when `signed_out` comes back.
                    if (accountSettings.getPendingSignOut()) {
                        Logger.i(TAG, "Sending pending sign_out on the new connection")
                        send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), SignOut)))
                    }
                    _status.value =
                        TransportStatus.Connected(message.protocolVersion, message.accounts, message.accountId)
                    return true
                }

                is Step -> {
                    val reply = runStepCancellable(message)
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), reply)))
                }

                is SecretRegenerated -> {
                    connectorUrlSettings.updateConnectorUrl(message.connectorUrl)
                    pendingRegenerate?.complete(message.connectorUrl)
                }

                is SignedIn -> {
                    // The user's latest action was signing in, so an older unacknowledged
                    // sign-out must not be replayed on the next connection.
                    accountSettings.setPendingSignOut(false)
                    pendingSignIn?.complete(SignInResult.SignedIn(message.accountId))
                }

                is SignInRejected -> {
                    pendingSignIn?.complete(SignInResult.Rejected(message.reason))
                }

                is SignedOut -> {
                    // The only place the persisted pending-sign-out flag clears: the server has
                    // now acknowledged the sign-out (or the phone was already signed out).
                    accountSettings.setPendingSignOut(false)
                    pendingSignOutReply?.complete(Unit)
                }

                is AiConnectionsList -> {
                    completeAiConnectionsReply(message, pendingAiConnections)
                }

                is AiConnectionRevoked -> {
                    completeAiRevokeReply(message, pendingAiRevoke)
                }

                // Hello/ChallengeResponse/RegenerateSecret/SignIn/SignOut/ListAiConnections/
                // RevokeAiConnection/StepResult/StepError never arrive server -> device
                else -> {}
            }
            return false
        }

        /** Runs [step] in a CHILD coroutine of this session (launched from the
         *  [DefaultClientWebSocketSession] receiver, which is itself a [CoroutineScope]) so
         *  [ControlBarCoordinator.stop] can cancel JUST this step - cancelling this session's own
         *  job would tear down the whole connection, which Stop must never do.
         *
         *  Round 4 phone-feedback redesign: a step arriving while paused no longer fails
         *  immediately with `device_paused`. A paused device can't resume the AI's own turn later -
         *  once a tool call has already failed, there is no "try again" signal the AI will see on
         *  its own - so instead this HOLDS the step (neither executing nor failing it) until
         *  [PauseSettings.getPauseHoldCapMs] elapses or the owner taps Resume, whichever comes
         *  first, via [awaitResumeOrCapExpired]. [controlBarCoordinator]'s cancel hook wraps the
         *  WHOLE hold-then-dispatch [dispatchJob], not just the dispatch half, so Stop aborts a
         *  held-but-not-yet-dispatched step exactly as immediately as it already aborted an
         *  in-flight one - the device owner's Stop button must never be slower than a step that
         *  hasn't even started yet. */
        private suspend fun DefaultClientWebSocketSession.runStepCancellable(step: Step): WireMessage {
            val resultDeferred = CompletableDeferred<WireMessage>()
            val dispatchJob =
                launch {
                    if (!awaitResumeOrCapExpired(pauseSettings)) {
                        resultDeferred.complete(
                            StepError(step.stepId, code = "device_paused", message = PAUSED_STEP_MESSAGE),
                        )
                        return@launch
                    }
                    resultDeferred.complete(stepDispatcher.dispatch(step))
                }
            controlBarCoordinator.registerCurrentStep {
                dispatchJob.cancel()
                resultDeferred.complete(
                    StepError(step.stepId, code = "stopped_by_owner", message = STOPPED_BY_OWNER_MESSAGE),
                )
            }
            return try {
                resultDeferred.await()
            } finally {
                controlBarCoordinator.clearCurrentStep()
            }
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

        override suspend fun signIn(accountToken: String): SignInResult =
            signInMutex.withLock {
                if (_status.value !is TransportStatus.Connected) return@withLock SignInResult.NotConnected
                val outbox = currentOutbox ?: return@withLock SignInResult.NotConnected
                val deferred = CompletableDeferred<SignInResult>()
                pendingSignIn = deferred
                // Same narrow session-teardown race regenerateSecret() guards against: checked
                // explicitly so a send that never actually went out fails fast instead of waiting
                // out the full timeout.
                if (!outbox.trySend(SignIn(accountToken, installHintProvider.installHint)).isSuccess) {
                    pendingSignIn = null
                    return@withLock SignInResult.NotConnected
                }
                val result = withTimeoutOrNull(SIGN_IN_TIMEOUT_MS) { deferred.await() }
                pendingSignIn = null
                result ?: SignInResult.TimedOut
            }

        override suspend fun signOut(): Boolean =
            signOutMutex.withLock {
                // Persisted first, before any Connected/outbox check: a sign-out requested while
                // the phone is offline must still be recorded, so the next connection replays it
                // (see handleIncomingMessage's Welcome branch). Cleared only on `signed_out`.
                accountSettings.setPendingSignOut(true)
                if (_status.value !is TransportStatus.Connected) return@withLock false
                val outbox = currentOutbox ?: return@withLock false
                val deferred = CompletableDeferred<Unit>()
                pendingSignOutReply = deferred
                // Same narrow session-teardown race regenerateSecret() guards against (see signIn).
                if (!outbox.trySend(SignOut).isSuccess) {
                    pendingSignOutReply = null
                    return@withLock false
                }
                val acked = withTimeoutOrNull(SIGN_OUT_TIMEOUT_MS) { deferred.await() } != null
                pendingSignOutReply = null
                acked
            }

        override suspend fun listAiConnections(): AiConnectionsResult =
            aiListMutex.withLock {
                if (_status.value !is TransportStatus.Connected) return@withLock AiConnectionsResult.NotConnected
                val outbox = currentOutbox ?: return@withLock AiConnectionsResult.NotConnected
                val deferred = CompletableDeferred<AiConnectionsResult>()
                pendingAiConnections = deferred
                // Same narrow session-teardown race regenerateSecret() guards against (see signIn).
                if (!outbox.trySend(ListAiConnections).isSuccess) {
                    pendingAiConnections = null
                    return@withLock AiConnectionsResult.NotConnected
                }
                val result = withTimeoutOrNull(AI_CONNECTIONS_TIMEOUT_MS) { deferred.await() }
                pendingAiConnections = null
                result ?: AiConnectionsResult.TimedOut
            }

        override suspend fun revokeAiConnection(connectionId: String): AiRevokeResult =
            aiRevokeMutex.withLock {
                if (_status.value !is TransportStatus.Connected) return@withLock AiRevokeResult.NotConnected
                val outbox = currentOutbox ?: return@withLock AiRevokeResult.NotConnected
                val deferred = CompletableDeferred<AiRevokeResult>()
                pendingAiRevoke = deferred
                // Same narrow session-teardown race regenerateSecret() guards against (see signIn).
                if (!outbox.trySend(RevokeAiConnection(connectionId)).isSuccess) {
                    pendingAiRevoke = null
                    return@withLock AiRevokeResult.NotConnected
                }
                val result = withTimeoutOrNull(AI_CONNECTIONS_TIMEOUT_MS) { deferred.await() }
                pendingAiRevoke = null
                result ?: AiRevokeResult.TimedOut
            }

        override fun stop() {
            job?.cancel()
            job = null
            currentOutbox = null
            pendingRegenerate = null
            pendingSignIn = null
            pendingSignOutReply = null
            pendingAiConnections = null
            pendingAiRevoke = null
            _status.value = TransportStatus.Idle
        }

        companion object {
            private const val TAG = "MCP:DeviceTransport"
            const val SUBPROTOCOL = "droidthumb.v2"
            private const val REGENERATE_TIMEOUT_MS = 5_000L
            private const val SIGN_IN_TIMEOUT_MS = 5_000L
            private const val SIGN_OUT_TIMEOUT_MS = 5_000L
            private const val AI_CONNECTIONS_TIMEOUT_MS = 5_000L

            /** Reply to a `step` that was HELD while paused (round 4 redesign of design doc §8.8's
             *  original "replies immediately" behavior - see [runStepCancellable]'s doc comment)
             *  once [awaitResumeOrCapExpired] gives up without the owner resuming in time. */
            const val PAUSED_STEP_MESSAGE = "Paused by the device owner"

            /** Reply when the floating control bar's Stop button cancels an in-flight step (plan
             *  71, D-39) - distinct from [PAUSED_STEP_MESSAGE], which is a step that never started
             *  because the device was already paused. */
            const val STOPPED_BY_OWNER_MESSAGE = "Stopped by the device owner"
            private val BACKOFF_SCHEDULE_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

            /** OkHttp WS heartbeat cadence - see [transportOkHttpClient]. */
            internal const val PING_INTERVAL_SECONDS = 30L
        }
    }

/** What one [DeviceTransportClientImpl.runSession] attempt ended with: `welcomed` resets the
 *  reconnect backoff, `replaced` stops the loop entirely (close code 4004). */
private data class SessionOutcome(
    val welcomed: Boolean,
    val replaced: Boolean,
)

/** Builds the OkHttpClient the transport WebSocket runs on. Sets a 30s ping interval so a
 *  half-open/dead connection (phone left Wi-Fi, NAT timeout, server lost the socket without a
 *  close frame) is detected within ~2 pings: OkHttp fails the WebSocket when no pong comes back,
 *  which throws through [DeviceTransportClientImpl.runSession]'s existing failure path and lets
 *  the reconnect loop take the status out of `Connected` - 16 Oct phone test: Home showed a green
 *  "Connected" while the server had lost the phone, because without a heartbeat nothing detects
 *  a dead socket. The Node `ws` server answers pings automatically; no server change.
 *
 * Top-level and `internal` so the built client's ping configuration can be unit-tested without
 * instantiating the transport client. */
internal fun transportOkHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .pingInterval(DeviceTransportClientImpl.PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

/** `true` the instant [pauseSettings] reports the device isn't (or stops being) paused - the
 *  common, un-paused case returns immediately with no delay at all. Polls
 *  [PauseSettings.getPauseState] every [PAUSE_HOLD_POLL_INTERVAL_MS] rather than collecting
 *  [PauseSettings.pauseState]'s Flow, because a TIMED pause's deadline simply elapsing writes
 *  nothing to the DataStore - a collector would never see a new emission to re-check
 *  [PauseState.isEffectivePause] against, and would hold right past the deadline. `false` if
 *  [PauseSettings.getPauseHoldCapMs] elapses while still paused. A top-level function, not a class
 *  member - keeps [DeviceTransportClientImpl] under detekt's `TooManyFunctions` threshold without
 *  an unjustified suppression, same reasoning as [decodeOrNull] below. */
private suspend fun awaitResumeOrCapExpired(pauseSettings: PauseSettings): Boolean {
    if (!pauseSettings.getPauseState().isEffectivePause(System.currentTimeMillis())) return true
    val capMs = pauseSettings.getPauseHoldCapMs()
    return withTimeoutOrNull(capMs) {
        while (pauseSettings.getPauseState().isEffectivePause(System.currentTimeMillis())) {
            delay(PAUSE_HOLD_POLL_INTERVAL_MS)
        }
        true
    } ?: false
}

/** Completes the waiter of an in-flight [DeviceTransportClient.listAiConnections] with the
 *  server's `ai_connections_list` reply. A top-level function, not a class member - keeps
 *  [DeviceTransportClientImpl] and [handleIncomingMessage] within detekt's function-count and
 *  complexity limits, same reasoning as [decodeOrNull]. */
private fun completeAiConnectionsReply(
    message: AiConnectionsList,
    pending: CompletableDeferred<AiConnectionsResult>?,
) {
    pending?.complete(
        if (message.ok && message.connections != null) {
            AiConnectionsResult.Connections(message.connections)
        } else {
            AiConnectionsResult.Error(message.error?.code ?: "unknown", message.error?.message)
        },
    )
}

/** Completes the waiter of an in-flight [DeviceTransportClient.revokeAiConnection] with the
 *  server's `ai_connection_revoked` reply — same reasoning as [completeAiConnectionsReply]. */
private fun completeAiRevokeReply(
    message: AiConnectionRevoked,
    pending: CompletableDeferred<AiRevokeResult>?,
) {
    pending?.complete(
        if (message.ok) {
            AiRevokeResult.Revoked
        } else {
            AiRevokeResult.Error(message.error?.code ?: "unknown", message.error?.message)
        },
    )
}

/** This phone's hello: protocol v2, the app's own version, and the device diagnostics. A
 *  top-level function, not a class member - keeps [DeviceTransportClientImpl] under detekt's
 *  `TooManyFunctions` threshold, same reasoning as [decodeOrNull]. */
private fun helloFor(
    deviceInfoProvider: DeviceInfoProvider,
    deviceId: String,
): Hello =
    Hello(
        protocolVersion = 2,
        apkVersion = BuildConfig.VERSION_NAME,
        deviceId = deviceId,
        capabilities = emptyList(),
        mode = "live",
        flowManifest = emptyList(),
        androidVersion = deviceInfoProvider.androidVersion,
        deviceModel = deviceInfoProvider.deviceModel,
    )

/** This phone's derived device id - a cheap recompute (one base64 decode, one SHA-256 hash,
 *  no I/O), never stored. Top-level, same reasoning as [helloFor]. */
private fun currentDeviceId(deviceIdentityKeyStore: DeviceIdentityKeyStore): String {
    val publicKeyDer = Base64.getDecoder().decode(deviceIdentityKeyStore.ensurePublicKeyBase64())
    return deriveDeviceId(publicKeyDer)
}

/** The server's `CLOSE_REPLACED`: an established connection retired by another phone's `sign_in`
 *  with `replace_device_id` (protocol `src/close-codes.ts`). */
private const val CLOSE_REPLACED_CODE: Short = 4004

/** How often [awaitResumeOrCapExpired] re-checks [PauseSettings.getPauseState] while holding a
 *  step - see that function's doc comment for why this polls instead of collecting the Flow. */
private const val PAUSE_HOLD_POLL_INTERVAL_MS = 500L

/** Applies the outcome of the server's close once `incoming` has completed — the only place a
 *  4000-4004 close is observable (Ktor's client WS doesn't throw for a normal close frame, it just
 *  ends the channel). Before a `welcome` any close is a handshake-level [TransportStatus.Rejected];
 *  a 4004 (`CLOSE_REPLACED`) on an established session is [TransportStatus.Replaced]: the local
 *  account association is cleared (no `sign_out` is sent — the server has already moved this
 *  phone) and no pending flag is left, so the next connection replays nothing. Returns `true` iff
 *  the close was a 4004 after welcome — the caller's reconnect loop stops on it. A top-level
 *  function, not a class member - keeps [DeviceTransportClientImpl] within detekt's per-function
 *  complexity/`TooManyFunctions` limits, same reasoning as [decodeOrNull]. */
private suspend fun DefaultClientWebSocketSession.handleSessionClose(
    welcomed: Boolean,
    status: MutableStateFlow<TransportStatus>,
    accountSettings: AccountSettings,
): Boolean {
    val reason = closeReason.await()
    val replaced = welcomed && reason?.code == CLOSE_REPLACED_CODE
    if (!welcomed) {
        Logger.w(
            "MCP:DeviceTransport",
            "Closed before welcome: code=${reason?.code} message=${reason?.message}",
        )
        status.value = TransportStatus.Rejected(reason?.code, reason?.message ?: "closed before welcome")
    }
    if (replaced) {
        Logger.w("MCP:DeviceTransport", "Closed with CLOSE_REPLACED: this phone was replaced")
        accountSettings.clearAccountId()
        accountSettings.setPendingSignOut(false)
        status.value = TransportStatus.Replaced
    }
    return replaced
}

/** Decodes one inbound frame, or null for a non-text frame or a malformed payload (logged and
 *  skipped rather than tearing down the whole session over one bad frame). A top-level function,
 *  not a class member - keeps [DeviceTransportClientImpl] under detekt's `TooManyFunctions`
 *  threshold without an unjustified suppression; this has no dependency on the class's own state. */
@Suppress("TooGenericExceptionCaught")
private fun decodeOrNull(frame: Frame): WireMessage? {
    if (frame !is Frame.Text) return null
    return try {
        wireJson.decodeFromString(WireMessage.serializer(), frame.readText())
    } catch (e: Exception) {
        Logger.w("MCP:DeviceTransport", "Ignoring malformed frame: ${e.message}")
        null
    }
}
