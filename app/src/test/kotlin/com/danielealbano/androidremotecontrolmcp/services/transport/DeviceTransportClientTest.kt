package com.danielealbano.androidremotecontrolmcp.services.transport

import com.danielealbano.androidremotecontrolmcp.data.repository.ConnectorUrlSettings
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceIdentityKeyStore
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceInfoProvider
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Challenge
import com.danielealbano.androidremotecontrolmcp.wireprotocol.ChallengeResponse
import com.danielealbano.androidremotecontrolmcp.wireprotocol.ClaimAccount
import com.danielealbano.androidremotecontrolmcp.wireprotocol.ClaimRejected
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Claimed
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Hello
import com.danielealbano.androidremotecontrolmcp.wireprotocol.RegenerateSecret
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SecretRegenerated
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Step
import com.danielealbano.androidremotecontrolmcp.wireprotocol.StepDispatcher
import com.danielealbano.androidremotecontrolmcp.wireprotocol.StepResult
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Welcome
import com.danielealbano.androidremotecontrolmcp.wireprotocol.WireMessage
import com.danielealbano.androidremotecontrolmcp.wireprotocol.wireJson
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises [DeviceTransportClientImpl] against a real, host-side Ktor WS server standing in for
 * `droidthumb-server`'s handshake — the same fake-server-on-the-JVM pattern
 * `EventDispatcherImplTest` already uses for HTTP, extended to WebSockets so the handshake and
 * reconnect logic are verified against real frames, not a mock.
 */
class DeviceTransportClientTest {
    private fun stepDispatcherMock(): StepDispatcher = mockk()

    private companion object {
        const val TEST_PUBLIC_KEY_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaLYBkdZrrs2nrNpdnPkFSS4F00NwONoA5e6B4Q6pB/5Oaxi6NUPTW7pJ80l8L+0Aaxt1V/87nXFRb83soCH0Sw=="
        const val TEST_DEVICE_ID = "dt_1d5aaa900ef5ffb98ae91a05932113643681ebc96ed43bdd084195ce34a9b09e"
    }

    private fun newClient(
        dispatcher: StepDispatcher = stepDispatcherMock(),
        identityKeyStore: DeviceIdentityKeyStore =
            mockk {
                every { ensurePublicKeyBase64() } returns TEST_PUBLIC_KEY_BASE64
                every { signNonce(any()) } answers { "sig-for-${firstArg<String>()}" }
            },
        deviceInfoProvider: DeviceInfoProvider =
            mockk {
                every { androidVersion } returns 34
                every { deviceModel } returns "Google Pixel 8"
            },
        registrationClient: DeviceRegistrationClient =
            mockk {
                coEvery { register(any(), any(), any(), any()) } returns
                    DeviceRegistrationResult.Success(TEST_DEVICE_ID, null)
            },
        connectorUrlSettings: ConnectorUrlSettings = mockk(relaxed = true),
    ): DeviceTransportClientImpl =
        DeviceTransportClientImpl(
            dispatcher,
            identityKeyStore,
            deviceInfoProvider,
            registrationClient,
            connectorUrlSettings,
        )

    /**
     * Waits until `client.status` matches [T], returning that exact snapshot — never re-reading
     * `client.status.value` afterward. Several fake-server handlers below return immediately after
     * sending `Welcome` (closing the connection from the server side), which sends the real client
     * straight back into `Reconnecting`/`Connecting` on the very next loop iteration with no delay;
     * a plain `while (status !is X) yield()` followed by a second read is a TOCTOU race against that
     * transition, found live (not theoretical) once `ensureRegistered()`'s extra suspension point
     * per loop iteration shifted the scheduling enough to make it flaky under `:app:test`'s full run.
     */
    private suspend inline fun <reified T : TransportStatus> awaitStatus(client: DeviceTransportClientImpl): T =
        withTimeout(15.seconds) {
            var captured: T? = null
            while (captured == null) {
                val current = client.status.value
                if (current is T) captured = current else kotlinx.coroutines.yield()
            }
            captured
        }

