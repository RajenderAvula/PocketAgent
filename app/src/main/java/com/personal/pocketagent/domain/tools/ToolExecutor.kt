package com.personal.pocketagent.domain.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.personal.pocketagent.automation.AgentAccessibilityService
import com.personal.pocketagent.automation.MacroRunner
import com.personal.pocketagent.data.backup.BackupManager
import com.personal.pocketagent.data.local.NoteDao
import com.personal.pocketagent.data.local.NoteEntity
import com.personal.pocketagent.domain.engine.HybridAgentRouter
import com.personal.pocketagent.domain.model.AgentAction

class ToolExecutor(
    private val context: Context,
    private val noteDao: NoteDao,
    private val router: HybridAgentRouter? = null
) {
    private val macroRunner = MacroRunner(context)
    private val backupManager = BackupManager(context)

    suspend fun execute(action: AgentAction, onStepProgress: (String) -> Unit = {}): String {
        if (action.tool == "run_macro" || action.steps.isNotEmpty()) {
            if (!AgentAccessibilityService.isRunning()) {
                return "Please enable 'UI Tap' Accessibility first so I can automate app routines."
            }
            val result = macroRunner.executeMacro(action.steps, onStepProgress)
            return result.fold(
                onSuccess = { action.spokenResponse },
                onFailure = { error -> error.message ?: "Macro execution failed." }
            )
        }

        return when (action.tool) {
            "set_alarm" -> {
                val hour = action.args["hour"]?.toIntOrNull() ?: return "Invalid hour."
                val minute = action.args["minute"]?.toIntOrNull() ?: 0
                val label = action.args["label"] ?: "Agent Alarm"
                setSystemAlarm(hour, minute, label)
                action.spokenResponse
            }
            "save_note" -> {
                val content = action.args["content"] ?: return "Note was empty."
                noteDao.insertNote(NoteEntity(content = content))
                action.spokenResponse
            }
            "list_notes" -> {
                val notes = noteDao.getAllNotes()
                if (notes.isEmpty()) "You have no saved notes."
                else "Your latest notes:\n" + notes.joinToString("\n") { "• ${it.content}" }
            }
            "open_app" -> {
                val appName = action.args["app_name"] ?: return "No app name specified."
                if (launchAppByName(appName)) action.spokenResponse
                else "Could not find app '$appName'."
            }
            "click_text" -> {
                val service = AgentAccessibilityService.instance ?: return "Accessibility is disabled."
                val target = action.args["target"] ?: return "Specify text to click."
                if (service.clickNodeByText(target)) action.spokenResponse
                else "Could not find '$target' on screen."
            }
            "type_text" -> {
                val service = AgentAccessibilityService.instance ?: return "Accessibility is disabled."
                val text = action.args["text"] ?: return "No text provided to type."
                if (service.inputTextIntoField(text)) action.spokenResponse
                else "No editable field found on screen."
            }
            "global_action" -> {
                val service = AgentAccessibilityService.instance ?: return "Accessibility is disabled."
                val command = action.args["command"] ?: "home"
                if (service.executeGlobalCommand(command)) action.spokenResponse
                else "Failed to execute '$command'."
            }
            "backup_data" -> {
                backupManager.exportToDownloadsFolder().getOrElse { "Backup failed: ${it.localizedMessage}" }
            }
            "ask_notes" -> {
                val rawQuery = action.args["query"] ?: return "Specify what to search for."
                val fullQuestion = action.args["full_question"] ?: rawQuery
                val ftsQuery = router?.buildFtsQuery(rawQuery) ?: "$rawQuery*"

                val matched = try {
                    noteDao.searchNotesFts(ftsQuery).ifEmpty { noteDao.searchNotesSubstring(rawQuery) }
                } catch (_: Exception) {
                    noteDao.searchNotesSubstring(rawQuery)
                }

                if (router != null) router.answerFromNotes(fullQuestion, matched)
                else if (matched.isEmpty()) "No notes matched '$rawQuery'."
                else "Matching notes:\n" + matched.joinToString("\n") { "• ${it.content}" }
            }
            "analyze_screen" -> {
                val service = AgentAccessibilityService.instance ?: return "Accessibility disabled."
                val screenDump = service.extractVisibleScreenText()
                if (screenDump.isBlank()) "No readable text found on screen."
                else "SCREEN_DUMP::$screenDump"
            }
            else -> action.spokenResponse
        }
    }

    private fun setSystemAlarm(hour: Int, minute: Int, message: String) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun launchAppByName(targetName: String): Boolean {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().contains(targetName, ignoreCase = true) } ?: return false
        val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
        launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(launchIntent)
        return true
    }
}
