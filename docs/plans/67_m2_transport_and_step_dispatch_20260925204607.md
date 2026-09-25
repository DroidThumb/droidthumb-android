<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# M2 — the app dials out

Scope: `droidthumb-server/docs/droidthumb-design-doc.md` v0.9 §11.4 M2; `droidthumb-server/docs/mvp-handover.md`
§4 (what this repo must build); `droidthumb-server/docs/plans/01_...md` §4.3/§5.1. Server and protocol
schemas already exist and are not modified here except where mvp-handover §4 item 4 (Event Channel
payload reconciliation) requires a companion change already made in `droidthumb-server`/`droidthumb-protocol`
(separate PRs, merged).

**Out of scope**: flow signing (SEC-05), on-device flow cache/executor (`run_flow` stays server-driven
per mvp-handover §1), redaction (SEC-08/SEC-09), unattended mode.

## User story 1 — Wire protocol Kotlin classes

Why: mvp-handover §4 item 2 — hand-written classes matching `droidthumb-protocol`'s six schemas,
keyed by `type` as the polymorphic discriminator, so the transport client (US3) has something to
encode/decode against.

Acceptance criteria:
- [x] `Hello`/`Welcome`/`Step`/`StepResult`/`StepError` are `@Serializable` subtypes of a sealed
      `WireMessage` interface; `FileReference` is a separate sealed interface (`Inline`/`Url`).
- [x] Round-trip tests (encode → decode → equals) pass for one example of each type, matching the
      JSON Schema examples in `droidthumb-protocol/schema/*.schema.json`.
- [x] Decoding a payload with a missing or wrong `type` fails, not silently misclassified.

