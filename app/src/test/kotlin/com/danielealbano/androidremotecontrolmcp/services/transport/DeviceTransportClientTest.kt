package com.danielealbano.androidremotecontrolmcp.services.transport

import com.danielealbano.androidremotecontrolmcp.wireprotocol.Hello
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
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
                val client = DeviceTransportClientImpl(stepDispatcherMock())
                client.start("127.0.0.1", port, "device-1")
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                assertEquals(TransportStatus.Connected(1), client.status.value)
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
                val client = DeviceTransportClientImpl(stepDispatcherMock())
                client.start("127.0.0.1", port, "device-1")
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Rejected) kotlinx.coroutines.yield()
                }
                val rejected = client.status.value as TransportStatus.Rejected
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
                val client = DeviceTransportClientImpl(dispatcher)
                client.start("127.0.0.1", port, "device-1")
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
                val client = DeviceTransportClientImpl(stepDispatcherMock())
                client.start("127.0.0.1", port, "device-1")
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
                val client = DeviceTransportClientImpl(stepDispatcherMock())
                client.start("127.0.0.1", port, "device-1")
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                client.stop()
                assertEquals(TransportStatus.Idle, client.status.value)
            }
        }
}
