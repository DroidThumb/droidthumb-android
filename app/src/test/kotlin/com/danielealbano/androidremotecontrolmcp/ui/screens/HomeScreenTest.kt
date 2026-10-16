package com.danielealbano.androidremotecontrolmcp.ui.screens

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HomeScreen pause-permission button and battery warning card visibility")
class HomeScreenTest {
    @Test
    fun `button shows while the accessibility service is enabled`() {
        assertTrue(shouldShowPausePermissionButton(isAccessibilityEnabled = true))
    }

    @Test
    fun `button is hidden once the service is disabled - the warning card takes over`() {
        assertFalse(shouldShowPausePermissionButton(isAccessibilityEnabled = false))
    }

    @Test
    fun `battery card shows when the exemption is missing`() {
        assertTrue(
            shouldShowBatteryWarningCard(
                isBatteryOptimizationIgnored = false,
                isBackgroundRestricted = false,
            ),
        )
    }

    @Test
    fun `battery card shows when background is restricted even if the exemption is granted`() {
        assertTrue(
            shouldShowBatteryWarningCard(
                isBatteryOptimizationIgnored = true,
                isBackgroundRestricted = true,
            ),
        )
    }

    @Test
    fun `battery card hides when exempt and not background-restricted`() {
        assertFalse(
            shouldShowBatteryWarningCard(
                isBatteryOptimizationIgnored = true,
                isBackgroundRestricted = false,
            ),
        )
    }
}
