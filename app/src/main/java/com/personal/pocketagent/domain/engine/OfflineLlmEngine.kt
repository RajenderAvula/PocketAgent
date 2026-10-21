package com.personal.pocketagent.domain.engine

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

class OfflineLlmEngine(private val context: Context) {
    private var llmInference: LlmInference? = null
    private val modelFile = File(context.filesDir, "gemma-2b-it-int4.bin")

    fun initializeIfModelExists(): Boolean {
        if (llmInference != null) return true
        if (!modelFile.exists()) return false

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(512)
            .setTemperature(0.3f)
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
        return true
    }

    fun generate(prompt: String): String {
        val engine = llmInference ?: return ""
        return engine.generateResponse(prompt)
    }
}
