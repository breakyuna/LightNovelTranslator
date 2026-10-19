package com.breakyuna.noveltranslator.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.breakyuna.noveltranslator.data.model.QualityRuleSetEntity
import kotlinx.coroutines.flow.Flow

/**
 * 质量规则槽位读写。
 *
 * 复合主键（工程 + 槽位）让 upsert 天然幂等：设置页反复保存同一槽位不会
 * 产生重复行，也不需要先查后写。
 */
@Dao
interface QualityRuleDao {

    @Query("SELECT * FROM quality_rule_sets WHERE translationProjectId = :projectId ORDER BY type")
    fun observeByProject(projectId: Long): Flow<List<QualityRuleSetEntity>>

    @Query("SELECT * FROM quality_rule_sets WHERE translationProjectId = :projectId ORDER BY type")
    suspend fun getByProject(projectId: Long): List<QualityRuleSetEntity>

    @Query("SELECT * FROM quality_rule_sets WHERE translationProjectId = :projectId AND type = :type LIMIT 1")
    suspend fun get(projectId: Long, type: String): QualityRuleSetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ruleSet: QualityRuleSetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(ruleSets: List<QualityRuleSetEntity>)

    @Query("DELETE FROM quality_rule_sets WHERE translationProjectId = :projectId")
    suspend fun deleteByProject(projectId: Long)

    @Query("DELETE FROM quality_rule_sets WHERE translationProjectId = :projectId AND type = :type")
    suspend fun delete(projectId: Long, type: String)

    /**
     * 在一个事务里替换一个工程的全部规则集。
     *
     * "整个工程三类一起保存"必须原子：先删后插如果中途失败，会留下只剩一两个
     * 槽位的半套状态，翻译流程会静默回退到默认规则。
     */
    @Transaction
    suspend fun replaceProjectRuleSets(
        projectId: Long,
        ruleSets: List<QualityRuleSetEntity>
    ) {
        deleteByProject(projectId)
        upsertAll(ruleSets)
    }

    /** 事务内确保默认规则集存在：没有任何行时写入，已有行时保持不动。 */
    @Transaction
    suspend fun ensureDefaultsIfAbsent(
        projectId: Long,
        defaults: List<QualityRuleSetEntity>
    ) {
        if (getByProject(projectId).isNotEmpty()) return
        upsertAll(defaults)
    }
}
