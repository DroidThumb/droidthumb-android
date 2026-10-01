package com.danielealbano.androidremotecontrolmcp.wireprotocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessagesTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("protocol-examples/$name")) { "missing fixture: $name" }
            .bufferedReader()
            .readText()

    @Test
    fun `hello round-trips`() {
        val hello =
            Hello(
                protocolVersion = 1,
                apkVersion = "1.0.0",
                deviceId = "device-1",
                capabilities = emptyList(),
                mode = "live",
                flowManifest = emptyList(),
            )
        val encoded = wireJson.encodeToString(WireMessage.serializer(), hello)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(hello, decoded)
        assertTrue(decoded is Hello)
    }

    @Test
    fun `hello encodes required fields even at their default value`() {
        // Regression test: kotlinx.serialization omits a property equal to its default value
        // unless `encodeDefaults = true` is set, which would silently produce a hello missing
        // `capabilities`/`flow_manifest` whenever both are empty (the common case) — a shape
        // hello.schema.json's ajv validation rejects outright, since both are required. Found
        // against a real server (closed with 4000, "expected a valid hello first"), not caught by
        // this package's own round-trip tests before this one, since encode-then-decode of the
        // same Kotlin class can't detect a field that both sides simply never populated.
        val hello =
            Hello(
                protocolVersion = 1,
                apkVersion = "1.0.0",
                deviceId = "device-1",
                mode = "live",
            )
        val encoded = wireJson.encodeToString(WireMessage.serializer(), hello)
        assertTrue(encoded.contains("\"capabilities\""), "encoded hello missing capabilities: $encoded")
        assertTrue(encoded.contains("\"flow_manifest\""), "encoded hello missing flow_manifest: $encoded")
    }

    @Test
    fun `welcome round-trips with settings`() {
        val welcome =
            Welcome(
                accepted = true,
                protocolVersion = 1,
                settings = WelcomeSettings(defaultStepTimeoutMs = 30000L),
            )
        val encoded = wireJson.encodeToString(WireMessage.serializer(), welcome)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(welcome, decoded)
        assertTrue(decoded is Welcome)
    }

    @Test
    fun `step round-trips, params preserved`() {
        val step =
            Step(
                stepId = "s1",
                op = "tap",
                params =
                    buildJsonObject {
                        put(
                            "selector",
                            buildJsonObject {
                                put("resource_id", "com.whatsapp:id/entry")
                            },
                        )
                    },
            )
        val encoded = wireJson.encodeToString(WireMessage.serializer(), step)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(step, decoded)
        assertTrue(decoded is Step)
        assertEquals(step.params, (decoded as Step).params)
    }

    @Test
    fun `result round-trips`() {
        val result =
            StepResult(
                stepId = "s1",
                output = buildJsonObject { put("tree", "some tree text") },
            )
        val encoded = wireJson.encodeToString(WireMessage.serializer(), result)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(result, decoded)
        assertTrue(decoded is StepResult)
    }

    @Test
    fun `error round-trips`() {
        val error = StepError(stepId = "s1", code = "NodeNotFound", message = "no such node")
        val encoded = wireJson.encodeToString(WireMessage.serializer(), error)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(error, decoded)
        assertTrue(decoded is StepError)
    }

    @Test
    fun `decoding a payload with no type field fails`() {
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(WireMessage.serializer(), """{"step_id":"1"}""")
        }
    }

    @Test
    fun `decoding a payload with an unknown type fails`() {
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(WireMessage.serializer(), """{"type":"bogus","step_id":"1"}""")
        }
    }

    @Test
    fun `file-reference inline encodes with mime and data (kind added by the caller)`() {
        // StepDispatcher builds the wire {kind, mime, data} object by hand (see its readScreen()),
        // not through polymorphic encoding of FileReference.Inline — this only confirms the plain
        // fields serialize as expected, per Messages.kt's own doc on why `kind`-as-discriminator
        // is out of scope here.
        val inline = FileReference.Inline(mime = "image/jpeg", data = "YmFzZTY0")
        val encoded = Json.encodeToJsonElement(FileReference.Inline.serializer(), inline)
        assertEquals("image/jpeg", encoded.jsonObject["mime"]?.jsonPrimitive?.content)
        assertEquals("YmFzZTY0", encoded.jsonObject["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `challenge round-trips`() {
        val challenge = Challenge(nonce = "b25jZQ==")
        val encoded = wireJson.encodeToString(WireMessage.serializer(), challenge)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(challenge, decoded)
        assertTrue(decoded is Challenge)
    }

    @Test
    fun `challenge_response round-trips`() {
        val response = ChallengeResponse(signature = "c2ln")
        val encoded = wireJson.encodeToString(WireMessage.serializer(), response)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(response, decoded)
        assertTrue(decoded is ChallengeResponse)
    }

    @Test
    fun `regenerate_secret encodes with only a type field`() {
        val encoded = wireJson.encodeToString(WireMessage.serializer(), RegenerateSecret)
        assertEquals("""{"type":"regenerate_secret"}""", encoded)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(RegenerateSecret, decoded)
        assertTrue(decoded is RegenerateSecret)
    }

    @Test
    fun `secret_regenerated round-trips`() {
        val message = SecretRegenerated(connectorUrl = "https://host/d/dtk_x/mcp")
        val encoded = wireJson.encodeToString(WireMessage.serializer(), message)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(message, decoded)
        assertTrue(decoded is SecretRegenerated)
    }

    @Test
    fun `welcome round-trips with update-info fields`() {
        val welcome =
            Welcome(
                accepted = true,
                protocolVersion = 1,
                settings = null,
                latestAppVersion = "2.0.0",
                minimumSupportedAppVersion = "1.5.0",
                downloadUrl = "https://host/apk",
            )
        val encoded = wireJson.encodeToString(WireMessage.serializer(), welcome)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(welcome, decoded)
        assertTrue(decoded is Welcome)
    }

    @Test
    fun `result with a null output omits the output key, not an explicit null`() {
        // explicitNulls = false is wireJson-wide, not scoped to hello — this is the other place
        // this app actually ENCODES (not just decodes) a pre-existing nullable field. No
        // compatibility risk: result.schema.json's `output` property is `{}` (accepts anything,
        // including no key and including a literal null), so this is a behavior change but not a
        // regression.
        val result = StepResult(stepId = "s1", output = null)
        val encoded = wireJson.encodeToString(WireMessage.serializer(), result)
        assertTrue(!encoded.contains("\"output\""), "encoded result should omit output, got: $encoded")
    }

    @Test
    fun `decodes the protocol repo's hello valid fixture`() {
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), fixture("hello.valid.json"))
        check(decoded is Hello)
        assertEquals(1, decoded.protocolVersion)
        assertEquals("1.2.3", decoded.apkVersion)
        assertEquals(34, decoded.androidVersion)
        assertEquals("Google Pixel 8", decoded.deviceModel)
        assertEquals("pixel-8-abc123", decoded.deviceId)
        assertEquals(listOf("a11y_tree"), decoded.capabilities)
        assertEquals("live", decoded.mode)
        assertEquals(listOf(FlowManifestEntry("flow-1", 2)), decoded.flowManifest)
    }

    @Test
    fun `decodes the protocol repo's hello valid legacy fixture`() {
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), fixture("hello.valid.legacy.json"))
        check(decoded is Hello)
        assertEquals("1.0.0", decoded.apkVersion)
        assertEquals("pixel-8-abc123", decoded.deviceId)
        assertEquals(null, decoded.androidVersion)
        assertEquals(null, decoded.deviceModel)
        assertEquals(emptyList<String>(), decoded.capabilities)
        assertEquals(emptyList<FlowManifestEntry>(), decoded.flowManifest)
    }

    @Test
    fun `decodes the protocol repo's welcome valid fixture`() {
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), fixture("welcome.valid.json"))
        check(decoded is Welcome)
        assertEquals(true, decoded.accepted)
        assertEquals("1.3.0", decoded.latestAppVersion)
        assertEquals("1.0.0", decoded.minimumSupportedAppVersion)
        assertEquals("https://github.com/DroidThumb/droidthumb-android/releases/latest", decoded.downloadUrl)
        assertEquals(30000L, decoded.settings?.defaultStepTimeoutMs)
    }

    @Test
    fun `decodes the protocol repo's welcome valid legacy fixture`() {
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), fixture("welcome.valid.legacy.json"))
        check(decoded is Welcome)
        assertEquals(true, decoded.accepted)
        assertEquals(null, decoded.latestAppVersion)
        assertEquals(null, decoded.minimumSupportedAppVersion)
        assertEquals(null, decoded.downloadUrl)
    }

    @Test
    fun `decodes the protocol repo's regenerate-secret valid fixture`() {
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), fixture("regenerate-secret.valid.json"))
        assertTrue(decoded is RegenerateSecret)
    }

    @Test
    fun `decodes the protocol repo's secret-regenerated valid fixture`() {
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), fixture("secret-regenerated.valid.json"))
        check(decoded is SecretRegenerated)
        assertEquals(
            "https://mcp.droidthumb.com/d/dtk_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG/mcp",
            decoded.connectorUrl,
        )
    }
}
