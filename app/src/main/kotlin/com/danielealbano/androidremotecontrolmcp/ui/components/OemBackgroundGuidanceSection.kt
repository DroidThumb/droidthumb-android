@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import android.content.ActivityNotFoundException
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.services.power.detectOemBrand
import com.danielealbano.androidremotecontrolmcp.services.power.oemGuidanceText
import com.danielealbano.androidremotecontrolmcp.services.power.oemSettingsIntent
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils

/**
 * The OEM-specific background-app-manager guidance (founder phone-test feedback, PR #9 round 1,
 * reproduced on a OnePlus): renders nothing for a brand with no known extra step
 * ([com.danielealbano.androidremotecontrolmcp.services.power.OemBrand.OTHER]) — shown right below
 * the standard battery-exemption action, in both the onboarding battery step and Home's own
 * reminder, since both need the exact same guidance.
 */
@Composable
fun OemBackgroundGuidanceSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val brand = detectOemBrand(Build.MANUFACTURER)
    val text = oemGuidanceText(brand) ?: return

    Column(modifier = modifier) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                val intent = oemSettingsIntent(brand)
                try {
                    if (intent != null) context.startActivity(intent) else PermissionUtils.openAppInfoSettings(context)
                } catch (_: ActivityNotFoundException) {
                    // This OEM's own settings activity is undocumented and can move/disappear
                    // across its software updates - App Info always resolves, so it's the safe
                    // fallback rather than leaving the tap silently do nothing.
                    PermissionUtils.openAppInfoSettings(context)
                }
            },
        ) {
            Text("Open background settings")
        }
    }
}
