package com.danielealbano.androidremotecontrolmcp.wireprotocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * The device <-> server wire protocol (`droidthumb-protocol`'s `schema/` JSON Schema files), hand-written
 * per `droidthumb-server`'s plan 01 §4.3 — no Kotlin codegen path exists yet, so these are kept in
 * sync with the JSON Schemas by hand. Kotlin property names are idiomatic camelCase; `@SerialName`
 * on each field maps to the wire's actual snake_case JSON key, so the two never drift silently —
 * a renamed Kotlin property with a stale `@SerialName` fails every round-trip test in this package
 * immediately, whereas a silently-wrong snake_case property name would not have been as visible.
 *
 * `Result`/`Error` are named [StepResult]/[StepError] here (not `Result`/`Error`) to avoid
 * shadowing `kotlin.Result`/`kotlin.Error`, both used elsewhere in this codebase; `@SerialName`
 * keeps the wire's `type` value (`"result"`/`"error"`) unchanged regardless of the Kotlin name.
 */
@Serializable
sealed interface WireMessage

/** device -> server, sent once per connection to open the handshake. */
@Serializable
@SerialName("hello")
data class Hello(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("apk_version") val apkVersion: String,
    @SerialName("device_id") val deviceId: String,
    val capabilities: List<String> = emptyList(),
    val mode: String,
    @SerialName("flow_manifest") val flowManifest: List<FlowManifestEntry> = emptyList(),
) : WireMessage

@Serializable
data class FlowManifestEntry(
    val id: String,
    val version: Int,
)

/** server -> device, sent right after a schema-valid hello for a device_id with a registered public
 *  key — signed by the device's Keystore key and answered with [ChallengeResponse] before [Welcome]
 *  can arrive (D-27; not sent at all for an unknown device_id, which closes the connection instead —
 *  droidthumb-server's ws-server.ts `CLOSE_UNKNOWN_DEVICE`). */
@Serializable
@SerialName("challenge")
data class Challenge(
    val nonce: String,
) : WireMessage

/** device -> server, reply to [Challenge]. `signature` is the base64 ASN.1 DER ECDSA signature
 *  (SHA256withECDSA) over the raw bytes of `nonce` after base64-decoding it — not over the nonce's
 *  base64 text (challenge-response.schema.json). */
@Serializable
@SerialName("challenge_response")
data class ChallengeResponse(
    val signature: String,
) : WireMessage

/** server -> device, replies to `hello` once the connection is accepted. */
@Serializable
@SerialName("welcome")
data class Welcome(
    val accepted: Boolean,
    @SerialName("protocol_version") val protocolVersion: Int,
    val settings: WelcomeSettings? = null,
    @SerialName("latest_app_version") val latestAppVersion: String? = null,
    @SerialName("minimum_supported_app_version") val minimumSupportedAppVersion: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
) : WireMessage

@Serializable
data class WelcomeSettings(
    @SerialName("default_step_timeout_ms") val defaultStepTimeoutMs: Long? = null,
)

/** server -> device, one per MCP tool call. `op` is one of the 7 step-vocabulary names this
 *  build's `StepDispatcher` understands — an open string on the wire, not an enum. */
@Serializable
@SerialName("step")
data class Step(
    @SerialName("step_id") val stepId: String,
    val op: String,
    val params: JsonElement? = null,
) : WireMessage

/** device -> server, success reply to a `step`. */
@Serializable
@SerialName("result")
data class StepResult(
    @SerialName("step_id") val stepId: String,
    val output: JsonElement? = null,
) : WireMessage

/** device -> server, failure reply to a `step`. Mutually exclusive with [StepResult] for a given
 *  `step_id` — `type` is the real discriminator between the two on the wire. */
@Serializable
@SerialName("error")
data class StepError(
    @SerialName("step_id") val stepId: String,
    val code: String,
    val message: String,
) : WireMessage

/** device -> server, sent on the already-challenge-authenticated connection to mint a new connector
 *  URL when the current one was lost (D-29). No fields. Rate-limited (10/hour/device) server-side
 *  with NO reply at all when over the limit — the caller must time out (~5s), not wait for an error. */
@Serializable
@SerialName("regenerate_secret")
data object RegenerateSecret : WireMessage

/** server -> device, reply to [RegenerateSecret]. */
@Serializable
@SerialName("secret_regenerated")
data class SecretRegenerated(
    @SerialName("connector_url") val connectorUrl: String,
) : WireMessage

/**
 * D-21. Referenced from within a `step`'s `params` or a `result`'s `output` wherever a value may
 * be large. Only [Inline] is ever produced by this build (`read_screen`'s optional screenshot);
 * [Url] exists so a future server response referencing it can still be represented, even though
 * nothing in this build constructs or expects one yet.
 *
 * The wire discriminator for this shape is `kind` (`{"kind": "inline", ...}`), not `type` —
 * different from every other message in this file. This build never *decodes* a [FileReference]
 * (only ever encodes [Inline], built directly as a `JsonObject` by `StepDispatcher`, not through
 * polymorphic serialization), so the mismatch between kotlinx's default `type` discriminator key
 * and the schema's `kind` key never actually executes. If a future build needs to decode a
 * [FileReference], give it its own `Json { classDiscriminator = "kind" }` instance at that point
 * rather than working around a path that currently never runs.
 */
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
        @SerialName("expires_at") val expiresAt: String? = null,
    ) : FileReference
}

/**
 * `kotlinx.serialization.json.Json`'s polymorphic discriminator defaults to a property named
 * `type`, matching every [WireMessage] schema exactly — no custom `classDiscriminator` needed,
 * only subtype registration. `ignoreUnknownKeys` for forward compatibility (design doc §9.8: the
 * server supports protocol N and N-1, so a newer server's extra fields shouldn't break decoding).
 */
val wireJson =
    Json {
        ignoreUnknownKeys = true
        // hello.schema.json requires `capabilities`/`flow_manifest` even when empty — kotlinx
        // omits a property that equals its default value unless told not to, which would
        // otherwise silently produce a hello the server's ajv validation rejects as malformed
        // (closed with 4000, "expected a valid hello first" — found against a real server, not
        // caught by this package's own round-trip tests, which don't validate against the real
        // JSON Schema).
        encodeDefaults = true
        // A nullable field whose value is actually null (hello.android_version/device_model when
        // the provider has nothing to report, result.output when a step produced none) is omitted
        // from the wire entirely rather than encoded as literal `null` — hello's schema types those
        // two fields as plain integer/string (not nullable), so a literal null would be rejected and
        // the connection closed with 4000. This is wireJson-wide, not scoped to one message type;
        // every other nullable field this app actually encodes (result.output) has an unconstrained
        // schema (`{}`), so omitting instead of nulling it is equally valid, not a behavior change
        // that risks anything.
        explicitNulls = false
        serializersModule =
            SerializersModule {
                polymorphic(WireMessage::class) {
                    subclass(Hello::class, Hello.serializer())
                    subclass(Challenge::class, Challenge.serializer())
                    subclass(ChallengeResponse::class, ChallengeResponse.serializer())
                    subclass(Welcome::class, Welcome.serializer())
                    subclass(Step::class, Step.serializer())
                    subclass(StepResult::class, StepResult.serializer())
                    subclass(StepError::class, StepError.serializer())
                    subclass(RegenerateSecret::class, RegenerateSecret.serializer())
                    subclass(SecretRegenerated::class, SecretRegenerated.serializer())
                }
            }
    }
