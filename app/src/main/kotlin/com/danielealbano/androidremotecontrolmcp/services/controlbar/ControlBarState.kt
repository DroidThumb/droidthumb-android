package com.danielealbano.androidremotecontrolmcp.services.controlbar

/** The foreground app recorded at session start, to return to later. `null` package means the
 *  launcher/home screen was foreground, not a real app - "Back to" then presses Home instead of
 *  launching a package. */
data class ReturnTarget(
    val packageName: String?,
    val label: String,
)

/** The floating control bar's own UI state (plan 71, D-31/D-39) - see [ControlBarCoordinator] for
 *  how it transitions. */
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
