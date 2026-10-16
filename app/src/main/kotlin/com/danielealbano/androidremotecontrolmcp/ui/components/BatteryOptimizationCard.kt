@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R

@Composable
fun BatteryOptimizationCard(
    onFixClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CalloutCard(
        icon = Icons.Default.BatteryAlert,
        title = stringResource(R.string.battery_optimization_card_title),
        modifier = modifier,
    ) {
        Text(text = stringResource(R.string.battery_optimization_card_body))
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onFixClick) {
            Text(text = stringResource(R.string.battery_optimization_card_action))
        }
    }
}
