package com.breakyuna.noveltranslator.nativecore.storage

import androidx.room.*

/** Bound pre-decision evidence and a durable receipt; inserted with the task transition. */
@Entity(tableName = "native_translation_task_decisions", indices = [Index(value = ["task"])])
internal data class NativeTranslationTaskDecisionRow(
    @PrimaryKey val command: String,
    val task: String,
    val action: String,
    val fingerprint: String,
    val previousStage: String,
    val revision: Long,
    val committed: Int,
    val remaining: Int,
    val requests: Int,
    val unresolvedRequests: Int,
)

@Dao
internal interface NativeTranslationTaskDecisionDao {
    @Insert fun insert(row: NativeTranslationTaskDecisionRow)
    @Insert fun members(rows: List<NativeTranslationMemberDecisionRow>)
    @Query("SELECT * FROM native_translation_member_decisions WHERE task=:task AND itemId IN (:items)")
    fun selected(task: String, items: List<Long>): List<NativeTranslationMemberDecisionRow>
    @Query("SELECT * FROM native_translation_task_decisions WHERE command=:command")
    fun find(command: String): NativeTranslationTaskDecisionRow?
}


/** One final disposition per original task member; retries do not reopen prior decisions. */
@Entity(tableName = "native_translation_member_decisions", primaryKeys = ["task", "itemId"])
internal data class NativeTranslationMemberDecisionRow(
    val task: String, val itemId: Long, val command: String, val action: String,
    val fingerprint: String, val revision: Long,
)
