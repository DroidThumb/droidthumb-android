package com.danielealbano.androidremotecontrolmcp.services.controlbar

import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.ApplicationScope
import com.danielealbano.androidremotecontrolmcp.services.apps.AppLabelResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
         *  dispatch - see [barTouchToggle]. A safe no-op before the overlay exists. */
        fun setBarTouchable(touchable: Boolean) {
            barTouchToggle?.invoke(touchable)
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

        /** Stop: cancels the in-flight step (if any), cancels the session's own 30s no-step
         *  fallback-end timer, and applies the indefinite pause - a strict superset of [pause],
         *  for the dedicated Stop button. Resume: clears it. All three delegate to
         *  [SettingsRepository] - the single source of pause truth.
         *
         *  The fallback-end cancellation matters specifically here (not in [pause], which never
         *  touches an in-flight step at all): without it, the ALREADY-ARMED timer from this
         *  session's last [beginStep] would still fire ~30s after Stop regardless of the pause,
         *  flipping the bar from the just-asserted, "unmistakable" Paused state to Ended (then,
         *  5s later, to Hidden) while the device remains paused the whole time - found in code
         *  review, round 2, as a regression of this very round's own "Paused must be unmistakable"
         *  fix. Stop is the right place for this: it's the one action that explicitly ends the
         *  current interaction, so there is nothing left for the fallback to legitimately end. */
        fun stop() {
            fallbackEndJob?.cancel()
            currentStepCancel?.invoke()
            pause()
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
        }
    }
