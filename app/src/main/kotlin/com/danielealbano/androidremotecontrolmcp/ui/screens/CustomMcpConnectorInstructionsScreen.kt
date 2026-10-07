@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectorUrlCopyBox
import com.danielealbano.androidremotecontrolmcp.ui.components.mcpServerUrl

/**
 * No vendor-specific flow exists for a generic MCP client (plan 70 US5): plain copy explaining the
 * server speaks standard OAuth 2.1 + Dynamic Client Registration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomMcpConnectorInstructionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Add Custom MCP") },
            windowInsets = WindowInsets(0),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ConnectorUrlCopyBox(url = mcpServerUrl)

            Text(
                text =
                    "Any MCP-compatible client can connect to this phone. In your client's settings, look " +
                        "for an option to add a custom connector, remote MCP server, or MCP integration, then " +
                        "paste the URL above as the server address.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD8D8E4),
            )

            Text(
                text =
                    "This server uses standard OAuth 2.1 with Dynamic Client Registration — if your client " +
                        "asks for a client ID and secret, leave them blank and let it register automatically; " +
                        "if it asks you to sign in, use the same Google account you used in DroidThumb.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD8D8E4),
            )
        }
    }
}
