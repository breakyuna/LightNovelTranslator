package com.breakyuna.noveltranslator.nativecore.storage

import androidx.room.*
import com.breakyuna.noveltranslator.nativecore.domain.*

@Entity(tableName = "native_runs", indices = [Index(value = ["instanceId", "generation", "createdAt"]), Index(value = ["state", "processId"])])
internal data class NativeRunRow(
    @PrimaryKey val executionId: String, val processId: String,
    val instanceId: String, val generation: Long, val projectId: String, val chatId: String?,
    val runId: String, val commandId: String, val owner: String, val state: String, val stage: String,
    val revision: Long?, val committedItems: Int, val requests: Int, val pendingCommands: String,
    val requestAttemptId: String?, val requestBranchId: String?,
    val needsReview: Boolean, val createdAt: Long, val updatedAt: Long,
)

@Dao
internal interface NativeRunDao {
    @Insert fun insert(row: NativeRunRow)
    @Update fun update(row: NativeRunRow): Int
    @Query("SELECT * FROM native_runs WHERE executionId=:id") fun find(id: String): NativeRunRow?
    @Query("SELECT * FROM native_runs WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND runId=:run AND commandId=:command AND ((:chat IS NULL AND chatId IS NULL) OR chatId=:chat) ORDER BY createdAt DESC, executionId DESC LIMIT 1")
    fun findRun(instance: String, generation: Long, project: String, run: String, command: String, chat: String?): NativeRunRow?
    @Query("SELECT * FROM native_runs WHERE state='RUNNING' AND (processId!=:process OR :live IS NULL OR executionId!=:live) ORDER BY createdAt,executionId LIMIT 50")
    fun abandoned(process: String, live: String?): List<NativeRunRow>
    @Query("SELECT * FROM native_runs WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND runId=:run AND commandId=:command AND chatId=:chat AND state='WAITING_APPROVAL'")
    fun waitingApprovals(instance: String, generation: Long, project: String, run: String, command: String, chat: String): List<NativeRunRow>
    @Query("SELECT * FROM native_runs WHERE instanceId=:instance AND generation=:generation AND projectId=:project ORDER BY createdAt DESC,executionId DESC LIMIT :limit")
    fun recent(instance: String, generation: Long, project: String, limit: Int): List<NativeRunRow>
    @Query("SELECT COUNT(*) FROM native_runs WHERE state='RUNNING' AND processId=:process")
    fun activeCount(process: String): Int
    @Query("SELECT state FROM (SELECT state,createdAt,id FROM native_agent_approvals WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat AND runId=:run UNION ALL SELECT state,createdAt,id FROM native_item_proposals WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat AND runId=:run UNION ALL SELECT state,createdAt,id FROM native_policy_proposals WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat AND runId=:run UNION ALL SELECT state,createdAt,id FROM native_pdf_page_proposals WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat AND runId=:run UNION ALL SELECT state,createdAt,id FROM native_asset_import_proposals WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat AND runId=:run) ORDER BY createdAt DESC,id DESC LIMIT 1")
    fun approvalState(instance: String, generation: Long, project: String, chat: String?, run: String): String?
}

/**
 * One persisted fixed-task decision that may not have reached the queue JSON yet. It is read-only
 * reconciliation evidence: the queue scheduler must still match it against the exact queue batch
 * and command identities before replaying anything.
 */
class StoredTranslationTaskCommand(
    val command: CommandId,
    val action: TranslationTaskCommandAction,
    val batchId: TranslationTaskFailureBatchId?,
    memberIds: List<ItemId>,
    memberFingerprints: List<ObjectFingerprint>,
    /** Persisted insertion order; only a stable tie-breaker for bounded reconciliation. */
    val sequence: Long,
) {
    val memberIds: List<ItemId> = java.util.Collections.unmodifiableList(ArrayList(memberIds))
    val memberFingerprints: List<ObjectFingerprint> =
        java.util.Collections.unmodifiableList(ArrayList(memberFingerprints))

    init {
        require(sequence >= 0) { "Invalid task command sequence" }
        require(this.memberIds.size == this.memberFingerprints.size) {
            "Task command evidence does not cover every member"
        }
        require(this.memberIds.distinct().size == this.memberIds.size) {
            "Duplicate task command member"
        }
        if (action.adjudication) {
            require(batchId != null && this.memberIds.isNotEmpty()) {
                "A member decision requires a batch and members"
            }
            require(this.memberIds.size <= TranslationTaskFailureBatch.MAX_MEMBERS)
            require(this.memberIds == this.memberIds.sortedBy { it.value }) {
                "Task command members are not canonical"
            }
        } else {
            require(action == TranslationTaskCommandAction.TERMINATE_TASK) {
                "Unsupported task command action"
            }
            require(batchId == null && this.memberIds.isEmpty() && this.memberFingerprints.isEmpty()) {
                "A task-level command has no member evidence"
            }
        }
    }
}
