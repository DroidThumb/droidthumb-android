package com.danielealbano.androidremotecontrolmcp.services.transport

import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import com.danielealbano.androidremotecontrolmcp.data.repository.ConnectorUrlSettings
import com.danielealbano.androidremotecontrolmcp.data.repository.PauseSettings
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceIdentityKeyStore
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceInfoProvider
import com.danielealbano.androidremotecontrolmcp.wireprotocol.StepDispatcher
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.seconds

/**
 * Shared fixtures for [DeviceTransportClientTest]/[DeviceTransportClientPauseTest] — split across
 * two files so neither grows past detekt's `LargeClass` threshold; both exercise
 * [DeviceTransportClientImpl] against the same real, host-side Ktor WS server standing in for
 * `droidthumb-server`'s handshake (the same fake-server-on-the-JVM pattern `EventDispatcherImplTest`
 * already uses for HTTP, extended to WebSockets).
 */
abstract class DeviceTransportClientTestBase {
    protected fun stepDispatcherMock(): StepDispatcher = mockk()

    protected companion object {
        const val TEST_PUBLIC_KEY_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaLYBkdZrrs2nrNpdnPkFSS4F00NwONoA5e6B4Q6pB/5Oaxi6NUPTW7pJ80l8L+0Aaxt1V/87nXFRb83soCH0Sw=="
        const val TEST_DEVICE_ID = "dt_1d5aaa900ef5ffb98ae91a05932113643681ebc96ed43bdd084195ce34a9b09e"
    }

    protected fun newClient(
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
        pauseSettings: PauseSettings =
            mockk {
                coEvery { getPauseState() } returns PauseState()
            },
    ): DeviceTransportClientImpl =
        DeviceTransportClientImpl(
            dispatcher,
            identityKeyStore,
            deviceInfoProvider,
            registrationClient,
            connectorUrlSettings,
            pauseSettings,
        )

    /**
     * Waits until `client.status` matches [T], returning that exact snapshot — never re-reading
     * `client.status.value` afterward. Several fake-server handlers return immediately after
     * sending `Welcome` (closing the connection from the server side), which sends the real client
     * straight back into `Reconnecting`/`Connecting` on the very next loop iteration with no delay;
     * a plain `while (status !is X) yield()` followed by a second read is a TOCTOU race against that
     * transition, found live (not theoretical) once `ensureRegistered()`'s extra suspension point
     * per loop iteration shifted the scheduling enough to make it flaky under `:app:test`'s full run.
     */
    protected suspend inline fun <reified T : TransportStatus> awaitStatus(client: DeviceTransportClientImpl): T =
        withTimeout(15.seconds) {
            var captured: T? = null
            while (captured == null) {
                val current = client.status.value
                if (current is T) captured = current else yield()
            }
            captured
        }

    protected suspend fun <T> withFakeServer(
        handler: suspend DefaultWebSocketServerSession.() -> Unit,
        block: suspend (port: Int) -> T,
    ): T {
        val server =
            embeddedServer(Netty, port = 0) {
                install(WebSockets)
                routing {
                    webSocket("/device") { handler() }
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
}
