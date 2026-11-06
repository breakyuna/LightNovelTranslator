package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.ArrayDeque

/** Incremental, session-local undo. No fixed entry count and no stored write authorization. */
internal class NativeDraftHistory {
    private data class Binding(val ref: LocalProjectRef, val item: ItemId, val revision: Long,
                               val fingerprint: ObjectFingerprint)
    private data class Value(val text: String, val status: TextItemStatus)
    private data class Patch(val offset: Int, val removed: String, val inserted: String,
                             val before: TextItemStatus, val after: TextItemStatus)
    private var binding: Binding? = null
    private var current: Value? = null
    private val undo = ArrayDeque<Patch>()
    private val redo = ArrayDeque<Patch>()
    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun record(draft: NativeEditDraft) {
        val key = Binding(draft.ref, draft.item.id, draft.revision, draft.fingerprint)
        val next = Value(draft.text, draft.status)
        val previous = current
        if (binding != key || previous == null) {
            clear(); binding = key; current = next; return
        }
        if (previous == next) return
        var start = 0
        while (start < minOf(previous.text.length, next.text.length) && previous.text[start] == next.text[start]) start++
        var oldEnd = previous.text.length
        var newEnd = next.text.length
        while (oldEnd > start && newEnd > start && previous.text[oldEnd - 1] == next.text[newEnd - 1]) {
            oldEnd--; newEnd--
        }
        undo.addLast(Patch(start, previous.text.substring(start, oldEnd), next.text.substring(start, newEnd),
            previous.status, next.status))
        redo.clear(); current = next
    }

    fun restore(draft: NativeEditDraft, forward: Boolean): NativeEditDraft? {
        if (binding != Binding(draft.ref, draft.item.id, draft.revision, draft.fingerprint) ||
            current != Value(draft.text, draft.status) || draft.conflict) return null
        val from = if (forward) redo else undo
        if (from.isEmpty()) return null
        val patch = from.removeLast()
        val old = if (forward) patch.removed else patch.inserted
        val replacement = if (forward) patch.inserted else patch.removed
        check(draft.text.regionMatches(patch.offset, old, 0, old.length))
        val text = draft.text.substring(0, patch.offset) + replacement + draft.text.substring(patch.offset + old.length)
        val status = if (forward) patch.after else patch.before
        (if (forward) undo else redo).addLast(patch)
        current = Value(text, status)
        return draft.changed(text, status) // Always a fresh command, never replay an older save.
    }

    fun clear() { binding = null; current = null; undo.clear(); redo.clear() }
}