### Task 1.1 — `WireMessage` sealed hierarchy

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/wireprotocol/Messages.kt` (create)

```kotlin
package com.danielealbano.androidremotecontrolmcp.wireprotocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * The device <-> server wire protocol (droidthumb-protocol/schema/*.schema.json), hand-written
 * per plan 01 §4.3 — no Kotlin codegen path exists. `Result`/`Error` are named `StepResult`/
 * `StepError` here to avoid shadowing `kotlin.Result`/`kotlin.Error`; `@SerialName` keeps the
 * wire's `type` value ("result"/"error") unchanged.
 */
@Serializable
sealed interface WireMessage

@Serializable
@SerialName("hello")
data class Hello(
    val protocol_version: Int,
    val apk_version: String,
    val device_id: String,
    val capabilities: List<String> = emptyList(),
    val mode: String,
    val flow_manifest: List<FlowManifestEntry> = emptyList(),
) : WireMessage

@Serializable
data class FlowManifestEntry(
    val id: String,
    val version: Int,
)

@Serializable
@SerialName("welcome")
data class Welcome(
    val accepted: Boolean,
    val protocol_version: Int,
    val settings: WelcomeSettings? = null,
) : WireMessage

@Serializable
data class WelcomeSettings(
    val default_step_timeout_ms: Long? = null,
)

@Serializable
@SerialName("step")
data class Step(
    val step_id: String,
    val op: String,
    val params: JsonElement? = null,
) : WireMessage

@Serializable
@SerialName("result")
data class StepResult(
    val step_id: String,
    val output: JsonElement? = null,
) : WireMessage

@Serializable
@SerialName("error")
data class StepError(
    val step_id: String,
    val code: String,
    val message: String,
) : WireMessage

/** D-21. Only `Inline` is ever produced by this build; `Url` exists so decoding a future server
 *  response referencing it doesn't crash — nothing constructs it yet. */
@Serializable
sealed interface FileReference {
    @Serializable
    @SerialName("inline")
    data class Inline(
        val mime: String,
        val data: String,
    ) : FileReference

    @Serializable
    @SerialName("url")
    data class Url(
        val mime: String,
        val url: String,
        val expires_at: String? = null,
    ) : FileReference
}

/**
 * `kotlinx.serialization.json.Json`'s polymorphic discriminator defaults to the property named
 * `type`, matching the wire schemas exactly — no custom `classDiscriminator` needed, only
 * subtype registration. `ignoreUnknownKeys` for forward compatibility (design doc §9.8, N/N-1).
 */
val wireJson =
    Json {
        ignoreUnknownKeys = true
        serializersModule =
            SerializersModule {
                polymorphic(WireMessage::class) {
                    subclass(Hello::class)
                    subclass(Welcome::class)
                    subclass(Step::class)
                    subclass(StepResult::class)
                    subclass(StepError::class)
                }
                polymorphic(FileReference::class) {
                    subclass(FileReference.Inline::class)
                    subclass(FileReference.Url::class)
                }
            }
    }
```

Note: `FileReference`'s wire discriminator key is `kind`, not `type` (schema: `{"kind": "inline", ...}`).
Since this build never decodes a `FileReference` (only encodes `Inline` for `read_screen`'s
screenshot, embedded manually as a `JsonObject` in `StepResult.output`, not through polymorphic
`Json.encodeToJsonElement<FileReference>`), the mismatch between kotlinx's default `type` key and
the schema's `kind` key is harmless for this build — recorded here, not worked around, because
working around a case that never executes is speculative. If a future build needs to *decode* a
`FileReference` (M2 does not), give `FileReference` its own `Json` instance with
`classDiscriminator = "kind"` at that point.

### Task 1.2 — Round-trip tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/wireprotocol/MessagesTest.kt` (create)

| Test | Verifies |
|---|---|
| `hello round-trips` | Encode a `Hello` example (matching `hello.schema.json`'s required fields), decode via `wireJson.decodeFromString<WireMessage>`, assert equal and `is Hello` |
| `welcome round-trips` | Same, for `Welcome` with `settings.default_step_timeout_ms` set |
| `step round-trips, params preserved` | `Step` with a non-trivial `params` `JsonObject` (a selector) survives encode/decode unchanged |
| `result round-trips` | `StepResult` with a `JsonObject` output |
| `error round-trips` | `StepError` |
| `decoding a payload with no type field fails` | `wireJson.decodeFromString<WireMessage>("""{"step_id":"1"}""")` throws |
| `decoding a payload with an unknown type fails` | `type: "bogus"` throws |
| `file-reference inline encodes with kind: inline` | `Json.encodeToJsonElement(FileReference.Inline(...))` — direct (non-polymorphic) encode of the concrete type, used by the dispatcher (US2), produces `{mime, data}`; `kind` is added manually where the wire needs it (see US2 task 2.3) |

### Definition of Done

- [x] All tests in the table above pass.
- [x] `./gradlew ktlintCheck detekt` clean for the new file.

---

## User story 2 — Selector resolution and the 7-op dispatch table

Why: mvp-handover §4 item 3 — translate a wire `Step` (`op` + `params`, selector-shaped per plan 01
§5.1.1) into a call against the already-kept, already-injectable tool handlers
(`app/src/main/kotlin/.../mcp/tools/*`), and the handler's `ToolResult` back into a `StepResult` or
`StepError`. This is new glue code; no handler logic changes.

Acceptance criteria:
- [x] All 7 ops (`read_screen`, `tap`, `type_text`, `scroll_find`, `key`, `launch_app`,
      `wait_until`) dispatch to the correct handler(s) per mvp-handover §4 item 3's table.
- [x] A selector (`{resource_id?, content_desc?, text?, class_name?, index?}`) resolves to a
      `node_id` before being handed to a `node_id`-based handler (`click_node`, `type_append_text`,
      `type_clear_text`, `scroll_to_node`, `wait_for_node`'s underlying search) — none of those
      handlers accept a raw selector.
- [x] `read_screen`'s screenshot, when requested, is embedded in `StepResult.output` as an inline
      `file-reference` shape (`{kind: "inline", mime, data}`), not left as a bare base64 string.
- [x] A handler failure (`McpToolException`) becomes a `StepError` with a device-chosen `code`
      (the exception's simple class name) and `message` (the exception's message) — never an
      unhandled throw out of the dispatcher.

### Task 2.1 — Selector resolution

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/wireprotocol/SelectorResolver.kt` (create)

Priority order `resource_id` → `content_desc` → `text` → `class_name` (plan 01 §5.1.1, §7.3's order):
first present field wins, `index` (default 0) picks among multiple matches. `exactMatch = true` for
`resource_id`/`class_name` (identifiers), `false` (case-insensitive contains) for `content_desc`/
`text` (human-readable labels — an LLM-supplied partial label should still resolve). This
exact/substring split is this plan's own judgment call, undocumented in mvp-handover's selector
shape — recorded in `docs/decisions-log.md`-equivalent for this repo (there isn't one; recorded
here and in the PR description instead, since `droidthumb-android`'s CLAUDE.md has no decisions-log
convention of its own).

```kotlin
package com.danielealbano.androidremotecontrolmcp.wireprotocol

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.mcp.tools.getFreshWindows
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class SelectorResolver
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
    ) {
        /** Resolves a wire selector object to a node_id against the CURRENT tree. Throws
         *  McpToolException.NodeNotFound if nothing matches; McpToolException.InvalidParams if
         *  the selector has none of the four field candidates. */
        suspend fun resolve(selector: JsonObject): String {
            val (by, value, exact) = pickCandidate(selector)
            val windows = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache).windows
            val matches = elementFinder.findElements(windows, by, value, exact)
            val index = selector["index"]?.jsonPrimitive?.intOrNull ?: 0
            return matches.getOrNull(index)?.id
                ?: throw McpToolException.NodeNotFound(
                    "No node matched selector {${by.name.lowercase()}: \"$value\"}" +
                        if (index != 0) " at index $index (${matches.size} match(es) found)" else "",
                )
        }

        private fun pickCandidate(selector: JsonObject): Triple<FindBy, String, Boolean> {
            selector["resource_id"]?.jsonPrimitive?.contentOrNull?.let { return Triple(FindBy.RESOURCE_ID, it, true) }
            selector["content_desc"]?.jsonPrimitive?.contentOrNull?.let { return Triple(FindBy.CONTENT_DESC, it, false) }
            selector["text"]?.jsonPrimitive?.contentOrNull?.let { return Triple(FindBy.TEXT, it, false) }
            selector["class_name"]?.jsonPrimitive?.contentOrNull?.let { return Triple(FindBy.CLASS_NAME, it, true) }
            throw McpToolException.InvalidParams(
                "selector must have at least one of resource_id, content_desc, text, class_name",
            )
        }
    }
```

(`getFreshWindows` is `internal`, module-visible from `mcp.tools` — importable here without
changing its visibility.)

### Task 2.2 — `scroll_find`'s blind-scroll fallback

`scroll_to_node` (the kept handler) requires the target node already resolvable in the current tree
(just off-screen) — it does not handle a virtualized list that hasn't rendered the target at all.
`SelectorResolver.resolve` alone can't distinguish "not visible, but in the tree" from "not in the
tree yet". This task adds the missing "not in the tree yet" loop: blind-scroll, re-resolve, repeat.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/wireprotocol/StepDispatcher.kt`
(created in Task 2.3) — `scrollFind` private method:

```kotlin
private suspend fun scrollFind(params: JsonObject): ToolResult {
    val selector = params["selector"]?.jsonObject
        ?: throw McpToolException.InvalidParams("scroll_find requires 'selector'")
    val direction = params["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
    val maxScrolls = params["max_scrolls"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_SCROLLS

    repeat(maxScrolls + 1) { attempt ->
        val nodeId = runCatching { selectorResolver.resolve(selector) }.getOrNull()
        if (nodeId != null) {
            return scrollToNodeTool.execute(buildJsonObject { put("node_id", nodeId) })
        }
        if (attempt == maxScrolls) return@repeat
        scrollTool.execute(
            buildJsonObject {
                put("direction", direction)
                put("amount", "medium")
            },
        )
    }
    throw McpToolException.NodeNotFound("scroll_find: selector never resolved after $maxScrolls scroll(s)")
}
```

`ScrollTool` (blind, gesture-based, no node id — `mcp/tools/TouchActionTools.kt`) is reused for the
"not rendered yet" case; `ScrollToNodeTool` (already-kept, direction-auto-trying) handles "rendered
but off-screen" once the selector resolves. `DEFAULT_MAX_SCROLLS = 5`.

### Task 2.3 — `StepDispatcher`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/wireprotocol/StepDispatcher.kt` (create)

One `@Inject constructor` pulling in `GetScreenStateHandler`, `TapTool`, `ClickNodeTool`,
`TypeAppendTextTool`, `TypeClearTextTool`, `ScrollToNodeTool`, `ScrollTool`, `PressBackHandler`,
`PressHomeHandler`, `PressRecentsHandler`, `DismissKeyboardHandler`, `OpenAppHandler`,
`WaitForNodeTool`, `SelectorResolver` (all already `@Inject`-constructible — Hilt wires this class
for free, no new `di/` module needed).

```kotlin
package com.danielealbano.androidremotecontrolmcp.wireprotocol

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.tools.*
import kotlinx.serialization.json.*
import javax.inject.Inject

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
        /** Never throws — every failure becomes a Step.error-shaped `WireMessage`. */
        suspend fun dispatch(step: Step): WireMessage {
            val params = step.params as? JsonObject ?: JsonObject(emptyMap())
            return try {
                val output = dispatchOp(step.op, params)
                StepResult(step_id = step.step_id, output = output)
            } catch (e: McpToolException) {
                StepError(step.step_id, code = e::class.simpleName ?: "InternalError", message = e.message ?: "")
            } catch (e: Exception) {
                StepError(step.step_id, code = "InternalError", message = e.message ?: "unknown error")
            }
        }

        private suspend fun dispatchOp(op: String, params: JsonObject): JsonElement =
            when (op) {
                "read_screen" -> readScreen(params)
                "tap" -> tap(params).let { JsonObject(emptyMap()) }
                "type_text" -> typeText(params).let { JsonObject(emptyMap()) }
                "scroll_find" -> scrollFind(params).let { JsonObject(emptyMap()) }
                "key" -> key(params).let { JsonObject(emptyMap()) }
                "launch_app" -> launchApp(params).let { JsonObject(emptyMap()) }
                "wait_until" -> waitUntil(params)
                else -> throw McpToolException.InvalidParams("Unknown op: '$op'")
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
                selector != null -> clickNodeTool.execute(buildJsonObject { put("node_id", selectorResolver.resolve(selector)) })
                at != null -> tapTool.execute(at)
                else -> throw McpToolException.InvalidParams("tap requires 'selector' or 'at'")
            }
        }

        private suspend fun typeText(params: JsonObject): ToolResult {
            val selector = params["selector"]?.jsonObject
                ?: throw McpToolException.InvalidParams("type_text requires 'selector'")
            val nodeId = selectorResolver.resolve(selector)
            val clear = params["clear"]?.jsonPrimitive?.booleanOrNull ?: false
            return if (clear) {
                typeClearTextTool.execute(buildJsonObject { put("node_id", nodeId) })
            } else {
                val text = params["text"]?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("type_text requires 'text' unless clear:true")
                typeAppendTextTool.execute(buildJsonObject { put("node_id", nodeId); put("text", text) })
            }
        }

        private suspend fun key(params: JsonObject): ToolResult {
            val key = params["key"]?.jsonPrimitive?.contentOrNull
                ?: throw McpToolException.InvalidParams("key requires 'key'")
            return when (key) {
                "back" -> pressBackHandler.execute(null)
                "home" -> pressHomeHandler.execute(null)
                "recents" -> pressRecentsHandler.execute(null)
                "dismiss_keyboard" -> dismissKeyboardHandler.execute(null)
                else -> throw McpToolException.InvalidParams(
                    "key must be one of: back, home, recents, dismiss_keyboard. Got: '$key'",
                )
            }
        }

        private suspend fun launchApp(params: JsonObject): ToolResult {
            val pkg = params["package"]?.jsonPrimitive?.contentOrNull
                ?: throw McpToolException.InvalidParams("launch_app requires 'package'")
            return openAppHandler.execute(buildJsonObject { put("package_id", pkg) })
        }

        private suspend fun waitUntil(params: JsonObject): JsonElement {
            if (params["absent"]?.jsonPrimitive?.booleanOrNull == true) {
                throw McpToolException.InvalidParams("wait_until: absent=true is not supported by this build")
            }
            val selector = params["selector"]?.jsonObject
                ?: throw McpToolException.InvalidParams("wait_until requires 'selector'")
            val (by, value) = selectorToByValue(selector)
            val timeoutMs = params["timeout_ms"]?.jsonPrimitive?.longOrNull
                ?: throw McpToolException.InvalidParams("wait_until requires 'timeout_ms'")
            waitForNodeTool.execute(
                buildJsonObject {
                    put("by", byWireName(by))
                    put("value", value)
                    put("timeout", timeoutMs)
                },
            )
            return JsonObject(emptyMap())
        }

        // scrollFind: Task 2.2, appended here in the real file, not repeated.

        private fun selectorToByValue(selector: JsonObject): Pair<FindBy, String> { /* same priority as SelectorResolver.pickCandidate, minus exactMatch — WaitForNodeTool takes exact_match implicitly false */ TODO() }
        private fun byWireName(by: FindBy): String = when (by) {
            FindBy.RESOURCE_ID -> "resource_id"
            FindBy.CONTENT_DESC -> "content_desc"
            FindBy.TEXT -> "text"
            FindBy.CLASS_NAME -> "class_name"
        }

        companion object {
            private const val DEFAULT_MAX_SCROLLS = 5
        }
    }
```

`selectorToByValue` is written out in full in the real file (not `TODO()` — shown elided here only
because it duplicates `SelectorResolver.pickCandidate`'s field-priority logic; the real
implementation factors the shared priority-picking into one `internal` function both call, rather
than copy-pasting it, to avoid the two drifting apart).

### Task 2.4 — `StepDispatcherTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/wireprotocol/StepDispatcherTest.kt` (create)

**Setup**: MockK doubles for each injected handler + `SelectorResolver`, matching
`HandlerTestHarness`'s existing mocking conventions.

| Test | Verifies |
|---|---|
| `read_screen without include_screenshot returns tree only` | `output.screenshot` absent |
| `read_screen with include_screenshot returns tree and inline screenshot` | `output.screenshot == {kind:"inline", mime, data}` |
| `tap with selector resolves node_id and calls click_node` | `ClickNodeTool.execute` called with resolved id |
| `tap with at calls TapTool directly, no selector resolution` | `SelectorResolver.resolve` never called |
| `tap with neither selector nor at throws InvalidParams` | Dispatch produces a `StepError` with that code |
| `type_text without clear calls type_append_text with resolved node_id and text` | |
| `type_text with clear:true calls type_clear_text, text not required` | |
| `scroll_find resolves immediately when selector already matches` | `ScrollToNodeTool` called, `ScrollTool` (blind) never called |
| `scroll_find blind-scrolls when selector doesn't resolve, then finds it` | `ScrollTool` called N times before `ScrollToNodeTool` succeeds |
| `scroll_find exhausts max_scrolls and errors` | `StepError` with `NodeNotFound` |
| `key back/home/recents/dismiss_keyboard dispatch to the right handler` | Parameterized over the 4 values |
| `key with an unsupported value throws InvalidParams` | |
| `launch_app maps package -> package_id` | `OpenAppHandler.execute` called with `{package_id: ...}` |
| `wait_until requires timeout_ms` | Missing -> `StepError` |
| `wait_until with absent:true is rejected` | `StepError`, code `InvalidParams` |
| `a handler's McpToolException becomes a StepError with the exception's class name as code` | |
| `an unexpected exception becomes a StepError, not a crash` | |

### Definition of Done

- [x] All tests in both tables pass.
- [x] `./gradlew ktlintCheck detekt` clean for the new files.

---

## User story 3 — WebSocket transport client and foreground service

Why: mvp-handover §4 item 1 — the actual outbound connection. Design doc M2 requirement: subprotocol
`droidthumb.v1`, `hello`/await-`welcome`-or-close, reconnect with backoff, foreground service so it
survives backgrounding.

Acceptance criteria:
- [x] Connects with the `droidthumb.v1` WebSocket subprotocol; a rejected upgrade or a 4000/4001
      close is logged distinctly from a network drop.
- [x] Sends `hello` first; on `welcome`, begins dispatching `step` messages via `StepDispatcher` and
      replying with the result; on close, reconnects with exponential backoff (capped).
- [x] Runs inside a foreground service (`services/transport/TransportService`), started/stopped by a
      setting, matching `EventChannelService`'s pattern (`START_STICKY`, persistent notification).
- [x] Server address is a setting (`TransportSettings`), not hard-coded — a host:port the operator
      points at the podman gateway for redroid, or a real relay address later.

### Task 3.1 — Add the Ktor WebSocket client dependency

**File**: `gradle/libs.versions.toml` (modify) — add under the existing `ktor` group:

```toml
ktor-client-websockets = { group = "io.ktor", name = "ktor-client-websockets", version.ref = "ktor" }
# test-only: a fake server to test the handshake/reconnect logic against, matching
# EventDispatcherImplTest's existing host-side-fake-server pattern
ktor-server-websockets = { group = "io.ktor", name = "ktor-server-websockets", version.ref = "ktor" }
```

**File**: `app/build.gradle.kts` (modify) — add `implementation(libs.ktor.client.websockets)` beside
the existing Ktor client lines, and `testImplementation(libs.ktor.server.websockets)` beside
`ktor.server.netty`.

### Task 3.2 — `DeviceTransportClient`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/transport/DeviceTransportClient.kt` (create)

Interface + Ktor-`OkHttp`-engine implementation. Connection lifecycle as a `StateFlow<TransportStatus>`
(`Idle`/`Connecting`/`Connected`/`Rejected(reason)`/`Reconnecting(attempt)`), mirroring
`ChannelConnectionStatus`'s existing shape in this codebase. Reconnect backoff: `1s, 2s, 4s, 8s,
16s, 30s` (capped), reset to `1s` on a successful `welcome`. On each inbound `step` frame: decode via
`wireJson`, dispatch via `StepDispatcher`, encode the resulting `StepResult`/`StepError`, send.

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.transport

import com.danielealbano.androidremotecontrolmcp.wireprotocol.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TransportStatus {
    data object Idle : TransportStatus
    data object Connecting : TransportStatus
    data class Connected(val protocolVersion: Int) : TransportStatus
    data class Rejected(val closeCode: Short?, val reason: String) : TransportStatus
    data class Reconnecting(val attempt: Int, val delayMs: Long) : TransportStatus
}

interface DeviceTransportClient {
    val status: StateFlow<TransportStatus>
    fun start(host: String, port: Int, deviceId: String)
    fun stop()
}

@Singleton
class DeviceTransportClientImpl
    @Inject
    constructor(
        private val stepDispatcher: StepDispatcher,
    ) : DeviceTransportClient {
        private val _status = MutableStateFlow<TransportStatus>(TransportStatus.Idle)
        override val status: StateFlow<TransportStatus> = _status.asStateFlow()

        private var job: Job? = null
        private val client = HttpClient(OkHttp) { install(WebSockets) }

        override fun start(host: String, port: Int, deviceId: String) {
            stop()
            job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                var attempt = 0
                while (isActive) {
                    _status.value = if (attempt == 0) TransportStatus.Connecting
                        else TransportStatus.Reconnecting(attempt, backoffMs(attempt))
                    if (attempt > 0) delay(backoffMs(attempt))
                    val cleanExit = runSession(host, port, deviceId)
                    attempt = if (cleanExit) 0 else attempt + 1
                }
            }
        }

        /** Returns true if the session completed a handshake at all (so backoff resets even if
         *  it later dropped) — a rejection (4000/4001) does NOT count as clean; retrying against
         *  an unsupported-version server is expected to keep failing, so it still backs off. */
        private suspend fun runSession(host: String, port: Int, deviceId: String): Boolean {
            var welcomed = false
            try {
                client.webSocket(
                    method = io.ktor.http.HttpMethod.Get,
                    host = host,
                    port = port,
                    path = "/device",
                    request = { header(io.ktor.http.HttpHeaders.SecWebSocketProtocol, "droidthumb.v1") },
                ) {
                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), helloFor(deviceId))))
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val message = wireJson.decodeFromString(WireMessage.serializer(), frame.readText())
                        when (message) {
                            is Welcome -> {
                                welcomed = true
                                _status.value = TransportStatus.Connected(message.protocol_version)
                            }
                            is Step -> {
                                val reply = stepDispatcher.dispatch(message)
                                send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), reply)))
                            }
                            else -> Unit // Hello/StepResult/StepError never arrive server->device
                        }
                    }
                }
            } catch (e: Exception) {
                if (!welcomed) _status.value = TransportStatus.Rejected(null, e.message ?: "connection failed")
            }
            return welcomed
        }

        private fun helloFor(deviceId: String) = Hello(
            protocol_version = 1,
            apk_version = /* BuildConfig.VERSION_NAME */ "unknown",
            device_id = deviceId,
            capabilities = emptyList(),
            mode = "live",
            flow_manifest = emptyList(),
        )

        override fun stop() {
            job?.cancel()
            job = null
            _status.value = TransportStatus.Idle
        }

        private fun backoffMs(attempt: Int): Long = BACKOFF_SCHEDULE_MS.getOrElse(attempt - 1) { BACKOFF_SCHEDULE_MS.last() }

        companion object {
            private val BACKOFF_SCHEDULE_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        }
    }
```

`apk_version` reads `BuildConfig.VERSION_NAME` in the real file (the app already derives this from
git — `build-notes.md`); elided above as a comment only to keep this listing buildable as pseudocode
without pulling in Gradle's generated `BuildConfig` here.

The 4000/4001 WS close codes arrive to a Ktor client as a normal channel close, not a distinguishable
exception by default — the real implementation reads `closeReason.await()` in a `finally` block to
recover the code/reason for `TransportStatus.Rejected`, shown elided above (`Rejected(null, ...)`)
for brevity; the actual file populates `closeCode`.

### Task 3.3 — `TransportService` (foreground service)

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/transport/TransportService.kt` (create)

Same shape as `EventChannelService.kt`: `@AndroidEntryPoint`, `ACTION_START`/`ACTION_STOP`,
`startForeground` with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, reads `TransportSettings` (US4),
starts/stops `DeviceTransportClient`, exposes `serviceStatus` as a companion `StateFlow` for the UI.

### Task 3.4 — Tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/transport/DeviceTransportClientTest.kt` (create)

**Setup**: a Ktor `embeddedServer(Netty)` with the `WebSockets` plugin installed, standing in for
`droidthumb-server`'s handshake (hand-rolled `hello`→`welcome`/close per test, not a copy of the real
server) — same host-side-fake-server pattern `EventDispatcherImplTest` already uses for HTTP.

