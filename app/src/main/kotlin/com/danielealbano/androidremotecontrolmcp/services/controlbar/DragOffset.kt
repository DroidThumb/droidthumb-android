package com.danielealbano.androidremotecontrolmcp.services.controlbar

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Reports raw per-frame drag deltas (px) to [onDrag] - the caller (the overlay manager) owns the
 * actual `WindowManager.LayoutParams` x/y and clamps to screen bounds; this composable holds no
 * window-placement state of its own, so the same modifier works for both the expanded bar and the
 * collapsed dot (plan 71 US2).
 */
fun Modifier.dragToReposition(onDrag: (dxPx: Float, dyPx: Float) -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectDragGestures { change, dragAmount ->
            change.consume()
            onDrag(dragAmount.x, dragAmount.y)
        }
    }
