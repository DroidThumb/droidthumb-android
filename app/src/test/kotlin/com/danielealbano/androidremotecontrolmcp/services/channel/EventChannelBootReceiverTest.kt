package com.danielealbano.androidremotecontrolmcp.services.channel

import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventChannelBootReceiverTest {
    @Test
    fun `auto-starts when enabled with an endpoint`() {
        assertTrue(shouldAutoStart(EventChannelConfig(enabled = true, endpointUrl = "http://localhost:9090")))
    }

    @Test
    fun `does not auto-start when disabled`() {
        assertFalse(shouldAutoStart(EventChannelConfig(enabled = false, endpointUrl = "http://localhost:9090")))
    }

    @Test
    fun `does not auto-start without an endpoint`() {
        assertFalse(shouldAutoStart(EventChannelConfig(enabled = true, endpointUrl = "  ")))
    }
}
