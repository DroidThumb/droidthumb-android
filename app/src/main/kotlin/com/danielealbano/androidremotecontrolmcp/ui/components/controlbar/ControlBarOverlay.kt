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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.services.controlbar.ControlBarState

private val BarBackground = Color(0xFF1C1C20)
private val Accent = Color(0xFF60C8FF)
private val PausedGrey = Color(0xFF9E9E9E)
private val CaptionColor = Color(0xFFD7D7DA)
private val DestructiveColor = Color(0xFFFF6B6B)
private val DoneColor = Color(0xFF9BE39B)

/** Callbacks the floating control bar's buttons invoke — all delegate straight to
 *  [com.danielealbano.androidremotecontrolmcp.services.controlbar.ControlBarCoordinator]'s own
 *  methods; this composable holds no business logic of its own (plan 71 US2, simplified round 2
 *  per founder phone-test feedback: no collapse, no drag, no record button). */
data class ControlBarCallbacks(
    val onStop: () -> Unit,
    val onPauseOrResume: () -> Unit,
    val onBackToApp: () -> Unit,
    val onClose: () -> Unit,
)

/** Thin, fully pass-through edge glow shown while an AI session is live (plan 71, D-39) - the
 *  caller (McpAccessibilityService) never adds this composable's window with touchable flags, so
 *  it never intercepts touches regardless of what's drawn here. Grey while paused - unmistakable
 *  at a glance, not just a dimmer version of the running color (founder phone-test feedback round
 *  2: "I couldn't tell whether Pause worked"). */
@Composable
fun EdgeGlow(paused: Boolean) {
    val color = if (paused) PausedGrey else Accent
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .border(BorderStroke(3.dp, color.copy(alpha = 0.55f))),
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
            RunningBar(state.caption, callbacks)
        }

        is ControlBarState.Paused -> {
            PausedBar(callbacks)
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
    BarRow {
        Text(
            caption,
            color = CaptionColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = callbacks.onPauseOrResume) {
            Icon(Icons.Filled.Pause, contentDescription = "Pause", tint = Color.White)
        }
        IconButton(onClick = callbacks.onStop) {
            Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = DestructiveColor)
        }
    }
}

@Composable
private fun PausedBar(callbacks: ControlBarCallbacks) {
    BarRow {
        Text(
            "Paused",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = callbacks.onPauseOrResume) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Resume", tint = Accent)
        }
        IconButton(onClick = callbacks.onStop) {
            Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = DestructiveColor)
        }
    }
}

@Composable
private fun EndedBar(
    summary: String?,
    returnTargetLabel: String,
    callbacks: ControlBarCallbacks,
) {
    BarRow(onClick = callbacks.onClose, onClickLabel = "Dismiss") {
        Column(modifier = Modifier.weight(1f)) {
            Text("Done", color = DoneColor, fontWeight = FontWeight.Bold)
            if (summary != null) {
                Text(summary, color = CaptionColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            "Back to $returnTargetLabel",
            color = Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = callbacks.onBackToApp),
        )
    }
}

/** Shared bar chrome: fixed at the top of the screen (plan 71 round 2 - no more drag/collapse),
 *  a single readable row. [onClick], when given, makes the WHOLE row clickable (the Ended bar's
 *  "tap anywhere dismisses it" behavior, with an [onClickLabel] so TalkBack announces what
 *  activating it does - found in code review, round 2, that the row replacing round 1's explicit
 *  "Close" `IconButton` had dropped that affordance's `contentDescription` entirely) - any nested
 *  `clickable` child (like "Back to") still consumes its own tap first, per Compose's normal
 *  pointer-input hit-testing, so it never triggers [onClick] too.
 *
 *  `fillMaxWidth()` with side margins, not a fixed `widthIn(max = ...)` - the hosting window is a
 *  small, explicit, density-aware pixel width capped to the screen's own width (see
 *  `McpAccessibilityService.barLayoutParams`), so this is what actually keeps the pill from
 *  stretching edge-to-edge within that window while the "Back to <app>" text itself truncates
 *  with an ellipsis rather than relying on window size alone to avoid clipping (found live on
 *  redroid, round 2; the window sizing itself was revisited again in code review, round 2, after
 *  an initial `MATCH_PARENT` fix turned out to create a screen-wide invisible touch-dead-zone). */
@Composable
private fun BarRow(
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(BarBackground, RoundedCornerShape(16.dp))
                .let { if (onClick != null) it.clickable(onClickLabel = onClickLabel, onClick = onClick) else it }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
