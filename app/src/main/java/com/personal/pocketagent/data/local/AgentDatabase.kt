package com.personal.pocketagent.data.local

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val id: Int = 0,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Fts4(contentEntity = NoteEntity::class)
@Entity(tableName = "notes_fts")
data class NoteFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Int,
    val content: String
)

@Serializable
@Entity(tableName = "custom_macros")
data class MacroEntity(
    @PrimaryKey val triggerPhrase: String,
    val stepsJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class AgentBackupPayload(
    val version: Int = 3,
    val exportedAt: Long = System.currentTimeMillis(),
    val notes: List<NoteEntity> = emptyList(),
    val macros: List<MacroEntity> = emptyList()
)

@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllNotes(notes: List<NoteEntity>)

    @Query("SELECT * FROM notes ORDER BY timestamp DESC LIMIT 20")
    suspend fun getAllNotes(): List<NoteEntity>

    @Query("SELECT * FROM notes ORDER BY timestamp ASC")
    suspend fun getAllNotesForBackup(): List<NoteEntity>

    @Query("""
        SELECT notes.* FROM notes
        JOIN notes_fts ON notes.rowid = notes_fts.rowid
        WHERE notes_fts MATCH :ftsQuery
        ORDER BY notes.timestamp DESC
        LIMIT 10
    """)
    suspend fun searchNotesFts(ftsQuery: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE content LIKE '%' || :keyword || '%' ORDER BY timestamp DESC LIMIT 10")
    suspend fun searchNotesSubstring(keyword: String): List<NoteEntity>

    @Query("INSERT INTO notes_fts(notes_fts) VALUES('rebuild')")
    suspend fun rebuildFtsIndex()
}

@Dao
interface MacroDao {
    @Query("SELECT * FROM custom_macros ORDER BY createdAt DESC")
    suspend fun getAllMacros(): List<MacroEntity>

    @Query("SELECT * FROM custom_macros ORDER BY createdAt DESC LIMIT 3")
    suspend fun getTopMacrosForWidget(): List<MacroEntity>

    @Query("SELECT * FROM custom_macros WHERE triggerPhrase = :phrase LIMIT 1")
    suspend fun getMacro(phrase: String): MacroEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMacro(macro: MacroEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMacros(macros: List<MacroEntity>)

    @Query("DELETE FROM custom_macros WHERE triggerPhrase = :phrase")
    suspend fun deleteMacro(phrase: String)
}

@Database(
    entities = [NoteEntity::class, NoteFtsEntity::class, MacroEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun macroDao(): MacroDao

    companion object {
        @Volatile private var INSTANCE: AgentDatabase? = null

        fun get(context: Context): AgentDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, "agent_db")
                    .fallbackToDestructiveMigration()
                    .addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.execSQL("INSERT INTO notes_fts(notes_fts) VALUES('rebuild')")
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
