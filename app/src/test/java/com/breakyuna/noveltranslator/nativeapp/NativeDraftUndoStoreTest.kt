package com.breakyuna.noveltranslator.nativeapp

import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeDraftUndoStoreTest {
    private lateinit var directory: File
    private lateinit var store: NativeDraftUndoStore

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        directory = File(context.cacheDir, "draft-undo-${UUID.randomUUID()}")
        store = NativeDraftUndoStore(context, directory)
    }

    @After fun cleanup() {
        directory.deleteRecursively()
    }

    private fun key(project: String = "project", instance: String = "instance",
                    generation: Long = 1, asset: Long = 1, revision: Long = 3,
                    item: Long = 7, fingerprint: String = "a".repeat(64),
                    scope: NativeDraftUndoScope = NativeDraftUndoScope.BODY_STATUS) =
        NativeDraftUndoKey(LocalProjectRef(ProjectId(project), ProjectInstanceId(instance),
            RecoveryGeneration(generation)), asset, revision, ItemId(item),
            ObjectFingerprint(fingerprint), scope)

    private fun snapshot(text: String, status: TextItemStatus = TextItemStatus.PROCESSED,
                         sourceNames: List<String> = emptyList(),
                         translationNames: List<String> = emptyList()) =
        NativeDraftUndoSnapshot(text, status, sourceNames, translationNames)

    @Test fun roundTripKeepsUndoRedoPointerAndNameArrays() = runBlocking {
        val key = key()
        val timeline = NativeDraftUndoTimeline(key, listOf(
            snapshot("", TextItemStatus.NONE),
            snapshot("one", sourceNames = listOf("Alice"), translationNames = listOf("艾莉丝")),
            snapshot("two", TextItemStatus.EXCLUDED, listOf("Alice"), listOf("艾莉丝")),
        ), 1)
        store.requestSave(timeline)
        store.awaitIdle()
        val loaded = store.load(key)
        assertTrue(loaded is NativeDraftUndoLoad.Restored)
        val restored = (loaded as NativeDraftUndoLoad.Restored).timeline
        assertEquals(key, restored.key)
        assertEquals(1, restored.currentIndex)
        assertEquals(1, restored.undoCount)
        assertTrue(restored.canUndo)
        assertTrue(restored.canRedo)
        assertEquals(listOf("Alice"), restored.states[1].sourceNames)
        assertEquals(listOf("艾莉丝"), restored.states[1].translationNames)
        assertEquals(TEXT_REDACTED, restored.toString())

        val history = NativeDraftUndoHistory()
        assertTrue(history.restore(restored, key, snapshot("one", sourceNames = listOf("Alice"),
            translationNames = listOf("艾莉丝"))))
        assertEquals("", history.undo(key, history.currentSnapshot()!!)!!.text)
        assertEquals("one", history.redo(key, history.currentSnapshot()!!)!!.text)
        assertEquals("two", history.redo(key, history.currentSnapshot()!!)!!.text)
    }

    @Test fun revisionAndScopeMismatchNeverRestore() = runBlocking {
        val original = key()
        store.requestSave(NativeDraftUndoTimeline(original, listOf(snapshot("a"), snapshot("b")), 1))
        store.awaitIdle()
        assertTrue(store.load(key(revision = 4)) is NativeDraftUndoLoad.Stale)
        assertFalse(store.fileFor(original).exists())

        store.requestSave(NativeDraftUndoTimeline(original, listOf(snapshot("a"), snapshot("b")), 1))
        store.awaitIdle()
        assertTrue(store.load(key(scope = NativeDraftUndoScope.NAMES)) is NativeDraftUndoLoad.Missing)
        assertTrue(store.fileFor(original).exists())
    }

    @Test fun corruptAndOldFormatsFailClosedAndAreDeleted() = runBlocking {
        val key = key()
        store.requestSave(NativeDraftUndoTimeline(key, listOf(snapshot("a"), snapshot("b")), 1))
        store.awaitIdle()
        val file = store.fileFor(key)
        file.writeText("{not-json", Charsets.UTF_8)
        assertTrue(store.load(key) is NativeDraftUndoLoad.Corrupt)
        assertFalse(file.exists())

        store.requestSave(NativeDraftUndoTimeline(key, listOf(snapshot("a"), snapshot("b")), 1))
        store.awaitIdle()
        val old = file.readText(Charsets.UTF_8).replace("\"version\":1", "\"version\":0")
        file.writeText(old, Charsets.UTF_8)
        assertTrue(store.load(key) is NativeDraftUndoLoad.Unsupported)
        assertFalse(file.exists())
    }

    @Test fun switchingProjectOrScopeCannotReadAnotherTimeline() = runBlocking {
        val first = key(project = "first")
        val second = key(project = "second")
        store.requestSave(NativeDraftUndoTimeline(first, listOf(snapshot("a"), snapshot("b")), 1))
        store.awaitIdle()
        assertTrue(store.load(second) is NativeDraftUndoLoad.Missing)
        assertTrue(store.load(first) is NativeDraftUndoLoad.Restored)
    }

    @Test fun saveDropsOldStatesAndHonoursTheByteBudget() = runBlocking {
        val key = key()
        val many = (0..79).map { snapshot("text-$it") }
        store.requestSave(NativeDraftUndoTimeline(key, many, 79))
        store.awaitIdle()
        val capped = (store.load(key) as NativeDraftUndoLoad.Restored).timeline
        assertEquals(NATIVE_DRAFT_UNDO_MAX_STATES, capped.states.size)
        assertEquals(NATIVE_DRAFT_UNDO_MAX_STATES - 1, capped.undoCount)
        assertEquals("text-79", capped.states.last().text)

        val large = (0..39).map { snapshot("x".repeat(65_536)) }
        store.requestSave(NativeDraftUndoTimeline(key, large, 39))
        store.awaitIdle()
        val trimmed = (store.load(key) as NativeDraftUndoLoad.Restored).timeline
        assertTrue(trimmed.states.size < large.size)
        assertTrue(store.fileFor(key).length() <= NATIVE_DRAFT_UNDO_MAX_FILE_BYTES)
        assertEquals(large.last().text, trimmed.states[trimmed.currentIndex].text)
        assertEquals(trimmed.states.lastIndex, trimmed.currentIndex)
    }

    @Test fun clearOnlyRemovesTheSelectedTimeline() = runBlocking {
        val first = key(project = "first")
        val second = key(project = "second")
        store.requestSave(NativeDraftUndoTimeline(first, listOf(snapshot("a"), snapshot("b")), 1))
        store.requestSave(NativeDraftUndoTimeline(second, listOf(snapshot("c"), snapshot("d")), 1))
        store.awaitIdle()
        store.requestClear(first)
        store.awaitIdle()
        assertTrue(store.load(first) is NativeDraftUndoLoad.Missing)
        assertTrue(store.load(second) is NativeDraftUndoLoad.Restored)
    }

    @Test fun historyRejectsOldRevisionAndStopsAtTheStepBound() {
        val key = key()
        val history = NativeDraftUndoHistory()
        for (index in 0..80) history.record(key, snapshot("state-$index"))
        assertEquals(NATIVE_DRAFT_UNDO_MAX_STATES - 1, history.undoCount)
        val restored = history.timeline()!!
        assertFalse(NativeDraftUndoHistory().restore(restored, key(revision = 4), snapshot("state-80")))

        var undone = 0
        while (true) {
            val current = history.currentSnapshot()!!
            if (history.undo(key, current) == null) break
            undone++
        }
        assertEquals(NATIVE_DRAFT_UNDO_MAX_STATES - 1, undone)
        assertTrue(history.canRedo)
        val current = history.currentSnapshot()!!
        assertNotNull(history.redo(key, current))
    }

    private companion object {
        const val TEXT_REDACTED = "NativeDraftUndoTimeline(redacted)"
    }
}
