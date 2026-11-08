package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.mcp.tools.findFocusedEditableNode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Performs the keyboard's own action (the IME's send/search/go action) on the currently focused
 * field, exactly as a user pressing the keyboard's action key would.
 */
interface ImeSubmitController {
    /**
     * Performs [AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER] on the focused
     * editable field. `false` means the keyboard's action cannot be sent: no editable field is
     * focused, the focused field does not offer the action, or the framework reported the
     * action as not performed. The typed text is left as it was — nothing is undone.
     */
    fun submitFocusedField(): Boolean
}

@Singleton
class ImeSubmitControllerImpl
    @Inject
    constructor(
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) : ImeSubmitController {
        override fun submitFocusedField(): Boolean {
            val focusedNode =
                findFocusedEditableNode(accessibilityServiceProvider) ?: return false
            return try {
                val offered =
                    focusedNode.actionList.any {
                        it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    }
                offered && focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } finally {
                @Suppress("DEPRECATION")
                focusedNode.recycle()
            }
        }
    }
