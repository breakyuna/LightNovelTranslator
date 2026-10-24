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
        assertEquals(64, original.digest().length)
        for (changed in listOf(batch(change = TextBatchChange.CLEAR_AND_RESET), batch(revision = 1),
            batch(identity = ref.copy(generation = RecoveryGeneration(2))),
            batch(targets = listOf(target.copy(row = 1))),
            batch(targets = listOf(target.copy(fingerprint = ObjectFingerprint("b".repeat(64))))))) {
            assertNotEquals(original.digest(), changed.digest())
            assertEquals(ManualTextBatch.receiptPrefix(original.commandId), ManualTextBatch.receiptPrefix(changed.commandId))
            assertNotEquals(original.itemCommand(0), changed.itemCommand(0))
        }
    }

    @Test fun aBatchOwnsItsBoundedSelectionAndRejectsDuplicatesOrReorderedTargets() {
        val targets = mutableListOf(target, target.copy(id = ItemId(3), row = 2))
        val prepared = batch(targets = targets); targets.clear()
        assertEquals(2, prepared.targets.size); assertNotEquals(prepared.itemCommand(0), prepared.itemCommand(1))
        assertThrows(IllegalArgumentException::class.java) { batch(targets = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { batch(targets = listOf(target, target)) }
        assertThrows(IllegalArgumentException::class.java) { batch(targets = prepared.targets.reversed()) }
        assertThrows(IllegalArgumentException::class.java) {
            batch(targets = (1L..(ManualTextBatch.MAX_TARGETS.toLong() + 1)).map { target.copy(id = ItemId(it)) })
        }
        assertThrows(IllegalArgumentException::class.java) { batch(revision = -1) }
    }

    @Test fun replacementRequiresExplicitParametersAndBindsEachParameterToTheCommand() {
        fun replacement(options: TextReplacementOptions) = ManualTextBatch(CommandId("command"), ref, 0,
            TextBatchChange.REPLACE_TRANSLATION, listOf(target), options)
        val original = replacement(TextReplacementOptions("needle", "new"))
        assertNotEquals(original.digest(), replacement(TextReplacementOptions("needle", "new", regex = true)).digest())
        for (changed in listOf(TextReplacementOptions("other", "new"), TextReplacementOptions("needle", "other"),
            TextReplacementOptions("needle", "new", false))) {
            assertNotEquals(original.digest(), replacement(changed).digest())
            assertNotEquals(original.itemCommand(0), replacement(changed).itemCommand(0))
        }
        assertThrows(IllegalArgumentException::class.java) { batch(change = TextBatchChange.REPLACE_TRANSLATION) }
        assertThrows(IllegalArgumentException::class.java) {
            ManualTextBatch(CommandId("command"), ref, 0, TextBatchChange.EXCLUDE, listOf(target), TextReplacementOptions("a", "b"))
        }
    }
}
