package com.danielealbano.androidremotecontrolmcp.wireprotocol

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ClickNodeTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.DismissKeyboardHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.GetScreenStateHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.mcp.tools.OpenAppHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.PressBackHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.PressHomeHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.PressRecentsHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ScrollToNodeTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TapTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolContent
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolResult
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeAppendTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeClearTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.WaitForIdleTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.WaitForNodeTool
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class StepDispatcherTest {
    private lateinit var getScreenState: GetScreenStateHandler
    private lateinit var tapTool: TapTool
    private lateinit var clickNodeTool: ClickNodeTool
    private lateinit var typeAppendTextTool: TypeAppendTextTool
    private lateinit var typeClearTextTool: TypeClearTextTool
    private lateinit var scrollToNodeTool: ScrollToNodeTool
    private lateinit var pressBackHandler: PressBackHandler
    private lateinit var pressHomeHandler: PressHomeHandler
    private lateinit var pressRecentsHandler: PressRecentsHandler
    private lateinit var dismissKeyboardHandler: DismissKeyboardHandler
    private lateinit var openAppHandler: OpenAppHandler
    private lateinit var waitForNodeTool: WaitForNodeTool
    private lateinit var waitForIdleTool: WaitForIdleTool
    private lateinit var treeParser: AccessibilityTreeParser
    private lateinit var accessibilityServiceProvider: AccessibilityServiceProvider
    private lateinit var nodeCache: AccessibilityNodeCache
    private lateinit var dispatcher: StepDispatcher

    private val mockRootNode = mockk<AccessibilityNodeInfo>()
    private val mockWindowInfo = mockk<AccessibilityWindowInfo>()

    private val ok = ToolResult(content = listOf(ToolContent.Text("ok")))
    private val foundTrueJson = """{"found":true,"elapsedMs":1,"attempts":1,"node":{}}"""
    private val foundTrue =
        ToolResult(content = listOf(ToolContent.Text("${McpToolUtils.UNTRUSTED_CONTENT_WARNING}\n$foundTrueJson")))
    private val idleOk = ToolResult(content = listOf(ToolContent.Text("UI is idle")))

    @BeforeEach
    fun setUp() {
        getScreenState = mockk()
        tapTool = mockk()
        clickNodeTool = mockk()
        typeAppendTextTool = mockk()
        typeClearTextTool = mockk()
        scrollToNodeTool = mockk()
        pressBackHandler = mockk()
        pressHomeHandler = mockk()
        pressRecentsHandler = mockk()
        dismissKeyboardHandler = mockk()
        openAppHandler = mockk()
        waitForNodeTool = mockk()
        waitForIdleTool = mockk()
        treeParser = mockk()
        accessibilityServiceProvider = mockk()
        nodeCache = mockk(relaxed = true)
        dispatcher =
            StepDispatcher(
                getScreenState,
                tapTool,
                clickNodeTool,
                typeAppendTextTool,
                typeClearTextTool,
                scrollToNodeTool,
                pressBackHandler,
                pressHomeHandler,
                pressRecentsHandler,
                dismissKeyboardHandler,
                openAppHandler,
                waitForNodeTool,
                waitForIdleTool,
                treeParser,
                accessibilityServiceProvider,
                nodeCache,
            )

        // Auto-wait (before every selector-targeted action) and post-action idle-wait default to
        // "already there" / "already idle" so most tests don't need to think about them.
        coEvery { waitForNodeTool.execute(any()) } returns foundTrue
        coEvery { waitForIdleTool.execute(any()) } returns idleOk
    }

    private fun step(
        op: String,
        params: JsonObject = JsonObject(emptyMap()),
    ) = Step(stepId = "s1", op = op, params = params)

    private fun selectorParams(
        field: String = "resource_id",
        value: String = "com.app:id/x",
    ): JsonObject = buildJsonObject { put("selector", buildJsonObject { put(field, value) }) }

    @Test
    fun `read_screen without include_screenshot returns tree only`() =
        runTest {
            coEvery { getScreenState.execute(any()) } returns
                ToolResult(content = listOf(ToolContent.Text("tree text")))
            val result = dispatcher.dispatch(step("read_screen")) as StepResult
            val output = result.output!!.jsonObject
            assertEquals("tree text", output["tree"]!!.jsonPrimitive.content)
            assertTrue("screenshot" !in output)
            // read_screen is a query, not an action — no post-action idle wait.
            coVerify(exactly = 0) { waitForIdleTool.execute(any()) }
        }

    @Test
    fun `read_screen with include_screenshot returns tree and inline screenshot`() =
        runTest {
            coEvery { getScreenState.execute(any()) } returns
                ToolResult(
                    content =
                        listOf(
                            ToolContent.Text("tree text"),
                            ToolContent.Image(data = "YmFzZTY0", mimeType = "image/jpeg"),
                        ),
                )
            val params = buildJsonObject { put("include_screenshot", true) }
            val result = dispatcher.dispatch(step("read_screen", params)) as StepResult
            val screenshot = result.output!!.jsonObject["screenshot"]!!.jsonObject
            assertEquals("inline", screenshot["kind"]!!.jsonPrimitive.content)
            assertEquals("image/jpeg", screenshot["mime"]!!.jsonPrimitive.content)
            assertEquals("YmFzZTY0", screenshot["data"]!!.jsonPrimitive.content)
        }

    // ── tap ─────────────────────────────────────────────────────────────────

    @Test
    fun `tap with selector auto-waits then hands the raw selector to click_node`() =
        runTest {
            coEvery { clickNodeTool.execute(any()) } returns ok
            dispatcher.dispatch(step("tap", selectorParams()))
            coVerify { waitForNodeTool.execute(match { it["value"]?.jsonPrimitive?.content == "com.app:id/x" }) }
            coVerify {
                clickNodeTool.execute(
                    match {
                        it["selector"]
                            ?.jsonObject
                            ?.get("resource_id")
                            ?.jsonPrimitive
                            ?.content == "com.app:id/x"
                    },
                )
            }
            coVerify { waitForIdleTool.execute(any()) }
        }

    @Test
    fun `tap with selector never appearing fails without ever calling click_node`() =
        runTest {
            val timeoutJson = """{"found":false,"elapsedMs":5000,"attempts":10,"message":"timed out"}"""
            coEvery { waitForNodeTool.execute(any()) } returns
                ToolResult(
                    content = listOf(ToolContent.Text("${McpToolUtils.UNTRUSTED_CONTENT_WARNING}\n$timeoutJson")),
                )
            val result = dispatcher.dispatch(step("tap", selectorParams())) as StepError
            assertEquals("NodeNotFound", result.code)
            coVerify(exactly = 0) { clickNodeTool.execute(any()) }
        }

    @Test
    fun `tap with selector and at falls back to the coordinate when click_node reports NodeNotFound`() =
        runTest {
            coEvery { clickNodeTool.execute(any()) } throws McpToolException.NodeNotFound("stale")
            coEvery { tapTool.execute(any()) } returns ok
            val params =
                buildJsonObject {
                    put("selector", buildJsonObject { put("resource_id", "com.app:id/x") })
                    put(
                        "at",
                        buildJsonObject {
                            put("x", 10)
                            put("y", 20)
                        },
                    )
                }
            val result = dispatcher.dispatch(step("tap", params))
            assertTrue(result is StepResult)
            coVerify { tapTool.execute(match { it["x"]?.jsonPrimitive?.content == "10" }) }
        }

    @Test
    fun `tap with selector only (no at) propagates NodeNotFound instead of falling back`() =
        runTest {
            coEvery { clickNodeTool.execute(any()) } throws McpToolException.NodeNotFound("stale")
            val result = dispatcher.dispatch(step("tap", selectorParams())) as StepError
            assertEquals("NodeNotFound", result.code)
            coVerify(exactly = 0) { tapTool.execute(any()) }
        }

    @Test
    fun `tap with at taps the coordinate and no selector resolution or auto-wait happens`() =
        runTest {
            coEvery { accessibilityServiceProvider.isReady() } returns true
            coEvery { accessibilityServiceProvider.getAccessibilityWindows() } returns emptyList()
            coEvery { accessibilityServiceProvider.getRootNode() } returns null
            coEvery { tapTool.execute(any()) } returns ok
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
            dispatcher.dispatch(step("tap", params))
            coVerify(exactly = 0) { waitForNodeTool.execute(any()) }
            coVerify(exactly = 0) { clickNodeTool.execute(any()) }
            coVerify { tapTool.execute(any()) }
        }

    @Test
    fun `tap with at discovers and returns a selector for the node under the point`() =
        runTest {
            val tappedNode =
                AccessibilityNodeData(
                    id = "n1",
                    resourceId = "com.app:id/target",
                    className = "android.widget.Button",
                    bounds = BoundsData(0, 0, 100, 100),
                    visible = true,
                )
            setUpSingleWindowTree(tappedNode)
            coEvery { tapTool.execute(any()) } returns ok

            val params =
                buildJsonObject {
                    put(
                        "at",
                        buildJsonObject {
                            put("x", 10)
                            put("y", 10)
                        },
                    )
                }
            val result = dispatcher.dispatch(step("tap", params)) as StepResult

            val selector = result.output!!.jsonObject["selector"]!!.jsonObject
            assertEquals("com.app:id/target", selector["resource_id"]!!.jsonPrimitive.content)
        }

    @Test
    fun `tap with at produces empty output when nothing is found at the point`() =
        runTest {
            coEvery { accessibilityServiceProvider.isReady() } returns true
            coEvery { accessibilityServiceProvider.getAccessibilityWindows() } returns emptyList()
            coEvery { accessibilityServiceProvider.getRootNode() } returns null
            coEvery { tapTool.execute(any()) } returns ok
            val params =
                buildJsonObject {
                    put(
                        "at",
                        buildJsonObject {
                            put("x", 9999)
                            put("y", 9999)
                        },
                    )
                }
            val result = dispatcher.dispatch(step("tap", params)) as StepResult
            assertEquals(JsonObject(emptyMap()), result.output)
        }

    @Test
    fun `tap with neither selector nor at produces an InvalidParams error`() =
        runTest {
            val result = dispatcher.dispatch(step("tap")) as StepError
            assertEquals("InvalidParams", result.code)
        }

    /** Wires the mocked accessibility stack to return a single window whose whole tree is [node]. */
    private fun setUpSingleWindowTree(node: AccessibilityNodeData) {
        every { mockWindowInfo.id } returns 0
        every { mockWindowInfo.root } returns mockRootNode
        every { mockWindowInfo.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { mockWindowInfo.title } returns "Test"
        every { mockWindowInfo.layer } returns 0
        every { mockWindowInfo.isFocused } returns true
        every { mockWindowInfo.recycle() } returns Unit
        every { mockRootNode.refresh() } returns true
        every { mockRootNode.packageName } returns "com.example"
        every { accessibilityServiceProvider.isReady() } returns true
        every { accessibilityServiceProvider.clearFrameworkNodeCache() } returns Unit
        every { accessibilityServiceProvider.getAccessibilityWindows() } returns listOf(mockWindowInfo)
        every { accessibilityServiceProvider.getCurrentPackageName() } returns "com.example"
        every { accessibilityServiceProvider.getCurrentActivityName() } returns ".Main"
        every { treeParser.parseTree(mockRootNode, "root_w0", any()) } returns node
    }

    // ── type_text ───────────────────────────────────────────────────────────

    @Test
    fun `type_text without clear auto-waits then calls type_append_text with the raw selector and text`() =
        runTest {
            coEvery { typeAppendTextTool.execute(any()) } returns ok
            val params = selectorParams() + mapOf("text" to JsonPrimitive("hi"))
            dispatcher.dispatch(step("type_text", JsonObject(params)))
            coVerify { waitForNodeTool.execute(any()) }
            coVerify {
                typeAppendTextTool.execute(
                    match {
                        it["selector"]
                            ?.jsonObject
                            ?.get("resource_id")
                            ?.jsonPrimitive
                            ?.content == "com.app:id/x" &&
                            it["text"]?.jsonPrimitive?.content == "hi"
                    },
                )
            }
        }

    @Test
    fun `type_text with clear true calls type_clear_text, text not required`() =
        runTest {
            coEvery { typeClearTextTool.execute(any()) } returns ok
            val params = selectorParams() + mapOf("clear" to JsonPrimitive(true))
            dispatcher.dispatch(step("type_text", JsonObject(params)))
            coVerify { typeClearTextTool.execute(any()) }
            coVerify(exactly = 0) { typeAppendTextTool.execute(any()) }
        }

    @Test
    fun `type_text whose selector never appears fails without calling either type tool`() =
        runTest {
            val timeoutJson = """{"found":false,"elapsedMs":5000,"attempts":10,"message":"timed out"}"""
            coEvery { waitForNodeTool.execute(any()) } returns
                ToolResult(
                    content = listOf(ToolContent.Text("${McpToolUtils.UNTRUSTED_CONTENT_WARNING}\n$timeoutJson")),
                )
            val params = selectorParams() + mapOf("text" to JsonPrimitive("hi"))
            val result = dispatcher.dispatch(step("type_text", JsonObject(params))) as StepError
            assertEquals("NodeNotFound", result.code)
            coVerify(exactly = 0) { typeAppendTextTool.execute(any()) }
            coVerify(exactly = 0) { typeClearTextTool.execute(any()) }
        }

    // ── scroll_find ─────────────────────────────────────────────────────────

    @Test
    fun `scroll_find forwards selector, direction, and max_scrolls to scroll_to_node as-is`() =
        runTest {
            coEvery { scrollToNodeTool.execute(any()) } returns ok
            val params =
                selectorParams() +
                    mapOf("direction" to JsonPrimitive("up"), "max_scrolls" to JsonPrimitive(3))
            dispatcher.dispatch(step("scroll_find", JsonObject(params)))
            coVerify {
                scrollToNodeTool.execute(
                    match {
                        it["selector"]
                            ?.jsonObject
                            ?.get("resource_id")
                            ?.jsonPrimitive
                            ?.content == "com.app:id/x" &&
                            it["direction"]?.jsonPrimitive?.content == "up" &&
                            it["max_scrolls"]?.jsonPrimitive?.content == "3"
                    },
                )
            }
        }

    @Test
    fun `scroll_find without a selector produces InvalidParams`() =
        runTest {
            val result = dispatcher.dispatch(step("scroll_find")) as StepError
            assertEquals("InvalidParams", result.code)
        }

    @Test
    fun `scroll_find propagates scroll_to_node's own failure as this step's error`() =
        runTest {
            coEvery { scrollToNodeTool.execute(any()) } throws McpToolException.NodeNotFound("never resolved")
            val result = dispatcher.dispatch(step("scroll_find", selectorParams())) as StepError
            assertEquals("NodeNotFound", result.code)
        }

    // ── key / launch_app ─────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource("back", "home", "recents", "dismiss_keyboard")
    fun `key dispatches to the right handler`(keyValue: String) =
        runTest {
            coEvery { pressBackHandler.execute(any()) } returns ok
            coEvery { pressHomeHandler.execute(any()) } returns ok
            coEvery { pressRecentsHandler.execute(any()) } returns ok
            coEvery { dismissKeyboardHandler.execute(any()) } returns ok
            val result = dispatcher.dispatch(step("key", buildJsonObject { put("key", keyValue) }))
            assertTrue(result is StepResult)
            when (keyValue) {
                "back" -> coVerify { pressBackHandler.execute(null) }
                "home" -> coVerify { pressHomeHandler.execute(null) }
                "recents" -> coVerify { pressRecentsHandler.execute(null) }
                "dismiss_keyboard" -> coVerify { dismissKeyboardHandler.execute(null) }
            }
        }

    @Test
    fun `key with an unsupported value produces InvalidParams`() =
        runTest {
            val result = dispatcher.dispatch(step("key", buildJsonObject { put("key", "enter") })) as StepError
            assertEquals("InvalidParams", result.code)
        }

    @Test
    fun `launch_app maps package to package_id and defaults fresh to false`() =
        runTest {
            coEvery { openAppHandler.execute(any()) } returns ok
            dispatcher.dispatch(step("launch_app", buildJsonObject { put("package", "com.whatsapp") }))
            coVerify {
                openAppHandler.execute(
                    match {
                        it["package_id"]?.jsonPrimitive?.content == "com.whatsapp" &&
                            it["fresh"]?.jsonPrimitive?.content == "false"
                    },
                )
            }
        }

    @Test
    fun `launch_app forwards fresh true`() =
        runTest {
            coEvery { openAppHandler.execute(any()) } returns ok
            val params =
                buildJsonObject {
                    put("package", "com.whatsapp")
                    put("fresh", true)
                }
            dispatcher.dispatch(step("launch_app", params))
            coVerify { openAppHandler.execute(match { it["fresh"]?.jsonPrimitive?.content == "true" }) }
        }

    // ── wait_until ────────────────────────────────────────────────────────────

    @Test
    fun `wait_until requires timeout_ms`() =
        runTest {
            val result = dispatcher.dispatch(step("wait_until", selectorParams())) as StepError
            assertEquals("InvalidParams", result.code)
        }

    @Test
    fun `wait_until with absent true is rejected`() =
        runTest {
            val params =
                selectorParams() +
                    mapOf("timeout_ms" to JsonPrimitive(1000), "absent" to JsonPrimitive(true))
            val result = dispatcher.dispatch(step("wait_until", JsonObject(params))) as StepError
            assertEquals("InvalidParams", result.code)
        }

    @Test
    fun `wait_until forwards by, value, and timeout to wait_for_node`() =
        runTest {
            val params = selectorParams() + mapOf("timeout_ms" to JsonPrimitive(5000))
            dispatcher.dispatch(step("wait_until", JsonObject(params)))
            coVerify {
                waitForNodeTool.execute(
                    match {
                        it["by"]?.jsonPrimitive?.content == "resource_id" &&
                            it["value"]?.jsonPrimitive?.content == "com.app:id/x" &&
                            it["timeout"]?.jsonPrimitive?.content == "5000"
                    },
                )
            }
        }

    @Test
    fun `wait_until with a found false result is reported as NodeNotFound, not success`() =
        runTest {
            val timeoutJson =
                """{"found":false,"elapsedMs":5000,"attempts":10,"message":"Operation timed out"}"""
            val text = "${McpToolUtils.UNTRUSTED_CONTENT_WARNING}\n$timeoutJson"
            coEvery { waitForNodeTool.execute(any()) } returns ToolResult(content = listOf(ToolContent.Text(text)))
            val params = selectorParams() + mapOf("timeout_ms" to JsonPrimitive(5000))
            val result = dispatcher.dispatch(step("wait_until", JsonObject(params))) as StepError
            assertEquals("NodeNotFound", result.code)
        }

    @Test
    fun `wait_until with a found true result still succeeds`() =
        runTest {
            val params = selectorParams() + mapOf("timeout_ms" to JsonPrimitive(5000))
            val result = dispatcher.dispatch(step("wait_until", JsonObject(params)))
            assertTrue(result is StepResult)
        }

    // ── post-action idle wait ──────────────────────────────────────────────

    @Test
    fun `every successful action waits for idle afterwards`() =
        runTest {
            coEvery { openAppHandler.execute(any()) } returns ok
            dispatcher.dispatch(step("launch_app", buildJsonObject { put("package", "com.x") }))
            coVerify(exactly = 1) { waitForIdleTool.execute(any()) }
        }

    @Test
    fun `a failed action does not wait for idle`() =
        runTest {
            coEvery { openAppHandler.execute(any()) } throws McpToolException.ActionFailed("boom")
            dispatcher.dispatch(step("launch_app", buildJsonObject { put("package", "com.x") }))
            coVerify(exactly = 0) { waitForIdleTool.execute(any()) }
        }

    // ── error handling ──────────────────────────────────────────────────────

    @Test
    fun `dispatch rethrows CancellationException instead of turning it into a StepError`() =
        runTest {
            coEvery { openAppHandler.execute(any()) } throws kotlinx.coroutines.CancellationException("cancelled")
            val params = buildJsonObject { put("package", "com.x") }
            assertThrows<kotlinx.coroutines.CancellationException> {
                dispatcher.dispatch(step("launch_app", params))
            }
        }

    @Test
    fun `a handler's McpToolException becomes a StepError with the exception's class name as code`() =
        runTest {
            coEvery { openAppHandler.execute(any()) } throws McpToolException.ActionFailed("boom")
            val params = buildJsonObject { put("package", "com.x") }
            val result = dispatcher.dispatch(step("launch_app", params)) as StepError
            assertEquals("ActionFailed", result.code)
            assertEquals("boom", result.message)
        }

    @Test
    fun `an unexpected exception becomes a StepError, not a crash`() =
        runTest {
            coEvery { openAppHandler.execute(any()) } throws RuntimeException("kaboom")
            val params = buildJsonObject { put("package", "com.x") }
            val result = dispatcher.dispatch(step("launch_app", params)) as StepError
            assertEquals("InternalError", result.code)
        }

    @Test
    fun `an unknown op produces InvalidParams`() =
        runTest {
            val result = dispatcher.dispatch(step("frobnicate")) as StepError
            assertEquals("InvalidParams", result.code)
        }
}