| Test | Verifies |
|---|---|
| `connects, sends hello, offers droidthumb.v1 subprotocol` | Fake server asserts the subprotocol header and receives a schema-shaped `hello` |
| `receives welcome, status becomes Connected` | |
| `server closes with 4001, status becomes Rejected, no immediate retry storm` | One reconnect attempt scheduled, not a tight loop |
| `receives a step, dispatches it, sends back the StepDispatcher's reply` | Fake `StepDispatcher` (MockK) returns a canned `StepResult`; assert the exact frame sent |
| `connection drops after welcome, client reconnects` | Second `runSession` observed after a drop |
| `stop() cancels the session and sets status to Idle` | |

### Definition of Done

- [x] All tests pass.
- [x] `./gradlew ktlintCheck detekt` clean.

---

## User story 4 — Transport settings and minimal UI

Why: mvp-handover — "the server address is a setting in the app, not hard-coded." Matches the
existing `EventChannelSettings` DataStore pattern exactly (`SettingsRepository` already documents
itself as "the only settings left after the demolition pass are the Event Channel's" — this adds the
second slice).

Acceptance criteria:
- [x] `TransportConfig` (host, port, deviceId, enabled) persists via DataStore, same pattern as
      `EventChannelConfig`.
- [x] `ServerScreen` gets a minimal card: host:port text fields, start/stop toggle, status text —
      no new screen, reusing the existing screen's layout.

