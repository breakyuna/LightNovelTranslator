package com.breakyuna.noveltranslator.data.repository

import com.breakyuna.noveltranslator.data.db.*
import com.breakyuna.noveltranslator.data.model.*
import com.breakyuna.noveltranslator.core.security.ApiKeyCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction

class ProjectRepository(private val projectDao: ProjectDao) {
    val allProjects: Flow<List<ProjectEntity>> = projectDao.getAllProjects()

    suspend fun getProjectById(id: Long): ProjectEntity? = projectDao.getProjectById(id)
    fun getProjectFlowById(id: Long): Flow<ProjectEntity?> = projectDao.getProjectFlowById(id)
    suspend fun insertProject(project: ProjectEntity): Long = projectDao.insertProject(project)
    suspend fun updateProject(project: ProjectEntity) = projectDao.updateProject(project)
    suspend fun updateProjectStats(projectId: Long, translatedCount: Int, promptTokens: Long, compTokens: Long, cost: Double, currency: String) =
        projectDao.updateProjectStats(projectId, translatedCount, promptTokens, compTokens, cost, currency)
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
        errorMsg: String?,
        translatedFileName: String? = null
    ) = chapterDao.updateTranslationResult(id, status, wordCount, promptTokens, completionTokens, cost, errorMsg, translatedFileName)
    suspend fun updateStatus(id: Long, status: ChapterStatus) = chapterDao.updateStatus(id, status)
    suspend fun resetTranslatingStatuses() = chapterDao.resetTranslatingStatuses()
    suspend fun updateSummary(id: Long, summary: String) = chapterDao.updateSummary(id, summary)
    suspend fun deleteChaptersByProject(projectId: Long) = chapterDao.deleteChaptersByProject(projectId)
    suspend fun deleteChapterById(id: Long) = chapterDao.deleteChapterById(id)
    suspend fun replaceChapters(projectId: Long, chapters: List<ChapterEntity>) = chapterDao.replaceChapters(projectId, chapters)
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

class ApiProviderRepository(
    private val apiProviderDao: ApiProviderDao,
    private val apiKeyCipher: ApiKeyCipher
) {
    val allProviders: Flow<List<ApiProviderEntity>> = apiProviderDao.getAllProviders()
        .map { providers -> providers.map(::decryptProvider) }

    suspend fun getDefaultProvider(): ApiProviderEntity? = apiProviderDao.getDefaultProvider()?.let(::decryptProvider)
    suspend fun getProviderById(id: Long): ApiProviderEntity? = apiProviderDao.getProviderById(id)?.let(::decryptProvider)
    suspend fun insertProvider(provider: ApiProviderEntity): Long = apiProviderDao.insertProvider(encryptProvider(provider))
    suspend fun updateProvider(provider: ApiProviderEntity) = apiProviderDao.updateProvider(encryptProvider(provider))
    suspend fun setDefaultProvider(id: Long) {
        apiProviderDao.clearDefaultFlags()
        apiProviderDao.setDefaultProvider(id)
    }
    suspend fun deleteProviderById(id: Long) = apiProviderDao.deleteProviderById(id)

    suspend fun encryptLegacyKeys() {
        apiProviderDao.getAllProviders().first().forEach { provider ->
            val hasPlainApiKey = provider.apiKey.isNotBlank() && !apiKeyCipher.isEncrypted(provider.apiKey)
            val headers = provider.customHeadersJson.trim()
            val hasPlainCustomHeaders = headers.isNotBlank() && headers != "{}" && !apiKeyCipher.isEncrypted(headers)
            if (hasPlainApiKey || hasPlainCustomHeaders) {
                apiProviderDao.updateProvider(encryptProvider(provider))
            }
        }
    }

    private fun encryptProvider(provider: ApiProviderEntity): ApiProviderEntity {
        val headers = provider.customHeadersJson.trim().ifBlank { "{}" }
        return provider.copy(
            apiKey = apiKeyCipher.encrypt(provider.apiKey.trim()),
            customHeadersJson = if (headers == "{}") headers else apiKeyCipher.encrypt(headers)
        )
    }

    private fun decryptProvider(provider: ApiProviderEntity): ApiProviderEntity = provider.copy(
        apiKey = apiKeyCipher.decrypt(provider.apiKey),
        customHeadersJson = apiKeyCipher.decrypt(provider.customHeadersJson).ifBlank { "{}" }
    )
}

