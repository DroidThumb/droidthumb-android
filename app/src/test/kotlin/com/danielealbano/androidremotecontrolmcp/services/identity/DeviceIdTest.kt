package com.danielealbano.androidremotecontrolmcp.services.identity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Base64

@Serializable
private data class Vector(
    @SerialName("public_key") val publicKey: String,
    @SerialName("device_id") val deviceId: String,
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
