package com.personal.pocketagent.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MacroStep(
    val action: String,
    val target: String = "",
    val value: String = "",
    val timeoutMs: Long = 6000L
)

@Serializable
data class AgentAction(
    val tool: String,
    val args: Map<String, String> = emptyMap(),
    val steps: List<MacroStep> = emptyList(),
    val spokenResponse: String
)

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val isUser: Boolean,
    val modeBadge: String = ""
)
