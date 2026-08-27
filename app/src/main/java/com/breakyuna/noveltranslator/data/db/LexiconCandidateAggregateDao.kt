package com.breakyuna.noveltranslator.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.breakyuna.noveltranslator.data.model.LexiconCandidateAggregateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LexiconCandidateAggregateDao {
    @Query(
        "SELECT * FROM lexicon_candidate_aggregates " +
            "WHERE translationProjectId = :projectId AND state = 'ACTIVE' " +
            "ORDER BY lastSeenChapterIndex, sourceTerm"
    )
    fun observeAllActive(projectId: Long): Flow<List<LexiconCandidateAggregateEntity>>

    @Query(
        "SELECT * FROM lexicon_candidate_aggregates " +
            "WHERE translationProjectId = :projectId AND state = 'ACTIVE' " +
            "ORDER BY lastSeenChapterIndex, sourceTerm"
    )
    suspend fun getAllActive(projectId: Long): List<LexiconCandidateAggregateEntity>

    @Query("SELECT * FROM lexicon_candidate_aggregates WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LexiconCandidateAggregateEntity?

    @Query(
        "SELECT * FROM lexicon_candidate_aggregates " +
            "WHERE translationProjectId = :projectId AND normalizedSourceTerm = :normalizedSourceTerm LIMIT 1"
    )
    suspend fun getBySource(projectId: Long, normalizedSourceTerm: String): LexiconCandidateAggregateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertObservation(aggregate: LexiconCandidateAggregateEntity): Long

    @Query("UPDATE lexicon_candidate_aggregates SET state = 'IMPORTED' WHERE id = :id")
    suspend fun markImported(id: Long)

    @Query("UPDATE lexicon_candidate_aggregates SET state = 'IGNORED' WHERE id = :id")
    suspend fun markIgnored(id: Long)

    @Query("DELETE FROM lexicon_candidate_aggregates WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM lexicon_candidate_aggregates WHERE translationProjectId = :projectId")
    suspend fun clearByProject(projectId: Long)
}