### Task 4.1 — `TransportConfig` and `TransportSettings`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/TransportConfig.kt` (create)

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

data class TransportConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 4001,
    val deviceId: String = "",
)
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/TransportSettings.kt` (create)
— interface: `val transportConfig: Flow<TransportConfig>`, `suspend fun getTransportConfig():
TransportConfig`, `suspend fun setTransportConfig(config: TransportConfig)`. `deviceId` defaults to
a generated UUID persisted on first read (device identity must survive reinstall-free restarts, not
regenerate every launch).

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/TransportSettingsImpl.kt` (create)
— DataStore-backed, same structure as `EventChannelSettingsImpl`.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt` (modify)
— extend `EventChannelSettings, TransportSettings`.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt` (modify)
— add `@Binds abstract fun bindTransportSettings(impl: TransportSettingsImpl): TransportSettings`
to `RepositoryModule`.

### Task 4.2 — UI

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/ServerScreen.kt` (modify)
— add a `TransportStatusCard` (new small composable in `ui/components/`, modeled on
`EventChannelStatusCard`) above or below the existing Event Channel card: host/port text fields
(editable only while stopped), start/stop button, status text driven from
`TransportService.serviceStatus`.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt`
or a new `TransportViewModel.kt` (create if a dedicated one is cleaner, following `ChannelViewModel`'s
existing precedent) — exposes `transportConfig`, `transportStatus`, `startTransport()`,
`stopTransport()`.

