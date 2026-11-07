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
                protocolVersion = 2,
                accounts = true,
                accountId = "acc_1",
                settings = WelcomeSettings(defaultStepTimeoutMs = 30000L),
            )
        val encoded = wireJson.encodeToString(WireMessage.serializer(), welcome)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(welcome, decoded)
        assertTrue(decoded is Welcome)
    }

    @Test
    fun `welcome decodes accounts false with a null account_id`() {
        val decoded =
            wireJson.decodeFromString(
                WireMessage.serializer(),
                """{"type":"welcome","accepted":true,"protocol_version":2,"accounts":false,"account_id":null}""",
            )
        check(decoded is Welcome)
        assertEquals(false, decoded.accounts)
        assertEquals(null, decoded.accountId)
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
                protocolVersion = 2,
                accounts = true,
                accountId = null,
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
        assertEquals(2, decoded.protocolVersion)
        assertEquals("1.2.3", decoded.apkVersion)
        assertEquals(34, decoded.androidVersion)
        assertEquals("Google Pixel 8", decoded.deviceModel)
        assertEquals("pixel-8-abc123", decoded.deviceId)
        assertEquals(listOf("a11y_tree"), decoded.capabilities)
        assertEquals("live", decoded.mode)
        assertEquals(listOf(FlowManifestEntry("flow-1", 2)), decoded.flowManifest)
    }

    @Test
    fun `decodes the protocol repo's welcome valid fixture`() {
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), fixture("welcome.valid.json"))
        check(decoded is Welcome)
        assertEquals(true, decoded.accepted)
        assertEquals(2, decoded.protocolVersion)
        assertEquals(true, decoded.accounts)
        assertEquals("acc_0123456789abcdef", decoded.accountId)
        assertEquals("1.3.0", decoded.latestAppVersion)
        assertEquals("1.0.0", decoded.minimumSupportedAppVersion)
        assertEquals("https://github.com/DroidThumb/droidthumb-android/releases/latest", decoded.downloadUrl)
        assertEquals(30000L, decoded.settings?.defaultStepTimeoutMs)
    }

    @Test
    fun `decodes the protocol repo's welcome valid accounts-false fixture`() {
        val decoded =
            wireJson.decodeFromString(WireMessage.serializer(), fixture("welcome.valid.accounts-false.json"))
        check(decoded is Welcome)
        assertEquals(true, decoded.accepted)
        assertEquals(false, decoded.accounts)
        assertEquals(null, decoded.accountId)
    }

    @Test
    fun `decodes the protocol repo's welcome valid signed-out fixture`() {
        val decoded =
            wireJson.decodeFromString(WireMessage.serializer(), fixture("welcome.valid.signed-out.json"))
        check(decoded is Welcome)
        assertEquals(true, decoded.accounts)
        assertEquals(null, decoded.accountId)
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

    @Test
    fun `sign_in round-trips`() {
        val message = SignIn(accountToken = "clt_abc123", installHint = "3fa2f1c9d4e5b6a7")
        val encoded = wireJson.encodeToString(WireMessage.serializer(), message)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(message, decoded)
        assertTrue(decoded is SignIn)
        assertTrue(encoded.contains("\"install_hint\""), "encoded sign_in missing install_hint: $encoded")
    }

    @Test
    fun `signed_in round-trips`() {
        val message = SignedIn(accountId = "acc_01J8Q7K2X9ABCDEFGHJKMNPQRS")
        val encoded = wireJson.encodeToString(WireMessage.serializer(), message)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(message, decoded)
        assertTrue(decoded is SignedIn)
    }

    @Test
    fun `sign_in_rejected round-trips with a reason only`() {
        val message = SignInRejected(reason = "invalid_token")
        val encoded = wireJson.encodeToString(WireMessage.serializer(), message)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(message, decoded)
        assertTrue(decoded is SignInRejected)
    }

    @Test
    fun `sign_in_rejected round-trips with devices`() {
        val message =
            SignInRejected(
                reason = "device_limit_reached",
                devices =
                    listOf(
                        SignInRejectedDevice("dt_1", "Pixel 8", "Google Pixel 8", "2026-10-21T09:30:00Z"),
                        SignInRejectedDevice("dt_2", "Old phone", "OnePlus Nord", "2026-10-14T18:02:11Z"),
                    ),
            )
        val encoded = wireJson.encodeToString(WireMessage.serializer(), message)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(message, decoded)
        check(decoded is SignInRejected)
        assertEquals("dt_1", decoded.devices?.first()?.deviceId)
        assertEquals("2026-10-14T18:02:11Z", decoded.devices?.last()?.lastSeen)
    }

    @Test
    fun `sign_out encodes with only a type field`() {
        val encoded = wireJson.encodeToString(WireMessage.serializer(), SignOut)
        assertEquals("""{"type":"sign_out"}""", encoded)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(SignOut, decoded)
        assertTrue(decoded is SignOut)
    }

    @Test
    fun `signed_out encodes with only a type field`() {
        val encoded = wireJson.encodeToString(WireMessage.serializer(), SignedOut)
        assertEquals("""{"type":"signed_out"}""", encoded)
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(SignedOut, decoded)
        assertTrue(decoded is SignedOut)
    }

    @Test
    fun `decodes the protocol repo's sign-in valid fixture`() {
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), fixture("sign-in.valid.json"))
        check(decoded is SignIn)
        assertEquals("clt_0123456789abcdefghijklmnopqrstuvwxyz", decoded.accountToken)
        assertEquals("3fa2f1c9d4e5b6a7", decoded.installHint)
    }

    @Test
    fun `decodes the protocol repo's signed-in valid fixture`() {
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), fixture("signed-in.valid.json"))
        check(decoded is SignedIn)
        assertEquals("acc_01J8Q7K2X9ABCDEFGHJKMNPQRS", decoded.accountId)
    }

    @Test
    fun `decodes the protocol repo's sign-in-rejected invalid-token fixture`() {
        val decoded =
            wireJson.decodeFromString(WireMessage.serializer(), fixture("sign-in-rejected.valid.invalid-token.json"))
        check(decoded is SignInRejected)
        assertEquals("invalid_token", decoded.reason)
        assertEquals(null, decoded.devices)
    }

    @Test
    fun `decodes the protocol repo's sign-in-rejected device-limit-reached fixture`() {
        val decoded =
            wireJson.decodeFromString(
                WireMessage.serializer(),
                fixture("sign-in-rejected.valid.device-limit-reached.json"),
            )
        check(decoded is SignInRejected)
        assertEquals("device_limit_reached", decoded.reason)
        assertEquals(2, decoded.devices?.size)
        assertEquals("Pixel 8", decoded.devices?.first()?.name)
        assertEquals("OnePlus Nord", decoded.devices?.last()?.model)
    }

    @Test
    fun `decodes the protocol repo's sign-in-rejected accounts-unsupported fixture`() {
        val decoded =
            wireJson.decodeFromString(
                WireMessage.serializer(),
                fixture("sign-in-rejected.valid.accounts-unsupported.json"),
            )
        check(decoded is SignInRejected)
        assertEquals("accounts_unsupported", decoded.reason)
        assertEquals(null, decoded.devices)
    }

    @Test
    fun `decodes the protocol repo's sign-in-rejected unknown-device fixture`() {
        val decoded =
            wireJson.decodeFromString(WireMessage.serializer(), fixture("sign-in-rejected.valid.unknown-device.json"))
        check(decoded is SignInRejected)
        assertEquals("unknown_device", decoded.reason)
    }

    @Test
    fun `decoding the protocol repo's sign-in missing-install-hint invalid fixture fails`() {
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(WireMessage.serializer(), fixture("sign-in.invalid.missing-install-hint.json"))
        }
    }

    @Test
    fun `decoding the protocol repo's sign-in missing-token invalid fixture fails`() {
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(WireMessage.serializer(), fixture("sign-in.invalid.missing-token.json"))
        }
    }

    @Test
    fun `decoding the protocol repo's signed-in missing-account-id invalid fixture fails`() {
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(WireMessage.serializer(), fixture("signed-in.invalid.missing-account-id.json"))
        }
    }

    @Test
    fun `decodes the protocol repo's sign-out valid fixture`() {
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), fixture("sign-out.valid.json"))
        assertTrue(decoded is SignOut)
    }

    @Test
    fun `decodes the protocol repo's signed-out valid fixture`() {
        val decoded = wireJson.decodeFromString(WireMessage.serializer(), fixture("signed-out.valid.json"))
        assertTrue(decoded is SignedOut)
    }
}
