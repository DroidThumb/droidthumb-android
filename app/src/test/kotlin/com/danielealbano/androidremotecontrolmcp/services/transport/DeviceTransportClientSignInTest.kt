package com.danielealbano.androidremotecontrolmcp.services.transport

import com.danielealbano.androidremotecontrolmcp.services.identity.InstallHintProvider
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignIn
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignInRejected
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignOut
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignedIn
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SignedOut
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Welcome
import com.danielealbano.androidremotecontrolmcp.wireprotocol.WireMessage
import com.danielealbano.androidremotecontrolmcp.wireprotocol.wireJson
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
                    // Hold the connection OPEN: a server that CLOSES it is a drop, which the
                    // client now reports as NotConnected immediately (not TimedOut).
                    delay(10.seconds)
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
    fun `signIn returns NotConnected, not TimedOut, when the connection drops before the reply`() =
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
                    coroutineContext.cancel() // abrupt drop: the reply never arrives
                },
            ) { port ->
                val client = newClient()
                client.start("127.0.0.1", port, tls = false)
                awaitStatus<TransportStatus.Connected>(client)
                val result = withTimeout(8.seconds) { client.signIn("clt_abc123") }
                assertEquals(SignInResult.NotConnected, result)
                client.stop()
            }
        }

    @Test
    fun `a blank install hint is rejected without sending sign_in`() =
        runBlocking {
            val signInSeen = AtomicBoolean(false)
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 2, accounts = true)),
                        ),
                    )
                    // Nothing but the hello may arrive: the sign_in must never be sent.
                    withTimeoutOrNull(2.seconds) {
                        incoming.receive()
                        signInSeen.set(true)
                    }
                },
            ) { port ->
                val blankHint = mockk<InstallHintProvider> { every { installHint } returns "" }
                val client = newClient(installHintProvider = blankHint)
                client.start("127.0.0.1", port, tls = false)
                awaitStatus<TransportStatus.Connected>(client)
                val result = withTimeout(8.seconds) { client.signIn("clt_abc123") }
                assertEquals(SignInResult.Rejected("install_hint_unavailable"), result)
                client.stop()
                assertFalse(signInSeen.get()) { "no sign_in may be sent with a blank install hint" }
            }
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
                    // Hold the connection OPEN: a closed connection is a drop (NotConnected
                    // semantics now), whereas this test is about the bounded 5s wait itself.
                    delay(10.seconds)
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
    fun `a 4004 close after welcome stops reconnecting, signs out locally, and leaves no pending flag`() =
        runBlocking {
            val accountSettings = accountSettingsMock()
            val connectionCount = AtomicInteger(0)
            withFakeServer(
                handler = {
                    connectionCount.getAndIncrement()
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(
                                WireMessage.serializer(),
                                Welcome(true, 2, accounts = true, accountId = "acc_1"),
                            ),
                        ),
                    )
                    close(CloseReason(4004, "replaced"))
                },
            ) { port ->
                val client = newClient(accountSettings = accountSettings)
                client.start("127.0.0.1", port, tls = false)
                val replaced = awaitStatus<TransportStatus.Replaced>(client)
                assertEquals(TransportStatus.Replaced, replaced)
                // The loop is done: after a welcomed session a retry would come with no delay, so
                // a second connection inside this window would mean the loop never stopped.
                kotlinx.coroutines.delay(1_000)
                assertEquals(1, connectionCount.get())
                coVerify(exactly = 1) { accountSettings.clearAccountId() }
                coVerify(exactly = 1) { accountSettings.setPendingSignOut(false) }
                client.stop()
            }
        }

    @Test
    fun `a 4004 close before welcome keeps the Rejected behaviour and touches no account state`() =
        runBlocking {
            val accountSettings = accountSettingsMock()
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    close(CloseReason(4004, "replaced"))
                },
            ) { port ->
                val client = newClient(accountSettings = accountSettings)
                client.start("127.0.0.1", port, tls = false)
                // Rejected is transient — the reconnect loop flips straight to Reconnecting after.
                val rejected = awaitStatus<TransportStatus.Rejected>(client)
                assertEquals(4004.toShort(), rejected.closeCode)
                coVerify(exactly = 0) { accountSettings.clearAccountId() }
                coVerify(exactly = 0) { accountSettings.setPendingSignOut(any()) }
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

    @Test
    fun `signOut while never connected still sets the pending flag and returns false`() =
        runBlocking {
            val accountSettings = accountSettingsMock()
            val client = newClient(accountSettings = accountSettings)
            val result = client.signOut()
            assertFalse(result)
            coVerify(exactly = 1) { accountSettings.setPendingSignOut(true) }
        }

    @Test
    fun `signOut while connected sets the pending flag before sending, and clears it on signed_out`() =
        runBlocking {
            val accountSettings = accountSettingsMock()
            // Sampled inside the setPendingSignOut(true) answer — deterministic proof the flag was
            // persisted before the sign_out frame reached the server.
            val flagSetBeforeSend = AtomicBoolean(false)
            coEvery { accountSettings.setPendingSignOut(true) } answers { flagSetBeforeSend.set(true) }
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
                    check(message is SignOut) { "expected sign_out, got $message" }
                    assertTrue(flagSetBeforeSend.get()) { "pending flag must be set before the send" }
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), SignedOut)))
                },
            ) { port ->
                val client = newClient(accountSettings = accountSettings)
                client.start("127.0.0.1", port, tls = false)
                awaitStatus<TransportStatus.Connected>(client)
                val result = withTimeout(8.seconds) { client.signOut() }
                assertTrue(result)
                coVerifyOrder {
                    accountSettings.setPendingSignOut(true)
                    accountSettings.setPendingSignOut(false)
                }
                client.stop()
            }
        }

    @Test
    fun `signed_in clears the pending flag`() =
        runBlocking {
            val accountSettings = accountSettingsMock(initialPendingSignOut = true)
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 2, accounts = true)),
                        ),
                    )
                    incoming.receive() // sign_in
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), SignedIn("acc_1"))))
                },
            ) { port ->
                val client = newClient(accountSettings = accountSettings)
                client.start("127.0.0.1", port, tls = false)
                awaitStatus<TransportStatus.Connected>(client)
                val result = withTimeout(8.seconds) { client.signIn("clt_abc123") }
                assertEquals(SignInResult.SignedIn("acc_1"), result)
                coVerify(exactly = 1) { accountSettings.setPendingSignOut(false) }
                client.stop()
            }
        }

    @Test
    fun `a pending sign-out is replayed before Connected is published`() =
        runBlocking {
            val accountSettings = accountSettingsMock(initialPendingSignOut = true)
            // Captured inside getPendingSignOut() — the call immediately preceding the sign_out
            // send, so the status at that instant is what callers observed while the replay was
            // still owed.
            val statusAtReplay = AtomicReference<TransportStatus>(null)
            val clientRef = AtomicReference<DeviceTransportClientImpl?>(null)
            coEvery { accountSettings.getPendingSignOut() } answers {
                statusAtReplay.set(clientRef.get()!!.status.value)
                true
            }
            // Atomic because the server handler thread writes it and this test coroutine reads it.
            val signOutSeenByServer = AtomicBoolean(false)
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    send(
                        Frame.Text(
                            wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 2, accounts = true)),
                        ),
                    )
                    // The FIRST message after welcome must be the pending sign_out; only then may
                    // anything else be expected.
                    val next = incoming.receive() as Frame.Text
                    val message = wireJson.decodeFromString(WireMessage.serializer(), next.readText())
                    check(message is SignOut) { "expected replayed sign_out, got $message" }
                    signOutSeenByServer.set(true)
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), SignedOut)))
                },
            ) { port ->
                val client = newClient(accountSettings = accountSettings)
                clientRef.set(client)
                client.start("127.0.0.1", port, tls = false)
                awaitStatus<TransportStatus.Connected>(client)
                // `signed_out` only arrives after the server processed the replayed sign_out, so
                // this bounds the outbox pump's transmission latency (Connected is set when the
                // sign_out is QUEUED, not when the wire send completes).
                coVerify(timeout = 5_000) { accountSettings.setPendingSignOut(false) }
                assertTrue(signOutSeenByServer.get()) { "server must have received the replayed sign_out" }
                assertEquals(TransportStatus.Connecting, statusAtReplay.get())
                client.stop()
            }
        }

    @Test
    fun `after a 4004 close, calling start again reconnects and reaches Connected`() =
        runBlocking {
            val accountSettings = accountSettingsMock()
            val connectionCount = AtomicInteger(0)
            val secondHelloReceived = AtomicBoolean(false)
            withFakeServer(
                handler = {
                    incoming.receive() // hello
                    if (connectionCount.getAndIncrement() == 0) {
                        send(
                            Frame.Text(
                                wireJson.encodeToString(
                                    WireMessage.serializer(),
                                    Welcome(true, 2, accounts = true, accountId = "acc_1"),
                                ),
                            ),
                        )
                        close(CloseReason(4004, "replaced"))
                    } else {
                        secondHelloReceived.set(true)
                        send(
                            Frame.Text(
                                wireJson.encodeToString(WireMessage.serializer(), Welcome(true, 2, accounts = true)),
                            ),
                        )
                    }
                },
            ) { port ->
                val client = newClient(accountSettings = accountSettings)
                client.start("127.0.0.1", port, tls = false)
                val replaced = awaitStatus<TransportStatus.Replaced>(client)
                assertEquals(TransportStatus.Replaced, replaced)
                client.start("127.0.0.1", port, tls = false)
                awaitStatus<TransportStatus.Connected>(client)
                assertTrue(secondHelloReceived.get()) { "the restarted client must send a second hello" }
                client.stop()
            }
        }
}
