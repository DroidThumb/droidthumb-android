package com.danielealbano.androidremotecontrolmcp.services.account

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
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

data class AccountConnection(
    val clientId: String,
    val clientName: String,
    /** Account-editable display name (`droidthumb-server` plan 05 US1) — defaults server-side to
     *  "Claude"/"ChatGPT"/"Custom" when never renamed. */
    val displayName: String,
    val imageUrl: String?,
    val connectedAt: String,
)

data class AccountDevice(
    val deviceId: String,
    val createdAt: String,
    val lastConnectedAt: String?,
)

sealed interface ClaimTokenResult {
    data class Success(
        val claimToken: String,
        val accountId: String,
    ) : ClaimTokenResult

    data class Failed(
        val message: String,
    ) : ClaimTokenResult
}

sealed interface ConnectionsResult {
    data class Success(
        val connections: List<AccountConnection>,
    ) : ConnectionsResult

    data class Failed(
        val message: String,
    ) : ConnectionsResult
}

sealed interface RevokeResult {
    data object Revoked : RevokeResult

    data object NotFound : RevokeResult

    data class Failed(
        val message: String,
    ) : RevokeResult
}

sealed interface RenameResult {
    data object Updated : RenameResult

    data object NotFound : RenameResult

    data class Failed(
        val message: String,
    ) : RenameResult
}

sealed interface DevicesResult {
    data class Success(
        val devices: List<AccountDevice>,
        val deviceLimit: Int?,
    ) : DevicesResult

    data class Failed(
        val message: String,
    ) : DevicesResult
}

/**
 * `droidthumb-server`'s account-facing REST surface (design doc D-33/D-37/D-38) — the first
 * hand-written REST routes in that codebase, authenticated the same way on every call: a fresh
 * Google `id_token` in the body, never a session or bearer token (there is no app session
 * mechanism on this server at all). Same host/port/tls as [DeviceRegistrationClient] — the
 * single listener every other call in this app already talks to.
 */
interface AccountApiClient {
    suspend fun mintClaimToken(
        host: String,
        port: Int,
        tls: Boolean,
        googleIdToken: String,
    ): ClaimTokenResult

    suspend fun listConnections(
        host: String,
        port: Int,
        tls: Boolean,
        googleIdToken: String,
    ): ConnectionsResult

    suspend fun revokeConnection(
        host: String,
        port: Int,
        tls: Boolean,
        googleIdToken: String,
        clientId: String,
    ): RevokeResult

    suspend fun renameConnection(
        host: String,
        port: Int,
        tls: Boolean,
        googleIdToken: String,
        clientId: String,
        displayName: String,
    ): RenameResult

    suspend fun listDevices(
        host: String,
        port: Int,
        tls: Boolean,
        googleIdToken: String,
    ): DevicesResult
}

@Serializable
private data class GoogleIdTokenBody(
    @SerialName("google_id_token") val googleIdToken: String,
)

@Serializable
private data class RevokeBody(
    @SerialName("google_id_token") val googleIdToken: String,
    @SerialName("client_id") val clientId: String,
)

