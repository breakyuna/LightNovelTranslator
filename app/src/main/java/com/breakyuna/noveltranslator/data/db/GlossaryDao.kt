package com.breakyuna.noveltranslator.data.db

import androidx.room.*
import com.breakyuna.noveltranslator.data.model.GlossaryEntity
import com.breakyuna.noveltranslator.data.model.TermCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface GlossaryDao {
    @Query("SELECT * FROM glossary WHERE projectId = :projectId ORDER BY category ASC, originalTerm ASC")
    fun getGlossaryByProject(projectId: Long): Flow<List<GlossaryEntity>>

    @Query("SELECT * FROM glossary WHERE projectId = :projectId")
    suspend fun getGlossaryListByProject(projectId: Long): List<GlossaryEntity>

    @Query("SELECT * FROM glossary WHERE id = :id LIMIT 1")
    suspend fun getTermById(id: Long): GlossaryEntity?

    @Query("SELECT * FROM glossary WHERE projectId = :projectId AND category = :category")
    fun getGlossaryByCategory(projectId: Long, category: TermCategory): Flow<List<GlossaryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTerm(term: GlossaryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTerms(terms: List<GlossaryEntity>): List<Long>

    @Update
    suspend fun updateTerm(term: GlossaryEntity)

    @Query("DELETE FROM glossary WHERE id = :id")
    suspend fun deleteTermById(id: Long)

    @Query("DELETE FROM glossary WHERE projectId = :projectId")
    suspend fun deleteGlossaryByProject(projectId: Long)
}
