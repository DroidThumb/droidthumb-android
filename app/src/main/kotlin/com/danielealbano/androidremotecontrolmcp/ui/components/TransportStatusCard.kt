@file:Suppress("FunctionNaming", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportStatus
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val STATUS_DOT_SIZE_DP = 12
private const val ANIMATION_DURATION_MS = 300

/**
 * Status and the owner's Pause control for the M2 device transport (design doc §8.8 revision —
 * the connection starts on its own once signed in and accessible; Pause is the only thing the
 * owner controls here). The server address and connector URL have no UI here any more (plan 70
 * US2): hosted builds connect to the right server out of the box (staging for debug, production
 * for release) and authenticate via the signed-in account; self-hosting is a build-time-only
 * configuration with no in-app surface.
 */
@Composable
fun TransportStatusCard(
    status: TransportStatus,
    pauseState: PauseState,
    onPauseFor1Hour: () -> Unit,
    onPauseUntilTomorrow: () -> Unit,
    onPauseIndefinitely: () -> Unit,
    onResumeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = "Remote Control"
    val paused = pauseState.isEffectivePause(System.currentTimeMillis())
    val statusText = transportStatusToText(status, paused, pauseState.resumeAtEpochMs)
    val animatedColor by animateColorAsState(
        targetValue = transportStatusToColor(status, paused, isSystemInDarkTheme()),
        animationSpec = tween(durationMillis = ANIMATION_DURATION_MS),
        label = "transportStatusColor",
    )

    var showPauseOptions by remember { mutableStateOf(false) }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        TransportStatusCardContent(
            label = label,
            statusText = statusText,
            statusColor = animatedColor,
            paused = paused,
            onPauseClick = { showPauseOptions = true },
            onResumeClick = onResumeClick,
        )
    }

    if (showPauseOptions) {
        PauseOptionsDialog(
            onDismiss = { showPauseOptions = false },
            onPauseFor1Hour = {
                showPauseOptions = false
                onPauseFor1Hour()
            },
            onPauseUntilTomorrow = {
                showPauseOptions = false
                onPauseUntilTomorrow()
            },
            onPauseIndefinitely = {
                showPauseOptions = false
                onPauseIndefinitely()
            },
        )
    }
}

@Composable
private fun TransportStatusCardContent(
    label: String,
    statusText: String,
    statusColor: Color,
    paused: Boolean,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        TransportStatusRow(
            label = label,
            statusText = statusText,
            statusColor = statusColor,
            paused = paused,
            onPauseClick = onPauseClick,
            onResumeClick = onResumeClick,
        )
    }
}

@Composable
private fun PauseOptionsDialog(
    onDismiss: () -> Unit,
    onPauseFor1Hour: () -> Unit,
    onPauseUntilTomorrow: () -> Unit,
    onPauseIndefinitely: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pause remote control?") },
        text = {
            Column {
                TextButton(onClick = onPauseFor1Hour) { Text("For 1 hour") }
                TextButton(onClick = onPauseUntilTomorrow) { Text("Until tomorrow") }
                TextButton(onClick = onPauseIndefinitely) { Text("Until I resume") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TransportStatusRow(
    label: String,
    statusText: String,
    statusColor: Color,
    paused: Boolean,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Canvas(
                modifier =
                    Modifier
                        .size(STATUS_DOT_SIZE_DP.dp)
                        .semantics { contentDescription = "$label status: $statusText" },
            ) {
                drawCircle(color = statusColor)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FilledTonalButton(onClick = if (paused) onResumeClick else onPauseClick) {
            Text(text = if (paused) "Resume" else "Pause")
        }
    }
}

private fun transportStatusToText(
    status: TransportStatus,
    paused: Boolean,
    resumeAtEpochMs: Long?,
): String =
    if (paused) {
        if (resumeAtEpochMs == null) {
            "Paused"
        } else {
            val time =
                DateTimeFormatter
                    .ofPattern("MMM d, HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(resumeAtEpochMs))
            "Paused until $time"
        }
    } else {
        when (status) {
            is TransportStatus.Idle -> "Idle"
            is TransportStatus.Connecting -> "Connecting…"
            is TransportStatus.Connected -> "Connected (protocol v${status.protocolVersion})"
            is TransportStatus.Rejected -> "Rejected: ${status.reason}"
            is TransportStatus.Reconnecting -> "Reconnecting (attempt ${status.attempt})…"
        }
    }

private fun transportStatusToColor(
    status: TransportStatus,
    paused: Boolean,
    isDarkTheme: Boolean,
): Color =
    if (paused) {
        if (isDarkTheme) Color(0xFFEF5350) else Color(0xFFF44336)
    } else {
        when (status) {
            is TransportStatus.Idle, is TransportStatus.Connecting -> Color.Gray
            is TransportStatus.Connected -> if (isDarkTheme) Color(0xFF81C784) else Color(0xFF4CAF50)
            is TransportStatus.Rejected -> if (isDarkTheme) Color(0xFFEF5350) else Color(0xFFF44336)
            is TransportStatus.Reconnecting -> if (isDarkTheme) Color(0xFFFFB74D) else Color(0xFFFF9800)
        }
    }

@Preview(showBackground = true)
@Composable
private fun TransportStatusCardPausedPreview() {
    AndroidRemoteControlMcpTheme {
        TransportStatusCard(
            status = TransportStatus.Idle,
            pauseState = PauseState(isPaused = true, resumeAtEpochMs = null),
            onPauseFor1Hour = {},
            onPauseUntilTomorrow = {},
            onPauseIndefinitely = {},
            onResumeClick = {},
        )
    }
}
