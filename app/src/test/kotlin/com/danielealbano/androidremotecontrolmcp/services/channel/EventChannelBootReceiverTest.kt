package com.danielealbano.androidremotecontrolmcp.services.channel

import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventChannelBootReceiverTest {
    @Test
    fun `auto-starts when enabled with a connector url`() {
        assertTrue(shouldAutoStart(EventChannelConfig(enabled = true), hasConnectorUrl = true))
    }

    @Test
    fun `does not auto-start when disabled`() {
        assertFalse(shouldAutoStart(EventChannelConfig(enabled = false), hasConnectorUrl = true))
    }

    @Test
    fun `does not auto-start without a connector url`() {
        assertFalse(shouldAutoStart(EventChannelConfig(enabled = true), hasConnectorUrl = false))
    }
}
