@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.account.AccountConnection
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountClaimState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ConnectionsState

/**
 * Account sign-in/claim + AI-connections list (design doc D-33/D-37/D-38). One card: the
 * sign-in prompt when signed out, the connections list once claimed — same compact-card shape
 * every other section of this screen uses ([CalloutCard]'s sibling, not reused directly, since
 * this needs its own list body rather than a single action row).
 */
@Composable
fun AccountCard(
    accountId: String?,
    claimState: AccountClaimState,
    connectionsState: ConnectionsState,
    onSignInClick: () -> Unit,
    onRetryConnectionsClick: () -> Unit,
    onRevokeConnection: (clientId: String, clientName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.account_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(12.dp))

            if (accountId == null) {
                AccountSignInBody(claimState = claimState, onSignInClick = onSignInClick)
            } else {
                ConnectionsBody(
                    connectionsState = connectionsState,
                    onRetryClick = onRetryConnectionsClick,
                    onRevokeConnection = onRevokeConnection,
                )
            }
        }
    }
}

@Composable
private fun AccountSignInBody(
    claimState: AccountClaimState,
    onSignInClick: () -> Unit,
) {
    when (claimState) {
        is AccountClaimState.SigningIn, is AccountClaimState.Claiming -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    text =
                        stringResource(
                            if (claimState is AccountClaimState.Claiming) {
                                R.string.account_claiming
                            } else {
                                R.string.account_signing_in
                            },
                        ),
                )
            }
        }

        is AccountClaimState.AlreadyClaimedByOther -> {
            Text(text = stringResource(R.string.account_already_claimed_body))
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSignInClick) { Text(stringResource(R.string.account_sign_in_action)) }
        }

        is AccountClaimState.Failed -> {
            Text(text = claimState.message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSignInClick) { Text(stringResource(R.string.account_sign_in_action)) }
        }

        else -> {
            Text(text = stringResource(R.string.account_signed_out_body))
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSignInClick) { Text(stringResource(R.string.account_sign_in_action)) }
        }
    }
}

@Composable
private fun ConnectionsBody(
    connectionsState: ConnectionsState,
    onRetryClick: () -> Unit,
    onRevokeConnection: (clientId: String, clientName: String) -> Unit,
) {
    Text(text = stringResource(R.string.account_connections_title), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    when (connectionsState) {
        is ConnectionsState.Loading -> CircularProgressIndicator(modifier = Modifier.height(20.dp))

        is ConnectionsState.Failed -> {
            Text(text = stringResource(R.string.account_connections_load_failed), color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetryClick) { Text(stringResource(R.string.account_connections_retry)) }
        }

        is ConnectionsState.Loaded -> {
            if (connectionsState.connections.isEmpty()) {
                Text(text = stringResource(R.string.account_connections_empty))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    connectionsState.connections.forEach { connection ->
                        ConnectionRow(connection = connection, onRevokeClick = { onRevokeConnection(connection.clientId, connection.clientName) })
                    }
                }
            }
        }

        is ConnectionsState.Idle -> Unit
    }
}

@Composable
private fun ConnectionRow(
    connection: AccountConnection,
    onRevokeClick: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(text = connection.clientName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = connection.connectedAt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { showConfirm = true }) {
            Text(stringResource(R.string.account_connection_revoke_action))
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.account_connection_revoke_dialog_title)) },
            text = { Text(stringResource(R.string.account_connection_revoke_dialog_body, connection.clientName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onRevokeClick()
                    },
                ) { Text(stringResource(R.string.account_connection_revoke_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.account_connection_revoke_dialog_cancel))
                }
            },
        )
    }
}
