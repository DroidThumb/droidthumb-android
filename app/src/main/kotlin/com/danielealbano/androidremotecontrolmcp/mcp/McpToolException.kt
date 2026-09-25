package com.danielealbano.androidremotecontrolmcp.mcp

/**
 * Sealed exception hierarchy for tool handler errors.
 *
 * Thrown from a tool handler's `execute` method. Whatever invokes the handler is responsible for
 * turning it into an error result (`ToolResult(isError = true)`); the on-device MCP server that used
 * to do this was removed (docs/plans/demolition.md).
 *
 * Each subclass classifies a specific failure mode.
 */
sealed class McpToolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class InvalidParams(
        message: String,
        cause: Throwable? = null,
    ) : McpToolException(message, cause)

    class InternalError(
        message: String,
        cause: Throwable? = null,
    ) : McpToolException(message, cause)

    class PermissionDenied(
        message: String,
        cause: Throwable? = null,
    ) : McpToolException(message, cause)

    class NodeNotFound(
        message: String,
        cause: Throwable? = null,
    ) : McpToolException(message, cause)

    class ActionFailed(
        message: String,
        cause: Throwable? = null,
    ) : McpToolException(message, cause)

    /**
     * Thrown when a tool operation exceeds its time limit.
     *
     * The specific subclass is used for logging granularity and internal classification,
     * not wire-level error codes.
     */
    class Timeout(
        message: String,
        cause: Throwable? = null,
    ) : McpToolException(message, cause)
}
