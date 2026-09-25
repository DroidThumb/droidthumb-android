@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/**
 * Executes a system action via [ActionExecutor], with standard error handling.
 *
 * Checks accessibility service availability, executes the action, and returns
 * a text content response on success. Throws [McpToolException] on failure.
 *
 * @param actionName Human-readable name of the action (for error/success messages).
 * @param action Suspend function that performs the system action and returns [Result].
 * @return [ToolResult] with confirmation message.
 */
private suspend fun executeSystemAction(
    accessibilityServiceProvider: AccessibilityServiceProvider,
    actionName: String,
    action: suspend () -> Result<Unit>,
): ToolResult {
    if (!accessibilityServiceProvider.isReady()) {
        throw McpToolException.PermissionDenied(
            "Accessibility service not enabled. Please enable it in Android Settings > Accessibility.",
        )
    }

    val result = action()
    result.onFailure { exception ->
        throw McpToolException.ActionFailed(
            "$actionName failed: ${exception.message ?: "Unknown error"}",
        )
    }

    return McpToolUtils.textResult("$actionName executed successfully")
}

// ─────────────────────────────────────────────────────────────────────────────
// press_back
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `press_back`.
 *
 * Presses the system back button via accessibility global action.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "Back button press executed successfully" }] }`
 * **Errors**:
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if action execution failed
 */
class PressBackHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): ToolResult =
            executeSystemAction(accessibilityServiceProvider, "Back button press") {
                actionExecutor.pressBack()
            }

        companion object {
            const val TOOL_NAME = "press_back"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// press_home
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `press_home`.
 *
 * Navigates to the home screen via accessibility global action.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "Home button press executed successfully" }] }`
 * **Errors**:
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if action execution failed
 */
class PressHomeHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): ToolResult =
            executeSystemAction(accessibilityServiceProvider, "Home button press") {
                actionExecutor.pressHome()
            }

        companion object {
            const val TOOL_NAME = "press_home"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// press_recents
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `press_recents`.
 *
 * Opens the recent apps screen via accessibility global action.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "Recents button press executed successfully" }] }`
 * **Errors**:
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if action execution failed
 */
class PressRecentsHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): ToolResult =
            executeSystemAction(accessibilityServiceProvider, "Recents button press") {
                actionExecutor.pressRecents()
            }

        companion object {
            const val TOOL_NAME = "press_recents"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// dismiss_keyboard
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `dismiss_keyboard`.
 *
 * Closes the on-screen soft keyboard if one is open. No-op (and never navigates back) when no
 * keyboard is visible — see [ActionExecutor.dismissKeyboard].
 *
 * **Input**: `{}` (no parameters)
 * **Output**: text `"Keyboard dismissed"` or `"No keyboard was open"`
 * **Errors**:
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if dismissing the keyboard failed
 */
class DismissKeyboardHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): ToolResult {
            if (!accessibilityServiceProvider.isReady()) {
                throw McpToolException.PermissionDenied(
                    "Accessibility service not enabled. Please enable it in Android Settings > Accessibility.",
                )
            }

            val dismissed =
                actionExecutor.dismissKeyboard().getOrElse { exception ->
                    throw McpToolException.ActionFailed(
                        "Dismiss keyboard failed: ${exception.message ?: "Unknown error"}",
                    )
                }

            return McpToolUtils.textResult(
                if (dismissed) "Keyboard dismissed" else "No keyboard was open",
            )
        }

        companion object {
            const val TOOL_NAME = "dismiss_keyboard"
        }
    }
