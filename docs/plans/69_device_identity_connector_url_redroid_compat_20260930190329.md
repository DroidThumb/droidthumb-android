<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Device identity, secret connector URL, and a redroid compatibility script

Supersedes plan 68 (see its own superseded-note). Scope: `droidthumb-android`#5 and
`droidthumb-server`#16 — both now **merged**, read from their final PR descriptions (`gh pr view 5`/
`gh pr view 16`), not the unmerged branches plan 68 was built against. `droidthumb-protocol`#12/
`droidthumb-server`#16 changed two foundational things after plan 68 was written:

1. **`device_id` is derived, not chosen.** `"dt_" + lowercase hex SHA-256 of the public key's X.509
   SPKI DER bytes` (`droidthumb-server/src/devices/device-id.ts`; vectors:
   `droidthumb-protocol/examples/device-id-vectors.json`). `POST /devices/register` takes only
   `{public_key}` and returns the derived `{device_id, connector_url?}`. There is no more
   client-chosen or randomly-generated device id anywhere — `TransportConfig.deviceId` (a random
   UUID minted on first read) is removed entirely; the id is recomputed on demand from the Keystore
   public key every time it's needed.
2. **One listener, not two.** `DEVICE_BIND_HOST`/`DEVICE_PORT` are gone; `/device` (the WS upgrade)
   and `/devices/register` now live on the same Fastify server as `/mcp`/`/d/*`/`/events`
   (`config.mcpPort`, default 4000). Caddy is a plain catch-all. `TransportConfig.port`'s default
   moves from `4001` (the old device-relay port) to `4000`; there is only one endpoint to configure.

Also new since plan 68: `hello.android_version`/`device_model` are specified as **nullable**, omitted
when absent (`explicitNulls = false`), not the non-null-always-populated design plan 68 chose (both
were valid per android#5's own wording — "make them non-null and always populated, **or** set
`explicitNulls = false`" — this plan takes the second option per direct instruction).

Everything else plan 68 got right carries over: the Ktor `ws://`-hardcoding finding (still real, still
needs the same fix, only the default port number changes), the Keystore/AES design for identity and
connector-URL-at-rest, the connector URL UI, and the Event Channel auto-repoint. Those sections below
are adapted from plan 68, not re-derived.

**New in this plan** (not in 68 at all): a redroid compatibility script (plan 03 milestone 2's own
item, never built) and the exact device-id-derivation unit test against the published vectors.

**Out of scope**: milestone 4's update-prompt UI (`updateInfo` is exposed via `StateFlow`, nothing
renders it); accounts/de-registration; a full historical-APK regression archive (needs milestone 5's
release pipeline, which doesn't exist yet — this plan's script takes explicit local APK paths
instead, per direct instruction); onboarding flow / guided setup (plan 03 §4's larger "App v1"
milestone); anything about `latest_app_version`/`minimum_supported_app_version` enforcement logic.

## User story 1 — Wire protocol additions

Why: hand-written Kotlin types for `droidthumb-protocol`'s `challenge`/`challenge-response`/
`regenerate-secret`/`secret-regenerated` schemas and the `hello`/`welcome` fields milestone 2 added —
none exist in `Messages.kt` today. All additive (`protocol_version` stays 1).

Acceptance criteria:
- [ ] `Challenge`, `ChallengeResponse`, `RegenerateSecret`, `SecretRegenerated` are `@Serializable`
      `WireMessage` subtypes, registered in `wireJson`.
- [ ] `Hello` gains nullable `androidVersion`/`deviceModel`; `Welcome` gains nullable
      `latestAppVersion`/`minimumSupportedAppVersion`/`downloadUrl`.
- [ ] `wireJson` sets `explicitNulls = false` so a null value is omitted from the wire rather than
      encoded as literal `null` — `hello.schema.json` types `android_version`/`device_model` as plain
      `integer`/`string` (not nullable), which a literal JSON `null` fails, closing the connection
      with code 4000 (this is `encodeDefaults = true`'s well-known interaction with a nullable
      property: without `explicitNulls = false` it would encode the null anyway).
- [ ] Fixture round-trips against `droidthumb-protocol`'s own `examples/v1/*.json` files (verbatim,
      not hand-retyped), per android#5/server#16's explicit test instruction.
- [ ] The encoded `hello` for a real (non-null) `DeviceInfoProvider` never contains a JSON `null`
      anywhere (regression coverage for the gotcha above, mirroring `MessagesTest`'s existing
      `capabilities`/`flow_manifest` regression test).

### Task 1.1 — `Messages.kt` additions

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/wireprotocol/Messages.kt` (modify)

```kotlin
/** device -> server, sent once per connection to open the handshake. `device_id` is derived from
 *  the Keystore public key (services/identity/DeviceId.kt), never stored or chosen. */
@Serializable
@SerialName("hello")
data class Hello(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("apk_version") val apkVersion: String,
    @SerialName("device_id") val deviceId: String,
    val capabilities: List<String> = emptyList(),
    val mode: String,
    @SerialName("flow_manifest") val flowManifest: List<FlowManifestEntry> = emptyList(),
    @SerialName("android_version") val androidVersion: Int? = null,
    @SerialName("device_model") val deviceModel: String? = null,
) : WireMessage
```
(replaces the current `Hello` — adds the last two fields, nullable with a `null` default)

```kotlin
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
```
(insert after `FlowManifestEntry`, before `Welcome`)

```kotlin
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
```
(replaces the current `Welcome` — adds the last three fields)

```kotlin
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
```
(insert after `StepError`, before `FileReference`)

`wireJson` gains `explicitNulls = false` and the four new subtypes:
```kotlin
val wireJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
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
```

### Task 1.2 — Copy the protocol repo's v1 fixtures in as test resources

**File**: `app/src/test/resources/protocol-examples/hello.valid.json` (create) — verbatim copy of
`droidthumb-protocol/examples/v1/hello.valid.json`:
```json
{
  "type": "hello",
  "protocol_version": 1,
  "apk_version": "1.2.3",
  "android_version": 34,
  "device_model": "Google Pixel 8",
  "device_id": "pixel-8-abc123",
  "capabilities": ["a11y_tree"],
  "mode": "live",
  "flow_manifest": [{ "id": "flow-1", "version": 2 }]
}
```

**File**: `app/src/test/resources/protocol-examples/hello.valid.legacy.json` (create) — verbatim copy
of `droidthumb-protocol/examples/v1/hello.valid.legacy.json`:
```json
{
  "type": "hello",
  "protocol_version": 1,
  "apk_version": "1.0.0",
  "device_id": "pixel-8-abc123",
  "capabilities": [],
  "mode": "live",
  "flow_manifest": []
}
```

**File**: `app/src/test/resources/protocol-examples/welcome.valid.json` (create) — verbatim copy of
`droidthumb-protocol/examples/v1/welcome.valid.json`:
```json
{
  "type": "welcome",
  "accepted": true,
  "protocol_version": 1,
  "latest_app_version": "1.3.0",
  "minimum_supported_app_version": "1.0.0",
  "download_url": "https://github.com/DroidThumb/droidthumb-android/releases/latest",
  "settings": { "default_step_timeout_ms": 30000 }
}
```

**File**: `app/src/test/resources/protocol-examples/welcome.valid.legacy.json` (create) — verbatim
copy of `droidthumb-protocol/examples/v1/welcome.valid.legacy.json`:
```json
{
  "type": "welcome",
  "accepted": true,
  "protocol_version": 1,
  "settings": { "default_step_timeout_ms": 30000 }
}
```

**File**: `app/src/test/resources/protocol-examples/device-registration.valid.key-only.json` (create)
— verbatim copy of `droidthumb-protocol/examples/v1/device-registration.valid.key-only.json`:
```json
{ "public_key": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaLYBkdZrrs2nrNpdnPkFSS4F00NwONoA5e6B4Q6pB/5Oaxi6NUPTW7pJ80l8L+0Aaxt1V/87nXFRb83soCH0Sw==" }
```

**File**: `app/src/test/resources/protocol-examples/device-registration-response.valid.json` (create)
— verbatim copy of `droidthumb-protocol/examples/v1/device-registration-response.valid.json`:
```json
{ "device_id": "pixel-8-abc123", "connector_url": "https://mcp.droidthumb.com/d/dtk_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG/mcp" }
```

**File**: `app/src/test/resources/protocol-examples/device-registration-response.valid.repeat.json`
(create) — verbatim copy of the same-named protocol fixture:
```json
{ "device_id": "pixel-8-abc123" }
```

**File**: `app/src/test/resources/protocol-examples/regenerate-secret.valid.json` (create) — verbatim
copy: `{ "type": "regenerate_secret" }`

**File**: `app/src/test/resources/protocol-examples/secret-regenerated.valid.json` (create) — verbatim
copy: `{ "type": "secret_regenerated", "connector_url": "https://mcp.droidthumb.com/d/dtk_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG/mcp" }`

Note: these are committed copies, not a build-time reference into the sibling `droidthumb-protocol`
checkout (this repo has no codegen/`file:` dependency on it — wire types are hand-written). If the
protocol repo's fixtures change, these must be re-synced by hand; there is no automation for that.

### Task 1.3 — `MessagesTest` updates and additions

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/wireprotocol/MessagesTest.kt` (modify)

Add a small helper for loading a fixture as a string:
```kotlin
private fun fixture(name: String): String =
    checkNotNull(javaClass.classLoader?.getResourceAsStream("protocol-examples/$name")) { "missing fixture: $name" }
        .bufferedReader()
        .readText()
```

New tests:

| Test | Verifies |
|---|---|
| `challenge round-trips` | `Challenge(nonce = "b25jZQ==")` encode → decode → equal, `is Challenge` |
| `challenge_response round-trips` | `ChallengeResponse(signature = "c2ln")` encode → decode → equal |
| `regenerate_secret encodes with only a type field` | `wireJson.encodeToString(WireMessage.serializer(), RegenerateSecret)` produces exactly `{"type":"regenerate_secret"}`; decode round-trips to `RegenerateSecret` |
| `secret_regenerated round-trips` | `SecretRegenerated(connectorUrl = "https://host/d/dtk_x/mcp")` encode → decode → equal |
| `hello with android_version and device_model round-trips` | `Hello(..., androidVersion = 34, deviceModel = "Google Pixel 8")` encode → decode → equal |
| `hello with null android_version and device_model omits both keys` | `Hello(..., androidVersion = null, deviceModel = null)` encodes to a string containing neither `"android_version"` nor `"device_model"` (the `explicitNulls = false` regression guard — a literal `null` here is exactly the bug that gets the connection closed with 4000) |
| `result with a null output omits the output key, not an explicit null` | `StepResult(stepId = "s1", output = null)` encodes to a string with no `"output"` key at all — `explicitNulls = false` is a `wireJson`-wide setting, not scoped to `Hello`; `result.schema.json`'s `output` property is `{}` (accepts any value, including no key and including `null`), so this is a behavior change with no compatibility risk, but it's the one other place this app actually *encodes* (not just decodes) a pre-existing nullable field, so it gets its own explicit regression coverage rather than relying on the `Hello`-specific test above to stand in for it |
| `decodes the protocol repo's hello.valid.json fixture` | `wireJson.decodeFromString<WireMessage>(fixture("hello.valid.json"))` decodes to a `Hello` with every field matching the fixture's values |
| `decodes the protocol repo's hello.valid.legacy.json fixture` | Same, for the legacy (no android_version/device_model) fixture — decodes with both `null` |
| `decodes the protocol repo's welcome.valid.json fixture` | Same, for `Welcome` with all three update-info fields set |
| `decodes the protocol repo's welcome.valid.legacy.json fixture` | Same, for `Welcome` with none of the three set (all `null`) |
| `decodes the protocol repo's regenerate-secret.valid.json fixture` | Decodes to `RegenerateSecret` |
| `decodes the protocol repo's secret-regenerated.valid.json fixture` | Decodes to `SecretRegenerated` with the fixture's `connector_url` |

### Definition of Done

- [ ] All tests in the table above, plus the existing suite, pass.

---

## User story 2 — Transport config gains TLS (purely additive)

Why: confirmed by disassembling `io.ktor.client.plugins.websocket.BuildersKt` (`ktor-client-core-jvm`
3.5.2, `javap -c`): the `client.webSocket(method, host, port, path, request, block)` overload's
generated lambda always calls `url("ws", host, port, path, null)` — a hardcoded scheme — before
running the caller's own `request {}` lambda (confirmed by decompiling `webSocket$lambda$3`, which
contains `ldc_w "ws"` immediately before the `HttpRequestKt.url$default` call). There is no implicit
TLS from a port number, and no separate overload the current code uses that avoids it. The app can
therefore never reach `wss://mcp.droidthumb.com`/`wss://staging.droidthumb.com` as written.

