package com.danielealbano.androidremotecontrolmcp.services.mcp

import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("serverStatusLogMessage")
class ServerStatusLogMessageTest {
    @Test
    fun `messages for all five statuses`() {
        assertEquals("Server starting", serverStatusLogMessage(ServerStatus.Starting))
        assertEquals(
            "Server started on 127.0.0.1:8080",
            serverStatusLogMessage(ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")),
        )
        assertEquals("Server stopping", serverStatusLogMessage(ServerStatus.Stopping))
        assertEquals("Server stopped", serverStatusLogMessage(ServerStatus.Stopped))
        assertEquals("Server error: boom", serverStatusLogMessage(ServerStatus.Error("boom")))
    }
}
