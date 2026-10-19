package com.breakyuna.noveltranslator.data.db

import android.content.Context
import androidx.room.withTransaction
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.breakyuna.noveltranslator.data.model.AgentMessageEntity
import com.breakyuna.noveltranslator.data.model.AgentSessionEntity
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
import com.breakyuna.noveltranslator.data.model.PromptProfileEntity
import com.breakyuna.noveltranslator.data.model.ProviderCacheRecordEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import com.breakyuna.noveltranslator.data.model.QualityRuleSetEntity
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
        PlatformRequestLogEntity::class,
        PromptProfileEntity::class,
        QualityRuleSetEntity::class,
        AgentSessionEntity::class,
        AgentMessageEntity::class
    ],
    version = 6,
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
    abstract fun promptProfileDao(): PromptProfileDao
    abstract fun qualityRuleDao(): QualityRuleDao
    abstract fun agentSessionDao(): AgentSessionDao
    abstract fun agentMessageDao(): AgentMessageDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE reader_progress ADD COLUMN fontSizeSp REAL NOT NULL DEFAULT 18.0")
                database.execSQL("ALTER TABLE reader_progress ADD COLUMN fontFamily TEXT NOT NULL DEFAULT 'SYSTEM'")
                database.execSQL("ALTER TABLE reader_progress ADD COLUMN letterSpacingSp REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE reader_progress ADD COLUMN lineSpacingMultiplier REAL NOT NULL DEFAULT 1.35")
                database.execSQL("ALTER TABLE reader_progress ADD COLUMN paragraphSpacingDp REAL NOT NULL DEFAULT 10.0")
                database.execSQL("ALTER TABLE reader_progress ADD COLUMN pageMarginDp REAL NOT NULL DEFAULT 18.0")
                database.execSQL("ALTER TABLE reader_progress ADD COLUMN useTraditionalChinese INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE reader_progress ADD COLUMN readerBackground TEXT NOT NULL DEFAULT 'SYSTEM'")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `translation_prompt_profiles` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `translationProjectId` INTEGER NOT NULL,
                        `version` INTEGER NOT NULL,
                        `translationSystemPrompt` TEXT NOT NULL,
                        `translationUserPromptTemplate` TEXT NOT NULL,
                        `polishSystemPrompt` TEXT NOT NULL,
                        `polishUserPromptTemplate` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`translationProjectId`) REFERENCES `translation_projects_v2`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_translation_prompt_profiles_translationProjectId` " +
                        "ON `translation_prompt_profiles` (`translationProjectId`)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_translation_prompt_profiles_translationProjectId_version` " +
                        "ON `translation_prompt_profiles` (`translationProjectId`, `version`)"
                )
                database.execSQL(
                    "ALTER TABLE `platform_translation_runs` ADD COLUMN `promptProfileVersion` INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `platform_request_logs` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'SUCCESS'"
                )
            }
        }

        /**
         * Adds the four engine switches to every existing project (all defaults reproduce the
         * previous behaviour except continuityMode, which is the intended refactor) and creates
         * the two agent conversation tables.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `translation_projects_v2` ADD COLUMN `parallelChapters` INTEGER NOT NULL DEFAULT 1"
                )
                database.execSQL(
                    "ALTER TABLE `translation_projects_v2` ADD COLUMN `continuityMode` TEXT NOT NULL DEFAULT 'SOURCE_TAIL'"
                )
                database.execSQL(
                    "ALTER TABLE `translation_projects_v2` ADD COLUMN `metadataEnabled` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `translation_projects_v2` ADD COLUMN `promptCacheEnabled` INTEGER NOT NULL DEFAULT 1"
                )
                database.execSQL(
                    "ALTER TABLE `translation_prompt_profiles` ADD COLUMN `includeMetadataRules` INTEGER NOT NULL DEFAULT 1"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `bookId` INTEGER,
                        `translationProjectId` INTEGER,
                        `providerId` INTEGER,
                        `modelName` TEXT,
                        `state` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastMessagePreview` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_sessions_updatedAt` " +
                        "ON `agent_sessions` (`updatedAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_sessions_bookId` " +
                        "ON `agent_sessions` (`bookId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_sessions_translationProjectId` " +
                        "ON `agent_sessions` (`translationProjectId`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `toolName` TEXT,
                        `toolCallId` TEXT,
                        `toolArgsJson` TEXT,
                        `toolResultJson` TEXT,
                        `isError` INTEGER NOT NULL,
                        `promptTokens` INTEGER NOT NULL,
                        `completionTokens` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `agent_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_messages_sessionId` " +
                        "ON `agent_messages` (`sessionId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_messages_createdAt` " +
                        "ON `agent_messages` (`createdAt`)"
                )
            }
        }

        /**
         * 新增质量规则槽位表（文本保护 / 译前替换 / 译后替换）。
         *
         * 这是一张全新表，不触碰任何既有表结构语义；迁移后既有工程没有任何
         * 规则行，由 `QualityRuleResolver` 的回退路径接管，行为与升级前一致。
         *
         * `app/schemas/.../6.json` 是手写快照，`identityHash` 为占位符：
         * 首次构建时 KSP 会按真实 schema 重新生成该文件，届时以生成结果为准
         * （占位 hash 只影响 MigrationTestHelper 对 5→6 的校验）。
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `quality_rule_sets` (
                        `translationProjectId` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `mode` TEXT NOT NULL,
                        `entriesJson` TEXT NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`translationProjectId`, `type`),
                        FOREIGN KEY(`translationProjectId`) REFERENCES `translation_projects_v2`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "light_novel_translation_platform.db"
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6
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
