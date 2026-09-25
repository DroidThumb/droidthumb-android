package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeLock
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * MCP tool: get_clipboard
 *
 * Gets the current clipboard text content. Accessibility services are exempt
 * from Android 10+ clipboard access restrictions.
 */
class GetClipboardTool
    @Inject
    constructor(
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("ThrowsCount", "UnusedParameter")
        suspend fun execute(arguments: JsonObject?): ToolResult {
            val context =
                accessibilityServiceProvider.getContext()
                    ?: throw McpToolException.PermissionDenied(
                        "Accessibility service is not enabled",
                    )

            return try {
                val clipboardManager =
                    context.getSystemService(ClipboardManager::class.java)
                        ?: throw McpToolException.ActionFailed(
                            "ClipboardManager not available",
                        )

                val clip = clipboardManager.primaryClip
                val text =
                    if (clip != null && clip.itemCount > 0) {
                        clip.getItemAt(0).text?.toString()
                    } else {
                        null
                    }

                Log.d(TAG, "get_clipboard: text=${if (text != null) "${text.length} chars" else "null"}")

                val resultJson =
                    buildJsonObject {
                        put("text", text)
                    }
                McpToolUtils.untrustedTextResult(Json.encodeToString(resultJson))
            } catch (e: McpToolException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException")
                e: Exception,
            ) {
                Log.e(TAG, "Clipboard access failed", e)
                throw McpToolException.ActionFailed(
                    "Clipboard access failed: ${e.message}",
                )
            }
        }

        companion object {
            private const val TAG = "MCP:GetClipboardTool"
            const val TOOL_NAME = "get_clipboard"
        }
    }

/**
 * MCP tool: set_clipboard
 *
 * Sets the clipboard content to the specified text.
 */
class SetClipboardTool
    @Inject
    constructor(
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): ToolResult {
            val text =
                arguments
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'text'")

            val context =
                accessibilityServiceProvider.getContext()
                    ?: throw McpToolException.PermissionDenied(
                        "Accessibility service is not enabled",
                    )

            return try {
                val clipboardManager =
                    context.getSystemService(ClipboardManager::class.java)
                        ?: throw McpToolException.ActionFailed(
                            "ClipboardManager not available",
                        )

                val clip = ClipData.newPlainText("MCP", text)
                clipboardManager.setPrimaryClip(clip)

                Log.d(TAG, "set_clipboard: set ${text.length} chars")

                McpToolUtils.textResult("Clipboard set successfully (${text.length} characters)")
            } catch (e: McpToolException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException")
                e: Exception,
            ) {
                Log.e(TAG, "Clipboard set failed", e)
                throw McpToolException.ActionFailed(
                    "Clipboard set failed: ${e.message}",
                )
            }
        }

        companion object {
            private const val TAG = "MCP:SetClipboardTool"
            const val TOOL_NAME = "set_clipboard"
        }
    }

/**
 * MCP tool: wait_for_node
 *
 * Polls the accessibility tree every [POLL_INTERVAL_MS] until a node matching
 * the criteria appears, or the timeout is reached.
 */
class WaitForNodeTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
    ) {
        @Suppress(
            "CyclomaticComplexity",
            "LongMethod",
            "ThrowsCount",
            "InstanceOfCheckForException",
            "NestedBlockDepth",
        )
        suspend fun execute(arguments: JsonObject?): ToolResult {
            // Validate parameters
            val byStr =
                arguments?.get("by")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'by'")

            val value =
                arguments["value"]?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'value'")

            if (value.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'value' must be non-empty")
            }

            val findBy =
                mapFindBy(byStr)
                    ?: throw McpToolException.InvalidParams(
                        "Invalid 'by' value: '$byStr'. Must be one of: text, content_desc, resource_id, class_name",
                    )

            val timeout =
                arguments["timeout"]?.jsonPrimitive?.longOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'timeout'")
            if (timeout <= 0 || timeout > MAX_TIMEOUT_MS) {
                throw McpToolException.InvalidParams(
                    "Timeout must be between 1 and $MAX_TIMEOUT_MS ms, got: $timeout",
                )
            }

            // Poll loop
            val startTime = SystemClock.elapsedRealtime()
            var attemptCount = 0

            while (SystemClock.elapsedRealtime() - startTime < timeout) {
                attemptCount++

                try {
                    // Phase 1: lightweight raw-node existence check (no intermediate objects, no cache)
                    val exists = rawNodeExists(findBy, value, accessibilityServiceProvider)

                    if (exists) {
                        // Phase 2: full parse to build response and populate cache
                        val multiWindowResult = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
                        val elements = elementFinder.findElements(multiWindowResult.windows, findBy, value, false)

                        if (elements.isNotEmpty()) {
                            val element = elements.first()
                            val elapsed = SystemClock.elapsedRealtime() - startTime
                            Log.d(TAG, "wait_for_node: found after ${elapsed}ms ($attemptCount attempts)")

                            val resultJson =
                                buildJsonObject {
                                    put("found", true)
                                    put("elapsedMs", elapsed)
                                    put("attempts", attemptCount)
                                    put("node", McpToolUtils.buildNodeJson(element))
                                }
                            return McpToolUtils.untrustedTextResult(Json.encodeToString(resultJson))
                        }
                        // Node disappeared between raw check and full parse — continue polling
                        Log.d(TAG, "wait_for_node: raw check found match but full parse did not, retrying")
                    }
                } catch (e: McpToolException) {
                    // If accessibility service becomes unavailable during polling, propagate
                    if (e is McpToolException.PermissionDenied) throw e
                    // Other errors (e.g., stale tree) — retry on next poll
                    Log.d(TAG, "wait_for_node: poll attempt $attemptCount failed: ${e.message}")
                }

                delay(POLL_INTERVAL_MS)
            }

            val timeoutJson =
                buildJsonObject {
                    put("found", false)
                    put("elapsedMs", timeout)
                    put("attempts", attemptCount)
                    put(
                        "message",
                        "Operation timed out after ${timeout}ms waiting for node " +
                            "(by=$byStr, value='$value'). Retry if the operation is long-running.",
                    )
                }
            return McpToolUtils.untrustedTextResult(Json.encodeToString(timeoutJson))
        }

        companion object {
            private const val TAG = "MCP:WaitForNodeTool"
            const val TOOL_NAME = "wait_for_node"
            private const val POLL_INTERVAL_MS = 150L
            private const val MAX_TIMEOUT_MS = 30000L
        }
    }

/**
 * Lightweight raw-node search that walks [AccessibilityNodeInfo] trees directly
 * without creating intermediate [com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData]
 * objects or populating [AccessibilityNodeCache]. Returns true on first match (early exit).
 *
 * Refreshes root nodes and virtual/Compose child nodes using the same logic as
 * [AccessibilityTreeParser.parseNode] to ensure fresh data from Jetpack Compose
 * and other virtual node providers.
 *
 * Child nodes obtained via [AccessibilityNodeInfo.getChild] are NOT recycled.
 * On API 33+ (this project's minSdk) [AccessibilityNodeInfo.recycle] is a no-op.
 * This matches the pattern used by [TreeFingerprint.populateFingerprintFromRaw].
 *
 * @param findBy The search criteria type.
 * @param value The search value (matched case-insensitive contains, same as
 *   [com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder.matchesValue]
 *   with exactMatch=false).
 * @param accessibilityServiceProvider Provider for accessibility windows and root nodes.
 * @return true if at least one node matching the criteria exists.
 * @throws McpToolException.PermissionDenied if accessibility service is not ready.
 * @throws McpToolException.ActionFailed if no windows or root nodes are available.
 */
internal fun rawNodeExists(
    findBy: FindBy,
    value: String,
    accessibilityServiceProvider: AccessibilityServiceProvider,
): Boolean =
    // Serialize the clear+read critical section against concurrent MCP requests (see
    // AccessibilityTreeLock): a parallel request's cache clear must not wipe the framework tree
    // mid-traversal of this poll read.
    synchronized(AccessibilityTreeLock.monitor) {
        rawNodeExistsLocked(findBy, value, accessibilityServiceProvider)
    }

@Suppress("ThrowsCount", "ReturnCount", "NestedBlockDepth")
private fun rawNodeExistsLocked(
    findBy: FindBy,
    value: String,
    accessibilityServiceProvider: AccessibilityServiceProvider,
): Boolean {
    if (!accessibilityServiceProvider.isReady()) {
        throw McpToolException.PermissionDenied(
            "Accessibility service not enabled or not ready. " +
                "Please enable it in Android Settings > Accessibility.",
        )
    }

    // Drop the framework's accessibility node cache before this poll read. Chromium WebView
    // suppresses/throttles the content-changed events that invalidate that cache, so a node added
    // by a JavaScript DOM change would otherwise never appear here (refresh() cannot re-fetch what
    // the cache still serves as current) and wait_for_node would time out on present content. See
    // AccessibilityServiceProvider.clearFrameworkNodeCache.
    accessibilityServiceProvider.clearFrameworkNodeCache()

    val accessibilityWindows = accessibilityServiceProvider.getAccessibilityWindows()

    if (accessibilityWindows.isNotEmpty()) {
        try {
            for (window in accessibilityWindows) {
                val rootNode = window.root ?: continue
                rootNode.refresh()
                if (rawNodeMatchesRecursive(rootNode, findBy, value, 0)) {
                    return true
                }
            }
            // All windows processed, no match
            return false
        } finally {
            for (w in accessibilityWindows) {
                @Suppress("DEPRECATION")
                w.recycle()
            }
        }
    }

    // Fallback to single-window mode
    val rootNode =
        accessibilityServiceProvider.getRootNode()
            ?: throw McpToolException.ActionFailed(
                "No windows available and no active window root node. " +
                    "The screen may be transitioning.",
            )
    rootNode.refresh()
    return rawNodeMatchesRecursive(rootNode, findBy, value, 0)
}

