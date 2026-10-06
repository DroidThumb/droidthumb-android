package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import com.danielealbano.androidremotecontrolmcp.data.model.TransportConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.transport.DeviceTransportClient
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportAutoStart
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("TransportViewModel")
class TransportViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val transportClient = mockk<DeviceTransportClient>(relaxed = true)
    private val transportAutoStart = mockk<TransportAutoStart>(relaxed = true)
    private val appContext = mockk<Context>(relaxed = true)

    private val transportConfigFlow = MutableStateFlow(TransportConfig())
    private val connectorUrlFlow = MutableStateFlow<String?>(null)
    private val pauseStateFlow = MutableStateFlow(PauseState())

    private lateinit var viewModel: TransportViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepository.transportConfig } returns transportConfigFlow
        every { settingsRepository.connectorUrl } returns connectorUrlFlow
        every { settingsRepository.pauseState } returns pauseStateFlow
        coEvery { settingsRepository.getTransportConfig() } answers { transportConfigFlow.value }
        viewModel =
            TransportViewModel(settingsRepository, transportClient, transportAutoStart, appContext, testDispatcher)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Nested
    @DisplayName("tls")
    inner class Tls {
        @Test
        fun `updateTls persists to repository and updates tlsInput`() =
            runTest {
                advanceUntilIdle()
                viewModel.updateTls(true)
                advanceUntilIdle()
                assertEquals(true, viewModel.tlsInput.value)
                coVerify { settingsRepository.updateTransportTls(true) }
            }
    }

    @Nested
    @DisplayName("connector url")
    inner class ConnectorUrl {
        @Test
        fun `connectorUrl reflects the repository's flow`() =
            runTest {
                viewModel.connectorUrl.test {
                    assertEquals(null, awaitItem())
                    connectorUrlFlow.value = "https://h/d/x/mcp"
                    assertEquals("https://h/d/x/mcp", awaitItem())
                    cancelAndIgnoreRemainingEvents()
                }
            }
    }

    @Nested
    @DisplayName("regenerate secret")
    inner class RegenerateSecretTests {
        @Test
        fun `regenerateSecret goes IN_PROGRESS then SUCCEEDED on true`() =
            runTest {
                coEvery { transportClient.regenerateSecret() } returns true
                advanceUntilIdle()

                viewModel.regenerateSecret()
                assertEquals(TransportViewModel.RegenerateSecretState.IN_PROGRESS, viewModel.regenerateState.value)

                advanceUntilIdle()
                assertEquals(TransportViewModel.RegenerateSecretState.SUCCEEDED, viewModel.regenerateState.value)
            }

        @Test
        fun `regenerateSecret goes IN_PROGRESS then TIMED_OUT on false`() =
            runTest {
                coEvery { transportClient.regenerateSecret() } returns false
                advanceUntilIdle()

                viewModel.regenerateSecret()
                advanceUntilIdle()
                assertEquals(TransportViewModel.RegenerateSecretState.TIMED_OUT, viewModel.regenerateState.value)
            }
    }

    @Nested
    @DisplayName("pause")
    inner class Pause {
        @Test
        fun `pauseFor1Hour persists a resumeAt roughly one hour out`() =
            runTest {
                val before = System.currentTimeMillis()
                viewModel.pauseFor1Hour()
                advanceUntilIdle()

                coVerify {
                    settingsRepository.pauseUntil(
                        match { it in (before + ONE_HOUR_MS)..(before + ONE_HOUR_MS + SLACK_MS) },
                    )
                }
            }

        @Test
        fun `pauseIndefinitely persists a null resumeAt`() =
            runTest {
                viewModel.pauseIndefinitely()
                advanceUntilIdle()

                coVerify { settingsRepository.pauseUntil(null) }
            }

        @Test
        fun `resume delegates to the repository and re-runs the auto-start check`() =
            runTest {
                viewModel.resume()
                advanceUntilIdle()

                coVerify { settingsRepository.resume() }
                coVerify { transportAutoStart.maybeStart(appContext) }
            }
    }

    private companion object {
        private const val ONE_HOUR_MS = 60 * 60 * 1000L
        private const val SLACK_MS = 5_000L
    }
}
