package com.danielealbano.androidremotecontrolmcp.data.model

// On-disk byte ids for ServerLogEntry.Type — NEVER renumber (constants, not literals, for detekt MagicNumber).
// Ids 0 (TOOL_CALL), 1 (TUNNEL), 2 (SERVER), 3 (OAUTH), 4 (AUTH) and 7 (PRIVACY) are retired: the
// on-device MCP server, OAuth server, tool registration and Privacy Mode that wrote them were
// removed (docs/plans/demolition.md). Do not reuse any of them — old devices may still have
// persisted entries with those byte ids, which Type.fromId correctly maps to null (the reader
// skips unrecognized ids rather than failing).
private const val TYPE_ID_CHANNEL: Byte = 5
private const val TYPE_ID_SETTINGS: Byte = 6

/**
 * Represents a single log entry, displayed in the in-app logs viewer.
 *
 * @property timestamp The epoch milliseconds when the event occurred.
 * @property type The category of this log entry.
 * @property message A human-readable message describing the event.
 * @property toolName Optional short label a producer can attach; currently unset by every producer.
 * @property durationMs Optional duration in milliseconds a producer can attach; currently unset by every producer.
 */
data class ServerLogEntry(
    val timestamp: Long,
    val type: Type,
    val message: String,
    val toolName: String? = null,
    val durationMs: Long? = null,
) {
    /** Categorizes log entries for display. Ids are the on-disk byte encoding — NEVER renumber. */
    enum class Type(
        val id: Byte,
    ) {
        /** An event-channel lifecycle or delivery event. */
        CHANNEL(TYPE_ID_CHANNEL),

        /** A settings change (UI or ADB). */
        SETTINGS(TYPE_ID_SETTINGS),

        ;

        companion object {
            fun fromId(id: Byte): Type? = entries.firstOrNull { it.id == id }
        }
    }
}
