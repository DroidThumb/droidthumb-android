@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.ui.components.AiClientLogoBadge
import com.danielealbano.androidremotecontrolmcp.ui.components.formatIsoDate
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ConnectionsState

private val SECONDARY_TEXT = Color(0xFF9A9AAE)
private val CARD_BACKGROUND = Color(0xFF1C1C24)
private val DESTRUCTIVE_COLOR = Color(0xFFF2777A)

/**
 * AI client detail screen (plan 70 US4, "remove connection" brought forward by founder phone-test
 * feedback, PR #9 round 1): rename a connected client, view when it was connected/last used, and
 * remove it. "Change image" is deferred this round (founder decision, recorded in the plan —
 * droidthumb-server has no image-hosting infrastructure yet). The name saves on blur, matching the
 * mockup's plain text field with no visible Save button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiClientDetailScreen(
    clientId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    accountViewModel: AccountViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val connectionsState by accountViewModel.connectionsState.collectAsStateWithLifecycle()
    val connection =
        (connectionsState as? ConnectionsState.Loaded)?.connections?.firstOrNull { it.clientId == clientId }

    var nameInput by remember(connection?.displayName) { mutableStateOf(connection?.displayName.orEmpty()) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    // Saving only on `onFocusChanged` isn't enough on its own: neither the back arrow nor the
    // system back gesture/button is guaranteed to deliver a focus-loss callback before this screen
    // leaves composition, so a pending edit would otherwise be silently lost on either path. All
    // three (focus loss, the back arrow, system back) call the same idempotent save-if-changed
    // check.
    val saveIfChanged = {
        if (connection != null && nameInput.isNotBlank() && nameInput != connection.displayName) {
            accountViewModel.renameConnection(context, clientId, nameInput)
        }
    }
    BackHandler {
        saveIfChanged()
        onBack()
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("AI client") },
            windowInsets = WindowInsets(0),
            navigationIcon = {
                IconButton(
                    onClick = {
                        saveIfChanged()
                        onBack()
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
        )
        if (connection == null) {
            return@Column
        }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AiClientLogoBadge(displayName = connection.displayName, imageUrl = connection.imageUrl, sizeDp = 84)
                TextButton(onClick = {}, enabled = false) {
                    Text("Change image (coming soon)")
                }
            }

            Column {
                Text(text = "Name", style = MaterialTheme.typography.bodySmall, color = SECONDARY_TEXT)
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) saveIfChanged()
                            },
                )
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(CARD_BACKGROUND, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column {
                    Text(text = "Connected", style = MaterialTheme.typography.bodySmall, color = SECONDARY_TEXT)
                    Text(text = formatIsoDate(connection.connectedAt), style = MaterialTheme.typography.bodyMedium)
                }
                Column {
                    Text(text = "Last used", style = MaterialTheme.typography.bodySmall, color = SECONDARY_TEXT)
                    Text(
                        text = connection.lastUsedAt?.let { formatIsoDate(it) } ?: "Never used yet",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            TextButton(onClick = { showRemoveConfirm = true }) {
                Text("Remove connection", color = DESTRUCTIVE_COLOR)
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove this connection?") },
            text = {
                Text(
                    "${connection?.displayName ?: "This AI client"} will no longer be able to control this phone. " +
                        "You can add it again later if you change your mind.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveConfirm = false
                        accountViewModel.revokeConnection(context, clientId)
                        onBack()
                    },
                ) {
                    Text("Remove", color = DESTRUCTIVE_COLOR)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