/**
 * Recursively walks a raw [AccessibilityNodeInfo] tree checking for a match.
 * Returns true immediately on first match (early exit). Refreshes virtual/Compose
 * child nodes before reading their properties.
 *
 * Child nodes obtained via [AccessibilityNodeInfo.getChild] are NOT recycled —
 * [AccessibilityNodeInfo.recycle] is a no-op on API 33+ (this project's minSdk).
 */
@Suppress("ReturnCount")
private fun rawNodeMatchesRecursive(
    node: AccessibilityNodeInfo,
    findBy: FindBy,
    value: String,
    depth: Int,
): Boolean {
    // Check current node
    val nodeValue =
        when (findBy) {
            FindBy.TEXT -> node.text?.toString()
            FindBy.CONTENT_DESC -> node.contentDescription?.toString()
            FindBy.RESOURCE_ID -> node.viewIdResourceName
            FindBy.CLASS_NAME -> node.className?.toString()
        }

    if (nodeValue != null && nodeValue.contains(value, ignoreCase = true)) {
        return true
    }

    // Depth guard — same limit as AccessibilityTreeParser.MAX_TREE_DEPTH
    if (depth >= AccessibilityTreeParser.MAX_TREE_DEPTH) {
        return false
    }

    // Recurse into children
    val childCount = node.childCount
    for (i in 0 until childCount) {
        val child = node.getChild(i) ?: continue

        // Refresh virtual/Compose nodes — same logic as AccessibilityTreeParser.parseNode
        if (child.viewIdResourceName == null ||
            child.availableExtraData.contains(AccessibilityTreeParser.COMPOSE_SEMANTICS_ID_KEY)
        ) {
            child.refresh()
        }

        if (rawNodeMatchesRecursive(child, findBy, value, depth + 1)) {
            return true
        }
    }

    return false
}

/**
 * MCP tool: wait_for_idle
 *
 * Waits for the UI to become idle by comparing accessibility tree fingerprints
 * across consecutive snapshots. Uses a lightweight path that walks raw
 * [android.view.accessibility.AccessibilityNodeInfo] trees directly, without
 * creating intermediate data objects or populating the node cache. Considers UI
 * idle when two consecutive snapshots (separated by [IDLE_CHECK_INTERVAL_MS])
 * meet the [DEFAULT_MATCH_PERCENTAGE] threshold (or the caller-provided
 * `match_percentage`).
 */
