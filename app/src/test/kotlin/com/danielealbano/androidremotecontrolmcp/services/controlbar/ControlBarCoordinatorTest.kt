package com.danielealbano.androidremotecontrolmcp.services.controlbar

import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.apps.AppLabelResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

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
            assertTrue(!state.collapsed)
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
    fun `stop invokes the registered cancel hook and applies an indefinite pause`() =
        runTest {
            val pauseFlow = MutableStateFlow(PauseState())
            val coordinator = newCoordinator(pauseFlow = pauseFlow, scope = backgroundScope)
            var cancelled = false
            coordinator.registerCurrentStep { cancelled = true }
            coordinator.stop()
            runCurrent() // let the launched pauseUntil coroutine run
            assertTrue(cancelled)
            assertTrue(pauseFlow.value.isPaused)
            assertEquals(null, pauseFlow.value.resumeAtEpochMs)
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
}
