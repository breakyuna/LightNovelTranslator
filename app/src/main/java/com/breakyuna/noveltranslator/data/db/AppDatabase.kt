package com.breakyuna.noveltranslator.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.breakyuna.noveltranslator.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class Converters {
    @TypeConverter
    fun fromChapterStatus(status: ChapterStatus): String = status.name

    @TypeConverter
    fun toChapterStatus(value: String): ChapterStatus = try {
        ChapterStatus.valueOf(value)
    } catch (e: Exception) {
        ChapterStatus.PENDING
    }

    @TypeConverter
    fun fromTermCategory(category: TermCategory): String = category.name

    @TypeConverter
    fun toTermCategory(value: String): TermCategory = try {
        TermCategory.valueOf(value)
    } catch (e: Exception) {
        TermCategory.CUSTOM
    }

    @TypeConverter
    fun fromProviderType(type: ProviderType): String = type.name

    @TypeConverter
    fun toProviderType(value: String): ProviderType = try {
        ProviderType.valueOf(value)
    } catch (e: Exception) {
        ProviderType.OPENAI_COMPATIBLE
    }
}

@Database(
    entities = [
        ProjectEntity::class,
        ChapterEntity::class,
        GlossaryEntity::class,
        ApiProviderEntity::class,
        TranslationLogEntity::class,
        TranslationRunEntity::class,
        TranslationChunkEntity::class,
        LlmRequestLogEntity::class,
        ChapterSegmentEntity::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun chapterDao(): ChapterDao
    abstract fun glossaryDao(): GlossaryDao
    abstract fun apiProviderDao(): ApiProviderDao
    abstract fun translationLogDao(): TranslationLogDao
    abstract fun translationRunDao(): TranslationRunDao
    abstract fun translationChunkDao(): TranslationChunkDao
    abstract fun llmRequestLogDao(): LlmRequestLogDao
    abstract fun chapterSegmentDao(): ChapterSegmentDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN costCurrency TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE translation_logs ADD COLUMN currency TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE projects SET costCurrency = 'UNKNOWN' WHERE totalCost > 0 AND (costCurrency IS NULL OR costCurrency = '')")
                db.execSQL("UPDATE translation_logs SET currency = 'UNKNOWN' WHERE currency IS NULL OR currency = ''")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS translation_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        projectId INTEGER NOT NULL,
                        providerId INTEGER NOT NULL,
                        providerName TEXT NOT NULL,
                        modelName TEXT NOT NULL,
                        inputPricePerMillion REAL NOT NULL,
                        outputPricePerMillion REAL NOT NULL,
                        currency TEXT NOT NULL,
                        state TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastErrorCategory TEXT,
                        lastErrorMessage TEXT,
                        nextRetryAt INTEGER,
                        totalPromptTokens INTEGER NOT NULL,
                        totalCompletionTokens INTEGER NOT NULL,
                        totalCost REAL NOT NULL,
                        FOREIGN KEY(projectId) REFERENCES projects(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_runs_projectId ON translation_runs(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_runs_state ON translation_runs(state)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS translation_chunks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        runId INTEGER NOT NULL,
                        chapterId INTEGER NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        chunkIndex INTEGER NOT NULL,
                        totalChunks INTEGER NOT NULL,
                        sourceHash TEXT NOT NULL,
                        parentChunkId INTEGER,
                        state TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        translatedTempFileName TEXT,
                        promptTokens INTEGER NOT NULL,
                        completionTokens INTEGER NOT NULL,
                        cost REAL NOT NULL,
                        durationMs INTEGER NOT NULL,
                        lastErrorCategory TEXT,
                        lastErrorMessage TEXT,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(runId) REFERENCES translation_runs(id) ON DELETE CASCADE,
                        FOREIGN KEY(chapterId) REFERENCES chapters(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_translation_chunks_runId_chapterId_chunkIndex_parentChunkId ON translation_chunks(runId, chapterId, chunkIndex, parentChunkId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_chunks_chapterId ON translation_chunks(chapterId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS llm_request_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        runId INTEGER,
                        projectId INTEGER NOT NULL,
                        chapterId INTEGER,
                        chapterIndex INTEGER,
                        chunkIndex INTEGER,
                        attemptNumber INTEGER NOT NULL,
                        operation TEXT NOT NULL,
                        providerId INTEGER NOT NULL,
                        providerName TEXT NOT NULL,
                        modelName TEXT NOT NULL,
                        inputPricePerMillion REAL NOT NULL,
                        outputPricePerMillion REAL NOT NULL,
                        currency TEXT NOT NULL,
                        promptTokens INTEGER NOT NULL,
                        completionTokens INTEGER NOT NULL,
                        totalTokens INTEGER NOT NULL,
                        usageSource TEXT NOT NULL,
                        estimatedCost REAL NOT NULL,
                        durationMs INTEGER NOT NULL,
                        httpStatus INTEGER,
                        errorCategory TEXT,
                        errorMessage TEXT,
                        finishReason TEXT,
                        requestId TEXT,
                        isSuccess INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        FOREIGN KEY(projectId) REFERENCES projects(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_llm_request_logs_projectId ON llm_request_logs(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_llm_request_logs_runId ON llm_request_logs(runId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_llm_request_logs_timestamp ON llm_request_logs(timestamp)")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chapter_segments (
                        stableKey TEXT NOT NULL,
                        chapterId INTEGER NOT NULL,
                        sourceSegmentId TEXT NOT NULL,
                        translatedSegmentId TEXT NOT NULL,
                        sourceOrdinal INTEGER,
                        translatedOrdinal INTEGER,
                        sourceText TEXT NOT NULL,
                        translatedText TEXT NOT NULL,
                        segmentType TEXT NOT NULL,
                        relation TEXT NOT NULL,
                        sourceHash TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(stableKey),
                        FOREIGN KEY(chapterId) REFERENCES chapters(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapter_segments_chapterId ON chapter_segments(chapterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapter_segments_chapterId_sourceSegmentId ON chapter_segments(chapterId, sourceSegmentId)")
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_chunks ADD COLUMN parentChunkKey INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE translation_chunks SET parentChunkKey = COALESCE(parentChunkId, 0)")
                db.execSQL("DROP INDEX IF EXISTS index_translation_chunks_runId_chapterId_chunkIndex_parentChunkId")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_translation_chunks_runId_chapterId_chunkIndex_parentChunkKey ON translation_chunks(runId, chapterId, chunkIndex, parentChunkKey)")

                // Provider connection tests are global operations, so request logs must allow no project.
                db.execSQL("ALTER TABLE llm_request_logs RENAME TO llm_request_logs_v5")
                db.execSQL("""
                    CREATE TABLE llm_request_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        runId INTEGER,
                        projectId INTEGER,
                        chapterId INTEGER,
                        chapterIndex INTEGER,
                        chunkIndex INTEGER,
                        attemptNumber INTEGER NOT NULL,
                        operation TEXT NOT NULL,
                        providerId INTEGER NOT NULL,
                        providerName TEXT NOT NULL,
                        modelName TEXT NOT NULL,
                        inputPricePerMillion REAL NOT NULL,
                        outputPricePerMillion REAL NOT NULL,
                        currency TEXT NOT NULL,
                        promptTokens INTEGER NOT NULL,
                        completionTokens INTEGER NOT NULL,
                        totalTokens INTEGER NOT NULL,
                        usageSource TEXT NOT NULL,
                        estimatedCost REAL NOT NULL,
                        durationMs INTEGER NOT NULL,
                        httpStatus INTEGER,
                        errorCategory TEXT,
                        errorMessage TEXT,
                        finishReason TEXT,
                        requestId TEXT,
                        isSuccess INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        FOREIGN KEY(projectId) REFERENCES projects(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO llm_request_logs (
                        id, runId, projectId, chapterId, chapterIndex, chunkIndex, attemptNumber,
                        operation, providerId, providerName, modelName, inputPricePerMillion,
                        outputPricePerMillion, currency, promptTokens, completionTokens, totalTokens,
                        usageSource, estimatedCost, durationMs, httpStatus, errorCategory, errorMessage,
                        finishReason, requestId, isSuccess, timestamp
                    ) SELECT
                        id, runId, projectId, chapterId, chapterIndex, chunkIndex, attemptNumber,
                        operation, providerId, providerName, modelName, inputPricePerMillion,
                        outputPricePerMillion, currency, promptTokens, completionTokens, totalTokens,
                        usageSource, estimatedCost, durationMs, httpStatus, errorCategory, errorMessage,
                        finishReason, requestId, isSuccess, timestamp
                    FROM llm_request_logs_v5
                """.trimIndent())
                db.execSQL("DROP TABLE llm_request_logs_v5")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_llm_request_logs_projectId ON llm_request_logs(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_llm_request_logs_runId ON llm_request_logs(runId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_llm_request_logs_timestamp ON llm_request_logs(timestamp)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "novel_translator_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Populate default provider presets
                            CoroutineScope(Dispatchers.IO).launch {
                                val dao = getDatabase(context).apiProviderDao()
                                if (dao.getProviderCount() == 0) {
                                    // Pre-populate standard providers
                                    dao.insertProvider(
                                        ApiProviderEntity(
                                            name = "Google Gemini",
                                            providerType = ProviderType.GEMINI_DIRECT,
                                            baseUrl = "https://generativelanguage.googleapis.com",
                                            apiKey = "",
                                            selectedModel = "gemini-2.5-flash",
                                            inputPricePerMillion = 0.30,
                                            outputPricePerMillion = 2.50,
                                            currency = "USD",
                                            maxContextTokens = 32_768,
                                            isDefault = true
                                        )
                                    )
                                    dao.insertProvider(
                                        ApiProviderEntity(
                                            name = "DeepSeek (Official)",
                                            providerType = ProviderType.DEEPSEEK,
                                            baseUrl = "https://api.deepseek.com/v1",
                                            apiKey = "",
                                            selectedModel = "deepseek-chat",
                                            inputPricePerMillion = 0.14,
                                            outputPricePerMillion = 0.28,
                                            currency = "USD",
                                            maxContextTokens = 32_768,
                                            isDefault = false
                                        )
                                    )
                                    dao.insertProvider(
                                        ApiProviderEntity(
                                            name = "OpenAI (Official)",
                                            providerType = ProviderType.OPENAI_COMPATIBLE,
                                            baseUrl = "https://api.openai.com/v1",
                                            apiKey = "",
                                            selectedModel = "gpt-5-mini",
                                            inputPricePerMillion = 0.25,
                                            outputPricePerMillion = 2.00,
                                            currency = "USD",
                                            maxContextTokens = 32_768,
                                            isDefault = false
                                        )
                                    )
                                    dao.insertProvider(
                                        ApiProviderEntity(
                                            name = "Anthropic Claude",
                                            providerType = ProviderType.ANTHROPIC_CLAUDE,
                                            baseUrl = "https://api.anthropic.com/v1",
                                            apiKey = "",
                                            selectedModel = "claude-haiku-4-5-20251001",
                                            inputPricePerMillion = 1.00,
                                            outputPricePerMillion = 5.00,
                                            currency = "USD",
                                            maxContextTokens = 32_768,
                                            isDefault = false
                                        )
                                    )
                                }
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
