package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.apps.AppManager
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// open_app
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `open_app`.
 *
 * Opens (launches) an application by its package ID.
 *
 * **Input**: `{ "package_id": "<string>" }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Application '<package_id>' launched successfully." }] }`
 */
class OpenAppHandler
    @Inject
    constructor(
        private val appManager: AppManager,
    ) {
        @Suppress("TooGenericExceptionCaught")
        suspend fun execute(arguments: JsonObject?): ToolResult {
            val packageId = McpToolUtils.requireString(arguments, "package_id")
            if (packageId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'package_id' must not be empty")
            }

            Log.d(TAG, "Executing open_app for package: $packageId")
            val result = appManager.openApp(packageId)
            result.onFailure { e ->
                throw McpToolException.ActionFailed("Failed to open application '$packageId': ${e.message}")
            }
            return McpToolUtils.textResult("Application '$packageId' launched successfully.")
        }

        companion object {
            const val TOOL_NAME = "open_app"
            private const val TAG = "MCP:OpenAppHandler"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// close_app
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `close_app`.
 *
 * Kills a background application process.
 *
 * **Input**: `{ "package_id": "<string>" }`
 * **Output**: `{ "content": [{ "type": "text",
 *   "text": "Kill signal sent for application '<package_id>'..." }] }`
 */
class CloseAppHandler
    @Inject
    constructor(
        private val appManager: AppManager,
    ) {
        @Suppress("TooGenericExceptionCaught")
        suspend fun execute(arguments: JsonObject?): ToolResult {
            val packageId = McpToolUtils.requireString(arguments, "package_id")
            if (packageId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'package_id' must not be empty")
            }

            Log.d(TAG, "Executing close_app for package: $packageId")
            val result = appManager.closeApp(packageId)
            result.onFailure { e ->
                throw McpToolException.ActionFailed("Failed to close application '$packageId': ${e.message}")
            }
            return McpToolUtils.textResult(
                "Kill signal sent for application '$packageId'. " +
                    "Note: this only affects background processes.",
            )
        }

        companion object {
            const val TOOL_NAME = "close_app"
            private const val TAG = "MCP:CloseAppHandler"
        }
    }
