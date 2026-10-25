package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.*
import org.junit.Test

class NativeDraftHistoryTest {
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private fun draft(text: String = "original", revision: Long = 0): NativeEditDraft {
        val item = TextProjectItem(ItemId(1), 0, "source", "original", TextItemStatus.NONE,
            TextProjectFingerprint.item(ItemId(1), 0, "source", "original", TextItemStatus.NONE))
        return NativeEditDraft(ref, item, revision, item.fingerprint, text, TextItemStatus.NONE, CommandId("initial"))
    }

    @Test fun unicodeTextAndStatusUndoWithFreshCommandsAndRedoBranchIsReplaced() {
        val history = NativeDraftHistory()
        val original = draft()
        history.record(original)
        val first = original.changed("😀e\u0301", TextItemStatus.PROCESSED)
        history.record(first)
        val second = first.changed(status = TextItemStatus.EXCLUDED)
        history.record(second)
        val undoStatus = requireNotNull(history.restore(second, false))
        assertEquals(TextItemStatus.PROCESSED, undoStatus.status)
        assertEquals(first.text, undoStatus.text)
        assertNotEquals(first.command, undoStatus.command)
        val undoText = requireNotNull(history.restore(undoStatus, false))
        assertEquals(original.text, undoText.text)
        assertEquals(TextItemStatus.NONE, undoText.status)
        val redo = requireNotNull(history.restore(undoText, true))
        assertEquals(first.text, redo.text)
        history.record(redo.changed("new branch"))
        assertFalse(history.canRedo)
    }

    @Test fun historyHasNoFixedEntryLimitAndBaselineChangeClearsIt() {
        val history = NativeDraftHistory()
        var current = draft("")
        history.record(current)
        repeat(2048) {
            current = current.changed(current.text + "x")
            history.record(current)
        }
        repeat(2048) { current = requireNotNull(history.restore(current, false)) }
        assertEquals("", current.text)
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        history.record(draft("new baseline", 1))
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test fun staleIdentityOrConflictedDraftCannotConsumeUndo() {
        val history = NativeDraftHistory()
        val start = draft()
        history.record(start)
        val changed = start.changed("edited")
        history.record(changed)
        assertNull(history.restore(draft("edited", 1), false))
        val conflict = NativeEditDraft(changed.ref, changed.item, changed.revision, changed.fingerprint,
            changed.text, changed.status, changed.command, conflict = true)
        assertNull(history.restore(conflict, false))
        assertTrue(history.canUndo)
        history.clear()
        assertFalse(history.canUndo)
    }
}