This user story is deliberately **purely additive** — it adds `TransportConfig.tls` and
`TransportSettings.updateTransportTls`, and changes nothing an existing caller depends on.
`TransportConfig.deviceId` (the random-UUID field `DeviceTransportClientImpl`/`TransportService`
still read today) is left completely untouched here, even though User Story 3/6 makes it obsolete:
removing it, `DeviceTransportClient.start()`'s signature, and `TransportService`'s call site all have
to change in the same atomic step (each references the other two), so they're one task group inside
User Story 6, not split across two user stories. Splitting `deviceId`'s removal into this story (as
plan 69's first draft did, alongside `TransportConfig.tls`) left `TransportService.kt` referencing a
field that no longer existed until User Story 6 landed — a real forward dependency the plan-reviewer
caught. Actually *using* `tls` (wiring it into `DeviceTransportClientImpl`/`TransportService`) is
User Story 6's job for the same reason.

Acceptance criteria:
- [ ] `TransportConfig` has a `tls: Boolean` field (default `false`) and its `port` default moves
      from `4001` to `4000` (the single listener's port, `config.mcpPort` server-side).
- [ ] `TransportSettings` gains `updateTransportTls`.
- [ ] `TransportConfig.deviceId` and its minting logic are untouched by this story.

### Task 2.1 — `TransportConfig.tls`, default port

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/TransportConfig.kt` (modify)

```kotlin
@Serializable
data class TransportConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val tls: Boolean = false,
    val deviceId: String = "",
) {
    companion object {
        /** Matches `droidthumb-server`'s single-listener default (`src/config.ts`'s `mcpPort`) —
         *  MCP, events, device registration and the device WebSocket are all on this one port now
         *  (no more separate device-relay port). */
        const val DEFAULT_PORT = 4000

        fun fromJson(json: String): TransportConfig = transportJson.decodeFromString(serializer(), json)

        fun fromJsonOrDefault(json: String): TransportConfig =
            try {
                fromJson(json)
            } catch (_: Exception) {
                TransportConfig()
            }
    }

    fun toJson(): String = transportJson.encodeToString(serializer(), this)
}
```
(replaces the current `TransportConfig` — inserts `tls` after `port`; `deviceId` stays exactly where
it is for now — User Story 6 removes it together with every caller, in one step)

### Task 2.2 — `TransportSettings` gains `updateTransportTls`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/TransportSettings.kt` (modify)

