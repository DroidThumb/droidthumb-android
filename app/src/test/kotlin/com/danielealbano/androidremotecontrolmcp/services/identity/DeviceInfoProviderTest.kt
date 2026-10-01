package com.danielealbano.androidremotecontrolmcp.services.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeviceInfoProviderTest {
    @Test
    fun `formats manufacturer and model with a space`() {
        assertEquals("Google Pixel 8", formatDeviceModel("Google", "Pixel 8"))
    }

    @Test
    fun `trims only the outer whitespace, not internal spacing`() {
        // trim() only strips leading/trailing whitespace from the combined string — it doesn't
        // collapse the extra spaces contributed by each already-padded input plus the template's
        // own separator space.
        assertEquals("Google   Pixel 8", formatDeviceModel(" Google ", " Pixel 8 "))
    }

    @Test
    fun `truncates to 128 chars`() {
        val longModel = "M".repeat(200)
        val result = formatDeviceModel("Manufacturer", longModel)
        assertEquals(MAX_DEVICE_MODEL_LENGTH, result.length)
    }
}
