package com.breakyuna.noveltranslator.core.logger

import com.breakyuna.noveltranslator.data.model.LiveLogMessage
import com.breakyuna.noveltranslator.data.model.LiveLogType
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SystemLoggerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(SystemLogEntry::class.java)

    @Before
    fun setUp() = runBlocking {
        SystemLogger.resetForTests()
    }

    @After
    fun tearDown() = runBlocking {
        SystemLogger.resetForTests()
    }

    @Test
    fun highFrequencyIdsAreUniqueForBothLogModels() {
        val systemIds = List(20_000) { SystemLogEntry(tag = "TEST", message = "entry-$it").id }
        val liveIds = List(20_000) { LiveLogMessage(type = LiveLogType.INFO, message = "entry-$it").id }

        assertEquals(systemIds.size, systemIds.toSet().size)
        assertEquals(liveIds.size, liveIds.toSet().size)
    }

    @Test
    fun concurrentWritesPublishOneOrderedSnapshotWithoutLoss() = runBlocking {
        val directory = temporaryFolder.newFolder("concurrent")
        SystemLogger.initDirectory(directory)
        SystemLogger.awaitIdleForTests()

        coroutineScope {
            repeat(8) { worker ->
                launch(Dispatchers.Default) {
                    repeat(50) { sequence ->
                        SystemLogger.info("CONCURRENT", "$worker-$sequence")
                    }
                }
            }
        }
        SystemLogger.awaitIdleForTests()

        val snapshot = SystemLogger.logsFlow.value.filter { it.tag == "CONCURRENT" }
        assertEquals(400, snapshot.size)
        assertEquals(snapshot.size, snapshot.map { it.id }.toSet().size)

        val persisted = readJsonl(File(directory, SystemLogger.JSONL_FILE_NAME))
            .filter { it.tag == "CONCURRENT" }
        assertEquals(400, persisted.size)
        assertEquals(snapshot.map { it.id }.toSet(), persisted.map { it.id }.toSet())
        assertEquals(snapshot.map { it.id }, persisted.map { it.id })
    }

    @Test
    fun newSessionStartsEmptyWhileKeepingPreviousEntriesInJsonl() = runBlocking {
        val directory = temporaryFolder.newFolder("recovery")
        SystemLogger.initDirectory(directory)
        SystemLogger.info(
            tag = "RECOVERY",
            message = "recover me",
            details = "line one\nline two",
            projectId = 41L,
            chapterIndex = 7
        )
        SystemLogger.awaitIdleForTests()
        val original = SystemLogger.logsFlow.value.single { it.message == "recover me" }

        SystemLogger.resetForTests()
        SystemLogger.initDirectory(directory)
        SystemLogger.awaitIdleForTests()
        assertTrue(SystemLogger.logsFlow.value.none { it.message == "recover me" })

        val persisted = readJsonl(File(directory, SystemLogger.JSONL_FILE_NAME)).single { it.message == "recover me" }
        assertEquals(original, persisted)
        assertEquals(original.id, persisted.id)
        assertEquals(original.timestamp, persisted.timestamp)
        assertEquals(41L, persisted.projectId)
        assertEquals(7, persisted.chapterIndex)
        assertEquals("line one\nline two", persisted.details)
    }

    @Test
    fun legacyTextMigratesWithDeterministicUniqueIdsAndOriginalTime() = runBlocking {
        val directory = temporaryFolder.newFolder("legacy")
        val legacy = File(directory, SystemLogger.LEGACY_FILE_NAME)
        val legacyTimestampText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(System.currentTimeMillis() - 1_000L))
        legacy.writeText(
            """
            [$legacyTimestampText] [WARN] [TRANSLATION] [Proj#17] [Chap#9] repeated
              Details: first line
              second line
            [$legacyTimestampText] [WARN] [TRANSLATION] [Proj#17] [Chap#9] repeated
            """.trimIndent() + "\n",
            Charsets.UTF_8
        )
        legacy.setLastModified(123L)
        val legacyFileTimestamp = legacy.lastModified()

        val parsedOnce = SystemLogger.parseLegacyEntries(legacy.readLines())
        val parsedTwice = SystemLogger.parseLegacyEntries(legacy.readLines())
        assertEquals(parsedOnce.map { it.id }, parsedTwice.map { it.id })
        assertNotEquals(parsedOnce[0].id, parsedOnce[1].id)

        SystemLogger.initDirectory(directory)
        SystemLogger.awaitIdleForTests()
        val migrated = readJsonl(File(directory, SystemLogger.JSONL_FILE_NAME)).filter { it.message == "repeated" }
            .sortedBy { it.id }
        val expectedTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .parse(legacyTimestampText)!!.time

        assertEquals(2, migrated.size)
        assertTrue(migrated.all { it.timestamp == expectedTimestamp })
        assertTrue(migrated.none { it.timestamp == legacyFileTimestamp })
        assertTrue(migrated.all { it.projectId == 17L && it.chapterIndex == 9 })
        assertTrue(migrated.map { it.id }.all { it.startsWith("legacy-") })
        assertTrue(migrated.map { it.id }.toSet().size == 2)
        assertTrue(migrated.any { it.details == "first line\nsecond line" })
        assertTrue(File(directory, SystemLogger.JSONL_FILE_NAME).exists())
        assertTrue(File(directory, SystemLogger.LEGACY_FILE_NAME + ".legacy").exists())

        val firstRecovery = migrated.associate { it.id to it.timestamp }
        SystemLogger.resetForTests()
        SystemLogger.initDirectory(directory)
        SystemLogger.awaitIdleForTests()
        val secondRecovery = readJsonl(File(directory, SystemLogger.JSONL_FILE_NAME))
            .filter { it.message == "repeated" }
            .associate { it.id to it.timestamp }
        assertEquals(firstRecovery, secondRecovery)
    }

    @Test
    fun largeLegacyHistoryIsMigratedButMemoryRemainsBounded() = runBlocking {
        val directory = temporaryFolder.newFolder("large-legacy")
        val legacy = File(directory, SystemLogger.LEGACY_FILE_NAME)
        val legacyTimestampText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(System.currentTimeMillis() - 1_000L))
        legacy.bufferedWriter(Charsets.UTF_8).use { writer ->
            repeat(1_200) { index ->
                writer.appendLine("[$legacyTimestampText] [INFO] [LEGACY_BURST] entry-$index")
            }
        }

        SystemLogger.initDirectory(directory)
        SystemLogger.awaitIdleForTests()

        val visible = SystemLogger.logsFlow.value
        assertTrue(visible.none { it.tag == "LEGACY_BURST" })
        assertEquals(
            1_200,
            readJsonl(File(directory, SystemLogger.JSONL_FILE_NAME)).count { it.tag == "LEGACY_BURST" }
        )
    }

    @Test
    fun highFrequencyPersistenceRetainsUniqueRecentLazyKeys() = runBlocking {
        val directory = temporaryFolder.newFolder("burst")
        SystemLogger.initDirectory(directory)
        repeat(2_000) { SystemLogger.debug("BURST", "entry-$it") }
        SystemLogger.awaitIdleForTests()

        val visible = SystemLogger.logsFlow.value
        assertEquals(SystemLogger.MAX_MEMORY_LOGS, visible.size)
        assertEquals(visible.size, visible.map { it.id }.toSet().size)
        assertEquals(2_000, readJsonl(File(directory, SystemLogger.JSONL_FILE_NAME)).count { it.tag == "BURST" })
    }

    @Test
    fun clearDuringInitializationCannotRepublishRecoveredHistory() = runBlocking {
        val directory = temporaryFolder.newFolder("clear-race")
        val legacyTimestampText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(System.currentTimeMillis() - 1_000L))
        File(directory, SystemLogger.LEGACY_FILE_NAME).writeText(
            "[$legacyTimestampText] [ERROR] [OLD] must disappear\n",
            Charsets.UTF_8
        )

        SystemLogger.initDirectory(directory)
        SystemLogger.clearLogs()
        SystemLogger.awaitIdleForTests()

        assertTrue(SystemLogger.logsFlow.value.none { it.tag == "OLD" })
        assertTrue(readJsonl(File(directory, SystemLogger.JSONL_FILE_NAME)).none { it.tag == "OLD" })
    }

    private fun readJsonl(file: File): List<SystemLogEntry> = file.readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() }
        .mapNotNull { line -> adapter.fromJson(line) }
}
