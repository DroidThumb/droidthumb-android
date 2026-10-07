@file:Suppress("FunctionNaming", "LongMethod")

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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectorUrlCopyBox
import com.danielealbano.androidremotecontrolmcp.ui.components.InstructionField
import com.danielealbano.androidremotecontrolmcp.ui.components.InstructionSourceNote
import com.danielealbano.androidremotecontrolmcp.ui.components.InstructionStep
import com.danielealbano.androidremotecontrolmcp.ui.components.mcpServerUrl

/**
 * Claude's custom-connector setup (plan 70 US5), researched against claude.com/docs/connectors/
 * custom/add-unlisted rather than guessed: Settings › Connectors › Add custom connector › MCP
 * server URL › Authentication: Sign in now › OAuth client: Register automatically › Add.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudeConnectorInstructionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Add Claude") },
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

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InstructionStep(1, "In Claude, open your profile menu and go to **Settings › Connectors**.")
                InstructionStep(2, "Click **Add custom connector**.")
                InstructionStep(
                    3,
                    "Paste your server URL into **MCP server URL**:",
                    extraContent = { ConnectorUrlCopyBox(url = mcpServerUrl, modifier = Modifier.padding(top = 8.dp)) },
                )
                InstructionStep(
                    4,
                    "For **Authentication**, choose **Sign in now**.",
                    extraContent = { InstructionField("Authentication", "Sign in now") },
                )
                InstructionStep(
                    5,
                    "For **OAuth client**, choose **Register automatically** — Claude handles the rest, " +
                        "no client ID or secret needed.",
                    extraContent = { InstructionField("OAuth client", "Register automatically") },
                )
                InstructionStep(6, "Click **Add**, then sign in with the Google account you used in DroidThumb when the browser opens.")
            }

            InstructionSourceNote(
                "Steps and labels confirmed against Claude's own help docs " +
                    "(claude.com/docs/connectors/custom/add-unlisted) — worth a quick re-check against the live " +
                    "UI before shipping, since Claude's settings layout can change.",
            )
        }
    }
}
