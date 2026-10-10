<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 71 — Floating control bar (D-31 trust features)

**Mockup (approved before this plan was written)**: https://claude.ai/artifact/Jr6cAKaWqyBCCNfRoG2rbi —
`Active.dc.html` (live session), `Collapsed.dc.html` (docked dot), `Paused.dc.html`,
`Ended.dc.html`. The founder approved the design verbatim in the request that produced this plan;
actions below implement these four states, citing the mockup for exact visual language (dark pill,
light-blue accent `#60C8FF`, edge glow) where a detail isn't spelled out in code.

**Cross-repo**: this plan is droidthumb-android only. `droidthumb-server` needs one small, separate
change (new `task_done` MCP tool catalog entry + design doc D-31 + decisions-log entry) — tracked
as its own small PR, not part of this plan, since `step.schema.json`'s `op` is already an open
string (droidthumb-protocol confirmed unaffected — no schema change needed there at all).

## Why this exists

Founder-requested trust feature: make it visually obvious on the phone itself when an AI client is
driving it, let the owner pause/stop/collapse that control without leaving whatever app is open,
and return cleanly to where they were. None of this existed before; this is new, not a fix.

## Key design decisions (judgment calls made to keep "build without stopping" honest — flagged,
not silently buried)

- **Session-start heuristic**: client-side only, no server/protocol involvement. `ControlBarCoordinator`
  tracks the wall-clock gap since the last dispatched step; a gap over `SESSION_IDLE_THRESHOLD_MS`
  (2 minutes) — or no session currently shown at all — makes the next step a new session: the
  foreground app is captured as the return target at that instant, before the step's own action
  runs.
- **Session-end fallback**: a 30s timer, reset on every step, auto-ends the session (no summary)
  if neither `task_done` nor a new step arrives in time — matches "fallback after ~30s without a
  tool call" literally. The timer is suspended while paused (nothing is happening to time out).
- **Captions update on action ops only**, not `read_screen` — `read_screen` is introspection, not
  a user-visible "doing something" moment, and firing captions on it would flicker between every
  real action and "Reading the screen…".
- **Stop vs Pause**: Stop cancels the in-flight step's own coroutine (see Task 1.2) **and** sets
  the existing indefinite `PauseState` (`SettingsRepository.pauseUntil(null)`) — the same
  mechanism the Home screen's pause dialog and the persistent notification already use, so the bar,
  the notification, and Home's status indicator all stay in sync for free. The bar's own
  Pause/Resume button is this same indefinite pause/resume, not the 1h/until-tomorrow timed
  variants (those stay Home-screen-only, reached from there, not duplicated on the bar).
- **"Back to `<app>`"**: in **Paused**, launches the recorded target without dismissing the bar —
  Resume is still meaningful. In **Ended**, it does the same **and** dismisses the overlay
  afterward (`controlBarCoordinator.close()`), since there is nothing left to resume. No recorded
  target (the launcher itself was foreground at session start) means "Back to" reads "Back to
  Home" and presses the home key (`performGlobalAction(GLOBAL_ACTION_HOME)`) instead of opening a
  package. **As actually implemented**, the launch itself is a direct
  `packageManager.getLaunchIntentForPackage(pkg)` + `startActivity`, not a call through
  `AppManager.openApp` — `AppManager` is designed for the MCP tool-handler layer (its own `fresh`
  task-clearing semantics included), and injecting it into the accessibility service for this one
  simple "resume the existing task" launch would be unnecessary indirection with no behavioral
  gain; the direct Intent call already resumes the app's existing task as-is, matching what
  `AppManager.openApp(pkg, fresh = false)` would do here anyway.
- **Glow**: shown in Running and Paused (dimmer, per the mockup), not in Ended/Hidden — once the
  session is over nothing is acting on the phone any more, so there's nothing left to flag.
- **Touch-passthrough during a gesture**: scoped exactly to where the user's wording applies —
  raw-coordinate `tap(at: …)` (`ActionExecutor.dispatchGesture` territory). Selector-resolved taps
  use `AccessibilityNodeInfo.performAction`, which never touches screen pixels the bar could be
  sitting on, so they're out of scope for this concern entirely.
- **Collapse** only applies to the **Running** bar (it's the one that's wide enough to be worth
  shrinking); Paused/Ended are already the compact single-row layout the mockup shows and aren't
  further collapsible.

## User Story 1 — Session state, captions, and cancellable step dispatch (no UI yet)

**Why:** The overlay (US2) needs something to render; this story is the state machine and the
step-cancellation wiring it renders, built and unit-tested independently of any WindowManager code
(which JVM tests can't exercise at all).

**Acceptance criteria:**
- [ ] A `Step` dispatch starts a new session (fresh return target, `Running` state) exactly when
      idle for ≥ 2 minutes or no session is currently shown; otherwise it only updates the caption.
- [ ] `task_done` ends the session with its optional summary; 30s of silence with no `task_done`
      and no new step ends it with no summary; either way the bar's auto-end timer is cancelled
      once a session is no longer `Running`.
- [ ] Calling `stop()` while a step is in flight cancels **that step's own coroutine only** — the
      transport's WebSocket session (and any other in-flight steps queued behind it — there are
      none, dispatch is already serialized per connection) is unaffected — and the device replies
      with a `StepError` whose `code` is `"stopped_by_owner"`.
