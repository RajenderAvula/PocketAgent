package com.personal.pocketagent.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.pocketagent.automation.AgentAccessibilityService
import com.personal.pocketagent.data.local.AgentDatabase
import com.personal.pocketagent.data.network.DownloadState
import com.personal.pocketagent.data.network.ModelDownloader
import com.personal.pocketagent.data.network.NetworkMonitor
import com.personal.pocketagent.domain.engine.CloudLlmEngine
import com.personal.pocketagent.domain.engine.HybridAgentRouter
import com.personal.pocketagent.domain.engine.OfflineLlmEngine
import com.personal.pocketagent.domain.model.ChatMessage
import com.personal.pocketagent.domain.tools.ToolExecutor
import com.personal.pocketagent.overlay.FloatingOverlayService
import com.personal.pocketagent.voice.VoiceController
import com.personal.pocketagent.voice.WakeWordService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val db = AgentDatabase.get(appContext)
    private val networkMonitor = NetworkMonitor(appContext)
    private val modelDownloader = ModelDownloader(appContext)

    private val router = HybridAgentRouter(
        networkMonitor = networkMonitor,
        cloudEngine = CloudLlmEngine(),
        offlineEngine = OfflineLlmEngine(appContext)
    )
    private val toolExecutor = ToolExecutor(appContext, db.noteDao(), router)

    val downloadState: StateFlow<DownloadState> = modelDownloader.state

    private val _wakeServiceEnabled = MutableStateFlow(WakeWordService.isRunning)
    val wakeServiceEnabled: StateFlow<Boolean> = _wakeServiceEnabled.asStateFlow()

    private val _accessibilityEnabled = MutableStateFlow(AgentAccessibilityService.isRunning())
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled.asStateFlow()

    private val _overlayEnabled = MutableStateFlow(FloatingOverlayService.isRunning)
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    private val _messages = MutableStateFlow(
        listOf(ChatMessage(text = "PocketAgent Ready.\n• Try: 'Send WhatsApp message to Mom saying I am on my way', 'Find notes about physics', or 'Summarize this screen'.", isUser = false, modeBadge = "SYSTEM"))
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val voiceController = VoiceController(
        context = appContext,
        onResult = { spokenText -> submitQuery(spokenText, speakReply = true) },
        onListeningStateChange = { listening -> _isListening.value = listening }
    )

    fun refreshSystemStatus() {
        _accessibilityEnabled.value = AgentAccessibilityService.isRunning()
        _wakeServiceEnabled.value = WakeWordService.isRunning
        _overlayEnabled.value = FloatingOverlayService.isRunning
    }

    fun startOfflineModelDownload(customUrl: String = ModelDownloader.DEFAULT_MODEL_URL, hfToken: String? = null) {
        viewModelScope.launch { modelDownloader.downloadModel(customUrl, hfToken) }
    }

    fun toggleWakeWordService() {
        val serviceIntent = Intent(appContext, WakeWordService::class.java)
        if (_wakeServiceEnabled.value) {
            appContext.stopService(serviceIntent)
            _wakeServiceEnabled.value = false
        } else {
            ContextCompat.startForegroundService(appContext, serviceIntent)
            _wakeServiceEnabled.value = true
        }
    }

    fun toggleOverlayService() {
        val intent = Intent(appContext, FloatingOverlayService::class.java)
        if (_overlayEnabled.value) {
            appContext.stopService(intent)
            _overlayEnabled.value = false
        } else {
            ContextCompat.startForegroundService(appContext, intent)
            _overlayEnabled.value = true
        }
    }

    fun toggleDictation() {
        if (_isListening.value) voiceController.stopListening()
        else voiceController.startListening(preferOffline = !networkMonitor.isOnline())
    }

    fun submitQuery(query: String, speakReply: Boolean = false) {
        if (query.isBlank()) return
        _messages.value = _messages.value + ChatMessage(text = query, isUser = true)

        viewModelScope.launch {
            val (action, modeBadge) = router.routeInput(query, db.macroDao())
            val progressMsgId = System.currentTimeMillis() + 1L

            if (action.tool == "run_macro" || action.steps.isNotEmpty()) {
                _messages.value = _messages.value + ChatMessage(
                    id = progressMsgId,
                    text = "Starting macro (${action.steps.size} steps)...",
                    isUser = false,
                    modeBadge = modeBadge
                )
            }

            val finalOutput = toolExecutor.execute(action) { stepStatus ->
                _messages.value = _messages.value.map { msg ->
                    if (msg.id == progressMsgId) msg.copy(text = "⚡ $stepStatus") else msg
                }
            }

            if (action.tool == "run_macro" || action.steps.isNotEmpty()) {
                _messages.value = _messages.value.map { msg ->
                    if (msg.id == progressMsgId) msg.copy(text = "✓ $finalOutput") else msg
                }
            } else {
                _messages.value = _messages.value + ChatMessage(text = finalOutput, isUser = false, modeBadge = modeBadge)
            }

            if (speakReply) voiceController.speak(finalOutput)
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceController.shutdown()
    }
}
