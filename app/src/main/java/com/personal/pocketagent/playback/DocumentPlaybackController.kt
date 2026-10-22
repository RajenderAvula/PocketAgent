package com.personal.pocketagent.playback

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.personal.pocketagent.data.local.AgentPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

enum class PlaybackState {
    IDLE,
    PLAYING,
    PAUSED,
    STOPPED
}

data class DocumentPlayerState(
    val title: String = "",
    val lines: List<String> = emptyList(),
    val currentLineIndex: Int = 0,
    val state: PlaybackState = PlaybackState.IDLE
) {
    val totalLines: Int get() = lines.size
    val currentLineText: String
        get() = if (currentLineIndex in lines.indices) lines[currentLineIndex] else ""
}

class DocumentPlaybackController private constructor(private val context: Context) : TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isTtsReady = false

    private val _playerState = MutableStateFlow(DocumentPlayerState())
    val playerState: StateFlow<DocumentPlayerState> = _playerState.asStateFlow()

    init {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                scope.launch {
                    val current = _playerState.value
                    if (current.state == PlaybackState.PLAYING) {
                        val nextIndex = current.currentLineIndex + 1
                        if (nextIndex < current.totalLines) {
                            _playerState.value = current.copy(currentLineIndex = nextIndex)
                            speakLine(nextIndex)
                        } else {
                            _playerState.value = current.copy(
                                currentLineIndex = current.totalLines - 1,
                                state = PlaybackState.STOPPED
                            )
                        }
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                scope.launch { pause() }
            }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            val rate = AgentPrefs.getSpeechRate(context)
            tts?.setSpeechRate(rate)
            isTtsReady = true
        }
    }

    fun loadDocument(title: String, rawText: String, startPosition: Int = 0) {
        stop()
        val parsedLines = splitIntoReadableLines(rawText)
        val safeStart = startPosition.coerceIn(0, (parsedLines.size - 1).coerceAtLeast(0))

        _playerState.value = DocumentPlayerState(
            title = title,
            lines = parsedLines,
            currentLineIndex = safeStart,
            state = PlaybackState.IDLE
        )
    }

    fun play(fromIndex: Int? = null) {
        if (!isTtsReady) return
        val current = _playerState.value
        if (current.lines.isEmpty()) return

        val targetIndex = (fromIndex ?: current.currentLineIndex).coerceIn(0, current.totalLines - 1)
        _playerState.value = current.copy(
            currentLineIndex = targetIndex,
            state = PlaybackState.PLAYING
        )
        speakLine(targetIndex)
    }

    fun pause() {
        tts?.stop()
        _playerState.value = _playerState.value.copy(state = PlaybackState.PAUSED)
    }

    fun stop() {
        tts?.stop()
        _playerState.value = _playerState.value.copy(
            currentLineIndex = 0,
            state = PlaybackState.STOPPED
        )
    }

    fun seekTo(index: Int) {
        val current = _playerState.value
        if (current.lines.isEmpty()) return
        val safeIndex = index.coerceIn(0, current.totalLines - 1)
        val wasPlaying = current.state == PlaybackState.PLAYING

        tts?.stop()
        _playerState.value = current.copy(currentLineIndex = safeIndex)

        if (wasPlaying) {
            speakLine(safeIndex)
        }
    }

    fun nextLine() {
        val current = _playerState.value
        if (current.currentLineIndex < current.totalLines - 1) {
            seekTo(current.currentLineIndex + 1)
        }
    }

    fun previousLine() {
        val current = _playerState.value
        if (current.currentLineIndex > 0) {
            seekTo(current.currentLineIndex - 1)
        }
    }

    private fun speakLine(index: Int) {
        val lines = _playerState.value.lines
        if (index !in lines.indices) return

        val lineText = lines[index]
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val rate = AgentPrefs.getSpeechRate(context)
        tts?.setSpeechRate(rate)

        tts?.speak(
            lineText,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "DOC_LINE_$index"
        )
    }

    private fun splitIntoReadableLines(rawText: String): List<String> {
        val result = mutableListOf<String>()
        val rawParagraphs = rawText.split(Regex("[\r\n]+"))

        for (para in rawParagraphs) {
            val cleanPara = para.trim()
            if (cleanPara.isBlank()) continue

            if (cleanPara.length <= 140) {
                result.add(cleanPara)
            } else {
                val sentences = cleanPara.split(Regex("(?<=[.!?])\\s+"))
                for (s in sentences) {
                    val cleanS = s.trim()
                    if (cleanS.isNotBlank()) {
                        result.add(cleanS)
                    }
                }
            }
        }
        return if (result.isEmpty()) listOf("Document contains no readable text.") else result
    }

    companion object {
        @Volatile private var INSTANCE: DocumentPlaybackController? = null

        fun getInstance(context: Context): DocumentPlaybackController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DocumentPlaybackController(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
