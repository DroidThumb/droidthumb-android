package com.danielealbano.androidremotecontrolmcp.services.accessibility

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Exercises [resolveSelectorNodeId]/[pickSelectorCandidate]/[selectorForNode]/[findNodeAtPoint]
 * against a real [ElementFinder] (stateless, cheap to use for real rather than mock) and plain
 * [WindowData]/[AccessibilityNodeData] fixtures — no accessibility-framework mocking needed at
 * all, since these functions take an already-parsed tree rather than fetching their own (that's
 * the M3 staleness fix: the caller's own single fresh parse is passed in, never refetched here).
 */
class SelectorMatchingTest {
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
                        clickable = true,
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
                    // A non-clickable label whose only clickable ancestor is "row" (item 6's fix).
                    AccessibilityNodeData(
                        id = "row",
                        className = "android.widget.LinearLayout",
                        bounds = BoundsData(0, 500, 1080, 600),
                        visible = true,
                        clickable = true,
                        children =
                            listOf(
                                AccessibilityNodeData(
                                    id = "row_label",
                                    text = "Timer",
                                    className = "android.widget.TextView",
                                    bounds = BoundsData(0, 500, 1080, 600),
                                    visible = true,
                                    clickable = false,
                                ),
                            ),
                    ),
                ),
        )

    private val windows =
        listOf(
            WindowData(
                windowId = 0,
                windowType = "APPLICATION",
                packageName = "com.example",
                layer = 0,
                focused = true,
                tree = tree,
            ),
        )

    private val finder = ElementFinder()

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

    // ── resolveSelectorNodeId ──────────────────────────────────────────────

    @Test
    fun `resolves by resource_id, exact match`() {
        assertEquals("entry", resolveSelectorNodeId(windows, finder, selector("resource_id" to "com.app:id/entry")))
    }

    @Test
    fun `resource_id wrong case does not match (exact)`() {
        assertThrows<McpToolException.NodeNotFound> {
            resolveSelectorNodeId(windows, finder, selector("resource_id" to "COM.APP:ID/ENTRY"))
        }
    }

    @Test
    fun `resolves by content_desc, case-insensitive substring`() {
        assertEquals("close", resolveSelectorNodeId(windows, finder, selector("content_desc" to "close")))
    }

    @Test
    fun `resolves by text, case-insensitive substring`() {
        assertEquals("submit", resolveSelectorNodeId(windows, finder, selector("text" to "submit")))
    }

    @Test
    fun `resolves by class_name, exact match`() {
        val id = resolveSelectorNodeId(windows, finder, selector("class_name" to "android.widget.EditText"))
        assertEquals("entry", id)
    }

    @Test
    fun `resource_id takes priority over text when both are present`() {
        val sel = selector("resource_id" to "com.app:id/entry", "text" to "submit")
        val id = resolveSelectorNodeId(windows, finder, sel)
        assertEquals("entry", id)
    }

    @Test
    fun `index selects among multiple matches`() {
        val id = resolveSelectorNodeId(windows, finder, selector("class_name" to "android.widget.Button", "index" to 2))
        assertEquals("btn1", id)
    }

    @Test
    fun `no match throws NodeNotFound with a clear message`() {
        val exception =
            assertThrows<McpToolException.NodeNotFound> {
                resolveSelectorNodeId(windows, finder, selector("text" to "nonexistent"))
            }
        assertTrue(exception.message?.contains("nonexistent") == true)
    }

    @Test
    fun `no selector fields throws InvalidParams`() {
        assertThrows<McpToolException.InvalidParams> {
            resolveSelectorNodeId(windows, finder, buildJsonObject {})
        }
    }

    // ── selectorForNode ─────────────────────────────────────────────────────

    @Test
    fun `selectorForNode prefers resource_id`() {
        val node = tree.children[0] // "entry": resourceId + text + className all present
        val selector = selectorForNode(node)
        assertEquals("com.app:id/entry", selector?.get("resource_id")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `selectorForNode falls back to content_desc when no resource_id`() {
        val node = tree.children[1] // "close": contentDescription + text, no resourceId
        val selector = selectorForNode(node)
        assertEquals("Close dialog", selector?.get("content_desc")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `selectorForNode falls back to class_name when nothing else identifies the node`() {
        val anonymous =
            AccessibilityNodeData(id = "x", className = "android.view.View", bounds = BoundsData(0, 0, 1, 1))
        val selector = selectorForNode(anonymous)
        assertEquals("android.view.View", selector?.get("class_name")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `selectorForNode returns null for a node with none of the four fields`() {
        val blank = AccessibilityNodeData(id = "x", bounds = BoundsData(0, 0, 1, 1))
        assertNull(selectorForNode(blank))
    }

    // ── findNodeAtPoint ─────────────────────────────────────────────────────

    @Test
    fun `findNodeAtPoint returns the deepest clickable ancestor, not the non-clickable label`() {
        // (10, 550) is inside both "row" (clickable) and its child "row_label" (not clickable) —
        // this is exactly the M3 Clock-tab shape (a label with no click handling of its own).
        val found = findNodeAtPoint(windows, 10, 550)
        assertEquals("row", found?.id)
    }

    @Test
    fun `findNodeAtPoint falls back to the deepest node when nothing on the path is clickable`() {
        val found = findNodeAtPoint(windows, 10, 150) // inside "close" only, not clickable
        assertEquals("close", found?.id)
    }

    @Test
    fun `findNodeAtPoint returns null outside every window's bounds`() {
        assertNull(findNodeAtPoint(windows, 5000, 5000))
    }
}
