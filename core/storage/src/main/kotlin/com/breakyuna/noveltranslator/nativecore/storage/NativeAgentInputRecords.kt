package com.breakyuna.noveltranslator.nativecore.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query

/**
 * One persisted Chat input queue. Draft text lives in [NativeAgentInputDraftRow]; this row keeps
 * only queue identity, budgets, pause/Run state, pending append and the last confirmed receipt.
 * The composite primary key binds every fact to the full instance/generation/Project/Chat.
 */
@Entity(
    tableName = "native_agent_input_queues",
    primaryKeys = ["instanceId", "generation", "projectId", "chatId"],
)
internal data class NativeAgentInputQueueRow(
    val instanceId: String,
    val generation: Long,
    val projectId: String,
    val chatId: String,
    val paused: Boolean,
    val runId: String?,
    val runCommandId: String?,
    val maxDrafts: Int,
    val maxDraftChars: Int,
    val maxTotalChars: Int,
    val revision: Long,
    val pendingDraftId: String?,
    val pendingDraftRevision: Long?,
    val pendingAttemptId: String?,
    val pendingMessageId: String?,
    val lastReceiptRunId: String?,
    val lastReceiptRunCommandId: String?,
    val lastReceiptDraftId: String?,
    val lastReceiptDraftRevision: Long?,
    val lastReceiptAttemptId: String?,
    val lastReceiptMessageId: String?,
    val updatedAt: Long,
) {
    override fun toString(): String = "NativeAgentInputQueueRow(chat=$chatId, revision=$revision)"
}

/**
 * One ordered draft. The primary key prevents duplicate draft identities in the same Chat; the
 * unique index prevents two drafts sharing one position. [state] is QUEUED or PENDING_APPEND and
 * is validated against the queue row on read; it does not drive transitions.
 */
@Entity(
    tableName = "native_agent_input_drafts",
    primaryKeys = ["instanceId", "generation", "projectId", "chatId", "draftId"],
    indices = [Index(
        value = ["instanceId", "generation", "projectId", "chatId", "position"],
        unique = true,
    )],
)
internal data class NativeAgentInputDraftRow(
    val instanceId: String,
    val generation: Long,
    val projectId: String,
    val chatId: String,
    val draftId: String,
    val position: Int,
    val text: String,
    val revision: Long,
    val runId: String?,
    val runCommandId: String?,
    val state: String,
) {
    override fun toString(): String =
        "NativeAgentInputDraftRow(id=$draftId, revision=$revision, chars=${text.length})"
}

/** One persisted steer ledger stage. Empty ledgers still have a row so the stage is factual. */
@Entity(
    tableName = "native_agent_steer_runs",
    primaryKeys = ["instanceId", "generation", "projectId", "chatId", "runId", "runCommandId"],
)
internal data class NativeAgentSteerRunRow(
    val instanceId: String,
    val generation: Long,
    val projectId: String,
    val chatId: String,
    val runId: String,
    val runCommandId: String,
    val stage: String,
    val revision: Long,
    val updatedAt: Long,
) {
    override fun toString(): String = "NativeAgentSteerRunRow(run=$runId, stage=$stage, revision=$revision)"
}

/**
 * One steer attempt fact. The primary key prevents duplicate steer identities in the same Run;
 * the unique position index preserves submission order. Rejected attempts are not persisted.
 */
@Entity(
    tableName = "native_agent_steer_attempts",
    primaryKeys = ["instanceId", "generation", "projectId", "chatId", "runId", "runCommandId", "steerId"],
    indices = [Index(
        value = ["instanceId", "generation", "projectId", "chatId", "runId", "runCommandId", "position"],
        unique = true,
    )],
)
internal data class NativeAgentSteerAttemptRow(
    val instanceId: String,
    val generation: Long,
    val projectId: String,
    val chatId: String,
    val runId: String,
    val runCommandId: String,
    val steerId: String,
    val position: Int,
    val text: String,
    val submittedDuring: String,
    val status: String,
    val reason: String?,
    val injectedDuring: String?,
) {
    override fun toString(): String =
        "NativeAgentSteerAttemptRow(id=$steerId, status=$status, chars=${text.length})"
}

@Dao
internal interface NativeAgentInputDao {
    @Query("SELECT * FROM native_agent_input_queues WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat")
    fun inputQueue(instance: String, generation: Long, project: String, chat: String): NativeAgentInputQueueRow?

    @Query("SELECT * FROM native_agent_input_drafts WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat ORDER BY position LIMIT 51")
    fun inputDrafts(instance: String, generation: Long, project: String, chat: String): List<NativeAgentInputDraftRow>

    @Query("SELECT COUNT(*) FROM native_agent_input_drafts WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat")
    fun inputDraftCount(instance: String, generation: Long, project: String, chat: String): Int

    @Insert
    fun insertInputQueue(row: NativeAgentInputQueueRow)

    @Insert
    fun insertInputDrafts(rows: List<NativeAgentInputDraftRow>)

    @Query("DELETE FROM native_agent_input_drafts WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat")
    fun deleteInputDrafts(instance: String, generation: Long, project: String, chat: String): Int

    @Query("DELETE FROM native_agent_input_queues WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat")
    fun deleteInputQueue(instance: String, generation: Long, project: String, chat: String): Int

    @Query("SELECT * FROM native_agent_steer_runs WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat AND (:beforeRun IS NULL OR runId<:beforeRun OR (runId=:beforeRun AND runCommandId<:beforeCommand)) ORDER BY runId DESC,runCommandId DESC LIMIT 21")
    fun steerRunPage(instance: String, generation: Long, project: String, chat: String,
        beforeRun: String?, beforeCommand: String?): List<NativeAgentSteerRunRow>

    @Query("SELECT * FROM native_agent_steer_runs WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat AND runId=:run AND runCommandId=:command")
    fun steerRun(
        instance: String,
        generation: Long,
        project: String,
        chat: String,
        run: String,
        command: String,
    ): NativeAgentSteerRunRow?

    @Query("SELECT * FROM native_agent_steer_attempts WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat AND runId=:run AND runCommandId=:command ORDER BY position LIMIT 101")
    fun steerAttempts(
        instance: String,
        generation: Long,
        project: String,
        chat: String,
        run: String,
        command: String,
    ): List<NativeAgentSteerAttemptRow>

    @Insert
    fun insertSteerRun(row: NativeAgentSteerRunRow)

    @Insert
    fun insertSteerAttempts(rows: List<NativeAgentSteerAttemptRow>)

    @Query("DELETE FROM native_agent_steer_attempts WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat AND runId=:run AND runCommandId=:command")
    fun deleteSteerAttempts(
        instance: String,
        generation: Long,
        project: String,
        chat: String,
        run: String,
        command: String,
    ): Int

    @Query("DELETE FROM native_agent_steer_runs WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat AND runId=:run AND runCommandId=:command")
    fun deleteSteerRun(
        instance: String,
        generation: Long,
        project: String,
        chat: String,
        run: String,
        command: String,
    ): Int
}
