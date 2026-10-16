package com.danielealbano.androidremotecontrolmcp.ui.screens

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HomeScreen pause-permission button visibility")
class HomeScreenTest {
    @Test
    fun `button shows while the accessibility service is enabled`() {
        assertTrue(shouldShowPausePermissionButton(isAccessibilityEnabled = true))
    }

    @Test
    fun `button is hidden once the service is disabled - the warning card takes over`() {
        assertFalse(shouldShowPausePermissionButton(isAccessibilityEnabled = false))
    }
}
