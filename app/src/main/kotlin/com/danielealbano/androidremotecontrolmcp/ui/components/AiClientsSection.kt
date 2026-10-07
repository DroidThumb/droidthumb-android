@file:Suppress("FunctionNaming", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.danielealbano.androidremotecontrolmcp.services.account.AccountConnection
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ConnectionsState

private val SECTION_HEADER_COLOR = Color(0xFF9A9AAE)
private val CARD_BACKGROUND = Color(0xFF1C1C24)
private val DIVIDER_COLOR = Color(0xFF262633)
private val LINK_COLOR = Color(0xFF8EA2F2)
private const val LOGO_BADGE_SIZE_DP = 34

/**
 * "AI clients" section (plan 70 Home screen, top): the account's connected AI clients, each
 * opening its detail screen; an empty state when there are none; "+ Add AI client" always at the
 * bottom-right. [ConnectionsState.Failed] reuses the same message [ConnectionsState] already
 * carries for "sign in to view" — no separate signed-out empty state is invented here.
 */
@Composable
fun AiClientsSection(
    connectionsState: ConnectionsState,
    onClientClick: (clientId: String) -> Unit,
    onAddClientClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader("AI clients")
        when (connectionsState) {
            is ConnectionsState.Loading -> {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }

            is ConnectionsState.Failed -> {
                Text(
                    text = connectionsState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = SECTION_HEADER_COLOR,
                )
            }

            is ConnectionsState.Loaded -> {
                if (connectionsState.connections.isEmpty()) {
                    Text(
                        text = "No AI clients connected yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SECTION_HEADER_COLOR,
                    )
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CARD_BACKGROUND),
                    ) {
                        connectionsState.connections.forEachIndexed { index, connection ->
                            if (index > 0) {
                                HorizontalDivider(color = DIVIDER_COLOR, modifier = Modifier.padding(start = 14.dp))
                            }
                            AiClientRow(connection = connection, onClick = { onClientClick(connection.clientId) })
                        }
                    }
                }
            }

            is ConnectionsState.Idle -> {
                Unit
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            Text(
                text = "+ Add AI client",
                style = MaterialTheme.typography.bodyMedium,
                color = LINK_COLOR,
                modifier = Modifier.clickable(onClick = onAddClientClick),
            )
        }
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = SECTION_HEADER_COLOR,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun AiClientRow(
    connection: AccountConnection,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AiClientLogoBadge(displayName = connection.displayName, imageUrl = connection.imageUrl)
        Spacer(Modifier.width(12.dp))
        Text(text = connection.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** Letter-badge placeholder (no real brand logo assets yet) — the image shows once a client has
 *  one set (not buildable this round, see the plan's "Change image" deferral note). */
@Composable
internal fun AiClientLogoBadge(
    displayName: String,
    imageUrl: String?,
    sizeDp: Int = LOGO_BADGE_SIZE_DP,
) {
    Box(
        modifier =
            Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape((sizeDp * 0.3).dp))
                .background(Color(0xFF3A3550)),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape((sizeDp * 0.3).dp)),
            )
        } else {
            Text(
                text = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = Color(0xFFECECF4),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