Add to the interface, after `updateTransportPort` (the existing `getTransportConfig()` doc, which
describes the device-id-minting behavior, is corrected in User Story 6 alongside removing that
behavior — not here, since it's still accurate until then):
```kotlin
/** Updates whether the transport connects over TLS (wss vs ws) — the same scheme covers the
 *  WebSocket handshake and the one-time registration call, since both are the same single listener
 *  behind Caddy's TLS termination on the public hostname. */
suspend fun updateTransportTls(tls: Boolean)
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/TransportSettingsImpl.kt` (modify)

Add, after `updateTransportPort` (the existing `getTransportConfig()`'s device-id-minting `if
(current.deviceId.isNotEmpty())`/`UUID.randomUUID()` logic is untouched — User Story 6 removes it):
```kotlin
override suspend fun updateTransportTls(tls: Boolean) {
    val (old, new) = updateConfig { it.copy(tls = tls) }
    settingsChangeLogger.submit("transport_tls", old.tls.toString(), new.tls.toString()) { _, n ->
        "Remote control TLS ${if (n.toBoolean()) "enabled" else "disabled"}"
    }
}
```

### Definition of Done

- [ ] The module still compiles exactly as before this story (purely additive — no existing caller
      changed), with the two new members present.

---

## User story 3 — Device identity: derivation, Keystore, device info

Why: `device_id` must be computed the same way the server computes it
(`"dt_" + hex(SHA-256(SPKI DER bytes)))`, from an EC P-256 key pair generated once in the Android
Keystore and never exported; `hello`'s new fields need real `Build.*` values behind an interface
(this repo's `ApiLevelProvider` precedent) so the handshake stays unit-testable.

Acceptance criteria:
- [ ] `deriveDeviceId(ByteArray): String` matches all three published vectors
      (`droidthumb-protocol/examples/device-id-vectors.json`) — pure JVM code (`MessageDigest`), no
      Android dependency, directly unit-testable.
- [ ] `DeviceIdentityKeyStore` generates the EC key pair once (idempotent), exposes the base64 SPKI
      public key, and signs a base64 nonce as base64 ASN.1 DER ECDSA.
- [ ] `DeviceInfoProvider` exposes `Build.VERSION.SDK_INT` and `"${Build.MANUFACTURER} ${Build.MODEL}"`
      (trimmed, capped at the schema's 128-char limit) behind an interface.
- [ ] `DeviceIdentityKeyStoreImpl`/`DefaultDeviceInfoProvider` have no dedicated unit test
      (Android-Keystore/`Build`-only code, same carve-out as `DefaultApiLevelProvider` — verified
      manually in User Story 6's QA steps); `deriveDeviceId` and `DeviceInfoProvider`'s truncation
      logic are pure and do get tests.

### Task 3.1 — `deriveDeviceId`, tested against the published vectors

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/identity/DeviceId.kt` (create)

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.identity

import java.security.MessageDigest

/**
 * `device_id = "dt_" + lowercase hex SHA-256` of the public key's X.509 SubjectPublicKeyInfo DER
 * bytes (EC P-256) — never chosen by the device (droidthumb-server: `src/devices/device-id.ts`;
 * test vectors: `droidthumb-protocol/examples/device-id-vectors.json`). Pure JVM code (no Android
 * Keystore dependency) so it's directly unit-testable; the DER bytes it's called with come from
 * [DeviceIdentityKeyStore.ensurePublicKeyBase64] base64-decoded.
 */
fun deriveDeviceId(publicKeySpkiDer: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(publicKeySpkiDer)
    return "dt_" + digest.joinToString("") { "%02x".format(it) }
}
```

**File**: `app/src/test/resources/protocol-examples/device-id-vectors.json` (create) — verbatim copy
of `droidthumb-protocol/examples/device-id-vectors.json`:
```json
{
  "algorithm": "device_id = \"dt_\" + lowercase hex of SHA-256 over the DER bytes of the public key (X.509 SubjectPublicKeyInfo, EC P-256), i.e. the bytes that base64-decode from public_key. Not a choice the device makes: the server derives the same value and refuses any other.",
  "vectors": [
    {
      "public_key": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaLYBkdZrrs2nrNpdnPkFSS4F00NwONoA5e6B4Q6pB/5Oaxi6NUPTW7pJ80l8L+0Aaxt1V/87nXFRb83soCH0Sw==",
      "device_id": "dt_1d5aaa900ef5ffb98ae91a05932113643681ebc96ed43bdd084195ce34a9b09e"
    },
    {
      "public_key": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEch5ryDL73l9Sw9jowm0vxZWvIf3rS4X3nSbzXOGq+ll6uIhkVDUmdDWm/ugBvcsjNyv0cSGCWJrxmrlkH//YDw==",
      "device_id": "dt_6dbd54ada60a4fcb2504de0db84c73687445530e1c2b89fa5fe7a7e509d6903b"
    },
    {
      "public_key": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE3Smsc00+NxdCZpw0ZncYBFhdAVhaHtFAS01LhNSlnDtXgV67pL1xRVCan8zjmoxg6BSbq9PjQxoawtA4Nz5/8w==",
      "device_id": "dt_ed4ce18ef7b35245fc7f858801cabe1ddb10de71f057387c277ca71cd14015d4"
    }
  ]
}
```
(same never-automated-sync caveat as Task 1.2's fixtures)

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/identity/DeviceIdTest.kt` (create)

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.identity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Base64

@Serializable
private data class Vector(
    @kotlinx.serialization.SerialName("public_key") val publicKey: String,
    @kotlinx.serialization.SerialName("device_id") val deviceId: String,
)

@Serializable
private data class VectorsFile(
    val vectors: List<Vector>,
)

class DeviceIdTest {
    @Test
    fun `derives ids matching the published vectors`() {
        val json =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("protocol-examples/device-id-vectors.json"))
                .bufferedReader()
                .readText()
        val vectors = Json { ignoreUnknownKeys = true }.decodeFromString(VectorsFile.serializer(), json).vectors
        check(vectors.size == 3) { "expected 3 vectors, found ${vectors.size} — did the fixture change?" }
        for (vector in vectors) {
            val der = Base64.getDecoder().decode(vector.publicKey)
            assertEquals(vector.deviceId, deriveDeviceId(der))
        }
    }
}
```

### Task 3.2 — `DeviceIdentityKeyStore`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/identity/DeviceIdentityKeyStore.kt` (create)

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's persistent identity (D-27): an EC (P-256) key pair generated once in the Android
 * Keystore, hardware-backed where the device supports it (`AndroidKeyStore` uses StrongBox/TEE
 * automatically when available for this key's purposes; no explicit opt-in needed), and never
 * exported. [ensurePublicKeyBase64] returns the base64 X.509 SPKI DER public key sent to
 * `POST /devices/register` and used to derive `device_id` ([deriveDeviceId]); [signNonce] answers
 * the connect-time `challenge` with an ASN.1 DER ECDSA signature
 * (droidthumb-protocol/schema/challenge-response.schema.json) — the default output shape of
 * `Signature.getInstance("SHA256withECDSA")` against a Keystore-backed key, matching the schema's
 * own documented expectation.
 */
interface DeviceIdentityKeyStore {
    fun ensurePublicKeyBase64(): String

    fun signNonce(nonceBase64: String): String
}

@Singleton
class DeviceIdentityKeyStoreImpl
    @Inject
    constructor() : DeviceIdentityKeyStore {
        private val keyStore: KeyStore by lazy {
            KeyStore.getInstance(PROVIDER).apply { load(null) }
        }

        override fun ensurePublicKeyBase64(): String {
            if (!keyStore.containsAlias(ALIAS)) {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
                val spec =
                    KeyGenParameterSpec
                        .Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .build()
                generator.initialize(spec)
                generator.generateKeyPair()
            }
            val publicKey = keyStore.getCertificate(ALIAS).publicKey
            return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        }

        override fun signNonce(nonceBase64: String): String {
            val privateKey = keyStore.getKey(ALIAS, null) as PrivateKey
            val nonceBytes = Base64.decode(nonceBase64, Base64.NO_WRAP)
            val signature =
                Signature.getInstance("SHA256withECDSA").apply {
                    initSign(privateKey)
                    update(nonceBytes)
                }
            return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
        }

        private companion object {
            const val PROVIDER = "AndroidKeyStore"
            const val ALIAS = "droidthumb_device_identity"
        }
    }
```

### Task 3.3 — `DeviceInfoProvider`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/identity/DeviceInfoProvider.kt` (create)

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.identity

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/** Real values for `hello.android_version`/`hello.device_model` — behind an interface (not
 *  `Build.*` read directly in `DeviceTransportClientImpl`) so the handshake is unit-testable
 *  without real `Build` values. */
interface DeviceInfoProvider {
    val androidVersion: Int
    val deviceModel: String
}

/** `deviceModel` is manufacturer + model per android#5 ("Google Pixel 8", not just "Pixel 8") —
 *  trimmed and capped at `device_model`'s schema limit (1-128 chars) so an unusually long
 *  `Build.MANUFACTURER`/`Build.MODEL` can never make `hello` schema-invalid. */
@Singleton
class DefaultDeviceInfoProvider
    @Inject
    constructor() : DeviceInfoProvider {
        override val androidVersion: Int = Build.VERSION.SDK_INT
        override val deviceModel: String =
            "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(MAX_DEVICE_MODEL_LENGTH)

        private companion object {
            const val MAX_DEVICE_MODEL_LENGTH = 128
        }
    }
```

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/identity/DeviceInfoProviderTest.kt` (create)

Since `Build.MANUFACTURER`/`Build.MODEL` can't be set on the plain JVM, this only tests the pure
truncation/trim logic, extracted so it's testable without `Build` at all:

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/identity/DeviceInfoProvider.kt` (same file, additional top-level function)

```kotlin
/** Extracted so the truncation/trim rule is unit-testable without a real `Build` value. */
internal fun formatDeviceModel(
    manufacturer: String,
    model: String,
): String = "$manufacturer $model".trim().take(MAX_DEVICE_MODEL_LENGTH_FOR_TEST)

internal const val MAX_DEVICE_MODEL_LENGTH_FOR_TEST = 128
```
Reuse this from `DefaultDeviceInfoProvider.deviceModel` (`formatDeviceModel(Build.MANUFACTURER,
Build.MODEL)`) instead of duplicating the expression, and drop the private `MAX_DEVICE_MODEL_LENGTH`/
companion object above in favor of the single top-level constant.

| Test | Verifies |
|---|---|
| `formats manufacturer and model with a space` | `formatDeviceModel("Google", "Pixel 8") == "Google Pixel 8"` |
| `trims leading/trailing whitespace` | `formatDeviceModel(" Google ", " Pixel 8 ") == "Google   Pixel 8"` — wait, verify actual behavior: `"$manufacturer $model".trim()` only trims the OUTER whitespace, not doubled internal spaces; assert the exact expected output for this input rather than guessing — compute it from the real expression when writing the test |
| `truncates to 128 chars` | A manufacturer+model string longer than 128 chars is truncated to exactly 128 |

### Task 3.4 — Hilt bindings

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt` (modify)

Add imports for the two new interface/impl pairs, then in `ServiceModule`:
```kotlin
@Binds
@Singleton
abstract fun bindDeviceIdentityKeyStore(impl: DeviceIdentityKeyStoreImpl): DeviceIdentityKeyStore

@Binds
@Singleton
abstract fun bindDeviceInfoProvider(impl: DefaultDeviceInfoProvider): DeviceInfoProvider
```

---

## User story 4 — Connector URL secure settings storage

Why: `ConnectorUrlSettings` is the durable, encrypted home for the connector URL (server#16), read by
the transport (to know a `connector_url` arrived) and by the Event Channel (User Story 8, to derive
its events endpoint). Encryption at rest is AES-256/GCM with a key that never leaves the Android
Keystore — `allowBackup="false"` is already set for the whole app (`AndroidManifest.xml`), so "never
in backups" needs no separate manifest work.

Acceptance criteria:
- [ ] `ConnectorUrlSettings` extends `SettingsRepository` alongside the existing slices.
- [ ] The stored value is always `ConnectorSecretCrypto`-encrypted; a decrypt failure (corrupted/
      missing data) is treated as "no connector URL yet", not a crash.
- [ ] Every update is logged via `SettingsChangeLogger` using fixed sentinel old/new strings — never
      the real URL — so the secret never reaches the logger's in-memory coalescing state either, not
      even transiently (stricter than the existing `authToken` pattern, which passes the real secret
      into `submit()` and relies only on the render lambda not using it).

### Task 4.1 — `ConnectorSecretCrypto`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/identity/ConnectorSecretCrypto.kt` (create)

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256/GCM encryption for the connector URL at rest (server#16: "store it durably... Keystore-
 * encrypted, never logged, not in backups/crash reports"). The key never leaves the Android
 * Keystore. Ciphertext is stored as `base64(iv):base64(bytes)`
 * ([com.danielealbano.androidremotecontrolmcp.data.repository.ConnectorUrlSettingsImpl]).
 */
interface ConnectorSecretCrypto {
    fun encrypt(plaintext: String): String

    fun decrypt(ciphertext: String): String
}

@Singleton
class ConnectorSecretCryptoImpl
    @Inject
    constructor() : ConnectorSecretCrypto {
        private val keyStore: KeyStore by lazy {
            KeyStore.getInstance(PROVIDER).apply { load(null) }
        }

        private val secretKey: SecretKey
            get() {
                (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
                val spec =
                    KeyGenParameterSpec
                        .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                generator.init(spec)
                return generator.generateKey()
            }

        override fun encrypt(plaintext: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey) }
            val bytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }

        override fun decrypt(ciphertext: String): String {
            val (ivPart, bytesPart) = ciphertext.split(":", limit = 2)
            val iv = Base64.decode(ivPart, Base64.NO_WRAP)
            val bytes = Base64.decode(bytesPart, Base64.NO_WRAP)
            val cipher =
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                }
            return String(cipher.doFinal(bytes), Charsets.UTF_8)
        }

        private companion object {
            const val PROVIDER = "AndroidKeyStore"
            const val ALIAS = "droidthumb_connector_secret"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
            const val GCM_TAG_LENGTH_BITS = 128
        }
    }
```

### Task 4.2 — `ConnectorUrlSettings`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/ConnectorUrlSettings.kt` (create)

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.repository

import kotlinx.coroutines.flow.Flow

/** The per-device secret connector URL (plan 03 milestone 3, D-29): `https://<host>/d/<secret>/mcp`,
 *  returned once by `POST /devices/register` and recoverable via `regenerate_secret` if lost. Stored
 *  encrypted at rest via [com.danielealbano.androidremotecontrolmcp.services.identity.ConnectorSecretCrypto]. */
interface ConnectorUrlSettings {
    val connectorUrl: Flow<String?>

    suspend fun getConnectorUrl(): String?

    suspend fun updateConnectorUrl(url: String)
}
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/ConnectorUrlSettingsImpl.kt` (create)

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.services.identity.ConnectorSecretCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ConnectorUrlSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val crypto: ConnectorSecretCrypto,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : ConnectorUrlSettings {
        override val connectorUrl: Flow<String?> =
            dataStore.data.map { prefs ->
                val encrypted = prefs[CONNECTOR_URL_KEY] ?: return@map null
                runCatching { crypto.decrypt(encrypted) }.getOrNull()
            }

        override suspend fun getConnectorUrl(): String? = connectorUrl.first()

        override suspend fun updateConnectorUrl(url: String) {
            val hadPrevious = getConnectorUrl() != null
            dataStore.edit { prefs -> prefs[CONNECTOR_URL_KEY] = crypto.encrypt(url) }
            // Fixed sentinel old/new values — never the real URL — so the secret never reaches
            // SettingsChangeLogger's in-memory coalescing state, not even transiently.
            settingsChangeLogger.submit("connector_url", "", "x") { _, _ ->
                if (hadPrevious) "Connector URL regenerated" else "Connector URL received"
            }
        }

        private companion object {
            private val CONNECTOR_URL_KEY = stringPreferencesKey("connector_url_encrypted")
        }
    }
```

### Task 4.3 — Wire into `SettingsRepository`, `SettingsRepositoryImpl`, and Hilt

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt` (modify)

```kotlin
interface SettingsRepository :
    EventChannelSettings,
    TransportSettings,
    ConnectorUrlSettings
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt` (modify)

The real current file is a pure by-delegation class with a two-arg constructor — it must gain the
third slice or it's left with an unimplemented abstract member:
```kotlin
class SettingsRepositoryImpl
    @Inject
    constructor(
        eventChannelSettings: EventChannelSettings,
        transportSettings: TransportSettings,
        connectorUrlSettings: ConnectorUrlSettings,
    ) : SettingsRepository,
        EventChannelSettings by eventChannelSettings,
        TransportSettings by transportSettings,
        ConnectorUrlSettings by connectorUrlSettings
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt` (modify)

In `RepositoryModule`:
```kotlin
@Binds
@Singleton
abstract fun bindConnectorUrlSettings(impl: ConnectorUrlSettingsImpl): ConnectorUrlSettings
```

### Task 4.4 — Fix the two existing `SettingsRepositoryImpl` construction sites, add a pass-through test

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt` (modify)

The real file constructs `SettingsRepositoryImpl(eventChannelSettings, transportSettings)` (2 args):
```kotlin
private val eventChannelSettings = mockk<EventChannelSettings>(relaxed = true)
private val transportSettings = mockk<TransportSettings>(relaxed = true)
private val connectorUrlSettings = mockk<ConnectorUrlSettings>(relaxed = true)
private val repository = SettingsRepositoryImpl(eventChannelSettings, transportSettings, connectorUrlSettings)
```
(replaces the current 2-arg fields/constructor)

Add a new test:
```kotlin
@Test
fun `updateConnectorUrl delegates to the slice`() =
    runTest {
        repository.updateConnectorUrl("https://h/d/x/mcp")

        coVerify { connectorUrlSettings.updateConnectorUrl("https://h/d/x/mcp") }
    }
```

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryLoggingTest.kt` (modify)

The real `setUp()` constructs `SettingsRepositoryImpl` from two REAL impls sharing a real
(tempfile-backed) `DataStore` — `ConnectorUrlSettingsImpl` needs a `ConnectorSecretCrypto` too, and
the real `ConnectorSecretCryptoImpl` touches the actual Android Keystore, unavailable on the plain
JVM this test runs on. Use a trivial identity fake instead — this test only cares about
`SettingsChangeLogger` coalescing behavior, not real encryption:
```kotlin
val changeLogger = SettingsChangeLogger(serverLog, testDispatcher, WINDOW)
val identityCrypto =
    object : ConnectorSecretCrypto {
        override fun encrypt(plaintext: String) = plaintext
        override fun decrypt(ciphertext: String) = ciphertext
    }
repository =
    SettingsRepositoryImpl(
        EventChannelSettingsImpl(dataStore, changeLogger),
        TransportSettingsImpl(dataStore, changeLogger),
        ConnectorUrlSettingsImpl(dataStore, identityCrypto, changeLogger),
    )
```
(replaces the current `val changeLogger = ...` / `repository = SettingsRepositoryImpl(...)` block;
add `import com.danielealbano.androidremotecontrolmcp.services.identity.ConnectorSecretCrypto`)

### Definition of Done

- [ ] Delegation tests (existing + new) pass.
- [ ] No dedicated `ConnectorUrlSettingsImplTest`/`ConnectorSecretCryptoImplTest` (DataStore
      persistence needs an instrumented test, same precedent as `TransportSettingsImpl`/
      `EventChannelSettingsImpl`; the crypto impl is Android-Keystore-only).

---

## User story 5 — Device registration HTTP client

Why: `POST /devices/register` must be called once before the device's first WebSocket connection,
sending only `{public_key}` (device#16's exact request shape — no `device_id` field at all, since the
deprecated optional field adds nothing this app needs to use), handling the 429/`Retry-After` rate
limit, and checking the returned `device_id` against the one this app derived locally.

Acceptance criteria:
- [ ] `register()` returns a typed `Success(deviceId, connectorUrl)` / `RateLimited(retryAfterSeconds)`
      / `Failed(message)` result — never throws.
- [ ] `Success.connectorUrl` is `null` when the call didn't create the device (idempotent
      re-registration), matching `device-registration-response.schema.json`'s optional field.
- [ ] Tested against a real embedded HTTP server (same pattern as `EventDispatcherImplTest`), not
      mocks.

### Task 5.1 — `DeviceRegistrationClient`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/transport/DeviceRegistrationClient.kt` (create)

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.transport

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.http.path
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DeviceRegistrationResult {
    /** `connector_url` is present only when this call created the device (first-ever registration);
     *  a re-registration of an already-known key returns it as `null` — expected, not an error. */
    data class Success(
        val deviceId: String,
        val connectorUrl: String?,
    ) : DeviceRegistrationResult

    data class RateLimited(
        val retryAfterSeconds: Int?,
    ) : DeviceRegistrationResult

    data class Failed(
        val message: String,
    ) : DeviceRegistrationResult
}

interface DeviceRegistrationClient {
    /** `POST /devices/register` (device-registration.schema.json) — idempotent, made once before
     *  the device's first WebSocket connection (D-27). Sends only `public_key`; the server derives
     *  and returns `device_id` (server#16 item 2 — the deprecated, optional request `device_id`
     *  field is never sent). Same host/port/tls as the WS transport: both are the single listener,
     *  unaffected by milestone 3's `/d/<secret>/*` routing. */
    suspend fun register(
        host: String,
        port: Int,
        tls: Boolean,
        publicKeyBase64: String,
    ): DeviceRegistrationResult
}

@Serializable
private data class RegisterRequestBody(
    @SerialName("public_key") val publicKey: String,
)

@Serializable
private data class RegisterResponseBody(
    @SerialName("device_id") val deviceId: String,
    @SerialName("connector_url") val connectorUrl: String? = null,
)

@Singleton
class DeviceRegistrationClientImpl
    @Inject
    constructor() : DeviceRegistrationClient {
        private val client by lazy {
            HttpClient(OkHttp) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(HttpTimeout) {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun register(
            host: String,
            port: Int,
            tls: Boolean,
            publicKeyBase64: String,
        ): DeviceRegistrationResult =
            withContext(Dispatchers.IO) {
                try {
                    val response: HttpResponse =
                        client.post {
                            url {
                                protocol = if (tls) URLProtocol.HTTPS else URLProtocol.HTTP
                                this.host = host
                                this.port = port
                                path(REGISTER_PATH)
                            }
                            contentType(ContentType.Application.Json)
                            setBody(RegisterRequestBody(publicKeyBase64))
                        }
                    when (response.status) {
                        HttpStatusCode.OK -> {
                            val body = response.body<RegisterResponseBody>()
                            DeviceRegistrationResult.Success(body.deviceId, body.connectorUrl)
                        }
                        HttpStatusCode.TooManyRequests ->
                            DeviceRegistrationResult.RateLimited(
                                response.headers[HttpHeaders.RetryAfter]?.toIntOrNull(),
                            )
                        else -> DeviceRegistrationResult.Failed("HTTP ${response.status.value}")
                    }
                } catch (e: Exception) {
                    DeviceRegistrationResult.Failed(e.message ?: "registration failed")
                }
            }

        private companion object {
            const val REGISTER_PATH = "/devices/register"
            const val REQUEST_TIMEOUT_MS = 5_000L
            const val CONNECT_TIMEOUT_MS = 3_000L
        }
    }
```

### Task 5.2 — Hilt binding

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt` (modify)

In `ServiceModule`:
```kotlin
@Binds
@Singleton
abstract fun bindDeviceRegistrationClient(impl: DeviceRegistrationClientImpl): DeviceRegistrationClient
```

### Task 5.3 — Tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/transport/DeviceRegistrationClientTest.kt` (create)

**Setup**: embedded `Netty` server (test-classpath-only, same pattern as `EventDispatcherImplTest`),
one `post("/devices/register") { ... }` route per test controlling the response; `client =
DeviceRegistrationClientImpl()`; `tls = false`, `host = "127.0.0.1"`, `port = <server's bound port>`.

| Test | Verifies |
|---|---|
| `200 with connector_url returns Success with device_id and that URL` | Route responds `{"device_id":"dt_abc","connector_url":"https://h/d/x/mcp"}` → `Success("dt_abc", "https://h/d/x/mcp")` |
| `200 with no connector_url returns Success with a null URL` | Route responds `{"device_id":"dt_abc"}` → `Success("dt_abc", null)` — the idempotent-re-registration shape |
| `429 with Retry-After returns RateLimited with that value` | Route responds 429, header `Retry-After: 120` → `RateLimited(120)` |
| `429 with no Retry-After returns RateLimited(null)` | Route responds 429, no header → `RateLimited(null)` |
| `400 returns Failed with the status code` | Route responds 400 (invalid-key shape) → `Failed("HTTP 400")` |
| `request body sends only public_key` | Route captures the received JSON body; asserts it has exactly one key, `public_key`, matching the call's argument — no `device_id` field at all |
| `unreachable server returns Failed` | `port` pointed at a closed port → `Failed(...)`, no exception thrown out of `register()` |

---

## User story 6 — Wire the handshake, registration, and regenerate-secret into `DeviceTransportClientImpl`

Why: this is where every prior user story's pieces meet the actual connection lifecycle. `device_id`
is now computed, not stored — `start()` no longer takes one at all.

Acceptance criteria:
- [ ] Registration happens once per `start()` call, not on every reconnect: re-registering is
      idempotent server-side but rate-limited (30/hour/IP), and the reconnect loop can retry
      indefinitely, so repeating it on every attempt risks exhausting that budget on ordinary
      reconnect churn. A registration that hasn't succeeded yet is retried on the next loop
      iteration (the same backoff schedule already governing WS reconnects — satisfies server#16's
      "back off, don't loop" for the 429 case with no extra machinery); one that has succeeded (or
      that fatally can't — see below) is never retried again for the lifetime of this `start()` call.
- [ ] A `challenge` is answered with `ChallengeResponse(signNonce(nonce))` before `welcome` can ever
      arrive; the existing `Rejected`/backoff handling for a 4000-4003 close needs no changes.
- [ ] The registration response's `device_id` is checked against the one this app derived locally
      (server#16 item 2: "must equal the one you computed (treat a mismatch as a fatal error)") — on
      mismatch, log clearly and stop retrying registration for this `start()` call (retrying an
      impossible mismatch would just burn the rate-limit budget forever); the resulting WS connects
      will keep failing with 4002, already surfaced via the existing `Rejected` status.
- [ ] `hello.device_id` is the derived id; `hello.android_version`/`device_model` come from
      `DeviceInfoProvider` (real, non-null values in practice — never actually omitted, despite the
      wire type being nullable to match the schema's true optionality).
- [ ] `welcome`'s update-info fields are exposed via a new `updateInfo: StateFlow<UpdateInfo?>`.
- [ ] A `connector_url` from either registration or `secret_regenerated` is persisted via
      `ConnectorUrlSettings`.
- [ ] `regenerateSecret()`: sends `RegenerateSecret` only while actually `Connected`; returns `false`
      immediately (no wait) when not connected; otherwise awaits `secret_regenerated` up to 5s and
      returns whether it arrived in time. Accepted edge case: a `secret_regenerated` that arrives in
      the narrow window right as the 5s timeout fires can make this return `false` even though the
      new URL was still correctly persisted in `runSession`'s own handler — a caller-visible false
      negative on an already-successful rotation, not a data-loss bug, not worth the added complexity
      of closing a window this narrow on a manual, confirmation-gated action.
- [ ] The connector URL never reaches `Logger` (which redacts UUID-shaped strings only — the
      `dtk_`-prefixed secret doesn't match that pattern, so the sanitizer would do nothing for it;
      protection comes from never passing it to `Logger` at all, not from the sanitizer) — tested
      directly by mocking `Logger` and scanning every captured message.

### Task 6.1 — Remove the stored device id

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/TransportConfig.kt` (modify)

```kotlin
@Serializable
data class TransportConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val tls: Boolean = false,
) {
```
(drops `val deviceId: String = ""` — the rest of the class, including `DEFAULT_PORT`, is unchanged
from User Story 2's version)

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/TransportSettings.kt` (modify)

```kotlin
/** Returns the current transport configuration as a one-shot read. */
suspend fun getTransportConfig(): TransportConfig
```
(replaces the doc comment — "On first-ever read (no `deviceId` persisted yet), generates and persists
a stable device id..." — there is nothing left to mint; `device_id` is derived from the Keystore key
on demand, computed fresh wherever it's needed, not read from `TransportConfig`)

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/TransportSettingsImpl.kt` (modify)

```kotlin
override suspend fun getTransportConfig(): TransportConfig = transportConfig.first()
```
(replaces the current `getTransportConfig()` — drops the `if (current.deviceId.isNotEmpty())` /
`UUID.randomUUID()` minting branch entirely; `updateConfig`'s own call to `getTransportConfig()` now
goes through this simplified version, no other change needed there. Remove the now-unused `import
java.util.UUID`.)

### Task 6.2 — Full `DeviceTransportClient.kt` rewrite

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/transport/DeviceTransportClient.kt` (modify — the full rewrite; incorporates Task 6.1's `deviceId` removal and User Story 2's `tls` plumbing in one consistent file)

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.transport

import android.util.Base64
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.ConnectorUrlSettings
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceIdentityKeyStore
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceInfoProvider
import com.danielealbano.androidremotecontrolmcp.services.identity.deriveDeviceId
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Challenge
import com.danielealbano.androidremotecontrolmcp.wireprotocol.ChallengeResponse
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Hello
import com.danielealbano.androidremotecontrolmcp.wireprotocol.RegenerateSecret
import com.danielealbano.androidremotecontrolmcp.wireprotocol.SecretRegenerated
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Step
import com.danielealbano.androidremotecontrolmcp.wireprotocol.StepDispatcher
import com.danielealbano.androidremotecontrolmcp.wireprotocol.Welcome
import com.danielealbano.androidremotecontrolmcp.wireprotocol.WireMessage
import com.danielealbano.androidremotecontrolmcp.wireprotocol.wireJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TransportStatus {
    data object Idle : TransportStatus

    data object Connecting : TransportStatus

    data class Connected(
        val protocolVersion: Int,
    ) : TransportStatus

    /** The connection closed (or never opened) before a `welcome` arrived — a handshake-level
     *  rejection (wrong subprotocol, 4000-4003, or a transport error), not a mid-session drop. */
    data class Rejected(
        val closeCode: Short?,
        val reason: String,
    ) : TransportStatus

    data class Reconnecting(
        val attempt: Int,
        val delayMs: Long,
    ) : TransportStatus
}

/** `welcome.latest_app_version`/`minimum_supported_app_version`/`download_url` — kept for milestone
 *  4's update-prompt UI; nothing reads this yet (android#5 item 4). */
data class UpdateInfo(
    val latestAppVersion: String?,
    val minimumSupportedAppVersion: String?,
    val downloadUrl: String?,
)

interface DeviceTransportClient {
    val status: StateFlow<TransportStatus>
    val updateInfo: StateFlow<UpdateInfo?>

    fun start(
        host: String,
        port: Int,
        tls: Boolean,
    )

    fun stop()

    /** Sends `regenerate_secret` on the current connection and awaits `secret_regenerated`, up to a
     *  5s timeout — the server sends NO reply at all when the per-device rate limit (10/hour) is
     *  exceeded, so a bounded wait is the only way to detect that (server#16). Returns `false`
     *  immediately, with no wait, when not currently `Connected`. */
    suspend fun regenerateSecret(): Boolean
}

/**
 * The M2/M3 outbound WebSocket client: offers `droidthumb.v1`, sends `hello`, answers a `challenge`
 * with the Keystore-signed nonce (D-27) before `welcome` can arrive, dispatches inbound `step`s
 * through [StepDispatcher], and carries [RegenerateSecret]/[SecretRegenerated] once connected.
 * Reconnects with capped exponential backoff on any drop. `device_id` is never stored — it's
 * [deriveDeviceId] applied to the Keystore public key, recomputed whenever needed (cheap: one
 * base64 decode and one SHA-256 hash, no I/O).
 *
 * Registration (`POST /devices/register`) happens once per [start] — not on every reconnect — via
 * [ensureRegistered]'s [registered] latch: see this user story's acceptance criteria for why.
 */
@Singleton
class DeviceTransportClientImpl
    @Inject
    constructor(
        private val stepDispatcher: StepDispatcher,
        private val deviceIdentityKeyStore: DeviceIdentityKeyStore,
        private val deviceInfoProvider: DeviceInfoProvider,
        private val registrationClient: DeviceRegistrationClient,
        private val connectorUrlSettings: ConnectorUrlSettings,
    ) : DeviceTransportClient {
        private val _status = MutableStateFlow<TransportStatus>(TransportStatus.Idle)
        override val status: StateFlow<TransportStatus> = _status.asStateFlow()

        private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
        override val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

        private var job: Job? = null
        private val client by lazy { HttpClient(OkHttp) { install(WebSockets) } }
        private val registered = AtomicBoolean(false)

        @Volatile
        private var currentOutbox: SendChannel<WireMessage>? = null

        private val regenerateMutex = Mutex()

        @Volatile
        private var pendingRegenerate: CompletableDeferred<String>? = null

        private fun currentDeviceId(): String =
            deriveDeviceId(Base64.decode(deviceIdentityKeyStore.ensurePublicKeyBase64(), Base64.NO_WRAP))

        override fun start(
            host: String,
            port: Int,
            tls: Boolean,
        ) {
            stop()
            registered.set(false)
            job =
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    var attempt = 0
                    while (isActive) {
                        _status.value =
                            if (attempt == 0) {
                                TransportStatus.Connecting
                            } else {
                                TransportStatus.Reconnecting(attempt, backoffMs(attempt))
                            }
                        if (attempt > 0) delay(backoffMs(attempt))
                        ensureRegistered(host, port, tls)
                        val welcomed = runSession(host, port, tls)
                        attempt = if (welcomed) 0 else attempt + 1
                    }
                }
        }

        private suspend fun ensureRegistered(
            host: String,
            port: Int,
            tls: Boolean,
        ) {
            if (registered.get()) return
            val publicKey = deviceIdentityKeyStore.ensurePublicKeyBase64()
            val expectedId = currentDeviceId()
            when (val result = registrationClient.register(host, port, tls, publicKey)) {
                is DeviceRegistrationResult.Success -> {
                    if (result.deviceId != expectedId) {
                        Logger.e(TAG, "Registration returned a device_id that doesn't match the derived one")
                        registered.set(true) // can never succeed; stop burning the rate-limit budget retrying
                        return
                    }
                    registered.set(true)
                    result.connectorUrl?.let { connectorUrlSettings.updateConnectorUrl(it) }
                }

                is DeviceRegistrationResult.RateLimited ->
                    Logger.w(TAG, "Registration rate-limited, retry-after=${result.retryAfterSeconds}")

                is DeviceRegistrationResult.Failed -> Logger.w(TAG, "Registration failed: ${result.message}")
            }
        }

        /** Returns true iff a `welcome` was received this session — used to reset backoff on a
         *  session that connected properly even if it later dropped. */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun runSession(
            host: String,
            port: Int,
            tls: Boolean,
        ): Boolean {
            var welcomed = false
            try {
                client.webSocket(
                    method = HttpMethod.Get,
                    host = host,
                    port = port,
                    path = "/device",
                    request = {
                        header(HttpHeaders.SecWebSocketProtocol, SUBPROTOCOL)
                        if (tls) url.protocol = URLProtocol.WSS
                    },
                ) {
                    val outbox = Channel<WireMessage>(Channel.BUFFERED)
                    currentOutbox = outbox
                    try {
                        send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), helloFor())))
                        launch {
                            for (message in outbox) {
                                send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), message)))
                            }
                        }
                        for (frame in incoming) {
                            val message = decodeOrNull(frame) ?: continue
                            when (message) {
                                is Challenge -> {
                                    val signature = deviceIdentityKeyStore.signNonce(message.nonce)
                                    send(
                                        Frame.Text(
                                            wireJson.encodeToString(
                                                WireMessage.serializer(),
                                                ChallengeResponse(signature),
                                            ),
                                        ),
                                    )
                                }

                                is Welcome -> {
                                    welcomed = true
                                    Logger.i(TAG, "Connected: protocol_version=${message.protocolVersion}")
                                    _updateInfo.value =
                                        UpdateInfo(
                                            message.latestAppVersion,
                                            message.minimumSupportedAppVersion,
                                            message.downloadUrl,
                                        )
                                    _status.value = TransportStatus.Connected(message.protocolVersion)
                                }

                                is Step -> {
                                    val reply = stepDispatcher.dispatch(message)
                                    send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), reply)))
                                }

                                is SecretRegenerated -> {
                                    connectorUrlSettings.updateConnectorUrl(message.connectorUrl)
                                    pendingRegenerate?.complete(message.connectorUrl)
                                }

                                // Hello/ChallengeResponse/RegenerateSecret/StepResult/StepError never
                                // arrive server -> device
                                else -> {}
                            }
                        }
                        if (!welcomed) {
                            val reason = closeReason.await()
                            Logger.w(TAG, "Closed before welcome: code=${reason?.code} message=${reason?.message}")
                            _status.value =
                                TransportStatus.Rejected(reason?.code, reason?.message ?: "closed before welcome")
                        }
                    } finally {
                        outbox.close()
                        if (currentOutbox === outbox) currentOutbox = null
                    }
                }
            } catch (e: CancellationException) {
                // stop() cancelling this session's job resumes a suspended incoming.receive()/send()
                // with this — rethrow rather than reporting Rejected, which would race stop()'s own
                // synchronous Idle write.
                throw e
            } catch (e: Exception) {
                if (!welcomed) _status.value = TransportStatus.Rejected(null, e.message ?: "connection failed")
                Logger.w(TAG, "Transport session ended: ${e.message}")
            }
            return welcomed
        }

        override suspend fun regenerateSecret(): Boolean =
            regenerateMutex.withLock {
                if (_status.value !is TransportStatus.Connected) return@withLock false
                val outbox = currentOutbox ?: return@withLock false
                val deferred = CompletableDeferred<String>()
                pendingRegenerate = deferred
                // A narrow session-teardown race (currentOutbox closed between the check above and
                // this send) makes trySend fail — checked explicitly so that case returns
                // immediately instead of waiting out the full 5s timeout for a message that was
                // never actually sent.
                if (!outbox.trySend(RegenerateSecret).isSuccess) {
                    pendingRegenerate = null
                    return@withLock false
                }
                val result = withTimeoutOrNull(REGENERATE_TIMEOUT_MS) { deferred.await() }
                pendingRegenerate = null
                result != null
            }

        /** Decodes one inbound frame, or null for a non-text frame or a malformed payload (logged
         *  and skipped rather than tearing down the whole session over one bad frame). */
        @Suppress("TooGenericExceptionCaught")
        private fun decodeOrNull(frame: Frame): WireMessage? {
            if (frame !is Frame.Text) return null
            return try {
                wireJson.decodeFromString(WireMessage.serializer(), frame.readText())
            } catch (e: Exception) {
                Logger.w(TAG, "Ignoring malformed frame: ${e.message}")
                null
            }
        }

        private fun helloFor(): Hello =
            Hello(
                protocolVersion = 1,
                apkVersion = BuildConfig.VERSION_NAME,
                deviceId = currentDeviceId(),
                capabilities = emptyList(),
                mode = "live",
                flowManifest = emptyList(),
                androidVersion = deviceInfoProvider.androidVersion,
                deviceModel = deviceInfoProvider.deviceModel,
            )

        override fun stop() {
            job?.cancel()
            job = null
            currentOutbox = null
            pendingRegenerate = null
            _status.value = TransportStatus.Idle
        }

        private fun backoffMs(attempt: Int): Long {
            val schedule = BACKOFF_SCHEDULE_MS
            return schedule.getOrElse(attempt - 1) { schedule.last() }
        }

        companion object {
            private const val TAG = "MCP:DeviceTransport"
            const val SUBPROTOCOL = "droidthumb.v1"
            private const val REGENERATE_TIMEOUT_MS = 5_000L
            private val BACKOFF_SCHEDULE_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        }
    }
