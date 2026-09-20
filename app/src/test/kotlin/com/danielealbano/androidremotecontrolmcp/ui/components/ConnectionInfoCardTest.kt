package com.danielealbano.androidremotecontrolmcp.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests verifying ConnectionInfoCard's URL/connection-string logic. These exercise the
 * pure logic extracted from the composable without a Compose runtime.
 */
class ConnectionInfoCardTest {
    private fun buildServerUrl(
        scheme: String,
        displayAddress: String,
        port: Int,
    ): String = "$scheme://$displayAddress:$port/mcp"

    @Test
    fun `serverUrl includes mcp suffix`() {
        val url = buildServerUrl("http", "127.0.0.1", 8080)
        assertTrue(url.endsWith("/mcp"), "URL should end with /mcp but was: $url")
        assertEquals("http://127.0.0.1:8080/mcp", url)
    }

    @Test
    fun `serverUrl includes mcp suffix with https`() {
        val url = buildServerUrl("https", "192.168.1.100", 8443)
        assertTrue(url.endsWith("/mcp"), "URL should end with /mcp but was: $url")
        assertEquals("https://192.168.1.100:8443/mcp", url)
    }

    @Test
    fun `copyAll always uses real bearer token`() {
        val realToken = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        val connectionString =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                bearerToken = realToken,
            )
        assertTrue(
            connectionString.contains("Bearer Token: $realToken"),
            "Connection string should contain the real bearer token",
        )
        assertFalse(
            connectionString.contains("********"),
            "Connection string should never contain masked token",
        )
    }

    @Test
    fun `connectionString format`() {
        val connectionString =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                bearerToken = "test-token-123",
            )
        assertEquals(
            "URL: http://127.0.0.1:8080/mcp\nBearer Token: test-token-123",
            connectionString,
        )
    }

    @Test
    fun `connection string omits bearer token when empty`() {
        val connectionString =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                bearerToken = "",
            )
        assertEquals("URL: http://127.0.0.1:8080/mcp", connectionString)
        assertFalse(connectionString.contains("Bearer Token"))
    }
}
