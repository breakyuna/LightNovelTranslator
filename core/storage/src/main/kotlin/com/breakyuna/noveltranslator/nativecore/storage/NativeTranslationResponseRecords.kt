package com.breakyuna.noveltranslator.nativecore.storage

import androidx.room.*

@Entity(tableName = "native_translation_request_inputs", primaryKeys = ["attemptId", "itemId"])
internal data class NativeTranslationRequestInputRow(val attemptId: String, val itemId: Long, val fingerprint: String)

/** One bounded translated item per row, fetched only by attempt while recovering. */
@Entity(tableName = "native_translation_responses", primaryKeys = ["attemptId", "itemId"])
internal data class NativeTranslationResponseRow(val attemptId: String, val itemId: Long,
    val fingerprint: String, val text: String) {
    override fun toString() = "NativeTranslationResponseRow(redacted)"
}
@Entity(tableName = "native_translation_staged", primaryKeys = ["task", "itemId"])
internal data class NativeTranslationStagedRow(val task: String, val itemId: Long,
    val fingerprint: String, val commandId: String, val text: String) {
    override fun toString() = "NativeTranslationStagedRow(redacted)"
}
@Dao
internal interface NativeTranslationResponseDao {
    @Insert fun inputs(rows: List<NativeTranslationRequestInputRow>)
    @Query("SELECT * FROM native_translation_request_inputs WHERE attemptId=:attempt ORDER BY itemId LIMIT 21")
    fun inputs(attempt: String): List<NativeTranslationRequestInputRow>
    @Query("SELECT COUNT(*) FROM native_translation_request_inputs AS i INNER JOIN native_translation_requests AS r ON r.attemptId=i.attemptId WHERE r.instanceId=:instance AND r.generation=:generation AND r.projectId=:project AND r.runId=:run AND r.commandId=:command AND i.itemId IN (:items)")
    fun previousRequests(instance: String, generation: Long, project: String, run: String, command: String, items: List<Long>): Int
    @Query("SELECT COUNT(*) FROM native_translation_requests AS r WHERE r.instanceId=:instance AND r.generation=:generation AND r.projectId=:project AND r.runId=:run AND r.commandId=:command AND NOT EXISTS (SELECT 1 FROM native_translation_request_inputs AS i WHERE i.attemptId=r.attemptId)")
    fun legacyRequests(instance: String, generation: Long, project: String, run: String, command: String): Int
    @Insert fun responses(rows: List<NativeTranslationResponseRow>)
    @Query("SELECT * FROM native_translation_responses WHERE attemptId=:attempt ORDER BY itemId LIMIT 21")
    fun responses(attempt: String): List<NativeTranslationResponseRow>
    @Query("SELECT COUNT(*) FROM native_translation_responses WHERE attemptId=:attempt") fun responseCount(attempt: String): Int
    @Insert fun stage(rows: List<NativeTranslationStagedRow>)
    @Query("SELECT * FROM native_translation_staged WHERE task=:task ORDER BY itemId LIMIT 20")
    fun staged(task: String): List<NativeTranslationStagedRow>
    @Query("SELECT itemId FROM native_translation_staged WHERE task=:task ORDER BY itemId LIMIT 101")
    fun stagedIds(task: String): List<Long>
    @Query("DELETE FROM native_translation_staged WHERE task=:task AND itemId IN (:items)")
    fun removeStaged(task: String, items: List<Long>)
}
