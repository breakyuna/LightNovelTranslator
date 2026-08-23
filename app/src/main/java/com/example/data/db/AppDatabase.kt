package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
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
        TermCategory.CHARACTER
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
    version = 1,
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
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "novel_translator_db"
                )
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
                                            name = "Google Gemini (Built-in)",
                                            providerType = ProviderType.GEMINI_DIRECT,
                                            baseUrl = "https://generativelanguage.googleapis.com",
                                            apiKey = "",
                                            selectedModel = "gemini-3.5-flash",
                                            inputPricePerMillion = 0.10,
                                            outputPricePerMillion = 0.40,
                                            currency = "USD",
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
                                            isDefault = false
                                        )
                                    )
                                    dao.insertProvider(
                                        ApiProviderEntity(
                                            name = "OpenAI (Official)",
                                            providerType = ProviderType.OPENAI_COMPATIBLE,
                                            baseUrl = "https://api.openai.com/v1",
                                            apiKey = "",
                                            selectedModel = "gpt-4o-mini",
                                            inputPricePerMillion = 0.15,
                                            outputPricePerMillion = 0.60,
                                            currency = "USD",
                                            isDefault = false
                                        )
                                    )
                                    dao.insertProvider(
                                        ApiProviderEntity(
                                            name = "Anthropic Claude",
                                            providerType = ProviderType.ANTHROPIC_CLAUDE,
                                            baseUrl = "https://api.anthropic.com/v1",
                                            apiKey = "",
                                            selectedModel = "claude-3-5-haiku-20241022",
                                            inputPricePerMillion = 0.80,
                                            outputPricePerMillion = 4.00,
                                            currency = "USD",
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
