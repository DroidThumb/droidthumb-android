@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LINK_COLOR = Color(0xFF8EA2F2)
private const val FLOWS_SALES_PAGE_URL = "https://droidthumb.com/flows"

/**
 * "Saved flows" section (plan 70 Home screen): flows aren't implemented on Android at all yet
 * (confirmed via `grep -rln "SavedFlow\|ListFlows"` returning nothing) and run-count-ordered
 * listing is its own not-yet-built `droidthumb-server` piece (plan 05 US2) — this is an honest
 * empty-state-only shell, not a real list, until that lands.
 */
@Composable
fun SavedFlowsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Column(modifier = modifier) {
        SectionHeader("Saved flows")
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FLOWS_SALES_PAGE_URL)))
                    },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Save money with flows",
                style = MaterialTheme.typography.bodyMedium,
                color = LINK_COLOR,
            )
            Icon(imageVector = Icons.Default.Add, contentDescription = "Learn about flows", tint = LINK_COLOR)
        }
    }
}
