package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.*

/** Small metadata only. A restored draft still requires an explicit confirmation and revision check. */
internal fun encodeManualBatch(batch: ManualTextBatch): ArrayList<String> = arrayListOf(
    "1", batch.commandId.value, batch.ref.projectId.value, batch.ref.instanceId.value,
    batch.ref.generation.value.toString(), batch.expectedRevision.toString(), batch.change.name,
).apply { batch.targets.forEach { add(it.id.value.toString()); add(it.row.toString()); add(it.fingerprint.sha256) } }

internal fun decodeManualBatch(values: List<String>?): ManualTextBatch? = runCatching {
    if (values == null || values.size !in 10..307 || values[0] != "1" || (values.size - 7) % 3 != 0) return null
    ManualTextBatch(CommandId(values[1]), LocalProjectRef(ProjectId(values[2]), ProjectInstanceId(values[3]),
        RecoveryGeneration(values[4].toLong())), values[5].toLong(), TextBatchChange.valueOf(values[6]),
        values.drop(7).chunked(3).map { TextBatchTarget(ItemId(it[0].toLong()), it[1].toInt(), ObjectFingerprint(it[2])) })
}.getOrNull()
