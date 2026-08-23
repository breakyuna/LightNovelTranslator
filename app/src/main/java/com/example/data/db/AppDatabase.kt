package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.*
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
        TranslationLogEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun chapterDao(): ChapterDao
    abstract fun glossaryDao(): GlossaryDao
    abstract fun apiProviderDao(): ApiProviderDao
    abstract fun translationLogDao(): TranslationLogDao

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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "novel_translator_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
