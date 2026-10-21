package com.personal.pocketagent.data.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.personal.pocketagent.data.local.AgentBackupPayload
import com.personal.pocketagent.data.local.AgentDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupManager(private val context: Context) {

    private val db = AgentDatabase.get(context)
    private val jsonFormatter = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private suspend fun createBackupJsonString(): Pair<String, AgentBackupPayload> {
        val notes = db.noteDao().getAllNotesForBackup()
        val macros = db.macroDao().getAllMacros()
        val payload = AgentBackupPayload(notes = notes, macros = macros)
        return jsonFormatter.encodeToString(payload) to payload
    }

    suspend fun exportToUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val (jsonString, payload) = createBackupJsonString()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(jsonString.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return@withContext Result.failure(Exception("Unable to open output stream."))

            Result.success("Exported ${payload.macros.size} macros and ${payload.notes.size} notes.")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importFromUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: return@withContext Result.failure(Exception("Unable to read selected file."))

            val payload = jsonFormatter.decodeFromString<AgentBackupPayload>(jsonString)

            db.noteDao().insertAllNotes(payload.notes)
            db.noteDao().rebuildFtsIndex()
            db.macroDao().insertAllMacros(payload.macros)

            Result.success("Restored ${payload.macros.size} macros and ${payload.notes.size} notes.")
        } catch (e: Exception) {
            Result.failure(Exception("Invalid backup file format: ${e.localizedMessage}"))
        }
    }

    suspend fun exportToDownloadsFolder(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val (jsonString, payload) = createBackupJsonString()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "pocket_agent_backup_$timestamp.json"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PocketAgent")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext Result.failure(Exception("Could not create file in Downloads."))

                resolver.openOutputStream(uri)?.use { out ->
                    out.write(jsonString.toByteArray(Charsets.UTF_8))
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "PocketAgent"
                ).apply { mkdirs() }
                File(dir, fileName).writeText(jsonString, Charsets.UTF_8)
            }

            Result.success(
                "Backed up ${payload.macros.size} macros and ${payload.notes.size} notes to Downloads/PocketAgent/$fileName."
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