    private suspend fun <T> withFakeServer(
        handler: suspend io.ktor.server.websocket.DefaultWebSocketServerSession.() -> Unit,
        block: suspend (port: Int) -> T,
    ): T {
        val server =
            embeddedServer(Netty, port = 0) {
                install(WebSockets)
                routing {
                    webSocket("/device") { handler() }
                }
            }
        server.start(wait = false)
        val port =
            server.engine
                .resolvedConnectors()
                .first()
                .port
        try {
            return block(port)
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `connects, sends a schema-shaped hello, and reaches Connected on welcome`() =
        runBlocking {
            withFakeServer(
                handler = {
                    val helloFrame = incoming.receive() as Frame.Text
                    val hello = wireJson.decodeFromString(WireMessage.serializer(), helloFrame.readText())
                    check(hello is Hello) { "expected hello, got $hello" }
                    check(hello.protocolVersion == 1)
                    check(hello.mode == "live")
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 1, null))))
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                val connected = awaitStatus<TransportStatus.Connected>(client)
                assertEquals(TransportStatus.Connected(1), connected)
                client.stop()
            }
        }

    @Test
    fun `server closes with 4001 before welcome, status becomes Rejected with that code`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    close(CloseReason(4001, "unsupported protocol_version"))
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                // Rejected is transient here — the reconnect loop flips straight to Reconnecting
                // right after, with no delay before that write on the very first retry.
                val rejected = awaitStatus<TransportStatus.Rejected>(client)
                assertEquals(4001.toShort(), rejected.closeCode)
                client.stop()
            }
        }

    @Test
    fun `receives a step, dispatches it, and sends back the dispatcher's reply`() =
        runBlocking {
            val dispatcher = stepDispatcherMock()
            val expectedReply = StepResult(stepId = "s1", output = null)
            coEvery { dispatcher.dispatch(any()) } returns expectedReply
            val receivedReply = AtomicReference<String?>(null)

            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 1, null))))
                    send(
                        Frame.Text(
                            wireJson.encodeToString(
                                WireMessage.serializer(),
                                Step(stepId = "s1", op = "tap", params = null),
                            ),
                        ),
                    )
                    val reply = incoming.receive() as Frame.Text
                    receivedReply.set(reply.readText())
                },
            ) { port ->
                val client = newClient(dispatcher = dispatcher)
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (receivedReply.get() == null) kotlinx.coroutines.yield()
                }
                val decoded = wireJson.decodeFromString(WireMessage.serializer(), receivedReply.get()!!)
                assertTrue(decoded is StepResult)
                assertEquals("s1", (decoded as StepResult).stepId)
                client.stop()
            }
        }

    @Test
    fun `stop while still mid-handshake before welcome leaves status Idle, not stuck Rejected`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    // Deliberately never send welcome or close — a slow/hanging handshake, so
                    // stop() below races the still-suspended incoming.receive() in runSession().
                    kotlinx.coroutines.delay(10_000)
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connecting) kotlinx.coroutines.yield()
                }
                client.stop()
                // Give the cancelled session coroutine a moment to (not) write a stray status
                // update after stop()'s own synchronous Idle write.
                kotlinx.coroutines.delay(200)
                assertEquals(TransportStatus.Idle, client.status.value)
            }
        }

    @Test
    fun `stop cancels the session and sets status to Idle`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive()
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 1, null))))
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                client.stop()
                assertEquals(TransportStatus.Idle, client.status.value)
            }
        }

    @Test
    fun `hello carries the device id derived from the public key`() =
        runBlocking {
            val receivedDeviceId = AtomicReference<String?>(null)
            withFakeServer(
                handler = {
                    val helloFrame = incoming.receive() as Frame.Text
                    val hello = wireJson.decodeFromString(WireMessage.serializer(), helloFrame.readText())
                    check(hello is Hello)
                    receivedDeviceId.set(hello.deviceId)
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 1, null))))
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (receivedDeviceId.get() == null) kotlinx.coroutines.yield()
                }
                assertEquals(TEST_DEVICE_ID, receivedDeviceId.get())
                client.stop()
            }
        }

    @Test
    fun `answers a challenge with the signed nonce before welcome`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Challenge("nonce-1"))))
                    val responseFrame = incoming.receive() as Frame.Text
                    val response = wireJson.decodeFromString(WireMessage.serializer(), responseFrame.readText())
                    check(response is ChallengeResponse)
                    check(response.signature == "sig-for-nonce-1") { "unexpected signature: ${response.signature}" }
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 1, null))))
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                val connected = awaitStatus<TransportStatus.Connected>(client)
                assertEquals(TransportStatus.Connected(1), connected)
                client.stop()
            }
        }

    @Test
    fun `hello carries android_version and device_model from DeviceInfoProvider`() =
        runBlocking {
            val received = AtomicReference<Hello?>(null)
            withFakeServer(
                handler = {
                    val helloFrame = incoming.receive() as Frame.Text
                    val hello = wireJson.decodeFromString(WireMessage.serializer(), helloFrame.readText())
                    check(hello is Hello)
                    received.set(hello)
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 1, null))))
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (received.get() == null) kotlinx.coroutines.yield()
                }
                assertEquals(34, received.get()?.androidVersion)
                assertEquals("Google Pixel 8", received.get()?.deviceModel)
                client.stop()
            }
        }

    @Test
    fun `registers once before the first session, not again after a reconnect`() =
        runBlocking {
            val registrationClient =
                mockk<DeviceRegistrationClient> {
                    coEvery { register(any(), any(), any(), any()) } returns
                        DeviceRegistrationResult.Success(TEST_DEVICE_ID, null)
                }
            val connectionCount = AtomicInteger(0)
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    if (connectionCount.getAndIncrement() == 0) {
                        close(CloseReason(1011, "forcing a reconnect"))
                    } else {
                        send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 1, null))))
                    }
                },
            ) { port ->
                val client = newClient(registrationClient = registrationClient)
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                coVerify(exactly = 1) { registrationClient.register(any(), any(), any(), any()) }
                client.stop()
            }
        }

    @Test
    fun `a registration Success with a connector_url persists it`() =
        runBlocking {
            val registrationClient =
                mockk<DeviceRegistrationClient> {
                    coEvery { register(any(), any(), any(), any()) } returns
                        DeviceRegistrationResult.Success(TEST_DEVICE_ID, "https://h/d/x/mcp")
                }
            val connectorUrlSettings = mockk<ConnectorUrlSettings>(relaxed = true)
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 1, null))))
                },
            ) { port ->
                val client =
                    newClient(registrationClient = registrationClient, connectorUrlSettings = connectorUrlSettings)
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                coVerify { connectorUrlSettings.updateConnectorUrl("https://h/d/x/mcp") }
                client.stop()
            }
        }

    @Test
    fun `a registration response with a mismatched device_id is not registered as successful`() =
        runBlocking {
            val registrationClient =
                mockk<DeviceRegistrationClient> {
                    coEvery { register(any(), any(), any(), any()) } returns
                        DeviceRegistrationResult.Success("dt_wrong", null)
                }
            val connectorUrlSettings = mockk<ConnectorUrlSettings>(relaxed = true)
            val connectionCount = AtomicInteger(0)
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    close(CloseReason(1011, "forcing a reconnect"))
                    connectionCount.incrementAndGet()
                },
            ) { port ->
                val client =
                    newClient(registrationClient = registrationClient, connectorUrlSettings = connectorUrlSettings)
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (connectionCount.get() < 2) kotlinx.coroutines.yield()
                }
                coVerify(exactly = 1) { registrationClient.register(any(), any(), any(), any()) }
                coVerify(exactly = 0) { connectorUrlSettings.updateConnectorUrl(any()) }
                client.stop()
            }
        }

    @Test
    fun `welcome's update-info fields are exposed via updateInfo`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(
                                WireMessage.serializer(),
                                Welcome(true, 1, null, "2.0.0", "1.5.0", "https://h/apk"),
                            ),
                        ),
                    )
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.updateInfo.value == null) kotlinx.coroutines.yield()
                }
                assertEquals(UpdateInfo("2.0.0", "1.5.0", "https://h/apk"), client.updateInfo.value)
                client.stop()
            }
        }

    @Test
    fun `regenerateSecret sends regenerate_secret and completes on secret_regenerated`() =
        runBlocking {
            val connectorUrlSettings = mockk<ConnectorUrlSettings>(relaxed = true)
            val receivedRegenerate = AtomicReference<String?>(null)
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 1, null))))
                    val next = incoming.receive() as Frame.Text
                    val message = wireJson.decodeFromString(WireMessage.serializer(), next.readText())
                    check(message is RegenerateSecret)
                    receivedRegenerate.set("received")
                    send(
                        Frame.Text(
                            wireJson.encodeToString(
                                WireMessage.serializer(),
                                SecretRegenerated("https://h/d/new/mcp"),
                            ),
                        ),
                    )
                },
            ) { port ->
                val client = newClient(connectorUrlSettings = connectorUrlSettings)
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                val result = withTimeout(8.seconds) { client.regenerateSecret() }
                assertTrue(result)
                coVerify { connectorUrlSettings.updateConnectorUrl("https://h/d/new/mcp") }
                client.stop()
            }
        }

    @Test
    fun `regenerateSecret times out when the server sends no reply`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 1, null))))
                    incoming.receive() // regenerate_secret, deliberately never answered
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                val result = withTimeout(8.seconds) { client.regenerateSecret() }
                assertFalse(result)
                client.stop()
            }
        }

    @Test
    fun `regenerateSecret returns false immediately when never connected`() =
        runBlocking {
            val client = newClient()
            val result = client.regenerateSecret()
            assertFalse(result)
        }

    @Test
    fun `claimAccount sends claim_account and completes on claimed`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 1, null))))
                    val next = incoming.receive() as Frame.Text
                    val message = wireJson.decodeFromString(WireMessage.serializer(), next.readText())
                    check(message is ClaimAccount)
                    assertEquals("clt_abc123", message.accountToken)
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Claimed("acc_1"))))
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                val result = withTimeout(8.seconds) { client.claimAccount("clt_abc123") }
                assertEquals(ClaimResult.Claimed("acc_1"), result)
                client.stop()
            }
        }

    @Test
    fun `claimAccount completes with Rejected on claim_rejected`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 1, null))))
                    incoming.receive() // claim_account
                    val rejected = ClaimRejected("already_claimed")
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), rejected)))
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                val result = withTimeout(8.seconds) { client.claimAccount("clt_abc123") }
                assertEquals(ClaimResult.Rejected("already_claimed"), result)
                client.stop()
            }
        }

    @Test
    fun `claimAccount times out when the server sends no reply`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 1, null))))
                    incoming.receive() // claim_account, deliberately never answered
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                val result = withTimeout(8.seconds) { client.claimAccount("clt_abc123") }
                assertEquals(ClaimResult.TimedOut, result)
                client.stop()
            }
        }

    @Test
    fun `claimAccount returns NotConnected immediately when never connected`() =
        runBlocking {
            val client = newClient()
            val result = client.claimAccount("clt_abc123")
            assertEquals(ClaimResult.NotConnected, result)
        }

    @Test
    fun `tls = true attempts a TLS handshake, which fails against a plain (non-TLS) fake server`() =
        runBlocking {
            withFakeServer(
                handler = {
                    // Never reached: a real TLS ClientHello against this plain-HTTP server can't
                    // complete the WS upgrade at all.
                    incoming.receive()
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = true)
                withTimeout(15.seconds) {
                    var sawConnecting = false
                    while (true) {
                        val status = client.status.value
                        if (status is TransportStatus.Connected) {
                            error("tls = true should not have reached Connected against a plain fake server")
                        }
                        if (status is TransportStatus.Connecting || status is TransportStatus.Reconnecting) {
                            sawConnecting = true
                        }
                        if (sawConnecting && status is TransportStatus.Rejected) break
                        kotlinx.coroutines.yield()
                    }
                }
                client.stop()
            }
        }

    @Test
    fun `the connector URL never reaches Logger`() {
        mockkObject(Logger)
        val capturedMessages = mutableListOf<String>()
        every { Logger.d(any(), any()) } answers { capturedMessages.add(secondArg<String>()) }
        every { Logger.i(any(), any()) } answers { capturedMessages.add(secondArg<String>()) }
        every { Logger.w(any(), any(), any()) } answers { capturedMessages.add(secondArg<String>()) }
        every { Logger.e(any(), any(), any()) } answers { capturedMessages.add(secondArg<String>()) }
        try {
            runBlocking {
                val registrationClient =
                    mockk<DeviceRegistrationClient> {
                        coEvery { register(any(), any(), any(), any()) } returns
                            DeviceRegistrationResult.Success(TEST_DEVICE_ID, "https://h/d/x/mcp")
                    }
                withFakeServer(
                    handler = {
                        incoming.receive() // hello
                        send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 1, null))))
                        val next = incoming.receive() as Frame.Text
                        val message = wireJson.decodeFromString(WireMessage.serializer(), next.readText())
                        check(message is RegenerateSecret)
                        send(
                            Frame.Text(
                                wireJson.encodeToString(
                                    WireMessage.serializer(),
                                    SecretRegenerated("https://h/d/new/mcp"),
                                ),
                            ),
                        )
                    },
                ) { port ->
                    val client = newClient(registrationClient = registrationClient)
                    client.start("127.0.0.1", port, tls = false)
                    withTimeout(15.seconds) {
                        while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                    }
                    withTimeout(8.seconds) { client.regenerateSecret() }
                    client.stop()
                }
            }
            for (message in capturedMessages) {
                val leakMessage = "Logger message leaked the connector URL: $message"
                assertFalse(message.contains("https://h/d/x/mcp"), leakMessage)
                assertFalse(message.contains("https://h/d/new/mcp"), leakMessage)
            }
        } finally {
            unmockkObject(Logger)
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(Logger)
    }
}
