package com.danielealbano.androidremotecontrolmcp.wireprotocol

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.tools.getFreshWindows
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * Picks which field of a wire selector object (`{resource_id?, content_desc?, text?, class_name?,
 * index?}`, plan 01 §5.1.1) to search by: first present field wins, in the §7.3 priority order
 * `resource_id` -> `content_desc` -> `text` -> `class_name`. `exactMatch = true` for
 * `resource_id`/`class_name` (identifiers — a substring match risks a wrong node), `false`
 * (case-insensitive contains) for `content_desc`/`text` (human-readable labels, where an
 * LLM-supplied partial label should still resolve). This split is this build's own judgment call —
 * mvp-handover's selector shape doesn't specify match semantics.
 *
 * Shared by [SelectorResolver] (node_id-based ops) and `StepDispatcher.waitUntil`
 * (`WaitForNodeTool`'s `by`/`value` shape) so the two never drift apart.
 */
@Suppress("ReturnCount") // one early return per selector field, in priority order — clearer than nesting
internal fun pickSelectorCandidate(selector: JsonObject): Triple<FindBy, String, Boolean> {
    selector["resource_id"]?.jsonPrimitive?.contentOrNull?.let { return Triple(FindBy.RESOURCE_ID, it, true) }
    selector["content_desc"]?.jsonPrimitive?.contentOrNull?.let { return Triple(FindBy.CONTENT_DESC, it, false) }
    selector["text"]?.jsonPrimitive?.contentOrNull?.let { return Triple(FindBy.TEXT, it, false) }
    selector["class_name"]?.jsonPrimitive?.contentOrNull?.let { return Triple(FindBy.CLASS_NAME, it, true) }
    throw McpToolException.InvalidParams(
        "selector must have at least one of resource_id, content_desc, text, class_name",
    )
}

/** Resolves a wire selector object to a `node_id` — the shape every `node_id`-based kept handler
 *  (`click_node`, `type_append_text`, `type_clear_text`, `scroll_to_node`) actually needs, since
 *  none of them accept a raw selector. */
class SelectorResolver
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
    ) {
        /**
         * @throws McpToolException.NodeNotFound if nothing in the current tree matches.
         * @throws McpToolException.InvalidParams if [selector] has none of the four field
         *   candidates.
         * @throws McpToolException.PermissionDenied if the accessibility service isn't ready
         *   ([getFreshWindows]) — a real, non-retryable failure, distinct from "not found yet".
         */
        suspend fun resolve(selector: JsonObject): String {
            val (by, value, exact) = pickSelectorCandidate(selector)
            val windows = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache).windows
            val matches = elementFinder.findElements(windows, by, value, exact)
            val index = selector["index"]?.jsonPrimitive?.intOrNull ?: 0
            return matches.getOrNull(index)?.id
                ?: throw McpToolException.NodeNotFound(
                    "No node matched selector {${by.name.lowercase()}: \"$value\"}" +
                        if (index != 0) " at index $index (${matches.size} match(es) found)" else "",
                )
        }
    }
