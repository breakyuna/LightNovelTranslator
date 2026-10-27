package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class ManualTextBatchTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val target = TextBatchTarget(ItemId(1), 0, ObjectFingerprint("a".repeat(64)))
    private fun batch(change: TextBatchChange = TextBatchChange.EXCLUDE, revision: Long = 0,
        identity: LocalProjectRef = ref, targets: List<TextBatchTarget> = listOf(target)) =
        ManualTextBatch(CommandId("command"), identity, revision, change, targets)

    @Test fun digestAndReceiptNamespaceBindEveryImmutableFieldAndAllChildCommands() {
        val original = batch()
        assertEquals(original.digest(), batch().digest())
        val changed = batch(change = TextBatchChange.CLEAR_AND_RESET)
        assertNotEquals(original.digest(), changed.digest())
        assertNotEquals(original.digest(), batch(revision = 1).digest())
        assertEquals(ManualTextBatch.receiptPrefix(original.commandId), ManualTextBatch.receiptPrefix(changed.commandId))
        assertNotEquals(original.itemCommand(0), changed.itemCommand(0))
    }

    @Test fun aBatchOwnsItsBoundedSelectionAndRejectsDuplicatesOrReorderedTargets() {
        val prepared = batch(targets = listOf(target, target.copy(id = ItemId(3), row = 2)))
        assertEquals(2, prepared.targets.size); assertNotEquals(prepared.itemCommand(0), prepared.itemCommand(1))
        assertThrows(IllegalArgumentException::class.java) { batch(targets = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { batch(targets = listOf(target, target)) }
        assertThrows(IllegalArgumentException::class.java) { batch(targets = prepared.targets.reversed()) }
    }

    @Test fun sourceNameAssignmentsUseTheirOwnChangeAndKeepTheSharedNameLimits() {
        val names = listOf("", "源", "source")
        val translated = ManualTextBatch(CommandId("command"), ref, 0, TextBatchChange.ASSIGN_NAMES,
            listOf(target), nameAssignment = names)
        val source = ManualTextBatch(CommandId("command"), ref, 0, TextBatchChange.ASSIGN_SOURCE_NAMES,
            listOf(target), nameAssignment = names)
        assertEquals(names, source.nameAssignment)
        assertTrue(source.assignsSourceNames); assertFalse(translated.assignsSourceNames)
        assertNotEquals(translated.digest(), source.digest())
        assertNotEquals(translated.itemCommand(0), source.itemCommand(0))
        assertEquals(emptyList<String>(), ManualTextBatch(CommandId("clear"), ref, 0,
            TextBatchChange.ASSIGN_SOURCE_NAMES, listOf(target), nameAssignment = emptyList()).nameAssignment)
        assertThrows(IllegalArgumentException::class.java) {
            ManualTextBatch(CommandId("command"), ref, 0, TextBatchChange.ASSIGN_SOURCE_NAMES, listOf(target))
        }
    }

    @Test fun replacementRequiresExplicitParametersAndBindsEachParameterToTheCommand() {
        fun replacement(options: TextReplacementOptions) = ManualTextBatch(CommandId("command"), ref, 0,
            TextBatchChange.REPLACE_TRANSLATION, listOf(target), options)
        val original = replacement(TextReplacementOptions("needle", "new"))
        val regexVariant = replacement(TextReplacementOptions("needle", "new", regex = true))
        assertNotEquals(original.digest(), regexVariant.digest())
        assertNotEquals(original.itemCommand(0), regexVariant.itemCommand(0))
        assertThrows(IllegalArgumentException::class.java) { batch(change = TextBatchChange.REPLACE_TRANSLATION) }
        assertThrows(IllegalArgumentException::class.java) {
            ManualTextBatch(CommandId("command"), ref, 0, TextBatchChange.EXCLUDE, listOf(target), TextReplacementOptions("a", "b"))
        }
    }
}
