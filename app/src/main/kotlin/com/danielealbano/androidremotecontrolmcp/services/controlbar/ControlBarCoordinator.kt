package com.danielealbano.androidremotecontrolmcp.services.controlbar

import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.ApplicationScope
import com.danielealbano.androidremotecontrolmcp.services.apps.AppLabelResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

interface CurrentForegroundAppProvider {
    /** `null` package means the launcher is foreground - see [ReturnTarget]. */
    fun current(): ReturnTarget
}

/**
 * The floating control bar's state machine (plan 71, D-31/D-39): session start/end heuristics,
 * caption updates, the pause/resume bridge to [SettingsRepository]'s existing [PauseState], and
 * the single current-step cancellation hook Stop uses.
 *
 * Session-start heuristic (client-side only, no server/protocol involvement): a step is a new
 * session when idle for >= [SESSION_IDLE_THRESHOLD_MS] or no session is currently shown. A 30s
 * timer, reset on every step, auto-ends the session with no summary if neither `task_done` nor a
 * new step arrives in time (plan 71 US1).
 */
@Singleton
@Suppress("TooManyFunctions") // one cohesive state machine; each method is a single, small, named transition
class ControlBarCoordinator
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val foregroundAppProvider: CurrentForegroundAppProvider,
        private val appLabelResolver: AppLabelResolver,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        // Written from the step-dispatch path (IO dispatcher, via StepDispatcher/DeviceTransportClient)
        // and read/written from bar button taps (Main dispatcher) - @Volatile for the same reason
        // DeviceTransportClientImpl marks its own equivalent cross-thread fields (currentOutbox,
        // pendingRegenerate, pendingClaim) @Volatile; plain vars here would let a Stop tap race
        // invisibly against a step just starting or finishing.
        @Volatile
        private var lastStepAtMs = 0L

        @Volatile
        private var fallbackEndJob: Job? = null

        @Volatile
        private var endedAutoCloseJob: Job? = null

        @Volatile
        private var currentStepCancel: (() -> Unit)? = null

        /** Set by [McpAccessibilityService]'s own overlay setup once the bar view exists; null
         *  until then, and [setBarTouchable] is a safe no-op while null. Lets
         *  [com.danielealbano.androidremotecontrolmcp.wireprotocol.StepDispatcher] make the bar
         *  briefly pass-through around a raw-coordinate gesture dispatch (plan 71's "touch
         *  passthrough during a gesture" decision) without this class needing any WindowManager
         *  dependency of its own. */
        @Volatile
        private var barTouchToggle: ((Boolean) -> Unit)? = null

        // Session-open bookkeeping independent of pause: what the bar shows when NOT paused.
        private val sessionShownState = MutableStateFlow<ControlBarState>(ControlBarState.Hidden)

        /** Combines session bookkeeping with the live [SettingsRepository.pauseState] so every
         *  pause source (this bar, the notification, Home's dialog) reflects here immediately.
         *  [sessionShownState] itself is NEVER [ControlBarState.Paused] - Paused is only ever this
         *  combined projection's own output, never written back - so clearing the pause always
         *  naturally re-surfaces [sessionShownState]'s real last state (Running with its real last
         *  caption, Ended, or Hidden) with no special-casing needed: there is nothing to recapture
         *  because nothing was ever lost. */
        val state: StateFlow<ControlBarState> =
            combine(sessionShownState, settingsRepository.pauseState) { shown, pause ->
                if (pause.isEffectivePause(System.currentTimeMillis())) {
                    when (shown) {
                        is ControlBarState.Running -> ControlBarState.Paused(shown.returnTarget)

                        // Pause asserted with no session open (Hidden/Ended) - intentionally a
                        // no-op: there is nothing for the bar to show differently, since pausing
                        // only gates FUTURE steps and none are running or expected now.
                        else -> shown
                    }
                } else {
                    shown
                }
            }.stateIn(scope, SharingStarted.Eagerly, ControlBarState.Hidden)

        /** Called by [com.danielealbano.androidremotecontrolmcp.wireprotocol.StepDispatcher] for
         *  every op except `read_screen`/`task_done`, immediately after that early-return. [nowMs]
         *  defaults to the real wall-clock time; production call sites never override it - it
         *  exists so a test can drive the idle-gap heuristic deterministically without depending on
         *  real elapsed time or the (virtual-time-only) coroutine test dispatcher, neither of which
         *  advances [System.currentTimeMillis]. */
        fun beginStep(
            op: String,
            params: JsonObject,
            nowMs: Long = System.currentTimeMillis(),
        ) {
            val now = nowMs
            val idleGapMs = now - lastStepAtMs
            lastStepAtMs = now
            fallbackEndJob?.cancel()
            endedAutoCloseJob?.cancel() // a new session starting must never be hidden by a stale Ended-dismiss timer

            val wasOpen = sessionShownState.value is ControlBarState.Running
            val returnTarget =
                if (!wasOpen || idleGapMs >= SESSION_IDLE_THRESHOLD_MS) {
                    foregroundAppProvider.current()
                } else {
                    (sessionShownState.value as? ControlBarState.Running)?.returnTarget
                        ?: foregroundAppProvider.current()
                }

            val caption = stepCaption(op, params) { pkg -> appLabelResolver.labelFor(pkg) }
            sessionShownState.value = ControlBarState.Running(caption, returnTarget)
            armFallbackEndTimer()
        }

        // Runs on the injected @ApplicationScope (Dispatchers.Default in production, a test's
        // backgroundScope in tests) while beginStep/stop/resume may be called from IO or Main -
        // safe today since StateFlow.value writes are thread-safe and currentStepCancel/
        // fallbackEndJob/lastStepAtMs are @Volatile, but keep that invariant in mind before adding
        // any new non-atomic state touched from this timer.
        private fun armFallbackEndTimer() {
            fallbackEndJob =
                scope.launch {
                    delay(SESSION_FALLBACK_END_MS)
                    taskDone(summary = null)
                }
        }

        fun taskDone(summary: String?) {
            fallbackEndJob?.cancel()
            val target =
                (sessionShownState.value as? ControlBarState.Running)?.returnTarget
                    ?: foregroundAppProvider.current()
            sessionShownState.value = ControlBarState.Ended(summary, target)
            armEndedAutoCloseTimer()
        }

        /** The Done bar auto-dismisses 5s after it appears if the owner doesn't tap it first
         *  (founder phone-test feedback round 2: "can't be closed and never goes away" - the bar
         *  had no dedicated close affordance before and nothing timed it out). */
        private fun armEndedAutoCloseTimer() {
            endedAutoCloseJob =
                scope.launch {
                    delay(ENDED_AUTO_CLOSE_MS)
                    close()
                }
        }

        /** Registers the currently in-flight step's cancellation hook - only one step is ever in
         *  flight per connection (dispatch is serialized), so a plain overwrite is correct. */
        fun registerCurrentStep(cancel: () -> Unit) {
            currentStepCancel = cancel
        }

        /** Called once by the overlay setup; see [barTouchToggle]. */
        fun registerBarTouchToggle(toggle: (Boolean) -> Unit) {
            barTouchToggle = toggle
        }

        /** Briefly makes the bar pass-through (or restores it) around a raw-coordinate gesture
         *  dispatch - see [barTouchToggle]. A safe no-op before the overlay exists.
         *
         *  `suspend` + [withContext] (round 3 phone-feedback regression): [barTouchToggle] is
         *  [McpAccessibilityService.setBarTouchable], which calls `WindowManager.updateViewLayout`
         *  directly - a real Android `View`/window operation that MUST run on the thread that
         *  created the view hierarchy. The caller here
         *  ([com.danielealbano.androidremotecontrolmcp.wireprotocol.StepDispatcher.tapAtCoordinate])
         *  runs on the step-dispatch path's own background dispatcher, NOT the main thread - found
         *  live on a real OnePlus: every tap (and, transitively, every step sharing that session's
         *  overlay state) failed with "Only the original thread that created a view hierarchy can
         *  touch its views". `withContext(Dispatchers.Main)` both fixes that directly AND
         *  serializes this call against [McpAccessibilityService]'s own main-thread-confined
         *  overlay add/remove collector, eliminating a second, subtler hazard: a background-thread
         *  touchability toggle racing a same-moment Stop/session-end that is concurrently removing
         *  the very view this call reads - the most plausible explanation for "the bar/glow
         *  sometimes never dismisses", also reported this round. This function returning only
         *  after the toggle has actually applied (not fire-and-forget) is required for
         *  correctness: the caller dispatches the real touch immediately after, which must land
         *  only once the window is actually pass-through. */
        suspend fun setBarTouchable(touchable: Boolean) {
            withContext(Dispatchers.Main) { barTouchToggle?.invoke(touchable) }
        }

        fun clearCurrentStep() {
            currentStepCancel = null
        }

        /** The bar's own Pause button: applies the indefinite pause ONLY - gates future steps,
         *  same as the notification/Home-dialog pause, but never cancels one already in flight
         *  (that's [stop]'s job, via the dedicated Stop button). */
        fun pause() {
            scope.launch { settingsRepository.pauseUntil(null) }
        }

        /** Stop: aborts the in-flight step (if any - it replies to the AI with
         *  `stopped_by_owner`/"Stopped by the device owner", wired by
         *  [com.danielealbano.androidremotecontrolmcp.services.transport.DeviceTransportClient]'s
         *  own registration of [registerCurrentStep]'s hook) and ends the session exactly like
         *  [taskDone] does - Done, with a fixed summary making clear the OWNER stopped it, 5s
         *  auto-dismiss like any other Ended bar.
         *
         *  Deliberately does NOT pause (round 3 phone-feedback fix - founder: "Stop behaves like
         *  Pause: it shows 'Paused' and the bar stays... Stop must end the session"). Stop and
         *  [pause] are now fully orthogonal: Stop ends THIS interaction without affecting whether
         *  the AI can start a new one; Pause gates FUTURE steps indefinitely without touching one
         *  already running. Conflating the two was the actual bug - a real Stop tap left the owner
         *  with no visible way to tell whether anything had happened, and no way to dismiss it. */
        fun stop() {
            currentStepCancel?.invoke()
            taskDone(STOP_SUMMARY)
        }

        fun resume() {
            scope.launch { settingsRepository.resume() }
        }

        fun close() {
            fallbackEndJob?.cancel()
            endedAutoCloseJob?.cancel()
            sessionShownState.value = ControlBarState.Hidden
        }

        companion object {
            internal const val SESSION_IDLE_THRESHOLD_MS = 120_000L
            internal const val SESSION_FALLBACK_END_MS = 30_000L

            /** How long the Done bar stays up before auto-dismissing (founder phone-test
             *  feedback round 2). */
            internal const val ENDED_AUTO_CLOSE_MS = 5_000L

            /** [stop]'s own Ended summary - deliberately the same wording as
             *  [com.danielealbano.androidremotecontrolmcp.services.transport.DeviceTransportClient]'s
             *  `STOPPED_BY_OWNER_MESSAGE` (the in-flight step's own error text), not a shared
             *  constant: the two live at different layers (wire-protocol error vs. bar display)
             *  that don't otherwise depend on each other, but the phrase itself should read
             *  identically wherever the owner sees it. */
            internal const val STOP_SUMMARY = "Stopped by the device owner"
        }
    }
