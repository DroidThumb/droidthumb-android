@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

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
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ConnectionsState

private val SECONDARY_TEXT = Color(0xFF9A9AAE)
private val CARD_BACKGROUND = Color(0xFF1C1C24)

/**
 * AI client detail screen (plan 70 US4): rename a connected client and view when it was
 * connected. "Change image" is deferred this round (founder decision, recorded in the plan —
 * droidthumb-server has no image-hosting infrastructure yet) and "remove connection" is explicitly
 * out of scope per your spec. The name saves on blur, matching the mockup's plain text field with
 * no visible Save button.
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

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("AI client") },
            windowInsets = WindowInsets(0),
            navigationIcon = {
                IconButton(onClick = onBack) {
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
                                if (!focusState.isFocused && nameInput.isNotBlank() && nameInput != connection.displayName) {
                                    accountViewModel.renameConnection(context, clientId, nameInput)
                                }
                            },
                )
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(CARD_BACKGROUND, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(text = "Connected", style = MaterialTheme.typography.bodySmall, color = SECONDARY_TEXT)
                Text(text = connection.connectedAt, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