```

### Task 6.3 — Hilt binding for `DeviceTransportClient` is unchanged

`AppModule.kt`'s existing `bindDeviceTransportClient` (`ServiceModule`) needs no edit — the bound
types are unchanged, only the impl's own constructor dependencies grew, which Hilt resolves from the
bindings added in Tasks 3.4/4.3/5.2.

### Task 6.4 — `TransportService` passes the new `start()` signature

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/transport/TransportService.kt` (modify)

```kotlin
transportClient.start(config.host, config.port, config.tls)
```
(was `transportClient.start(config.host, config.port, config.deviceId)`, line 69 — `config.deviceId`
no longer exists as of Task 6.1, and `start()` no longer takes a device id as of Task 6.2)

```kotlin
if (newConfig.host != config.host || newConfig.port != config.port || newConfig.tls != config.tls) {
    transportClient.start(newConfig.host, newConfig.port, newConfig.tls)
}
```
(was the `if (newConfig.host != config.host || newConfig.port != config.port)` block, lines 81-83)

### Task 6.5 — `DeviceTransportClientTest` updates and additions

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/transport/DeviceTransportClientTest.kt` (modify)

Add a shared factory using one of the published device-id vectors as the mocked identity, so hello's
`device_id` is a real, independently-verifiable derived value rather than an arbitrary test string:
```kotlin
private const val TEST_PUBLIC_KEY_BASE64 =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaLYBkdZrrs2nrNpdnPkFSS4F00NwONoA5e6B4Q6pB/5Oaxi6NUPTW7pJ80l8L+0Aaxt1V/87nXFRb83soCH0Sw=="
private const val TEST_DEVICE_ID = "dt_1d5aaa900ef5ffb98ae91a05932113643681ebc96ed43bdd084195ce34a9b09e"

