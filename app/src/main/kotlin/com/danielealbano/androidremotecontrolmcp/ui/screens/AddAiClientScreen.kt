@file:Suppress("FunctionNaming", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val CARD_BACKGROUND = Color(0xFF1C1C24)
private val CARD_BORDER = Color(0xFF262633)
private val SECONDARY_TEXT = Color(0xFF9A9AAE)
private const val LOGO_SIZE_DP = 40

/** Selectable boxes for each AI-client type (plan 70 US5 Task 5.1) — each opens its own connector
 *  instructions screen. Logo colors/letters are placeholders (no real brand assets yet), matching
 *  [com.danielealbano.androidremotecontrolmcp.ui.components.AiClientLogoBadge]'s own disclaimer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAiClientScreen(
    onBack: () -> Unit,
    onSelectClaude: () -> Unit,
    onSelectChatGpt: () -> Unit,
    onSelectCustomMcp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Add AI client") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Choose the AI client you want to connect this phone to.",
                style = MaterialTheme.typography.bodyMedium,
                color = SECONDARY_TEXT,
            )

            AddAiClientRow(
                badgeColor = Color(0xFFD97757),
                letter = "C",
                name = "Claude",
                description = "Anthropic's assistant",
                onClick = onSelectClaude,
            )
            AddAiClientRow(
                badgeColor = Color(0xFF4D9E8F),
                letter = "G",
                name = "ChatGPT",
                description = "OpenAI's assistant",
                onClick = onSelectChatGpt,
            )
            AddAiClientRow(
                badgeColor = Color(0xFF3A3550),
                letter = null,
                name = "Custom MCP",
                description = "Any other MCP-compatible client",
                onClick = onSelectCustomMcp,
            )
        }
    }
}

@Composable
private fun AddAiClientRow(
    badgeColor: Color,
    letter: String?,
    name: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CARD_BACKGROUND)
                .border(1.dp, CARD_BORDER, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(LOGO_SIZE_DP.dp).clip(RoundedCornerShape(11.dp)).background(badgeColor),
            contentAlignment = Alignment.Center,
        ) {
            if (letter != null) {
                Text(text = letter, color = Color(0xFF1A1A2E), style = MaterialTheme.typography.titleMedium)
            } else {
                Icon(imageVector = Icons.Default.Build, contentDescription = null, tint = Color(0xFFECECF4))
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(text = name, style = MaterialTheme.typography.titleSmall)
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = SECONDARY_TEXT)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color(0xFF6A6A7E),
        )
    }
}
