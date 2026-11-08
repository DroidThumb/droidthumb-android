package com.danielealbano.androidremotecontrolmcp.services.transport

import com.danielealbano.androidremotecontrolmcp.wireprotocol.AiConnection
import com.danielealbano.androidremotecontrolmcp.wireprotocol.AiConnectionRevoked
import com.danielealbano.androidremotecontrolmcp.wireprotocol.AiConnectionsError
import com.danielealbano.androidremotecontrolmcp.wireprotocol.AiConnectionsList
import com.danielealbano.androidremotecontrolmcp.wireprotocol.ListAiConnections
import com.danielealbano.androidremotecontrolmcp.wireprotocol.RevokeAiConnection
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Welcome
import com.danielealbano.androidremotecontrolmcp.wireprotocol.WireMessage
import com.danielealbano.androidremotecontrolmcp.wireprotocol.wireJson
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * `list_ai_connections`/`revoke_ai_connection` behaviour of [DeviceTransportClientImpl] against
 * the same real, host-side Ktor WS server as [DeviceTransportClientTest] — split out to keep that
 * class under detekt's `LargeClass` threshold; shares [DeviceTransportClientTestBase]'s fixtures.
 */
class DeviceTransportClientAiConnectionsTest : DeviceTransportClientTestBase() {
    @Test
    fun `listAiConnections sends list_ai_connections and returns the connections on ok`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 2, accounts = true)),
                        ),
                    )
                    val next = incoming.receive() as Frame.Text
                    val message = wireJson.decodeFromString(WireMessage.serializer(), next.readText())
                    check(message is ListAiConnections)
                    val reply =
                        AiConnectionsList(
                            ok = true,
                            connections =
                                listOf(
                                    AiConnection(
                                        clientId = "cl_1",
                                        clientName = "Claude",
                                        displayName = "My Claude",
                                        imageUrl = null,
                                        connectedAt = "2026-10-01T08:30:00Z",
                                        lastUsedAt = null,
                                    ),
                                ),
                        )
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), reply)))
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                val result = withTimeout(8.seconds) { client.listAiConnections() }
                val connections = checkNotNull((result as AiConnectionsResult.Connections).connections)
                assertEquals(1, connections.size)
                assertEquals("cl_1", connections.first().clientId)
                assertEquals("My Claude", connections.first().displayName)
                client.stop()
            }
        }

    @Test
    fun `listAiConnections returns Error with not_signed_in when the phone is signed out`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 2, accounts = true)),
                        ),
                    )
                    incoming.receive() // list_ai_connections
                    val reply = AiConnectionsList(ok = false, error = AiConnectionsError("not_signed_in", null))
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), reply)))
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                val result = withTimeout(8.seconds) { client.listAiConnections() }
                assertEquals(AiConnectionsResult.Error("not_signed_in", null), result)
                client.stop()
            }
        }

    @Test
    fun `listAiConnections returns NotConnected immediately when never connected`() =
        runBlocking {
            val client = newClient()
            assertEquals(AiConnectionsResult.NotConnected, client.listAiConnections())
        }

    @Test
    fun `listAiConnections returns NotConnected, not TimedOut, when the connection drops before the reply`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 2, accounts = true)),
                        ),
                    )
                    incoming.receive() // list_ai_connections
                    coroutineContext.cancel() // abrupt drop: the reply never arrives
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                awaitStatus<TransportStatus.Connected>(client)
                val result = withTimeout(8.seconds) { client.listAiConnections() }
                assertEquals(AiConnectionsResult.NotConnected, result)
                client.stop()
            }
        }

    @Test
    fun `revokeAiConnection sends revoke_ai_connection with the connection_id and returns Revoked on ok`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 2, accounts = true)),
                        ),
                    )
                    val next = incoming.receive() as Frame.Text
                    val message = wireJson.decodeFromString(WireMessage.serializer(), next.readText())
                    check(message is RevokeAiConnection)
                    assertEquals("cl_1", message.connectionId)
                    val reply = AiConnectionRevoked(ok = true)
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), reply)))
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                val result = withTimeout(8.seconds) { client.revokeAiConnection("cl_1") }
                assertEquals(AiRevokeResult.Revoked, result)
                client.stop()
            }
        }

    @Test
    fun `revokeAiConnection returns Error with not_found for an unknown connection`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 2, accounts = true)),
                        ),
                    )
                    incoming.receive() // revoke_ai_connection
                    val reply =
                        AiConnectionRevoked(ok = false, error = AiConnectionsError("not_found", "no such connection"))
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), reply)))
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                val result = withTimeout(8.seconds) { client.revokeAiConnection("cl_1") }
                assertEquals(AiRevokeResult.Error("not_found", "no such connection"), result)
                client.stop()
            }
        }

    @Test
    fun `revokeAiConnection returns NotConnected immediately when never connected`() =
        runBlocking {
            val client = newClient()
            assertEquals(AiRevokeResult.NotConnected, client.revokeAiConnection("cl_1"))
        }
}
