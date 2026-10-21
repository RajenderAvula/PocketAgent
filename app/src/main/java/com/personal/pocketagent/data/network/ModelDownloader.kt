package com.personal.pocketagent.data.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class DownloadState {
    object NotDownloaded : DownloadState()
    data class Downloading(val progressPercent: Int, val downloadedMb: Long, val totalMb: Long) : DownloadState()
    object Ready : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val targetFile = File(context.filesDir, MODEL_FILENAME)
    private val tempFile = File(context.filesDir, "$MODEL_FILENAME.part")

    private val _state = MutableStateFlow<DownloadState>(
        if (targetFile.exists() && targetFile.length() > 10_000_000L) DownloadState.Ready
        else DownloadState.NotDownloaded
    )
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    suspend fun downloadModel(modelUrl: String = DEFAULT_MODEL_URL, authToken: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
                val requestBuilder = Request.Builder().url(modelUrl)

                if (existingBytes > 0L) {
                    requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                }
                if (!authToken.isNullOrBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $authToken")
                }

                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        _state.value = DownloadState.Error("HTTP ${response.code}: Check URL or token")
                        return@withContext
                    }

                    val body = response.body ?: run {
                        _state.value = DownloadState.Error("Empty response body")
                        return@withContext
                    }

                    val isResuming = response.code == 206
                    val contentLength = body.contentLength()
                    val totalBytes = if (isResuming) existingBytes + contentLength else contentLength

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile, isResuming).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            var totalRead = if (isResuming) existingBytes else 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead

                                val percent = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt() else 0
                                _state.value = DownloadState.Downloading(
                                    progressPercent = percent,
                                    downloadedMb = totalRead / (1024 * 1024),
                                    totalMb = if (totalBytes > 0) totalBytes / (1024 * 1024) else 0L
                                )
                            }
                            output.flush()
                        }
                    }

                    if (targetFile.exists()) targetFile.delete()
                    tempFile.renameTo(targetFile)
                    _state.value = DownloadState.Ready
                }
            } catch (e: Exception) {
                _state.value = DownloadState.Error(e.localizedMessage ?: "Download interrupted")
            }
        }
    }

    companion object {
        const val MODEL_FILENAME = "gemma-2b-it-int4.bin"
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/gemma2_2b_it_gpu_int8.bin"
    }
}
