package com.personal.pocketagent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.personal.pocketagent.data.local.AgentDatabase
import com.personal.pocketagent.data.local.AgentPrefs
import com.personal.pocketagent.data.network.NetworkMonitor
import com.personal.pocketagent.domain.engine.CloudLlmEngine
import com.personal.pocketagent.domain.engine.HybridAgentRouter
import com.personal.pocketagent.domain.engine.OfflineLlmEngine
import com.personal.pocketagent.domain.tools.ToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class WakeWordService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isDestroyed = false

    private lateinit var db: AgentDatabase
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var router: HybridAgentRouter
    private lateinit var toolExecutor: ToolExecutor

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        AgentPrefs.setWakeWordEnabled(this, true)

        db = AgentDatabase.get(applicationContext)
        networkMonitor = NetworkMonitor(applicationContext)
        router = HybridAgentRouter(networkMonitor, CloudLlmEngine(), OfflineLlmEngine(applicationContext))
        toolExecutor = ToolExecutor(applicationContext, db.noteDao(), router)

        tts = TextToSpeech(applicationContext, this)
        startForegroundNotification()
        initContinuousRecognizer()
    }

    private fun startForegroundNotification() {
        val channelId = "pocket_agent_wake_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "PocketAgent Wake Listener", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PocketAgent Active")
            .setContentText("Say your trigger word followed by your command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun initContinuousRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onError(error: Int) { restartListeningWithDelay(800L) }

                override fun onResults(results: Bundle?) {
                    val rawSpoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""

                    val cleanSpoken = rawSpoken.lowercase()
                        .replace(",", " ")
                        .replace(".", " ")
                        .replace("?", " ")
                        .replace("what's app", "whatsapp")
                        .replace("whats app", "whatsapp")
                        .replace(Regex("\\s+"), " ")
                        .trim()

                    val wakePhrases = AgentPrefs.getCustomWakeWordsList(this@WakeWordService)
                    val wakeRegex = Regex(
                        "\\b(" + wakePhrases.joinToString("|") { Regex.escape(it) } + ")\\b",
                        RegexOption.IGNORE_CASE
                    )

                    val match = wakeRegex.find(cleanSpoken)
                    if (match != null) {
                        val command = cleanSpoken.substring(match.range.last + 1).trim()
                        if (command.isNotEmpty()) {
                            executeVoiceCommand(command)
                        } else {
                            speak("Yes? How can I help?")
                        }
                    }
                    restartListeningWithDelay(400L)
                }
            })
        }
        startListeningCycle()
    }

    private fun startListeningCycle() {
        if (isDestroyed) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !networkMonitor.isOnline())
        }
        try { speechRecognizer?.startListening(intent) }
        catch (_: Exception) { restartListeningWithDelay(1500L) }
    }

    private fun restartListeningWithDelay(delayMs: Long) {
        if (isDestroyed) return
        mainHandler.postDelayed({ startListeningCycle() }, delayMs)
    }

    private fun executeVoiceCommand(command: String) {
        serviceScope.launch {
            val (action, _) = router.routeInput(command, db.macroDao())
            val resultText = toolExecutor.execute(action)
            speak(resultText)
        }
    }

    private fun speak(text: String) {
        if (isTtsReady) {
            val rate = AgentPrefs.getSpeechRate(this)
            tts?.setSpeechRate(rate)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "WAKE_AGENT_REPLY")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            val rate = AgentPrefs.getSpeechRate(this)
            tts?.setSpeechRate(rate)
            isTtsReady = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isDestroyed = true
        isRunning = false
        AgentPrefs.setWakeWordEnabled(this, false)
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile var isRunning: Boolean = false
            private set
    }
}
