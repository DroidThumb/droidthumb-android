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
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ThisDeviceState

private val SECTION_HEADER_COLOR = Color(0xFF9A9AAE)
private val CARD_BACKGROUND = Color(0xFF1C1C24)
private const val INSTALL_PAGE_URL = "https://droidthumb.com/install"
private const val DEVICES_SALES_PAGE_URL = "https://droidthumb.com/devices"

/**
 * "This device" section (plan 70 Home screen, bottom): this phone's name and when it was added to
 * the account, plus "+ Devices". No real free-tier device limit exists yet (founder decision,
 * recorded in the plan), so "+ Devices" always opens the install page — the multi-device sales
 * page branch is unreachable until a real limit lands.
 */
@Composable
fun ThisDeviceSection(
    deviceModel: String,
    thisDeviceState: ThisDeviceState,
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
                    text = thisDeviceSubtitle(thisDeviceState),
                    style = MaterialTheme.typography.bodySmall,
                    color = SECTION_HEADER_COLOR,
                )
            }
            if (thisDeviceState is ThisDeviceState.Loading) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp))
            } else {
                val deviceLimit = (thisDeviceState as? ThisDeviceState.Loaded)?.deviceLimit
                val targetUrl = if (deviceLimit != null) DEVICES_SALES_PAGE_URL else INSTALL_PAGE_URL
                Row(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFFB9C6F7))
                            .clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
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

private fun thisDeviceSubtitle(state: ThisDeviceState): String =
    when (state) {
        is ThisDeviceState.Loaded -> {
            state.device?.let { "Added ${formatIsoDate(it.createdAt)}" }
                // Right after a fresh claim, this device's own registration can momentarily not
                // show up yet in the account's device list - not an error, just not caught up yet.
                ?: "Still showing up on the server — try again shortly"
        }

        is ThisDeviceState.Failed -> {
            state.message
        }

        // Idle is this section's real steady state whenever this device was never signed in at all
        // (sign-in is skippable during onboarding) - not just a flash before Loading, so it needs
        // its own message rather than silence, same reasoning as AiClientsSection's Idle branch.
        ThisDeviceState.Idle -> {
            "Sign in to see when this device was added"
        }

        ThisDeviceState.Loading -> {
            ""
        }
    }
