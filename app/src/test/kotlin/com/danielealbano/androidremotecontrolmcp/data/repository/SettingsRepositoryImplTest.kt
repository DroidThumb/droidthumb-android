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
    private val repository = SettingsRepositoryImpl(eventChannelSettings)

    @Test
    fun `eventChannelConfig is the slice's flow`() =
        runTest {
            val config = EventChannelConfig(enabled = true, endpointUrl = "http://localhost:9090")
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
            repository.updateEventChannelEndpointUrl("http://localhost:9090")
            repository.updateNotificationChannelEnabled(true)

            coVerify { eventChannelSettings.updateEventChannelEndpointUrl("http://localhost:9090") }
            coVerify { eventChannelSettings.updateNotificationChannelEnabled(true) }
        }
}
