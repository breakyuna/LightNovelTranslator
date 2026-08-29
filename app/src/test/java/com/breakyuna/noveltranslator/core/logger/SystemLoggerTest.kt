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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
    fun clearRemovesPersistedHistory() = runBlocking {
        val directory = temporaryFolder.newFolder("clear")
        SystemLogger.initDirectory(directory)
        SystemLogger.awaitIdleForTests()
        SystemLogger.error("OLD", "must disappear")
        SystemLogger.awaitIdleForTests()

        SystemLogger.clearLogs()
        SystemLogger.awaitIdleForTests()

        assertTrue(SystemLogger.logsFlow.value.none { it.tag == "OLD" })
        assertTrue(readJsonl(File(directory, SystemLogger.JSONL_FILE_NAME)).none { it.tag == "OLD" })
    }

    private fun readJsonl(file: File): List<SystemLogEntry> = file.readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() }
        .mapNotNull { line -> adapter.fromJson(line) }
}
