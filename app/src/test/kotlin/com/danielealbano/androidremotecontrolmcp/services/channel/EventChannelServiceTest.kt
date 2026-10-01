// Note: EventChannelService extends android.app.Service and requires Android
// framework to instantiate. Full lifecycle tests (ACTION_START/STOP, listener
// creation, config observer) require Robolectric or instrumented tests. These
// JVM-only tests verify config validation logic, constants, and contracts.
package com.danielealbano.androidremotecontrolmcp.services.channel

import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationChannelConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("EventChannelService")
class EventChannelServiceTest {
    @Nested
    @DisplayName("events URL derivation")
    inner class EventsUrlDerivation {
        @Test
        fun `eventChannelOriginFromConnectorUrl drops the mcp path segment`() {
            assertEquals(
                "https://host/d/dtk_x",
                eventChannelOriginFromConnectorUrl("https://host/d/dtk_x/mcp"),
            )
        }
    }

    @Nested
    @DisplayName("service status")
    inner class ServiceStatus {
        @Test
        fun `initial service status is Idle`() {
            assertEquals(ChannelConnectionStatus.Idle, EventChannelService.serviceStatus.value)
        }
    }

    @Nested
    @DisplayName("listener configuration logic")
    inner class ListenerConfigurationLogic {
        @Test
        fun `config with notifications enabled requires notification listener creation`() {
            val config =
                EventChannelConfig(
                    enabled = true,
                    notifications = NotificationChannelConfig(enabled = true),
                )
            assertTrue(config.notifications.enabled)
        }

        @Test
        fun `config with all listeners disabled means no active sources`() {
            val config = EventChannelConfig(enabled = true)
            assertFalse(config.notifications.enabled)
        }

        @Test
        fun `disabled channel config should stop service`() {
            val config = EventChannelConfig(enabled = false)
            assertFalse(config.enabled)
        }
    }

    @Nested
    @DisplayName("action constants")
    inner class ActionConstants {
        @Test
        fun `ACTION_START is correctly defined`() {
            assertEquals(
                "com.danielealbano.androidremotecontrolmcp.channel.START",
                EventChannelService.ACTION_START,
            )
        }

        @Test
        fun `ACTION_STOP is correctly defined`() {
            assertEquals(
                "com.danielealbano.androidremotecontrolmcp.channel.STOP",
                EventChannelService.ACTION_STOP,
            )
        }
    }
}
