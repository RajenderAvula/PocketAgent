package com.personal.pocketagent.automation

import android.view.accessibility.AccessibilityEvent
import com.personal.pocketagent.domain.model.MacroStep

object MacroRecorder {
    @Volatile var isRecording: Boolean = false
        private set

    private val recordedSteps = mutableListOf<MacroStep>()
    private var lastRecordedTime = System.currentTimeMillis()

    fun startRecording() {
        recordedSteps.clear()
        lastRecordedTime = System.currentTimeMillis()
        isRecording = true
    }

    fun stopRecording(): List<MacroStep> {
        isRecording = false
        return recordedSteps.toList()
    }

    fun captureEvent(event: AccessibilityEvent?) {
        if (!isRecording || event == null) return
        val pkg = event.packageName?.toString() ?: ""
        if (pkg.contains("pocketagent", ignoreCase = true)) return

        val currentTime = System.currentTimeMillis()
        val delta = currentTime - lastRecordedTime

        if (delta > 1200L && recordedSteps.isNotEmpty()) {
            recordedSteps.add(MacroStep(action = "wait_ms", value = "1000"))
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source ?: return
                val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                val viewId = node.viewIdResourceName ?: ""

                if (text.isNotBlank()) recordedSteps.add(MacroStep(action = "click_text", target = text))
                else if (viewId.isNotBlank()) recordedSteps.add(MacroStep(action = "click_id", target = viewId))
                lastRecordedTime = System.currentTimeMillis()
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text?.firstOrNull()?.toString() ?: ""
                if (text.isNotBlank()) recordedSteps.add(MacroStep(action = "type_text", value = text))
                lastRecordedTime = System.currentTimeMillis()
            }
        }
    }
}
