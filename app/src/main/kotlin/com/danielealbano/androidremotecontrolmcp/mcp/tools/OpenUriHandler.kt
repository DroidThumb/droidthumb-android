package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcher
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// open_uri
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `open_uri`.
 *
 * Opens a URI using Android's `ACTION_VIEW`. Handles https, http, tel, mailto,
 * geo, content URLs, deep links, and custom app schemes.
 *
 * **Input**: `{ "uri": "<string>", "package_name": "<string>?", "mime_type": "<string>?" }`
 * **Output**: `{ "content": [{ "type": "text", "text": "URI opened successfully: ..." }] }`
 */
class OpenUriHandler
    @Inject
    constructor(
        private val intentDispatcher: IntentDispatcher,
    ) {
        suspend fun execute(arguments: JsonObject?): ToolResult {
            val uri = McpToolUtils.requireString(arguments, "uri")
            val packageName =
                McpToolUtils
                    .optionalString(arguments, "package_name", "")
                    .ifEmpty { null }
            val mimeType =
                McpToolUtils
                    .optionalString(arguments, "mime_type", "")
                    .ifEmpty { null }

            Logger.d(TAG, "Executing open_uri")
            val result = intentDispatcher.openUri(uri, packageName, mimeType)
            return McpToolUtils.handleActionResult(result, "URI opened successfully: $uri")
        }

        companion object {
            const val TOOL_NAME = "open_uri"
            private const val TAG = "MCP:OpenUriTool"
        }
    }
