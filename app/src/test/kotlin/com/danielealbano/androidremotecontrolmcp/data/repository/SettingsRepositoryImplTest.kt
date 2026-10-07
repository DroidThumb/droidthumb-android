package com.danielealbano.androidremotecontrolmcp.data.repository

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * [SettingsRepositoryImpl] holds no settings of its own: every member delegates to its feature
 * slice (each covered by its own slice test, e.g. ConnectorUrlSettingsTest).
 */
class SettingsRepositoryImplTest {
    private val transportSettings = mockk<TransportSettings>(relaxed = true)
    private val connectorUrlSettings = mockk<ConnectorUrlSettings>(relaxed = true)
    private val accountSettings = mockk<AccountSettings>(relaxed = true)
    private val pauseSettings = mockk<PauseSettings>(relaxed = true)
    private val repository =
        SettingsRepositoryImpl(
            transportSettings,
            connectorUrlSettings,
            accountSettings,
            pauseSettings,
        )

    @Test
    fun `updateConnectorUrl delegates to the slice`() =
        runTest {
            repository.updateConnectorUrl("https://h/d/x/mcp")

            coVerify { connectorUrlSettings.updateConnectorUrl("https://h/d/x/mcp") }
        }
}
