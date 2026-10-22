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
import com.personal.pocketagent.playback.DocumentPlaybackController

class ToolExecutor(
    private val context: Context,
    private val noteDao: NoteDao,
    private val router: HybridAgentRouter? = null
) {
    private val macroRunner = MacroRunner(context)
    private val backupManager = BackupManager(context)

    suspend fun execute(
        action: AgentAction,
        onStepProgress: (String) -> Unit = {}
    ): String {
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
            "open_app" -> {
                val appName = action.args["app_name"] ?: return "No app specified to open."
                if (launchAppByName(appName)) action.spokenResponse
                else "Could not find an app named '$appName'."
            }

            "close_app" -> {
                val appName = (action.args["app_name"] ?: "this app").lowercase().trim()
                val service = AgentAccessibilityService.instance

                if (service != null) {
                    if (appName == "apps" || appName == "all apps" || appName == "all") {
                        service.executeGlobalCommand("recents")
                        "Opened recent apps so you can clear them."
                    } else if (appName == "app" || appName == "this app" || appName == "current app") {
                        service.executeGlobalCommand("back")
                        "Exited current screen."
                    } else {
                        // Return home to close foreground app
                        service.executeGlobalCommand("home")
                        "Closed $appName."
                    }
                } else {
                    // Fallback to launch Home Intent if Accessibility is not active
                    try {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(homeIntent)
                        "Closed $appName."
                    } catch (_: Exception) {
                        "Please enable 'UI Tap' Accessibility so PocketAgent can close apps."
                    }
                }
            }

            "doc_control" -> {
                val controller = DocumentPlaybackController.getInstance(context)
                val cmd = action.args["command"] ?: "pause"
                when (cmd) {
                    "pause" -> controller.pause()
                    "resume" -> controller.play()
                    "stop" -> controller.stop()
                    "next" -> controller.nextLine()
                    "previous" -> controller.previousLine()
                    "seek" -> {
                        val targetIndex = action.args["line"]?.toIntOrNull() ?: 0
                        controller.seekTo(targetIndex)
                        controller.play()
                    }
                }
                action.spokenResponse
            }

            "set_alarm" -> {
                val hour = action.args["hour"]?.toIntOrNull() ?: return "Invalid hour for alarm."
                val minute = action.args["minute"]?.toIntOrNull() ?: 0
                val label = action.args["label"] ?: "Agent Alarm"
                setSystemAlarm(hour, minute, label)
                action.spokenResponse
            }

            "save_note" -> {
                val content = action.args["content"] ?: return "Note content was empty."
                noteDao.insertNote(NoteEntity(content = content))
                action.spokenResponse
            }

            "list_notes" -> {
                val notes = noteDao.getAllNotes()
                if (notes.isEmpty()) "You have no saved notes."
                else "Your latest notes:\n" + notes.joinToString("\n") { "• ${it.content}" }
            }

            "click_text" -> {
                val service = AgentAccessibilityService.instance
                    ?: return "Accessibility Service is disabled. Enable 'UI Tap' first."
                val target = action.args["target"] ?: return "Specify text or button to click."
                if (service.clickNodeByText(target)) action.spokenResponse
                else "Could not find '$target' on screen."
            }

            "type_text" -> {
                val service = AgentAccessibilityService.instance
                    ?: return "Accessibility Service is disabled. Enable 'UI Tap' first."
                val text = action.args["text"] ?: return "No text provided to type."
                if (service.inputTextIntoField(text)) action.spokenResponse
                else "No editable field found on screen."
            }

            "global_action" -> {
                val service = AgentAccessibilityService.instance
                    ?: return "Accessibility Service is disabled. Enable 'UI Tap' first."
                val command = action.args["command"] ?: "home"
                if (service.executeGlobalCommand(command)) action.spokenResponse
                else "Failed to execute global action '$command'."
            }

            "backup_data" -> {
                backupManager.exportToDownloadsFolder().getOrElse {
                    "Backup failed: ${it.localizedMessage}"
                }
            }

            "ask_notes" -> {
                val rawQuery = action.args["query"] ?: return "Specify what to search for in your notes."
                val fullQuestion = action.args["full_question"] ?: rawQuery
                val ftsQuery = router?.buildFtsQuery(rawQuery) ?: "$rawQuery*"

                val matched = try {
                    noteDao.searchNotesFts(ftsQuery).ifEmpty {
                        noteDao.searchNotesSubstring(rawQuery)
                    }
                } catch (_: Exception) {
                    noteDao.searchNotesSubstring(rawQuery)
                }

                if (router != null) {
                    router.answerFromNotes(fullQuestion, matched)
                } else if (matched.isEmpty()) {
                    "No saved notes matched '$rawQuery'."
                } else {
                    "Matching notes:\n" + matched.joinToString("\n") { "• ${it.content}" }
                }
            }

            "analyze_screen" -> {
                val service = AgentAccessibilityService.instance
                    ?: return "Accessibility Service is disabled. Enable 'UI Tap' first."
                val screenDump = service.extractVisibleScreenText()
                if (screenDump.isBlank()) "No readable text found on the active screen."
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
        val cleanName = targetName.lowercase()
            .replace("'", "")
            .replace(" ", "")
            .trim()

        val packageManager = context.packageManager

        val knownPackages = mapOf(
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
            "youtube" to listOf("com.google.android.youtube"),
            "instagram" to listOf("com.instagram.android"),
            "chrome" to listOf("com.android.chrome"),
            "settings" to listOf("com.android.settings"),
            "spotify" to listOf("com.spotify.music"),
            "gmail" to listOf("com.google.android.gm"),
            "maps" to listOf("com.google.android.apps.maps")
        )

        knownPackages[cleanName]?.forEach { pkg ->
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                return true
            }
        }

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = packageManager.queryIntentActivities(mainIntent, 0)
        val matchedApp = apps.firstOrNull { resolveInfo ->
            val label = resolveInfo.loadLabel(packageManager).toString().lowercase().replace(" ", "")
            label.contains(cleanName) || cleanName.contains(label)
        }

        if (matchedApp != null) {
            val launchIntent = packageManager.getLaunchIntentForPackage(matchedApp.activityInfo.packageName)
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(launchIntent)
            return true
        }

        return false
    }
}
