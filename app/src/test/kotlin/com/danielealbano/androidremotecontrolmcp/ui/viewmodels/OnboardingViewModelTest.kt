package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.power.BatteryOptimizationManager
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OnboardingViewModel")
class OnboardingViewModelTest {
    private val batteryOptimizationManager = mockk<BatteryOptimizationManager>()
    private val context = mockk<Context>(relaxed = true)
    private lateinit var viewModel: OnboardingViewModel

    @BeforeEach
    fun setup() {
        mockkObject(PermissionUtils)
        viewModel = OnboardingViewModel(batteryOptimizationManager)
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
    fun `shows restricted settings first when accessibility is off and nothing acknowledged yet`() {
        stubAccessibility(false)
        every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns false

        viewModel.refresh(context, accountId = null)

        assertEquals(OnboardingStep.RESTRICTED_SETTINGS, viewModel.step.value)
    }

    @Test
    fun `acknowledging restricted settings moves to accessibility while it's still off`() {
        stubAccessibility(false)
        every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns false

        viewModel.acknowledgeRestrictedSettings(context, accountId = null)

        assertEquals(OnboardingStep.ACCESSIBILITY, viewModel.step.value)
    }

    @Test
    fun `an already-enabled accessibility service skips both restricted settings and accessibility`() {
        stubAccessibility(true)
        every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns false

        viewModel.refresh(context, accountId = null)

        assertEquals(OnboardingStep.BATTERY, viewModel.step.value)
    }

    @Test
    fun `an already-ignored battery optimization skips straight to sign-in`() {
        stubAccessibility(true)
        every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns true

        viewModel.refresh(context, accountId = null)

        assertEquals(OnboardingStep.GOOGLE_SIGN_IN, viewModel.step.value)
    }

    @Test
    fun `an already-claimed account (restored from a previous session) skips straight to done`() {
        stubAccessibility(true)
        every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns true

        viewModel.refresh(context, accountId = "acc_1")

        assertEquals(OnboardingStep.DONE, viewModel.step.value)
    }

    @Test
    fun `skipSignIn moves to done without an account, and a later refresh does not bounce back`() {
        stubAccessibility(true)
        every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns true

        viewModel.skipSignIn()
        assertEquals(OnboardingStep.DONE, viewModel.step.value)

        viewModel.refresh(context, accountId = null)
        assertEquals(OnboardingStep.DONE, viewModel.step.value)
    }

    @Test
    fun `markSignedIn moves to done`() {
        viewModel.markSignedIn()

        assertEquals(OnboardingStep.DONE, viewModel.step.value)
    }
}