class WaitForIdleTool
    @Inject
    constructor(
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        private val treeFingerprint = TreeFingerprint()

        @Suppress(
            "CyclomaticComplexity",
            "NestedBlockDepth",
            "ThrowsCount",
            "InstanceOfCheckForException",
            "LongMethod",
        )
        suspend fun execute(arguments: JsonObject?): ToolResult {
            val timeout =
                arguments?.get("timeout")?.jsonPrimitive?.longOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'timeout'")
            if (timeout <= 0 || timeout > MAX_TIMEOUT_MS) {
                throw McpToolException.InvalidParams(
                    "Timeout must be between 1 and $MAX_TIMEOUT_MS ms, got: $timeout",
                )
            }

            val matchPercentage =
                arguments["match_percentage"]
                    ?.jsonPrimitive
                    ?.longOrNull
                    ?.toInt()
                    ?: DEFAULT_MATCH_PERCENTAGE
            if (matchPercentage < 0 || matchPercentage > TreeFingerprint.FULL_MATCH_PERCENTAGE) {
                throw McpToolException.InvalidParams(
                    "match_percentage must be between 0 and " +
                        "${TreeFingerprint.FULL_MATCH_PERCENTAGE}, got: $matchPercentage",
                )
            }

            val startTime = SystemClock.elapsedRealtime()
            var previousFingerprint: IntArray? = null
            var consecutiveIdleChecks = 0
            var lastSimilarity = 0

            while (SystemClock.elapsedRealtime() - startTime < timeout) {
                try {
                    val currentFingerprint = generateCurrentFingerprint()

                    if (previousFingerprint != null) {
                        val similarity = treeFingerprint.compare(previousFingerprint, currentFingerprint)
                        lastSimilarity = similarity

                        if (similarity >= matchPercentage) {
                            consecutiveIdleChecks++
                            if (consecutiveIdleChecks >= REQUIRED_IDLE_CHECKS) {
                                val elapsed = SystemClock.elapsedRealtime() - startTime
                                Log.d(TAG, "wait_for_idle: UI idle after ${elapsed}ms (similarity=$similarity%)")
                                val resultJson =
                                    buildJsonObject {
                                        put("message", "UI is idle")
                                        put("elapsedMs", elapsed)
                                        put("similarity", similarity)
                                    }
                                return McpToolUtils.untrustedTextResult(Json.encodeToString(resultJson))
                            }
                        } else {
                            consecutiveIdleChecks = 0
                        }
                    }

                    previousFingerprint = currentFingerprint
                } catch (e: McpToolException) {
                    if (e is McpToolException.PermissionDenied) throw e
                    // Tree parse failures during transitions — reset idle counter
                    consecutiveIdleChecks = 0
                    previousFingerprint = null
                }

                delay(IDLE_CHECK_INTERVAL_MS)
            }

            val elapsed = SystemClock.elapsedRealtime() - startTime
            val resultJson =
                buildJsonObject {
                    put(
                        "message",
                        "Operation timed out after ${elapsed}ms waiting for UI idle." +
                            " Retry if the operation is long-running.",
                    )
                    put("elapsedMs", elapsed)
                    put("similarity", lastSimilarity)
                }
            return McpToolUtils.untrustedTextResult(Json.encodeToString(resultJson))
        }

        private fun generateCurrentFingerprint(): IntArray =
            // Serialize the clear+read critical section against concurrent MCP requests (see
            // AccessibilityTreeLock): a parallel request's cache clear must not wipe the framework
            // tree mid-traversal of this fingerprint read.
            synchronized(AccessibilityTreeLock.monitor) {
                generateCurrentFingerprintLocked()
            }

        @Suppress("ThrowsCount")
        private fun generateCurrentFingerprintLocked(): IntArray {
            if (!accessibilityServiceProvider.isReady()) {
                throw McpToolException.PermissionDenied(
                    "Accessibility service not enabled or not ready. " +
                        "Please enable it in Android Settings > Accessibility.",
                )
            }

            // Drop the framework's accessibility node cache before fingerprinting. Chromium WebView
            // suppresses/throttles the content-changed events that invalidate that cache, so without
            // this the fingerprint would be computed from a stale tree that never reflects ongoing
            // JavaScript DOM changes — reporting the UI as idle while it is still mutating. See
            // AccessibilityServiceProvider.clearFrameworkNodeCache.
            accessibilityServiceProvider.clearFrameworkNodeCache()

            val accessibilityWindows = accessibilityServiceProvider.getAccessibilityWindows()

            if (accessibilityWindows.isNotEmpty()) {
                try {
                    val rootNodes =
                        accessibilityWindows.mapNotNull { window ->
                            window.root
                        }
                    if (rootNodes.isEmpty()) {
                        throw McpToolException.ActionFailed("All windows returned null root nodes.")
                    }
                    return treeFingerprint.generateFromRawNodes(rootNodes)
                } finally {
                    for (w in accessibilityWindows) {
                        @Suppress("DEPRECATION")
                        w.recycle()
                    }
                }
            }

            // Fallback to single root node
            val rootNode =
                accessibilityServiceProvider.getRootNode()
                    ?: throw McpToolException.ActionFailed(
                        "No windows available and no active window root node. " +
                            "The screen may be transitioning.",
                    )
            return treeFingerprint.generateFromRawNodes(listOf(rootNode))
        }

        companion object {
            private const val TAG = "MCP:WaitForIdleTool"
            const val TOOL_NAME = "wait_for_idle"
            private const val IDLE_CHECK_INTERVAL_MS = 250L
            private const val MAX_TIMEOUT_MS = 30000L
            private const val DEFAULT_MATCH_PERCENTAGE = 100

            /** Number of consecutive checks meeting the similarity threshold required to consider UI idle. */
            private const val REQUIRED_IDLE_CHECKS = 2
        }
    }
