@file:Suppress("FunctionNaming", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private val STEP_BADGE_BACKGROUND = Color(0xFF3A3550)
private val STEP_BODY_COLOR = Color(0xFFD8D8E4)
private val FIELD_BACKGROUND = Color(0xFF15151C)
private val FIELD_BORDER = Color(0xFF262633)
private val FIELD_LABEL_COLOR = Color(0xFF9A9AAE)
private val FIELD_VALUE_COLOR = Color(0xFFECECF4)
private val FOOTNOTE_BACKGROUND = Color(0xFF1C1C24)

/** A numbered step row (the shared shape both [ClaudeConnectorInstructionsScreen] and
 *  [ChatGptConnectorInstructionsScreen] use), with bold inline terms matched by `**term**`. */
@Composable
fun InstructionStep(
    number: Int,
    body: String,
    extraContent: (@Composable () -> Unit)? = null,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(24.dp).clip(CircleShape).background(STEP_BADGE_BACKGROUND),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = number.toString(), style = MaterialTheme.typography.labelSmall, color = Color(0xFFECECF4))
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = boldedTerms(body), style = MaterialTheme.typography.bodyMedium, color = STEP_BODY_COLOR)
            extraContent?.invoke()
        }
    }
}

/** A labeled "this is the exact value to pick" row, e.g. `Authentication  **Sign in now**`. */
@Composable
fun InstructionField(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(FIELD_BACKGROUND)
                .border(1.dp, FIELD_BORDER, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = FIELD_LABEL_COLOR)
        Spacer(Modifier.size(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = FIELD_VALUE_COLOR,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The small "where this copy came from" attribution box at the bottom of each instructions
 *  screen. */
@Composable
fun InstructionSourceNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = FIELD_LABEL_COLOR,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(FOOTNOTE_BACKGROUND)
                .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

private val BOLD_TERM_PATTERN = Regex("\\*\\*(.+?)\\*\\*")

/** Renders `**term**` spans in bold — just enough markup for this screen's own copy, not a
 *  general-purpose parser. */
private fun boldedTerms(text: String) =
    buildAnnotatedString {
        var cursor = 0
        for (match in BOLD_TERM_PATTERN.findAll(text)) {
            append(text.substring(cursor, match.range.first))
            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                append(match.groupValues[1])
            }
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
    }
