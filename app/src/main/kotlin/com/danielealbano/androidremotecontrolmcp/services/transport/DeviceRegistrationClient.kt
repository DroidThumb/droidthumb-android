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
