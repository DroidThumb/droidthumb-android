@file:Suppress("FunctionNaming", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountSessionState

private val SECTION_HEADER_COLOR = Color(0xFF9A9AAE)
private val CARD_BACKGROUND = Color(0xFF1C1C24)
private const val INSTALL_PAGE_URL = "https://droidthumb.com/install"

/**
 * "This device" section (plan 70 Home screen, bottom): this phone's name and when it was added to
 * the account, plus "+ Devices". The subtitle reads the one [AccountSessionState] everything else
 * on Home reads (plan A1a) — there is no separate device state any more. "+ Devices" opens the
 * install page (no real free-tier device limit exists yet — founder decision — so the sales-page
 * branch stays unreachable).
 */
@Composable
fun ThisDeviceSection(
    deviceModel: String,
    sessionState: AccountSessionState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(modifier = modifier) {
        SectionHeader("This device")
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CARD_BACKGROUND)
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = deviceModel, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = thisDeviceSubtitle(sessionState),
                    style = MaterialTheme.typography.bodySmall,
                    color = SECTION_HEADER_COLOR,
                )
            }
            if (sessionState is AccountSessionState.Checking) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp))
            } else {
                Row(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFFB9C6F7))
                            .clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(INSTALL_PAGE_URL)))
                            }.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "+ Devices",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF1A1A2E),
                    )
                }
            }
        }
    }
}

private fun thisDeviceSubtitle(state: AccountSessionState): String =
    when {
        state is AccountSessionState.SignedIn && state.claimed -> {
            state.device?.let { "Added ${formatIsoDate(it.createdAt)}" }
                // Right after a fresh claim, this device's own registration can momentarily not
                // show up yet in the account's device list - not an error, just not caught up yet.
                ?: "Still showing up on the server — try again shortly"
        }

        else -> {
            sessionMessage(state) ?: ""
        }
    }