### Definition of Done

- [x] Compiles; `ktlintCheck`/`detekt` clean.
- [x] Manual check (part of M3 below, not repeated here): entering the redroid gateway host:port
      and pressing start actually connects. **Done, against a real redroid device and a real
      server — see "Real-device verification findings" below for two more real bugs this surfaced
      that no unit test, review, or mock could have caught.**

### Real-device verification findings (against real redroid + a real server, not mocks)

Both plan reviews above were thorough and correct about what they could see; neither could have
caught these two, since both require an actual device, an actual server, and actual wall-clock
timing — exactly the gap unit tests and code review can't close, which is why this DoD item
existed. Recorded here rather than silently fixed, per this pass's "decide, don't ask, and record
it" mandate.

1. **`wireJson` didn't set `encodeDefaults = true`.** kotlinx.serialization omits a property equal
   to its default value unless told otherwise — `Hello.capabilities`/`flowManifest` default to
   `emptyList()`, the common case, so every real `hello` this build sent omitted both fields
   entirely. `hello.schema.json` requires both. The real server closed every connection attempt
   with `4000 "expected a valid hello first"` before `welcome` could ever arrive — silently, from
   this build's own tests' perspective, since `MessagesTest`'s round-trip tests encode-then-decode
   the same Kotlin class and can't detect a field neither side ever populated. Only visible by
   logging the actual bytes sent to a real server and watching it reject them. Fixed:
   `encodeDefaults = true` on `wireJson`. Regression test added
   (`hello encodes required fields even at their default value`, asserting on the raw JSON string).