class TranslationLogRepository(private val translationLogDao: TranslationLogDao) {
    fun getLogsByProject(projectId: Long): Flow<List<TranslationLogEntity>> = translationLogDao.getLogsByProject(projectId)
    suspend fun getLogsListByProject(projectId: Long): List<TranslationLogEntity> = translationLogDao.getLogsListByProject(projectId)
    suspend fun insertLog(log: TranslationLogEntity): Long = translationLogDao.insertLog(log)
    suspend fun deleteLogsByProject(projectId: Long) = translationLogDao.deleteLogsByProject(projectId)
}

class TranslationRunRepository(private val dao: TranslationRunDao) {
    suspend fun insert(run: TranslationRunEntity): Long = dao.insert(run)
    suspend fun update(run: TranslationRunEntity) = dao.update(run)
    suspend fun getById(id: Long): TranslationRunEntity? = dao.getById(id)
    suspend fun getByProject(projectId: Long): List<TranslationRunEntity> = dao.getByProject(projectId)
    suspend fun findResumable(projectId: Long, providerId: Long): TranslationRunEntity? = dao.findResumable(projectId, providerId)
    suspend fun findLatestResumable(projectId: Long): TranslationRunEntity? = dao.findLatestResumable(projectId)
    suspend fun updateState(id: Long, state: String, category: String? = null, message: String? = null, nextRetryAt: Long? = null) =
        dao.updateState(id, state, category, message, nextRetryAt)
    suspend fun markInFlightInterrupted() = dao.markInFlightInterrupted()
}

class TranslationChunkRepository(private val dao: TranslationChunkDao) {
    suspend fun insertAll(chunks: List<TranslationChunkEntity>) = dao.insertAll(chunks)
    suspend fun update(chunk: TranslationChunkEntity) = dao.update(chunk)
    suspend fun getByChapter(runId: Long, chapterId: Long): List<TranslationChunkEntity> = dao.getByChapter(runId, chapterId)
    suspend fun getByRun(runId: Long): List<TranslationChunkEntity> = dao.getByRun(runId)
    suspend fun getById(id: Long): TranslationChunkEntity? = dao.getById(id)
    suspend fun getChildren(runId: Long, parentChunkId: Long): List<TranslationChunkEntity> = dao.getChildren(runId, parentChunkId)
    suspend fun resetRunningChunks() = dao.resetRunningChunks()
}

class LlmRequestLogRepository(private val dao: LlmRequestLogDao) {
    fun getFlowByProject(projectId: Long): Flow<List<LlmRequestLogEntity>> = dao.getFlowByProject(projectId)
    suspend fun insert(log: LlmRequestLogEntity): Long = dao.insert(log)
    suspend fun getByProject(projectId: Long): List<LlmRequestLogEntity> = dao.getByProject(projectId)
    suspend fun getByRun(runId: Long): List<LlmRequestLogEntity> = dao.getByRun(runId)
}

/** Keeps request audit rows and their run/chunk counters consistent in one Room transaction. */
class TranslationAuditRepository(private val database: AppDatabase) {
    suspend fun record(runId: Long?, chunkId: Long?, logs: List<LlmRequestLogEntity>) {
        if (logs.isEmpty()) return
        database.withTransaction {
            database.llmRequestLogDao().insertAll(logs)
            val promptTokens = logs.sumOf { it.promptTokens }
            val completionTokens = logs.sumOf { it.completionTokens }
            val cost = logs.sumOf { it.estimatedCost }
            runId?.let {
                database.translationRunDao().addUsage(it, promptTokens, completionTokens, cost)
            }
            chunkId?.let {
                database.translationChunkDao().addUsage(
                    id = it,
                    attempts = logs.size,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    cost = cost,
                    durationMs = logs.sumOf { row -> row.durationMs }
                )
            }
        }
    }
}

class ChapterSegmentRepository(private val dao: ChapterSegmentDao) {
    suspend fun replaceForChapter(chapterId: Long, segments: List<ChapterSegmentEntity>) =
        dao.replaceForChapter(chapterId, segments)

    suspend fun getByChapter(chapterId: Long): List<ChapterSegmentEntity> = dao.getByChapter(chapterId)
}
