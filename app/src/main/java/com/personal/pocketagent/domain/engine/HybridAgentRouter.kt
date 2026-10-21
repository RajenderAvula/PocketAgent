package com.personal.pocketagent.domain.engine

import com.personal.pocketagent.data.local.MacroDao
import com.personal.pocketagent.data.local.NoteEntity
import com.personal.pocketagent.data.network.NetworkMonitor
import com.personal.pocketagent.domain.model.AgentAction
import com.personal.pocketagent.domain.model.MacroStep
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HybridAgentRouter(
    private val networkMonitor: NetworkMonitor,
    private val cloudEngine: CloudLlmEngine,
    private val offlineEngine: OfflineLlmEngine
) {
    private val jsonParser = Json { ignoreUnknownKeys = true }

    private val systemPrompt = """
        You are a personal Android agent with multi-step screen macro automation. Respond ONLY in strict JSON:
        {
          "tool": "run_macro|set_alarm|save_note|list_notes|open_app|click_text|type_text|global_action|chat_reply|ask_notes|analyze_screen",
          "args": {"key": "value"},
          "steps": [
            {"action": "open_app|click_text|click_id|type_text|wait_ms|global_action|ime_enter", "target": "...", "value": "...", "timeoutMs": 6000}
          ],
          "spokenResponse": "concise reply"
        }
    """.trimIndent()

    suspend fun routeInput(input: String, macroDao: MacroDao? = null): Pair<AgentAction, String> {
        val trimmed = input.lowercase().trim()

        if (macroDao != null) {
            val customMacro = macroDao.getMacro(trimmed)
            if (customMacro != null) {
                val steps = jsonParser.decodeFromString<List<MacroStep>>(customMacro.stepsJson)
                return AgentAction(
                    tool = "run_macro",
                    steps = steps,
                    spokenResponse = "Running custom routine: ${customMacro.triggerPhrase}"
                ) to "CUSTOM-MACRO"
            }
        }

        parseFastReflex(input)?.let { return it to "OFFLINE-MACRO" }

        if (networkMonitor.isOnline()) {
            try {
                val rawJson = cloudEngine.generateJsonAction(systemPrompt, input)
                val action = jsonParser.decodeFromString<AgentAction>(rawJson)
                return action to "ONLINE-CLOUD"
            } catch (_: Exception) {}
        }

        if (offlineEngine.initializeIfModelExists()) {
            val rawResponse = offlineEngine.generate("$systemPrompt\nUser: $input\nJSON:")
            return try {
                val cleanJson = rawResponse.substringAfter("{").substringBeforeLast("}")
                val action = jsonParser.decodeFromString<AgentAction>("{$cleanJson}")
                action to "OFFLINE-LLM"
            } catch (_: Exception) {
                AgentAction("chat_reply", emptyMap(), emptyList(), rawResponse.trim()) to "OFFLINE-LLM"
            }
        }

        return AgentAction(
            tool = "chat_reply",
            spokenResponse = "Offline LLM is not downloaded yet. You can use macros, save notes, search notes, or tap 'Download Offline Model'."
        ) to "OFFLINE-FALLBACK"
    }

    private fun parseFastReflex(input: String): AgentAction? {
        val trimmed = input.trim()
        val lower = trimmed.lowercase()

        val searchNotesRegex = Regex(
            "^(?:search\\s+(?:my\\s+)?notes\\s+(?:for|about)|find\\s+(?:in\\s+)?(?:my\\s+)?notes\\s+(?:about|for|on)?|what\\s+did\\s+i\\s+(?:note|save|write)\\s+about|ask\\s+(?:my\\s+)?notes)\\s+(.+)",
            RegexOption.IGNORE_CASE
        )
        searchNotesRegex.find(trimmed)?.let { match ->
            val rawTopic = match.groupValues[1].trim()
            return AgentAction(
                tool = "ask_notes",
                args = mapOf("query" to rawTopic, "full_question" to trimmed),
                spokenResponse = "Searching your notes for $rawTopic."
            )
        }

        val screenQueryRegex = Regex(
            "^(?:summarize|read|explain|analyze|what is on|solve|answer)\\s+(?:this\\s+|my\\s+|the\\s+)?(?:screen|page|question)(.*)",
            RegexOption.IGNORE_CASE
        )
        screenQueryRegex.find(trimmed)?.let { match ->
            val extraFocus = match.groupValues[1].trim()
            val prompt = if (extraFocus.isNotEmpty()) "Analyze the screen and $extraFocus"
            else "Summarize key points or solve any question on this screen."
            return AgentAction(
                tool = "analyze_screen",
                args = mapOf("instruction" to prompt),
                spokenResponse = "Analyzing your screen."
            )
        }

        val waRegex = Regex(
            "(?:send\\s+)?(?:a\\s+)?whatsapp\\s+(?:message\\s+)?(?:to\\s+)?(.+?)\\s+(?:saying|that|with message)\\s+(.+)",
            RegexOption.IGNORE_CASE
        )
        waRegex.find(trimmed)?.let { match ->
            return buildWhatsAppMacro(match.groupValues[1].trim(), match.groupValues[2].trim())
        }

        val ytRegex = Regex(
            "(?:search youtube for|play\\s+(.+?)\\s+on youtube)(?:\\s*(.+))?",
            RegexOption.IGNORE_CASE
        )
        ytRegex.find(trimmed)?.let { match ->
            val query = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
            if (query.isNotEmpty()) {
                return AgentAction(
                    tool = "run_macro",
                    steps = listOf(
                        MacroStep(action = "open_app", target = "YouTube"),
                        MacroStep(action = "click_text", target = "Search", timeoutMs = 6000L),
                        MacroStep(action = "type_text", target = "Search", value = query, timeoutMs = 5000L),
                        MacroStep(action = "ime_enter")
                    ),
                    spokenResponse = "Searching YouTube for $query."
                )
            }
        }

        if (lower.contains(" and then ")) {
            val parts = trimmed.split(Regex("\\s+and then\\s+", RegexOption.IGNORE_CASE))
            val chainedSteps = parts.mapNotNull { singleCommandToStep(it.trim()) }
            if (chainedSteps.size >= 2) {
                return AgentAction(
                    tool = "run_macro",
                    steps = chainedSteps,
                    spokenResponse = "Running ${chainedSteps.size}-step macro."
                )
            }
        }

        if (lower.contains("backup") || lower.contains("back up") || lower.contains("export macro") || lower.contains("export data")) {
            return AgentAction(tool = "backup_data", spokenResponse = "Backing up your data to Downloads.")
        }

        val clickRegex = Regex("^(?:click|tap|press)\\s+(.+)", RegexOption.IGNORE_CASE)
        clickRegex.find(trimmed)?.let {
            return AgentAction("click_text", mapOf("target" to it.groupValues[1].trim()), spokenResponse = "Clicking ${it.groupValues[1].trim()}.")
        }

        val typeRegex = Regex("^(?:type|enter text)\\s+(.+)", RegexOption.IGNORE_CASE)
        typeRegex.find(trimmed)?.let {
            return AgentAction("type_text", mapOf("text" to it.groupValues[1].trim()), spokenResponse = "Typing ${it.groupValues[1].trim()}.")
        }

        when {
            lower == "go home" || lower == "home screen" -> return AgentAction("global_action", mapOf("command" to "home"), spokenResponse = "Going home.")
            lower == "go back" || lower == "navigate back" -> return AgentAction("global_action", mapOf("command" to "back"), spokenResponse = "Going back.")
            lower.contains("scroll down") -> return AgentAction("global_action", mapOf("command" to "scroll_down"), spokenResponse = "Scrolling down.")
            lower.contains("scroll up") -> return AgentAction("global_action", mapOf("command" to "scroll_up"), spokenResponse = "Scrolling up.")
            lower.contains("open notifications") -> return AgentAction("global_action", mapOf("command" to "notifications"), spokenResponse = "Opening notifications.")
        }

        if (lower.startsWith("open ")) {
            val app = trimmed.substring(5).trim()
            return AgentAction("open_app", mapOf("app_name" to app), spokenResponse = "Opening $app.")
        }

        val noteRegex = Regex("^(?:save note|note|remember to|remember)\\s+(.+)", RegexOption.IGNORE_CASE)
        noteRegex.find(trimmed)?.let {
            return AgentAction("save_note", mapOf("content" to it.groupValues[1]), spokenResponse = "Saved to your notes.")
        }

        if (lower.contains("show notes") || lower.contains("list notes") || lower.contains("my notes")) {
            return AgentAction("list_notes", emptyMap(), spokenResponse = "Fetching your notes.")
        }

        val alarmRegex = Regex("alarm for (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)
        alarmRegex.find(lower)?.let { match ->
            var hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3]
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            return AgentAction(
                tool = "set_alarm",
                args = mapOf("hour" to hour.toString(), "minute" to minute.toString(), "label" to "Voice Alarm"),
                spokenResponse = "Setting an alarm for $hour:${minute.toString().padStart(2, '0')}."
            )
        }

        return null
    }

    private fun buildWhatsAppMacro(contactName: String, messageBody: String): AgentAction {
        return AgentAction(
            tool = "run_macro",
            steps = listOf(
                MacroStep("open_app", target = "WhatsApp"),
                MacroStep("click_text", target = "Search", timeoutMs = 6000L),
                MacroStep("type_text", target = "Search", value = contactName, timeoutMs = 5000L),
                MacroStep("wait_ms", value = "800"),
                MacroStep("click_text", target = contactName, value = "skip_editable", timeoutMs = 5000L),
                MacroStep("type_text", target = "Message", value = messageBody, timeoutMs = 5000L),
                MacroStep("click_text", target = "Send", timeoutMs = 4000L)
            ),
            spokenResponse = "Sent WhatsApp message to $contactName."
        )
    }

    private fun singleCommandToStep(cmd: String): MacroStep? {
        val lower = cmd.lowercase()
        return when {
            lower.startsWith("open ") -> MacroStep("open_app", target = cmd.substring(5).trim())
            lower.startsWith("click ") -> MacroStep("click_text", target = cmd.substring(6).trim())
            lower.startsWith("tap ") -> MacroStep("click_text", target = cmd.substring(4).trim())
            lower.startsWith("type ") -> MacroStep("type_text", value = cmd.substring(5).trim())
            lower.startsWith("wait ") -> MacroStep("wait_ms", value = "1500")
            lower == "scroll down" -> MacroStep("global_action", target = "scroll_down")
            lower == "go back" -> MacroStep("global_action", target = "back")
            else -> null
        }
    }

    fun buildFtsQuery(rawInput: String): String {
        val stopWords = setOf("what", "when", "where", "which", "who", "whom", "this", "that", "these", "those", "have", "from", "with", "about", "into", "note", "notes", "saved", "save", "tell", "show", "find", "search", "summary", "screen", "the", "and", "for")
        val tokens = rawInput.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").split(Regex("\\s+")).filter { it.length >= 3 && it !in stopWords }.distinct()
        return if (tokens.isEmpty()) rawInput.replace(Regex("[^a-zA-Z0-9]"), "") + "*" else tokens.joinToString(" OR ") { "$it*" }
    }

    suspend fun answerFromNotes(userQuestion: String, matchedNotes: List<NoteEntity>): String {
        if (matchedNotes.isEmpty()) return "I couldn't find any saved notes or screen summaries matching '$userQuestion'."
        val contextBlock = matchedNotes.mapIndexed { idx, note ->
            val dateStr = SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(note.timestamp))
            "[Note ${idx + 1} - $dateStr]: ${note.content}"
        }.joinToString("\n\n")

        val ragPrompt = "Answer using ONLY these notes:\n$contextBlock\n\nQuestion: $userQuestion"

        if (networkMonitor.isOnline()) {
            try {
                val rawJson = cloudEngine.generateJsonAction(
                    systemPrompt = """Respond in JSON: {"tool": "chat_reply", "args": {}, "spokenResponse": "concise answer"}""",
                    userPrompt = ragPrompt
                )
                val action = jsonParser.decodeFromString<AgentAction>(rawJson)
                return "${action.spokenResponse}\n\n📚 Matched ${matchedNotes.size} note(s)."
            } catch (_: Exception) {}
        }

        if (offlineEngine.initializeIfModelExists()) {
            try {
                val reply = offlineEngine.generate("$ragPrompt\nAnswer:")
                if (reply.isNotBlank()) return "${reply.trim()}\n\n📚 Matched ${matchedNotes.size} note(s) (Offline LLM)."
            } catch (_: Exception) {}
        }

        return "Found ${matchedNotes.size} matching note(s):\n\n" + matchedNotes.joinToString("\n\n") { "• ${it.content}" }
    }

    suspend fun analyzeScreenContent(screenText: String, userInstruction: String): Pair<String, String> {
        if (screenText.isBlank()) return "Could not read any text from the current screen." to "ERROR"
        val combinedPrompt = "Task: $userInstruction\n\nVisible Screen Text:\n$screenText"

        if (networkMonitor.isOnline()) {
            try {
                val rawJson = cloudEngine.generateJsonAction(
                    systemPrompt = """Respond in JSON: {"tool": "chat_reply", "args": {}, "spokenResponse": "clear summary"}""",
                    userPrompt = combinedPrompt
                )
                val action = jsonParser.decodeFromString<AgentAction>(rawJson)
                return action.spokenResponse to "ONLINE-CLOUD"
            } catch (_: Exception) {}
        }

        if (offlineEngine.initializeIfModelExists()) {
            return try {
                offlineEngine.generate("$combinedPrompt\n\nSummary:").trim() to "OFFLINE-LLM"
            } catch (_: Exception) {
                fallbackScreenExtract(screenText) to "OFFLINE-EXTRACTIVE"
            }
        }

        return fallbackScreenExtract(screenText) to "OFFLINE-EXTRACTIVE"
    }

    private fun fallbackScreenExtract(screenText: String): String {
        val lines = screenText.lines().filter { it.isNotBlank() }
        return "Screen Snapshot (${lines.size} lines):\n" + lines.take(8).joinToString(" • ")
    }
}
