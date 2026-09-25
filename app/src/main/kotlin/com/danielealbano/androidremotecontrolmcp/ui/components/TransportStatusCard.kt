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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportStatus
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

private const val STATUS_DOT_SIZE_DP = 12
private const val ANIMATION_DURATION_MS = 300

/**
 * Status, server-address fields, and start/stop control for the M2 device transport. Host/port
 * are editable only while stopped — changing them while connected would silently reconnect
 * elsewhere, which is confusing without an explicit action.
 */
@Composable
fun TransportStatusCard(
    status: TransportStatus,
    enabled: Boolean,
    host: String,
    port: String,
    portError: String?,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    startEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = "Remote Control"
    val statusText = transportStatusToText(status, enabled)
    val animatedColor by animateColorAsState(
        targetValue = transportStatusToColor(status, enabled, isSystemInDarkTheme()),
        animationSpec = tween(durationMillis = ANIMATION_DURATION_MS),
        label = "transportStatusColor",
    )

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            TransportStatusRow(label, statusText, animatedColor, enabled, startEnabled, onStartClick, onStopClick)
            Spacer(modifier = Modifier.width(8.dp))
            TransportAddressFields(host, port, portError, onHostChange, onPortChange, fieldsEnabled = !enabled)
        }
    }
}

@Composable
private fun TransportStatusRow(
    label: String,
    statusText: String,
    statusColor: Color,
    enabled: Boolean,
    startEnabled: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
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
        FilledTonalButton(
            onClick = if (enabled) onStopClick else onStartClick,
            enabled = if (enabled) true else startEnabled,
        ) {
            Text(text = if (enabled) "Stop" else "Start")
        }
    }
}

@Composable
private fun TransportAddressFields(
    host: String,
    port: String,
    portError: String?,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    fieldsEnabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("Server host") },
                enabled = fieldsEnabled,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text("Port") },
                enabled = fieldsEnabled,
                singleLine = true,
                isError = portError != null,
                keyboardOptions =
                    androidx.compose.foundation.text
                        .KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(96.dp),
            )
        }
        if (portError != null) {
            Text(
                text = portError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun transportStatusToText(
    status: TransportStatus,
    enabled: Boolean,
): String =
    if (!enabled) {
        "Stopped"
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
    enabled: Boolean,
    isDarkTheme: Boolean,
): Color =
    if (!enabled) {
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
private fun TransportStatusCardStoppedPreview() {
    AndroidRemoteControlMcpTheme {
        TransportStatusCard(
            status = TransportStatus.Idle,
            enabled = false,
            host = "",
            port = "4001",
            portError = null,
            onHostChange = {},
            onPortChange = {},
            onStartClick = {},
            onStopClick = {},
            startEnabled = true,
        )
    }
}
