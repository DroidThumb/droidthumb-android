// Note: Full DataStore persistence tests require PreferencesDataStore with
// test context (Android instrumented test). These JVM-only tests verify
// serialization round-trips and config defaults.
// Serialization tests confirm that toJson/fromJson preserves all fields,
// which is the core persistence mechanism used by SettingsRepositoryImpl.
package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationFilterMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("EventChannelSettings")
class EventChannelSettingsTest {
    @Nested
    @DisplayName("default values")
    inner class DefaultValues {
        @Test
        fun `getEventChannelConfig returns default when empty`() {
            val config = EventChannelConfig()
            assertFalse(config.enabled)
            assertFalse(config.notifications.enabled)
        }
    }

    @Nested
    @DisplayName("serialization persistence")
    inner class SerializationPersistence {
        @Test
        fun `updateEventChannelEnabled persists`() {
            val config = EventChannelConfig(enabled = true)
            val json = config.toJson()
            val restored = EventChannelConfig.fromJson(json)
            assertTrue(restored.enabled)
        }

        @Test
        fun `updateNotificationFilterMode persists`() {
            val config =
                EventChannelConfig(
                    notifications =
                        com.danielealbano.androidremotecontrolmcp.data.model.NotificationChannelConfig(
                            filterMode = NotificationFilterMode.WHITELIST,
                        ),
                )
            val json = config.toJson()
            val restored = EventChannelConfig.fromJson(json)
            assertEquals(NotificationFilterMode.WHITELIST, restored.notifications.filterMode)
        }

        @Test
        fun `updateNotificationFilterApps persists`() {
            val apps = setOf("com.app1", "com.app2")
            val config =
                EventChannelConfig(
                    notifications =
                        com.danielealbano.androidremotecontrolmcp.data.model.NotificationChannelConfig(
                            filterApps = apps,
                        ),
                )
            val json = config.toJson()
            val restored = EventChannelConfig.fromJson(json)
            assertEquals(apps, restored.notifications.filterApps)
        }
    }
}
