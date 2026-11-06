package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One atomic change of the rule/prompt policy, including its complete order and flags. */
class PolicyChangeProposal(val id: ApprovalRequestId, val ref: LocalProjectRef, val chat: ChatId,
    val run: RunId, val command: CommandId, val revision: Long,
    val before: ProjectTextPolicy, val after: ProjectTextPolicy) {
    init { require(revision >= 0 && before.fingerprint() != after.fingerprint()) }
    val beforeText = ProjectTextPolicyCodec.encode(before)
    val afterText = ProjectTextPolicyCodec.encode(after)
    val digest = ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(buildJsonObject {
        put("version", 1); put("kind", "TEXT_POLICY"); put("id", id.value)
        put("project", ref.projectId.value); put("instance", ref.instanceId.value); put("generation", ref.generation.value)
        put("chat", chat.value); put("run", run.value); put("command", command.value); put("revision", revision)
        put("before", beforeText); put("after", afterText); put("conflict_policy", "REJECT_BATCH")
    }.toString())))
    val added = after.rules.count { rule -> before.rules.none { it.id == rule.id } }
    val deleted = before.rules.count { rule -> after.rules.none { it.id == rule.id } }
    val updated = after.rules.count { rule -> before.rules.any { it.id == rule.id && it != rule } }
    val reordered = before.rules.filter { old -> after.rules.any { it.id == old.id } }.map { it.id } !=
        after.rules.filter { new -> before.rules.any { it.id == new.id } }.map { it.id }
    val promptChanged = before.prompt != after.prompt || before.promptEnabled != after.promptEnabled
    /** Select content mutations by rule ID; ordering and prompt are independent operations.
     * Unselected deletions keep their original values. New rules append unless order is accepted. */
    fun selectedPolicy(ruleIds: Set<String>, acceptOrder: Boolean, acceptPrompt: Boolean): ProjectTextPolicy {
        val old = before.rules.associateBy { it.id }
        val proposed = after.rules.associateBy { it.id }
        require(ruleIds.all { it in old || it in proposed }) { "Unknown selected rule" }
        val merged = linkedMapOf<String, ProjectTextRule>()
        for (rule in before.rules) {
            if (rule.id !in ruleIds) merged[rule.id] = rule
            else proposed[rule.id]?.let { merged[it.id] = it }
        }
        for (rule in after.rules) if (rule.id in ruleIds && rule.id !in old) merged[rule.id] = rule
        val rules = if (acceptOrder) {
            val ordered = after.rules.mapNotNull { merged[it.id] }
            ordered + merged.values.filter { it.id !in proposed }
        } else merged.values.toList()
        return ProjectTextPolicy(rules, if (acceptPrompt) after.prompt else before.prompt,
            if (acceptPrompt) after.promptEnabled else before.promptEnabled)
    }

    fun intent() = ProjectPolicyEdit(command, ref, revision, before.fingerprint(), after)
    fun summary(state: TextApprovalState) = PolicyApprovalSummary(id, ref, chat, run, command, revision, digest,
        added, updated, deleted, reordered, promptChanged, state)
    override fun toString() = "PolicyChangeProposal(id=${id.value}, body=redacted)"
}

/** No policy body in conversation state or history lists. */
class PolicyApprovalSummary(val id: ApprovalRequestId, val ref: LocalProjectRef, val chat: ChatId,
    val run: RunId, val command: CommandId, val revision: Long, val digest: ObjectFingerprint,
    val added: Int, val updated: Int, val deleted: Int, val reordered: Boolean, val promptChanged: Boolean,
    val state: TextApprovalState)
