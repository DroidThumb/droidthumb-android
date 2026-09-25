package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ServerLogEntry.Type")
class ServerLogEntryTypeTest {
    @Test
    fun `type ids are pinned to their on-disk values`() {
        assertEquals(5.toByte(), ServerLogEntry.Type.CHANNEL.id)
        assertEquals(6.toByte(), ServerLogEntry.Type.SETTINGS.id)
    }

    @Test
    fun `fromId maps every id and returns null for unknown`() {
        ServerLogEntry.Type.entries.forEach { type ->
            assertEquals(type, ServerLogEntry.Type.fromId(type.id))
        }
        assertNull(ServerLogEntry.Type.fromId(99.toByte()))
    }

    @Test
    fun `fromId returns null for every retired id`() {
        // 0=TOOL_CALL, 1=TUNNEL, 2=SERVER, 3=OAUTH, 4=AUTH, 7=PRIVACY — see ServerLogEntry.kt.
        listOf(0, 1, 2, 3, 4, 7).forEach { id ->
            assertNull(ServerLogEntry.Type.fromId(id.toByte()), "id $id should decode to null")
        }
    }
}
