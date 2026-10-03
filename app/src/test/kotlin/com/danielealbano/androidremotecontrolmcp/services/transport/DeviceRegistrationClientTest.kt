package com.danielealbano.androidremotecontrolmcp.services.transport

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

@DisplayName("DeviceRegistrationClient")
class DeviceRegistrationClientTest {
    private val client = DeviceRegistrationClientImpl()

    private suspend fun <T> withFakeServer(
        handler: suspend io.ktor.server.routing.RoutingContext.() -> Unit,
        block: suspend (port: Int) -> T,
    ): T {
        val server =
            embeddedServer(Netty, port = 0) {
                routing {
                    post("/devices/register") { handler() }
                }
            }
        server.start(wait = false)
        val port =
            server.engine
                .resolvedConnectors()
                .first()
                .port
        try {
            return block(port)
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `200 with connector_url returns Success with device_id and that URL`() =
        runTest {
            withFakeServer(
                handler = {
                    call.respondText(
                        """{"device_id":"dt_abc","connector_url":"https://h/d/x/mcp"}""",
                        contentType = io.ktor.http.ContentType.Application.Json,
                    )
                },
            ) { port ->
                val result = client.register("127.0.0.1", port, tls = false, publicKeyBase64 = "pub")
                check(result is DeviceRegistrationResult.Success)
                assertEquals("dt_abc", result.deviceId)
                assertEquals("https://h/d/x/mcp", result.connectorUrl)
            }
        }

    @Test
    fun `200 with no connector_url returns Success with a null URL`() =
        runTest {
            withFakeServer(
                handler = {
                    call.respondText(
                        """{"device_id":"dt_abc"}""",
                        contentType = io.ktor.http.ContentType.Application.Json,
                    )
                },
            ) { port ->
                val result = client.register("127.0.0.1", port, tls = false, publicKeyBase64 = "pub")
                check(result is DeviceRegistrationResult.Success)
                assertEquals("dt_abc", result.deviceId)
                assertNull(result.connectorUrl)
            }
        }

    @Test
    fun `429 with Retry-After returns RateLimited with that value`() =
        runTest {
            withFakeServer(
                handler = {
                    call.response.headers.append("Retry-After", "120")
                    call.respond(HttpStatusCode.TooManyRequests)
                },
            ) { port ->
                val result = client.register("127.0.0.1", port, tls = false, publicKeyBase64 = "pub")
                check(result is DeviceRegistrationResult.RateLimited)
                assertEquals(120, result.retryAfterSeconds)
            }
        }

    @Test
    fun `429 with no Retry-After returns RateLimited(null)`() =
        runTest {
            withFakeServer(
                handler = { call.respond(HttpStatusCode.TooManyRequests) },
            ) { port ->
                val result = client.register("127.0.0.1", port, tls = false, publicKeyBase64 = "pub")
                check(result is DeviceRegistrationResult.RateLimited)
                assertNull(result.retryAfterSeconds)
            }
        }

    @Test
    fun `400 returns Failed with the status code`() =
        runTest {
            withFakeServer(
                handler = { call.respond(HttpStatusCode.BadRequest) },
            ) { port ->
                val result = client.register("127.0.0.1", port, tls = false, publicKeyBase64 = "pub")
                check(result is DeviceRegistrationResult.Failed)
                assertEquals("HTTP 400", result.message)
            }
        }

    @Test
    fun `request body sends only public_key`() =
        runTest {
            val receivedBody = AtomicReference<String?>(null)
            withFakeServer(
                handler = {
                    receivedBody.set(call.receiveText())
                    call.respondText(
                        """{"device_id":"dt_abc"}""",
                        contentType = io.ktor.http.ContentType.Application.Json,
                    )
                },
            ) { port ->
                client.register("127.0.0.1", port, tls = false, publicKeyBase64 = "pub-key-value")
                val body = receivedBody.get()
                assertTrue(body != null && body.contains("\"public_key\":\"pub-key-value\""), "unexpected body: $body")
                assertTrue(body != null && !body.contains("device_id"), "body should not contain device_id: $body")
            }
        }

    @Test
    fun `unreachable server returns Failed`() =
        runTest {
            val result = client.register("127.0.0.1", 1, tls = false, publicKeyBase64 = "pub")
            assertTrue(result is DeviceRegistrationResult.Failed)
        }
}
