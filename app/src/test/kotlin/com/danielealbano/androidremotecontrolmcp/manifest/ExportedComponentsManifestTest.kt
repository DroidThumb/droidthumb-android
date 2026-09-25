package com.danielealbano.androidremotecontrolmcp.manifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the exported-component surface declared in the main manifest.
 *
 * An exported component with no `android:permission` is reachable by every app installed on the
 * device, with no permission of its own. Upstream's ADB-facing configuration surface was exactly
 * this class of problem (GHSA-v82h-m32h-3j39); the app now has no on-device server and no
 * exported component that can reconfigure it (design doc SEC-19).
 *
 * These tests parse the checked-in manifest directly — no Robolectric, no device — so a component
 * exported by mistake fails the build rather than shipping.
 */
@DisplayName("Exported components")
class ExportedComponentsManifestTest {
    @Test
    fun `every exported component in the main manifest is gated or intentionally public`() {
        val offenders =
            componentsIn(MAIN_MANIFEST)
                .filter { it.exported && it.permission == null }
                .map { it.name }
                .filterNot { it in INTENTIONALLY_PUBLIC }

        assertTrue(offenders.isEmpty()) {
            "Exported components in $MAIN_MANIFEST without android:permission: $offenders. " +
                "Either gate the component or, if it must be reachable by any app, add it to " +
                "INTENTIONALLY_PUBLIC with a rationale."
        }
    }

    @Test
    fun `the launcher activity is the only exported component`() {
        val exported = componentsIn(MAIN_MANIFEST).filter { it.exported }.map { it.name }

        assertEquals(listOf(".ui.MainActivity"), exported) {
            "Only the launcher MainActivity may be exported (SEC-19: no exported components that " +
                "change app behaviour). Found: $exported"
        }
    }

    @Test
    fun `EventChannelService declares the specialUse foreground-service type it starts with`() {
        // EventChannelService calls startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE); a
        // mismatch with the manifest crashes at startForeground, so pin both halves here.
        val service =
            elementsIn(MAIN_MANIFEST, "service")
                .single { it.getAttribute("android:name") == ".services.channel.EventChannelService" }
        assertEquals("specialUse", service.getAttribute("android:foregroundServiceType"))

        val properties = service.getElementsByTagName("property")
        val subtype =
            (0 until properties.length)
                .map { properties.item(it) as Element }
                .singleOrNull { it.getAttribute("android:name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" }
        assertTrue(subtype != null && subtype.getAttribute("android:value").isNotBlank()) {
            "EventChannelService must declare a non-empty PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        }
        assertTrue(permissionsIn(MAIN_MANIFEST).contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
    }

    private fun parse(relativePath: String) =
        DocumentBuilderFactory
            .newInstance()
            .apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            .newDocumentBuilder()
            .parse(resolveManifest(relativePath))

    private fun elementsIn(
        relativePath: String,
        tag: String,
    ): List<Element> {
        val nodes = parse(relativePath).getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun permissionsIn(relativePath: String): List<String> =
        elementsIn(relativePath, "uses-permission").map { it.getAttribute("android:name") }

    private fun componentsIn(relativePath: String): List<Component> {
        val document = parse(relativePath)

        return COMPONENT_TAGS.flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { index ->
                val element = nodes.item(index) as Element
                Component(
                    name = element.getAttribute("android:name"),
                    exported = element.getAttribute("android:exported") == "true",
                    permission = element.getAttribute("android:permission").ifEmpty { null },
                )
            }
        }
    }

    /** Unit tests run with the module directory as CWD; fall back to the repository root. */
    private fun resolveManifest(relativePath: String): File =
        listOf(File(relativePath), File("app", relativePath)).firstOrNull { it.isFile }
            ?: error("Manifest not found: $relativePath (cwd=${File(".").absolutePath})")

    private data class Component(
        val name: String,
        val exported: Boolean,
        val permission: String?,
    )

    private companion object {
        const val MAIN_MANIFEST = "src/main/AndroidManifest.xml"

        val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")

        /** Components that must stay reachable by any caller: `MainActivity` is the LAUNCHER entry point. */
        val INTENTIONALLY_PUBLIC = setOf(".ui.MainActivity")
    }
}
