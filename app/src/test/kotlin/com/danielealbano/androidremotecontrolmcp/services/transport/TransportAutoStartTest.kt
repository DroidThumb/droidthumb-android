package com.danielealbano.androidremotecontrolmcp.services.transport

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TransportAutoStart")
class TransportAutoStartTest {
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val autoStart = TransportAutoStart(settingsRepository)

    @BeforeEach
    fun setup() {
        mockkObject(PermissionUtils)
    }

    @AfterEach
    fun teardown() {
        unmockkObject(PermissionUtils)
    }

    private fun stubAccessibility(enabled: Boolean) {
        every {
            PermissionUtils.isAccessibilityServiceEnabled(context, McpAccessibilityService::class.java)
        } returns enabled
    }

    @Test
    fun `starts on accessibility alone - no account required`() =
        runTest {
            // Gating this on an account would deadlock claiming entirely: claim_account is sent
            // *over* an already-connected transport, so the transport must be able to start
            // before any account exists (real regression, found live on a phone - see this
            // class's own doc comment).
            stubAccessibility(true)
            coEvery { settingsRepository.getPauseState() } returns PauseState()

            autoStart.maybeStart(context)

            coVerify { settingsRepository.updateTransportEnabled(true) }
        }

    @Test
    fun `does not start when accessibility is off`() =
        runTest {
            stubAccessibility(false)
            coEvery { settingsRepository.getPauseState() } returns PauseState()

            autoStart.maybeStart(context)

            coVerify(exactly = 0) { settingsRepository.updateTransportEnabled(any()) }
        }

    @Test
    fun `does not start while effectively paused`() =
        runTest {
            stubAccessibility(true)
            coEvery { settingsRepository.getPauseState() } returns
                PauseState(isPaused = true, resumeAtEpochMs = null)

            autoStart.maybeStart(context)

            coVerify(exactly = 0) { settingsRepository.updateTransportEnabled(any()) }
        }

    @Test
    fun `starts once an expired timed pause is no longer effective`() =
        runTest {
            stubAccessibility(true)
            coEvery { settingsRepository.getPauseState() } returns
                PauseState(isPaused = true, resumeAtEpochMs = System.currentTimeMillis() - 1)

            autoStart.maybeStart(context)

            coVerify { settingsRepository.updateTransportEnabled(true) }
        }
}
