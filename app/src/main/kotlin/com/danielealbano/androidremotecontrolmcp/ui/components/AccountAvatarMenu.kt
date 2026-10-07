@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountProfile

private const val AVATAR_SIZE_DP = 36

/**
 * Top-right account avatar (plan 70 global spec): the Google profile photo, or a blank/initial-
 * letter placeholder before it loads or for non-Google sign-in later. Tapping opens a dropdown
 * with the signed-in email and Sign out; tapping outside or the avatar again closes it. Shown even
 * when signed out (onboarding's sign-in step is skippable) — the dropdown then offers "Sign in"
 * instead of an email/sign-out it has no data for.
 */
@Composable
fun AccountAvatarMenu(
    profile: AccountProfile?,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .size(AVATAR_SIZE_DP.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3A3550))
                    .border(2.dp, Color(0xFF4A4468), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { menuOpen = !menuOpen },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            val photoUri = profile?.profilePictureUri
            if (photoUri != null) {
                AsyncImage(
                    model = photoUri,
                    contentDescription = "Account menu",
                    modifier = Modifier.size(AVATAR_SIZE_DP.dp).clip(CircleShape),
                )
            } else {
                val initial = (profile?.displayName ?: profile?.email)?.firstOrNull()?.uppercaseChar()
                if (initial != null) {
                    Text(text = initial.toString(), color = Color(0xFFECECF4))
                }
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.width(220.dp).background(Color(0xFF1F1F2A), RoundedCornerShape(14.dp)),
        ) {
            if (profile == null) {
                DropdownMenuItem(
                    text = { Text("Sign in") },
                    onClick = {
                        menuOpen = false
                        onSignInClick()
                    },
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    profile.displayName?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFECECF4))
                    }
                    profile.email?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A9AAE))
                    }
                }
                HorizontalDivider(color = Color(0xFF322E46))
                DropdownMenuItem(
                    text = { Text("Sign out", color = Color(0xFFF2777A)) },
                    onClick = {
                        menuOpen = false
                        onSignOutClick()
                    },
                )
            }
        }
    }
}
