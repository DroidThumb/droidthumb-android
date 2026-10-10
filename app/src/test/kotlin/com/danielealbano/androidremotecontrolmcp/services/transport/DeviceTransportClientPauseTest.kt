package com.danielealbano.androidremotecontrolmcp.services.transport

import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import com.danielealbano.androidremotecontrolmcp.data.repository.PauseSettings
import com.danielealbano.androidremotecontrolmcp.services.controlbar.ControlBarCoordinator
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Step
import com.danielealbano.androidremotecontrolmcp.wireprotocol.StepDispatcher
import com.danielealbano.androidremotecontrolmcp.wireprotocol.StepError
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Welcome
import com.danielealbano.androidremotecontrolmcp.wireprotocol.WireMessage
import com.danielealbano.androidremotecontrolmcp.wireprotocol.wireJson
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/** Pause-gating for [DeviceTransportClientImpl] — split out from [DeviceTransportClientTest] to
 *  keep both under detekt's `LargeClass` threshold; shares [DeviceTransportClientTestBase]'s
 *  fixtures. */
class DeviceTransportClientPauseTest : DeviceTransportClientTestBase() {
    @Test
    fun `receives a step while paused, replies with device_paused without dispatching it`() =
        runBlocking {
            val dispatcher = stepDispatcherMock()
            val pauseSettings =
                mockk<PauseSettings> {
                    coEvery { getPauseState() } returns PauseState(isPaused = true, resumeAtEpochMs = null)
                }
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
                val client = newClient(dispatcher = dispatcher, pauseSettings = pauseSettings)
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (receivedReply.get() == null) yield()
                }
                val decoded = wireJson.decodeFromString(WireMessage.serializer(), receivedReply.get()!!)
                assertTrue(decoded is StepError)
                assertEquals("device_paused", (decoded as StepError).code)
                coVerify(exactly = 0) { dispatcher.dispatch(any()) }
                client.stop()
            }
        }

    @Test
    fun `Stop cancels the in-flight step with StepError stopped_by_owner, but the session itself stays connected`() =
        runBlocking {
            // dispatcher.dispatch suspends forever until cancelled - nothing completes it normally,
            // so the only way the step's reply is ever produced is via the cancel hook below.
            val neverCompletes = CompletableDeferred<WireMessage>()
            val dispatcher =
                mockk<StepDispatcher> {
                    coEvery { dispatch(any()) } coAnswers { neverCompletes.await() }
                }
            val cancelSlot = CapturingSlot<() -> Unit>()
            val controlBarCoordinator =
                mockk<ControlBarCoordinator>(relaxed = true) {
                    every { registerCurrentStep(capture(cancelSlot)) } returns Unit
                }
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
                val client = newClient(dispatcher = dispatcher, controlBarCoordinator = controlBarCoordinator)
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (!cancelSlot.isCaptured) yield()
                }
                // Simulate the bar's Stop button: invoke the registered cancel hook directly.
                cancelSlot.captured.invoke()

                withTimeout(15.seconds) {
                    while (receivedReply.get() == null) yield()
                }
                val decoded = wireJson.decodeFromString(WireMessage.serializer(), receivedReply.get()!!)
                assertTrue(decoded is StepError)
                assertEquals("stopped_by_owner", (decoded as StepError).code)

                // The session itself must still be connected - Stop cancels the step, not the socket.
                withTimeout(5.seconds) {
                    while (client.status.value !is TransportStatus.Connected) yield()
                }
                client.stop()
            }
        }
}
