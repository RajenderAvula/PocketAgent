package com.personal.pocketagent.data.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DocumentReader(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(uri: Uri, maxPages: Int = 15): Result<String> = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        val fileName = uri.path?.lowercase() ?: ""

        try {
            val content = when {
                mimeType == "text/plain" || fileName.endsWith(".txt") -> {
                    readPlainText(uri)
                }
                mimeType.contains("wordprocessingml") || fileName.endsWith(".docx") -> {
                    readDocxText(uri)
                }
                mimeType == "application/pdf" || fileName.endsWith(".pdf") -> {
                    readPdfText(uri, maxPages)
                }
                else -> {
                    readPlainText(uri)
                }
            }
            Result.success(content)
        } catch (e: Exception) {
            Result.failure(Exception("Failed to read document: ${e.localizedMessage}"))
        }
    }

    private fun readPlainText(uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw Exception("Unable to open text file stream.")
    }

    private fun readDocxText(uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val zip = ZipInputStream(stream)
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = zip.bufferedReader(Charsets.UTF_8).readText()
                    return xml.replace(Regex("<[^>]*>"), " ")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                }
                entry = zip.nextEntry
            }
        }
        throw Exception("Invalid .docx file: word/document.xml not found.")
    }

    private suspend fun readPdfText(uri: Uri, maxPages: Int): String {
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw Exception("Could not open PDF file descriptor.")

        val renderer = PdfRenderer(pfd)
        val stringBuilder = StringBuilder()
        val totalPages = minOf(renderer.pageCount, maxPages)

        for (i in 0 until totalPages) {
            val page = renderer.openPage(i)
            val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(image).awaitTask()
            stringBuilder.append("\n--- Page ${i + 1} ---\n")
            stringBuilder.append(visionText.text ?: "")
            bitmap.recycle()
        }

        renderer.close()
        pfd.close()

        val result = stringBuilder.toString().trim()
        if (result.isBlank()) throw Exception("No readable text found in PDF.")
        return result
    }

    /**
     * Bridges ML Kit / Play Services Tasks directly to Kotlin Coroutines without extra dependencies.
     */
    private suspend fun <T> Task<T>.awaitTask(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                continuation.resume(result)
            }
            addOnFailureListener { exception ->
                continuation.resumeWithException(exception)
            }
            addOnCanceledListener {
                continuation.cancel()
            }
        }
}
