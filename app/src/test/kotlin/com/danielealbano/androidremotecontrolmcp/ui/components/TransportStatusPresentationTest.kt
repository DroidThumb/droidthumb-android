package com.danielealbano.androidremotecontrolmcp.ui.components

import com.danielealbano.androidremotecontrolmcp.services.transport.TransportStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TransportStatusPresentation")
class TransportStatusPresentationTest {
    @Test
    fun `connected maps to CONNECTED with fixed copy`() {
        val status = TransportStatus.Connected(protocolVersion = 1)
        val bucket = statusBucket(status, paused = false)

        assertEquals(StatusBucket.CONNECTED, bucket)
        assertEquals("Connected", statusText(bucket, status, resumeAtEpochMs = null))
    }

    @Test
    fun `reconnecting maps to CONNECTING with the reconnecting copy`() {
        val status = TransportStatus.Reconnecting(attempt = 3, delayMs = 1000L)
        val bucket = statusBucket(status, paused = false)

        assertEquals(StatusBucket.CONNECTING, bucket)
        assertEquals("Reconnecting…", statusText(bucket, status, resumeAtEpochMs = null))
    }

    @Test
    fun `idle and connecting also map to CONNECTING, with the connecting copy`() {
        assertEquals(StatusBucket.CONNECTING, statusBucket(TransportStatus.Idle, paused = false))
        assertEquals(StatusBucket.CONNECTING, statusBucket(TransportStatus.Connecting, paused = false))
        assertEquals(
            "Connecting…",
            statusText(StatusBucket.CONNECTING, TransportStatus.Idle, resumeAtEpochMs = null),
        )
    }

    @Test
    fun `rejected maps to ERROR with the fixed spec copy, not the raw reason`() {
        val status = TransportStatus.Rejected(closeCode = 4000.toShort(), reason = "bad_secret")
        val bucket = statusBucket(status, paused = false)

        assertEquals(StatusBucket.ERROR, bucket)
        assertEquals(
            "We seem to be having a problem but are aware and working hard to fix it",
            statusText(bucket, status, resumeAtEpochMs = null),
        )
    }

    @Test
    fun `paused always wins regardless of the underlying transport status`() {
        val bucket = statusBucket(TransportStatus.Connected(protocolVersion = 1), paused = true)

        assertEquals(StatusBucket.PAUSED, bucket)
    }

    @Test
    fun `paused with no resume deadline shows plain Paused`() {
        assertEquals(
            "Paused",
            statusText(StatusBucket.PAUSED, TransportStatus.Idle, resumeAtEpochMs = null),
        )
    }

    @Test
    fun `paused with a resume deadline includes the formatted time`() {
        val text = statusText(StatusBucket.PAUSED, TransportStatus.Idle, resumeAtEpochMs = 1L)

        assertEquals(true, text.startsWith("Paused until "))
    }
}
