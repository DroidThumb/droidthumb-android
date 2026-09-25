package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolContent
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Intent Tools Integration Tests")
class IntentToolsIntegrationTest {
    @BeforeEach
    fun setUp() {
        HandlerTestHarness.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        HandlerTestHarness.unmockAndroidLog()
    }

    @Test
    fun `open_uri valid uri returns success`() =
        runTest {
            val deps = HandlerTestHarness.createMockDependencies()
            coEvery {
                deps.intentDispatcher.openUri("https://example.com", null, null)
            } returns Result.success(Unit)

            HandlerTestHarness.withTools(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_uri",
                        arguments = mapOf("uri" to "https://example.com"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as ToolContent.Text).text
                assertTrue(text.contains("URI opened successfully"))
            }
        }

    @Test
    fun `open_uri with package_name passes through`() =
        runTest {
            val deps = HandlerTestHarness.createMockDependencies()
            coEvery {
                deps.intentDispatcher.openUri("https://example.com", "com.android.chrome", null)
            } returns Result.success(Unit)

            HandlerTestHarness.withTools(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_uri",
                        arguments =
                            mapOf(
                                "uri" to "https://example.com",
                                "package_name" to "com.android.chrome",
                            ),
                    )
                assertNotEquals(true, result.isError)
                coVerify {
                    deps.intentDispatcher.openUri("https://example.com", "com.android.chrome", null)
                }
            }
        }

    @Test
    fun `open_uri with mime_type passes through`() =
        runTest {
            val deps = HandlerTestHarness.createMockDependencies()
            coEvery {
                deps.intentDispatcher.openUri("content://media/1", null, "image/jpeg")
            } returns Result.success(Unit)

            HandlerTestHarness.withTools(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_uri",
                        arguments =
                            mapOf(
                                "uri" to "content://media/1",
                                "mime_type" to "image/jpeg",
                            ),
                    )
                assertNotEquals(true, result.isError)
                coVerify {
                    deps.intentDispatcher.openUri("content://media/1", null, "image/jpeg")
                }
            }
        }

    @Test
    fun `open_uri missing uri returns error`() =
        runTest {
            HandlerTestHarness.withTools { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_uri",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as ToolContent.Text).text
                assertTrue(text.contains("Missing required parameter"))
            }
        }

    @Test
    fun `open_uri dispatcher failure returns error with message`() =
        runTest {
            val deps = HandlerTestHarness.createMockDependencies()
            coEvery {
                deps.intentDispatcher.openUri("custom://unknown", null, null)
            } returns Result.failure(IllegalArgumentException("No app found to handle URI"))

            HandlerTestHarness.withTools(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_uri",
                        arguments = mapOf("uri" to "custom://unknown"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as ToolContent.Text).text
                assertTrue(text.contains("No app found"))
            }
        }
}
