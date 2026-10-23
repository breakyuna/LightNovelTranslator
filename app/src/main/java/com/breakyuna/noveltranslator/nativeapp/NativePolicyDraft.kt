package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID

class NativePolicyDraft(val ref: LocalProjectRef, val revision: Long, val base: ObjectFingerprint,
    val policy: ProjectTextPolicy, val command: CommandId = CommandId(UUID.randomUUID().toString()), val conflict: Boolean = false) {
    val dirty get() = policy.fingerprint() != base
    fun changed(policy: ProjectTextPolicy) = NativePolicyDraft(ref, revision, base, policy, conflict = conflict)
    companion object {
        fun from(snapshot: ProjectPolicySnapshot) = NativePolicyDraft(snapshot.ref, snapshot.revision, snapshot.fingerprint, snapshot.policy)
    }
}

internal fun encodePolicyDraft(draft: NativePolicyDraft): ArrayList<String> = arrayListOf(draft.ref.projectId.value,
    draft.ref.instanceId.value, draft.ref.generation.value.toString(), draft.revision.toString(), draft.base.sha256,
    draft.command.value, draft.conflict.toString(), ProjectTextPolicyCodec.encode(draft.policy))
internal fun decodePolicyDraft(value: ArrayList<String>?): NativePolicyDraft? = try {
    if (value == null || value.size != 8 || value.any { it.length > ProjectTextPolicyCodec.MAX_CHARS } ||
        listOf(0, 1, 5).any { value[it].length > 2048 }) null else {
        val revision = value[3].toLongOrNull()
        val generation = value[2].toLongOrNull()
        if (revision == null || revision < 0 || generation == null || generation <= 0 || value[6] !in listOf("true", "false")) null
        else NativePolicyDraft(LocalProjectRef(ProjectId(value[0]), ProjectInstanceId(value[1]), RecoveryGeneration(generation)),
            revision, ObjectFingerprint(value[4]), ProjectTextPolicyCodec.decode(value[7]), CommandId(value[5]), value[6] == "true")
    }
} catch (_: IllegalArgumentException) { null }
catch (_: TextProjectException) { null }
