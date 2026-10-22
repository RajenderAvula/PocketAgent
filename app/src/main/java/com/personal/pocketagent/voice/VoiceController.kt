package com.personal.pocketagent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.personal.pocketagent.data.local.AgentPrefs
import java.util.Locale

class VoiceController(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onListeningStateChange: (Boolean) -> Unit
) : TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = SpeechRecognizer.createSpeechRecognizer(context)
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isTtsReady = false
    private var speechRate: Float = AgentPrefs.getSpeechRate(context)

    init {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onListeningStateChange(true)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onListeningStateChange(false)
            override fun onError(error: Int) = onListeningStateChange(false)
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                onListeningStateChange(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) onResult(matches[0])
            }
        })
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate
        tts?.setSpeechRate(rate)
        AgentPrefs.setSpeechRate(context, rate)
    }

    fun startListening(preferOffline: Boolean) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }
        tts?.stop()
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        onListeningStateChange(false)
    }

    fun speak(text: String) {
        if (isTtsReady) {
            tts?.setSpeechRate(speechRate)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AGENT_UTTERANCE")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            tts?.setSpeechRate(speechRate)
            isTtsReady = true
        }
    }

    fun shutdown() {
        speechRecognizer?.destroy()
        tts?.shutdown()
    }
}
