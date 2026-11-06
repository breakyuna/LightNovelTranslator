package com.breakyuna.noveltranslator.nativecore.storage

import androidx.room.*

/**
 * One prepared workspace-artifact import. The artifact body never enters Room; only frozen
 * metadata, the whole-object digest, the approval binding and the final asset receipt do.
 */
@Entity(tableName = "native_asset_import_proposals", indices = [Index(value = ["commandId"], unique = true),
    Index(value = ["instanceId", "generation", "chatId"])])
internal data class NativeAssetImportProposalRow(
    @PrimaryKey val id: String,
    val instanceId: String,
    val generation: Long,
    val projectId: String,
    val chatId: String,
    val runId: String,
    val commandId: String,
    val revision: Long,
    val artifactRunId: String,
    val artifactId: String,
    val artifactPath: String,
    val sha256: String,
    val size: Long,
    val targetPath: String,
    val enabled: Boolean,
    val digest: String,
    val state: String,
    val binding: String?,
    val claimedAt: Long?,
    val receiptStatus: String?,
    val receiptRevision: Long?,
    val receiptAssetId: Long?,
    val receiptAt: Long?,
    val settledAt: Long?,
    val createdAt: Long,
)

@Dao
internal interface NativeAssetImportProposalDao {
    @Insert fun insert(row: NativeAssetImportProposalRow)
    @Query("SELECT * FROM native_asset_import_proposals WHERE id=:id")
    fun find(id: String): NativeAssetImportProposalRow?
    @Query("SELECT * FROM native_asset_import_proposals WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND state='PENDING' ORDER BY createdAt,id LIMIT 2")
    fun unresolved(instance: String, generation: Long, chat: String): List<NativeAssetImportProposalRow>
    @Query("SELECT * FROM native_asset_import_proposals WHERE instanceId=:instance AND generation=:generation AND chatId=:chat ORDER BY createdAt DESC,id DESC LIMIT 50")
    fun recent(instance: String, generation: Long, chat: String): List<NativeAssetImportProposalRow>
    @Query("UPDATE native_asset_import_proposals SET binding=:binding,claimedAt=:at WHERE id=:id AND state='PENDING' AND binding IS NULL AND claimedAt IS NULL")
    fun claim(id: String, binding: String, at: Long): Int
    @Query("UPDATE native_asset_import_proposals SET state=:state,settledAt=:at WHERE id=:id AND state=:expected")
    fun settle(id: String, expected: String, state: String, at: Long): Int
    @Query("UPDATE native_asset_import_proposals SET state=:state,receiptStatus=:status,receiptRevision=:revision,receiptAssetId=:assetId,receiptAt=:receiptAt,settledAt=:at WHERE id=:id AND state=:expected")
    fun settleReceipt(id: String, expected: String, state: String, status: String, revision: Long,
                      assetId: Long?, receiptAt: Long, at: Long): Int
    @Query("UPDATE native_asset_import_proposals SET state='CANCELLED',settledAt=:at WHERE instanceId=:instance AND generation=:generation AND runId=:run AND state='PENDING' AND binding IS NULL")
    fun cancelUnclaimed(instance: String, generation: Long, run: String, at: Long): Int
}
