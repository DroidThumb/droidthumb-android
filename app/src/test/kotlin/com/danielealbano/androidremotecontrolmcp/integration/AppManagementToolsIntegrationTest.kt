package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.data.model.AppInfo
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolContent
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("App Management Tools Integration Tests")
class AppManagementToolsIntegrationTest {
    @BeforeEach
    fun setUp() {
        HandlerTestHarness.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        HandlerTestHarness.unmockAndroidLog()
    }

    @Test
    fun `open_app with valid package_id launches app successfully`() =
        runTest {
            val deps = HandlerTestHarness.createMockDependencies()
            coEvery { deps.appManager.openApp("com.test.app") } returns Result.success(Unit)

            HandlerTestHarness.withTools(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_app",
                        arguments = mapOf("package_id" to "com.test.app"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as ToolContent.Text).text
                assertTrue(text.contains("launched successfully"))
            }
        }

    @Test
    fun `open_app with unknown package_id returns error`() =
        runTest {
            val deps = HandlerTestHarness.createMockDependencies()
            coEvery {
                deps.appManager.openApp("com.unknown")
            } returns Result.failure(IllegalArgumentException("No launchable activity"))

            HandlerTestHarness.withTools(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_app",
                        arguments = mapOf("package_id" to "com.unknown"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as ToolContent.Text).text
                assertTrue(text.contains("Failed to open"))
            }
        }

    @Test
    fun `open_app with missing package_id returns invalid params error`() =
        runTest {
            HandlerTestHarness.withTools { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_app",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as ToolContent.Text).text
                assertTrue(text.contains("Missing required parameter"))
            }
        }

    @Test
    fun `close_app sends kill signal successfully`() =
        runTest {
            val deps = HandlerTestHarness.createMockDependencies()
            coEvery { deps.appManager.closeApp("com.test.app") } returns Result.success(Unit)

            HandlerTestHarness.withTools(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_close_app",
                        arguments = mapOf("package_id" to "com.test.app"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as ToolContent.Text).text
                assertTrue(text.contains("Kill signal sent"))
            }
        }

    @Test
    fun `close_app with missing package_id returns invalid params error`() =
        runTest {
            HandlerTestHarness.withTools { client, _ ->
                val result =
                    client.callTool(
                        name = "android_close_app",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as ToolContent.Text).text
                assertTrue(text.contains("Missing required parameter"))
            }
        }
}
