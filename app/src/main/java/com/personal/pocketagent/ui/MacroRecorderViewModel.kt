package com.personal.pocketagent.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.pocketagent.automation.MacroRecorder
import com.personal.pocketagent.automation.MacroRunner
import com.personal.pocketagent.data.backup.BackupManager
import com.personal.pocketagent.data.local.AgentDatabase
import com.personal.pocketagent.data.local.MacroEntity
import com.personal.pocketagent.domain.model.MacroStep
import com.personal.pocketagent.widget.PocketAgentWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MacroRecorderViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AgentDatabase.get(application)
    private val macroDao = db.macroDao()
    private val backupManager = BackupManager(application)
    private val macroRunner = MacroRunner(application)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _savedMacros = MutableStateFlow<List<MacroEntity>>(emptyList())
    val savedMacros: StateFlow<List<MacroEntity>> = _savedMacros.asStateFlow()

    private val _stagedSteps = MutableStateFlow<List<MacroStep>>(emptyList())
    val stagedSteps: StateFlow<List<MacroStep>> = _stagedSteps.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init { loadSavedMacros() }

    fun loadSavedMacros() {
        viewModelScope.launch { _savedMacros.value = macroDao.getAllMacros() }
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            val steps = MacroRecorder.stopRecording()
            _stagedSteps.value = steps
            _isRecording.value = false
        } else {
            MacroRecorder.startRecording()
            _isRecording.value = true
        }
    }

    fun removeStagedStep(index: Int) {
        val current = _stagedSteps.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _stagedSteps.value = current
        }
    }

    fun testMacroSteps(steps: List<MacroStep>, onStatus: (String) -> Unit) {
        viewModelScope.launch {
            val result = macroRunner.executeMacro(steps) { progress -> onStatus(progress) }
            result.fold(
                onSuccess = { onStatus("✓ Preview test succeeded!") },
                onFailure = { onStatus("✗ Test failed: ${it.localizedMessage}") }
            )
        }
    }

    fun saveMacro(triggerPhrase: String, steps: List<MacroStep>) {
        if (triggerPhrase.isBlank() || steps.isEmpty()) return
        viewModelScope.launch {
            val json = Json.encodeToString(steps)
            macroDao.insertMacro(MacroEntity(triggerPhrase = triggerPhrase.lowercase().trim(), stepsJson = json))
            loadSavedMacros()
            _stagedSteps.value = emptyList()
            _statusMessage.value = "Saved routine: '$triggerPhrase'"
            PocketAgentWidget.refreshWidgets(getApplication())
        }
    }

    fun deleteMacro(triggerPhrase: String) {
        viewModelScope.launch {
            macroDao.deleteMacro(triggerPhrase)
            loadSavedMacros()
            PocketAgentWidget.refreshWidgets(getApplication())
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val result = backupManager.exportToUri(uri)
            _statusMessage.value = result.fold(
                onSuccess = { "✓ $it" },
                onFailure = { "✗ Export failed: ${it.localizedMessage}" }
            )
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            val result = backupManager.importFromUri(uri)
            _statusMessage.value = result.fold(
                onSuccess = {
                    loadSavedMacros()
                    PocketAgentWidget.refreshWidgets(getApplication())
                    "✓ $it"
                },
                onFailure = { "✗ Import failed: ${it.localizedMessage}" }
            )
        }
    }

    fun clearStatusMessage() { _statusMessage.value = null }
}
