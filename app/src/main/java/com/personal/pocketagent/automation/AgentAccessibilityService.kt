package com.personal.pocketagent.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
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
        MacroRecorder.captureEvent(event)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    fun clickNodeByText(targetText: String, skipEditableFields: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val matchedNodes = root.findAccessibilityNodeInfosByText(targetText)

        for (node in matchedNodes) {
            if (skipEditableFields && node.isEditable) continue
            if (performClickOnNodeOrParent(node)) return true
        }
        return searchAndClickByDescription(root, targetText, skipEditableFields)
    }

    fun clickNodeByViewId(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        for (node in nodes) {
            if (performClickOnNodeOrParent(node)) return true
        }
        return false
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
        }
        return false
    }

    private fun searchAndClickByDescription(node: AccessibilityNodeInfo?, target: String, skipEditable: Boolean): Boolean {
        if (node == null) return false
        if (!(skipEditable && node.isEditable)) {
            val desc = node.contentDescription?.toString() ?: ""
            if (desc.contains(target, ignoreCase = true) && performClickOnNodeOrParent(node)) return true
        }
        for (i in 0 until node.childCount) {
            if (searchAndClickByDescription(node.getChild(i), target, skipEditable)) return true
        }
        return false
    }

    fun inputTextIntoField(text: String, targetHint: String = ""): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = if (targetHint.isNotBlank()) {
            findEditableByHintOrId(root, targetHint)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditableNode(root)
        } else {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditableNode(root)
        } ?: return false

        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun findEditableByHintOrId(node: AccessibilityNodeInfo?, hint: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) {
            val nodeText = node.text?.toString() ?: ""
            val nodeHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString() ?: "" else ""
            val viewId = node.viewIdResourceName ?: ""
            if (nodeHint.contains(hint, true) || nodeText.contains(hint, true) || viewId.contains(hint, true)) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val found = findEditableByHintOrId(node.getChild(i), hint)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    fun performImeEnter(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(root)
            ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else false
    }

    fun executeGlobalCommand(command: String): Boolean {
        return when (command.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "scroll_down" -> scrollFirstScrollableNode(rootInActiveWindow, forward = true)
            "scroll_up" -> scrollFirstScrollableNode(rootInActiveWindow, forward = false)
            else -> false
        }
    }

    private fun scrollFirstScrollableNode(node: AccessibilityNodeInfo?, forward: Boolean): Boolean {
        if (node == null) return false
        if (node.isScrollable) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            return node.performAction(action)
        }
        for (i in 0 until node.childCount) {
            if (scrollFirstScrollableNode(node.getChild(i), forward)) return true
        }
        return false
    }

    fun extractVisibleScreenText(maxChars: Int = 3500): String {
        val targetRoot = findUnderlyingAppRoot() ?: rootInActiveWindow ?: return ""
        val packageName = targetRoot.packageName?.toString() ?: "unknown.app"

        val collectedLines = LinkedHashSet<String>()
        collectNodeText(targetRoot, collectedLines)

        if (collectedLines.isEmpty()) return ""
        return ("App: $packageName\n" + collectedLines.joinToString("\n")).take(maxChars)
    }

    private fun findUnderlyingAppRoot(): AccessibilityNodeInfo? {
        return try {
            windows?.firstOrNull { window ->
                val root = window.root
                val pkg = root?.packageName?.toString() ?: ""
                pkg.isNotEmpty() && !pkg.contains("pocketagent", ignoreCase = true)
            }?.root
        } catch (_: Exception) { null }
    }

    private fun collectNodeText(node: AccessibilityNodeInfo?, output: MutableSet<String>) {
        if (node == null || !node.isVisibleToUser) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrBlank() && text.length > 1) output.add(text)
        else if (!desc.isNullOrBlank() && desc.length > 2) output.add("[$desc]")

        for (i in 0 until node.childCount) {
            collectNodeText(node.getChild(i), output)
        }
    }

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
            private set
        fun isRunning(): Boolean = instance != null
    }
}
