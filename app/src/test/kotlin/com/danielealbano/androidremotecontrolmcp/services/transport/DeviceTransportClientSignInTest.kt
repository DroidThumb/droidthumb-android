package com.danielealbano.androidremotecontrolmcp.services.transport

import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignIn
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignInRejected
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignOut
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignedIn
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignedOut
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Welcome
import com.danielealbano.androidremotecontrolmcp.wireprotocol.WireMessage
import com.danielealbano.androidremotecontrolmcp.wireprotocol.wireJson
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.mockk.coVerify
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Sign-in/sign-out and welcome `accounts` behaviour of [DeviceTransportClientImpl] against the
 * same real, host-side Ktor WS server as [DeviceTransportClientTest] — split out to keep that
 * class under detekt's `LargeClass` threshold; both share [DeviceTransportClientTestBase]'s
 * fixtures.
 */
class DeviceTransportClientSignInTest : DeviceTransportClientTestBase() {
    @Test
    fun `signIn sends sign_in with install_hint and completes on signed_in`() =
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
                    check(message is SignIn)
                    assertEquals("clt_abc123", message.accountToken)
                    assertEquals(TEST_INSTALL_HINT, message.installHint)
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), SignedIn("acc_1"))))
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                val result = withTimeout(8.seconds) { client.signIn("clt_abc123") }
                assertEquals(SignInResult.SignedIn("acc_1"), result)
                client.stop()
            }
        }

    @Test
    fun `signIn completes with Rejected on sign_in_rejected`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 2, accounts = true)),
                        ),
                    )
                    incoming.receive() // sign_in
                    val rejected = SignInRejected("invalid_token")
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), rejected)))
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                val result = withTimeout(8.seconds) { client.signIn("clt_abc123") }
                assertEquals(SignInResult.Rejected("invalid_token"), result)
                client.stop()
            }
        }

    @Test
    fun `signIn times out when the server sends no reply`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 2, accounts = true)),
                        ),
                    )
                    incoming.receive() // sign_in, deliberately never answered
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                val result = withTimeout(8.seconds) { client.signIn("clt_abc123") }
                assertEquals(SignInResult.TimedOut, result)
                client.stop()
            }
        }

    @Test
    fun `signIn returns NotConnected immediately when never connected`() =
        runBlocking {
            val client = newClient()
            val result = client.signIn("clt_abc123")
            assertEquals(SignInResult.NotConnected, result)
        }

    @Test
    fun `signOut sends sign_out, returns true on signed_out, and clears the pending flag`() =
        runBlocking {
            val accountSettings = accountSettingsMock()
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
                    check(message is SignOut)
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), SignedOut)))
                },
            ) { port ->
                val client = newClient(accountSettings = accountSettings)
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                val result = withTimeout(8.seconds) { client.signOut() }
                assertTrue(result)
                coVerify { accountSettings.setPendingSignOut(false) }
                client.stop()
            }
        }

    @Test
    fun `signOut times out when the server sends no reply, and the pending flag stays set`() =
        runBlocking {
            val accountSettings = accountSettingsMock()
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 2, accounts = true)),
                        ),
                    )
                    incoming.receive() // sign_out, deliberately never answered
                },
            ) { port ->
                val client = newClient(accountSettings = accountSettings)
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (client.status.value !is TransportStatus.Connected) kotlinx.coroutines.yield()
                }
                val result = withTimeout(8.seconds) { client.signOut() }
                assertFalse(result)
                coVerify(exactly = 0) { accountSettings.setPendingSignOut(false) }
                client.stop()
            }
        }

    @Test
    fun `signOut returns false immediately when never connected`() =
        runBlocking {
            val client = newClient()
            assertFalse(client.signOut())
        }

    @Test
    fun `a pending sign-out is sent on the next connection after a reconnect, and cleared on signed_out`() =
        runBlocking {
            val accountSettings = accountSettingsMock(initialPendingSignOut = true)
            val connectionCount = AtomicInteger(0)
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 2, accounts = true)),
                        ),
                    )
                    // Both connections see the pending sign-out replayed right after welcome; the
                    // first connection never answers it (it drops instead), the second does.
                    val next = incoming.receive() as Frame.Text
                    val message = wireJson.decodeFromString(WireMessage.serializer(), next.readText())
                    check(message is SignOut) { "expected sign_out, got $message" }
                    if (connectionCount.getAndIncrement() == 0) {
                        coroutineContext.cancel() // abrupt drop: signed_out never arrives
                    } else {
                        send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), SignedOut)))
                    }
                },
            ) { port ->
                val client = newClient(accountSettings = accountSettings)
                client.start("127.0.0.1", port, tls = false)
                withTimeout(30.seconds) {
                    while (connectionCount.get() < 2) kotlinx.coroutines.yield()
                }
                // The second connection's sign_out must have been acknowledged, clearing the flag.
                coVerify(timeout = 5_000, exactly = 1) { accountSettings.setPendingSignOut(false) }
                client.stop()
            }
        }

    @Test
    fun `welcome with accounts false is surfaced on Connected`() =
        runBlocking {
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(
                                WireMessage.serializer(),
                                Welcome(true, 2, accounts = false, accountId = null),
                            ),
                        ),
                    )
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                val connected = awaitStatus<TransportStatus.Connected>(client)
                assertEquals(
                    TransportStatus.Connected(2, accounts = false, accountId = null),
                    connected,
                )
                client.stop()
            }
        }
}
