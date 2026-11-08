package com.danielealbano.androidremotecontrolmcp.wireprotocol

import com.danielealbano.androidremotecontrolmcp.mcp.tools.GetScreenStateHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.mcp.tools.OpenAppHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolContent
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolResult
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeAppendTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeClearTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.WaitForIdleTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.WaitForNodeTool
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ImeSubmitController
import com.danielealbano.androidremotecontrolmcp.services.controlbar.ControlBarCoordinator
import com.danielealbano.androidremotecontrolmcp.services.lockscreen.LockStateProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** `type_text`'s replace/append/submit behaviour and the lock-screen gate. Split out of
 *  [StepDispatcherTest] to keep both classes within detekt's size limits. */
class StepDispatcherTypeTextAndLockTest {
    private lateinit var getScreenState: GetScreenStateHandler
    private lateinit var typeAppendTextTool: TypeAppendTextTool
    private lateinit var typeClearTextTool: TypeClearTextTool
    private lateinit var waitForNodeTool: WaitForNodeTool
    private lateinit var waitForIdleTool: WaitForIdleTool
    private lateinit var imeSubmitController: ImeSubmitController
    private lateinit var lockStateProvider: LockStateProvider
    private lateinit var openAppHandler: OpenAppHandler
    private lateinit var controlBarCoordinator: ControlBarCoordinator
    private lateinit var dispatcher: StepDispatcher

    private val ok = ToolResult(content = listOf(ToolContent.Text("ok")))
    private val foundTrue =
        ToolResult(
            content =
                listOf(
                    ToolContent.Text(
                        "${McpToolUtils.UNTRUSTED_CONTENT_WARNING}\n" +
                            """{"found":true,"elapsedMs":1,"attempts":1,"node":{}}""",
                    ),
                ),
        )
    private val idleOk = ToolResult(content = listOf(ToolContent.Text("UI is idle")))

    @BeforeEach
    fun setUp() {
        getScreenState = mockk()
        typeAppendTextTool = mockk()
        typeClearTextTool = mockk()
        waitForNodeTool = mockk()
        waitForIdleTool = mockk()
        imeSubmitController = mockk()
        lockStateProvider = mockk()
        openAppHandler = mockk()
        controlBarCoordinator = mockk(relaxed = true)
        dispatcher =
            StepDispatcher(
                getScreenState = getScreenState,
                tapTool = mockk(),
                clickNodeTool = mockk(),
                typeAppendTextTool = typeAppendTextTool,
                typeClearTextTool = typeClearTextTool,
                scrollToNodeTool = mockk(),
                pressBackHandler = mockk(),
                pressHomeHandler = mockk(),
                pressRecentsHandler = mockk(),
                dismissKeyboardHandler = mockk(),
                openAppHandler = openAppHandler,
                waitForNodeTool = waitForNodeTool,
                waitForIdleTool = waitForIdleTool,
                treeParser = mockk(),
                accessibilityServiceProvider = mockk(),
                nodeCache = mockk(relaxed = true),
                imeSubmitController = imeSubmitController,
                lockStateProvider = lockStateProvider,
                controlBarCoordinator = controlBarCoordinator,
            )

        coEvery { waitForNodeTool.execute(any()) } returns foundTrue
        coEvery { waitForIdleTool.execute(any()) } returns idleOk
        every { lockStateProvider.isLocked() } returns false
    }

    private fun step(
        op: String,
        params: JsonObject = JsonObject(emptyMap()),
    ) = Step(stepId = "s1", op = op, params = params)

    private fun selectorParams(): JsonObject =
        JsonObject(mapOf("selector" to JsonObject(mapOf("resource_id" to JsonPrimitive("com.app:id/x")))))

    // ── type_text ───────────────────────────────────────────────────────────

