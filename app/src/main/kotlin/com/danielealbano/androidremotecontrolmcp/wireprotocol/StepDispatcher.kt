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
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ScrollTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TapTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolContent
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolResult
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeAppendTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.TypeClearTextTool
import com.danielealbano.androidremotecontrolmcp.mcp.tools.WaitForNodeTool
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * Translates a wire [Step] (`op` + `params`, plan 01 §5.1) into a call against the already-kept,
 * already-`@Inject`-constructible tool handlers, and the handler's [ToolResult] back into a
 * [StepResult] or [StepError]. New glue code only — no handler logic changes.
 */
@Suppress("TooManyFunctions") // one function per op (7) plus dispatch/executeAction/readScreen/byWireName/foundFalse
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
        private val scrollTool: ScrollTool,
        private val pressBackHandler: PressBackHandler,
        private val pressHomeHandler: PressHomeHandler,
        private val pressRecentsHandler: PressRecentsHandler,
        private val dismissKeyboardHandler: DismissKeyboardHandler,
        private val openAppHandler: OpenAppHandler,
        private val waitForNodeTool: WaitForNodeTool,
        private val selectorResolver: SelectorResolver,
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

        /** `read_screen` is the only op with a real output shape; every other op's [ToolResult] is
         *  discarded and reported as an empty object (matching the fake device's own convention,
         *  plan 01 §5.4) — success is signalled by not throwing, not by the output's content. */
        private suspend fun dispatchOp(
            op: String,
            params: JsonObject,
        ): JsonElement {
            if (op == "read_screen") return readScreen(params)
            executeAction(op, params)
            return EMPTY_OUTPUT
        }

        private suspend fun executeAction(
            op: String,
            params: JsonObject,
        ) {
            when (op) {
                "tap" -> tap(params)
                "type_text" -> typeText(params)
                "scroll_find" -> scrollFind(params)
                "key" -> key(params)
                "launch_app" -> launchApp(params)
                "wait_until" -> waitUntil(params)
                else -> throw McpToolException.InvalidParams("Unknown op: '$op'")
            }
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

        private suspend fun tap(params: JsonObject): ToolResult {
            val selector = params["selector"]?.jsonObject
            val at = params["at"]?.jsonObject
            return when {
                selector != null -> {
                    clickNodeTool.execute(buildJsonObject { put("node_id", selectorResolver.resolve(selector)) })
                }

                at != null -> {
                    tapTool.execute(at)
                }

                else -> {
                    throw McpToolException.InvalidParams("tap requires 'selector' or 'at'")
                }
            }
        }

        private suspend fun typeText(params: JsonObject): ToolResult {
            val selector =
                params["selector"]?.jsonObject
                    ?: throw McpToolException.InvalidParams("type_text requires 'selector'")
            val nodeId = selectorResolver.resolve(selector)
            val clear = params["clear"]?.jsonPrimitive?.booleanOrNull ?: false
            return if (clear) {
                typeClearTextTool.execute(buildJsonObject { put("node_id", nodeId) })
            } else {
                val text =
                    params["text"]?.jsonPrimitive?.contentOrNull
                        ?: throw McpToolException.InvalidParams("type_text requires 'text' unless clear:true")
                typeAppendTextTool.execute(
                    buildJsonObject {
                        put("node_id", nodeId)
                        put("text", text)
                    },
                )
            }
        }

        /**
         * `scroll_to_node` (the kept handler) requires the target already resolvable in the
         * current tree (just off-screen) — it can't handle a virtualized list that hasn't
         * rendered the target at all. This loop covers that gap: try to resolve the selector;
         * if found, delegate to `scroll_to_node` (which auto-tries both directions); if not
         * found *at all*, perform one blind gesture-scroll (`ScrollTool`, no node id) and retry.
         * `max_scrolls` is clamped to [MAX_SCROLLS_HARD_CAP] regardless of what the wire step
         * requests, bounding worst-case step latency against a malformed or hostile value.
         * `NodeNotFound` from a resolve attempt below means "keep blind-scrolling," not a real
         * failure — that's what the `@Suppress("SwallowedException")` below is about.
         */
        @Suppress("SwallowedException")
        private suspend fun scrollFind(params: JsonObject): ToolResult {
            val selector =
                params["selector"]?.jsonObject
                    ?: throw McpToolException.InvalidParams("scroll_find requires 'selector'")
            val direction = params["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
            val maxScrolls =
                (params["max_scrolls"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_SCROLLS)
                    .coerceIn(1, MAX_SCROLLS_HARD_CAP)

            for (attempt in 0..maxScrolls) {
                val nodeId =
                    try {
                        selectorResolver.resolve(selector)
                    } catch (e: McpToolException.NodeNotFound) {
                        null
                    }
                if (nodeId != null) {
                    return scrollToNodeTool.execute(buildJsonObject { put("node_id", nodeId) })
                }
                if (attempt == maxScrolls) break
                scrollTool.execute(
                    buildJsonObject {
                        put("direction", direction)
                        put("amount", "medium")
                    },
                )
            }
            throw McpToolException.NodeNotFound("scroll_find: selector never resolved after $maxScrolls scroll(s)")
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

        private suspend fun launchApp(params: JsonObject): ToolResult {
            val pkg =
                params["package"]?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("launch_app requires 'package'")
            return openAppHandler.execute(buildJsonObject { put("package_id", pkg) })
        }

        /**
         * `absent: true` (design doc §7.2's full `wait_until` row) isn't supported by this build —
         * a forward-compatibility placeholder rejection, not a spec requirement (mvp-handover's
         * own simplified table only lists `selector`+`timeout_ms`). `timeout_ms` is forwarded
         * as-is to `WaitForNodeTool`, whose own cap was raised from 30s to 120s (see that file) so
         * a step near the server's own `timeout_ms + STEP_TIMEOUT_MARGIN_MS` transport timeout
         * doesn't fail client-side before the server even times out.
         *
         * `WaitForNodeTool` never throws on a timeout — it returns a normal, successful
         * [ToolResult] whose text is `{"found": false, ...}` (`UtilityTools.kt`), because that
         * shape is right for the MCP-facing tool (an LLM can read the JSON either way). Left as-is,
         * every `wait_until` step — found or not — would produce the identical empty `StepResult`
         * (§5.1/§5.2's "success ops -> `{}`" convention), so a server-driven flow would have no way
         * to see a timeout and branch or retry on it. This op is the one place that convention is
         * wrong: `found: false` is parsed back out of the tool's own JSON text and turned into a
         * `NodeNotFound` `StepError`, matching how every other selector-based op already reports
         * "the target never resolved."
         */
        @Suppress("ThrowsCount")
        private suspend fun waitUntil(params: JsonObject): ToolResult {
            if (params["absent"]?.jsonPrimitive?.booleanOrNull == true) {
                throw McpToolException.InvalidParams("wait_until: absent=true is not supported by this build")
            }
            val selector =
                params["selector"]?.jsonObject
                    ?: throw McpToolException.InvalidParams("wait_until requires 'selector'")
            val (by, value, _) = pickSelectorCandidate(selector)
            val timeoutMs =
                params["timeout_ms"]?.jsonPrimitive?.longOrNull
                    ?: throw McpToolException.InvalidParams("wait_until requires 'timeout_ms'")
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
                    "wait_until: selector {${by.name.lowercase()}: \"$value\"} never matched within ${timeoutMs}ms",
                )
            }
            return result
        }

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
            private const val DEFAULT_MAX_SCROLLS = 5
            private const val MAX_SCROLLS_HARD_CAP = 25
            private val EMPTY_OUTPUT = JsonObject(emptyMap())
        }
    }
