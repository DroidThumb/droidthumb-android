package com.danielealbano.androidremotecontrolmcp.integration

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ClickNodeTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.CloseAppHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.DismissKeyboardHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.DoubleTapTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.FindNodesTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.GetClipboardTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.GetScreenStateHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.LongClickNodeTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.LongPressTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.OpenAppHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.OpenUriHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.PressBackHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.PressHomeHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.PressKeyTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.PressRecentsHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ScrollToNodeTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ScrollTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.SetClipboardTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.SwipeTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TapNodeTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TapTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolContent
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolResult
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeAppendTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeClearTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeInsertTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeReplaceTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.WaitForIdleTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.WaitForNodeTool
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCacheImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputController
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WebViewNodeMerger
import com.danielealbano.androidremotecontrolmcp.services.apps.AppManager
import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcher
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotator
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Test-only harness for the handler integration tests.
 *
 * Builds every kept tool handler with mocked Android services and dispatches calls by tool name
 * straight to each handler's `execute()`. No transport is involved: the on-device MCP server these
 * tests used to go through was removed (docs/plans/demolition.md). A thrown exception becomes
 * `ToolResult(isError = true)` carrying the exception message, which is how the removed MCP SDK
 * layer reported handler failures, so the tests keep asserting on outcomes. Only [Exception]s are
 * mapped (a JVM [Error] propagates), and an unknown tool name throws, so a mistyped name can never
 * pass as an expected error result.
 */
object HandlerTestHarness {
    /** Prefix the tests use for tool names (the tools' former MCP names, e.g. `android_tap`). */
    private const val TOOL_NAME_PREFIX = "android_"

    /** Mocked service dependencies used by all tool handlers. */
    data class MockDependencies(
        val actionExecutor: ActionExecutor,
        val accessibilityServiceProvider: AccessibilityServiceProvider,
        val screenCaptureProvider: ScreenCaptureProvider,
        val treeParser: AccessibilityTreeParser,
        val elementFinder: ElementFinder,
        val appManager: AppManager,
        val typeInputController: TypeInputController,
        val screenshotAnnotator: ScreenshotAnnotator,
        val screenshotEncoder: ScreenshotEncoder,
        val nodeCache: AccessibilityNodeCache,
        val screenStateSnapshotCache: ScreenStateSnapshotCache,
        val intentDispatcher: IntentDispatcher,
    )

    /** Creates mocked service dependencies used by all tool handlers. */
    fun createMockDependencies(): MockDependencies =
        MockDependencies(
            actionExecutor = mockk(relaxed = true),
            accessibilityServiceProvider = mockk(relaxed = true),
            screenCaptureProvider = mockk(relaxed = true),
            treeParser = mockk(relaxed = true),
            elementFinder = mockk(relaxed = true),
            appManager = mockk(relaxed = true),
            typeInputController = mockk(relaxed = true),
            screenshotAnnotator = mockk(relaxed = true),
            screenshotEncoder = mockk(relaxed = true),
            nodeCache = mockk(relaxed = true),
            screenStateSnapshotCache = ScreenStateSnapshotCacheImpl(),
            intentDispatcher = mockk(relaxed = true),
        )

