package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.*
import org.junit.Test

class NativeManualBatchStateTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(2))
    private val batch = ManualTextBatch(CommandId("batch"), ref, 7, TextBatchChange.CLEAR_AND_RESET,
        listOf(TextBatchTarget(ItemId(41), 40, ObjectFingerprint("a".repeat(64)))))

    @Test fun savedMetadataRestoresTheExactCommandIdentityAndImmutablePayload() {
        val encoded = encodeManualBatch(batch)
        val decoded = requireNotNull(decodeManualBatch(encoded))
        assertEquals(batch.commandId, decoded.commandId); assertEquals(ref, decoded.ref)
        assertEquals(batch.digest(), decoded.digest()); assertEquals(batch.targets, decoded.targets)
        encoded.clear(); assertEquals(1, decoded.targets.size)
    }

    @Test fun invalidVersionsTruncatedMetadataAndDuplicateTargetsCannotRestoreAnExecutableDraft() {
        assertNull(decodeManualBatch(null)); assertNull(decodeManualBatch(emptyList()))
        assertNull(decodeManualBatch(encodeManualBatch(batch).apply { this[0] = "2" }))
        assertNull(decodeManualBatch(encodeManualBatch(batch).dropLast(1)))
        assertNull(decodeManualBatch(encodeManualBatch(batch).apply { this[5] = "-1" }))
        assertNull(decodeManualBatch(encodeManualBatch(batch).apply { addAll(takeLast(3)) }))
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
