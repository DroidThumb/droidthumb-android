package com.danielealbano.androidremotecontrolmcp.services.controlbar

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("stepCaption")
class StepCaptionTest {
    private val identityLabel: (String) -> String = { it }

    @Test
    fun `tap with a text selector captions Tapping the text`() {
        val params = tapParams(buildJsonObject { put("text", "Timer") })
        assertEquals("Tapping \"Timer\"…", stepCaption("tap", params, identityLabel))
    }

    @Test
    fun `tap with content_desc only falls back to content_desc`() {
        val params = tapParams(buildJsonObject { put("content_desc", "Settings") })
        assertEquals("Tapping \"Settings\"…", stepCaption("tap", params, identityLabel))
    }

    @Test
    fun `tap with resource_id only falls back to resource_id`() {
        val params = tapParams(buildJsonObject { put("resource_id", "com.app:id/x") })
        assertEquals("Tapping \"com.app:id/x\"…", stepCaption("tap", params, identityLabel))
    }

    @Test
    fun `tap with at-only (no selector) captions Tapping the screen`() {
        val params =
            buildJsonObject {
                put(
                    "at",
                    buildJsonObject {
                        put("x", 10)
                        put("y", 20)
                    },
                )
            }
        assertEquals("Tapping the screen…", stepCaption("tap", params, identityLabel))
    }

    @Test
    fun `type_text captions the typed text`() {
        val params = buildJsonObject { put("text", "hello") }
        assertEquals("Typing \"hello\"…", stepCaption("type_text", params, identityLabel))
    }

    @Test
    fun `type_text truncates text past 24 characters`() {
        val longText = "a".repeat(30)
        val params = buildJsonObject { put("text", longText) }
        val caption = stepCaption("type_text", params, identityLabel)
        assertEquals("Typing \"${"a".repeat(24)}…\"…", caption)
    }

    @Test
    fun `type_text with clear true captions Clearing the field`() {
        val params = buildJsonObject { put("clear", true) }
        assertEquals("Clearing the field…", stepCaption("type_text", params, identityLabel))
    }

    @ParameterizedTest
    @CsvSource(
        "back, Pressing back…",
        "home, Going home…",
        "recents, Viewing recent apps…",
        "dismiss_keyboard, Dismissing the keyboard…",
    )
    fun `key maps each known key to its own caption`(
        key: String,
        expected: String,
    ) {
        val params = buildJsonObject { put("key", key) }
        assertEquals(expected, stepCaption("key", params, identityLabel))
    }

    @Test
    fun `launch_app captions Opening the resolved label, not the raw package`() {
        val params = buildJsonObject { put("package", "com.android.deskclock") }
        val caption = stepCaption("launch_app", params) { "Clock" }
        assertEquals("Opening Clock…", caption)
    }

    @Test
    fun `wait_until captions around the selector label`() {
        val params =
            buildJsonObject {
                put("selector", buildJsonObject { put("text", "Chats") })
                put("timeout_ms", 5000)
            }
        assertEquals("Waiting for \"Chats\"…", stepCaption("wait_until", params, identityLabel))
    }

    @Test
    fun `scroll_find captions around the selector label`() {
        val params = tapParams(buildJsonObject { put("text", "Submit") })
        assertEquals("Looking for \"Submit\"…", stepCaption("scroll_find", params, identityLabel))
    }

    @Test
    fun `an unknown op captions Working`() {
        assertEquals("Working…", stepCaption("frobnicate", JsonObject(emptyMap()), identityLabel))
    }

    private fun tapParams(selector: JsonObject): JsonObject = buildJsonObject { put("selector", selector) }
}
