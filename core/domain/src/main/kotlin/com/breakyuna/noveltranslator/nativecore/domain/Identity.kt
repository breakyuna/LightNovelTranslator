package com.breakyuna.noveltranslator.nativecore.domain

private fun requireIdentity(value: String) {
    require(value.isNotBlank() && value.none { it.isISOControl() }) { "Invalid identity" }
}

@JvmInline
value class ProjectId(val value: String) {
    init { requireIdentity(value) }
}

@JvmInline
value class ChatId(val value: String) {
    init { requireIdentity(value) }
}

@JvmInline
value class RunId(val value: String) {
    init { requireIdentity(value) }
}

@JvmInline
value class CommandId(val value: String) {
    init { requireIdentity(value) }
}

/** Public LG item identity remains a positive SQLite integer, never a row position. */
@JvmInline
value class ItemId(val value: Long) {
    init { require(value > 0) { "Item identity must be positive" } }
}

data class ChatIdentity(val projectId: ProjectId, val chatId: ChatId)

data class RunIdentity(
    val projectId: ProjectId,
    val chatId: ChatId?,
    val runId: RunId,
    val commandId: CommandId,
)

@JvmInline
value class MessageId(val value: String) {
    init { requireIdentity(value) }
}

@JvmInline
value class ToolCallId(val value: String) {
    init { requireIdentity(value) }
}

@JvmInline
value class RequestAttemptId(val value: String) {
    init { requireIdentity(value) }
}

@JvmInline
value class BranchId(val value: String) {
    init { requireIdentity(value) }
}

@JvmInline
value class AttachmentId(val value: String) {
    init { requireIdentity(value) }
}

/** One prepared batch of project changes; the user approves this identity, not a position. */
@JvmInline
value class ChangeSetId(val value: String) {
    init { requireIdentity(value) }
}

/** Stable approval identity supplied by the caller (tool call or explicit command). */
@JvmInline
value class ApprovalRequestId(val value: String) {
    init { requireIdentity(value) }
}

/**
 * Private working copy identity. Two copies of the same exchangeable project have different
 * instance IDs and never share approvals, runs or caches (ARCHITECTURE §3.4).
 */
@JvmInline
value class ProjectInstanceId(val value: String) {
    init { requireIdentity(value) }
}

/**
 * Bumped when the active copy is replaced or restored. Old commands, callbacks and grants
 * must be rejected by generation; the value is never reused or restored from a backup.
 */
@JvmInline
value class RecoveryGeneration(val value: Long) {
    init { require(value > 0) { "Recovery generation starts at 1" } }
}
