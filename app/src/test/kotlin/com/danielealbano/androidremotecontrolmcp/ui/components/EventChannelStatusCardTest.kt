package com.danielealbano.androidremotecontrolmcp.ui.components

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventChannelStatusCardTest {
    @Test
    fun `channel start requires startEnabled`() {
        assertTrue(channelStartStopButtonEnabled(channelEnabled = false, startEnabled = true))
        assertFalse(channelStartStopButtonEnabled(channelEnabled = false, startEnabled = false))
    }

    @Test
    fun `channel stop always enabled`() {
        assertTrue(channelStartStopButtonEnabled(channelEnabled = true, startEnabled = false))
        assertTrue(channelStartStopButtonEnabled(channelEnabled = true, startEnabled = true))
    }
}
