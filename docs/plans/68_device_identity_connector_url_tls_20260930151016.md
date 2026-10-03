<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Device identity, secret connector URL & TLS transport

> **SUPERSEDED by plan 69** (`69_device_identity_connector_url_redroid_compat_*.md`), before any task
> below was implemented. Written and reviewed against `droidthumb-protocol`#12/`droidthumb-server`#16
> while still unmerged; both merged afterward with changes this plan doesn't reflect — most
> significantly, `device_id` is now derived (`dt_` + hex SHA-256 of the public key), not a
> client-chosen random UUID, and the server collapsed to a single listener (no more separate
> device-relay port). Kept, unmodified beyond this note, as a record of that earlier design.

Scope: `droidthumb-android#5` (milestone 2 app work: hello/welcome fields, Keystore device-identity
handshake) and `droidthumb-server#16`/`droidthumb-protocol#12` (plan 03 milestone 3: per-phone secret
connector URL) — both PR bodies are the spec for this plan, quoted inline where a detail isn't
derivable from code. Protocol schemas are read from `droidthumb-protocol`'s
`claude/dreamy-cerf-hptzea` branch (PR#12, unmerged at plan-writing time) for the new
`challenge`/`challenge-response`/`device-registration`/`device-registration-response`/
`regenerate-secret`/`secret-regenerated` schemas, and from `main` for the already-merged
`hello`/`welcome` fields. `droidthumb-android` hand-writes wire types (no codegen dependency on
`droidthumb-protocol`), so nothing here is blocked on that PR merging.

**Finding, not in either PR body**: `DeviceTransportClientImpl.runSession()`'s
`client.webSocket(method, host, port, path, request, block)` call hardcodes the `ws://` scheme —
confirmed by disassembling `io.ktor.client.plugins.websocket.BuildersKt` (`ktor-client-core-jvm`
3.5.2): the `host`/`port`/`path` overload's internal lambda always calls
`url("ws", host, port, path, null)` before running the caller's own `request {}` lambda. There is no
implicit TLS from a port number. The app can therefore never reach `wss://staging.droidthumb.com` as
currently written, regardless of milestone 2/3 work — this is Task 1.1 below, ordered first because
everything else in this plan is unverifiable against a real deployment without it. `droidthumb-server`'s
`deploy/Caddyfile` (read from `main`) confirms `/device`, `/devices/register` and `/events` are all
proxied on the SAME public hostname as `/mcp`/`/d/*` (path-routed, not a separate port) — so in
production/staging the transport's `port` field must be the public HTTPS port (443), not the
container's raw `4001`; `4001` stays correct only for direct local/redroid testing.

**Out of scope**: milestone 4's update-prompt UI (this plan only exposes `updateInfo` via `StateFlow`,
per android#5 item 6, nothing renders it yet); accounts/de-registration (D-29 explicitly defers this;
the open "device_id squatting" gap named in server#16 stays open); dropping the now-redundant old
Caddy proxy routes (`/mcp`, `/v1/*`, bare `/events` — a `droidthumb-server` cleanup, not this repo);
flow signing, on-device flow cache/executor, redaction (already out of scope per plan 67).

## User story 1 — TLS support for the device transport

Why: the device's outbound WebSocket connection (`/device`) and its one-time HTTP registration call
(`/devices/register`) both need to reach the real hosted relay over `wss://`/`https://`, not just a
local plaintext test server — see the Finding above.

Acceptance criteria:
- [ ] `TransportConfig` has a `tls: Boolean` field (default `false`, matching the existing
      `port = 4001` default's local/redroid-testing target).
- [ ] `DeviceTransportClientImpl.start()` accepts `tls: Boolean` and sets `url.protocol =
      URLProtocol.WSS` on the WS request when true.
- [ ] `TransportService` reads and passes `config.tls`, and re-starts the client when it changes.
- [ ] The Settings UI exposes a TLS toggle next to host/port (Task 7.2, later — kept out of this task
      so this task is pure plumbing other tasks build on).

### Task 1.1 — `TransportConfig.tls`

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
```
(insert `tls` after `port`; rest of the file unchanged)

### Task 1.2 — `TransportSettings.updateTransportTls`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/TransportSettings.kt` (modify)

Add to the interface, after `updateTransportPort`:
```kotlin
/** Updates whether the transport connects over TLS (wss/https vs ws/http) — the same scheme
 *  covers both the WebSocket handshake and the one-time registration call, since both are the
 *  plain device-relay server behind Caddy's TLS termination on the public hostname (D-27). */
suspend fun updateTransportTls(tls: Boolean)
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/TransportSettingsImpl.kt` (modify)

Add, after `updateTransportPort`:
```kotlin
override suspend fun updateTransportTls(tls: Boolean) {
    val (old, new) = updateConfig { it.copy(tls = tls) }
    settingsChangeLogger.submit("transport_tls", old.tls.toString(), new.tls.toString()) { _, n ->
        "Remote control TLS ${if (n.toBoolean()) "enabled" else "disabled"}"
    }
}
```

### Task 1.3 — `DeviceTransportClient` speaks TLS

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/transport/DeviceTransportClient.kt` (modify)

Interface `start` gains a parameter:
```kotlin
fun start(
    host: String,
    port: Int,
    tls: Boolean,
    deviceId: String,
)
```

`DeviceTransportClientImpl.start`/`runSession` thread `tls` through (same positions as `host`/`port`);
in `runSession`'s `client.webSocket(...)` call:
```kotlin
request = {
    header(HttpHeaders.SecWebSocketProtocol, SUBPROTOCOL)
    if (tls) url.protocol = URLProtocol.WSS
},
```
Add `import io.ktor.http.URLProtocol`. (Full rewrite of this file happens in Task 6.2 once the
handshake/identity work lands on top of it — this task's diff is superseded there, listed separately
only so TLS can be reasoned about and reviewed on its own.)

### Task 1.4 — `TransportService` passes `tls`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/transport/TransportService.kt` (modify)

```kotlin
transportClient.start(config.host, config.port, config.tls, config.deviceId)
```
(was `transportClient.start(config.host, config.port, config.deviceId)`, line 69)

```kotlin
if (newConfig.host != config.host || newConfig.port != config.port || newConfig.tls != config.tls) {
    transportClient.start(newConfig.host, newConfig.port, newConfig.tls, newConfig.deviceId)
}
```
(was the `if (newConfig.host != config.host || newConfig.port != config.port)` block, lines 81-83)

### Task 1.5 — Update existing `DeviceTransportClientTest` call sites

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/transport/DeviceTransportClientTest.kt` (modify)

Every `client.start("127.0.0.1", port, "device-1")` (5 call sites) becomes
`client.start("127.0.0.1", port, tls = false, "device-1")` — the embedded Netty test server has no
TLS. (Constructor call sites are updated in Task 6.4 once the new dependencies exist.)

---

## User story 2 — Wire protocol: challenge handshake, welcome update-info fields, regenerate-secret

Why: hand-written Kotlin types matching `droidthumb-protocol`'s `challenge`/`challenge-response`
schemas (device-identity handshake, D-27), `regenerate-secret`/`secret-regenerated` schemas (D-29),
and the already-merged `welcome.latest_app_version`/`minimum_supported_app_version`/`download_url`
fields — none of which exist in `Messages.kt` today.

**Note on `Hello`**: `hello.android_version`/`device_model` are added in User Story 6 (Task 6.1), not
here, even though they're part of the same protocol surface — `Hello`'s only production call site
(`helloFor()` in `DeviceTransportClientImpl`) isn't touched until US6's rewrite, and making these
fields required here would leave the module non-compiling from this task until US6 lands, breaking
every intervening user story's "compiles"/"tests pass" Definition of Done. `Welcome`'s three new
fields carry no such risk (nullable, defaulted, and the app only ever decodes a `Welcome`, never
encodes/sends one), so they're safe to add now.

Acceptance criteria:
- [ ] `Challenge`, `ChallengeResponse`, `RegenerateSecret`, `SecretRegenerated` are `@Serializable`
      `WireMessage` subtypes, registered in `wireJson`.
- [ ] `Welcome` gains nullable `latestAppVersion`/`minimumSupportedAppVersion`/`downloadUrl`.
- [ ] Round-trip tests for every new type and for `Welcome`'s new fields.

### Task 2.1 — `Messages.kt` additions

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/wireprotocol/Messages.kt` (modify)

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
 *  with NO reply at all when over the limit — the caller must time out (~5s), not wait for an error
 *  (services/transport/DeviceTransportClient.kt task 6.1's `regenerateSecret`). */
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

`wireJson`'s `SerializersModule` gains the four new subtypes:
```kotlin
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
```

### Task 2.2 — Update existing tests, add new ones

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/wireprotocol/MessagesTest.kt` (modify)

New tests (the `Hello`-specific test updates for `androidVersion`/`deviceModel` are Task 6.1's, next
to where `Hello` actually gains those fields):

| Test | Verifies |
|---|---|
| `challenge round-trips` | `Challenge(nonce = "b25jZQ==")` encode → decode → equal, `is Challenge` |
| `challenge_response round-trips` | `ChallengeResponse(signature = "c2ln")` encode → decode → equal |
| `regenerate_secret encodes with only a type field` | `wireJson.encodeToString(WireMessage.serializer(), RegenerateSecret)` produces exactly `{"type":"regenerate_secret"}`; decode round-trips to `RegenerateSecret` |
| `secret_regenerated round-trips` | `SecretRegenerated(connectorUrl = "https://host/d/dtk_x/mcp")` encode → decode → equal |
| `welcome round-trips with update-info fields` | `Welcome(true, 1, null, "2.0.0", "1.5.0", "https://host/apk")` encode → decode → equal |

---

## User story 3 — Device identity Keystore, device-info provider, connector-secret crypto

Why: the EC (P-256) key pair that answers the connect-time challenge (D-27) and the AES key that
encrypts the connector URL at rest (server#16: "store it durably/securely... never logged, never in
backups") both need the Android Keystore — a real device API unavailable on the plain JVM this repo's
unit tests run on (no Robolectric; `EventChannelSettingsTest`'s own header comment already states
DataStore itself needs an instrumented test for the same reason). Both go behind interfaces so the
handshake/storage *logic* (US 6) stays unit-testable via MockK, matching this repo's existing
`ApiLevelProvider`/`DefaultApiLevelProvider` pattern for `Build.*`. `allowBackup="false"` is already
set for the whole app (`AndroidManifest.xml`), so no separate backup-exclusion rule is needed for the
"never in backups" requirement.

Acceptance criteria:
- [ ] `DeviceIdentityKeyStore` generates the EC key pair once (idempotent), exposes the base64 SPKI
      public key, and signs a base64 nonce as base64 ASN.1 DER ECDSA.
- [ ] `ConnectorSecretCrypto` encrypts/decrypts a string with an AES-256/GCM Keystore key.
- [ ] `DeviceInfoProvider` exposes `Build.VERSION.SDK_INT`/`Build.MODEL` (truncated to the schema's
      128-char `device_model` limit) behind an interface.
- [ ] All three `*Impl`s are bound in Hilt; none has a dedicated unit test (Android-Keystore-only
      code, same carve-out as `DefaultApiLevelProvider` — verified manually per Task 6's manual QA
      steps, not by the JVM unit test suite).

### Task 3.1 — `DeviceIdentityKeyStore`

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
 * Keystore and never exported. [ensurePublicKeyBase64] returns the base64 X.509 SPKI DER public key
 * sent to `POST /devices/register`; [signNonce] answers the connect-time `challenge` with an ASN.1
 * DER ECDSA signature (droidthumb-protocol/schema/challenge-response.schema.json) — the default
 * output shape of `Signature.getInstance("SHA256withECDSA")` against a Keystore-backed key, matching
 * the schema's own documented expectation.
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

### Task 3.2 — `ConnectorSecretCrypto`

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
 * AES-256/GCM encryption for the connector URL at rest (server#16: "store it durably/securely...
 * never logged, never in backups"). The key never leaves the Android Keystore. Ciphertext is stored
 * as `base64(iv):base64(bytes)`
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

@Singleton
class DefaultDeviceInfoProvider
    @Inject
    constructor() : DeviceInfoProvider {
        override val androidVersion: Int = Build.VERSION.SDK_INT
        override val deviceModel: String = Build.MODEL.take(MAX_DEVICE_MODEL_LENGTH)

        private companion object {
            // hello.schema.json device_model maxLength (droidthumb-protocol) — truncated defensively
            // so an unusually long Build.MODEL can never make hello schema-invalid.
            const val MAX_DEVICE_MODEL_LENGTH = 128
        }
    }
```

### Task 3.4 — Hilt bindings

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt` (modify)

Add imports for the three new interface/impl pairs, then in `ServiceModule`:
```kotlin
@Binds
@Singleton
abstract fun bindDeviceIdentityKeyStore(impl: DeviceIdentityKeyStoreImpl): DeviceIdentityKeyStore

@Binds
@Singleton
abstract fun bindConnectorSecretCrypto(impl: ConnectorSecretCryptoImpl): ConnectorSecretCrypto

@Binds
@Singleton
abstract fun bindDeviceInfoProvider(impl: DefaultDeviceInfoProvider): DeviceInfoProvider
```

### Definition of Done

- [ ] No unit test is expected for `DeviceIdentityKeyStoreImpl`/`ConnectorSecretCryptoImpl`
      (Android-Keystore-only; verified manually in Task 6's QA steps).

---

## User story 4 — Connector URL secure settings storage

Why: `ConnectorUrlSettings` is the durable, encrypted home for the connector URL (server#16), read by
the transport (to know whether registration is still needed) and by the Event Channel (US 8, to derive
its events endpoint).

Acceptance criteria:
- [ ] `ConnectorUrlSettings` extends `SettingsRepository` alongside the existing slices.
- [ ] The stored value is always the `ConnectorSecretCrypto`-encrypted form; a decrypt failure (e.g.
      corrupted/missing data) is treated as "no connector URL yet", not a crash.
- [ ] Every update is logged via `SettingsChangeLogger` without the real URL ever reaching it (unlike
      the existing `authToken` pattern, which passes the real secret into `submit()` and only relies
      on the render lambda to not use it — this passes fixed sentinel strings instead, so the secret
      never exists in the logger's in-memory coalescing state either).

### Task 4.1 — `ConnectorUrlSettings`

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

### Task 4.2 — Wire into `SettingsRepository`, `SettingsRepositoryImpl`, and Hilt

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt` (modify)

```kotlin
interface SettingsRepository :
    EventChannelSettings,
    TransportSettings,
    ConnectorUrlSettings
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt` (modify)

The real current file is a pure by-delegation class with a two-arg constructor
(`EventChannelSettings`, `TransportSettings`) — it must gain the third slice or it's left with an
unimplemented abstract member (`SettingsRepository` now also requires `ConnectorUrlSettings`):
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
(replaces the current two-arg constructor/delegation list)

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt` (modify)

In `RepositoryModule`:
```kotlin
@Binds
@Singleton
abstract fun bindConnectorUrlSettings(impl: ConnectorUrlSettingsImpl): ConnectorUrlSettings
```

### Task 4.3 — Fix the two existing `SettingsRepositoryImpl` construction sites, add a pass-through test

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt` (modify)

The real file constructs `SettingsRepositoryImpl(eventChannelSettings, transportSettings)` (2 args,
line 21) and one of its existing tests references the field `EventChannelConfig.endpointUrl`, which
no longer exists as of Task 8.1 — that field reference must be dropped from this test regardless of
this task, since `EventChannelConfig(enabled = true, endpointUrl = "...")` won't compile once US8
lands, but this task lands first, so remove it now rather than leaving a dangling reference for US8
to trip over:
```kotlin
private val eventChannelSettings = mockk<EventChannelSettings>(relaxed = true)
private val transportSettings = mockk<TransportSettings>(relaxed = true)
private val connectorUrlSettings = mockk<ConnectorUrlSettings>(relaxed = true)
private val repository = SettingsRepositoryImpl(eventChannelSettings, transportSettings, connectorUrlSettings)

@Test
fun `eventChannelConfig is the slice's flow`() =
    runTest {
        val config = EventChannelConfig(enabled = true)
        every { eventChannelSettings.eventChannelConfig } returns flowOf(config)

        assertEquals(config, repository.eventChannelConfig.first())
    }
```
(replaces the current 2-arg fields/constructor at lines 19-21, and drops `endpointUrl = "..."` from
the `eventChannelConfig is the slice's flow` test at line 26 — only that one argument, the rest of
that test and the unrelated `getEventChannelConfig delegates to the slice`/`updates delegate to the
slice` tests are untouched by this task)

Add a new test (mirroring `updates delegate to the slice`'s shape, kept separate rather than folded
into it, since it exercises a different mocked slice):
```kotlin
@Test
fun `updateConnectorUrl delegates to the slice`() =
    runTest {
        repository.updateConnectorUrl("https://h/d/x/mcp")

        coVerify { connectorUrlSettings.updateConnectorUrl("https://h/d/x/mcp") }
    }
```

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryLoggingTest.kt` (modify)

The real `setUp()` constructs `SettingsRepositoryImpl` from two REAL impls sharing a real (tempfile-backed)
`DataStore` — `ConnectorUrlSettingsImpl` needs a `ConnectorSecretCrypto` too, and the real
`ConnectorSecretCryptoImpl` touches the actual Android Keystore (`KeyStore.getInstance("AndroidKeyStore")`),
which doesn't exist on the plain JVM this test runs on and would throw. Use a trivial identity fake
instead — this test only cares about `SettingsChangeLogger` coalescing behavior, not real encryption:
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
- [ ] No dedicated `ConnectorUrlSettingsImplTest` (DataStore persistence needs an instrumented test,
      same precedent as `TransportSettingsImpl`/`EventChannelSettingsImpl`, neither of which has one).

---

## User story 5 — Device registration HTTP client

Why: `POST /devices/register` (device-registration.schema.json, D-27) must be called once before the
device's first WebSocket connection; server#16 requires handling its 429/`Retry-After` rate limit.

Acceptance criteria:
- [ ] `DeviceRegistrationClient.register()` returns a typed `Success(connectorUrl)` /
      `RateLimited(retryAfterSeconds)` / `Failed(message)` result — never throws.
- [ ] `Success.connectorUrl` is `null` when the call didn't create the device (idempotent
      re-registration), matching `device-registration-response.schema.json`'s optional field.
- [ ] Tested against a real embedded HTTP server (same pattern as `EventDispatcherImplTest`), not
      mocks — this is plain Ktor HTTP client code with no Android Keystore involved.

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
     *  a re-registration of an already-known device_id/key returns it as `null` — expected, not an
     *  error (device-registration-response.schema.json: `connector_url` is optional). */
    data class Success(
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
    /** `POST /devices/register` (device-registration.schema.json) — idempotent, made once before the
     *  device's first WebSocket connection (D-27). Same host/port/tls as the WS transport: both are
     *  the plain device-relay server, unaffected by milestone 3's `/d/<secret>/*` routing
     *  (droidthumb-server/deploy/Caddyfile: `/device`, `/devices/register`, `/events` all proxy to
     *  the device-relay port; only `/mcp`/`/v1/*` moved behind the secret). */
    suspend fun register(
        host: String,
        port: Int,
        tls: Boolean,
        deviceId: String,
        publicKeyBase64: String,
    ): DeviceRegistrationResult
}

@Serializable
private data class RegisterRequestBody(
    @SerialName("device_id") val deviceId: String,
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
            deviceId: String,
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
                            setBody(RegisterRequestBody(deviceId, publicKeyBase64))
                        }
                    when (response.status) {
                        HttpStatusCode.OK ->
                            DeviceRegistrationResult.Success(response.body<RegisterResponseBody>().connectorUrl)
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

**Setup**: embedded `Netty` server (`io.ktor.server.*`, test-classpath-only, same pattern as
`EventDispatcherImplTest`), one `post("/devices/register") { ... }` route per test controlling the
response; `client = DeviceRegistrationClientImpl()`; `tls = false`, `host = "127.0.0.1"`, `port =
<server's bound port>`.

| Test | Verifies |
|---|---|
| `200 with connector_url returns Success with that URL` | Route responds `{"device_id":"d1","connector_url":"https://h/d/x/mcp"}` → `Success("https://h/d/x/mcp")` |
| `200 with no connector_url returns Success(null)` | Route responds `{"device_id":"d1"}` → `Success(null)` — the idempotent-re-registration shape |
| `429 with Retry-After returns RateLimited with that value` | Route responds 429, header `Retry-After: 120` → `RateLimited(120)` |
| `429 with no Retry-After returns RateLimited(null)` | Route responds 429, no header → `RateLimited(null)` |
| `409 returns Failed with the status code` | Route responds 409 (key-mismatch shape) → `Failed("HTTP 409")` |
| `request body matches the wire shape` | Route captures the received JSON body; asserts `device_id`/`public_key` keys (snake_case) and values match the call's arguments |
| `unreachable server returns Failed` | `port` pointed at a closed port → `Failed(...)`, no exception thrown out of `register()` |

---

## User story 6 — Wire the handshake, registration, and regenerate-secret into `DeviceTransportClientImpl`

Why: this is where every prior user story's pieces meet the actual connection lifecycle — the app
cannot reach a device-relay server it hasn't registered with, cannot pass a challenge without signing
its nonce, and cannot recover a lost connector URL without sending `regenerate_secret` and handling the
"no reply means rate-limited" behavior (server#16).

Acceptance criteria:
- [ ] Registration happens once per `start()` call, not on every reconnect (rate-limit safety — see
      the class doc comment below for why).
- [ ] A `challenge` is answered with `ChallengeResponse(signNonce(nonce))` before `welcome` can ever
      arrive; the existing `Rejected`/backoff handling for a 4002/4003 close needs no changes.
- [ ] `hello.android_version`/`device_model` come from `DeviceInfoProvider`.
- [ ] `welcome`'s update-info fields are exposed via a new `updateInfo: StateFlow<UpdateInfo?>`.
- [ ] A `connector_url` from either registration or `secret_regenerated` is persisted via
      `ConnectorUrlSettings`.
- [ ] `regenerateSecret()`: sends `RegenerateSecret` only while actually `Connected`; returns `false`
      immediately (no wait) when not connected; otherwise awaits `secret_regenerated` up to 5s and
      returns whether it arrived in time. Accepted edge case: a `secret_regenerated` that arrives in
      the narrow window right as the 5s timeout fires can make this return `false` even though the
      new URL was still correctly persisted via `connectorUrlSettings.updateConnectorUrl(...)` in
      `runSession`'s own handler — a caller-visible false negative on an already-successful rotation,
      not a data-loss bug. Not worth the added complexity of closing that window given how rarely a
      manual, confirmation-gated regenerate action races a 5-second boundary.

### Task 6.1 — `Hello` gains `android_version`/`device_model`, wired immediately into `helloFor()`

Done as the first task of this user story, not User Story 2, because `helloFor()` (the only
production caller) is rewritten in the very next task (6.2) — no intervening user story ever sees a
non-compiling `Hello`.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/wireprotocol/Messages.kt` (modify)

```kotlin
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
    @SerialName("android_version") val androidVersion: Int,
    @SerialName("device_model") val deviceModel: String,
) : WireMessage
```
(replaces the current `Hello` — adds the last two fields; required/non-null because
`wireJson.encodeDefaults = true` would otherwise encode a nullable-with-null-default field as literal
JSON `null`, which `hello.schema.json` rejects for these non-nullable-typed fields — the exact bug
`MessagesTest`'s existing `hello encodes required fields even at their default value` regression test
already guards a different pair of fields against)

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/wireprotocol/MessagesTest.kt` (modify)

The two existing `Hello(...)` constructions (`hello round-trips`, `hello encodes required fields even
at their default value`) each need `androidVersion = 34, deviceModel = "Pixel 8"` added.

### Task 6.2 — Full `DeviceTransportClient.kt` rewrite

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/transport/DeviceTransportClient.kt` (modify — supersedes Task 1.3's smaller diff)

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.transport

import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.ConnectorUrlSettings
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceIdentityKeyStore
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceInfoProvider
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
 *  4's update-prompt UI; nothing reads this yet (android#5 item 6). */
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
        deviceId: String,
    )

    fun stop()

    /** Sends `regenerate_secret` on the current connection and awaits `secret_regenerated`, up to a
     *  5s timeout — the server sends NO reply at all when the per-device rate limit (10/hour) is
     *  exceeded, so a bounded wait is the only way to detect that (server#16). Returns `false`
     *  immediately, with no wait, when not currently `Connected`. */
    suspend fun regenerateSecret(): Boolean
}

/**
 * The M2/M3 outbound WebSocket client (design doc M2 mvp-handover §4 item 1; plan 03 milestone 3):
 * offers `droidthumb.v1`, sends `hello`, answers a `challenge` with the Keystore-signed nonce (D-27)
 * before `welcome` can arrive, dispatches inbound `step`s through [StepDispatcher], and carries
 * [RegenerateSecret]/[SecretRegenerated] once connected. Reconnects with capped exponential backoff
 * on any drop.
 *
 * Registration (`POST /devices/register`) happens once per [start] — not on every reconnect — via
 * [ensureRegistered]'s [registered] latch: re-registering is idempotent server-side but rate-limited
 * (30/hour/IP), and the reconnect loop can retry indefinitely, so repeating it on every attempt risks
 * exhausting that budget on ordinary reconnect churn. A registration that hasn't succeeded yet is
 * retried on the next loop iteration (same backoff schedule as the WS connect); one that has
 * succeeded is never retried again for the lifetime of this [start] call.
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

        override fun start(
            host: String,
            port: Int,
            tls: Boolean,
            deviceId: String,
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
                        ensureRegistered(host, port, tls, deviceId)
                        val welcomed = runSession(host, port, tls, deviceId)
                        attempt = if (welcomed) 0 else attempt + 1
                    }
                }
        }

        private suspend fun ensureRegistered(
            host: String,
            port: Int,
            tls: Boolean,
            deviceId: String,
        ) {
            if (registered.get()) return
            val publicKey = deviceIdentityKeyStore.ensurePublicKeyBase64()
            when (val result = registrationClient.register(host, port, tls, deviceId, publicKey)) {
                is DeviceRegistrationResult.Success -> {
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
            deviceId: String,
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
                        send(Frame.Text(wireJson.encodeToString(WireMessage.serializer(), helloFor(deviceId))))
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
                        // `incoming` completed — the server closed the connection. This is the ONLY
                        // place a 4000-4003 close is observable (Ktor's client WS doesn't throw for a
                        // normal close frame, it just ends the channel).
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
                outbox.trySend(RegenerateSecret)
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

        private fun helloFor(deviceId: String) =
            Hello(
                protocolVersion = 1,
                apkVersion = BuildConfig.VERSION_NAME,
                deviceId = deviceId,
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
bindings added in Tasks 3.4/4.2/5.2.

### Task 6.4 — `DeviceTransportClientTest` updates and additions

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/transport/DeviceTransportClientTest.kt` (modify)

Add a shared factory (replaces every bare `DeviceTransportClientImpl(stepDispatcherMock())` call):
```kotlin
private fun newClient(
    dispatcher: StepDispatcher = stepDispatcherMock(),
    identityKeyStore: DeviceIdentityKeyStore =
        mockk {
            every { ensurePublicKeyBase64() } returns "pub-key"
            every { signNonce(any()) } answers { "sig-for-${firstArg<String>()}" }
        },
    deviceInfoProvider: DeviceInfoProvider =
        mockk {
            every { androidVersion } returns 34
            every { deviceModel } returns "Pixel 8"
        },
    registrationClient: DeviceRegistrationClient =
        mockk {
            coEvery { register(any(), any(), any(), any(), any()) } returns DeviceRegistrationResult.Success(null)
        },
    connectorUrlSettings: ConnectorUrlSettings = mockk(relaxed = true),
): DeviceTransportClientImpl =
    DeviceTransportClientImpl(dispatcher, identityKeyStore, deviceInfoProvider, registrationClient, connectorUrlSettings)
```
Every existing test's `DeviceTransportClientImpl(stepDispatcherMock())` becomes `newClient()` (or
`newClient(dispatcher = ...)` where a specific dispatcher mock is asserted on), and every
`client.start("127.0.0.1", port, "device-1")` becomes `client.start("127.0.0.1", port, tls = false,
"device-1")` (supersedes Task 1.5, applied against the now-larger constructor).

New tests:

| Test | Verifies | Setup |
|---|---|---|
| `answers a challenge with the signed nonce before welcome` | Fake server sends `Challenge("nonce-1")` after hello, asserts the received `challenge_response.signature == "sig-for-nonce-1"`, then sends `Welcome` — status reaches `Connected` | default `identityKeyStore` mock |
| `hello carries android_version and device_model from DeviceInfoProvider` | Fake server asserts decoded `Hello.androidVersion == 34`, `Hello.deviceModel == "Pixel 8"` | default `deviceInfoProvider` mock |
| `registers once before the first session, not again after a reconnect` | Fake server closes without welcome on the first connection (forcing a reconnect), accepts normally on the second; `coVerify(exactly = 1) { registrationClient.register(any(), any(), any(), any(), any()) }` after both | — |
| `a registration Success with a connector_url persists it` | `registrationClient` mock returns `Success("https://h/d/x/mcp")`; `coVerify { connectorUrlSettings.updateConnectorUrl("https://h/d/x/mcp") }` after `start()` | — |
| `welcome's update-info fields are exposed via updateInfo` | Fake server sends `Welcome(true, 1, null, "2.0.0", "1.5.0", "https://h/apk")`; `client.updateInfo.value` matches | — |
| `regenerateSecret sends regenerate_secret and completes on secret_regenerated` | After `Connected`, fake server receives `regenerate_secret`, replies `SecretRegenerated("https://h/d/new/mcp")`; `client.regenerateSecret()` returns `true`; `coVerify { connectorUrlSettings.updateConnectorUrl("https://h/d/new/mcp") }` | `runBlocking` |
| `regenerateSecret times out when the server sends no reply` | After `Connected`, fake server receives `regenerate_secret` and never replies; `client.regenerateSecret()` returns `false` | wrap the assertion in `withTimeout(8.seconds)` (> the client's internal 5s) per this file's existing `withTimeout(15.seconds)` convention |
| `regenerateSecret returns false immediately when never connected` | Fresh `newClient()`, never `start()`ed; `client.regenerateSecret()` returns `false` | no `withTimeout` needed — must return promptly |

### Definition of Done

- [ ] **Manual QA** (Android-Keystore code the JVM suite can't exercise): install the debug APK on
      the persistent redroid device or a real phone, point Remote Control at a real
      `droidthumb-server` (staging), enable it, and confirm via `deploy/admin-active-devices.sh
      staging` or the server's `device_connected` log line that the handshake (challenge included)
      completes.

---

## User story 7 — Transport UI: TLS toggle, connector URL, regenerate

Why: android#5/server#16 both require surfacing this — a TLS toggle (US 1 has no UI yet), and the
connector URL with copy + a regenerate action (server#16's "What the Android app needs": display,
copy, regenerate with confirmation).

Acceptance criteria:
- [ ] TLS toggle sits next to host/port, editable only while stopped (same rule as host/port today).
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
(top-level in this file, or nested in the class — nested, matching this codebase's preference for
keeping small UI-only sealed/enum types close to their owner)

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
`Switch` in its `Row`, after the port field, plus a hint when TLS is on but `port` is still the
plaintext-local-dev default (4001) — a real, easy-to-hit misconfiguration per this plan's own intro
(production/staging need port 443 behind Caddy, not the container's raw device-relay port):
```kotlin
Spacer(modifier = Modifier.width(8.dp))
Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text("TLS", style = MaterialTheme.typography.bodySmall)
    Switch(checked = tls, onCheckedChange = onTlsChange, enabled = fieldsEnabled)
}
```
and, in `TransportAddressFields`'s body after the `Row`:
```kotlin
if (tls && port == TransportConfig.DEFAULT_PORT.toString()) {
    Text(
        "TLS is usually port 443 (Caddy), not $port — check with whoever set up the server.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
    )
}
```
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
                        "No response — try again",
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
`androidx.compose.ui.platform.LocalClipboardManager`, `androidx.compose.ui.text.AnnotatedString`,
`com.danielealbano.androidremotecontrolmcp.data.model.TransportConfig` (for `DEFAULT_PORT`, the
TLS/port hint above).

Update the existing `TransportStatusCardStoppedPreview` call site with the 5 new parameters
(`tls = false, onTlsChange = {}, connectorUrl = null, regenerateState =
TransportViewModel.RegenerateSecretState.IDLE, onRegenerateClick = {}`).

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

**Setup**: MockK `SettingsRepository` (relaxed), MockK `DeviceTransportClient`; standard
`HiltViewModel`-free direct construction (matches `ChannelViewModelTest`'s pattern) with a test
`CoroutineDispatcher` for `ioDispatcher`.

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

Why: server#16 item 5 ("point the Event Channel at `/d/<secret>/events`"), resolved with the user as:
auto-derive from the connector URL and remove the manual endpoint URL/auth-token fields entirely — the
secret path is now the whole credential (D-29), so a separate Bearer token is redundant, and a
turnkey per-phone URL shouldn't also require hand-typing a webhook target.

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
(lines 96-101) — that field no longer exists after Task 8.1. `ServerScreen` already collects
`connectorUrl` from `transportViewModel` (Task 7.3), so reuse it:
```kotlin
onStartClick = {
    if (connectorUrl == null) {
        showChannelNotConfiguredDialog = true
    } else {
        channelViewModel.startChannel()
    }
},
```
(replaces the current `if (channelConfig.endpointUrl.isBlank())` condition — this task must land
after Task 7.3 introduces the `connectorUrl` local in this file, which it does: User Story 7 precedes
User Story 8)

**File**: `app/src/main/res/values/strings.xml` (modify)

The dialog text still talks about a manually-configured endpoint (lines 68-69):
```xml
<string name="channel_not_configured_dialog_title">Not registered with a server yet</string>
<string name="channel_not_configured_dialog_body">Set up the connector on the Server tab before starting the event channel.</string>
```
(replaces the current `channel_not_configured_dialog_title`/`_body` values; `_ok` is unchanged)

### Task 8.2 — `EventChannelSettings`/`Impl` lose the endpoint/token methods

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/EventChannelSettings.kt` (modify)

Remove `updateEventChannelEndpointUrl`, `updateEventChannelAuthToken`, `generateNewEventChannelAuthToken`,
`validateEndpointUrl` from the interface. The file's `@Suppress("TooManyFunctions")` (line 14) drops
from 10 methods to 6 — remove the annotation if detekt's `TooManyFunctions` threshold no longer flags
the interface without it.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/EventChannelSettingsImpl.kt` (modify)

Remove the four corresponding method bodies and the now-unused `import java.net.URL` /
`import java.util.UUID`. The class also carries `@Suppress("TooManyFunctions")` (line 22, same
14-method count as the interface it implements) — remove it too if detekt no longer flags the class
without it, for the same reason.

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
        // log the secret path itself, at any level (server#16 / CLAUDE.md "never log secrets").
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
 *  route (droidthumb-server plan 03 milestone 3 item 5; deploy/Caddyfile routes both under `/d/*`). */
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
info row,
inserted before the "Auto-start at boot" `item`:
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

Task 4.3 already dropped the stale `endpointUrl = "..."` argument from `eventChannelConfig is the
slice's flow` and added the 3-arg constructor/`connectorUrlSettings` mock. This task only removes the
`updateEventChannelEndpointUrl` line and its `coVerify` from `updates delegate to the slice`, keeping
that test's `updateNotificationChannelEnabled` line and `coVerify` (still valid, unrelated coverage):
```kotlin
@Test
fun `updates delegate to the slice`() =
    runTest {
        repository.updateNotificationChannelEnabled(true)

        coVerify { eventChannelSettings.updateNotificationChannelEnabled(true) }
    }
```

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryLoggingTest.kt` (modify)

Remove the tests built around `updateEventChannelEndpointUrl` (~lines 70-105) — the burst-coalescing
behavior they exercise stays covered by this file's equivalent tests for other settings (e.g.
`updateTransportHost`). (The constructor fix for this file's `setUp()` is Task 4.3's, already applied
by this point.)

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

Remove the `AuthToken` and `EndpointUrl` nested test classes entirely (~lines 79-116).

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
(replaces both existing tests — the first no longer passes a full URL, matching Task 8.3's
host-only, never-log-the-secret change)

### Definition of Done

- [ ] All updated/new tests pass; no reference to `EventChannelConfig.endpointUrl`/`authToken`
      remains anywhere in `app/src/main` or `app/src/test`.
- [ ] **Manual QA**: on the redroid debug device or a real phone, register with staging, confirm the
      Event Channel starts and a real notification event reaches the server's `/d/<secret>/events`
      (server-side log or `admin`/DB check), and that it refuses to start (with the new log message)
      before a connector URL exists.

---

## Final verification (after all user stories, before opening the PR)

Per android#5/server#16's own instructions:
1. `./gradlew ktlintCheck detekt` and `./gradlew :app:test jacocoTestReport
   jacocoTestCoverageVerification` clean.
2. `./gradlew assembleDebug`, install on the redroid debug device, point Remote Control at
   `staging.droidthumb.com` with TLS on, confirm connection via `deploy/admin-active-devices.sh
   staging` (run on the droplet) or the server's `device_connected` log line.
3. Read the connector URL from the app, add it as a connector in Claude (or ChatGPT), make a real
   tool call through it.
4. Tap Regenerate, confirm the app shows the new URL and the old one stops working (a call through
   the old URL now 404s/401s per server#16's routing).
