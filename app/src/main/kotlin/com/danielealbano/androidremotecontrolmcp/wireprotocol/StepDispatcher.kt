package com.danielealbano.androidremotecontrolmcp.wireprotocol

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ClickNodeTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.DismissKeyboardHandler
import com.danielealbano.androidremotecontrolmcp.mcp.tools.GetScreenStateHandler
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
import com.danielealbano.androidremotecontrolmcp.mcp.tools.getFreshWindows
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.findNodeAtPoint
import com.danielealbano.androidremotecontrolmcp.services.accessibility.pickSelectorCandidate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.selectorForNode
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * Translates a wire [Step] (`op` + `params`, plan 01 §5.1) into a call against the already-kept,
 * already-`@Inject`-constructible tool handlers, and the handler's [ToolResult] back into a
 * [StepResult] or [StepError].
 *
 * M3 root-cause fixes (droidthumb-server/docs/decisions-log.md) live here:
 *  - **Resolve at the moment of acting**: every selector-taking op now hands its raw `selector`
 *    straight to the tool (`click_node`/`type_append_text`/`type_clear_text`/`scroll_to_node`),
 *    which resolves it against its own single fresh tree parse, immediately before acting. There
 *    is no separate pre-resolve step here any more (the old `SelectorResolver` class did its own
 *    independent parse, whose `node_id` could go stale by the time the handler's *own* parse
 *    looked it up — that was the M3 staleness bug, not anything about `node_id` itself).
 *  - **Auto-wait**: [awaitSelectorPresent] runs before every selector-targeted action, and
 *    [waitForIdle] runs after every action — a flow no longer needs hand-inserted `wait_until`
 *    steps just to survive timing (see the M3 false-positive `run_flow` replay finding).
 *  - **Selectors from coordinates**: a coordinate `tap` describes whatever it actually hit as a
 *    selector in its output, so `save_flow` can prefer that over the raw coordinate.
 */
