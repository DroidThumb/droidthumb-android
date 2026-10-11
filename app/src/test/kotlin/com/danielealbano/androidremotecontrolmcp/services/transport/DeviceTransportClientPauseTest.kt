package com.danielealbano.androidremotecontrolmcp.services.transport

import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import com.danielealbano.androidremotecontrolmcp.data.repository.PauseSettings
import com.danielealbano.androidremotecontrolmcp.services.controlbar.ControlBarCoordinator
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Step
import com.danielealbano.androidremotecontrolmcp.wireprotocol.StepDispatcher
import com.danielealbano.androidremotecontrolmcp.wireprotocol.StepError
import com.danielealbano.androidremotecontrolmcp.wireprotocol.StepResult
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/** Pause-gating for [DeviceTransportClientImpl] — split out from [DeviceTransportClientTest] to
 *  keep both under detekt's `LargeClass` threshold; shares [DeviceTransportClientTestBase]'s
 *  fixtures.
 *
 *  Round 4 phone-feedback redesign: a step arriving while paused is now HELD (neither executed nor
 *  failed) until Resume or [PauseSettings.getPauseHoldCapMs] elapses, instead of failing
 *  immediately - see [DeviceTransportClientImpl.runStepCancellable]'s doc comment for why (a paused
 *  device couldn't otherwise let the AI's own turn resume later; once a tool call has failed, there
 *  is no "try again" signal the AI sees on its own). */
class DeviceTransportClientPauseTest : DeviceTransportClientTestBase() {
    private fun stepHandler(
        receivedReply: AtomicReference<String?>,
    ): suspend io.ktor.server.websocket.DefaultWebSocketServerSession.() -> Unit =
        {
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
        }

    @Test
    fun `a step held while paused replies device_paused once the hold cap elapses, without ever dispatching it`() =
        runBlocking {
            val dispatcher = stepDispatcherMock()
            val pauseSettings =
                mockk<PauseSettings> {
                    coEvery { getPauseState() } returns PauseState(isPaused = true, resumeAtEpochMs = null)
                    coEvery { getPauseHoldCapMs() } returns HOLD_CAP_MS
                }
            val receivedReply = AtomicReference<String?>(null)

            withFakeServer(handler = stepHandler(receivedReply)) { port ->
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
    fun `a held step is dispatched normally once the owner resumes within the cap`() =
        runBlocking {
            val dispatcher =
                mockk<StepDispatcher> {
                    coEvery { dispatch(any()) } returns StepResult(stepId = "s1", output = null)
                }
            val paused = AtomicBoolean(true)
            val pauseSettings =
                mockk<PauseSettings> {
                    coEvery { getPauseState() } answers {
                        if (paused.get()) PauseState(isPaused = true, resumeAtEpochMs = null) else PauseState()
                    }
                    coEvery { getPauseHoldCapMs() } returns 10.seconds.inWholeMilliseconds
                }
            val receivedReply = AtomicReference<String?>(null)

            withFakeServer(handler = stepHandler(receivedReply)) { port ->
                val client = newClient(dispatcher = dispatcher, pauseSettings = pauseSettings)
                client.start("127.0.0.1", port, tls = false)
                // Let the hold loop poll at least once while still paused, then simulate Resume.
                delay(POLL_SETTLE_MS)
                paused.set(false)

                withTimeout(15.seconds) {
                    while (receivedReply.get() == null) yield()
                }
                val decoded = wireJson.decodeFromString(WireMessage.serializer(), receivedReply.get()!!)
                assertTrue(decoded is StepResult)
                coVerify(exactly = 1) { dispatcher.dispatch(any()) }
                client.stop()
            }
        }

    @Test
    fun `Stop cancels a held (paused, not yet dispatched) step immediately with stopped_by_owner`() =
        runBlocking {
            val dispatcher = stepDispatcherMock()
            val pauseSettings =
                mockk<PauseSettings> {
                    coEvery { getPauseState() } returns PauseState(isPaused = true, resumeAtEpochMs = null)
                    coEvery { getPauseHoldCapMs() } returns 60.seconds.inWholeMilliseconds
                }
            val cancelSlot = CapturingSlot<() -> Unit>()
            val controlBarCoordinator =
                mockk<ControlBarCoordinator>(relaxed = true) {
                    every { registerCurrentStep(capture(cancelSlot)) } returns Unit
                }
            val receivedReply = AtomicReference<String?>(null)

            withFakeServer(handler = stepHandler(receivedReply)) { port ->
                val client =
                    newClient(
                        dispatcher = dispatcher,
                        pauseSettings = pauseSettings,
                        controlBarCoordinator = controlBarCoordinator,
                    )
                client.start("127.0.0.1", port, tls = false)
                withTimeout(15.seconds) {
                    while (!cancelSlot.isCaptured) yield()
                }
                // Simulate the bar's Stop button, well before the 60s hold cap could ever elapse.
                cancelSlot.captured.invoke()

                withTimeout(5.seconds) {
                    while (receivedReply.get() == null) yield()
                }
                val decoded = wireJson.decodeFromString(WireMessage.serializer(), receivedReply.get()!!)
                assertTrue(decoded is StepError)
                assertEquals("stopped_by_owner", (decoded as StepError).code)
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

            withFakeServer(handler = stepHandler(receivedReply)) { port ->
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

    private companion object {
        /** Short enough to keep this test fast, long enough to be unambiguously distinct from a
         *  step that dispatched immediately. */
        const val HOLD_CAP_MS = 300L
        const val POLL_SETTLE_MS = 700L
    }
}
