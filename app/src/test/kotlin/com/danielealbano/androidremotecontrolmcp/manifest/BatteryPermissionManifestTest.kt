package com.danielealbano.androidremotecontrolmcp.manifest

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards against `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` returning to the manifest.
 *
 * The app deliberately opens the battery-optimization settings list
 * ([com.danielealbano.androidremotecontrolmcp.services.power.BatteryOptimizationManagerImpl]) rather
 * than requesting the one-tap system exemption dialog, which needs this permission: Google Play
 * restricts it to a narrow set of use cases and F-Droid flags it outright. (Before 2026-09-25 the
 * app had a `gms` flavour that declared it and a `foss` flavour that didn't; the flavours were
 * merged using the `foss` behaviour, so no build should declare it any more.) This test parses the
 * checked-in manifest directly — no Robolectric, no device — so a re-added declaration fails the
 * build instead of surfacing as a Play/F-Droid rejection.
 */
@DisplayName("Battery-optimization permission")
class BatteryPermissionManifestTest {
    @Test
    fun `main manifest does not declare REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`() {
        assertFalse(BATTERY_PERMISSION in usesPermissionsIn(MAIN_MANIFEST)) {
            "$BATTERY_PERMISSION must not be declared — F-Droid flags it and Play restricts it; " +
                "BatteryOptimizationManagerImpl deliberately avoids it."
        }
    }

    /** Returns the `android:name` of every `<uses-permission>` in the manifest. */
    private fun usesPermissionsIn(relativePath: String): Set<String> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                .newDocumentBuilder()
                .parse(resolveManifest(relativePath))

        val nodes = document.getElementsByTagName("uses-permission")
        return (0 until nodes.length)
            .map { index -> (nodes.item(index) as Element).getAttribute("android:name") }
            .toSet()
    }

    /** Unit tests run with the module directory as CWD; fall back to the repository root. */
    private fun resolveManifest(relativePath: String): File =
        listOf(File(relativePath), File("app", relativePath)).firstOrNull { it.isFile }
            ?: error("Manifest not found: $relativePath (cwd=${File(".").absolutePath})")

    private companion object {
        const val MAIN_MANIFEST = "src/main/AndroidManifest.xml"
        const val BATTERY_PERMISSION = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    }
}
