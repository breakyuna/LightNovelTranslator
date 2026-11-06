package com.breakyuna.noveltranslator.nativecore.domain

/**
 * Durable Agent recovery evidence. A checkpoint records what the application committed before
 * and after provider/tool boundaries; it never authorizes replaying a paid request or a tool
 * whose side effect is unknown. Reads are scoped to the exact project instance, recovery
 * generation, chat and branch, and must fail closed instead of returning another project's data.
 */
interface AgentCheckpointPort {
    /**
     * Persist [envelope] exactly once. Implementations must require the envelope's
     * [CheckpointEnvelope.projectRef] scope; a missing or foreign scope is rejected.
     */
    suspend fun save(envelope: CheckpointEnvelope)

    /** Newest checkpoint for this exact scope, or null when the scope has no checkpoint. */
    suspend fun latest(ref: LocalProjectRef, chat: ChatId, branch: BranchId): CheckpointEnvelope?

    /** Newest-first checkpoints for this exact scope, at most [limit]. Corrupt state is an error. */
    suspend fun list(ref: LocalProjectRef, chat: ChatId, branch: BranchId, limit: Int): List<CheckpointEnvelope>
}

/** Base type for durable checkpoint failures that callers may surface without reading chat text. */
open class AgentCheckpointException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Stored data is malformed, foreign to the requested scope, or uses an unknown version. */
class AgentCheckpointCorruptException(message: String, cause: Throwable? = null) :
    AgentCheckpointException(message, cause)

/** A checkpoint, partition or query exceeded an explicit storage bound. */
class AgentCheckpointLimitException(message: String, cause: Throwable? = null) :
    AgentCheckpointException(message, cause)
