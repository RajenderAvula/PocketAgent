package com.personal.pocketagent.data.network

import android.content.Context
import android.os.Environment
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

    private val _state = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    init {
        checkExistingModel()
    }

    /**
     * Checks if a valid model file already exists in internal storage, external files,
     * or the public Downloads directory.
     */
    fun checkExistingModel(): Boolean {
        // 1. Direct Target File Check
        if (targetFile.exists() && targetFile.length() > 20_000_000L) {
            _state.value = DownloadState.Ready
            return true
        }

        // 2. Check for alternate filenames or sideloaded files in filesDir
        val alternateNames = listOf(
            MODEL_FILENAME,
            "gemma2_2b_it_gpu_int8.bin",
            "gemma-2b-it-gpu-int4.bin",
            "gemma-2b-it.bin",
            "model.bin",
            "model.task"
        )

        for (name in alternateNames) {
            val altFile = File(context.filesDir, name)
            if (altFile.exists() && altFile.length() > 20_000_000L) {
                if (altFile.absolutePath != targetFile.absolutePath) {
                    try { altFile.copyTo(targetFile, overwrite = true) } catch (_: Exception) {}
                }
                _state.value = DownloadState.Ready
                return true
            }
        }

        // 3. Check public Downloads/PocketAgent directory
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PocketAgent")
        if (publicDir.exists()) {
            for (name in alternateNames) {
                val dlFile = File(publicDir, name)
                if (dlFile.exists() && dlFile.length() > 20_000_000L) {
                    try {
                        dlFile.copyTo(targetFile, overwrite = true)
                        _state.value = DownloadState.Ready
                        return true
                    } catch (_: Exception) {}
                }
            }
        }

        if (_state.value is DownloadState.Ready) {
            _state.value = DownloadState.NotDownloaded
        }
        return false
    }

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
                        _state.value = DownloadState.Error("HTTP ${response.code}: File moved or access token required.")
                        return@withContext
                    }

                    val body = response.body ?: run {
                        _state.value = DownloadState.Error("Empty response body from server.")
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

                    // Robust Atomic Promotion
                    if (targetFile.exists()) targetFile.delete()
                    val renamed = tempFile.renameTo(targetFile)
                    if (!renamed) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }

                    _state.value = DownloadState.Ready
                }
            } catch (e: Exception) {
                _state.value = DownloadState.Error(e.localizedMessage ?: "Download interrupted.")
            }
        }
    }

    companion object {
        const val MODEL_FILENAME = "gemma-2b-it-int4.bin"
        const val DEFAULT_MODEL_URL =
            /*"https://huggingface.co/google/gemma-2b-it-tflite/resolve/main/gemma-2b-it-gpu-int4.bin"*/
        "https://huggingface.co/alexdlov/gemma-2b-it-gpu-int4.bin"
    }
}