@Suppress("TooManyFunctions")
class StepDispatcher
    @Suppress("LongParameterList")
    @Inject
    constructor(
        private val getScreenState: GetScreenStateHandler,
        private val tapTool: TapTool,
        private val clickNodeTool: ClickNodeTool,
        private val typeAppendTextTool: TypeAppendTextTool,
        private val typeClearTextTool: TypeClearTextTool,
        private val scrollToNodeTool: ScrollToNodeTool,
        private val pressBackHandler: PressBackHandler,
        private val pressHomeHandler: PressHomeHandler,
        private val pressRecentsHandler: PressRecentsHandler,
        private val dismissKeyboardHandler: DismissKeyboardHandler,
        private val openAppHandler: OpenAppHandler,
        private val waitForNodeTool: WaitForNodeTool,
        private val waitForIdleTool: WaitForIdleTool,
        private val treeParser: AccessibilityTreeParser,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
    ) {
        /** Never throws — every failure becomes a [StepError], never an unhandled exception out of
         *  the transport client's per-step dispatch loop. The final catch-all is deliberate: any
         *  op's underlying handler could throw a non-[McpToolException] we haven't anticipated,
         *  and a crashed dispatch loop is worse than one step reported as `InternalError`.
         *  [CancellationException] is rethrown, never turned into a [StepError] — swallowing it
         *  would break structured concurrency (the coroutine would keep running and `send()` a
         *  fabricated reply instead of unwinding when the transport session is stopped mid-step). */
        @Suppress("TooGenericExceptionCaught")
        suspend fun dispatch(step: Step): WireMessage =
            try {
                val params = step.params as? JsonObject ?: JsonObject(emptyMap())
                StepResult(stepId = step.stepId, output = dispatchOp(step.op, params))
            } catch (e: CancellationException) {
                throw e
            } catch (e: McpToolException) {
                StepError(step.stepId, code = e::class.simpleName ?: "InternalError", message = e.message ?: "")
            } catch (e: Exception) {
                StepError(step.stepId, code = "InternalError", message = e.message ?: "unknown error")
            }

        /** `read_screen` is the only op with a real output shape; every other op's output is only
         *  ever the selector `tap` discovered at a coordinate (or empty, matching the fake
         *  device's own convention, plan 01 §5.4) — success is signalled by not throwing. Every
         *  action op waits for the UI to settle afterwards (best-effort; never throws on timeout —
         *  see [waitForIdle]), which is skipped when the action itself threw. */
        private suspend fun dispatchOp(
            op: String,
            params: JsonObject,
        ): JsonElement {
            if (op == "read_screen") return readScreen(params)
            val output = executeAction(op, params)
            waitForIdle()
            return output ?: EMPTY_OUTPUT
        }

        private suspend fun executeAction(
            op: String,
            params: JsonObject,
        ): JsonElement? =
            when (op) {
                "tap" -> {
                    tap(params)
                }

                "type_text" -> {
                    typeText(params)
                    null
                }

                "scroll_find" -> {
                    scrollFind(params)
                    null
                }

                "key" -> {
                    key(params)
                    null
                }

                "launch_app" -> {
                    launchApp(params)
                    null
                }

                "wait_until" -> {
                    waitUntil(params)
                    null
                }

                else -> {
                    throw McpToolException.InvalidParams("Unknown op: '$op'")
                }
            }

        /** Best-effort settle wait after every action — never throws, `WaitForIdleTool` reports a
         *  timeout as a normal (non-error) result, which is exactly right here: an action that
         *  didn't settle within [DEFAULT_IDLE_TIMEOUT_MS] still happened, it just gets no extra
         *  grace period once the cap is hit. */
        private suspend fun waitForIdle() {
            waitForIdleTool.execute(buildJsonObject { put("timeout", DEFAULT_IDLE_TIMEOUT_MS) })
        }

        private suspend fun readScreen(params: JsonObject): JsonElement {
            val result = getScreenState.execute(params)
            val text = result.content.filterIsInstance<ToolContent.Text>().joinToString("\n") { it.text }
            val image = result.content.filterIsInstance<ToolContent.Image>().firstOrNull()
            return buildJsonObject {
                put("tree", text)
                image?.let {
                    putJsonObject("screenshot") {
                        put("kind", "inline")
                        put("mime", it.mimeType)
                        put("data", it.data)
                    }
                }
            }
        }

        /**
         * `selector` (preferred) auto-waits for the target to appear, then resolves and acts
         * inside `click_node` itself. If the resolved node has since disappeared or turned out
         * non-clickable and a raw `at` coordinate was *also* supplied, falls back to it — "selectors
         * from coordinates" keeps the coordinate as a fallback, not just a replacement. A bare
         * `at` (no selector at all — the common case for a live-authored coordinate tap) has the
         * device describe whatever it actually hit as a selector in the step's output, so
         * `save_flow` can prefer that over the coordinate on replay.
         */
        private suspend fun tap(params: JsonObject): JsonElement? {
            val selector = params["selector"]?.jsonObject
            val at = params["at"]?.jsonObject
            if (selector != null) {
                awaitSelectorPresent(selector, autoWaitTimeoutMs(params))
                try {
                    clickNodeTool.execute(buildJsonObject { put("selector", selector) })
                } catch (e: McpToolException) {
                    val fallbackEligible =
                        at != null && (e is McpToolException.NodeNotFound || e is McpToolException.ActionFailed)
                    if (!fallbackEligible) throw e
                    tapTool.execute(at)
                }
                return null
            }
            if (at != null) {
                val discovered = discoverSelectorAt(at)
                tapTool.execute(at)
                return discovered
            }
            throw McpToolException.InvalidParams("tap requires 'selector' or 'at'")
        }

        /** Hit-tests the point *before* tapping (describing what was there when the tap was
         *  decided, not whatever the tap itself may have navigated to) — null if nothing usable
         *  was found there (no window covers the point, the node has none of the four selector
         *  fields, or the tree read itself failed for any reason), in which case the step's output
         *  stays empty and `save_flow` will mark any flow built from it as fragile
         *  (droidthumb-protocol's flow.schema.json `fragile` field). This is a best-effort
         *  enhancement to a coordinate tap, never a requirement for it to succeed — a raw
         *  coordinate tap must still work even when the accessibility service isn't ready or no
         *  window covers the point, so every failure here is swallowed, not propagated. */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun discoverSelectorAt(at: JsonObject): JsonElement? =
            try {
                val x = at["x"]?.jsonPrimitive?.floatOrNull?.toInt() ?: return null
                val y = at["y"]?.jsonPrimitive?.floatOrNull?.toInt() ?: return null
                val windows = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache).windows
                val node = findNodeAtPoint(windows, x, y) ?: return null
                val selector = selectorForNode(node) ?: return null
                buildJsonObject { put("selector", selector) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.d(TAG, "discoverSelectorAt: no selector recorded for this tap: ${e.message}")
                null
            }

        private suspend fun typeText(params: JsonObject): ToolResult {
            val selector =
                params["selector"]?.jsonObject
                    ?: throw McpToolException.InvalidParams("type_text requires 'selector'")
            awaitSelectorPresent(selector, autoWaitTimeoutMs(params))
            val clear = params["clear"]?.jsonPrimitive?.booleanOrNull ?: false
            return if (clear) {
                typeClearTextTool.execute(buildJsonObject { put("selector", selector) })
            } else {
                val text =
                    params["text"]?.jsonPrimitive?.contentOrNull
                        ?: throw McpToolException.InvalidParams("type_text requires 'text' unless clear:true")
                typeAppendTextTool.execute(
                    buildJsonObject {
                        put("selector", selector)
                        put("text", text)
                    },
                )
            }
        }

        /** `scroll_to_node` now owns the whole "not even in the tree yet" retry loop itself
         *  (`direction`/`max_scrolls` forwarded as-is), re-resolving the selector fresh on every
         *  attempt — see that tool for why this moved out of here (M3 staleness fix). */
        private suspend fun scrollFind(params: JsonObject): ToolResult {
            val selector =
                params["selector"]?.jsonObject
                    ?: throw McpToolException.InvalidParams("scroll_find requires 'selector'")
            return scrollToNodeTool.execute(
                buildJsonObject {
                    put("selector", selector)
                    params["direction"]?.let { put("direction", it) }
                    params["max_scrolls"]?.let { put("max_scrolls", it) }
                },
            )
        }

        private suspend fun key(params: JsonObject): ToolResult {
            val key =
                params["key"]?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("key requires 'key'")
            return when (key) {
                "back" -> {
                    pressBackHandler.execute(null)
                }

                "home" -> {
                    pressHomeHandler.execute(null)
                }

                "recents" -> {
                    pressRecentsHandler.execute(null)
                }

                "dismiss_keyboard" -> {
                    dismissKeyboardHandler.execute(null)
                }

                else -> {
                    throw McpToolException.InvalidParams(
                        "key must be one of: back, home, recents, dismiss_keyboard. Got: '$key'",
                    )
                }
            }
        }

        /** `fresh` (default false at the wire level; `save_flow` defaults it to true when saving —
         *  see droidthumb-server's save-flow.ts) clears the app's existing task first, so the step
         *  lands at the launcher activity instead of resuming wherever a previous session left it —
         *  the M3 "known start state" fix. */
        private suspend fun launchApp(params: JsonObject): ToolResult {
            val pkg =
                params["package"]?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("launch_app requires 'package'")
            val fresh = params["fresh"]?.jsonPrimitive?.booleanOrNull ?: false
            return openAppHandler.execute(
                buildJsonObject {
                    put("package_id", pkg)
                    put("fresh", fresh)
                },
            )
        }

        /**
         * `absent: true` (design doc §7.2's full `wait_until` row) isn't supported by this build —
         * a forward-compatibility placeholder rejection, not a spec requirement (mvp-handover's
         * own simplified table only lists `selector`+`timeout_ms`).
         */
        @Suppress("ThrowsCount")
        private suspend fun waitUntil(params: JsonObject) {
            if (params["absent"]?.jsonPrimitive?.booleanOrNull == true) {
                throw McpToolException.InvalidParams("wait_until: absent=true is not supported by this build")
            }
            val selector =
                params["selector"]?.jsonObject
                    ?: throw McpToolException.InvalidParams("wait_until requires 'selector'")
            val timeoutMs =
                params["timeout_ms"]?.jsonPrimitive?.longOrNull
                    ?: throw McpToolException.InvalidParams("wait_until requires 'timeout_ms'")
            awaitSelectorPresent(selector, timeoutMs)
        }

        /**
         * Shared by the explicit `wait_until` op and the auto-wait every selector-targeted action
         * (`tap`/`type_text`) now runs before acting. `timeout_ms` is forwarded as-is to
         * `WaitForNodeTool`, whose own cap was raised from 30s to 120s (see that file) so a step
         * near the server's own `timeout_ms + STEP_TIMEOUT_MARGIN_MS` transport timeout doesn't
         * fail client-side before the server even times out.
         *
         * `WaitForNodeTool` never throws on a timeout — it returns a normal, successful
         * [ToolResult] whose text is `{"found": false, ...}` (`UtilityTools.kt`). `found: false` is
         * parsed back out of the tool's own JSON text and turned into a `NodeNotFound` — the one
         * place the "every non-`read_screen` op reports `{}`" convention doesn't apply, since a
         * caller (server-driven flow or live session) needs to be able to see a timeout and react
         * to it rather than have it look identical to success.
         */
        private suspend fun awaitSelectorPresent(
            selector: JsonObject,
            timeoutMs: Long,
        ) {
            val (by, value, _) = pickSelectorCandidate(selector)
            val result =
                waitForNodeTool.execute(
                    buildJsonObject {
                        put("by", byWireName(by))
                        put("value", value)
                        put("timeout", timeoutMs)
                    },
                )
            if (foundFalse(result)) {
                throw McpToolException.NodeNotFound(
                    "selector {${by.name.lowercase()}: \"$value\"} never appeared within ${timeoutMs}ms",
                )
            }
        }

        private fun autoWaitTimeoutMs(params: JsonObject): Long =
            params["timeout_ms"]?.jsonPrimitive?.longOrNull ?: DEFAULT_AUTO_WAIT_TIMEOUT_MS

        /** True iff [result]'s JSON text (after `McpToolUtils.untrustedTextResult`'s warning line)
         *  is `WaitForNodeTool`'s timeout shape, `{"found": false, ...}`. Any other shape — parse
         *  failure included — is treated as found, since only an explicit `false` is a known
         *  failure signal; anything else is `WaitForNodeTool`'s own success case to trust as-is. */
        private fun foundFalse(result: ToolResult): Boolean {
            val text =
                result.content
                    .filterIsInstance<ToolContent.Text>()
                    .firstOrNull()
                    ?.text ?: return false
            val jsonText = text.substringAfter('\n', text)
            val found =
                runCatching {
                    Json
                        .parseToJsonElement(jsonText)
                        .jsonObject["found"]
                        ?.jsonPrimitive
                        ?.booleanOrNull
                }.getOrNull()
            return found == false
        }

        private fun byWireName(by: FindBy): String =
            when (by) {
                FindBy.RESOURCE_ID -> "resource_id"
                FindBy.CONTENT_DESC -> "content_desc"
                FindBy.TEXT -> "text"
                FindBy.CLASS_NAME -> "class_name"
            }

        companion object {
            private const val TAG = "MCP:StepDispatcher"
            private val EMPTY_OUTPUT = JsonObject(emptyMap())
            private const val DEFAULT_AUTO_WAIT_TIMEOUT_MS = 5000L
            private const val DEFAULT_IDLE_TIMEOUT_MS = 2000L
        }
    }
