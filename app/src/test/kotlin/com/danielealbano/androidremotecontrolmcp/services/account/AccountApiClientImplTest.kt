package com.danielealbano.androidremotecontrolmcp.services.account

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@DisplayName("AccountApiClientImpl")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountApiClientImplTest {
    private val client = AccountApiClientImpl()

    /** Forces the lazy Ktor/OkHttp engine to build before any assertion-bearing request: this is
     *  the alphabetically first test class in the whole suite, and the very first engine start
     *  in a cold JVM can exceed the production 5 s request timeout under full-suite load
     *  (HttpRequestTimeoutException against a perfectly healthy local server). The warm-up call
     *  itself is best-effort and its outcome is discarded — one deterministic warm-up step, no
     *  retry, no timing dependence. */
    @BeforeAll
    fun warmUpEngine() {
        runBlocking {
            withContext(Dispatchers.IO) {
                runCatching { client.listDevices("127.0.0.1", 1, tls = false, googleIdToken = "warmup") }
            }
        }
    }

    private suspend fun <T> withFakeServer(
        handler: suspend io.ktor.server.routing.RoutingContext.() -> Unit,
        block: suspend (port: Int) -> T,
    ): T {
        val server =
            embeddedServer(Netty, port = 0) {
                routing {
                    post("/v1/accounts/devices") { handler() }
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
    fun `200 with a parseable body returns Success`() =
        runTest {
            withFakeServer(
                handler = {
                    call.respondText(
                        """{"devices":[{"device_id":"dt_abc","created_at":"2026-10-01"}],"device_limit":3}""",
                        contentType = ContentType.Application.Json,
                    )
                },
            ) { port ->
                val result = client.listDevices("127.0.0.1", port, tls = false, googleIdToken = "tok")
                assertTrue(
                    result is DevicesResult.Success,
                    "expected Success but got $result",
                )
                assertEquals(1, (result as DevicesResult.Success).devices.size)
                assertEquals(3, result.deviceLimit)
            }
        }

    @Test
    fun `a 200 body that fails to parse maps to Server with the exception class, not Network`() =
        runTest {
            // A non-IOException (the response body failing to parse) is the server's answer
            // being wrong, not the network being down (plan A1 review).
            withFakeServer(
                handler = {
                    call.respondText(
                        """{"devices":"not-a-list"}""",
                        contentType = ContentType.Application.Json,
                    )
                },
            ) { port ->
                val result = client.listDevices("127.0.0.1", port, tls = false, googleIdToken = "tok")
                assertTrue(result is DevicesResult.Failed)
                val failed = result as DevicesResult.Failed
                assertEquals(ApiErrorKind.Server, failed.kind)
                assertEquals("JsonConvertException", failed.message)
                assertEquals("JsonConvertException", failed.exceptionClass)
                assertNull(failed.httpStatus)
            }
        }

    @Test
    fun `401 maps to Rejected`() =
        runTest {
            withFakeServer(
                handler = { call.respond(HttpStatusCode.Unauthorized) },
            ) { port ->
                val result = client.listDevices("127.0.0.1", port, tls = false, googleIdToken = "tok")
                assertTrue(result is DevicesResult.Failed)
                assertEquals(ApiErrorKind.Rejected, (result as DevicesResult.Failed).kind)
            }
        }

    @Test
    fun `an unreachable server maps to Network`() =
        runTest {
            val result = client.listDevices("127.0.0.1", 1, tls = false, googleIdToken = "tok")
            assertTrue(result is DevicesResult.Failed)
            assertEquals(ApiErrorKind.Network, (result as DevicesResult.Failed).kind)
        }
}
