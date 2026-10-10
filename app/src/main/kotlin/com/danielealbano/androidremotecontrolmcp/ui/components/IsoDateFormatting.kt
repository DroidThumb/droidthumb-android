package com.danielealbano.androidremotecontrolmcp.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Formats a server-sent ISO-8601 timestamp for display (e.g. "Oct 9, 2026") — shared by
 *  [ThisDeviceSection] and [AiClientsSection]/`AiClientDetailScreen`, which all show a server-sent
 *  date the same way. Falls back to the raw string if it isn't parseable, rather than crashing or
 *  showing nothing. */
fun formatIsoDate(isoTimestamp: String): String =
    try {
        DateTimeFormatter
            .ofPattern("MMM d, yyyy")
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(isoTimestamp))
    } catch (_: DateTimeParseException) {
        isoTimestamp
    }
