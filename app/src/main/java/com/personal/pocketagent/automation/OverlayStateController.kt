package com.personal.pocketagent.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class OverlayUiState(
    val isListening: Boolean = false,
    val isMacroRunning: Boolean = false,
    val isAnalyzingScreen: Boolean = false,
    val statusText: String = "Tap Mic or Eye",
    val stepProgress: String = "",
    val screenSummary: String = ""
)

object OverlayStateController {
    private val _uiState = MutableStateFlow(OverlayUiState())
    val uiState: StateFlow<OverlayUiState> = _uiState.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)

    fun onListeningChanged(listening: Boolean) {
        _uiState.value = _uiState.value.copy(
            isListening = listening,
            statusText = if (listening) "Listening..." else _uiState.value.statusText
        )
    }

    fun onScreenAnalysisStarted() {
        _uiState.value = _uiState.value.copy(
            isListening = false,
            isAnalyzingScreen = true,
            statusText = "Reading screen...",
            screenSummary = ""
        )
    }

    fun onScreenAnalysisResult(summary: String, modeBadge: String) {
        _uiState.value = _uiState.value.copy(
            isAnalyzingScreen = false,
            statusText = "Screen Read ($modeBadge)",
            screenSummary = summary
        )
    }

    fun clearScreenSummary() {
        _uiState.value = _uiState.value.copy(screenSummary = "", statusText = "Tap Mic or Eye")
    }

    fun updateStatus(message: String) {
        _uiState.value = _uiState.value.copy(isListening = false, isAnalyzingScreen = false, statusText = message)
    }

    fun onMacroStarted(totalSteps: Int) {
        cancelRequested.set(false)
        _uiState.value = OverlayUiState(
            isListening = false,
            isMacroRunning = true,
            statusText = "Running Macro...",
            stepProgress = "0/$totalSteps"
        )
    }

    fun onStepUpdate(stepIndex: Int, totalSteps: Int, description: String) {
        _uiState.value = _uiState.value.copy(
            isListening = false,
            isMacroRunning = true,
            statusText = description,
            stepProgress = "${stepIndex + 1}/$totalSteps"
        )
    }

    fun onMacroFinished(summary: String) {
        cancelRequested.set(false)
        _uiState.value = _uiState.value.copy(
            isListening = false,
            isMacroRunning = false,
            statusText = summary,
            stepProgress = ""
        )
    }

    fun requestEmergencyStop() {
        cancelRequested.set(true)
        _uiState.value = _uiState.value.copy(
            isListening = false,
            isMacroRunning = false,
            isAnalyzingScreen = false,
            statusText = "Aborted",
            stepProgress = ""
        )
    }

    fun isCancelRequested(): Boolean = cancelRequested.get()
}
