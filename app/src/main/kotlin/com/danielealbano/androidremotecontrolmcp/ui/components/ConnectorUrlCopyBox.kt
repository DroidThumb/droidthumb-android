@file:Suppress("FunctionNaming", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.BuildConfig

/** The account-mode MCP endpoint (design doc D-33, §8.1): one fixed URL for every signed-in
 *  user — auth happens via the OAuth/DCR flow each client drives itself (Sign in now/Register
 *  automatically for Claude, OAuth for ChatGPT), not a URL-embedded per-device secret. Unlike the
 *  self-host connector URL (plan 70 US2 decision), this needs no settings lookup — it's a pure
 *  build-time constant. */
val mcpServerUrl: String
    get() = "https://${BuildConfig.DEFAULT_SERVER_HOST}/mcp"

/**
 * The box+monospace-URL+copy-icon-button pattern (plan 70 US5 Task 5.2), reused both at the top
 * of each "Add AI client" instructions screen and again inline at the "paste the URL" step.
 */
@Composable
fun ConnectorUrlCopyBox(
    url: String,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1C1C24))
                .border(1.dp, Color(0xFF322E46), RoundedCornerShape(10.dp))
                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = Color(0xFFB9C6F7),
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { clipboardManager.setText(AnnotatedString(url)) },
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy MCP server URL",
                tint = Color(0xFFECECF4),
            )
        }
    }
}