    /**
     * Configures multi-window mocking on the given [MockDependencies].
     *
     * Sets up [AccessibilityServiceProvider.getAccessibilityWindows] to return
     * a single mock [AccessibilityWindowInfo] whose root node parses to the given tree.
     */
    @Suppress("LongParameterList")
    fun setupMultiWindowMock(
        deps: MockDependencies,
        tree: AccessibilityNodeData,
        screenInfo: ScreenInfo,
        packageName: String = "com.example.app",
        activityName: String = ".MainActivity",
        windowId: Int = 0,
    ): AccessibilityNodeInfo {
        val mockRootNode = mockk<AccessibilityNodeInfo>()
        val mockWindowInfo = mockk<AccessibilityWindowInfo>(relaxed = true)

        every { deps.accessibilityServiceProvider.isReady() } returns true
        every { mockWindowInfo.id } returns windowId
        every { mockWindowInfo.root } returns mockRootNode
        every { mockWindowInfo.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { mockWindowInfo.title } returns "Test"
        every { mockWindowInfo.layer } returns 0
        every { mockWindowInfo.isFocused } returns true
        every { mockRootNode.refresh() } returns true
        every { mockRootNode.packageName } returns packageName
        // Raw-node walk support: rawNodeExists() reads these properties directly
        // from AccessibilityNodeInfo without going through AccessibilityTreeParser.
        // Return null/0/empty so the root node does not match any search criteria
        // (individual tests that need a match will override these stubs).
        every { mockRootNode.text } returns null
        every { mockRootNode.contentDescription } returns null
        every { mockRootNode.viewIdResourceName } returns null
        every { mockRootNode.className } returns null
        every { mockRootNode.childCount } returns 0
        every { mockRootNode.availableExtraData } returns emptyList()
        every {
            deps.accessibilityServiceProvider.getAccessibilityWindows()
        } returns listOf(mockWindowInfo)
        every { deps.accessibilityServiceProvider.getCurrentPackageName() } returns packageName
        every { deps.accessibilityServiceProvider.getCurrentActivityName() } returns activityName
        every { deps.accessibilityServiceProvider.getScreenInfo() } returns screenInfo
        every { deps.treeParser.parseTree(mockRootNode, "root_w$windowId", any()) } returns tree
        return mockRootNode
    }

    /**
     * Mocks [android.util.Log] static methods to prevent crashes in JVM unit tests.
     * Must be called in @BeforeEach.
     */
    fun mockAndroidLog() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    /**
     * Unmocks [android.util.Log] static methods.
     * Must be called in @AfterEach.
     */
    fun unmockAndroidLog() {
        unmockkStatic(android.util.Log::class)
    }

    /** Runs [testBlock] with a [ToolClient] over handlers built from [deps]. */
    suspend fun withTools(
        deps: MockDependencies = createMockDependencies(),
        testBlock: suspend (client: ToolClient, deps: MockDependencies) -> Unit,
    ) {
        testBlock(ToolClient(deps), deps)
    }

    /** Dispatches tool calls by name directly to the handlers' `execute()`. */
    class ToolClient(
        deps: MockDependencies,
    ) {
        private val handlers: Map<String, suspend (JsonObject?) -> ToolResult> = buildHandlers(deps)

        /**
         * Calls the tool named [name] (with or without the `android_` prefix) with [arguments].
         *
         * @throws IllegalArgumentException if no handler has that name (a test bug, not a tool error).
         */
        suspend fun callTool(
            name: String,
            arguments: Map<String, Any?> = emptyMap(),
        ): ToolResult {
            val handler =
                requireNotNull(handlers[name.removePrefix(TOOL_NAME_PREFIX)]) { "No handler named '$name'" }
            return runCatching { handler(toJsonObject(arguments)) }
                .getOrElse { e ->
                    if (e is CancellationException || e !is Exception) throw e
                    errorResult(e.message ?: e.toString())
                }
        }

        private fun errorResult(message: String): ToolResult =
            ToolResult(content = listOf(ToolContent.Text(text = message)), isError = true)
    }

    private fun buildHandlers(d: MockDependencies): Map<String, suspend (JsonObject?) -> ToolResult> {
        val tree = d.treeParser
        val a11y = d.accessibilityServiceProvider
        val exec = d.actionExecutor
        val cache = d.nodeCache
        val finder = d.elementFinder
        val input = d.typeInputController
        val getScreenState =
            GetScreenStateHandler(
                tree,
                a11y,
                d.screenCaptureProvider,
                CompactTreeFormatter(),
                d.screenshotAnnotator,
                d.screenshotEncoder,
                cache,
                d.screenStateSnapshotCache,
                WebViewNodeMerger(),
            )
        return mapOf(
            "get_screen_state" to getScreenState::execute,
            "press_back" to PressBackHandler(exec, a11y)::execute,
            "press_home" to PressHomeHandler(exec, a11y)::execute,
            "press_recents" to PressRecentsHandler(exec, a11y)::execute,
            "dismiss_keyboard" to DismissKeyboardHandler(exec, a11y)::execute,
            "tap" to TapTool(exec)::execute,
            "long_press" to LongPressTool(exec)::execute,
            "double_tap" to DoubleTapTool(exec)::execute,
            "swipe" to SwipeTool(exec)::execute,
            "scroll" to ScrollTool(exec)::execute,
            "find_nodes" to FindNodesTool(tree, finder, a11y, cache)::execute,
            "click_node" to ClickNodeTool(tree, finder, exec, a11y, cache)::execute,
            "long_click_node" to LongClickNodeTool(tree, exec, a11y, cache)::execute,
            "tap_node" to TapNodeTool(tree, finder, exec, a11y, cache)::execute,
            "scroll_to_node" to ScrollToNodeTool(tree, finder, exec, a11y, cache)::execute,
            "type_append_text" to TypeAppendTextTool(tree, finder, exec, a11y, input, cache)::execute,
            "type_insert_text" to TypeInsertTextTool(tree, exec, a11y, input, cache)::execute,
            "type_replace_text" to TypeReplaceTextTool(tree, exec, a11y, input, cache)::execute,
            "type_clear_text" to TypeClearTextTool(tree, finder, exec, a11y, input, cache)::execute,
            "press_key" to PressKeyTool(exec, a11y)::execute,
            "get_clipboard" to GetClipboardTool(a11y)::execute,
            "set_clipboard" to SetClipboardTool(a11y)::execute,
            "wait_for_node" to WaitForNodeTool(tree, finder, a11y, cache)::execute,
            "wait_for_idle" to WaitForIdleTool(a11y)::execute,
            "open_app" to OpenAppHandler(d.appManager)::execute,
            "close_app" to CloseAppHandler(d.appManager)::execute,
            "open_uri" to OpenUriHandler(d.intentDispatcher)::execute,
        )
    }

    private fun toJsonObject(arguments: Map<String, Any?>): JsonObject =
        JsonObject(arguments.mapValues { (_, value) -> toJsonElement(value) })

    private fun toJsonElement(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) })
            is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
            is Array<*> -> JsonArray(value.map { toJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }
}
