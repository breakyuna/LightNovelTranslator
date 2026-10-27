package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.*
import org.junit.Test

class NativeManualBatchStateTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(2))
    private val batch = ManualTextBatch(CommandId("batch"), ref, 7, TextBatchChange.CLEAR_AND_RESET,
        listOf(TextBatchTarget(ItemId(41), 40, ObjectFingerprint("a".repeat(64)))))

    @Test fun nameAssignmentArrayIncludingEmptySlotsRestoresExactly() {
        val assignment = ManualTextBatch(CommandId("assign"), ref, 7, TextBatchChange.ASSIGN_NAMES,
            batch.targets, nameAssignment = listOf("first", "", "third"))
        val encoded = encodeManualBatch(assignment)
        assertEquals("5", encoded[0])
        val restored = requireNotNull(decodeManualBatch(encoded))
        assertEquals(assignment.nameAssignment, restored.nameAssignment)
        assertEquals(assignment.digest(), restored.digest())
        assertNull(decodeManualBatch(encoded.toMutableList().apply { this[7] = "[7]" }))
    }

    @Test fun sourceNameAssignmentArrayRestoresItsChangeAndScope() {
        val source = ManualTextBatch(CommandId("assign-source"), ref, 7, TextBatchChange.ASSIGN_SOURCE_NAMES,
            batch.targets, nameAssignment = listOf("", "源", "third"))
        val encoded = encodeManualBatch(source)
        assertEquals("5", encoded[0])
        val restored = requireNotNull(decodeManualBatch(encoded))
        assertEquals(TextBatchChange.ASSIGN_SOURCE_NAMES, restored.change)
        assertTrue(restored.assignsSourceNames)
        assertEquals(source.nameAssignment, restored.nameAssignment)
        assertEquals(source.digest(), restored.digest())
        val retargeted = requireNotNull(decodeManualBatch(encoded.toMutableList().apply {
            this[6] = TextBatchChange.ASSIGN_NAMES.name
        }))
        assertEquals(TextBatchChange.ASSIGN_NAMES, retargeted.change)
        assertNotEquals(source.digest(), retargeted.digest())
    }

    @Test fun nameTargetsRestoreWithoutChangingTheBoundDigestOrRegexMode() {
        for (target in listOf(TextReplacementTarget.NAMES, TextReplacementTarget.BOTH)) {
            val command = ManualTextBatch(CommandId("name-target"), ref, 7, TextBatchChange.REPLACE_TRANSLATION,
                batch.targets, TextReplacementOptions("old", "new", regex = true, target = target))
            val encoded = encodeManualBatch(command)
            assertEquals("4", encoded[0])
            val restored = requireNotNull(decodeManualBatch(encoded))
            assertEquals(command.digest(), restored.digest())
            assertEquals(target, restored.replacement!!.target)
            assertTrue(restored.replacement!!.regex)
            assertNull(decodeManualBatch(encoded.toMutableList().apply { this[11] = "unknown" }))
        }
    }

    @Test fun savedMetadataRestoresTheExactCommandIdentityAndImmutablePayload() {
        val encoded = encodeManualBatch(batch)
        val decoded = requireNotNull(decodeManualBatch(encoded))
        assertEquals(batch.commandId, decoded.commandId); assertEquals(ref, decoded.ref)
        assertEquals(batch.digest(), decoded.digest()); assertEquals(batch.targets, decoded.targets)
    }

    @Test fun invalidVersionsTruncatedMetadataAndDuplicateTargetsCannotRestoreAnExecutableDraft() {
        assertNull(decodeManualBatch(null))
        assertNull(decodeManualBatch(encodeManualBatch(batch).apply { this[0] = "2" }))
        assertNull(decodeManualBatch(encodeManualBatch(batch).dropLast(1)))
    }

    @Test fun replacementMetadataRestoresExactOptionsAndCannotBeReinterpretedAsAStatusBatch() {
        val replacement = ManualTextBatch(batch.commandId, ref, batch.expectedRevision, TextBatchChange.REPLACE_TRANSLATION,
            batch.targets, TextReplacementOptions("a\n", "\$1\\1", false))
        val restored = requireNotNull(decodeManualBatch(encodeManualBatch(replacement)))
        assertEquals(replacement.digest(), restored.digest()); assertEquals(replacement.replacement, restored.replacement)
        assertNull(decodeManualBatch(encodeManualBatch(replacement).apply { this[9] = "yes" }))
        assertNull(decodeManualBatch(encodeManualBatch(replacement).apply { this[6] = TextBatchChange.EXCLUDE.name }))
        assertNull(decodeManualBatch(encodeManualBatch(replacement).apply { this[0] = "1" }))
    }

    @Test fun regexBatchRestoresItsModeAndRejectsMalformedModeOrPattern() {
        val replacement = ManualTextBatch(batch.commandId, ref, batch.expectedRevision, TextBatchChange.REPLACE_TRANSLATION,
            batch.targets, TextReplacementOptions("(a)", "\$1", regex = true))
        val fields = encodeManualBatch(replacement)
        assertEquals("3", fields[0])
        assertEquals(replacement.digest(), requireNotNull(decodeManualBatch(fields)).digest())
        assertTrue(requireNotNull(decodeManualBatch(fields)).replacement!!.regex)
        assertNull(decodeManualBatch(fields.toMutableList().apply { this[10] = "false" }))
        assertNull(decodeManualBatch(fields.toMutableList().apply { this[7] = "(?=a)" }))
    }
}
