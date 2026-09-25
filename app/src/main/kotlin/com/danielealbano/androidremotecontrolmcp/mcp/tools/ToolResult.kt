package com.danielealbano.androidremotecontrolmcp.mcp.tools

/**
 * Result of a tool handler: what an LLM-facing caller receives.
 *
 * Mirrors the shape of the MCP SDK's `CallToolResult` that the handlers used to return, so handler
 * logic did not change when the on-device MCP server was removed (docs/plans/demolition.md §5.2).
 */
data class ToolResult(
    val content: List<ToolContent>,
    val isError: Boolean = false,
)

/** One content item of a [ToolResult]. */
sealed interface ToolContent {
    /** Plain text. */
    data class Text(
        val text: String,
    ) : ToolContent

    /** An image; [data] is the base64-encoded image bytes. */
    data class Image(
        val data: String,
        val mimeType: String,
    ) : ToolContent
}
