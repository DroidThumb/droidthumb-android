@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components.controlbar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.services.controlbar.ControlBarState
import com.danielealbano.androidremotecontrolmcp.services.controlbar.dragToReposition

private val BarBackground = Color(0xFF1C1C20)
private val Accent = Color(0xFF60C8FF)
private val CaptionColor = Color(0xFFD7D7DA)
private val DestructiveColor = Color(0xFFFF6B6B)
private val DoneColor = Color(0xFF9BE39B)
private val IconButtonBackground = Color(0xFF2A2A2E)
private val DisabledIconColor = Color(0xFF5A5A5E)

/** Callbacks the floating control bar's buttons invoke — all delegate straight to
 *  [com.danielealbano.androidremotecontrolmcp.services.controlbar.ControlBarCoordinator]'s own
 *  methods; this composable holds no business logic of its own (plan 71 US2). */
data class ControlBarCallbacks(
    val onCollapseToggle: (collapsed: Boolean) -> Unit,
    val onStop: () -> Unit,
    val onPauseOrResume: () -> Unit,
    val onBackToApp: () -> Unit,
    val onClose: () -> Unit,
    val onDrag: (dxPx: Float, dyPx: Float) -> Unit,
)

/** Thin, fully pass-through edge glow shown while an AI session is live (plan 71, D-39) - the
 *  caller (McpAccessibilityService) never adds this composable's window with touchable flags, so
 *  it never intercepts touches regardless of what's drawn here. [dim] matches the mockup's dimmer
 *  glow while paused. */
@Composable
fun EdgeGlow(dim: Boolean) {
    val alpha = if (dim) 0.3f else 0.55f
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .border(BorderStroke(3.dp, Accent.copy(alpha = alpha))),
    )
}

/** Dispatches to the right composable for [state] — `Hidden` renders nothing (the caller is
 *  expected to not even add this view's window while Hidden, but rendering nothing here too is a
 *  harmless, defensive no-op). */
@Composable
fun ControlBarOverlay(
    state: ControlBarState,
    callbacks: ControlBarCallbacks,
) {
    when (state) {
        is ControlBarState.Hidden -> {}

        is ControlBarState.Running -> {
            if (state.collapsed) {
                CollapsedDot(onExpand = { callbacks.onCollapseToggle(false) }, onDrag = callbacks.onDrag)
            } else {
                RunningBar(state.caption, callbacks)
            }
        }

        is ControlBarState.Paused -> {
            PausedBar(state.returnTarget.label, callbacks)
        }

        is ControlBarState.Ended -> {
            EndedBar(state.summary, state.returnTarget.label, callbacks)
        }
    }
}

@Composable
private fun RunningBar(
    caption: String,
    callbacks: ControlBarCallbacks,
) {
    Row(
        modifier =
            Modifier
                .widthIn(max = 330.dp)
                .background(BarBackground, RoundedCornerShape(percent = 50))
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .dragToReposition(callbacks.onDrag),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { callbacks.onCollapseToggle(true) },
            modifier = Modifier.size(28.dp).semantics { contentDescription = "Collapse" },
        ) {
            Text("‹", color = CaptionColor)
        }
        RoundIconButton(onClick = callbacks.onPauseOrResume) {
            Icon(Icons.Filled.Pause, contentDescription = "Pause", tint = Color.White)
        }
        RoundIconButton(onClick = callbacks.onStop) {
            Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = DestructiveColor)
        }
        RoundIconButton(onClick = {}, enabled = false) {
            Icon(Icons.Filled.FiberManualRecord, contentDescription = "Record (coming soon)", tint = DisabledIconColor)
        }
        Text(caption, color = CaptionColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RoundIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(28.dp).background(IconButtonBackground, CircleShape),
        content = content,
    )
}

@Composable
private fun CollapsedDot(
    onExpand: () -> Unit,
    onDrag: (dxPx: Float, dyPx: Float) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(width = 22.dp, height = 56.dp)
                .background(BarBackground, RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
                .dragToReposition(onDrag),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onExpand,
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Expand control bar" },
        ) {
            Box(modifier = Modifier.size(8.dp).background(Accent, CircleShape))
        }
    }
}

@Composable
private fun PausedBar(
    returnTargetLabel: String,
    callbacks: ControlBarCallbacks,
) {
    Row(
        modifier =
            Modifier
                .background(BarBackground, RoundedCornerShape(percent = 50))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = callbacks.onPauseOrResume) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Resume", tint = Accent)
        }
        Text(
            "Back to $returnTargetLabel",
            color = CaptionColor,
            modifier = Modifier.clickable(onClick = callbacks.onBackToApp),
        )
        IconButton(onClick = callbacks.onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = DisabledIconColor)
        }
    }
}

@Composable
private fun EndedBar(
    summary: String?,
    returnTargetLabel: String,
    callbacks: ControlBarCallbacks,
) {
    Box(
        modifier =
            Modifier
                .widthIn(max = 330.dp)
                .background(BarBackground, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column {
            Text("Done", color = DoneColor)
            if (summary != null) {
                Text(summary, color = CaptionColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Back to $returnTargetLabel",
                    color = Accent,
                    modifier = Modifier.clickable(onClick = callbacks.onBackToApp),
                )
                IconButton(onClick = callbacks.onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = DisabledIconColor)
                }
            }
        }
    }
}
