package com.danielealbano.androidremotecontrolmcp.wireprotocol

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Exercises [SelectorResolver]/[pickSelectorCandidate] against a real [ElementFinder] (stateless,
 * cheap to use for real rather than mock) and a real tree — the field-priority order, exact-vs-
 * substring split, and `index` disambiguation are this build's own judgment call
 * (plan 01 §5.1.1 doesn't specify match semantics), so they're worth verifying against real
 * matching behaviour, not just documenting in a comment.
 */
class SelectorResolverTest {
    private val mockTreeParser = mockk<AccessibilityTreeParser>()
    private val mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>()
    private val mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
    private val mockRootNode = mockk<AccessibilityNodeInfo>()
    private val mockWindowInfo = mockk<AccessibilityWindowInfo>()

    private val tree =
        AccessibilityNodeData(
            id = "root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "entry",
                        resourceId = "com.app:id/entry",
                        text = "Message",
                        className = "android.widget.EditText",
                        bounds = BoundsData(0, 0, 100, 100),
                        visible = true,
                    ),
                    AccessibilityNodeData(
                        id = "close",
                        contentDescription = "Close dialog",
                        text = "X",
                        className = "android.widget.ImageButton",
                        bounds = BoundsData(0, 100, 100, 200),
                        visible = true,
                    ),
                    AccessibilityNodeData(
                        id = "submit",
                        text = "Submit Now",
                        className = "android.widget.Button",
                        bounds = BoundsData(0, 200, 100, 300),
                        visible = true,
                    ),
                    AccessibilityNodeData(
                        id = "btn0",
                        text = "First",
                        className = "android.widget.Button",
                        bounds = BoundsData(0, 300, 100, 400),
                        visible = true,
                    ),
                    AccessibilityNodeData(
                        id = "btn1",
                        text = "Second",
                        className = "android.widget.Button",
                        bounds = BoundsData(0, 400, 100, 500),
                        visible = true,
                    ),
                ),
        )

    private val resolver =
        SelectorResolver(mockTreeParser, ElementFinder(), mockAccessibilityServiceProvider, mockNodeCache)

    @BeforeEach
    fun setUp() {
        every { mockAccessibilityServiceProvider.isReady() } returns true
        every { mockAccessibilityServiceProvider.clearFrameworkNodeCache() } returns Unit
        every { mockWindowInfo.id } returns 0
        every { mockWindowInfo.root } returns mockRootNode
        every { mockWindowInfo.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { mockWindowInfo.title } returns "Test"
        every { mockWindowInfo.layer } returns 0
        every { mockWindowInfo.isFocused } returns true
        every { mockWindowInfo.recycle() } returns Unit
        every { mockRootNode.refresh() } returns true
        every { mockRootNode.packageName } returns "com.example"
        every { mockAccessibilityServiceProvider.getAccessibilityWindows() } returns listOf(mockWindowInfo)
        every { mockAccessibilityServiceProvider.getCurrentPackageName() } returns "com.example"
        every { mockAccessibilityServiceProvider.getCurrentActivityName() } returns ".Main"
        every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns tree
    }

    private fun selector(vararg pairs: Pair<String, Any>) =
        buildJsonObject {
            for ((k, v) in pairs) {
                when (v) {
                    is String -> put(k, v)
                    is Int -> put(k, v)
                    else -> error("unsupported: $v")
                }
            }
        }

    @Test
    fun `resolves by resource_id, exact match`() =
        runTest {
            val id = resolver.resolve(selector("resource_id" to "com.app:id/entry"))
            assertEquals("entry", id)
        }

    @Test
    fun `resource_id wrong case does not match (exact)`() =
        runTest {
            assertThrows<McpToolException.NodeNotFound> {
                resolver.resolve(selector("resource_id" to "COM.APP:ID/ENTRY"))
            }
        }

    @Test
    fun `resolves by content_desc, case-insensitive substring`() =
        runTest {
            val id = resolver.resolve(selector("content_desc" to "close"))
            assertEquals("close", id)
        }

    @Test
    fun `resolves by text, case-insensitive substring`() =
        runTest {
            val id = resolver.resolve(selector("text" to "submit"))
            assertEquals("submit", id)
        }

    @Test
    fun `resolves by class_name, exact match`() =
        runTest {
            val id = resolver.resolve(selector("class_name" to "android.widget.EditText"))
            assertEquals("entry", id)
        }

    @Test
    fun `resource_id takes priority over text when both are present`() =
        runTest {
            // "text" here would match "submit" (Submit Now) if honoured, but resource_id must win.
            val id = resolver.resolve(selector("resource_id" to "com.app:id/entry", "text" to "submit"))
            assertEquals("entry", id)
        }

    @Test
    fun `content_desc takes priority over text when both are present`() =
        runTest {
            val id = resolver.resolve(selector("content_desc" to "close", "text" to "submit"))
            assertEquals("close", id)
        }

    @Test
    fun `index selects among multiple matches`() =
        runTest {
            // submit, btn0, btn1 are all android.widget.Button, matched in tree order; index 2
            // (0-based) is the third match, btn1.
            val id = resolver.resolve(selector("class_name" to "android.widget.Button", "index" to 2))
            assertEquals("btn1", id)
        }

    @Test
    fun `no match throws NodeNotFound with a clear message`() =
        runTest {
            val exception =
                assertThrows<McpToolException.NodeNotFound> {
                    resolver.resolve(selector("text" to "nonexistent"))
                }
            assertTrue(exception.message?.contains("nonexistent") == true)
        }

    @Test
    fun `no selector fields throws InvalidParams`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> {
                resolver.resolve(buildJsonObject {})
            }
        }
}