@Serializable
private data class RenameBody(
    @SerialName("google_id_token") val googleIdToken: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
private data class ClaimTokenResponseBody(
    @SerialName("claim_token") val claimToken: String,
    @SerialName("account_id") val accountId: String,
)

@Serializable
private data class ConnectionBody(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_name") val clientName: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("connected_at") val connectedAt: String,
)

@Serializable
private data class ConnectionsResponseBody(
    val connections: List<ConnectionBody>,
)

@Serializable
private data class DeviceBody(
    @SerialName("device_id") val deviceId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_connected_at") val lastConnectedAt: String? = null,
)

@Serializable
private data class DevicesResponseBody(
    val devices: List<DeviceBody>,
    @SerialName("device_limit") val deviceLimit: Int? = null,
)

@Singleton
class AccountApiClientImpl
    @Inject
    constructor() : AccountApiClient {
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
        override suspend fun mintClaimToken(
            host: String,
            port: Int,
            tls: Boolean,
            googleIdToken: String,
        ): ClaimTokenResult =
            withContext(Dispatchers.IO) {
                try {
                    val response: HttpResponse =
                        client.post {
                            url {
                                protocol = if (tls) URLProtocol.HTTPS else URLProtocol.HTTP
                                this.host = host
                                this.port = port
                                path(CLAIM_TOKEN_PATH)
                            }
                            contentType(ContentType.Application.Json)
                            setBody(GoogleIdTokenBody(googleIdToken))
                        }
                    when (response.status) {
                        HttpStatusCode.OK -> {
                            val body = response.body<ClaimTokenResponseBody>()
                            ClaimTokenResult.Success(body.claimToken, body.accountId)
                        }

                        else -> {
                            ClaimTokenResult.Failed("HTTP ${response.status.value}")
                        }
                    }
                } catch (e: Exception) {
                    ClaimTokenResult.Failed(e.message ?: "claim-token request failed")
                }
            }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun listConnections(
            host: String,
            port: Int,
            tls: Boolean,
            googleIdToken: String,
        ): ConnectionsResult =
            withContext(Dispatchers.IO) {
                try {
                    val response: HttpResponse =
                        client.post {
                            url {
                                protocol = if (tls) URLProtocol.HTTPS else URLProtocol.HTTP
                                this.host = host
                                this.port = port
                                path(CONNECTIONS_PATH)
                            }
                            contentType(ContentType.Application.Json)
                            setBody(GoogleIdTokenBody(googleIdToken))
                        }
                    when (response.status) {
                        HttpStatusCode.OK -> {
                            val body = response.body<ConnectionsResponseBody>()
                            ConnectionsResult.Success(
                                body.connections.map {
                                    AccountConnection(it.clientId, it.clientName, it.displayName, it.imageUrl, it.connectedAt)
                                },
                            )
                        }

                        else -> {
                            ConnectionsResult.Failed("HTTP ${response.status.value}")
                        }
                    }
                } catch (e: Exception) {
                    ConnectionsResult.Failed(e.message ?: "connections request failed")
                }
            }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun revokeConnection(
            host: String,
            port: Int,
            tls: Boolean,
            googleIdToken: String,
            clientId: String,
        ): RevokeResult =
            withContext(Dispatchers.IO) {
                try {
                    val response: HttpResponse =
                        client.post {
                            url {
                                protocol = if (tls) URLProtocol.HTTPS else URLProtocol.HTTP
                                this.host = host
                                this.port = port
                                path(REVOKE_PATH)
                            }
                            contentType(ContentType.Application.Json)
                            setBody(RevokeBody(googleIdToken, clientId))
                        }
                    when (response.status) {
                        HttpStatusCode.OK -> RevokeResult.Revoked
                        HttpStatusCode.NotFound -> RevokeResult.NotFound
                        else -> RevokeResult.Failed("HTTP ${response.status.value}")
                    }
                } catch (e: Exception) {
                    RevokeResult.Failed(e.message ?: "revoke request failed")
                }
            }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun renameConnection(
            host: String,
            port: Int,
            tls: Boolean,
            googleIdToken: String,
            clientId: String,
            displayName: String,
        ): RenameResult =
            withContext(Dispatchers.IO) {
                try {
                    val response: HttpResponse =
                        client.post {
                            url {
                                protocol = if (tls) URLProtocol.HTTPS else URLProtocol.HTTP
                                this.host = host
                                this.port = port
                                path(RENAME_PATH)
                            }
                            contentType(ContentType.Application.Json)
                            setBody(RenameBody(googleIdToken, clientId, displayName))
                        }
                    when (response.status) {
                        HttpStatusCode.OK -> RenameResult.Updated
                        HttpStatusCode.NotFound -> RenameResult.NotFound
                        else -> RenameResult.Failed("HTTP ${response.status.value}")
                    }
                } catch (e: Exception) {
                    RenameResult.Failed(e.message ?: "rename request failed")
                }
            }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun listDevices(
            host: String,
            port: Int,
            tls: Boolean,
            googleIdToken: String,
        ): DevicesResult =
            withContext(Dispatchers.IO) {
                try {
                    val response: HttpResponse =
                        client.post {
                            url {
                                protocol = if (tls) URLProtocol.HTTPS else URLProtocol.HTTP
                                this.host = host
                                this.port = port
                                path(DEVICES_PATH)
                            }
                            contentType(ContentType.Application.Json)
                            setBody(GoogleIdTokenBody(googleIdToken))
                        }
                    when (response.status) {
                        HttpStatusCode.OK -> {
                            val body = response.body<DevicesResponseBody>()
                            DevicesResult.Success(
                                body.devices.map { AccountDevice(it.deviceId, it.createdAt, it.lastConnectedAt) },
                                body.deviceLimit,
                            )
                        }

                        else -> {
                            DevicesResult.Failed("HTTP ${response.status.value}")
                        }
                    }
                } catch (e: Exception) {
                    DevicesResult.Failed(e.message ?: "devices request failed")
                }
            }

        private companion object {
            const val CLAIM_TOKEN_PATH = "/v1/accounts/claim-token"
            const val CONNECTIONS_PATH = "/v1/accounts/connections"
            const val REVOKE_PATH = "/v1/accounts/connections/revoke"
            const val RENAME_PATH = "/v1/accounts/connections/rename"
            const val DEVICES_PATH = "/v1/accounts/devices"
            const val REQUEST_TIMEOUT_MS = 5_000L
            const val CONNECT_TIMEOUT_MS = 3_000L
        }
    }
