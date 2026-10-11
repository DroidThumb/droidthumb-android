package com.danielealbano.androidremotecontrolmcp.services.controlbar

import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.apps.AppLabelResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * [ControlBarCoordinator.state] is a `stateIn(scope, SharingStarted.Eagerly, ...)` StateFlow: its
 * combine() runs as a background collector on the injected scope (here, `runTest`'s own
 * `backgroundScope`, so the infinite collector never prevents the test from completing - see
 * [ControlBarCoordinator]'s own doc). Every test below calls [runCurrent] right after a state
 * mutation and before reading `.value`, to let that collector actually process the update -
 * `advanceUntilIdle()` is deliberately NOT used here: with a perpetually-active backgroundScope
 * collector, it does not reliably drive pending work the way a plain `runCurrent()` does (found
 * live while writing these tests - confirmed by a minimal repro before settling on this pattern).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ControlBarCoordinator")
class ControlBarCoordinatorTest {
    private val fixedTarget = ReturnTarget(packageName = "com.app", label = "App")
    private val tapParams: JsonObject = buildJsonObject { put("selector", buildJsonObject { put("text", "x") }) }

    private fun newCoordinator(
        pauseFlow: MutableStateFlow<PauseState> = MutableStateFlow(PauseState()),
        foregroundTarget: ReturnTarget = fixedTarget,
        scope: kotlinx.coroutines.CoroutineScope,
    ): ControlBarCoordinator = newCoordinator(pauseFlow, { foregroundTarget }, scope)

    /** Overload taking a live supplier instead of a fixed target, for tests where the foreground
     *  app changes mid-test (the idle-threshold re-capture tests below). */
    private fun newCoordinator(
        pauseFlow: MutableStateFlow<PauseState> = MutableStateFlow(PauseState()),
        foregroundTarget: () -> ReturnTarget,
        scope: kotlinx.coroutines.CoroutineScope,
    ): ControlBarCoordinator {
        val settingsRepository =
            mockk<SettingsRepository> {
                every { pauseState } returns pauseFlow
                coEvery { pauseUntil(any()) } answers {
                    pauseFlow.value = PauseState(isPaused = true, resumeAtEpochMs = firstArg())
                }
                coEvery { resume() } answers { pauseFlow.value = PauseState() }
            }
        val foregroundAppProvider =
            mockk<CurrentForegroundAppProvider> { every { current() } answers { foregroundTarget() } }
        val appLabelResolver = mockk<AppLabelResolver> { every { labelFor(any()) } answers { firstArg() } }
        return ControlBarCoordinator(settingsRepository, foregroundAppProvider, appLabelResolver, scope)
    }

    @Test
    fun `beginStep from Hidden opens Running and captures the return target`() =
        runTest {
            val coordinator = newCoordinator(scope = backgroundScope)
            coordinator.beginStep("tap", tapParams)
            runCurrent()
            val state = coordinator.state.value as ControlBarState.Running
            assertEquals(fixedTarget, state.returnTarget)
            assertEquals("Tapping \"x\"…", state.caption)
        }

    @Test
    fun `beginStep again within the idle threshold keeps the same return target, updates only the caption`() =
        runTest {
            var currentForeground = fixedTarget
            val coordinator = newCoordinator(foregroundTarget = { currentForeground }, scope = backgroundScope)

            coordinator.beginStep("tap", tapParams, nowMs = 0L)
            currentForeground = ReturnTarget("com.other", "Other") // foreground changed mid-session
            coordinator.beginStep("key", buildJsonObject { put("key", "back") }, nowMs = 1_000L)
            runCurrent()

            val state = coordinator.state.value as ControlBarState.Running
            assertEquals(fixedTarget, state.returnTarget) // still the ORIGINAL target, not re-captured
            assertEquals("Pressing back…", state.caption)
        }

    @Test
    fun `beginStep after the idle threshold re-captures the return target`() =
        runTest {
            var currentForeground = fixedTarget
            val coordinator = newCoordinator(foregroundTarget = { currentForeground }, scope = backgroundScope)

            coordinator.beginStep("tap", tapParams, nowMs = 0L)
            currentForeground = ReturnTarget("com.other", "Other")
            coordinator.beginStep("tap", tapParams, nowMs = ControlBarCoordinator.SESSION_IDLE_THRESHOLD_MS + 1)
            runCurrent()

            val state = coordinator.state.value as ControlBarState.Running
            assertEquals(ReturnTarget("com.other", "Other"), state.returnTarget)
        }

    @Test
    fun `taskDone with a summary ends the session into Ended`() =
        runTest {
            val coordinator = newCoordinator(scope = backgroundScope)
            coordinator.beginStep("tap", tapParams)
            coordinator.taskDone("Set a timer")
            runCurrent()
            val state = coordinator.state.value as ControlBarState.Ended
            assertEquals("Set a timer", state.summary)
            assertEquals(fixedTarget, state.returnTarget)
        }

    @Test
    fun `Ended auto-dismisses to Hidden 5s after taskDone, with no further action needed`() =
        runTest {
            val coordinator = newCoordinator(scope = backgroundScope)
            coordinator.beginStep("tap", tapParams)
            coordinator.taskDone("Set a timer")
            runCurrent()
            assertTrue(coordinator.state.value is ControlBarState.Ended)
            advanceTimeBy(ControlBarCoordinator.ENDED_AUTO_CLOSE_MS + 1)
            runCurrent()
            assertEquals(ControlBarState.Hidden, coordinator.state.value)
        }

    @Test
    fun `a new beginStep before the 5s Ended auto-close never hides the new session`() =
        runTest {
            val coordinator = newCoordinator(scope = backgroundScope)
            coordinator.beginStep("tap", tapParams)
            coordinator.taskDone("Set a timer")
            runCurrent()
            advanceTimeBy(ControlBarCoordinator.ENDED_AUTO_CLOSE_MS - 1_000)
            runCurrent()
            coordinator.beginStep("tap", tapParams) // a brand new session starts before the old Ended would auto-close
            runCurrent()
            advanceTimeBy(2_000) // past the OLD Ended timer's original deadline
            runCurrent()
            assertTrue(coordinator.state.value is ControlBarState.Running)
        }

    @Test
    fun `taskDone called with no session open falls back to the live foreground app as its target`() =
        runTest {
            val coordinator = newCoordinator(scope = backgroundScope)
            coordinator.taskDone("summary with no prior step")
            runCurrent()
            val state = coordinator.state.value as ControlBarState.Ended
            assertEquals(fixedTarget, state.returnTarget)
        }

    @Test
    fun `taskDone with no summary (fallback) ends the session into Ended with null summary`() =
        runTest {
            val coordinator = newCoordinator(scope = backgroundScope)
            coordinator.beginStep("tap", tapParams)
            runCurrent()
            advanceTimeBy(ControlBarCoordinator.SESSION_FALLBACK_END_MS + 1)
            runCurrent()
            val state = coordinator.state.value as ControlBarState.Ended
            assertEquals(null, state.summary)
        }

    @Test
    fun `a new beginStep before 30s cancels the fallback timer - no Ended fires`() =
        runTest {
            val coordinator = newCoordinator(scope = backgroundScope)
            coordinator.beginStep("tap", tapParams)
            runCurrent()
            advanceTimeBy(ControlBarCoordinator.SESSION_FALLBACK_END_MS - 1_000)
            runCurrent()
            coordinator.beginStep("tap", tapParams) // resets the timer
            runCurrent()
            advanceTimeBy(ControlBarCoordinator.SESSION_FALLBACK_END_MS - 1_000)
            runCurrent()
            assertTrue(coordinator.state.value is ControlBarState.Running)
        }

    @Test
    fun `pause applies an indefinite pause WITHOUT invoking any registered cancel hook`() =
        runTest {
            val pauseFlow = MutableStateFlow(PauseState())
            val coordinator = newCoordinator(pauseFlow = pauseFlow, scope = backgroundScope)
            var cancelled = false
            coordinator.registerCurrentStep { cancelled = true }
            coordinator.pause()
            runCurrent()
            assertTrue(!cancelled) // pause() never cancels an in-flight step - that's stop()'s job
            assertTrue(pauseFlow.value.isPaused)
        }

    @Test
    fun `stop invokes the registered cancel hook and ends the session into Ended - NOT Paused`() =
        runTest {
            val pauseFlow = MutableStateFlow(PauseState())
            val coordinator = newCoordinator(pauseFlow = pauseFlow, scope = backgroundScope)
            var cancelled = false
            coordinator.registerCurrentStep { cancelled = true }
            coordinator.beginStep("tap", tapParams)
            coordinator.stop()
            runCurrent()

            assertTrue(cancelled)
            // Round 3 phone-feedback fix: Stop used to also pause, so the bar showed "Paused" and
            // never dismissed - founder: "Stop behaves like Pause... Stop must end the session".
            assertTrue(!pauseFlow.value.isPaused)
            val state = coordinator.state.value as ControlBarState.Ended
            assertEquals(ControlBarCoordinator.STOP_SUMMARY, state.summary)
            assertEquals(fixedTarget, state.returnTarget)
        }

    @Test
    fun `stop's Ended bar auto-dismisses to Hidden 5s later, same as any other Ended`() =
        runTest {
            val coordinator = newCoordinator(scope = backgroundScope)
            coordinator.registerCurrentStep { }
            coordinator.beginStep("tap", tapParams)
            coordinator.stop()
            runCurrent()
            assertTrue(coordinator.state.value is ControlBarState.Ended)

            advanceTimeBy(ControlBarCoordinator.ENDED_AUTO_CLOSE_MS + 1)
            runCurrent()

            assertEquals(ControlBarState.Hidden, coordinator.state.value)
        }

    @Test
    fun `stop with no in-flight step still ends the session - the cancel hook is a safe no-op`() =
        runTest {
            val coordinator = newCoordinator(scope = backgroundScope)
            coordinator.beginStep("tap", tapParams)
            coordinator.stop() // no registerCurrentStep call - nothing to cancel
            runCurrent()

            val state = coordinator.state.value as ControlBarState.Ended
            assertEquals(ControlBarCoordinator.STOP_SUMMARY, state.summary)
        }

    @Test
    fun `resume clears the pause`() =
        runTest {
            val pauseFlow = MutableStateFlow(PauseState(isPaused = true, resumeAtEpochMs = null))
            val coordinator = newCoordinator(pauseFlow = pauseFlow, scope = backgroundScope)
            coordinator.resume()
            runCurrent()
            assertTrue(!pauseFlow.value.isPaused)
        }

    @Test
    fun `state reflects an externally-set pause as Paused`() =
        runTest {
            val pauseFlow = MutableStateFlow(PauseState())
            val coordinator = newCoordinator(pauseFlow = pauseFlow, scope = backgroundScope)
            coordinator.beginStep("tap", tapParams)
            runCurrent()
            pauseFlow.value = PauseState(isPaused = true, resumeAtEpochMs = null) // NOT via stop()
            runCurrent()
            val state = coordinator.state.value as ControlBarState.Paused
            assertEquals(fixedTarget, state.returnTarget)
        }

    @Test
    fun `state reflects an externally-cleared pause by re-surfacing the real Running state, caption included`() =
        runTest {
            val pauseFlow = MutableStateFlow(PauseState(isPaused = true, resumeAtEpochMs = null))
            val coordinator = newCoordinator(pauseFlow = pauseFlow, scope = backgroundScope)
            coordinator.beginStep("tap", tapParams) // opens Running, immediately mapped to Paused by combine()
            runCurrent()
            pauseFlow.value = PauseState() // cleared from elsewhere, not coordinator.resume()
            runCurrent()
            val state = coordinator.state.value as ControlBarState.Running
            // _sessionShown was never actually overwritten to Paused - only combine()'s OUTPUT was -
            // so the real caption from before the pause is still there, not lost or faked.
            assertEquals("Tapping \"x\"…", state.caption)
            assertEquals(fixedTarget, state.returnTarget)
        }

    @Test
    fun `close resets to Hidden and cancels any pending fallback timer`() =
        runTest {
            val coordinator = newCoordinator(scope = backgroundScope)
            coordinator.beginStep("tap", tapParams)
            runCurrent()
            coordinator.close()
            runCurrent()
            assertEquals(ControlBarState.Hidden, coordinator.state.value)
            advanceTimeBy(ControlBarCoordinator.SESSION_FALLBACK_END_MS + 1_000)
            runCurrent()
            assertEquals(ControlBarState.Hidden, coordinator.state.value) // fallback never fired after close()
        }

    // --- Full transition-matrix tests (round 3 phone-feedback, explicitly requested) ---------

    @Test
    @DisplayName("start -> running -> paused -> resumed -> stopped -> done -> dismissed")
    fun `full transition matrix - start, pause, resume, stop, done, dismissed`() =
        runTest {
            val pauseFlow = MutableStateFlow(PauseState())
            val coordinator = newCoordinator(pauseFlow = pauseFlow, scope = backgroundScope)
            var cancelled = false
            coordinator.registerCurrentStep { cancelled = true }

            // start -> running
            coordinator.beginStep("tap", tapParams)
            runCurrent()
            assertTrue(coordinator.state.value is ControlBarState.Running)

            // running -> paused
            coordinator.pause()
            runCurrent()
            assertTrue(coordinator.state.value is ControlBarState.Paused)
            assertTrue(!cancelled) // pause() never touches an in-flight step

            // paused -> resumed (back to the real Running state, nothing lost)
            coordinator.resume()
            runCurrent()
            val resumed = coordinator.state.value as ControlBarState.Running
            assertEquals(fixedTarget, resumed.returnTarget)

            // resumed -> stopped -> done (NOT paused)
            coordinator.stop()
            runCurrent()
            assertTrue(cancelled)
            assertTrue(!pauseFlow.value.isPaused)
            val ended = coordinator.state.value as ControlBarState.Ended
            assertEquals(ControlBarCoordinator.STOP_SUMMARY, ended.summary)

            // done -> dismissed
            advanceTimeBy(ControlBarCoordinator.ENDED_AUTO_CLOSE_MS + 1)
            runCurrent()
            assertEquals(ControlBarState.Hidden, coordinator.state.value)
        }

    @Test
    @DisplayName("start -> 30s timeout -> done -> dismissed")
    fun `full transition matrix - start, 30s timeout, done, dismissed`() =
        runTest {
            val coordinator = newCoordinator(scope = backgroundScope)

            // start -> running
            coordinator.beginStep("tap", tapParams)
            runCurrent()
            assertTrue(coordinator.state.value is ControlBarState.Running)

            // running -> (30s, no further step or task_done) -> done, no summary
            advanceTimeBy(ControlBarCoordinator.SESSION_FALLBACK_END_MS + 1)
            runCurrent()
            val ended = coordinator.state.value as ControlBarState.Ended
            assertEquals(null, ended.summary)

            // done -> dismissed
            advanceTimeBy(ControlBarCoordinator.ENDED_AUTO_CLOSE_MS + 1)
            runCurrent()
            assertEquals(ControlBarState.Hidden, coordinator.state.value)
        }

    // --- Main-thread dispatch guarantee (round 3 phone-feedback regression) -----------------

    @Test
    @DisplayName(
        "setBarTouchable always invokes the registered toggle on Dispatchers.Main, " +
            "regardless of the calling thread",
    )
    fun `setBarTouchable dispatches the toggle on Main even when called from a background thread`() =
        runTest {
            val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "test-main") }
            val backgroundExecutor = Executors.newSingleThreadExecutor { Thread(it, "test-background") }
            val mainDispatcher = mainExecutor.asCoroutineDispatcher()
            val backgroundDispatcher = backgroundExecutor.asCoroutineDispatcher()
            Dispatchers.setMain(mainDispatcher)
            try {
                val coordinator = newCoordinator(scope = backgroundScope)
                var invokingThreadName: String? = null
                coordinator.registerBarTouchToggle { invokingThreadName = Thread.currentThread().name }

                // Exactly what StepDispatcher.tapAtCoordinate does: call setBarTouchable from its
                // own (background) dispatch context, found live on a real OnePlus to crash with
                // "Only the original thread that created a view hierarchy can touch its views"
                // before this fix.
                withContext(backgroundDispatcher) {
                    coordinator.setBarTouchable(false)
                }

                assertEquals("test-main", invokingThreadName)
            } finally {
                Dispatchers.resetMain()
                mainExecutor.shutdown()
                backgroundExecutor.shutdown()
            }
        }
}
