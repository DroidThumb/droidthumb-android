@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.integration

import android.view.inputmethod.SurroundingText
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolContent
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Text Input Integration Tests")
class TextInputIntegrationTest {
    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "node_edit",
                        className = "android.widget.EditText",
                        text = "",
                        bounds = BoundsData(50, 800, 500, 900),
                        editable = true,
                        focusable = true,
                        enabled = true,
                        visible = true,
                    ),
                ),
        )

    private val sampleScreenInfo =
        ScreenInfo(
            width = 1080,
            height = 2400,
            densityDpi = 420,
            orientation = "portrait",
        )

    private fun createMockSurroundingText(
        text: String,
        offset: Int = 0,
    ): SurroundingText {
        val mock = mockk<SurroundingText>()
        every { mock.text } returns text
        every { mock.offset } returns offset
        every { mock.selectionStart } returns text.length
        every { mock.selectionEnd } returns text.length
        return mock
    }

    @BeforeEach
    fun setUp() {
        HandlerTestHarness.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        HandlerTestHarness.unmockAndroidLog()
    }

    @Test
    fun `type_append_text with node_id returns success with field content`() =
        runTest {
            val deps = HandlerTestHarness.createMockDependencies()
            HandlerTestHarness.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            coEvery {
                deps.actionExecutor.clickNode("node_edit", any<List<WindowData>>())
            } returns Result.success(Unit)

            // Explicitly configure TypeInputController mock returns
            every { deps.typeInputController.isReady() } returns true
            every { deps.typeInputController.setSelection(any(), any()) } returns true

            // General getSurroundingText: field-content reads (registered first, lower priority)
            val beforeText = createMockSurroundingText("existing")
            val afterText = createMockSurroundingText("existingHello")
            every {
                deps.typeInputController.getSurroundingText(any(), any(), any())
            } returnsMany listOf(beforeText, beforeText, afterText)

            // Verification mocks: dynamic answers for per-character verification (registered after, higher priority)
            var lastCommittedText = ""
            every { deps.typeInputController.commitText(any(), any()) } answers {
                lastCommittedText = firstArg<String>()
                true
            }
            every {
                deps.typeInputController.getSurroundingText(any(), eq(0), eq(0))
            } answers {
                createMockSurroundingText(lastCommittedText)
            }

            HandlerTestHarness.withTools(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_type_append_text",
                        arguments = mapOf("node_id" to "node_edit", "text" to "Hello"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as ToolContent.Text).text
                assertTrue(text.contains("Typed 5 characters"))
                assertTrue(text.contains("Field content:"))
            }

            // Verify mock interaction: cursor positioned at end of existing text
            verify { deps.typeInputController.setSelection(8, 8) }
        }

    @Test
    fun `type_append_text with missing text returns error`() =
        runTest {
            HandlerTestHarness.withTools { client, _ ->
                val result =
                    client.callTool(
                        name = "android_type_append_text",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as ToolContent.Text).text
                assertTrue(text.isNotEmpty())
            }
        }

    @Test
    fun `type_insert_text with valid offset returns success with field content`() =
        runTest {
            val deps = HandlerTestHarness.createMockDependencies()
            HandlerTestHarness.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            coEvery {
                deps.actionExecutor.clickNode("node_edit", any<List<WindowData>>())
            } returns Result.success(Unit)

            every { deps.typeInputController.isReady() } returns true
            every { deps.typeInputController.setSelection(any(), any()) } returns true

            // General getSurroundingText: field-content reads (registered first, lower priority)
            val beforeText = createMockSurroundingText("Hello")
            val afterText = createMockSurroundingText("Hel Worldlo")
            every {
                deps.typeInputController.getSurroundingText(any(), any(), any())
            } returnsMany listOf(beforeText, beforeText, afterText)

            // Verification mocks (registered after, higher priority)
            var lastCommittedText = ""
            every { deps.typeInputController.commitText(any(), any()) } answers {
                lastCommittedText = firstArg<String>()
                true
            }
            every {
                deps.typeInputController.getSurroundingText(any(), eq(0), eq(0))
            } answers {
                createMockSurroundingText(lastCommittedText)
            }

            HandlerTestHarness.withTools(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_type_insert_text",
                        arguments =
                            mapOf(
                                "node_id" to "node_edit",
                                "text" to " World",
                                "offset" to 3,
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as ToolContent.Text).text
                assertTrue(text.contains("Field content:"))
            }

            // Verify mock interaction: cursor positioned at specified offset
            verify { deps.typeInputController.setSelection(3, 3) }
        }

    @Test
    fun `type_replace_text with found search text returns success with field content`() =
        runTest {
            val deps = HandlerTestHarness.createMockDependencies()
            HandlerTestHarness.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            coEvery {
                deps.actionExecutor.clickNode("node_edit", any<List<WindowData>>())
            } returns Result.success(Unit)

            every { deps.typeInputController.isReady() } returns true
            every { deps.typeInputController.setSelection(any(), any()) } returns true
            every { deps.typeInputController.sendKeyEvent(any()) } returns true

            // General getSurroundingText: field-content reads (registered first, lower priority)
            val beforeText = createMockSurroundingText("Hello World")
            val afterText = createMockSurroundingText("Goodbye World")
            every {
                deps.typeInputController.getSurroundingText(any(), any(), any())
            } returnsMany listOf(beforeText, beforeText, afterText)

            // Verification mocks (registered after, higher priority)
            var lastCommittedText = ""
            every { deps.typeInputController.commitText(any(), any()) } answers {
                lastCommittedText = firstArg<String>()
                true
            }
            every {
                deps.typeInputController.getSurroundingText(any(), eq(0), eq(0))
            } answers {
                createMockSurroundingText(lastCommittedText)
            }

            HandlerTestHarness.withTools(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_type_replace_text",
                        arguments =
                            mapOf(
                                "node_id" to "node_edit",
                                "search" to "Hello",
                                "new_text" to "Goodbye",
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as ToolContent.Text).text
                assertTrue(text.contains("Field content:"))
            }

            // Verify mock interaction: search text "Hello" selected (indices 0..5)
            verify { deps.typeInputController.setSelection(0, 5) }
        }

    @Test
    fun `type_replace_text with missing search text returns error`() =
        runTest {
            val deps = HandlerTestHarness.createMockDependencies()
            HandlerTestHarness.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            coEvery {
                deps.actionExecutor.clickNode("node_edit", any<List<WindowData>>())
            } returns Result.success(Unit)

            every { deps.typeInputController.isReady() } returns true
            val beforeText = createMockSurroundingText("Something")
            every {
                deps.typeInputController.getSurroundingText(any(), any(), any())
            } returns beforeText

            HandlerTestHarness.withTools(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_type_replace_text",
                        arguments =
                            mapOf(
                                "node_id" to "node_edit",
                                "search" to "NotFound",
                                "new_text" to "X",
                            ),
                    )
                assertEquals(true, result.isError)
            }
        }

    @Test
    fun `type_clear_text returns success with field content`() =
        runTest {
            val deps = HandlerTestHarness.createMockDependencies()
            HandlerTestHarness.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            coEvery {
                deps.actionExecutor.clickNode("node_edit", any<List<WindowData>>())
            } returns Result.success(Unit)

            every { deps.typeInputController.isReady() } returns true
            every { deps.typeInputController.performContextMenuAction(any()) } returns true
            every { deps.typeInputController.sendKeyEvent(any()) } returns true

            val beforeText = createMockSurroundingText("Hello")
            val afterText = createMockSurroundingText("")
            every {
                deps.typeInputController.getSurroundingText(any(), any(), any())
            } returnsMany listOf(beforeText, afterText)

            HandlerTestHarness.withTools(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_type_clear_text",
                        arguments = mapOf("node_id" to "node_edit"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as ToolContent.Text).text
                assertTrue(text.contains("Field content:"))
            }

            // Verify mock interaction: select all was called
            verify { deps.typeInputController.performContextMenuAction(android.R.id.selectAll) }
        }

    @Test
    fun `press_key still works after tool changes`() =
        runTest {
            val deps = HandlerTestHarness.createMockDependencies()
            coEvery { deps.actionExecutor.pressBack() } returns Result.success(Unit)

            HandlerTestHarness.withTools(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_press_key",
                        arguments = mapOf("key" to "BACK"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as ToolContent.Text).text
                assertTrue(text.contains("BACK"))
            }
        }
}