- [ ] `stop()` also applies the existing indefinite pause (`SettingsRepository.pauseUntil(null)`);
      `resume()` clears it via the existing `SettingsRepository.resume()`.
- [ ] The exposed `ControlBarState` reflects `PauseState` changes from ANY source (bar, the
      existing notification actions, Home's dialog) within one flow emission — no separate "bar
      thinks paused" bookkeeping that could drift from the real `PauseState`.

### Task 1.1 — State model and caption mapping

**Action 1 — create** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/controlbar/ControlBarState.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.controlbar

/** The foreground app recorded at session start, to return to later. `null` package means the
 *  launcher/home screen was foreground, not a real app - "Back to" then presses Home instead of
 *  launching a package. */
data class ReturnTarget(
    val packageName: String?,
    val label: String,
)

sealed interface ControlBarState {
    data object Hidden : ControlBarState

    data class Running(
        val caption: String,
        val collapsed: Boolean,
        val returnTarget: ReturnTarget,
    ) : ControlBarState

    data class Paused(
        val returnTarget: ReturnTarget,
    ) : ControlBarState

    data class Ended(
        val summary: String?,
        val returnTarget: ReturnTarget,
    ) : ControlBarState
}
```

**Action 2 — create** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/controlbar/StepCaption.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.controlbar

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Human, present-progressive caption for one wire `op`/`params` pair — `read_screen` is
 *  deliberately not called here (see plan 71's "captions update on action ops only"). [appLabel]
 *  resolves a package name to its launcher label, falling back to the package name itself. */
fun stepCaption(
    op: String,
    params: JsonObject,
    appLabel: (String) -> String,
): String =
    when (op) {
        "tap" -> {
            val selector = params["selector"]?.jsonObject
            selector?.let { "Tapping \"${selectorLabel(it)}\"…" } ?: "Tapping the screen…"
        }

        "type_text" -> {
            if (params["clear"]?.jsonPrimitive?.contentOrNull == "true") {
                "Clearing the field…"
            } else {
                val text = params["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                "Typing \"${text.take(MAX_CAPTION_TEXT_LEN)}${if (text.length > MAX_CAPTION_TEXT_LEN) "…" else ""}\"…"
            }
        }

        "scroll_find" -> {
            val selector = params["selector"]?.jsonObject
            "Looking for \"${selector?.let(::selectorLabel) ?: "the target"}\"…"
        }

        "key" ->
            when (params["key"]?.jsonPrimitive?.contentOrNull) {
                "back" -> "Pressing back…"
                "home" -> "Going home…"
                "recents" -> "Viewing recent apps…"
                "dismiss_keyboard" -> "Dismissing the keyboard…"
                else -> "Pressing a key…"
            }

        "launch_app" -> {
            val pkg = params["package"]?.jsonPrimitive?.contentOrNull
            "Opening ${pkg?.let(appLabel) ?: "an app"}…"
        }

        "wait_until" -> {
            val selector = params["selector"]?.jsonObject
            "Waiting for \"${selector?.let(::selectorLabel) ?: "the target"}\"…"
        }

        else -> "Working…"
    }

/** Prefers the most human-readable selector field present: text, then content_desc, then
 *  resource_id, then class_name — matches how a person would describe the element. */
private fun selectorLabel(selector: JsonObject): String =
    selector["text"]?.jsonPrimitive?.contentOrNull
        ?: selector["content_desc"]?.jsonPrimitive?.contentOrNull
        ?: selector["resource_id"]?.jsonPrimitive?.contentOrNull
        ?: selector["class_name"]?.jsonPrimitive?.contentOrNull
        ?: "the element"

private const val MAX_CAPTION_TEXT_LEN = 24
```

**Definition of Done — Task 1.1**:
- [ ] `ControlBarStateTest`/`StepCaptionTest` written (Task 1.4's table covers both).

### Task 1.2 — `ControlBarCoordinator`: session lifecycle, pause bridge, cancellable dispatch

**Action 1 — create** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/controlbar/ControlBarCoordinator.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.controlbar

import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.apps.AppLabelResolver
import com.danielealbano.androidremotecontrolmcp.wireprotocol.StepError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

@Singleton
class ControlBarCoordinator
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val foregroundAppProvider: CurrentForegroundAppProvider,
        private val appLabelResolver: AppLabelResolver,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        private var currentStepCancel: (() -> Unit)? = null

        // Session-open bookkeeping independent of pause: what the bar shows when NOT paused.
        private val _sessionShown =
            MutableStateFlow<ControlBarState>(ControlBarState.Hidden)

        /** Combines session bookkeeping with the live [SettingsRepository.pauseState] so every
         *  pause source (this bar, the notification, Home's dialog) reflects here immediately -
         *  see plan 71 US1's "no separate bar-thinks-paused bookkeeping" acceptance criterion.
         *  [_sessionShown] itself is NEVER [ControlBarState.Paused] - Paused is only ever this
         *  combined projection's own OUTPUT, never written back into [_sessionShown] - so clearing
         *  the pause always naturally re-surfaces [_sessionShown]'s real last state (Running with
         *  its real last caption, Ended, or Hidden), with no special-casing needed: there is
         *  nothing to recapture because nothing was ever lost. (An earlier draft of this method
         *  tried to special-case "was showing Paused" by checking `shown is Paused` - that branch
         *  can never be true, since `shown` IS `_sessionShown`'s own value, which this function
         *  never assigns `Paused` to; found via the test below actually failing, not by
         *  inspection.) */
        val state: StateFlow<ControlBarState> =
            combine(_sessionShown, settingsRepository.pauseState) { shown, pause ->
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

        /** Called at the top of [com.danielealbano.androidremotecontrolmcp.wireprotocol.StepDispatcher.dispatchOp]
         *  for every op except `read_screen`. Returns a `cancel()` the caller must invoke if the
         *  step throws/returns, paired with [endStep], to release [currentStepCancel]. */
        fun beginStep(
            op: String,
            params: JsonObject,
        ) {
            val now = System.currentTimeMillis()
            val idleGapMs = now - lastStepAtMs
            lastStepAtMs = now
            fallbackEndJob?.cancel()

            val wasOpen = _sessionShown.value is ControlBarState.Running
            val returnTarget =
                if (!wasOpen || idleGapMs >= SESSION_IDLE_THRESHOLD_MS) {
                    foregroundAppProvider.current()
                } else {
                    (_sessionShown.value as? ControlBarState.Running)?.returnTarget
                        ?: foregroundAppProvider.current()
                }

            val caption = stepCaption(op, params) { pkg -> appLabelResolver.labelFor(pkg) }
            _sessionShown.value = ControlBarState.Running(caption, collapsed = false, returnTarget)
            armFallbackEndTimer()
        }

        // Runs on Dispatchers.Default (this coordinator's own scope) while beginStep/stop/resume
        // may be called from IO or Main - safe today since StateFlow.value writes are thread-safe
        // and currentStepCancel/fallbackEndJob/lastStepAtMs are @Volatile, but keep that invariant
        // in mind before adding any new non-atomic state touched from this timer.
        private fun armFallbackEndTimer() {
            fallbackEndJob =
                scope.launch {
                    kotlinx.coroutines.delay(SESSION_FALLBACK_END_MS)
                    taskDone(summary = null)
                }
        }

        fun taskDone(summary: String?) {
            fallbackEndJob?.cancel()
            val target =
                (_sessionShown.value as? ControlBarState.Running)?.returnTarget
                    ?: foregroundAppProvider.current()
            _sessionShown.value = ControlBarState.Ended(summary, target)
        }

        /** Registers the currently in-flight step's cancellation hook — see [StepDispatcher]'s
         *  `runStepCancellable` (Action 2) for the caller. Only one step is ever in flight per
         *  connection (dispatch is serialized), so a plain overwrite is correct. */
        fun registerCurrentStep(cancel: () -> Unit) {
            currentStepCancel = cancel
        }

        fun clearCurrentStep() {
            currentStepCancel = null
        }

        fun collapseToggle(collapsed: Boolean) {
            (_sessionShown.value as? ControlBarState.Running)?.let {
                _sessionShown.value = it.copy(collapsed = collapsed)
            }
        }

        /** The bar's own Pause button: applies the indefinite pause ONLY - gates future steps,
         *  same as the notification/Home-dialog pause, but never cancels one already in flight
         *  (that's [stop]'s job, via the dedicated Stop button). Found needed only once US2's
         *  Pause button was actually wired up - [stop] alone is wrong for it, since Stop's own
         *  cancel-in-flight-step behavior must stay exclusive to the Stop button. */
        fun pause() {
            scope.launch { settingsRepository.pauseUntil(null) }
        }

        /** Stop: cancels the in-flight step (if any) AND applies the indefinite pause - a strict
         *  superset of [pause], for the dedicated Stop button. Resume: clears it. All three
         *  delegate to [SettingsRepository] - the single source of pause truth. */
        fun stop() {
            currentStepCancel?.invoke()
            pause()
        }

        fun resume() {
            scope.launch { settingsRepository.resume() }
        }

        fun close() {
            fallbackEndJob?.cancel()
            _sessionShown.value = ControlBarState.Hidden
        }

        companion object {
            internal const val SESSION_IDLE_THRESHOLD_MS = 120_000L
            internal const val SESSION_FALLBACK_END_MS = 30_000L
        }
    }
```

**Action 2 — modify** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/transport/DeviceTransportClient.kt`:
inject `ControlBarCoordinator` into `DeviceTransportClientImpl` (its 7th constructor param — add
`@Suppress("LongParameterList")` to the constructor now, matching `StepDispatcher`'s own existing
suppression for the same reason; this repo runs detekt's stock `LongParameterList` threshold, no
`detekt.yml` override exists), and replace the `is Step ->` branch
of `handleIncomingMessage` with a cancellable child coroutine so `stop()` can abort just this one
step without tearing down the WebSocket session:

```kotlin
is Step -> {
    val reply = runStepCancellable(message)
    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), reply)))
}
```

```kotlin
/** Runs [message] in a CHILD coroutine of this session (launched from the
 *  [DefaultClientWebSocketSession] receiver, which is itself a [CoroutineScope]) so
 *  [ControlBarCoordinator.stop] can cancel JUST this step - cancelling `this@runSession`'s own
 *  job would tear down the whole connection, which Stop must never do. */
private suspend fun DefaultClientWebSocketSession.runStepCancellable(step: Step): WireMessage {
    if (pauseSettings.getPauseState().isEffectivePause(System.currentTimeMillis())) {
        return StepError(step.stepId, code = "device_paused", message = PAUSED_STEP_MESSAGE)
    }
    val deferred = CompletableDeferred<WireMessage>()
    val job =
        launch {
            deferred.complete(stepDispatcher.dispatch(step))
        }
    controlBarCoordinator.registerCurrentStep {
        job.cancel()
        deferred.complete(StepError(step.stepId, code = "stopped_by_owner", message = STOPPED_BY_OWNER_MESSAGE))
    }
    return try {
        deferred.await()
    } finally {
        controlBarCoordinator.clearCurrentStep()
    }
}
```

Add `STOPPED_BY_OWNER_MESSAGE = "Stopped by the device owner"` alongside the existing
`PAUSED_STEP_MESSAGE` constant.

**Action 3 — modify** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/wireprotocol/StepDispatcher.kt`:
inject `ControlBarCoordinator`; in `dispatchOp`, call `controlBarCoordinator.beginStep(op, params)`
immediately after the existing `if (op == "read_screen") return readScreen(params)` early-return —
i.e. the first line of the function once that check has passed, before `executeAction` runs — so
every op except `read_screen` triggers it, exactly once per step. Add a `"task_done"` case to
`executeAction`:

```kotlin
"task_done" -> {
    taskDone(params)
    null
}
```

```kotlin
private fun taskDone(params: JsonObject) {
    val summary = params["summary"]?.jsonPrimitive?.contentOrNull
    controlBarCoordinator.taskDone(summary)
}
```

**Action 4 — create** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/apps/AppLabelResolver.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.apps

interface AppLabelResolver {
    /** The package's launcher label, or the package name itself if it can't be resolved (app
     *  uninstalled between the step naming it and this lookup, etc. - never throws). */
    fun labelFor(packageName: String): String
}
```

**Action 5 — create** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/apps/AppLabelResolverImpl.kt`
(`@Inject constructor(@ApplicationContext context: Context)`, wraps
`context.packageManager.getApplicationLabel(getApplicationInfo(packageName, 0)).toString()` in a
try/catch returning `packageName` on any failure — this is the same category of real,
synchronous PackageManager call this codebase already wraps defensively elsewhere, e.g.
`DeviceIdentityKeyStoreImpl`'s Keystore calls per the PR #9 phone-test infinite-spinner fix).

**Action 6 — create** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/controlbar/CurrentForegroundAppProviderImpl.kt`,
implementing `CurrentForegroundAppProvider` via `AccessibilityServiceProvider.getCurrentPackageName()`:
`null`/launcher package (compare against `Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)`
resolved via `PackageManager.resolveActivity` to get the real launcher package on this device,
cached once) → `ReturnTarget(null, "Home")`; otherwise `ReturnTarget(pkg, appLabelResolver.labelFor(pkg))`.

**Action 7 — modify** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt`:
`@Binds` for `AppLabelResolver` → `AppLabelResolverImpl`, `CurrentForegroundAppProvider` →
`CurrentForegroundAppProviderImpl`.

### Task 1.3 — Hilt wiring for the two modified classes' new constructor params

**Definition of Done**: `DeviceTransportClientImpl` and `StepDispatcher` both compile with the new
`ControlBarCoordinator` constructor parameter; no other call site of either class exists outside
Hilt injection and their own tests (confirm via `grep -rn "DeviceTransportClientImpl(\|StepDispatcher("`).

### Task 1.4 — Tests

**File**: `app/src/test/kotlin/.../services/controlbar/StepCaptionTest.kt`

| Test | Verifies |
|------|----------|
| `tap with a text selector captions "Tapping "<text>"…"` | selector label preference order |
| `tap with content_desc only falls back to content_desc` | fallback chain |
| `tap with at-only (no selector) captions "Tapping the screen…"` | coordinate-tap caption |
| `type_text captions the typed text, truncated past 24 chars` | truncation boundary |
| `type_text with clear:true captions "Clearing the field…"` | clear-branch caption |
| `key maps each of back/home/recents/dismiss_keyboard to its own caption` | parameterized, 4 cases |
| `launch_app captions "Opening <resolved label>…"` | appLabel callback is used, not the raw package |
| `wait_until and scroll_find caption around the selector label` | shared `selectorLabel` helper |
| `an unknown op captions "Working…"` | default-branch safety net |

**File**: `app/src/test/kotlin/.../services/controlbar/ControlBarCoordinatorTest.kt`

**Setup**: fake `SettingsRepository` (reuse/extend the existing test fake with a real `pauseState`
`MutableStateFlow`), fake `CurrentForegroundAppProvider` returning a fixed `ReturnTarget`, fake
`AppLabelResolver` returning the input package unchanged. `runTest` + `advanceTimeBy`/`advanceUntilIdle`
for the 30s fallback and 2-minute idle-gap timing.

| Test | Verifies |
|------|----------|
| `beginStep from Hidden opens Running and captures the return target` | first-step session start |
| `beginStep again within the idle threshold keeps the same return target, updates only the caption` | "ongoing session" branch |
| `beginStep after the idle threshold re-captures the return target` | idle-gap re-open |
| `taskDone with a summary ends the session into Ended(summary, target)` | explicit end |
| `taskDone with no summary (fallback) ends the session into Ended(null, target)` | 30s auto-end, **setup**: `beginStep` once, `advanceTimeBy(30_000)`, no further call |
| `taskDone called with no session open falls back to the live foreground app as its target` | the `?: foregroundAppProvider.current()` branch in `taskDone` — **setup**: call `taskDone(summary)` with no prior `beginStep` |
| `a new beginStep before 30s cancels the fallback timer - no Ended fires` | timer is reset per step, not fire-and-forget |
| `pause() applies an indefinite pause WITHOUT invoking any registered cancel hook` | `pause` never touches `currentStepCancel` - only `stop` does |
| `stop() invokes the registered cancel hook and applies an indefinite pause` | `registerCurrentStep`/`stop` contract |
| `resume() clears the pause` | symmetric to stop |
| `state reflects an externally-set pause (not via stop()) as Paused` | the `combine()` bridge - set `pauseState` directly, not through `stop()` |
| `state reflects an externally-cleared pause by re-surfacing the real underlying Running state, caption included` | clearing the pause needs no special-casing - the real last caption was never lost |
| `close() resets to Hidden and cancels any pending fallback timer` | `close()` contract |

**File**: `app/src/test/kotlin/.../services/transport/DeviceTransportClientImplTest.kt` (existing
file — extend)

| Test | Verifies |
|------|----------|
| `a Step dispatched while registerCurrentStep's cancel hook fires replies with StepError code=stopped_by_owner, and the WebSocket session itself stays connected` | the core "cancel the step, not the session" contract — **setup**: a `stepDispatcher.dispatch` stub that suspends on a `CompletableDeferred` never completed by the test, drive `controlBarCoordinator.stop()` from another launched coroutine, assert the reply and that `status` is still `Connected` |

**File**: `app/src/test/kotlin/.../wireprotocol/StepDispatcherTest.kt` (existing file — extend)

| Test | Verifies |
|------|----------|
| `task_done with a summary calls controlBarCoordinator.taskDone(summary)` | wiring, via `verify` on a mocked coordinator |
| `task_done with no params calls controlBarCoordinator.taskDone(null)` | optional-summary path |
| `read_screen never calls controlBarCoordinator.beginStep` | the "captions skip read_screen" rule, via `verify(exactly = 0)` |
| `every other existing op calls controlBarCoordinator.beginStep(op, params) before executing` | regression guard across tap/type_text/scroll_find/key/launch_app/wait_until |

### Definition of Done — User Story 1
- [ ] All tests above pass; no WindowManager/Compose code introduced yet (US2's job).

## User Story 2 — The overlay itself: Compose-in-WindowManager, drag, collapse/expand

**Why:** US1 has no UI. This story makes the state machine visible and interactive, per the
approved mockup.

**Acceptance criteria:**
- [ ] The overlay (bar + edge glow) is added via `WindowManager`/`TYPE_ACCESSIBILITY_OVERLAY` from
      `McpAccessibilityService` — no `SYSTEM_ALERT_WINDOW`/"display over other apps" permission
      requested or declared.
- [ ] The bar is shown exactly when `ControlBarCoordinator.state` is not `Hidden`; removed when it
      is.
- [ ] The glow is shown in `Running`/`Paused` only, dimmer in `Paused`, matching the mockup.
- [ ] The bar (expanded and collapsed-dot forms) is draggable along the screen edge/within bounds;
      position is not persisted across app restarts (session-scoped UI chrome, not a setting).
- [ ] Collapse shrinks the `Running` bar to a dot; tapping the dot expands it back.
- [ ] Stop/Pause/Resume/Back-to/Close buttons call the matching `ControlBarCoordinator` method.

### Task 2.1 — A manually-driven `LifecycleOwner`/`ViewModelStoreOwner`/`SavedStateRegistryOwner` for the overlay's `ComposeView`

**Why:** an `AccessibilityService`-added `ComposeView` has no Activity/Fragment to source these
from — Compose requires all three to be set on the view's `ViewTree*Owner` before it can compose
anything.

**Action 1 — create** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/controlbar/OverlayLifecycleOwner.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.controlbar

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/** The standard "Compose view added directly via WindowManager, outside any Activity/Fragment"
 *  lifecycle shim - RESUMED for as long as the overlay is attached, DESTROYED on [dismiss]. No
 *  saved-state restoration is needed (this view is recreated fresh every time the overlay shows),
 *  so [SavedStateRegistryController] is driven with an empty bundle. */
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun onAttach() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDetach() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}
```

### Task 2.2 — The overlay content composable

**Action 1 — create** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/controlbar/ControlBarOverlay.kt`
— one composable per mockup state (`RunningBar`, `CollapsedDot`, `PausedBar`, `EndedBar`), a
top-level `ControlBarOverlay(state, callbacks)` dispatcher, and `EdgeGlow(dim: Boolean)`. Visual
language from the mockup: dark pill `Color(0xFF1C1C20)` at ~92% alpha, accent `Color(0xFF60C8FF)`,
20sp-equivalent 13sp caption text, 999dp corner radius, `Modifier.size(48.dp)` minimum touch
targets per this project's own accessibility baseline (PROJECT.md "UI Design Principles"). Full
button semantics (`contentDescription` on every icon button — Stop, Pause/Resume, Collapse,
Record, Close — screen-reader support is a stated project baseline, not optional here just because
this is an overlay).

**Action 2 — create** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/controlbar/DragOffset.kt`:
a tiny `pointerInput(Unit) { detectDragGestures { ... } }` modifier extension used by both the bar
and the dot, reporting delta to a caller-supplied `(dxPx: Float, dyPx: Float) -> Unit` — the
overlay manager (Task 2.3) owns the actual `WindowManager.LayoutParams` x/y and clamps to screen
bounds; the composable itself holds no window-placement state.

### Task 2.3 — `McpAccessibilityService` hosts the overlay

**Action 1 — modify** `McpAccessibilityService.kt`: in `onServiceConnected()`, after the existing
setup, create the two overlay views (glow window, bar window — glow added first so it paints
behind the bar), each a `ComposeView` with its own `OverlayLifecycleOwner` set via
`view.setViewTreeLifecycleOwner(owner)` / `setViewTreeViewModelStoreOwner(owner)` /
`setViewTreeSavedStateRegistryOwner(owner)`, added via
`getSystemService(WindowManager::class.java).addView(view, layoutParams)` with
`type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`, `format = PixelFormat.TRANSLUCENT`;
the glow window additionally sets `FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE` (always pass-through,
decorative only — see plan 71's "Edge glow" decision); the bar window sets `FLAG_NOT_FOCUSABLE`
only (touchable, but never steals keyboard/IME focus from the app underneath).

**As actually implemented** (simpler than first drafted above, found once this was actually
wired up): each `ComposeView`'s `setContent {}` is called exactly ONCE, with a composable that
reads `controlBarCoordinator.state.collectAsState()` itself — plain Compose recomposition then
reacts to every emission with no manual per-emission `setContent` calls and no explicit
`View.GONE`/`VISIBLE` toggling for `Hidden` vs. everything else: the `Hidden`/unhandled branches
in `ControlBarOverlay`/the glow's own `when` render nothing, which Compose already sizes as a
zero-area, non-touchable region on its own. `setOverlayHidden` (Task 3.2) is a SEPARATE,
additional mechanism — it force-hides the whole view via `View.GONE` regardless of state, only for
the duration of a screenshot capture; it doesn't replace this. Remove both views and call
`onDetach()` on both lifecycle owners in `onDestroy()`.

**Action 2 — modify** `McpAccessibilityService.kt`: add `fun setBarTouchable(touchable: Boolean)`
toggling the bar window's `FLAG_NOT_TOUCHABLE` bit via `windowManager.updateViewLayout` — called
from Task 3's touch-passthrough wiring.

### Task 2.4 — Drag persists the window position while dragging

**Action 1 — modify** Task 2.3's bar `ComposeView` setup: the drag callback from Task 2.2 updates
the bar `WindowManager.LayoutParams.x`/`.y` and calls `windowManager.updateViewLayout(barView,
params)` using `getScreenInfo()`. **Clamp, as actually implemented:** the bar's `gravity` is
`BOTTOM|CENTER_HORIZONTAL`, so `x` is an offset from horizontal center, not a left-edge coordinate
— the symmetric bound is `[-(screenWidth - viewWidth)/2, (screenWidth - viewWidth)/2]`, not a
plain `[0, screenWidth - viewWidth]` (a first draft used the latter and let roughly half the bar
push off-screen at either drag extreme — caught in review, not by inspection). `y` (an offset from
the bottom edge) stays `[0, screenHeight - viewHeight]` as originally drafted.

### Task 2.5 — Tests

No JVM-testable surface in this story beyond what US1 already covers (`OverlayLifecycleOwner`,
`WindowManager` additions, and Compose rendering all require a real Android window/device). Manual
verification only — see the plan's final Verification section.

### Definition of Done — User Story 2
- [ ] Builds; `OverlayLifecycleOwner`'s three overrides compile against the real androidx
      lifecycle/savedstate/viewmodel artifacts already on this project's classpath (confirm via
      `./gradlew :app:compileDebugKotlin` — no new dependency should be needed, these are all
      already-used libraries per `docs/PROJECT.md`'s tech stack).

## User Story 3 — Exclude the overlay from the AI's own view of the screen

**Why:** the AI must never see or be confused by DroidThumb's own control surface — it's for the
device owner only.

**Acceptance criteria:**
- [ ] `read_screen`/`find_nodes`/any tree read never includes a window belonging to this app's own
      package.
- [ ] A screenshot taken while the bar/glow is showing never contains them: the real target app's
      window only on API 34+, and the overlay hidden-then-restored around capture on API 33.
- [ ] A genuine third-party overlay (e.g. a OEM translate/assistant bar) is unaffected by either
      change — still in the tree, still in screenshots, per the existing behavior.

### Task 3.1 — Tree exclusion

**Action 1 — modify** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/NodeActionTools.kt`'s
`getFreshWindowsLocked`, inside the `for (window in accessibilityWindows)` loop: skip (`continue`)
any window whose `rootNode.packageName` equals `BuildConfig.APPLICATION_ID`, **before** calling
`rootNode.refresh()`/`treeParser.parseTree` for it (no point parsing a tree that's about to be
discarded). Add the import for `com.danielealbano.androidremotecontrolmcp.BuildConfig`.

### Task 3.2 — Per-window screenshot capture

**Action 1 — modify** `McpAccessibilityService.kt`: add

```kotlin
/** API 34+ only - captures just [windowId]'s content, never this app's own overlay windows
 *  (different windows entirely). Falls back to null on any failure so the caller can fall back to
 *  whole-display capture rather than fail the screenshot outright. */
@SuppressLint("NewApi")
suspend fun takeScreenshotOfWindowBitmap(
    windowId: Int,
    timeoutMs: Long = SCREENSHOT_TIMEOUT_MS,
): Bitmap? =
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val executor = Executor { it.run() }
            val callback = /* same onSuccess/onFailure shape as takeScreenshotBitmap */
            try {
                takeScreenshotOfWindow(windowId, executor, callback)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
```

(Full body mirrors `takeScreenshotBitmap`'s existing callback — share the callback-building logic
in a private helper both call, rather than duplicating the `TakeScreenshotCallback` object.)

**Action 2 — modify** `ScreenCaptureProvider.kt`/`ScreenCaptureProviderImpl.kt`: add
`captureScreenshotBitmap(windowId: Int?, maxWidth, maxHeight)` overload (or a parameter default of
`null` on the existing one — prefer extending the existing signature, since `GetScreenStateHandler`
is the only caller and already has a natural `windowId` available from `result.windows`'
focused-`APPLICATION`-type entry). Behavior: `windowId != null && Build.VERSION.SDK_INT >=
Build.VERSION_CODES.UPSIDE_DOWN_CAKE` → `service.takeScreenshotOfWindowBitmap(windowId)`, falling
back to the existing whole-display `takeScreenshotBitmap()` if that returns `null` (resolution
failed) or the condition isn't met. On API 33 specifically (condition false, `windowId` present),
wrap the existing whole-display call with
`accessibilityServiceProvider.getContext()`-cast-to-`McpAccessibilityService`'s new
`setOverlayHidden(true)` / `setOverlayHidden(false)` (Task 2.3's views' `View.GONE`/`VISIBLE`,
exposed as a small public method) immediately before/after the capture, so the whole-display image
never contains them.

**Action 3 — modify** `GetScreenStateHandler.kt`'s `buildScreenshotResult`: pass the focused
`APPLICATION`-type window's id from `result.windows` (same lookup `activityName` resolution
already does — reuse, don't re-derive) into `captureScreenshotBitmap`.

### Task 3.3 — Tests

**File**: `app/src/test/kotlin/.../mcp/tools/NodeActionToolsTest.kt` (existing file — extend)

| Test | Verifies |
|------|----------|
| `getFreshWindows skips a window whose package is this app's own BuildConfig.APPLICATION_ID` | Task 3.1's filter, with ≥2 fake windows (one ours, one a real app) |
| `getFreshWindows keeps a third-party overlay window (different package, TYPE_ACCESSIBILITY_OVERLAY)` | regression guard — only OUR package is excluded, never overlays generally |

**File**: `app/src/test/kotlin/.../services/screencapture/ScreenCaptureProviderImplTest.kt`
(existing file — extend)

| Test | Verifies |
|------|----------|
| `given a windowId on API 34, captureScreenshotBitmap calls takeScreenshotOfWindowBitmap, not the whole-display capture` | API-gated branch, **setup**: fake `ApiLevelProvider` returning 34 |
| `given a windowId on API 33, captureScreenshotBitmap hides the overlay, captures whole-display, then restores it` | the hide/show fallback path, **setup**: fake `ApiLevelProvider` returning 33, verify call order with MockK's `verifyOrder` |
| `a null windowId always uses whole-display capture regardless of API level` | no-window-known fallback (e.g. degraded multi-window mode) |
| `takeScreenshotOfWindowBitmap returning null falls back to whole-display capture` | resolution-failure fallback |

### Definition of Done — User Story 3
- [ ] All tests above pass.

## User Story 4 — Third-party overlay labeling in the tree, tool description note

**Why:** the founder's own spec: other apps' overlays (OnePlus/Oppo `com.coloros.translate` etc.)
stay in the tree, but are clearly labeled so the AI doesn't mistake them for part of the target
app, and knows to ignore them unless one blocks the real target.

### Task 4.1 — Label third-party `TYPE_ACCESSIBILITY_OVERLAY` windows in the compact tree

**Action 1 — modify** `CompactTreeFormatter.kt`'s window-header line formatting (wherever
`WindowData.windowType`/`packageName` currently render per window — same spot that already prints
e.g. `note:DEGRADED`): when `windowType == "ACCESSIBILITY_OVERLAY"` and the window's package is
**not** this app's own (already excluded by US3's filter, so by construction any overlay reaching
here is third-party), prefix its header with `note:third-party overlay — ignore unless it blocks
the target`.