2. **A real race between the settings write and the service-start intent** in
   `TransportViewModel.start()`: `updateTransportEnabled(true)` was launched (fire-and-forget)
   alongside `startService()` rather than before it. `TransportService.handleStart()` collects
   `transportConfig` and stops itself the instant it observes `enabled == false` — and a DataStore
   `Flow` re-emits the *current* value immediately on collection. If the service's first collection
   read landed before the enabled-write completed, it saw the pre-write `false` and self-stopped
   within tens of milliseconds of starting — reproduced directly, not theoretical (`Transport
   started` immediately followed by `Transport service destroyed` in logcat, no exception, no
   explicit stop tap). Fixed: `start()`/`stop()` now await the settings write before sending the
   intent. **Note:** the identical pattern exists in the pre-existing, already-shipped
   `ChannelViewModel.startChannel()`/`EventChannelService.handleStart()` — out of scope for this
   plan (a file this plan doesn't otherwise touch), flagged here for a future pass rather than
   fixed silently alongside an unrelated feature.

**Verified working end-to-end** (2026-09-25, this host): built and installed the debug APK on the
persistent redroid device, ran `droidthumb-server` with `DEVICE_BIND_HOST` set to the current
podman gateway (`docs/debug-device.md`/plan 01 §0), entered that address in the app's Remote
Control card, pressed Start — status reached `Connected (protocol v1)`. Confirmed from the server
side with a real MCP client: `list_tools` returned all 7 device ops plus the M4/M5 tools, and
`read_screen` executed against the live device and returned a real accessibility tree. This is
also the first real evidence for M3 that the whole chain (app -> transport -> server -> MCP) works,
ahead of M3's own dedicated proof pass.

---

## User story 5 — Event Channel repoint

Why: mvp-handover §4 item 4. The payload shape needs no change (already reconciled server-side —
`droidthumb-server`/`droidthumb-protocol` PRs, merged before this task). Only the path was wrong.

Acceptance criteria:
- [x] `EventDispatcherImpl` POSTs to `$endpointUrl/events` (was `/event`).

### Task 5.1

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventDispatcherImpl.kt` (modify)

```kotlin
// before: httpClient.post("$endpointUrl/event") { ... }
httpClient.post("$endpointUrl/events") { ... }
```

**File**: `app/src/test/kotlin/.../EventDispatcherImplTest.kt` (modify) — update the fake server's
registered route from `/event` to `/events` (find via `grep -rn '"/event"' app/src/test`).

### Definition of Done

- [x] Existing `EventDispatcherImplTest` suite still passes after the path change.

---

## Final Definition of Done (whole plan)

- [x] All five user stories' DoDs checked.
- [x] `./gradlew build` succeeds (lint + unit tests + assembleDebug).
- [x] A single `plan-reviewer` subagent has reviewed this plan; findings addressed below this line
      before implementation starts.
- [x] A single `code-reviewer` subagent (plan-compliance mode) has reviewed the finished
      implementation against this plan; findings addressed.

## Code-reviewer findings and resolutions (plan-compliance pass, post-implementation)

No plan-compliance gaps found — all 12 plan-reviewer resolutions above were verified correctly
reflected in the code. Two CRITICAL functional bugs were found that neither the plan nor the first
review anticipated, plus three WARNINGs; all fixed except one accepted pre-existing pattern.

1. **CRITICAL — `CancellationException` was swallowed by generic `catch (e: Exception)` blocks in
   two places**, breaking structured concurrency:
   - `DeviceTransportClientImpl.runSession`: `stop()` called mid-handshake (before `welcome`)
     resumes the suspended `incoming.receive()`/`send()` with `CancellationException`; the old
     catch treated it as a transport failure and wrote `Rejected` asynchronously, racing `stop()`'s
     own synchronous `Idle` write — the UI could get stuck showing "Rejected" after Stop was
     pressed. Fixed: `catch (e: CancellationException) { throw e }` before the generic catch.
     Regression test added (`stop while still mid-handshake before welcome leaves status Idle`).
   - `StepDispatcher.dispatch`: cancelling the dispatching coroutine mid-step (e.g. during a long
     `wait_until`, now up to 120s) was turned into a normal `StepError("InternalError")` and the
     coroutine kept running to `send()` that fabricated reply instead of unwinding — the canonical
     "never swallow cancellation" bug. Same fix, rethrow before the generic catch. Regression test
     added (`dispatch rethrows CancellationException instead of turning it into a StepError`).
2. **CRITICAL — `wait_until` could never report "not found," only success.** `WaitForNodeTool`
   doesn't throw on timeout — it returns a normal successful `ToolResult` whose JSON text is
   `{"found": false, ...}` (that shape is right for the MCP-facing tool; an LLM can read it either
   way). Left as-is, `StepDispatcher`'s "every non-`read_screen` op reports `{}` on success"
   convention meant a `wait_until` that timed out looked byte-for-byte identical to one that
   succeeded immediately — a server-driven flow would have no way to branch or retry on it. Fixed:
   `waitUntil` now parses `found` back out of the tool's own JSON text and throws `NodeNotFound`
   when it's `false`, matching how every other selector-based op already reports "target never
   resolved." Two regression tests added (`found false` -> `StepError`; `found true` -> still a
   `StepResult`, so the fix doesn't paper over the success path).
3. **WARNING — no test coverage for `SelectorResolver`/`pickSelectorCandidate`'s field-priority
   order, exact-vs-substring split, and `index` disambiguation** — this build's own judgment call
   (plan 01 §5.1.1 doesn't specify match semantics), previously only documented in a comment.
   `SelectorResolverTest.kt` added: 10 tests against a real `ElementFinder` and a real multi-node
   tree (not mocked), covering every field, both priority-collision cases, index selection, and
   both failure modes (`NodeNotFound`, `InvalidParams`).
4. **WARNING — no range validation on the transport port setting**, unlike
   `EventChannelSettings.validateEndpointUrl`'s existing pattern. Fixed:
   `TransportViewModel.updatePort` now rejects non-numeric input and values outside 1..65535 with a
   `portError` state surfaced in `TransportStatusCard` (an error-styled port field plus message),
   instead of silently persisting an unusable value.
5. **WARNING — `TransportService.handleStart()` had no re-entrancy guard** against a second
   `ACTION_START` (e.g. a redelivered `START_STICKY` intent) launching a second, permanently-running
   pair of collectors alongside the first. Fixed with a `@Volatile started` flag, reset on stop/
   destroy. (`EventChannelService` has the identical pre-existing gap — out of scope here, a
   separate file this plan doesn't touch.)
6. INFO items (6/7/9/10 in the original report) required no action: the double-tree-walk cost is
   the plan's own accepted MVP cost (finding #7, above); the `FileReference` polymorphic-registration
   gap is inert dead code per `Messages.kt`'s own doc comment; `ws://` vs `wss://` is a real M2+
   concern but out of scope while every target is the redroid gateway on localhost; the
   `@Suppress` annotations collectively (item 8) are each individually justified and match
   precedent already in this codebase (`EventDispatcherImpl.kt`, `WaitForIdleTool`) — treated as a
   `Suppress`-audit judgment call for this build, recorded here rather than asked per-occurrence,
   consistent with items 12 above and the overall "decide, don't ask" mandate for this pass.

All 851 tests pass (837 + 14 new), `ktlintCheck`/`detekt` clean, `assembleDebug` succeeds.

## Plan-reviewer findings and resolutions

Reviewed before implementation began. All CRITICAL and WARNING findings addressed; INFO findings
noted, no code change needed.

1. **CRITICAL — US3 Task 3.2's close-code handling as originally sketched never fires**, because
   Ktor's client `webSocket { for (frame in incoming) {...} }` doesn't throw on a graceful WS close
   — the `for` loop just ends. Fixed: `closeReason.await()` is read unconditionally *after* the
   `for` loop, inside the `webSocket{}` block (the session receiver's own property), not inside the
   outer `catch`. Verified by a real test (`DeviceTransportClientTest`, "server closes with 4001
   before welcome") against a real Ktor WS server, not just a mock — this was the one finding worth
   an end-to-end test given how easy it is to get this control flow subtly wrong.
2. **CRITICAL — Task 3.3 was missing the `AndroidManifest.xml` service declaration**, which would
   have crashed at runtime on API 34 (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE` requires both
   `android:foregroundServiceType="specialUse"` and a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property,
   same as `EventChannelService`'s existing entry). Added.
3. **CRITICAL — `wait_until`'s `timeout_ms` could legitimately exceed `WaitForNodeTool`'s hardcoded
   30s cap**, contradicting mvp-handover §4 item 3's own stated design (server timeout =
   `timeout_ms + STEP_TIMEOUT_MARGIN_MS`). Fixed by raising `WaitForNodeTool.MAX_TIMEOUT_MS` to
   120s (a real, minimal handler change, not a dispatcher workaround) — see that file's own comment
   for the reasoning. Existing test updated to assert against the constant rather than a hardcoded
   value that the fix would otherwise have silently made meaningless.
4. **WARNING — US3 Task 3.3 had a forward dependency on US4's `TransportSettings`.** Implemented in
   the order 1 → 2 → 4 → 3 → 5 (settings before the service that reads them), not the plan's
   document order — the document itself is left as originally reviewed per the "don't modify a plan
   except checkmarks/findings" rule; this note is the record of the actual build order.
5. **WARNING — `scroll_find`'s blind-scroll retry swallowed every exception, not just
   "not found yet."** Narrowed the catch to `McpToolException.NodeNotFound` specifically; a
   `PermissionDenied` (accessibility service unavailable) now surfaces immediately instead of
   burning through `max_scrolls` blind attempts first. Test added
   (`scroll_find propagates a non-NodeNotFound failure immediately`).
6. **WARNING — `max_scrolls` had no upper bound.** Clamped to `MAX_SCROLLS_HARD_CAP = 25`
   regardless of what the wire step requests.
7. **WARNING — selector resolution re-walks the accessibility tree that the target handler then
   re-walks again internally** (`getFreshWindows` is expensive and lock-serialized). Accepted as a
   known MVP cost, not fixed: every kept handler already re-fetches internally by design (staleness
   correctness, per `NodeActionTools.kt`'s own docs), so avoiding the double walk would mean
   changing the kept handlers themselves, which is out of this plan's scope (US2's whole premise is
   "new glue code only — no handler logic changes").
8. **INFO — `key`/`launch_app` param mapping confirmed correct against real handler code**, no
   change needed.
9. **INFO — `wait_until`'s `absent: true` rejection is this plan's own forward-compatibility
   placeholder, not a spec requirement.** Noted in the code's own doc comment (`StepDispatcher.
   waitUntil`).
10. No security issues found in the op-dispatch or selector paths; none introduced.
11. Task 2.2's content (documented before Task 2.3, which actually creates the file) was folded
    directly into Task 2.3's `StepDispatcher` listing during implementation rather than kept as a
    separate forward-referencing task.
12. **Deviation from Task 1.1's code sample, decided during implementation, not a review finding:**
    the sample used snake_case Kotlin property names (`protocol_version` etc.) matching the wire
    JSON literally. Detekt's `ConstructorParameterNaming` rule rejects that outright, and per this
    repo's CLAUDE.md, suppressing a lint rule needs the conflict to genuinely be unavoidable — it
    wasn't: `kotlinx.serialization`'s `@SerialName` per field lets every property be idiomatic
    camelCase while still mapping to the exact wire key, with no suppression needed and, if
    anything, a clearer audit trail (a stale `@SerialName` fails every round-trip test immediately;
    a silently-wrong snake_case property would not have been as visible). Applied throughout
    `Messages.kt` and every call site.
