package com.breakyuna.noveltranslator.data.db

import android.content.Context
import androidx.room.withTransaction
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.BookEntity
import com.breakyuna.noveltranslator.data.model.ChapterMemoryEntity
import com.breakyuna.noveltranslator.data.model.ContextSnapshotEntity
import com.breakyuna.noveltranslator.data.model.EditionChapterEntity
import com.breakyuna.noveltranslator.data.model.EditionEntity
import com.breakyuna.noveltranslator.data.model.EditionSegmentEntity
import com.breakyuna.noveltranslator.data.model.EditionSegmentMappingEntity
import com.breakyuna.noveltranslator.data.model.LexiconCandidateAggregateEntity
import com.breakyuna.noveltranslator.data.model.LexiconEntryEntity
import com.breakyuna.noveltranslator.data.model.LogicalChapterEntity
import com.breakyuna.noveltranslator.data.model.LogicalSegmentEntity
import com.breakyuna.noveltranslator.data.model.PlatformRequestLogEntity
import com.breakyuna.noveltranslator.data.model.PlatformTranslationBatchEntity
import com.breakyuna.noveltranslator.data.model.PlatformTranslationRunEntity
import com.breakyuna.noveltranslator.data.model.ProviderCacheRecordEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import com.breakyuna.noveltranslator.data.model.ReaderProgressEntity
import com.breakyuna.noveltranslator.data.model.SegmentRevisionEntity
import com.breakyuna.noveltranslator.data.model.StoryMemoryEntity
import com.breakyuna.noveltranslator.data.model.TranslationProjectV2Entity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class Converters {
    @TypeConverter
    fun fromProviderType(type: ProviderType): String = type.name

    @TypeConverter
    fun toProviderType(value: String): ProviderType = runCatching {
        ProviderType.valueOf(value)
    }.getOrDefault(ProviderType.OPENAI_COMPATIBLE)
}

/**
 * Database for the current reader/translation platform only.
 *
 * The pre-platform project/chapter/translation tables were intentionally removed.  The beta
 * schema is recreated under a new database name so stale tables cannot leak back into
 * the active runtime or be mistaken for the V2 workflow.
 */
@Database(
    entities = [
        ApiProviderEntity::class,
        BookEntity::class,
        EditionEntity::class,
        LogicalChapterEntity::class,
        LogicalSegmentEntity::class,
        EditionChapterEntity::class,
        EditionSegmentEntity::class,
        EditionSegmentMappingEntity::class,
        SegmentRevisionEntity::class,
        TranslationProjectV2Entity::class,
        LexiconEntryEntity::class,
        LexiconCandidateAggregateEntity::class,
        StoryMemoryEntity::class,
        ChapterMemoryEntity::class,
        ContextSnapshotEntity::class,
        ReaderProgressEntity::class,
        ProviderCacheRecordEntity::class,
        PlatformTranslationRunEntity::class,
        PlatformTranslationBatchEntity::class,
        PlatformRequestLogEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun apiProviderDao(): ApiProviderDao
    abstract fun bookDao(): BookDao
    abstract fun translationProjectV2Dao(): TranslationProjectV2Dao
    abstract fun lexiconV2Dao(): LexiconV2Dao
    abstract fun lexiconCandidateAggregateDao(): LexiconCandidateAggregateDao
    abstract fun memoryDao(): MemoryDao
    abstract fun readerProgressDao(): ReaderProgressDao
    abstract fun providerCacheDao(): ProviderCacheDao
    abstract fun platformTaskDao(): PlatformTaskDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "light_novel_translation_platform.db"
                ).build().also { database ->
                    instance = database
                    CoroutineScope(Dispatchers.IO).launch {
                        seedDefaultProviders(database)
                    }
                }
            }
        }

        private suspend fun seedDefaultProviders(database: AppDatabase) {
            database.withTransaction {
                val dao = database.apiProviderDao()
                // The seed runs asynchronously when the singleton is first created. Recheck and
                // insert atomically so a user-created provider cannot race this block and leave
                // multiple defaults behind.
                if (dao.getProviderCount() != 0) return@withTransaction
                dao.insertProvider(
                    ApiProviderEntity(
                        name = "OpenAI (Official)",
                        providerType = ProviderType.OPENAI_COMPATIBLE,
                        baseUrl = "https://api.openai.com/v1",
                        apiKey = "",
                        selectedModel = "gpt-5.6-luna",
                        inputPricePerMillion = 0.25,
                        outputPricePerMillion = 2.00,
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
                        selectedModel = "deepseek-v4-flash",
                        inputPricePerMillion = 0.14,
                        outputPricePerMillion = 0.28,
                        currency = "USD",
                        maxContextTokens = 32_768,
                        isDefault = false
                    )
                )
            }
        }
    }
}
