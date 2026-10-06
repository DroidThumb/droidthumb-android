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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.data.model.TransportConfig
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportStatus
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.TransportViewModel

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
    tls: Boolean,
    onTlsChange: (Boolean) -> Unit,
    connectorUrl: String?,
    regenerateState: TransportViewModel.RegenerateSecretState,
    onRegenerateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = "Remote Control"
    val statusText = transportStatusToText(status, enabled)
    val animatedColor by animateColorAsState(
        targetValue = transportStatusToColor(status, enabled, isSystemInDarkTheme()),
        animationSpec = tween(durationMillis = ANIMATION_DURATION_MS),
        label = "transportStatusColor",
    )

    var advancedExpanded by remember { mutableStateOf(false) }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            TransportStatusRow(label, statusText, animatedColor, enabled, startEnabled, onStartClick, onStopClick)
            Spacer(modifier = Modifier.width(8.dp))
            // Host/port/TLS are self-hosting-only (design doc D-33/§8.8): the app connects to the
            // right server out of the box (staging for a debug build, production for release), so
            // nobody signing in normally ever needs to see these fields, let alone edit them —
            // collapsed by default, not removed, since a self-hoster still needs to change them.
            TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                Text(if (advancedExpanded) "Hide advanced" else "Advanced (self-hosting)")
            }
            if (advancedExpanded) {
                TransportAddressFields(
                    host,
                    port,
                    portError,
                    onHostChange,
                    onPortChange,
                    tls,
                    onTlsChange,
                    fieldsEnabled = !enabled,
                )
            }
            if (connectorUrl != null) {
                Spacer(modifier = Modifier.height(12.dp))
                ConnectorUrlSection(
                    connectorUrl = connectorUrl,
                    canRegenerate = status is TransportStatus.Connected,
                    regenerateState = regenerateState,
                    onRegenerateClick = onRegenerateClick,
                )
            }
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
    tls: Boolean,
    onTlsChange: (Boolean) -> Unit,
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
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TLS", style = MaterialTheme.typography.bodySmall)
                Switch(checked = tls, onCheckedChange = onTlsChange, enabled = fieldsEnabled)
            }
        }
        if (portError != null) {
            Text(
                text = portError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (tls && port == TransportConfig.DEFAULT_PORT.toString()) {
            Text(
                "TLS is usually port 443 (Caddy), not $port — check with whoever set up the server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ConnectorUrlSection(
    connectorUrl: String,
    canRegenerate: Boolean,
    regenerateState: TransportViewModel.RegenerateSecretState,
    onRegenerateClick: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("Connector URL", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Add this as a connector in Claude, ChatGPT, or your own MCP-compatible agent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(connectorUrl, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { clipboardManager.setText(AnnotatedString(connectorUrl)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy connector URL")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showConfirm = true }, enabled = canRegenerate) {
                Text("Regenerate")
            }
            when (regenerateState) {
                TransportViewModel.RegenerateSecretState.IN_PROGRESS -> {
                    Text("Regenerating…", style = MaterialTheme.typography.bodySmall)
                }

                TransportViewModel.RegenerateSecretState.SUCCEEDED -> {
                    Text("Done", style = MaterialTheme.typography.bodySmall)
                }

                TransportViewModel.RegenerateSecretState.TIMED_OUT -> {
                    Text(
                        "No response — try again later",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                TransportViewModel.RegenerateSecretState.IDLE -> {}
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Regenerate connector URL?") },
            text = {
                Text(
                    "The current URL stops working immediately. Anything using it (Claude, ChatGPT, " +
                        "your own agent) will need the new one.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onRegenerateClick()
                    },
                ) { Text("Regenerate") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } },
        )
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
            port = "4000",
            portError = null,
            onHostChange = {},
            onPortChange = {},
            onStartClick = {},
            onStopClick = {},
            startEnabled = true,
            tls = false,
            onTlsChange = {},
            connectorUrl = null,
            regenerateState = TransportViewModel.RegenerateSecretState.IDLE,
            onRegenerateClick = {},
        )
    }
}
