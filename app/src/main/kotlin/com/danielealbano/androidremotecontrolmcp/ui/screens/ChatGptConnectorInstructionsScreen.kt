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
 * ChatGPT's Developer Mode / full MCP connectors setup (plan 70 US5), based on OpenAI's own help
 * article (help.openai.com/en/articles/12584461 — reachable only via search-engine synthesis at
 * plan-writing time, help.openai.com returned 403 to a direct fetch): Settings › Apps › Advanced
 * settings › Developer mode (one-time) › Create app › Name/Description/MCP Server URL/
 * Authentication: OAuth › Create. Re-verify against the live UI before shipping — ChatGPT's
 * settings surface has changed shape before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatGptConnectorInstructionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Add ChatGPT") },
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
                InstructionStep(1, "In ChatGPT, open **Settings › Apps**.")
                InstructionStep(
                    2,
                    "Under **Advanced settings**, turn on **Developer mode** (one-time, lets ChatGPT connect " +
                        "to custom MCP servers).",
                )
                InstructionStep(3, "Next to **Advanced settings**, click **Create app**.")
                InstructionStep(
                    4,
                    "Fill in the app details:",
                    extraContent = {
                        InstructionField("Name", "DroidThumb")
                        InstructionField("Description", "Control my Android phone")
                    },
                )
                InstructionStep(
                    5,
                    "Paste your server URL into **MCP Server URL**:",
                    extraContent = { ConnectorUrlCopyBox(url = mcpServerUrl, modifier = Modifier.padding(top = 8.dp)) },
                )
                InstructionStep(
                    6,
                    "For **Authentication**, choose **OAuth**.",
                    extraContent = { InstructionField("Authentication", "OAuth") },
                )
                InstructionStep(7, "Click **Create**, then sign in with the Google account you used in DroidThumb when the browser opens.")
            }

            InstructionSourceNote(
                "Based on OpenAI's own help article (\"Developer mode and full MCP connectors in " +
                    "ChatGPT\") — ChatGPT's settings UI has changed shape before, so re-check field names/order " +
                    "against the live app before shipping.",
            )
        }
    }
}
