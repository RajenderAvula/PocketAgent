package com.personal.pocketagent.data.local

import android.content.Context

object AgentPrefs {
    private const val PREFS_NAME = "pocket_agent_prefs"
    private const val KEY_BUBBLE_ENABLED = "bubble_enabled"
    private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_SPEECH_RATE = "tts_speech_rate"
    private const val KEY_CUSTOM_WAKE_WORDS = "custom_wake_words"
    private const val DEFAULT_WAKE_WORDS = "hey agent, okay agent, pocket agent, hey man, dude, man"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setBubbleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()
    }

    fun isBubbleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BUBBLE_ENABLED, false)

    fun setWakeWordEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKE_WORD_ENABLED, enabled).apply()
    }

    fun isWakeWordEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WAKE_WORD_ENABLED, false)

    fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply()
    }

    fun isAutoStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, true)

    fun getSpeechRate(context: Context): Float =
        prefs(context).getFloat(KEY_SPEECH_RATE, 0.90f)

    fun setSpeechRate(context: Context, rate: Float) {
        prefs(context).edit().putFloat(KEY_SPEECH_RATE, rate).apply()
    }

    fun getCustomWakeWordsRaw(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_WAKE_WORDS, DEFAULT_WAKE_WORDS) ?: DEFAULT_WAKE_WORDS

    fun setCustomWakeWordsRaw(context: Context, rawWords: String) {
        prefs(context).edit().putString(KEY_CUSTOM_WAKE_WORDS, rawWords).apply()
    }

    fun getCustomWakeWordsList(context: Context): List<String> {
        val raw = getCustomWakeWordsRaw(context)
        return raw.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .sortedByDescending { it.length }
    }
}
