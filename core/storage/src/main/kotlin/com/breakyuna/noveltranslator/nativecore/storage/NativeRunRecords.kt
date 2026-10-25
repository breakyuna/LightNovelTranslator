package com.breakyuna.noveltranslator.nativecore.storage

import androidx.room.*

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
    @Query("SELECT * FROM native_runs WHERE state='RUNNING' AND (processId!=:process OR :live IS NULL OR executionId!=:live) ORDER BY createdAt,executionId LIMIT 50")
    fun abandoned(process: String, live: String?): List<NativeRunRow>
    @Query("SELECT * FROM native_runs WHERE instanceId=:instance AND generation=:generation AND projectId=:project ORDER BY createdAt DESC,executionId DESC LIMIT :limit")
    fun recent(instance: String, generation: Long, project: String, limit: Int): List<NativeRunRow>
    @Query("SELECT COUNT(*) FROM native_runs WHERE state='RUNNING' AND processId=:process")
    fun activeCount(process: String): Int
    @Query("SELECT state FROM (SELECT state,createdAt,id FROM native_agent_approvals WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat AND runId=:run UNION ALL SELECT state,createdAt,id FROM native_item_proposals WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat AND runId=:run UNION ALL SELECT state,createdAt,id FROM native_policy_proposals WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND chatId=:chat AND runId=:run) ORDER BY createdAt DESC,id DESC LIMIT 1")
    fun approvalState(instance: String, generation: Long, project: String, chat: String?, run: String): String?
}