private fun newClient(
    dispatcher: StepDispatcher = stepDispatcherMock(),
    identityKeyStore: DeviceIdentityKeyStore =
        mockk {
            every { ensurePublicKeyBase64() } returns TEST_PUBLIC_KEY_BASE64
            every { signNonce(any()) } answers { "sig-for-${firstArg<String>()}" }
        },
    deviceInfoProvider: DeviceInfoProvider =
        mockk {
            every { androidVersion } returns 34
            every { deviceModel } returns "Google Pixel 8"
        },
    registrationClient: DeviceRegistrationClient =
        mockk {
            coEvery { register(any(), any(), any(), any()) } returns
                DeviceRegistrationResult.Success(TEST_DEVICE_ID, null)
        },
    connectorUrlSettings: ConnectorUrlSettings = mockk(relaxed = true),
): DeviceTransportClientImpl =
    DeviceTransportClientImpl(dispatcher, identityKeyStore, deviceInfoProvider, registrationClient, connectorUrlSettings)
```
Every existing test's `DeviceTransportClientImpl(stepDispatcherMock())` becomes `newClient()` (or
`newClient(dispatcher = ...)` where a specific dispatcher mock is asserted on), and every
`client.start("127.0.0.1", port, "device-1")` becomes `client.start("127.0.0.1", port, tls = false)`
(3 args — `deviceId` is gone from the signature entirely).

New tests:

| Test | Verifies | Setup |
|---|---|---|
| `hello carries the device id derived from the public key` | Fake server asserts decoded `Hello.deviceId == TEST_DEVICE_ID` | default mocks |
| `answers a challenge with the signed nonce before welcome` | Fake server sends `Challenge("nonce-1")` after hello, asserts the received `challenge_response.signature == "sig-for-nonce-1"`, then sends `Welcome` — status reaches `Connected` | default mocks |
| `hello carries android_version and device_model from DeviceInfoProvider` | Fake server asserts decoded `Hello.androidVersion == 34`, `Hello.deviceModel == "Google Pixel 8"` | default mocks |
| `registers once before the first session, not again after a reconnect` | Fake server closes without welcome on the first connection (forcing a reconnect), accepts normally on the second; `coVerify(exactly = 1) { registrationClient.register(any(), any(), any(), any()) }` after both | — |
| `a registration Success with a connector_url persists it` | `registrationClient` mock returns `Success(TEST_DEVICE_ID, "https://h/d/x/mcp")`; `coVerify { connectorUrlSettings.updateConnectorUrl("https://h/d/x/mcp") }` after `start()` | — |
| `a registration response with a mismatched device_id is not registered as successful` | `registrationClient` mock returns `Success("dt_wrong", null)`; `connectorUrlSettings.updateConnectorUrl` is never called; `registrationClient.register` is still called only once even across a forced reconnect (the mismatch latch stops retries same as success) | fake server never sends `welcome` (hello would be rejected anyway since the server never actually saw this bogus id registered) — assert via `coVerify(exactly = 1)` after two `runSession` iterations |
| `welcome's update-info fields are exposed via updateInfo` | Fake server sends `Welcome(true, 1, null, "2.0.0", "1.5.0", "https://h/apk")`; `client.updateInfo.value` matches | — |
| `regenerateSecret sends regenerate_secret and completes on secret_regenerated` | After `Connected`, fake server receives `regenerate_secret`, replies `SecretRegenerated("https://h/d/new/mcp")`; `client.regenerateSecret()` returns `true`; `coVerify { connectorUrlSettings.updateConnectorUrl("https://h/d/new/mcp") }` | `runBlocking` |
| `regenerateSecret times out when the server sends no reply` | After `Connected`, fake server receives `regenerate_secret` and never replies; `client.regenerateSecret()` returns `false` | wrap the assertion in `withTimeout(8.seconds)` (> the client's internal 5s), per this file's existing `withTimeout(15.seconds)` convention |
| `regenerateSecret returns false immediately when never connected` | Fresh `newClient()`, never `start()`ed; `client.regenerateSecret()` returns `false` | no `withTimeout` needed — must return promptly |
| `the connector URL never reaches Logger` | `mockkObject(Logger)` before `start()`, capture every `Logger.i`/`w`/`e`/`d` call's `message` argument (via `slot`/`mutableListOf` in the mocked answers) across a full run that registers (`connectorUrl` present), connects, and regenerates; assert none of the captured strings contain the literal connector URL substring `"https://h/d/x/mcp"` or `"https://h/d/new/mcp"`; `unmockkObject(Logger)` in a `finally`/`@AfterEach` | `mockkObject`/`unmockkObject` (MockK's object-mocking, already available — no new test dependency) |
| `tls = true attempts a TLS handshake, which fails against a plain (non-TLS) fake server` | `client.start("127.0.0.1", port, tls = true)` against this file's existing plain (non-TLS) Netty fake server never reaches `Connected` — status becomes `Rejected` (or stays `Connecting`/`Reconnecting`) within a timeout, since a real TLS `ClientHello` against a plain-HTTP server can't complete. This is the regression test for User Story 2/Task 6.2's `if (tls) url.protocol = URLProtocol.WSS` line: with `tls` ignored (the bug this plan starts by fixing), this exact call would instead connect normally against the same fake server, exactly like every other test in this file with `tls = false` | `withTimeout(15.seconds)`, matching this file's convention; assert `client.status.value` is never `Connected` throughout the wait, then `client.stop()` |

