package com.breakyuna.noveltranslator.legacy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.core.quality.QualityRuleJson
import com.breakyuna.noveltranslator.core.quality.QualityRuleType
import com.breakyuna.noveltranslator.core.quality.ReplacementRuleEntry
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.AgentMessageEntity
import com.breakyuna.noveltranslator.data.model.AgentSessionEntity
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.BookEntity
import com.breakyuna.noveltranslator.data.model.EditionChapterEntity
import com.breakyuna.noveltranslator.data.model.EditionEntity
import com.breakyuna.noveltranslator.data.model.EditionSegmentEntity
import com.breakyuna.noveltranslator.data.model.EditionSegmentMappingEntity
import com.breakyuna.noveltranslator.data.model.EditionType
import com.breakyuna.noveltranslator.data.model.LexiconEntryEntity
import com.breakyuna.noveltranslator.data.model.LogicalChapterEntity
import com.breakyuna.noveltranslator.data.model.LogicalSegmentEntity
import com.breakyuna.noveltranslator.data.model.PromptProfileEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import com.breakyuna.noveltranslator.data.model.QualityRuleSetEntity
import com.breakyuna.noveltranslator.data.model.ReaderProgressEntity
import com.breakyuna.noveltranslator.data.model.RevisionType
import com.breakyuna.noveltranslator.data.model.SegmentRevisionEntity
import com.breakyuna.noveltranslator.data.model.TermCategory
import com.breakyuna.noveltranslator.data.model.TranslationProjectV2Entity
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyExportServiceTest {

    private lateinit var context: Context
    private lateinit var root: File
    private val databaseNames = mutableListOf<String>()
    private lateinit var service: LegacyExportService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "legacy-export-test-${System.nanoTime()}").apply { mkdirs() }
        service = LegacyExportService(context)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        databaseNames.forEach { context.deleteDatabase(it) }
    }

    @Test
    fun manifestsHashesRedactsCredentialsAndDoesNotModifyTheLegacyDatabase() = runTest {
        val fixture = createDatabase("legacy_export_full_${System.nanoTime()}.db") { database ->
            seedTranslatedBook(database)
        }
        val output = File(root, "package")

        val result = service.exportAll(fixture.file, output)

        assertEquals(fixture.sha256, sha256(fixture.file))
        assertTrue(output.isDirectory)
        assertEquals(output, result.outputDirectory)
        assertTrue(result.manifestFile.isFile)

        val manifest = JSONObject(File(output, "manifest.json").readText())
        assertTrue(manifest.getBoolean("credentials_excluded"))
        assertFalse(manifest.getBoolean("migration"))
        assertTrue(manifest.getBoolean("read_only"))
        assertEquals(fixture.sha256, manifest.getJSONObject("sourceDatabase").getString("sha256"))
        assertEquals(1, manifest.getJSONObject("counts").getInt("books"))
        assertEquals(2, manifest.getJSONObject("counts").getInt("editions"))
        assertTrue(result.failures.any { it.scope == "edition-files" })

        val files = manifest.getJSONArray("files")
        assertTrue(files.length() > 0)
        for (index in 0 until files.length()) {
            val entry = files.getJSONObject(index)
            val path = entry.getString("path")
            assertFalse(path.startsWith("/"))
            assertFalse(path.contains(".."))
            assertFalse(path.contains('\\'))
            val file = File(output, path)
            assertTrue("missing $path", file.isFile)
            assertEquals(entry.getLong("bytes"), file.length())
            assertEquals(entry.getString("sha256"), sha256(file))
        }

        val exportedText = output.walkTopDown()
            .filter { it.isFile && it.extension in setOf("json", "csv", "txt") }
            .joinToString("\n") { it.readText() }
        assertFalse(exportedText.contains("sk-live-secret-123456"))
        assertFalse(exportedText.contains("enc:v1:"))
        assertFalse(exportedText.contains("SYSTEM secret"))
        assertTrue(exportedText.contains("[REDACTED]"))

        val bookJson = output.walkTopDown().first { it.name == "book.json" }.readText()
        assertFalse(bookJson.contains("apiKey"))
        assertFalse(bookJson.contains("coverPath"))

        val chat = JSONObject(File(output, "chat/sessions.json").readText())
        val session = chat.getJSONArray("sessions").getJSONObject(0)
        val messages = session.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("USER", messages.getJSONObject(0).getString("role"))
        assertEquals("ASSISTANT", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun oneBrokenBookDoesNotDropOtherBooksAndEmptyBooksGetMetadata() = runTest {
        var brokenBookId = 0L
        val fixture = createDatabase("legacy_export_broken_${System.nanoTime()}.db") { database ->
            seedTranslatedBook(database, title = "Good Book")
            val bookDao = database.bookDao()
            brokenBookId = bookDao.insert(BookEntity(title = "Broken Book"))
            bookDao.insertEdition(
                EditionEntity(
                    bookId = brokenBookId,
                    name = "Broken Edition",
                    type = EditionType.AI_TRANSLATION.name,
                )
            )
            bookDao.insert(BookEntity(title = "Empty Book"))
        }
        val output = File(root, "package")

        val result = service.exportAll(fixture.file, output)

        assertEquals(2, result.failures.count { it.bookId == brokenBookId })
        assertTrue(result.failures.any { it.scope == "edition-txt" && it.bookId == brokenBookId })
        assertTrue(result.failures.any { it.scope == "edition-epub" && it.bookId == brokenBookId })
        assertTrue(output.walkTopDown().any { it.extension == "txt" })
        assertTrue(output.walkTopDown().any { it.extension == "epub" })

        val bookFiles = output.walkTopDown().filter { it.name == "book.json" }.toList()
        assertEquals(3, bookFiles.size)
        val emptyBook = bookFiles
            .map { JSONObject(it.readText()) }
            .first { it.getString("title") == "Empty Book" }
        assertEquals(0, emptyBook.getJSONArray("editions").length())
        assertEquals(0, emptyBook.getJSONArray("projectIds").length())
    }

    @Test
    fun emptyEditionStillProducesTxtAndEpubEntries() = runTest {
        val fixture = createDatabase("legacy_export_empty_edition_${System.nanoTime()}.db") { database ->
            val bookDao = database.bookDao()
            val bookId = bookDao.insert(BookEntity(title = "Empty Edition Book"))
            val editionId = bookDao.insertEdition(
                EditionEntity(
                    bookId = bookId,
                    name = "Empty Edition",
                    type = EditionType.IMPORTED.name,
                )
            )
            bookDao.update(bookDao.getBook(bookId)!!.copy(primaryEditionId = editionId))
        }
        val output = File(root, "package")

        service.exportAll(fixture.file, output)

        val textFiles = output.walkTopDown().filter { it.extension == "txt" }.toList()
        assertEquals(1, textFiles.size)
        assertTrue(textFiles.single().readText().contains("Empty Edition Book"))
        assertTrue(output.walkTopDown().any { it.extension == "epub" })

        val manifest = JSONObject(File(output, "manifest.json").readText())
        val files = manifest.getJSONArray("files")
        val editionFiles = (0 until files.length())
            .map { files.getJSONObject(it) }
            .filter { it.getString("kind").startsWith("edition-") }
        assertEquals(2, editionFiles.size)
        assertTrue(editionFiles.all { it.getInt("entries") == 0 })
    }

    @Test
    fun sanitizesHostileNamesAndResolvesCollisions() = runTest {
        val fixture = createDatabase("legacy_export_paths_${System.nanoTime()}.db") { database ->
            seedTranslatedBook(
                database,
                title = "../秘密/书:名\u0001",
                sourceName = "相同/名:称",
                targetName = "相同/名:称",
                withChapters = false,
            )
        }
        val output = File(root, "package")

        service.exportAll(fixture.file, output)

        val allFiles = output.walkTopDown().filter { it.isFile }.toList()
        assertTrue(allFiles.isNotEmpty())
        for (file in allFiles) {
            assertFalse(file.name.contains(':'))
            assertFalse(file.name.contains('\\'))
            assertFalse(file.name.contains(".."))
        }
        assertEquals(4, allFiles.count { it.extension == "txt" || it.extension == "epub" })

        val used = mutableSetOf<String>()
        assertEquals("同名.txt", LegacyExportPaths.uniqueName("同名", ".txt", used))
        assertEquals("同名_2.txt", LegacyExportPaths.uniqueName("同名", ".txt", used))
        val segment = LegacyExportPaths.safeSegment("../危险:名", "fallback", 16)
        assertFalse(segment.contains('/'))
        assertFalse(segment.contains(".."))
        assertTrue(segment.toByteArray(Charsets.UTF_8).size <= 16)
    }

    @Test
    fun cancellationPropagatesAndLeavesNoPublishedOrStagingDirectory() = runTest {
        val fixture = createDatabase("legacy_export_cancel_${System.nanoTime()}.db") { database ->
            seedTranslatedBook(database)
        }
        val output = File(root, "cancelled-package")
        var cancelled = false

        try {
            service.exportAll(
                fixture.file,
                output,
                onProgress = { throw CancellationException("cancel test") },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertFalse(output.exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".legacy-export-") })
    }

    @Test
    fun totalBudgetAbortsWithoutPublishingAPartialPackage() = runTest {
        val fixture = createDatabase("legacy_export_budget_${System.nanoTime()}.db") { database ->
            seedTranslatedBook(database)
        }
        val output = File(root, "budget-package")
        var failure: Throwable? = null

        try {
            service.exportAll(
                fixture.file,
                output,
                options = LegacyExportOptions(maxTotalBytes = 10L),
            )
        } catch (error: Throwable) {
            failure = error
        }

        assertTrue("expected LegacyExportLimitException, got $failure", failure is LegacyExportLimitException)
        assertFalse(output.exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".legacy-export-") })
    }

    @Test
    fun refusesToOverwriteAnExistingOutputDirectory() = runTest {
        val fixture = createDatabase("legacy_export_existing_${System.nanoTime()}.db") { database ->
            seedTranslatedBook(database)
        }
        val output = File(root, "existing-package").apply { mkdirs() }
        val keep = File(output, "keep.txt").apply { writeText("keep", Charsets.UTF_8) }
        var failure: Throwable? = null

        try {
            service.exportAll(fixture.file, output)
        } catch (error: Throwable) {
            failure = error
        }

        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(keep.isFile)
        assertEquals("keep", keep.readText())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".legacy-export-") })
    }

    @Test
    fun entryBudgetAbortsWithoutPublishingAPartialPackage() = runTest {
        val fixture = createDatabase("legacy_export_entries_${System.nanoTime()}.db") { database ->
            seedTranslatedBook(database)
        }
        val output = File(root, "entry-budget-package")
        var failure: Throwable? = null

        try {
            service.exportAll(
                fixture.file,
                output,
                options = LegacyExportOptions(maxEntries = 1),
            )
        } catch (error: Throwable) {
            failure = error
        }

        assertTrue("expected LegacyExportLimitException, got $failure", failure is LegacyExportLimitException)
        assertFalse(output.exists())
    }

    private suspend fun createDatabase(
        name: String,
        seed: suspend (AppDatabase) -> Unit,
    ): Fixture {
        databaseNames += name
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            seed(database)
        } finally {
            database.close()
        }
        val file = context.getDatabasePath(name)
        assertTrue(file.isFile)
        return Fixture(file, sha256(file))
    }

    private suspend fun seedTranslatedBook(
        database: AppDatabase,
        title: String = "书/名:测试",
        sourceName: String = "原文/版",
        targetName: String = "译文:版",
        withChapters: Boolean = true,
    ): SeededBook {
        val bookDao = database.bookDao()
        val bookId = bookDao.insert(
            BookEntity(
                title = title,
                author = "作者",
                description = "描述",
            )
        )
        val sourceEditionId = bookDao.insertEdition(
            EditionEntity(
                bookId = bookId,
                name = sourceName,
                type = EditionType.IMPORTED.name,
                language = "en",
            )
        )
        val targetEditionId = bookDao.insertEdition(
            EditionEntity(
                bookId = bookId,
                name = targetName,
                type = EditionType.AI_TRANSLATION.name,
                language = "zh",
                sourceEditionId = sourceEditionId,
            )
        )
        bookDao.update(
            bookDao.getBook(bookId)!!.copy(
                primaryEditionId = sourceEditionId,
                preferredReadingEditionId = targetEditionId,
            )
        )

        var chapterId: Long? = null
        var logicalSegmentId: Long? = null
        if (withChapters) {
            val newChapterId = bookDao.insertLogicalChapter(
                LogicalChapterEntity(bookId = bookId, chapterIndex = 1, canonicalTitle = "第一章")
            )
            chapterId = newChapterId
            val newLogicalSegmentId = bookDao.insertLogicalSegments(
                listOf(LogicalSegmentEntity(logicalChapterId = newChapterId, segmentIndex = 0))
            ).single()
            logicalSegmentId = newLogicalSegmentId

            val sourceChapterId = bookDao.insertEditionChapter(
                EditionChapterEntity(
                    editionId = sourceEditionId,
                    logicalChapterId = newChapterId,
                    title = "第一章",
                    contentFileName = "0001.txt",
                )
            )
            val sourceSegmentId = bookDao.insertEditionSegments(
                listOf(
                    EditionSegmentEntity(
                        editionChapterId = sourceChapterId,
                        segmentIndex = 0,
                        baseText = "Hello",
                        sourceHash = "source-hash",
                    )
                )
            ).single()
            bookDao.insertMappings(
                listOf(
                    EditionSegmentMappingEntity(
                        logicalSegmentId = newLogicalSegmentId,
                        editionSegmentId = sourceSegmentId,
                    )
                )
            )

            val targetChapterId = bookDao.insertEditionChapter(
                EditionChapterEntity(
                    editionId = targetEditionId,
                    logicalChapterId = newChapterId,
                    title = "第一章",
                    contentFileName = "0001-zh.txt",
                )
            )
            val targetSegmentId = bookDao.insertEditionSegments(
                listOf(
                    EditionSegmentEntity(
                        editionChapterId = targetChapterId,
                        segmentIndex = 0,
                        baseText = "你好",
                        sourceHash = "target-hash",
                    )
                )
            ).single()
            bookDao.insertMappings(
                listOf(
                    EditionSegmentMappingEntity(
                        logicalSegmentId = newLogicalSegmentId,
                        editionSegmentId = targetSegmentId,
                    )
                )
            )
            bookDao.insertRevision(
                SegmentRevisionEntity(
                    editionSegmentId = targetSegmentId,
                    revisionType = RevisionType.AI_TRANSLATION.name,
                    text = "你好",
                    priority = 200,
                )
            )
        }

        val providerId = database.apiProviderDao().insertProvider(
            ApiProviderEntity(
                name = "Legacy Provider",
                providerType = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                apiKey = "sk-live-secret-123456",
                selectedModel = "legacy-model",
            )
        )
        val projectId = database.translationProjectV2Dao().insert(
            TranslationProjectV2Entity(
                bookId = bookId,
                sourceEditionId = sourceEditionId,
                targetEditionId = targetEditionId,
                sourceLanguage = "en",
                targetLanguage = "zh",
                providerId = providerId,
                modelName = "legacy-model",
                styleGuide = "style",
            )
        )
        database.lexiconV2Dao().upsert(
            LexiconEntryEntity(
                translationProjectId = projectId,
                sourceTerm = "Alice",
                targetTerm = "爱丽丝",
                kind = "PROPER_NOUN",
                category = TermCategory.CHARACTER.name,
                aliases = "Ali",
                notes = "note",
            )
        )
        database.qualityRuleDao().upsert(
            QualityRuleSetEntity(
                translationProjectId = projectId,
                type = QualityRuleType.PRE_REPLACEMENT.storageKey,
                enabled = true,
                mode = "CUSTOM",
                entriesJson = QualityRuleJson.encode(
                    QualityRuleType.PRE_REPLACEMENT,
                    listOf(ReplacementRuleEntry(pattern = "AB", replacement = "CD", note = "rule")),
                ),
            )
        )
        database.promptProfileDao().insert(
            PromptProfileEntity(
                translationProjectId = projectId,
                version = 1,
                translationSystemPrompt = "Use sk-live-secret-123456 for the provider",
                translationUserPromptTemplate = "Translate",
                polishSystemPrompt = "Polish",
                polishUserPromptTemplate = "Polish the text",
            )
        )
        val sessionId = database.agentSessionDao().insert(
            AgentSessionEntity(
                title = "Chat",
                bookId = bookId,
                translationProjectId = projectId,
                modelName = "legacy-model",
                createdAt = 1L,
                updatedAt = 2L,
                lastMessagePreview = "Bearer abcdefghijklmnop",
            )
        )
        database.agentMessageDao().insertAll(
            listOf(
                AgentMessageEntity(
                    sessionId = sessionId,
                    role = "SYSTEM",
                    content = "SYSTEM secret sk-live-secret-123456",
                    createdAt = 1L,
                ),
                AgentMessageEntity(
                    sessionId = sessionId,
                    role = "USER",
                    content = "hello Bearer abcdefghijklmnop",
                    createdAt = 2L,
                ),
                AgentMessageEntity(
                    sessionId = sessionId,
                    role = "ASSISTANT",
                    content = "你好",
                    createdAt = 3L,
                ),
                AgentMessageEntity(
                    sessionId = sessionId,
                    role = "TOOL_CALL",
                    content = "",
                    toolName = "secret_tool",
                    toolArgsJson = """{"apiKey":"sk-live-secret-123456"}""",
                    createdAt = 4L,
                ),
            )
        )
        val progressChapterId = chapterId
        val progressSegmentId = logicalSegmentId
        if (progressChapterId != null && progressSegmentId != null) {
            database.readerProgressDao().upsert(
                ReaderProgressEntity(
                    bookId = bookId,
                    preferredEditionId = targetEditionId,
                    logicalChapterId = progressChapterId,
                    logicalSegmentId = progressSegmentId,
                )
            )
        }
        return SeededBook(bookId, sourceEditionId, targetEditionId, projectId)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class Fixture(val file: File, val sha256: String)

    private data class SeededBook(
        val bookId: Long,
        val sourceEditionId: Long,
        val targetEditionId: Long,
        val projectId: Long,
    )
}
