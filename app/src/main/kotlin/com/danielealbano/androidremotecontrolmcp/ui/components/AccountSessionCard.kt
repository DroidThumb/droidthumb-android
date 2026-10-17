@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountSessionState

private val CARD_BACKGROUND = Color(0xFF1C1C24)

/** The sign-in status message for [state], or `null` when there is nothing to show (the
 *  signed-in-and-claimed steady state). Top-level and `internal` so the copy mapping is
 *  unit-testable — the wording is the plan's exact UI copy. */
internal fun sessionMessage(state: AccountSessionState): String? =
    when (state) {
        is AccountSessionState.Checking -> {
            "Checking your sign-in…"
        }

        is AccountSessionState.SignedIn -> {
            if (state.claimed) {
                null
            } else {
                "This phone isn't linked to your account."
            }
        }

        AccountSessionState.SignedOut -> {
            "You're signed out."
        }

        is AccountSessionState.Unreachable -> {
            "Can't reach the server: ${state.reason}"
        }

        is AccountSessionState.ServerError -> {
            "The server had a problem (HTTP ${state.httpStatus})."
        }
    }

/** The action button label for [state], or `null` when the card shows no button. */
internal fun sessionActionLabel(state: AccountSessionState): String? =
    when (state) {
        is AccountSessionState.Checking -> {
            null
        }

        is AccountSessionState.SignedIn -> {
            if (state.claimed) {
                null
            } else {
                "Link this phone"
            }
        }

        AccountSessionState.SignedOut -> {
            "Sign in with Google"
        }

        is AccountSessionState.Unreachable, is AccountSessionState.ServerError -> {
            "Retry"
        }
    }

/**
 * Home's sign-in status card (plan A1a): one card showing the current [AccountSessionState]'s
 * message and its single action. Hidden entirely in the signed-in-and-claimed steady state.
 * One tap is one attempt — the card never retries on its own.
 */
@Composable
fun AccountSessionCard(
    sessionState: AccountSessionState,
    onSignInClick: () -> Unit,
    onLinkPhoneClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = sessionMessage(sessionState) ?: return
    val actionLabel = sessionActionLabel(sessionState)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CARD_BACKGROUND)
                .padding(14.dp),
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
        when {
            actionLabel == null && sessionState is AccountSessionState.Checking -> {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp))
                }
            }

            actionLabel != null -> {
                val onClick =
                    when (sessionState) {
                        is AccountSessionState.SignedIn -> onLinkPhoneClick
                        AccountSessionState.SignedOut -> onSignInClick
                        else -> onRetryClick
                    }
                Button(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}