    @Test
    fun `type_text replaces the field's content by default - clears then types`() =
        runTest {
            coEvery { typeClearTextTool.execute(any()) } returns ok
            coEvery { typeAppendTextTool.execute(any()) } returns ok
            val params = selectorParams() + mapOf("text" to JsonPrimitive("hi"))
            dispatcher.dispatch(step("type_text", JsonObject(params)))
            coVerifyOrder {
                typeClearTextTool.execute(any())
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
    fun `type_text with append true appends without clearing`() =
        runTest {
            coEvery { typeAppendTextTool.execute(any()) } returns ok
            val params =
                selectorParams() +
                    mapOf("text" to JsonPrimitive("hi"), "append" to JsonPrimitive(true))
            dispatcher.dispatch(step("type_text", JsonObject(params)))
            coVerify { typeAppendTextTool.execute(any()) }
            coVerify(exactly = 0) { typeClearTextTool.execute(any()) }
        }

    @Test
    fun `type_text with submit true performs the keyboard's action after typing`() =
        runTest {
            coEvery { typeAppendTextTool.execute(any()) } returns ok
            every { imeSubmitController.submitFocusedField() } returns true
            val params =
                selectorParams() +
                    mapOf(
                        "text" to JsonPrimitive("hi"),
                        "append" to JsonPrimitive(true),
                        "submit" to JsonPrimitive(true),
                    )
            val result = dispatcher.dispatch(step("type_text", JsonObject(params)))
            assertTrue(result is StepResult)
            coVerifyOrder {
                typeAppendTextTool.execute(any())
                imeSubmitController.submitFocusedField()
            }
        }

    @Test
    fun `type_text with submit true fails with submit_unsupported when the keyboard has no action`() =
        runTest {
            coEvery { typeAppendTextTool.execute(any()) } returns ok
            every { imeSubmitController.submitFocusedField() } returns false
            val params =
                selectorParams() +
                    mapOf(
                        "text" to JsonPrimitive("hi"),
                        "append" to JsonPrimitive(true),
                        "submit" to JsonPrimitive(true),
                    )
            val result = dispatcher.dispatch(step("type_text", JsonObject(params))) as StepError
            assertEquals("submit_unsupported", result.code)
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

    @Test
    fun `type_text replace with empty text clears the field and succeeds without typing`() =
        runTest {
            coEvery { typeClearTextTool.execute(any()) } returns ok
            val params = selectorParams() + mapOf("text" to JsonPrimitive(""))
            val result = dispatcher.dispatch(step("type_text", JsonObject(params)))
            assertTrue(result is StepResult)
            coVerify(exactly = 1) { typeClearTextTool.execute(any()) }
            coVerify(exactly = 0) { typeAppendTextTool.execute(any()) }
        }

    @Test
    fun `type_text append with empty text fails InvalidParams before any screen interaction`() =
        runTest {
            val params =
                selectorParams() +
                    mapOf("text" to JsonPrimitive(""), "append" to JsonPrimitive(true))
            val result = dispatcher.dispatch(step("type_text", JsonObject(params))) as StepError
            assertEquals("InvalidParams", result.code)
            // Nothing on screen may change: neither type tool, and not even the selector wait.
            coVerify(exactly = 0) { typeAppendTextTool.execute(any()) }
            coVerify(exactly = 0) { typeClearTextTool.execute(any()) }
            coVerify(exactly = 0) { waitForNodeTool.execute(any()) }
        }

    // ── lock screen ──────────────────────────────────────────────────────────

    @Test
    fun `read_screen while locked still returns the tree and reports locked true`() =
        runTest {
            every { lockStateProvider.isLocked() } returns true
            coEvery { getScreenState.execute(any()) } returns
                ToolResult(content = listOf(ToolContent.Text("tree text")))
            val result = dispatcher.dispatch(step("read_screen")) as StepResult
            val output = result.output!!.jsonObject
            assertEquals("tree text", output["tree"]!!.jsonPrimitive.content)
            assertEquals(true, output["locked"]!!.jsonPrimitive.booleanOrNull)
        }

    @Test
    fun `read_screen while unlocked reports locked false`() =
        runTest {
            coEvery { getScreenState.execute(any()) } returns
                ToolResult(content = listOf(ToolContent.Text("tree text")))
            val result = dispatcher.dispatch(step("read_screen")) as StepResult
            assertEquals(
                false,
                result.output!!
                    .jsonObject["locked"]!!
                    .jsonPrimitive.booleanOrNull,
            )
        }

    @Test
    fun `a device-action step while locked fails with device_locked without executing anything`() =
        runTest {
            every { lockStateProvider.isLocked() } returns true
            val params = JsonObject(mapOf("package" to JsonPrimitive("com.x")))
            val result = dispatcher.dispatch(step("launch_app", params)) as StepError
            assertEquals("device_locked", result.code)
            assertEquals(StepDispatcher.DEVICE_LOCKED_MESSAGE, result.message)
            coVerify(exactly = 0) { openAppHandler.execute(any()) }
            coVerify(exactly = 0) { waitForIdleTool.execute(any()) }
        }

    @Test
    fun `task_done while locked fails with device_locked`() =
        runTest {
            every { lockStateProvider.isLocked() } returns true
            val result = dispatcher.dispatch(step("task_done")) as StepError
            assertEquals("device_locked", result.code)
            coVerify(exactly = 0) { controlBarCoordinator.taskDone(any()) }
        }
}
