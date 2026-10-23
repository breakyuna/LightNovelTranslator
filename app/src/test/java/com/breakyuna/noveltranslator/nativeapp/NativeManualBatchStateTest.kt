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
}