### Definition of Done

- [ ] **Manual QA** (Android-Keystore code the JVM suite can't exercise): install the debug APK on
      the persistent redroid device or a real phone, point Remote Control at a real
      `droidthumb-server` (staging, TLS on), enable it, and confirm via
      `deploy/admin-active-devices.sh staging` or the server's `device_connected` log line that the
      handshake (challenge included) completes and the logged `device_id` matches what
      `deriveDeviceId` computes from the same key.

---

## User story 7 — Transport UI: TLS toggle, connector URL, copy, regenerate

Why: android#5/server#16 both require surfacing this. Per direct instruction, kept minimal:
connection status (already exists), the connector URL with copy and regenerate buttons — no update
banner, no onboarding flow (both explicitly out of scope, see the top of this plan).

Acceptance criteria:
- [ ] TLS toggle sits next to host/port, editable only while stopped (same rule as host/port today)
      — needed for the feature to reach a real deployment at all, per User Story 2.
- [ ] The connector URL section is shown only once one exists (`connectorUrl != null`); has a copy
      button and a "Regenerate" action gated behind a confirmation dialog warning that the old URL
      stops working immediately.
- [ ] Regenerate is only actionable while `TransportStatus.Connected` (mirrors
      `DeviceTransportClientImpl.regenerateSecret()`'s own guard).

### Task 7.1 — `TransportViewModel` additions

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/TransportViewModel.kt` (modify)

Constructor gains `private val transportClient: DeviceTransportClient` (already Hilt-bound).

```kotlin
enum class RegenerateSecretState { IDLE, IN_PROGRESS, SUCCEEDED, TIMED_OUT }
```
(nested in the class, alongside the other UI-only state it already declares)

Add fields/methods:
```kotlin
val connectorUrl: StateFlow<String?> =
    settingsRepository.connectorUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

private val _tlsInput = MutableStateFlow(false)
val tlsInput: StateFlow<Boolean> = _tlsInput.asStateFlow()

private val _regenerateState = MutableStateFlow(RegenerateSecretState.IDLE)
val regenerateState: StateFlow<RegenerateSecretState> = _regenerateState.asStateFlow()

fun updateTls(tls: Boolean) {
    _tlsInput.value = tls
    viewModelScope.launch(ioDispatcher) { settingsRepository.updateTransportTls(tls) }
}

fun regenerateSecret() {
    _regenerateState.value = RegenerateSecretState.IN_PROGRESS
    viewModelScope.launch(ioDispatcher) {
        val succeeded = transportClient.regenerateSecret()
        _regenerateState.value = if (succeeded) RegenerateSecretState.SUCCEEDED else RegenerateSecretState.TIMED_OUT
    }
}
```
In the existing `init { viewModelScope.launch { transportConfig.collect { config -> ... } } }` block,
add `_tlsInput.value = config.tls` alongside the existing `_hostInput`/`_portInput` assignments.

### Task 7.2 — `TransportStatusCard` additions

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/TransportStatusCard.kt` (modify)

`TransportStatusCard`'s signature gains:
```kotlin
tls: Boolean,
onTlsChange: (Boolean) -> Unit,
connectorUrl: String?,
regenerateState: TransportViewModel.RegenerateSecretState,
onRegenerateClick: () -> Unit,
```
`TransportAddressFields` gains `tls: Boolean, onTlsChange: (Boolean) -> Unit` and renders a labeled
`Switch` in its `Row`, after the port field:
```kotlin
Spacer(modifier = Modifier.width(8.dp))
Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text("TLS", style = MaterialTheme.typography.bodySmall)
    Switch(checked = tls, onCheckedChange = onTlsChange, enabled = fieldsEnabled)
}
```
`TransportStatusCard`'s own body must pass the two new arguments to its now-changed call:
```kotlin
TransportAddressFields(host, port, portError, onHostChange, onPortChange, tls, onTlsChange, fieldsEnabled = !enabled)
```
(replaces the current `TransportAddressFields(host, port, portError, onHostChange, onPortChange,
fieldsEnabled = !enabled)` call inside `TransportStatusCard`'s `Column` — without this the file
doesn't compile, since the function signature above just gained two required parameters)

After `TransportAddressFields(...)` in the card body, add:
```kotlin
if (connectorUrl != null) {
    Spacer(modifier = Modifier.height(12.dp))
    ConnectorUrlSection(
        connectorUrl = connectorUrl,
        canRegenerate = status is TransportStatus.Connected,
        regenerateState = regenerateState,
        onRegenerateClick = onRegenerateClick,
    )
}
```

New private composable in the same file:
```kotlin
@Composable
private fun ConnectorUrlSection(
    connectorUrl: String,
    canRegenerate: Boolean,
    regenerateState: TransportViewModel.RegenerateSecretState,
    onRegenerateClick: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("Connector URL", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Add this as a connector in Claude, ChatGPT, or your own MCP-compatible agent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(connectorUrl, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { clipboardManager.setText(AnnotatedString(connectorUrl)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy connector URL")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showConfirm = true }, enabled = canRegenerate) {
                Text("Regenerate")
            }
            when (regenerateState) {
                TransportViewModel.RegenerateSecretState.IN_PROGRESS -> Text("Regenerating…", style = MaterialTheme.typography.bodySmall)
                TransportViewModel.RegenerateSecretState.SUCCEEDED -> Text("Done", style = MaterialTheme.typography.bodySmall)
                TransportViewModel.RegenerateSecretState.TIMED_OUT ->
                    Text(
                        "No response — try again later",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                TransportViewModel.RegenerateSecretState.IDLE -> {}
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Regenerate connector URL?") },
            text = {
                Text(
                    "The current URL stops working immediately. Anything using it (Claude, ChatGPT, " +
                        "your own agent) will need the new one.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onRegenerateClick()
                    },
                ) { Text("Regenerate") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } },
        )
    }
}
```
Add imports: `androidx.compose.material3.AlertDialog`, `androidx.compose.material3.IconButton`,
`androidx.compose.material3.Icon`, `androidx.compose.material.icons.filled.ContentCopy`,
`androidx.compose.runtime.remember`, `androidx.compose.runtime.mutableStateOf`,
`androidx.compose.ui.platform.LocalClipboardManager`, `androidx.compose.ui.text.AnnotatedString`.

Update the existing `TransportStatusCardStoppedPreview` call site with the 5 new parameters
(`tls = false, onTlsChange = {}, connectorUrl = null, regenerateState =
TransportViewModel.RegenerateSecretState.IDLE, onRegenerateClick = {}`), and its existing hardcoded
`port = "4001"` literal to `port = "4000"`, matching Task 2.1's new `TransportConfig.DEFAULT_PORT`.

### Task 7.3 — `ServerScreen` wiring

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/ServerScreen.kt` (modify)

```kotlin
val connectorUrl by transportViewModel.connectorUrl.collectAsStateWithLifecycle()
val tlsInput by transportViewModel.tlsInput.collectAsStateWithLifecycle()
val regenerateState by transportViewModel.regenerateState.collectAsStateWithLifecycle()
```
(alongside the existing `transportViewModel`-derived `collectAsStateWithLifecycle()` calls)

`TransportStatusCard(...)` call gains:
```kotlin
tls = tlsInput,
onTlsChange = transportViewModel::updateTls,
connectorUrl = connectorUrl,
regenerateState = regenerateState,
onRegenerateClick = transportViewModel::regenerateSecret,
```

### Task 7.4 — Tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/TransportViewModelTest.kt` (create)

**Setup**: MockK `SettingsRepository` (relaxed), MockK `DeviceTransportClient`; direct construction
(no Hilt) with a test `CoroutineDispatcher` for `ioDispatcher`.

| Test | Verifies |
|---|---|
| `updateTls persists to repository and updates tlsInput` | `viewModel.updateTls(true)` → `tlsInput.value == true`, `coVerify { settingsRepository.updateTransportTls(true) }` |
| `connectorUrl reflects the repository's flow` | `settingsRepository.connectorUrl` emits a URL → `viewModel.connectorUrl.value` matches |
| `regenerateSecret goes IN_PROGRESS then SUCCEEDED on true` | `coEvery { transportClient.regenerateSecret() } returns true`; state transitions `IDLE -> IN_PROGRESS -> SUCCEEDED` |
| `regenerateSecret goes IN_PROGRESS then TIMED_OUT on false` | `coEvery { transportClient.regenerateSecret() } returns false`; state ends `TIMED_OUT` |

### Definition of Done

- [ ] **Manual QA**: on the redroid debug device or a real phone, confirm the TLS switch is disabled
      while connected, the connector URL section appears only after registration, copy works, and
      the regenerate confirmation dialog + resulting URL update work end to end against staging.

---

## User story 8 — Event Channel: derive its endpoint from the connector URL, drop manual config

Why: server#16 item 6 ("Event Channel: POST notifications to `<origin>/d/<secret>/events`"), resolved
with the user in the prior planning round as: auto-derive from the connector URL and remove the
manual endpoint URL/auth-token fields entirely — the secret path is now the whole credential (D-29),
so a separate Bearer token is redundant. Unchanged from plan 68's already-reviewed design for this
part (nothing about it depends on device-id derivation or the listener count).

Acceptance criteria:
- [ ] `EventChannelConfig` no longer has `endpointUrl`/`authToken`.
- [ ] The Event Channel refuses to start (same "can't start" logging/UI path as today's blank-endpoint
      case) when there is no connector URL yet, and otherwise POSTs to `<connector origin>/events`
      with no `Authorization` header.
- [ ] Boot auto-start requires a connector URL, not a non-blank endpoint string.
- [ ] The manual "Endpoint URL"/"Auth Token" fields are removed from `ChannelSettingsScreen`.

### Task 8.1 — `EventChannelConfig` loses `endpointUrl`/`authToken`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/EventChannelConfig.kt` (modify)

```kotlin
@Serializable
data class EventChannelConfig(
    val enabled: Boolean = false,
    val notifications: NotificationChannelConfig = NotificationChannelConfig(),
) {
    companion object {
        fun fromJson(json: String): EventChannelConfig = eventChannelJson.decodeFromString(serializer(), json)

        fun fromJsonOrDefault(json: String): EventChannelConfig =
            try {
                fromJson(json)
            } catch (_: Exception) {
                EventChannelConfig()
            }
    }

    fun toJson(): String = eventChannelJson.encodeToString(serializer(), this)
}
```
(drops `endpointUrl`, `authToken`, `DEFAULT_ENDPOINT_URL`; `eventChannelJson`'s existing
`ignoreUnknownKeys = true` makes a pre-existing persisted config with a leftover `endpointUrl`/
`authToken` key decode cleanly — those keys are just ignored)

### Task 8.1b — `ServerScreen`'s "channel not configured" gate

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/ServerScreen.kt` (modify)

The real file gates `EventChannelStatusCard`'s start button on `channelConfig.endpointUrl.isBlank()`
— that field no longer exists after Task 8.1. `ServerScreen` already collects `connectorUrl` from
`transportViewModel` (Task 7.3), so reuse it:
```kotlin
onStartClick = {
    if (connectorUrl == null) {
        showChannelNotConfiguredDialog = true
    } else {
        channelViewModel.startChannel()
    }
},
```
(replaces the current `if (channelConfig.endpointUrl.isBlank())` condition — lands after Task 7.3
introduces the `connectorUrl` local in this file, which it does: User Story 7 precedes User Story 8)

**File**: `app/src/main/res/values/strings.xml` (modify)

The dialog text still talks about a manually-configured endpoint:
```xml
<string name="channel_not_configured_dialog_title">Not registered with a server yet</string>
<string name="channel_not_configured_dialog_body">Set up the connector on the Server tab before starting the event channel.</string>
```
(replaces the current `channel_not_configured_dialog_title`/`_body` values; `_ok` is unchanged)

### Task 8.2 — `EventChannelSettings`/`Impl` lose the endpoint/token methods

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/EventChannelSettings.kt` (modify)

Remove `updateEventChannelEndpointUrl`, `updateEventChannelAuthToken`, `generateNewEventChannelAuthToken`,
`validateEndpointUrl` from the interface. The file's `@Suppress("TooManyFunctions")` drops from 10
methods to 6 — remove the annotation if detekt's `TooManyFunctions` threshold no longer flags the
interface without it.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/EventChannelSettingsImpl.kt` (modify)

Remove the four corresponding method bodies and the now-unused `import java.net.URL` /
`import java.util.UUID`. The class also carries `@Suppress("TooManyFunctions")` — remove it too if
detekt no longer flags the class without it, for the same reason.

### Task 8.3 — `eventsUrlFromConnectorUrl` and `EventChannelService`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventChannelService.kt` (modify)

```kotlin
private fun handleStart() {
    createNotificationChannel()
    val notification = buildForegroundNotification()
    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

    serviceScope.launch {
        val config = settingsRepository.getEventChannelConfig()
        val connectorUrl = settingsRepository.getConnectorUrl()
        if (connectorUrl == null) {
            Logger.e(TAG, "Cannot start: no connector URL yet")
            serverLogRepository.log(ServerLogEntry.Type.CHANNEL, CHANNEL_START_FAILED_LOG_MESSAGE)
            stopSelf()
            return@launch
        }

        val eventsUrl = eventsUrlFromConnectorUrl(connectorUrl)
        eventDispatcher.start(eventsUrl, authToken = "")
        startLogged = true
        // channelStartedLogMessage takes the HOST only, never the full URL: eventsUrl carries the
        // same per-device secret as the connector URL (.../d/<secret>/events) and this log is
        // persisted to disk and rendered in the app's own Logs screen (ServerLogRepository) — never
        // log the secret path itself, at any level.
        serverLogRepository.log(ServerLogEntry.Type.CHANNEL, channelStartedLogMessage(Uri.parse(eventsUrl).host ?: "unknown host"))

        // Immediate health check on start
        eventDispatcher.healthCheck()

        serviceScope.launch {
            eventDispatcher.connectionStatus.collect { _serviceStatus.value = it }
        }

        // Periodic health check every 30 seconds
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(HEALTH_CHECK_INTERVAL_MS)
                eventDispatcher.healthCheck()
            }
        }

        startListeners(config)

        settingsRepository.eventChannelConfig.collect { newConfig ->
            if (!newConfig.enabled) {
                handleStop()
                return@collect
            }
            reconfigureListeners(newConfig)
        }
    }
}
```
(replaces the current `handleStart()`; `startListeners`/`reconfigureListeners`/`handleStop`/etc.
unchanged)

Add `import android.net.Uri`.

The existing `internal fun channelStartedLogMessage(endpointUrl: String): String = "Event channel
started (endpoint: $endpointUrl)"` took a full URL when the endpoint was a manually-typed, non-secret
value; now its only caller passes a bare host (never the secret path), so rename the parameter to
match what it actually is:
```kotlin
internal fun channelStartedLogMessage(host: String): String = "Event channel started (host: $host)"

/** `https://host/d/<secret>/mcp` -> `https://host/d/<secret>/events` — same secret path, sibling
 *  route (server#16 item 6). */
internal fun eventsUrlFromConnectorUrl(connectorUrl: String): String =
    "${connectorUrl.substringBeforeLast('/')}/events"
```
(replaces the current `channelStartedLogMessage`; `eventsUrlFromConnectorUrl` is new)

Update `CHANNEL_START_FAILED_LOG_MESSAGE`'s text from `"Event channel failed to start: endpoint URL
is empty"` to `"Event channel failed to start: no connector URL yet"`.

### Task 8.4 — `EventChannelBootReceiver`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventChannelBootReceiver.kt` (modify)

```kotlin
withTimeout(SETTINGS_READ_TIMEOUT_MS) {
    val channelConfig = settingsRepository.getEventChannelConfig()
    val hasConnectorUrl = settingsRepository.getConnectorUrl() != null
    if (shouldAutoStart(channelConfig, hasConnectorUrl)) {
        val channelIntent =
            Intent(context, EventChannelService::class.java).apply {
                action = EventChannelService.ACTION_START
            }
        context.startForegroundService(channelIntent)
        Log.i(TAG, "Event channel auto-started on boot")
    }
}
```
(replaces the body of the existing `withTimeout` block)

```kotlin
/** The channel auto-starts on boot only when it is enabled and has a connector URL to send to. */
internal fun shouldAutoStart(
    config: EventChannelConfig,
    hasConnectorUrl: Boolean,
): Boolean = config.enabled && hasConnectorUrl
```
(replaces the current `shouldAutoStart`)

### Task 8.5 — `ChannelViewModel`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/ChannelViewModel.kt` (modify)

Remove `_endpointUrlInput`/`endpointUrlInput`, `_endpointUrlError`/`endpointUrlError`,
`_authTokenInput`/`authTokenInput`, `updateEndpointUrl`, `updateAuthToken`, `generateNewAuthToken`,
and the two corresponding lines in the `init` block's `eventChannelConfig.collect { config -> ... }`.

### Task 8.6 — `ChannelSettingsScreen`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/ChannelSettingsScreen.kt` (modify)

Remove the two `OutlinedTextField` `item {}` blocks for "Endpoint URL" and "Auth Token" and the
`tokenVisible`/`clipboardManager` locals that only served the Auth Token field, along with every
import that becomes unused once they're gone: `endpointUrlInput`/`endpointUrlError`/`authTokenInput`
(ViewModel-collected state, removed in Task 8.5), `KeyboardOptions`, `KeyboardType` (used only by the
Auth Token field's `keyboardType = KeyboardType.Password`), `PasswordVisualTransformation`,
`VisualTransformation`, the `Visibility`/`VisibilityOff`/`ContentCopy`/`Refresh` icons (the last used
only by the Auth Token field's "Generate new" button), `rememberSaveable`/`mutableStateOf`/`setValue`
(used only to declare `tokenVisible`), `LocalClipboardManager`, `AnnotatedString`. Replace with one
info row, inserted before the "Auto-start at boot" `item`:
```kotlin
item {
    Text(
        "Events are sent to your connector URL automatically — see the Server tab.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
```
Add `import androidx.compose.material3.MaterialTheme`.

### Task 8.7 — Test updates

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/EventChannelConfigTest.kt` (modify)

Remove the `endpointUrl`/`authToken` default-value assertions and the endpoint-specific
serialization-round-trip test. Rename/adapt the legacy-JSON test to assert forward-compat instead:

| Test | Verifies |
|---|---|
| `decoding a legacy config JSON with a leftover endpointUrl key ignores it` | `EventChannelConfig.fromJson("""{"enabled":true,"endpointUrl":"http://old","notifications":{...}}""")` decodes successfully with `enabled == true`, no error |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt` (modify)

Task 4.4 already added the 3-arg constructor/`connectorUrlSettings` mock, but its own `endpointUrl =
"..."` argument in `eventChannelConfig is the slice's flow` was still valid at that point in the plan
(`EventChannelConfig.endpointUrl` isn't removed until this story's Task 8.1) — it only breaks now:
```kotlin
@Test
fun `eventChannelConfig is the slice's flow`() =
    runTest {
        val config = EventChannelConfig(enabled = true)
        every { eventChannelSettings.eventChannelConfig } returns flowOf(config)

        assertEquals(config, repository.eventChannelConfig.first())
    }
```
(replaces the current test — drops `endpointUrl = "http://localhost:9090"` from the `EventChannelConfig(...)`
construction; nothing else about this test changes)

This task also removes the `updateEventChannelEndpointUrl` line and its `coVerify` from `updates
delegate to the slice`, keeping that test's `updateNotificationChannelEnabled` line and `coVerify`
(still valid, unrelated coverage):
```kotlin
@Test
fun `updates delegate to the slice`() =
    runTest {
        repository.updateNotificationChannelEnabled(true)

        coVerify { eventChannelSettings.updateNotificationChannelEnabled(true) }
    }
```

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryLoggingTest.kt` (modify)

Remove the tests built around `updateEventChannelEndpointUrl` — the burst-coalescing behavior they
exercise stays covered by this file's equivalent tests for other settings (e.g. `updateTransportHost`).
(The constructor fix for this file's `setUp()` is Task 4.4's, already applied by this point.)

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventChannelBootReceiverTest.kt` (modify)

Update all three `shouldAutoStart(EventChannelConfig(...))` calls to the new two-argument form, e.g.
`shouldAutoStart(EventChannelConfig(enabled = true), hasConnectorUrl = true)`.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventChannelServiceTest.kt` (modify)

This file's existing tests assert boolean combinations of `EventChannelConfig.endpointUrl`/
`authToken` directly (a pure-model test, not exercising the real service) — remove that nested test
class entirely (the "startable shape" concept moves to `connectorUrl != null`, not a field on
`EventChannelConfig`) and replace with:

| Test | Verifies |
|---|---|
| `eventsUrlFromConnectorUrl replaces the last path segment` | `eventsUrlFromConnectorUrl("https://host/d/dtk_x/mcp") == "https://host/d/dtk_x/events"` |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/ChannelViewModelTest.kt` (modify)

Remove the `AuthToken` and `EndpointUrl` nested test classes entirely.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/channel/EventChannelLogMessagesTest.kt` (modify)

Both tests here assert directly on the two constants Task 8.3 changes:
```kotlin
@Test
fun `started message contains the host`() {
    val message = channelStartedLogMessage("host:9090")
    assertTrue(message.contains("host:9090"))
}

@Test
fun `stopped and failed-start messages are the shared constants`() {
    assertEquals("Event channel stopped", CHANNEL_STOPPED_LOG_MESSAGE)
    assertEquals("Event channel failed to start: no connector URL yet", CHANNEL_START_FAILED_LOG_MESSAGE)
}
```
(replaces both existing tests)

### Definition of Done

- [ ] All updated/new tests pass; no reference to `EventChannelConfig.endpointUrl`/`authToken`
      remains anywhere in `app/src/main` or `app/src/test`.
- [ ] **Manual QA**: on the redroid debug device or a real phone, register with staging, confirm the
      Event Channel starts and a real notification event reaches the server's `/d/<secret>/events`,
      and that it refuses to start (with the new log message) before a connector URL exists.

---

## User story 9 — Redroid compatibility script (plan 03 milestone 2's own item, never built)

Why: plan 03 §2 calls for "a redroid regression script: runs the last N released APKs... against
staging, one at a time" — specified against GitHub Releases (milestone 5), which don't exist yet.
Direct instruction: build the same mechanism parametrized on a **list of local APK paths** instead,
so it's usable today and trivially extended to a Releases-driven wrapper once milestone 5 exists.

**Precondition, stated plainly (not solved by this script):** the redroid device must already have
Remote Control enabled and pointed at the target server (host/port/TLS set once via the UI — the
existing `docs/debug-device.md` setup). `adb install -r -d` (replace, allow downgrade) preserves app
data across a reinstall of the same package/signing key, which is exactly what this script relies on:
the Keystore identity and the transport settings persist across every APK in the list, so the script
only needs to cycle installs and observe the resulting connection — not re-drive onboarding UI for
each one. This also means every APK in one run shares the same `device_id` (the same physical
Keystore key persists across reinstalls, matching what a real phone upgrading through app versions
does) — the connector URL from the first successful registration stays valid for the rest of the run.

Acceptance criteria:
- [ ] Given a target (`staging`/`production`/an explicit `host:port`) and a list of local APK paths,
      the script installs each in turn, launches it, and reports pass/fail based on whether the
      app's own logcat shows a completed handshake within a timeout.
- [ ] Documented in `docs/debug-device.md`, per plan 03 §2's own instruction.

### Task 9.1 — `scripts/redroid-compat.sh`

Detection depends on Task 6.2's `Logger.i(TAG, "Connected: protocol_version=...")` line in the
`is Welcome ->` branch (already part of that task, not a separate action here) — this script greps
for it in `adb logcat`.

**File**: `scripts/redroid-compat.sh` (create)

```bash
#!/usr/bin/env bash
# Installs a list of APKs on the connected redroid device one at a time and checks each one
# completes the device-identity handshake against a target server. See docs/debug-device.md for
# the one-time setup this depends on (Remote Control already enabled and pointed at the target).
#
# Usage: scripts/redroid-compat.sh <staging|production|host:port> <apk-path> [apk-path ...]
set -euo pipefail

PKG="uk.co.drhconsulting.droidthumb.debug"
ACTIVITY_CATEGORY="android.intent.category.LAUNCHER"
CONNECT_TIMEOUT_SECONDS=15

if [ "$#" -lt 2 ]; then
  echo "Usage: $0 <staging|production|host:port> <apk-path> [apk-path ...]" >&2
  exit 1
fi

target="$1"
shift
apks=("$@")

case "$target" in
  staging) label="staging.droidthumb.com" ;;
  production) label="mcp.droidthumb.com" ;;
  *) label="$target" ;;
esac

echo "Target: $label (this script does not configure the app's host/port/TLS — see docs/debug-device.md)"
echo

pass=0
fail=0

for apk in "${apks[@]}"; do
  if [ ! -f "$apk" ]; then
    echo "SKIP: $apk (not found)"
    fail=$((fail + 1))
    continue
  fi

  version=$(aapt dump badging "$apk" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
  version=${version:-unknown}
  echo "=== $apk (apk_version=$version) ==="

  if ! adb install -r -d "$apk" >/tmp/redroid-compat-install.log 2>&1; then
    echo "  FAIL — install failed:"
    sed 's/^/    /' /tmp/redroid-compat-install.log
    fail=$((fail + 1))
    continue
  fi

  adb shell am force-stop "$PKG"
  adb logcat -c
  adb shell monkey -p "$PKG" -c "$ACTIVITY_CATEGORY" 1 >/dev/null 2>&1

  echo "  waiting up to ${CONNECT_TIMEOUT_SECONDS}s for the handshake..."
  connected=0
  for _ in $(seq 1 "$CONNECT_TIMEOUT_SECONDS"); do
    if adb logcat -d -s "MCP:DeviceTransport:V" 2>/dev/null | grep -q "Connected: protocol_version"; then
      connected=1
      break
    fi
    sleep 1
  done

  if [ "$connected" -eq 1 ]; then
    line=$(adb logcat -d -s "MCP:DeviceTransport:V" 2>/dev/null | grep "Connected: protocol_version" | tail -1)
    echo "  PASS — $line"
    pass=$((pass + 1))
  else
    echo "  FAIL — no handshake within ${CONNECT_TIMEOUT_SECONDS}s"
    echo "  Recent MCP:DeviceTransport log lines:"
    adb logcat -d -s "MCP:DeviceTransport:V" 2>/dev/null | tail -5 | sed 's/^/    /'
    fail=$((fail + 1))
  fi
  echo
done

echo "=== Summary: $pass passed, $fail failed (of $((pass + fail))) ==="
[ "$fail" -eq 0 ]
```
Mark executable (`chmod +x scripts/redroid-compat.sh`).

### Task 9.2 — Document it

**File**: `docs/debug-device.md` (modify)

Add a section (placement: after the existing manual-verification/handshake-check content this file
already has, matching its own structure):
```markdown
## Compatibility across APK versions

`scripts/redroid-compat.sh <staging|production|host:port> <apk-path> [apk-path ...]` installs each
APK in turn on the connected redroid device and checks it completes the device-identity handshake
against the target, reporting pass/fail per APK. It does **not** configure the app's server
host/port/TLS — do that once via the UI first (same setup as any other manual redroid check above);
`adb install -r -d` preserves app data (including the Keystore identity and that config) across every
APK in the list, so the same phone identity connects under every version tested, exactly like a real
phone upgrading through app versions.

Currently useful with exactly one APK per run in practice (there is no APK archive yet — plan 03
milestone 5's release pipeline is what will produce a real "last N released versions" list; until
then, pass whatever local builds you have, e.g. `app/build/outputs/apk/debug/app-debug.apk` after
`make build`).
```

### Definition of Done

- [ ] **Manual QA**: run it against at least one real APK (the current debug build) and staging,
      confirm a PASS.

---

## Final verification (after all user stories, before opening the PR)

Per android#5/server#16's own instructions:
1. `./gradlew ktlintCheck detekt` and `./gradlew :app:test jacocoTestReport
   jacocoTestCoverageVerification` clean; `shellcheck scripts/redroid-compat.sh` clean (or manually
   reviewed line-by-line if `shellcheck` isn't available in this environment).
2. `./gradlew assembleDebug`, install on the redroid debug device, point Remote Control at
   `staging.droidthumb.com` with TLS on, confirm connection via `deploy/admin-active-devices.sh
   staging` (run on the droplet) or the server's `device_connected` log line, and that the logged
   `device_id`, `app_version`, `android_version` and `device_model` are correct.
3. Read the connector URL from the app, add it as a connector in Claude (or ChatGPT), make a real
   tool call through it.
4. Tap Regenerate, confirm the app shows the new URL and the old one stops working (a call through
   the old URL now 404s per server#16's routing).
5. Run `scripts/redroid-compat.sh staging app/build/outputs/apk/debug/app-debug.apk`, confirm PASS.