### Task 4.2 — Tool description note

**Action 1 — modify** `droidthumb-server/src/mcp/tool-catalog.ts`'s `read_screen` entry
description (separate small addition to that repo's own task-done PR, not this plan — flagged here
for traceability only, not an action of this plan): append a sentence matching Task 4.1's wording
so the model is told about this convention from the tool description itself, not just discovered
ad hoc in a tree dump.

### Task 4.3 — Tests

**File**: `app/src/test/kotlin/.../services/accessibility/CompactTreeFormatterTest.kt` (existing
file — extend)

| Test | Verifies |
|------|----------|
| `a third-party ACCESSIBILITY_OVERLAY window is prefixed with the ignore-unless-blocking note` | Task 4.1's label |
| `an APPLICATION window is never labeled as a third-party overlay` | the label is type-gated, not applied broadly |

### Definition of Done — User Story 4
- [ ] Tests pass.

## Final steps (after every user story above is implemented)

1. `make lint` / `./gradlew ktlintCheck detekt` — zero findings.
2. `./gradlew :app:testDebugUnitTest` — all green, including every table above.
3. `./gradlew assembleDebug assembleRelease` — clean, matches CI's `build-release` job.
4. Manual verification on the persistent redroid debug device (`docs/debug-device.md`): drive a
   real step sequence through the fake-relay/staging harness, confirm the bar appears, captions
   update, collapse/expand, Stop aborts and pauses, Resume continues, `task_done` shows Done +
   summary, the 30s fallback fires with no summary when `task_done` is never called, "Back to
   `<app>`" and the Home-only case both work, the overlay never appears in `read_screen`/a
   screenshot.
