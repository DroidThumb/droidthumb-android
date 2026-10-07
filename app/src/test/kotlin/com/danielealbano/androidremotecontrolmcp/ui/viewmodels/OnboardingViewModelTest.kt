package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.power.BatteryOptimizationManager
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportAutoStart
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("OnboardingViewModel")
class OnboardingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val batteryOptimizationManager = mockk<BatteryOptimizationManager>()
    private val transportAutoStart = mockk<TransportAutoStart>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var viewModel: OnboardingViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(PermissionUtils)
        coEvery { transportAutoStart.maybeStart(context) } returns Unit
        viewModel = OnboardingViewModel(batteryOptimizationManager, transportAutoStart)
    }

    @AfterEach
    fun teardown() {
        unmockkObject(PermissionUtils)
        Dispatchers.resetMain()
    }

    private fun stubAccessibility(enabled: Boolean) {
        every {
            PermissionUtils.isAccessibilityServiceEnabled(context, McpAccessibilityService::class.java)
        } returns enabled
    }

    @Test
    fun `shows accessibility first while it's off`() {
        stubAccessibility(false)
        every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns false

        viewModel.refresh(context, accountId = null)

        assertEquals(OnboardingStep.ACCESSIBILITY, viewModel.step.value)
    }

    @Test
    fun `accessibility being off does not auto-start the transport`() =
        runTest(testDispatcher) {
            stubAccessibility(false)
            every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns false

            viewModel.refresh(context, accountId = null)
            advanceUntilIdle()

            coVerify(exactly = 0) { transportAutoStart.maybeStart(context) }
        }

    @Test
    fun `an already-enabled accessibility service skips straight to battery`() {
        stubAccessibility(true)
        every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns false

        viewModel.refresh(context, accountId = null)

        assertEquals(OnboardingStep.BATTERY, viewModel.step.value)
    }

    @Test
    fun `accessibility alone - regardless of account or battery - auto-starts the transport`() =
        runTest(testDispatcher) {
            stubAccessibility(true)
            every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns false

            viewModel.refresh(context, accountId = null)
            advanceUntilIdle()

            // The transport connects on device identity, not the account - claim_account is sent
            // *over* an already-connected transport, so this must NOT be gated on accountId (a
            // real regression found live: gating on accountId deadlocked sign-in entirely, since
            // claiming requires a connection that would never have been allowed to start).
            coVerify { transportAutoStart.maybeStart(context) }
        }

    @Test
    fun `an already-ignored battery optimization skips straight to sign-in`() {
        stubAccessibility(true)
        every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns true

        viewModel.refresh(context, accountId = null)

        assertEquals(OnboardingStep.GOOGLE_SIGN_IN, viewModel.step.value)
    }

    @Test
    fun `an already-claimed account (restored from a previous session) skips straight to done`() =
        runTest(testDispatcher) {
            stubAccessibility(true)
            every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns true

            viewModel.refresh(context, accountId = "acc_1")
            advanceUntilIdle()

            assertEquals(OnboardingStep.DONE, viewModel.step.value)
        }

    @Test
    fun `skipSignIn moves to done without an account, and a later refresh does not bounce back`() =
        runTest(testDispatcher) {
            stubAccessibility(true)
            every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns true

            viewModel.skipSignIn()
            assertEquals(OnboardingStep.DONE, viewModel.step.value)

            viewModel.refresh(context, accountId = null)
            advanceUntilIdle()
            assertEquals(OnboardingStep.DONE, viewModel.step.value)
        }

    @Test
    fun `markSignedIn moves to done`() {
        viewModel.markSignedIn()

        assertEquals(OnboardingStep.DONE, viewModel.step.value)
    }
}
