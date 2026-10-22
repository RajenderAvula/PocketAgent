package com.personal.pocketagent.automation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.personal.pocketagent.data.local.AgentPrefs
import com.personal.pocketagent.overlay.FloatingOverlayService

class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        if (AgentPrefs.isAutoStartOnBoot(this) &&
            AgentPrefs.isBubbleEnabled(this) &&
            Settings.canDrawOverlays(this) &&
            !FloatingOverlayService.isRunning
        ) {
            try {
                val intent = Intent(this, FloatingOverlayService::class.java)
                ContextCompat.startForegroundService(this, intent)
            } catch (_: Exception) {}
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            MacroRecorder.captureEvent(event)
        } catch (_: Throwable) {}
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    fun clickNodeByText(targetText: String, skipEditableFields: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val matchedNodes = try { root.findAccessibilityNodeInfosByText(targetText) } catch (_: Throwable) { emptyList() }

        for (node in matchedNodes) {
            if (skipEditableFields && node.isEditable) continue
            if (performClickOnNodeOrParent(node)) return true
        }
        return searchAndClickByDescription(root, targetText, skipEditableFields, depth = 0)
    }

    fun clickNodeByViewId(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = try { root.findAccessibilityNodeInfosByViewId(viewId) } catch (_: Throwable) { emptyList() }
        for (node in nodes) {
            if (performClickOnNodeOrParent(node)) return true
        }
        return false
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            try {
                if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current = current.parent
            } catch (_: Throwable) { break }
        }
        return false
    }

    private fun searchAndClickByDescription(node: AccessibilityNodeInfo?, target: String, skipEditable: Boolean, depth: Int): Boolean {
        if (node == null || depth > 20) return false
        try {
            if (!(skipEditable && node.isEditable)) {
                val desc = node.contentDescription?.toString() ?: ""
                if (desc.contains(target, ignoreCase = true) && performClickOnNodeOrParent(node)) return true
            }
            for (i in 0 until node.childCount) {
                if (searchAndClickByDescription(node.getChild(i), target, skipEditable, depth + 1)) return true
            }
        } catch (_: Throwable) {}
        return false
    }

    fun inputTextIntoField(text: String, targetHint: String = ""): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = if (targetHint.isNotBlank()) {
            findEditableByHintOrId(root, targetHint, 0)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditableNode(root, 0)
        } else {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditableNode(root, 0)
        } ?: return false

        try {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (_: Throwable) {
            return false
        }
    }

    private fun findEditableByHintOrId(node: AccessibilityNodeInfo?, hint: String, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 20) return null
        try {
            if (node.isEditable) {
                val nodeText = node.text?.toString() ?: ""
                val nodeHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString() ?: "" else ""
                val viewId = node.viewIdResourceName ?: ""
                if (nodeHint.contains(hint, true) || nodeText.contains(hint, true) || viewId.contains(hint, true)) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                val found = findEditableByHintOrId(node.getChild(i), hint, depth + 1)
                if (found != null) return found
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 20) return null
        try {
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                val found = findFirstEditableNode(node.getChild(i), depth + 1)
                if (found != null) return found
            }
        } catch (_: Throwable) {}
        return null
    }

    fun performImeEnter(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(root, 0)
            ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } catch (_: Throwable) { false }
        } else false
    }

    fun executeGlobalCommand(command: String): Boolean {
        return try {
            when (command.lowercase().trim()) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "scroll_down" -> scrollFirstScrollableNode(rootInActiveWindow, forward = true, 0)
                "scroll_up" -> scrollFirstScrollableNode(rootInActiveWindow, forward = false, 0)
                else -> false
            }
        } catch (_: Throwable) { false }
    }

    private fun scrollFirstScrollableNode(node: AccessibilityNodeInfo?, forward: Boolean, depth: Int): Boolean {
        if (node == null || depth > 20) return false
        try {
            if (node.isScrollable) {
                val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                return node.performAction(action)
            }
            for (i in 0 until node.childCount) {
                if (scrollFirstScrollableNode(node.getChild(i), forward, depth + 1)) return true
            }
        } catch (_: Throwable) {}
        return false
    }

    /**
     * Extracts visible screen text without crashing, locking, or overflowing the Binder transaction buffer.
     */
    fun extractVisibleScreenText(maxChars: Int = 3500): String {
        return try {
            val targetRoot = findUnderlyingAppRoot() ?: rootInActiveWindow ?: return ""
            val packageName = targetRoot.packageName?.toString() ?: "unknown.app"

            val collectedLines = LinkedHashSet<String>()
            collectNodeTextSafely(targetRoot, collectedLines, depth = 0)

            if (collectedLines.isEmpty()) return ""
            ("App: $packageName\n" + collectedLines.joinToString("\n")).take(maxChars)
        } catch (t: Throwable) {
            "Screen reading completed: Content could not be parsed (${t.message ?: "active window unavailable"})."
        }
    }

    private fun findUnderlyingAppRoot(): AccessibilityNodeInfo? {
        return try {
            val currentWindows = windows ?: return null
            for (window in currentWindows) {
                val root = try { window.root } catch (_: Throwable) { null } ?: continue
                val pkg = root.packageName?.toString() ?: ""
                if (pkg.isNotEmpty() && !pkg.contains("pocketagent", ignoreCase = true)) {
                    return root
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun collectNodeTextSafely(node: AccessibilityNodeInfo?, output: MutableSet<String>, depth: Int) {
        if (node == null || depth > 20 || output.size > 80) return
        try {
            if (!node.isVisibleToUser) return
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()

            if (!text.isNullOrBlank() && text.length > 1) {
                output.add(text)
            } else if (!desc.isNullOrBlank() && desc.length > 2) {
                output.add("[$desc]")
            }

            for (i in 0 until node.childCount) {
                collectNodeTextSafely(node.getChild(i), output, depth + 1)
            }
        } catch (_: Throwable) {}
    }

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
            private set
        fun isRunning(): Boolean = instance != null
    }
}
