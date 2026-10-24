package com.breakyuna.noveltranslator.nativecore.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeDiagnosticsStoreTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    private var now = 1_700_000_000_000L

    private fun store(
        directory: File = temporary.root,
        maxFileBytes: Int = 512 * 1024,
        maxFiles: Int = 4,
        includeDetails: Boolean = false,
    ) = NativeDiagnosticsStore(
        directory = directory,
        clock = { now },
        limits = NativeDiagnosticsLimits(maxFileBytes = maxFileBytes, maxFiles = maxFiles),
        includeDetails = includeDetails,
    )

    @Test fun appendQueryAndDetailLookupUseStableIdsAndNewestFirstCursor() = runBlocking {
        val directory = temporary.newFolder("diagnostics")
        val store = store(directory, includeDetails = true)
        val first = store.append(NativeDiagnosticLevel.INFO, "MODEL", "first")
        now += 10
        val second = store.append(NativeDiagnosticLevel.WARN, "MODEL", "second", detail = "detail two")
        now += 10
        val third = store.append(NativeDiagnosticLevel.ERROR, "AGENT", "third")

        val firstPage = store.query(limit = 2)
        assertEquals(listOf(third.id, second.id), firstPage.entries.map { it.id })
        val next = requireNotNull(firstPage.nextCursor)
        assertEquals(next.beforeSequence, NativeDiagnosticCursor.parse(next.token).beforeSequence)

        val secondPage = store.query(limit = 2, cursor = next)
        assertEquals(listOf(first.id), secondPage.entries.map { it.id })
        assertNull(secondPage.nextCursor)

        val detail = requireNotNull(store.read(second.id))
        assertEquals("detail two", detail.detail)
        assertEquals(second.sequence, detail.sequence)
        assertTrue(detail.summary().hasDetail)
        assertNull(store.read("missing"))
        assertEquals(3, store.export(ByteArrayOutputStream()).recordCount)
    }

    @Test fun defaultStoreDropsDetailsAndRedactsCredentialsInMessages() = runBlocking {
        val store = store(temporary.newFolder("redaction"))
        val summary = store.append(
            NativeDiagnosticLevel.WARN,
            "MODEL",
            "request failed Authorization: Bearer abcdefghijklmnop api_key=sk-abcdefghijklmnop",
            detail = "prompt body must not be persisted",
        )
        val record = requireNotNull(store.read(summary.id))
        assertNull(record.detail)
        assertFalse(record.message.contains("abcdefghijklmnop"))
        assertTrue(record.message.contains(NativeDiagnosticRedaction.REDACTED))

        val output = ByteArrayOutputStream()
        store.export(output)
        val text = output.toString(Charsets.UTF_8.name())
        assertFalse(text.contains("abcdefghijklmnop"))
        assertFalse(text.contains("prompt body must not be persisted"))
        assertTrue(text.contains(NativeDiagnosticRedaction.REDACTED))
    }

    @Test fun enabledDetailsAreRedactedBeforeTheyReachDisk() = runBlocking {
        val store = store(temporary.newFolder("detail"), includeDetails = true)
        val summary = store.append(
            NativeDiagnosticLevel.ERROR,
            "MODEL",
            "transport failed",
            detail = "Authorization: Bearer abcdefghijklmnop\nSource: private novel body",
        )
        val detail = requireNotNull(requireNotNull(store.read(summary.id)).detail)
        assertFalse(detail.contains("abcdefghijklmnop"))
        assertFalse(detail.contains("private novel body"))
        assertTrue(detail.contains(NativeDiagnosticRedaction.REDACTED))
    }

    @Test fun rotationKeepsBoundedFileCountAndNewestRecords() = runBlocking {
        val directory = temporary.newFolder("rotation")
        val store = store(directory, maxFileBytes = 400, maxFiles = 3)
        val ids = ArrayList<String>()
        for (index in 1..30) {
            now += 1
            ids.add(store.append(NativeDiagnosticLevel.INFO, "ROTATION", "record $index " + "x".repeat(80)).id)
        }
        val page = store.query(limit = 100)
        assertTrue(page.entries.isNotEmpty())
        assertTrue(page.entries.size <= 6)
        assertEquals(ids.last(), page.entries.first().id)
        assertFalse(page.entries.any { it.id == ids.first() })

        val generations = directory.listFiles().orEmpty()
            .filter { it.name.startsWith("diagnostics.jsonl") }
        assertTrue(generations.size <= 3)
        assertTrue(generations.any { it.name == "diagnostics.jsonl" })
        assertEquals(ids.last(), store.read(ids.last())!!.id)
    }

    @Test fun interruptedTrailingWriteIsTruncatedBeforeTheNextAppend() = runBlocking {
        val directory = temporary.newFolder("interrupted")
        val store = store(directory)
        val first = store.append(NativeDiagnosticLevel.INFO, "IO", "first")
        val second = store.append(NativeDiagnosticLevel.INFO, "IO", "second")
        val active = File(directory, "diagnostics.jsonl")
        FileOutputStream(active, true).use { output ->
            output.write("{\"format\":\"lnt-diagnostics\",\"version\":1".toByteArray(Charsets.UTF_8))
        }
        now += 10
        val third = store.append(NativeDiagnosticLevel.INFO, "IO", "third")
        val page = store.query(limit = 10)
        assertEquals(listOf(third.id, second.id, first.id), page.entries.map { it.id })
        assertFalse(active.readText(Charsets.UTF_8).contains("\"id\":\"{\""))
    }

    @Test fun concurrentAppendsKeepUniqueIdsAndSequences() = runBlocking {
        val directory = temporary.newFolder("concurrent")
        val store = store(directory, maxFileBytes = 8 * 1024, maxFiles = 4)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map { worker ->
                executor.submit(Callable {
                    runBlocking {
                        (1..10).map { index ->
                            store.append(NativeDiagnosticLevel.INFO, "CONCURRENT", "worker $worker item $index")
                        }
                    }
                })
            }
            val summaries = futures.flatMap { it.get() }
            assertEquals(80, summaries.map { it.id }.distinct().size)
            val page = store.query(limit = 200)
            assertEquals(80, page.entries.size)
            assertEquals(80, page.entries.map { it.sequence }.distinct().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun clearRemovesHistoryAndInvalidatesOldCursor() = runBlocking {
        val store = store(temporary.newFolder("clear"))
        store.append(NativeDiagnosticLevel.INFO, "CLEAR", "one")
        now += 1
        store.append(NativeDiagnosticLevel.INFO, "CLEAR", "two")
        val cursor = requireNotNull(store.query(limit = 1).nextCursor)
        store.clear()
        assertTrue(store.query(limit = 10).entries.isEmpty())
        try {
            store.query(limit = 10, cursor = cursor)
            fail("Accepted a cursor from before clear")
        } catch (_: IllegalArgumentException) {
        }
        store.append(NativeDiagnosticLevel.INFO, "CLEAR", "three")
        assertEquals(1, store.query(limit = 10).entries.size)
    }
}
