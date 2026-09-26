package com.danielealbano.androidremotecontrolmcp.services.accessibility

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Picks which field of a wire selector object (`{resource_id?, content_desc?, text?, class_name?,
 * index?}`, plan 01 §5.1.1) to search by: first present field wins, in the §7.3 priority order
 * `resource_id` -> `content_desc` -> `text` -> `class_name`. `exactMatch = true` for
 * `resource_id`/`class_name` (identifiers — a substring match risks a wrong node), `false`
 * (case-insensitive contains) for `content_desc`/`text` (human-readable labels, where an
 * LLM-supplied partial label should still resolve). This split is this build's own judgment call —
 * mvp-handover's selector shape doesn't specify match semantics.
 *
 * Shared by [resolveSelectorNodeId] (every selector-taking tool) and `StepDispatcher.waitUntil`
 * (`WaitForNodeTool`'s `by`/`value` shape) so the two never drift apart.
 */
@Suppress("ReturnCount") // one early return per selector field, in priority order — clearer than nesting
fun pickSelectorCandidate(selector: JsonObject): Triple<FindBy, String, Boolean> {
    selector["resource_id"]?.jsonPrimitive?.contentOrNull?.let { return Triple(FindBy.RESOURCE_ID, it, true) }
    selector["content_desc"]?.jsonPrimitive?.contentOrNull?.let { return Triple(FindBy.CONTENT_DESC, it, false) }
    selector["text"]?.jsonPrimitive?.contentOrNull?.let { return Triple(FindBy.TEXT, it, false) }
    selector["class_name"]?.jsonPrimitive?.contentOrNull?.let { return Triple(FindBy.CLASS_NAME, it, true) }
    throw McpToolException.InvalidParams(
        "selector must have at least one of resource_id, content_desc, text, class_name",
    )
}

/**
 * Resolves a wire selector object to a `node_id`, against an already-fetched [windows] snapshot —
 * never fetches its own. This is the fix for the M3 staleness bug (droidthumb-server's
 * docs/m3-results.md): every selector-taking tool (`click_node`, `type_append_text`,
 * `type_clear_text`, `scroll_to_node`) now resolves its own selector as the very next thing after
 * its own single `getFreshWindows()` call, immediately before acting — there is no longer a
 * separate earlier resolve step (the old `SelectorResolver` class, which did its own independent
 * fresh parse) whose `node_id` could go stale by the time a second, later parse looked it up.
 *
 * @throws McpToolException.NodeNotFound if nothing in [windows] matches.
 * @throws McpToolException.InvalidParams if [selector] has none of the four field candidates.
 */
fun resolveSelectorNodeId(
    windows: List<WindowData>,
    elementFinder: ElementFinder,
    selector: JsonObject,
): String {
    val (by, value, exact) = pickSelectorCandidate(selector)
    val matches = elementFinder.findElements(windows, by, value, exact)
    val index = selector["index"]?.jsonPrimitive?.intOrNull ?: 0
    return matches.getOrNull(index)?.id
        ?: throw McpToolException.NodeNotFound(
            "No node matched selector {${by.name.lowercase()}: \"$value\"}" +
                if (index != 0) " at index $index (${matches.size} match(es) found)" else "",
        )
}

/**
 * Builds a wire selector object describing [node], using the same field priority
 * [pickSelectorCandidate] resolves by — the reverse direction, for M3's "selectors from
 * coordinates" fix (droidthumb-server/docs/decisions-log.md): when a live session taps by raw
 * coordinate, the device describes whatever it actually tapped as a selector, so `save_flow` can
 * prefer that selector over the coordinate on replay. Returns null only if [node] has none of the
 * four candidate fields at all (in practice this basically never happens — `class_name` is always
 * populated for a real Android view — but an anonymous node is possible in principle).
 */
fun selectorForNode(node: AccessibilityNodeData): JsonObject? {
    val (key, value) =
        when {
            !node.resourceId.isNullOrEmpty() -> "resource_id" to node.resourceId
            !node.contentDescription.isNullOrEmpty() -> "content_desc" to node.contentDescription
            !node.text.isNullOrEmpty() -> "text" to node.text
            !node.className.isNullOrEmpty() -> "class_name" to node.className
            else -> return null
        }
    return buildJsonObject { put(key, value) }
}

/**
 * Hit-tests [windows] (in their existing z-order) for the node at ([x], [y]), walking down to the
 * deepest node whose bounds contain the point, then back up to the nearest ancestor (inclusive)
 * that is actually clickable — a selector built from a non-clickable descendant (e.g. a label
 * `TextView` inside a clickable row) would just fail `click_node`'s own "not clickable" check on
 * replay. Falls back to the deepest node found if nothing on the path is clickable, so a selector
 * is still recorded (better than nothing, and `tap`'s `at` coordinate stays as the replay fallback
 * either way).
 *
 * @return null if [x], [y] isn't inside any window's tree at all.
 */
fun findNodeAtPoint(
    windows: List<WindowData>,
    x: Int,
    y: Int,
): AccessibilityNodeData? {
    for (window in windows) {
        val path = mutableListOf<AccessibilityNodeData>()
        collectPathAtPoint(window.tree, x, y, path)
        if (path.isNotEmpty()) {
            return path.lastOrNull { it.clickable } ?: path.last()
        }
    }
    return null
}

private fun collectPathAtPoint(
    node: AccessibilityNodeData,
    x: Int,
    y: Int,
    path: MutableList<AccessibilityNodeData>,
): Boolean {
    if (!containsPoint(node.bounds, x, y)) return false
    path.add(node)
    for (child in node.children) {
        if (collectPathAtPoint(child, x, y, path)) break
    }
    return true
}

private fun containsPoint(
    bounds: BoundsData,
    x: Int,
    y: Int,
): Boolean {
    val withinX = x >= bounds.left && x < bounds.right
    val withinY = y >= bounds.top && y < bounds.bottom
    return withinX && withinY
}
