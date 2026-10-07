@file:Suppress("FunctionNaming", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportStatus

private const val STATUS_DOT_SIZE_DP = 10
private const val STATUS_TEXT_COLOR = 0xFF121218

/** The light pastel badge background + dark dot/text per bucket — fixed brand colors from the
 *  approved mockup, not theme-driven (unlike the rest of the app's dark Material theme). */
private data class BucketColors(val background: Color, val dot: Color)

private fun bucketColors(bucket: StatusBucket): BucketColors =
    when (bucket) {
        StatusBucket.CONNECTED -> BucketColors(Color(0xFF9FE6B8), Color(0xFF1F7A43))
        StatusBucket.CONNECTING -> BucketColors(Color(0xFFF6D98B), Color(0xFF8A6A16))
        StatusBucket.ERROR -> BucketColors(Color(0xFFF2A3A5), Color(0xFF8A2426))
        StatusBucket.PAUSED -> BucketColors(Color(0xFFCFCFE0), Color(0xFF3A3550))
    }

/**
 * Replaces the old "Remote Control" status card (plan 70 US3): a single pill showing exactly the
 * four states the spec demonstrates (Connected / Reconnecting… / the fixed error copy / Paused),
 * driven by the same [TransportStatus]/[PauseState] the old card used — see
 * [statusBucket]/[statusText]. The approved mockup's status card has no "Pause" button for the
 * non-paused states, so pausing is triggered by tapping the pill itself (founder decision,
 * recorded in the plan) rather than a separate always-visible button.
 */
@Composable
fun HomeStatusIndicator(
    status: TransportStatus,
    pauseState: PauseState,
    onPauseFor1Hour: () -> Unit,
    onPauseUntilTomorrow: () -> Unit,
    onPauseIndefinitely: () -> Unit,
    onResumeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paused = pauseState.isEffectivePause(System.currentTimeMillis())
    val bucket = statusBucket(status, paused)
    val text = statusText(bucket, status, pauseState.resumeAtEpochMs)
    val colors = bucketColors(bucket)
    var showPauseOptions by remember { mutableStateOf(false) }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.background)
                .clickable(enabled = !paused) { showPauseOptions = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bucket == StatusBucket.CONNECTING) {
            CircularProgressIndicator(
                modifier = Modifier.size(STATUS_DOT_SIZE_DP.dp + 4.dp),
                color = Color(STATUS_TEXT_COLOR),
                strokeWidth = 2.dp,
            )
        } else {
            Canvas(
                modifier =
                    Modifier
                        .size(STATUS_DOT_SIZE_DP.dp)
                        .semantics { contentDescription = "Status: $text" },
            ) {
                drawCircle(color = colors.dot)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(STATUS_TEXT_COLOR),
            modifier = Modifier.weight(1f),
        )
        if (bucket == StatusBucket.PAUSED) {
            FilledTonalButton(onClick = onResumeClick) {
                Text("Resume")
            }
        }
    }

    if (showPauseOptions) {
        PauseOptionsDialog(
            onDismiss = { showPauseOptions = false },
            onPauseFor1Hour = {
                showPauseOptions = false
                onPauseFor1Hour()
            },
            onPauseUntilTomorrow = {
                showPauseOptions = false
                onPauseUntilTomorrow()
            },
            onPauseIndefinitely = {
                showPauseOptions = false
                onPauseIndefinitely()
            },
        )
    }
}

@Composable
private fun PauseOptionsDialog(
    onDismiss: () -> Unit,
    onPauseFor1Hour: () -> Unit,
    onPauseUntilTomorrow: () -> Unit,
    onPauseIndefinitely: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pause remote control?") },
        text = {
            Column {
                TextButton(onClick = onPauseFor1Hour) { Text("For 1 hour") }
                TextButton(onClick = onPauseUntilTomorrow) { Text("Until tomorrow") }
                TextButton(onClick = onPauseIndefinitely) { Text("Until I resume") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
