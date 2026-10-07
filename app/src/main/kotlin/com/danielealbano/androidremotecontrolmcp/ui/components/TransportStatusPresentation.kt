package com.danielealbano.androidremotecontrolmcp.ui.components

import com.danielealbano.androidremotecontrolmcp.services.transport.TransportStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The four visual states [HomeStatusIndicator] shows (plan 70 spec) — a many-to-one mapping of
 * [TransportStatus]'s real sub-states, not a 1:1 mirror of it: [TransportStatus.Idle] and
 * [TransportStatus.Connecting] both fall under [CONNECTING] alongside
 * [TransportStatus.Reconnecting], since none of them is the fixed "something is wrong" copy
 * [ERROR] carries. [PAUSED] always wins regardless of the underlying transport status — pausing
 * is the owner's own choice, not a connection outcome.
 */
enum class StatusBucket { CONNECTED, CONNECTING, ERROR, PAUSED }

fun statusBucket(
    status: TransportStatus,
    paused: Boolean,
): StatusBucket =
    when {
        paused -> StatusBucket.PAUSED
        status is TransportStatus.Connected -> StatusBucket.CONNECTED
        status is TransportStatus.Rejected -> StatusBucket.ERROR
        else -> StatusBucket.CONNECTING
    }

private const val ERROR_COPY = "We seem to be having a problem but are aware and working hard to fix it"

/** [resumeAtEpochMs] only matters for [StatusBucket.PAUSED] — ignored for every other bucket. */
fun statusText(
    bucket: StatusBucket,
    status: TransportStatus,
    resumeAtEpochMs: Long?,
): String =
    when (bucket) {
        StatusBucket.CONNECTED -> "Connected"
        StatusBucket.CONNECTING -> if (status is TransportStatus.Reconnecting) "Reconnecting…" else "Connecting…"
        StatusBucket.ERROR -> ERROR_COPY
        StatusBucket.PAUSED ->
            if (resumeAtEpochMs == null) {
                "Paused"
            } else {
                val time =
                    DateTimeFormatter
                        .ofPattern("MMM d, HH:mm")
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(resumeAtEpochMs))
                "Paused until $time"
            }
    }
