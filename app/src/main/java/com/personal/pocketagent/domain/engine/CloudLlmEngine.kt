package com.personal.pocketagent.domain.engine

import com.personal.pocketagent.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class CloudLlmEngine {
    private val client = OkHttpClient()

    suspend fun generateJsonAction(systemPrompt: String, userPrompt: String): String =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.CLOUD_API_KEY
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

            val payload = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", "$systemPrompt\n\nUser request: $userPrompt")
                    ))
                ))
                put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            }

            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: throw Exception("Empty response")
                val json = JSONObject(body)
                json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            }
        }
}
