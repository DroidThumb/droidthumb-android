package com.danielealbano.androidremotecontrolmcp.wireprotocol

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
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ScrollTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TapTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolContent
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolResult
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeAppendTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeClearTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.WaitForNodeTool
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class StepDispatcherTest {
    private lateinit var getScreenState: GetScreenStateHandler
    private lateinit var tapTool: TapTool
    private lateinit var clickNodeTool: ClickNodeTool
    private lateinit var typeAppendTextTool: TypeAppendTextTool
    private lateinit var typeClearTextTool: TypeClearTextTool
    private lateinit var scrollToNodeTool: ScrollToNodeTool
    private lateinit var scrollTool: ScrollTool
    private lateinit var pressBackHandler: PressBackHandler
    private lateinit var pressHomeHandler: PressHomeHandler
    private lateinit var pressRecentsHandler: PressRecentsHandler
    private lateinit var dismissKeyboardHandler: DismissKeyboardHandler
    private lateinit var openAppHandler: OpenAppHandler
    private lateinit var waitForNodeTool: WaitForNodeTool
    private lateinit var selectorResolver: SelectorResolver
    private lateinit var dispatcher: StepDispatcher

    private val ok = ToolResult(content = listOf(ToolContent.Text("ok")))

    @BeforeEach
    fun setUp() {
        getScreenState = mockk()
        tapTool = mockk()
        clickNodeTool = mockk()
        typeAppendTextTool = mockk()
        typeClearTextTool = mockk()
        scrollToNodeTool = mockk()
        scrollTool = mockk()
        pressBackHandler = mockk()
        pressHomeHandler = mockk()
        pressRecentsHandler = mockk()
        dismissKeyboardHandler = mockk()
        openAppHandler = mockk()
        waitForNodeTool = mockk()
        selectorResolver = mockk()
        dispatcher =
            StepDispatcher(
                getScreenState,
                tapTool,
                clickNodeTool,
                typeAppendTextTool,
                typeClearTextTool,
                scrollToNodeTool,
                scrollTool,
                pressBackHandler,
                pressHomeHandler,
                pressRecentsHandler,
                dismissKeyboardHandler,
                openAppHandler,
                waitForNodeTool,
                selectorResolver,
            )
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

    @Test
    fun `tap with selector resolves node_id and calls click_node`() =
        runTest {
            coEvery { selectorResolver.resolve(any()) } returns "node-42"
            coEvery { clickNodeTool.execute(any()) } returns ok
            dispatcher.dispatch(step("tap", selectorParams()))
            coVerify { clickNodeTool.execute(match { it.get("node_id")?.jsonPrimitive?.content == "node-42" }) }
        }

    @Test
    fun `tap with at calls TapTool directly, no selector resolution`() =
        runTest {
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
            coVerify(exactly = 0) { selectorResolver.resolve(any()) }
            coVerify { tapTool.execute(any()) }
        }

    @Test
    fun `tap with neither selector nor at produces an InvalidParams error`() =
        runTest {
            val result = dispatcher.dispatch(step("tap")) as StepError
            assertEquals("InvalidParams", result.code)
        }

    @Test
    fun `type_text without clear calls type_append_text with resolved node_id and text`() =
        runTest {
            coEvery { selectorResolver.resolve(any()) } returns "node-1"
            coEvery { typeAppendTextTool.execute(any()) } returns ok
            val params = selectorParams() + mapOf("text" to kotlinx.serialization.json.JsonPrimitive("hi"))
            dispatcher.dispatch(step("type_text", JsonObject(params)))
            coVerify {
                typeAppendTextTool.execute(
                    match {
                        it.get("node_id")?.jsonPrimitive?.content == "node-1" &&
                            it["text"]?.jsonPrimitive?.content == "hi"
                    },
                )
            }
        }

    @Test
    fun `type_text with clear true calls type_clear_text, text not required`() =
        runTest {
            coEvery { selectorResolver.resolve(any()) } returns "node-1"
            coEvery { typeClearTextTool.execute(any()) } returns ok
            val params = selectorParams() + mapOf("clear" to kotlinx.serialization.json.JsonPrimitive(true))
            dispatcher.dispatch(step("type_text", JsonObject(params)))
            coVerify { typeClearTextTool.execute(any()) }
            coVerify(exactly = 0) { typeAppendTextTool.execute(any()) }
        }

    @Test
    fun `scroll_find resolves immediately when selector already matches`() =
        runTest {
            coEvery { selectorResolver.resolve(any()) } returns "node-9"
            coEvery { scrollToNodeTool.execute(any()) } returns ok
            dispatcher.dispatch(step("scroll_find", selectorParams()))
            coVerify(exactly = 1) { selectorResolver.resolve(any()) }
            coVerify(exactly = 0) { scrollTool.execute(any()) }
            coVerify { scrollToNodeTool.execute(any()) }
        }

    @Test
    fun `scroll_find blind-scrolls when selector doesn't resolve, then finds it`() =
        runTest {
            var call = 0
            coEvery { selectorResolver.resolve(any()) } answers {
                call++
                if (call < 3) throw McpToolException.NodeNotFound("nope") else "node-9"
            }
            coEvery { scrollTool.execute(any()) } returns ok
            coEvery { scrollToNodeTool.execute(any()) } returns ok
            dispatcher.dispatch(step("scroll_find", selectorParams()))
            coVerify(exactly = 2) { scrollTool.execute(any()) }
            coVerify(exactly = 1) { scrollToNodeTool.execute(any()) }
        }

    @Test
    fun `scroll_find exhausts max_scrolls and errors`() =
        runTest {
            coEvery { selectorResolver.resolve(any()) } throws McpToolException.NodeNotFound("nope")
            coEvery { scrollTool.execute(any()) } returns ok
            val params = selectorParams() + mapOf("max_scrolls" to kotlinx.serialization.json.JsonPrimitive(2))
            val result = dispatcher.dispatch(step("scroll_find", JsonObject(params))) as StepError
            assertEquals("NodeNotFound", result.code)
            coVerify(exactly = 2) { scrollTool.execute(any()) }
        }

    @Test
    fun `scroll_find propagates a non-NodeNotFound failure immediately instead of blind-scrolling`() =
        runTest {
            coEvery { selectorResolver.resolve(any()) } throws McpToolException.PermissionDenied("no a11y")
            val result = dispatcher.dispatch(step("scroll_find", selectorParams())) as StepError
            assertEquals("PermissionDenied", result.code)
            coVerify(exactly = 0) { scrollTool.execute(any()) }
        }

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
    fun `launch_app maps package to package_id`() =
        runTest {
            coEvery { openAppHandler.execute(any()) } returns ok
            dispatcher.dispatch(step("launch_app", buildJsonObject { put("package", "com.whatsapp") }))
            coVerify {
                openAppHandler.execute(match { it.get("package_id")?.jsonPrimitive?.content == "com.whatsapp" })
            }
        }

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
                    mapOf(
                        "timeout_ms" to kotlinx.serialization.json.JsonPrimitive(1000),
                        "absent" to kotlinx.serialization.json.JsonPrimitive(true),
                    )
            val result = dispatcher.dispatch(step("wait_until", JsonObject(params))) as StepError
            assertEquals("InvalidParams", result.code)
        }

    @Test
    fun `wait_until forwards by, value, and timeout to wait_for_node`() =
        runTest {
            coEvery { waitForNodeTool.execute(any()) } returns ok
            val params = selectorParams() + mapOf("timeout_ms" to kotlinx.serialization.json.JsonPrimitive(5000))
            dispatcher.dispatch(step("wait_until", JsonObject(params)))
            coVerify {
                waitForNodeTool.execute(
                    match {
                        it.get("by")?.jsonPrimitive?.content == "resource_id" &&
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
            val params = selectorParams() + mapOf("timeout_ms" to kotlinx.serialization.json.JsonPrimitive(5000))
            val result = dispatcher.dispatch(step("wait_until", JsonObject(params))) as StepError
            assertEquals("NodeNotFound", result.code)
        }

    @Test
    fun `wait_until with a found true result still succeeds`() =
        runTest {
            val foundJson = """{"found":true,"elapsedMs":10,"attempts":1,"node":{}}"""
            coEvery { waitForNodeTool.execute(any()) } returns
                ToolResult(content = listOf(ToolContent.Text("${McpToolUtils.UNTRUSTED_CONTENT_WARNING}\n$foundJson")))
            val params = selectorParams() + mapOf("timeout_ms" to kotlinx.serialization.json.JsonPrimitive(5000))
            val result = dispatcher.dispatch(step("wait_until", JsonObject(params)))
            assertTrue(result is StepResult)
        }

    @Test
    fun `dispatch rethrows CancellationException instead of turning it into a StepError`() =
        runTest {
            coEvery { openAppHandler.execute(any()) } throws kotlinx.coroutines.CancellationException("cancelled")
            val params = buildJsonObject { put("package", "com.x") }
            org.junit.jupiter.api.assertThrows<kotlinx.coroutines.CancellationException> {
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
