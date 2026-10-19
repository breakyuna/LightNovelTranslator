package com.breakyuna.noveltranslator.core.logger

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Persistence contract of [SystemLogger]: what is written must be readable again after a
 * restart, the rotation must stay bounded, and nothing sensitive may reach the file.
 */
class SystemLoggerPersistenceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var directory: File

    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(SystemLogEntry::class.java)

    @Before
    fun setUp() = runBlocking {
        SystemLogger.resetForTests()
        directory = temporaryFolder.newFolder("logs")
    }

    @After
    fun tearDown() = runBlocking {
        SystemLogger.resetForTests()
    }

    @Test
    fun recentEntriesReadsThePersistedTailAfterASimulatedRestart() = runBlocking {
        SystemLogger.initDirectory(directory)
        repeat(10) { index -> SystemLogger.info("RECENT", "entry-$index") }
        SystemLogger.awaitIdleForTests()

        // A new process starts with an empty in-memory buffer.
        SystemLogger.resetForTests()
        SystemLogger.initDirectory(directory)
        SystemLogger.awaitIdleForTests()
        assertTrue(SystemLogger.logsFlow.value.none { it.tag == "RECENT" })

        val recent = SystemLogger.recentEntries(4).filter { it.tag == "RECENT" }
        assertEquals(listOf("entry-6", "entry-7", "entry-8", "entry-9"), recent.map { it.message })
    }

    @Test
    fun recentEntriesReturnsEmptyListWhenNothingWasPersisted() = runBlocking {
        SystemLogger.initDirectory(directory)
        SystemLogger.awaitIdleForTests()
        SystemLogger.resetForTests()
        assertTrue(SystemLogger.recentEntries(10).isEmpty())
        assertTrue(SystemLogger.recentEntries(0).isEmpty())
    }

    @Test
    fun exportTextContainsThePersistedHistory() = runBlocking {
        SystemLogger.initDirectory(directory)
        SystemLogger.error("EXPORT", "something broke", details = "line one\nline two")
        SystemLogger.awaitIdleForTests()
        SystemLogger.resetForTests()

        val text = SystemLogger.exportText()
        assertTrue(text.contains("=== NOVEL TRANSLATOR STUDIO - SYSTEM RUNTIME LOG ==="))
        assertTrue(text.contains("[EXPORT] something broke"))
        assertTrue(text.contains("line one"))
        assertTrue(text.contains("Redaction: API keys"))
    }

    @Test
    fun rotationKeepsThreeGenerationsAndStaysBounded() = runBlocking {
        SystemLogger.initDirectory(directory)
        // ~6 MB of entries: the 2 MB cap rotates three times, so .3 must exist and .4 must not.
        repeat(420) { index -> SystemLogger.info("BIG", "entry-$index " + "x".repeat(15_000)) }
        SystemLogger.awaitIdleForTests()

        assertTrue(File(directory, SystemLogger.JSONL_FILE_NAME + ".1").exists())
        assertTrue(File(directory, SystemLogger.JSONL_FILE_NAME + ".2").exists())
        assertTrue(File(directory, SystemLogger.JSONL_FILE_NAME + ".3").exists())
        assertFalse(
            "a fourth generation must never appear",
            File(directory, SystemLogger.JSONL_FILE_NAME + ".4").exists()
        )
        val active = File(directory, SystemLogger.JSONL_FILE_NAME)
        assertTrue("active file must stay under the cap", active.length() <= 2L * 1024L * 1024L + 20_000L)

        // Every generation stays readable and the tail spans them in chronological order.
        val persisted = readAll().filter { it.tag == "BIG" }
        assertEquals(420, persisted.size)
        assertEquals(persisted.size, persisted.map { it.id }.toSet().size)
        val recent = SystemLogger.recentEntries(3).filter { it.tag == "BIG" }
        assertEquals(listOf("entry-417", "entry-418", "entry-419"), recent.map { it.message.take(11) })
    }

    @Test
    fun secretsNeverReachThePersistedFile() = runBlocking {
        SystemLogger.initDirectory(directory)
        val key = "sk-abcdef1234567890abcdef1234567890"
        SystemLogger.warn(
            tag = "SECRET",
            message = "request failed with Authorization: Bearer $key and api_key=$key",
            details = "Prompt: 他把长剑插回鞘中，转身走向城门。"
        )
        SystemLogger.awaitIdleForTests()

        val persisted = readAll().single { it.tag == "SECRET" }
        assertFalse(persisted.message.contains(key))
        assertFalse(persisted.details.orEmpty().contains(key))
        assertTrue(persisted.message.contains("[REDACTED]"))
        assertFalse("novel body must not be persisted", persisted.details.orEmpty().contains("转身走向城门"))

        // And the on-disk text itself is clean.
        val raw = File(directory, SystemLogger.JSONL_FILE_NAME).readText(Charsets.UTF_8)
        assertFalse(raw.contains(key))
    }

    @Test
    fun clearRemovesEveryGeneration() = runBlocking {
        SystemLogger.initDirectory(directory)
        repeat(5) { SystemLogger.info("CLEAR", "entry-$it") }
        SystemLogger.awaitIdleForTests()

        SystemLogger.clearLogs()
        SystemLogger.awaitIdleForTests()

        assertTrue(File(directory, SystemLogger.JSONL_FILE_NAME).readText(Charsets.UTF_8).isBlank())
        assertFalse(File(directory, SystemLogger.JSONL_FILE_NAME + ".1").exists())
        assertFalse(File(directory, SystemLogger.JSONL_FILE_NAME + ".2").exists())
        assertFalse(File(directory, SystemLogger.JSONL_FILE_NAME + ".3").exists())
    }

    /** Oldest generation first, matching the on-disk chronological order. */
    private fun readAll(): List<SystemLogEntry> =
        listOf(".3", ".2", ".1", "")
            .mapNotNull { suffix ->
                val name = if (suffix.isEmpty()) SystemLogger.JSONL_FILE_NAME else SystemLogger.JSONL_FILE_NAME + suffix
                val file = File(directory, name)
                if (!file.exists()) null else file.readLines(Charsets.UTF_8)
            }
            .flatten()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { adapter.fromJson(line) }.getOrNull() }
}
