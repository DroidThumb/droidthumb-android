package com.danielealbano.androidremotecontrolmcp.services.controlbar

/**
 * A hard backstop for [ControlBarCoordinator]'s two timers (the 30s no-tool-call fallback end,
 * and the 5s Ended auto-close), scheduled via [android.app.AlarmManager.setAndAllowWhileIdle]
 * rather than relying solely on a plain coroutine `delay()` (round 4 phone-feedback regression:
 * "the ~30s no-tool-call fallback must end the session regardless of task_done" - founder reported
 * it staying stuck indefinitely on a real OnePlus until a manual Stop tap).
 *
 * This build's own [ControlBarCoordinator.armFallbackEndTimer]/[ControlBarCoordinator
 * .armEndedAutoCloseTimer] already schedule a plain coroutine `delay()` on `@ApplicationScope`
 * (`Dispatchers.Default`), which this redroid session re-verified fires correctly end-to-end when
 * nothing resets it - no "a read/heartbeat resets the idle timer" bug was found (every call site
 * was audited; `read_screen`/unrecognized ops never call `beginStep`). A plain in-process
 * coroutine delay is not, however, a *guaranteed* mechanism on real hardware: several OEMs
 * (OnePlus/OxygenOS among the most aggressive) freeze a backgrounded app's CPU scheduling - even a
 * foreground service's - while the device is idle, which stops a running `delay()` from ever
 * completing until the owner interacts with the device again and unfreezes it. Android's
 * `SurfaceFlinger` keeps compositing that frozen process's last-drawn frame regardless, so the bar
 * stays visually on screen exactly as reported, with no further updates - matching every detail of
 * the phone report. [android.app.AlarmManager.setAndAllowWhileIdle] is the OS's own documented
 * mechanism for "must still fire while idle/Doze" that needs no special permission (the exact
 * variant, `setExactAndAllowWhileIdle`, needs the sensitive, not-granted-by-default
 * `SCHEDULE_EXACT_ALARM` permission - overkill for a worst-case backstop that doesn't need
 * precise delivery), so it is kept as an independent backstop
 * alongside the existing coroutine timer (not a replacement - the coroutine path is simpler, far
 * more thoroughly unit-tested already, and fires first in the overwhelmingly common case where the
 * process isn't frozen) rather than one replacing the other. Both paths call back into the exact
 * same [ControlBarCoordinator] methods the coroutine path already used
 * ([ControlBarCoordinator.onFallbackTimerFired]/[ControlBarCoordinator.onEndedAutoCloseFired],
 * themselves calling the already-idempotent [ControlBarCoordinator.taskDone]/
 * [ControlBarCoordinator.close]), so whichever fires first wins and the other is a harmless no-op.
 *
 * [token] (code-review finding, this round): cancelling an `AlarmManager` alarm only suppresses a
 * still *pending* delivery - it cannot recall a broadcast the OS has already dispatched into
 * [ControlBarTimeoutReceiver]'s queue at the exact instant the in-process cancel runs, on the same
 * device-frozen-then-thaws timing this whole backstop targets (the freeze ending is also what lets
 * a brand-new step arrive). A stale alarm from a PREVIOUS arm reaching
 * [ControlBarCoordinator.onFallbackTimerFired]/[ControlBarCoordinator.onEndedAutoCloseFired] after
 * a new session already started (or after the bar already ended with a real summary) would
 * otherwise incorrectly re-fire into that new state. Each `schedule*` call is tagged with a
 * caller-chosen [token] (round-tripped through the `AlarmManager`/`PendingIntent`/`Intent` boundary
 * as an extra), and the matching `on*Fired` callback only acts if [token] still matches the
 * coordinator's current arm - a stale delivery's token never does, by construction, since every arm
 * AND every cancel assigns a fresh one.
 */
interface ControlBarTimeoutScheduler {
    fun scheduleFallbackEnd(
        delayMs: Long,
        token: Long,
    )

    fun cancelFallbackEnd()

    fun scheduleEndedAutoClose(
        delayMs: Long,
        token: Long,
    )

    fun cancelEndedAutoClose()
}
