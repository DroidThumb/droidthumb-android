package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.services.power.BatteryOptimizationManager
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MainViewModelTest {
    private lateinit var batteryOptimizationManager: BatteryOptimizationManager
    private lateinit var viewModel: MainViewModel
    private val context = mockk<Context>()

    @BeforeEach
    fun setUp() {
        batteryOptimizationManager = mockk(relaxed = true)
        viewModel = MainViewModel(batteryOptimizationManager)
        mockkObject(PermissionUtils)
        every { PermissionUtils.isAccessibilityServiceEnabled(context, any()) } returns false
        every { PermissionUtils.isNotificationPermissionGranted(context) } returns false
        every { PermissionUtils.isNotificationListenerEnabled(context, any()) } returns false
        every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns false
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(PermissionUtils)
    }

    @Test
    fun `refreshPermissionStatus updates isAccessibilityEnabled`() {
        every { PermissionUtils.isAccessibilityServiceEnabled(context, any()) } returns true

        viewModel.refreshPermissionStatus(context)

        assertEquals(true, viewModel.isAccessibilityEnabled.value)
    }

    @Test
    fun `refreshPermissionStatus updates isNotificationPermissionGranted`() {
        every { PermissionUtils.isNotificationPermissionGranted(context) } returns true

        viewModel.refreshPermissionStatus(context)

        assertEquals(true, viewModel.isNotificationPermissionGranted.value)
    }

    @Test
    fun `refreshPermissionStatus updates isNotificationListenerEnabled when service is enabled`() {
        every { PermissionUtils.isNotificationListenerEnabled(context, any()) } returns true

        viewModel.refreshPermissionStatus(context)

        assertEquals(true, viewModel.isNotificationListenerEnabled.value)
    }

    @Test
    fun `refreshPermissionStatus updates isNotificationListenerEnabled when service is not enabled`() {
        viewModel.refreshPermissionStatus(context)

        assertEquals(false, viewModel.isNotificationListenerEnabled.value)
    }

    @Test
    fun `refreshPermissionStatus reflects not-exempt`() {
        viewModel.refreshPermissionStatus(context)

        assertEquals(false, viewModel.isBatteryOptimizationIgnored.value)
    }

    @Test
    fun `refreshPermissionStatus reflects exempt after grant`() {
        viewModel.refreshPermissionStatus(context)
        assertEquals(false, viewModel.isBatteryOptimizationIgnored.value)

        every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns true
        viewModel.refreshPermissionStatus(context)
        assertEquals(true, viewModel.isBatteryOptimizationIgnored.value)
    }

    @Test
    fun `requestBatteryOptimizationExemption delegates to manager`() {
        viewModel.requestBatteryOptimizationExemption()

        verify { batteryOptimizationManager.requestExemption() }
    }
}
