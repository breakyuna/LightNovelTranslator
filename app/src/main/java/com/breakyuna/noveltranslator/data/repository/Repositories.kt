package com.breakyuna.noveltranslator.data.repository

import com.breakyuna.noveltranslator.data.db.*
import com.breakyuna.noveltranslator.data.model.*
import com.breakyuna.noveltranslator.core.security.ApiKeyCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
