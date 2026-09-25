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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

private const val STATUS_DOT_SIZE_DP = 12
private const val ANIMATION_DURATION_MS = 300

/**
 * Status and start/stop control for the Event Channel (the Event Channel row of the former
 * `ServerStatusCard`, whose MCP-server row was removed with the server).
 */
@Composable
fun EventChannelStatusCard(
    channelStatus: ChannelConnectionStatus,
    channelEnabled: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    startEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = "Event Channel"
    val statusText = channelStatusToText(channelStatus, channelEnabled)
    val animatedColor by animateColorAsState(
        targetValue = channelStatusToColor(channelStatus, channelEnabled, isSystemInDarkTheme()),
        animationSpec = tween(durationMillis = ANIMATION_DURATION_MS),
        label = "statusColor",
    )

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Canvas(
                    modifier =
                        Modifier
                            .size(STATUS_DOT_SIZE_DP.dp)
                            .semantics {
                                contentDescription = "$label status: $statusText"
                            },
                ) {
                    drawCircle(color = animatedColor)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FilledTonalButton(
                onClick = if (channelEnabled) onStopClick else onStartClick,
                enabled = channelStartStopButtonEnabled(channelEnabled, startEnabled),
            ) {
                Text(text = if (channelEnabled) "Stop" else "Start")
            }
        }
    }
}

/**
 * Whether the Event Channel start/stop button is enabled. Stop (enabled) is always allowed;
 * Start requires [startEnabled] (accessibility granted).
 */
internal fun channelStartStopButtonEnabled(
    channelEnabled: Boolean,
    startEnabled: Boolean,
): Boolean = if (channelEnabled) true else startEnabled

private fun channelStatusToText(
    status: ChannelConnectionStatus,
    enabled: Boolean,
): String =
    if (!enabled) {
        "Stopped"
    } else {
        when (status) {
            is ChannelConnectionStatus.Idle -> "Idle"
            is ChannelConnectionStatus.Active -> "Active"
            is ChannelConnectionStatus.Error -> status.message
        }
    }

private fun channelStatusToColor(
    status: ChannelConnectionStatus,
    enabled: Boolean,
    isDarkTheme: Boolean,
): Color =
    if (!enabled) {
        if (isDarkTheme) Color(0xFFEF5350) else Color(0xFFF44336)
    } else {
        when (status) {
            is ChannelConnectionStatus.Idle -> {
                Color.Gray
            }

            is ChannelConnectionStatus.Active -> {
                if (isDarkTheme) Color(0xFF81C784) else Color(0xFF4CAF50)
            }

            is ChannelConnectionStatus.Error -> {
                if (isDarkTheme) Color(0xFFFFB74D) else Color(0xFFFF9800)
            }
        }
    }

@Preview(showBackground = true)
@Composable
private fun EventChannelStatusCardStoppedPreview() {
    AndroidRemoteControlMcpTheme {
        EventChannelStatusCard(
            channelStatus = ChannelConnectionStatus.Idle,
            channelEnabled = false,
            onStartClick = {},
            onStopClick = {},
            startEnabled = true,
        )
    }
}
