package com.example.data.repository

import com.example.data.db.*
import com.example.data.model.*
import kotlinx.coroutines.flow.Flow

class ProjectRepository(private val projectDao: ProjectDao) {
    val allProjects: Flow<List<ProjectEntity>> = projectDao.getAllProjects()

    suspend fun getProjectById(id: Long): ProjectEntity? = projectDao.getProjectById(id)
    fun getProjectFlowById(id: Long): Flow<ProjectEntity?> = projectDao.getProjectFlowById(id)
    suspend fun insertProject(project: ProjectEntity): Long = projectDao.insertProject(project)
    suspend fun updateProject(project: ProjectEntity) = projectDao.updateProject(project)
    suspend fun updateProjectStats(projectId: Long, translatedCount: Int, promptTokens: Long, compTokens: Long, cost: Double) =
        projectDao.updateProjectStats(projectId, translatedCount, promptTokens, compTokens, cost)
    suspend fun deleteProjectById(id: Long) = projectDao.deleteProjectById(id)
}

class ChapterRepository(private val chapterDao: ChapterDao) {
    fun getChaptersByProject(projectId: Long): Flow<List<ChapterEntity>> = chapterDao.getChaptersByProject(projectId)
    suspend fun getChaptersListByProject(projectId: Long): List<ChapterEntity> = chapterDao.getChaptersListByProject(projectId)
    suspend fun getChapterById(id: Long): ChapterEntity? = chapterDao.getChapterById(id)
    suspend fun getChapterByIndex(projectId: Long, index: Int): ChapterEntity? = chapterDao.getChapterByIndex(projectId, index)
    suspend fun insertChapter(chapter: ChapterEntity): Long = chapterDao.insertChapter(chapter)
    suspend fun insertChapters(chapters: List<ChapterEntity>): List<Long> = chapterDao.insertChapters(chapters)
    suspend fun updateChapter(chapter: ChapterEntity) = chapterDao.updateChapter(chapter)
    suspend fun updateTranslationResult(
        id: Long,
        status: ChapterStatus,
        wordCount: Int,
        promptTokens: Long,
        completionTokens: Long,
        cost: Double,
        errorMsg: String?
    ) = chapterDao.updateTranslationResult(id, status, wordCount, promptTokens, completionTokens, cost, errorMsg)
    suspend fun updateStatus(id: Long, status: ChapterStatus) = chapterDao.updateStatus(id, status)
    suspend fun updateSummary(id: Long, summary: String) = chapterDao.updateSummary(id, summary)
    suspend fun deleteChaptersByProject(projectId: Long) = chapterDao.deleteChaptersByProject(projectId)
    suspend fun deleteChapterById(id: Long) = chapterDao.deleteChapterById(id)
}

class GlossaryRepository(private val glossaryDao: GlossaryDao) {
    fun getGlossaryByProject(projectId: Long): Flow<List<GlossaryEntity>> = glossaryDao.getGlossaryByProject(projectId)
    suspend fun getGlossaryListByProject(projectId: Long): List<GlossaryEntity> = glossaryDao.getGlossaryListByProject(projectId)
    suspend fun insertTerm(term: GlossaryEntity): Long = glossaryDao.insertTerm(term)
    suspend fun insertTerms(terms: List<GlossaryEntity>): List<Long> = glossaryDao.insertTerms(terms)
    suspend fun updateTerm(term: GlossaryEntity) = glossaryDao.updateTerm(term)
    suspend fun deleteTermById(id: Long) = glossaryDao.deleteTermById(id)
    suspend fun deleteGlossaryByProject(projectId: Long) = glossaryDao.deleteGlossaryByProject(projectId)
}

class ApiProviderRepository(private val apiProviderDao: ApiProviderDao) {
    val allProviders: Flow<List<ApiProviderEntity>> = apiProviderDao.getAllProviders()
    suspend fun getDefaultProvider(): ApiProviderEntity? = apiProviderDao.getDefaultProvider()
    suspend fun getProviderById(id: Long): ApiProviderEntity? = apiProviderDao.getProviderById(id)
    suspend fun insertProvider(provider: ApiProviderEntity): Long = apiProviderDao.insertProvider(provider)
    suspend fun updateProvider(provider: ApiProviderEntity) = apiProviderDao.updateProvider(provider)
    suspend fun setDefaultProvider(id: Long) {
        apiProviderDao.clearDefaultFlags()
        apiProviderDao.setDefaultProvider(id)
    }
    suspend fun deleteProviderById(id: Long) = apiProviderDao.deleteProviderById(id)
}

class TranslationLogRepository(private val translationLogDao: TranslationLogDao) {
    fun getLogsByProject(projectId: Long): Flow<List<TranslationLogEntity>> = translationLogDao.getLogsByProject(projectId)
    suspend fun insertLog(log: TranslationLogEntity): Long = translationLogDao.insertLog(log)
    suspend fun deleteLogsByProject(projectId: Long) = translationLogDao.deleteLogsByProject(projectId)
}