5. Spawn `plan-reviewer` (or self-review, disclosed) on this plan before starting — not yet done,
   see below. Spawn `code-reviewer` in plan-compliance mode once all four user stories are
   implemented and gates above pass; address every finding; re-run until clean.
6. Update `docs/ARCHITECTURE.md`'s Components table (new `McpAccessibilityService`-hosted overlay)
   and `docs/PROJECT.md` if the new `services/controlbar/` package warrants a folder-structure
   line — both already somewhat stale from before the transport/account work landed (noted, not
   this plan's job to fully correct) but this plan's own new files should at least appear.

## What founder testing on a real OnePlus should specifically check (beyond redroid)

- The edge glow and bar render correctly alongside the OnePlus's own rounded-corner display cutout
  and gesture-nav bar (redroid's emulated screen won't reveal real-device chrome overlaps).
- Dragging the bar near the gesture-navigation strip at the bottom doesn't fight the system's own
  edge-swipe gestures.
- A screenshot taken mid-session, compared by eye, genuinely never shows the bar/glow — this is
  the one piece redroid's API level (confirm which) may not exercise identically to a real OnePlus
  on a different Android version.
- Per-brand background battery guidance (already shipped, PR #9/#10) doesn't visually collide with
  the new overlay if both happen to be showing at once (unlikely overlap, but cheap to eyeball).
