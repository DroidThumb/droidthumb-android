package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [SettingsRepositoryImpl] holds no settings of its own after the demolition pass: every member
 * delegates to the [EventChannelSettings] slice (whose behaviour is covered by EventChannelSettingsTest).
 */
class SettingsRepositoryImplTest {
    private val eventChannelSettings = mockk<EventChannelSettings>(relaxed = true)
    private val transportSettings = mockk<TransportSettings>(relaxed = true)
    private val connectorUrlSettings = mockk<ConnectorUrlSettings>(relaxed = true)
    private val accountSettings = mockk<AccountSettings>(relaxed = true)
    private val repository =
        SettingsRepositoryImpl(eventChannelSettings, transportSettings, connectorUrlSettings, accountSettings)

    @Test
    fun `eventChannelConfig is the slice's flow`() =
        runTest {
            val config = EventChannelConfig(enabled = true)
            every { eventChannelSettings.eventChannelConfig } returns flowOf(config)

            assertEquals(config, repository.eventChannelConfig.first())
        }

    @Test
    fun `getEventChannelConfig delegates to the slice`() =
        runTest {
            val config = EventChannelConfig(enabled = true)
            coEvery { eventChannelSettings.getEventChannelConfig() } returns config

            assertEquals(config, repository.getEventChannelConfig())
        }

    @Test
    fun `updates delegate to the slice`() =
        runTest {
            repository.updateNotificationChannelEnabled(true)

            coVerify { eventChannelSettings.updateNotificationChannelEnabled(true) }
        }

    @Test
    fun `updateConnectorUrl delegates to the slice`() =
        runTest {
            repository.updateConnectorUrl("https://h/d/x/mcp")

            coVerify { connectorUrlSettings.updateConnectorUrl("https://h/d/x/mcp") }
        }
}
