package com.danielealbano.androidremotecontrolmcp.services.power

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("OemBrand")
class OemBrandTest {
    @ParameterizedTest
    @CsvSource(
        "Xiaomi, XIAOMI",
        "Redmi, XIAOMI",
        "POCO, XIAOMI",
        "OnePlus, ONEPLUS_OPPO_REALME",
        "OPPO, ONEPLUS_OPPO_REALME",
        "realme, ONEPLUS_OPPO_REALME",
        "vivo, VIVO",
        "iQOO, VIVO",
        "HUAWEI, HUAWEI_HONOR",
        "HONOR, HUAWEI_HONOR",
        "samsung, SAMSUNG",
        "Google, OTHER",
        "motorola, OTHER",
    )
    fun `detectOemBrand classifies manufacturer strings case-insensitively`(
        manufacturer: String,
        expected: String,
    ) {
        assertEquals(OemBrand.valueOf(expected), detectOemBrand(manufacturer))
    }

    @Test
    fun `OTHER has no settings intent or guidance text - no OEM-specific escape hatch to offer`() {
        assertNull(oemSettingsIntent(OemBrand.OTHER))
        assertNull(oemGuidanceText(OemBrand.OTHER))
    }

    @Test
    fun `every non-OTHER brand has both a settings intent and guidance text`() {
        for (brand in OemBrand.entries.filterNot { it == OemBrand.OTHER }) {
            assertNotNull(oemSettingsIntent(brand), "expected a settings intent for $brand")
            assertNotNull(oemGuidanceText(brand), "expected guidance text for $brand")
        }
    }
}
