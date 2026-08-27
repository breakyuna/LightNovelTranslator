package com.breakyuna.noveltranslator.core.task

import android.content.Context
import com.breakyuna.noveltranslator.core.llm.*
import com.breakyuna.noveltranslator.core.project.ProjectFileManager
import com.breakyuna.noveltranslator.core.translator.TranslationQualityValidator
import com.breakyuna.noveltranslator.core.translator.TranslationValidation
import com.breakyuna.noveltranslator.data.model.*
import com.breakyuna.noveltranslator.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class TranslationTaskManager(
    private val context: Context,
    private val projectRepository: ProjectRepository,
    private val chapterRepository: ChapterRepository,
    private val glossaryRepository: GlossaryRepository,
    private val translationLogRepository: TranslationLogRepository,
    private val llmRequestLogRepository: LlmRequestLogRepository,
    private val fileManager: ProjectFileManager,
    private val llmClient: LlmGateway,
    private val scope: CoroutineScope
) {
    private val prefs = context.getSharedPreferences("novel_task_queue_prefs", Context.MODE_PRIVATE)

    private val _tasks = MutableStateFlow<List<TranslationTaskItem>>(emptyList())
    val tasks: StateFlow<List<TranslationTaskItem>> = _tasks.asStateFlow()

    private val _maxConcurrency = MutableStateFlow(
        prefs.getInt("max_concurrent_tasks", 2).coerceIn(1, 6)
    )
    val maxConcurrency: StateFlow<Int> = _maxConcurrency.asStateFlow()

    private val _isQueuePaused = MutableStateFlow(false)
    val isQueuePaused: StateFlow<Boolean> = _isQueuePaused.asStateFlow()

    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val dispatchMutex = Mutex()

    fun setMaxConcurrency(limit: Int) {
        val bounded = limit.coerceIn(1, 6)
        _maxConcurrency.value = bounded
        prefs.edit().putInt("max_concurrent_tasks", bounded).apply()
        triggerDispatch()
    }

    fun pauseQueue() {
        _isQueuePaused.value = true
    }

    fun resumeQueue() {
        _isQueuePaused.value = false
        triggerDispatch()
    }

    fun enqueueChapter(
        project: ProjectEntity,
        chapter: ChapterEntity,
        provider: ApiProviderEntity
    ) {
        enqueueChapters(project, listOf(chapter), provider)
    }

    fun enqueueChapters(
        project: ProjectEntity,
        chapters: List<ChapterEntity>,
        provider: ApiProviderEntity
    ) {
        val newItems = chapters.map { chap ->
            TranslationTaskItem(
                projectId = project.id,
                projectTitle = project.title,
                chapterId = chap.id,
                chapterIndex = chap.chapterIndex,
                chapterTitle = chap.title,
                providerId = provider.id,
                providerName = provider.name,
                modelName = provider.selectedModel,
                currency = provider.currency,
                status = TaskStatus.QUEUED
            )
        }

        _tasks.value = _tasks.value + newItems
        triggerDispatch()
    }

    fun pauseTask(taskId: String) {
        runningJobs[taskId]?.cancel()
        runningJobs.remove(taskId)
        updateTask(taskId) { it.copy(status = TaskStatus.PAUSED) }
        triggerDispatch()
    }

    fun resumeTask(taskId: String) {
        updateTask(taskId) { it.copy(status = TaskStatus.QUEUED, errorMessage = null) }
        triggerDispatch()
    }

    fun retryTask(taskId: String) {
        updateTask(taskId) {
            it.copy(
                status = TaskStatus.QUEUED,
                progressPercent = 0f,
                currentChunk = 0,
                errorMessage = null
            )
        }
        triggerDispatch()
    }

    fun cancelTask(taskId: String) {
        runningJobs[taskId]?.cancel()
        runningJobs.remove(taskId)
        updateTask(taskId) { it.copy(status = TaskStatus.CANCELLED) }
        triggerDispatch()
    }

    fun clearCompletedTasks() {
        val activeStatuses = setOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.PAUSED)
        _tasks.value = _tasks.value.filter { it.status in activeStatuses }
    }

    fun removeTask(taskId: String) {
        runningJobs[taskId]?.cancel()
        runningJobs.remove(taskId)
        _tasks.value = _tasks.value.filterNot { it.id == taskId }
        triggerDispatch()
    }

    private fun triggerDispatch() {
        scope.launch(Dispatchers.IO) {
            dispatchMutex.withLock {
                if (_isQueuePaused.value) return@withLock

                val currentRunning = _tasks.value.count { it.status == TaskStatus.RUNNING }
                val availableSlots = _maxConcurrency.value - currentRunning
                if (availableSlots <= 0) return@withLock

                val queuedTasks = _tasks.value
                    .filter { it.status == TaskStatus.QUEUED }
                    .take(availableSlots)

                for (task in queuedTasks) {
                    launchTaskExecution(task)
                }
            }
        }
    }

    private fun launchTaskExecution(task: TranslationTaskItem) {
        val updatedTask = task.copy(
            status = TaskStatus.RUNNING,
            startedAt = System.currentTimeMillis(),
            errorMessage = null
        )
        updateTask(task.id) { updatedTask }

        val job = scope.launch(Dispatchers.IO) {
            try {
                executeSingleTask(updatedTask)
            } catch (ce: CancellationException) {
                // Task was paused or cancelled by user
            } catch (e: Exception) {
                updateTask(task.id) {
                    it.copy(
                        status = TaskStatus.FAILED,
                        errorMessage = e.localizedMessage ?: "Unknown translation error",
                        completedAt = System.currentTimeMillis()
                    )
                }
            } finally {
                runningJobs.remove(task.id)
                triggerDispatch()
            }
        }

        runningJobs[task.id] = job
    }

    private suspend fun executeSingleTask(task: TranslationTaskItem) {
        val chapter = chapterRepository.getChapterById(task.chapterId)
            ?: throw IllegalStateException("Chapter not found (id=${task.chapterId})")
        val project = projectRepository.getProjectById(task.projectId)
            ?: throw IllegalStateException("Project not found (id=${task.projectId})")
        val provider = com.breakyuna.noveltranslator.data.db.AppDatabase.getDatabase(context)
            .apiProviderDao().getProviderById(task.providerId)
            ?: throw IllegalStateException("API Provider not found (id=${task.providerId})")

        val glossary = glossaryRepository.getGlossaryListByProject(task.projectId)
        val originalText = fileManager.readOriginalChapter(task.projectId, chapter.originalFileName)
        if (originalText.isBlank()) {
            throw IllegalStateException("Original chapter text is blank")
        }

        // Mark chapter status
        chapterRepository.updateChapter(chapter.copy(status = ChapterStatus.TRANSLATING))

        val chunks = splitChapterIntoChunks(originalText)
        val totalChunks = chunks.size
        val translatedChunks = mutableListOf<String>()
        var totalPromptTok = 0L
        var totalCompTok = 0L
        var totalCost = 0.0

        val systemPrompt = TranslationPrompts.buildSystemPrompt(
            sourceLanguage = project.sourceLanguage,
            targetLanguage = project.targetLanguage,
            style = project.translationStyle
        )

        suspend fun recordRequestLog(result: LlmResult, attemptNumber: Int, operation: String) {
            val requestCost = TokenCalculator.calculateCost(
                result.promptTokens,
                result.completionTokens,
                provider.inputPricePerMillion,
                provider.outputPricePerMillion
            )
            llmRequestLogRepository.insert(
                LlmRequestLogEntity(
                    projectId = project.id,
                    attemptNumber = attemptNumber,
                    operation = operation,
                    providerId = provider.id,
                    providerName = provider.name,
                    modelName = provider.selectedModel,
                    inputPricePerMillion = provider.inputPricePerMillion,
                    outputPricePerMillion = provider.outputPricePerMillion,
                    currency = provider.currency,
                    promptTokens = result.promptTokens,
                    completionTokens = result.completionTokens,
                    totalTokens = result.promptTokens + result.completionTokens,
                    usageSource = result.usageSource.name,
                    estimatedCost = requestCost,
                    durationMs = result.durationMs,
                    httpStatus = result.httpStatus,
                    errorCategory = result.errorCategory?.name,
                    errorMessage = result.errorMessage,
                    finishReason = result.finishReason,
                    requestId = result.requestId,
                    isSuccess = result.isSuccess
                )
            )
        }

        fun accountUsage(result: LlmResult) {
            totalPromptTok += result.promptTokens
            totalCompTok += result.completionTokens
            totalCost += TokenCalculator.calculateCost(
                result.promptTokens,
                result.completionTokens,
                provider.inputPricePerMillion,
                provider.outputPricePerMillion
            )
            updateTask(task.id) {
                it.copy(
                    promptTokens = totalPromptTok,
                    completionTokens = totalCompTok,
                    cost = totalCost
                )
            }
        }

        suspend fun persistFailedUsage(message: String) {
            chapterRepository.updateChapter(
                chapter.copy(
                    status = ChapterStatus.FAILED,
                    promptTokens = totalPromptTok,
                    completionTokens = totalCompTok,
                    estimatedCost = totalCost,
                    errorMessage = message.take(500),
                    updatedAt = System.currentTimeMillis()
                )
            )
            translationLogRepository.insertLog(
                TranslationLogEntity(
                    projectId = task.projectId,
                    chapterIndex = chapter.chapterIndex,
                    chapterTitle = chapter.title,
                    modelName = provider.selectedModel,
                    providerName = provider.name,
                    promptTokens = totalPromptTok,
                    completionTokens = totalCompTok,
                    totalTokens = totalPromptTok + totalCompTok,
                    estimatedCost = totalCost,
                    currency = provider.currency,
                    durationMs = 0L,
                    isSuccess = false,
                    message = message.take(500)
                )
            )
            val allChapters = chapterRepository.getChaptersListByProject(task.projectId)
            val allLogs = translationLogRepository.getLogsListByProject(task.projectId)
            projectRepository.updateProjectStats(
                projectId = task.projectId,
                translatedCount = allChapters.count { it.status == ChapterStatus.COMPLETED },
                promptTokens = allLogs.sumOf { it.promptTokens },
                compTokens = allLogs.sumOf { it.completionTokens },
                cost = allLogs.sumOf { it.estimatedCost },
                currency = provider.currency
            )
        }

        for ((idx, chunk) in chunks.withIndex()) {
            yield() // Check cancellation

            updateTask(task.id) {
                it.copy(
                    currentChunk = idx + 1,
                    totalChunks = totalChunks,
                    progressPercent = idx.toFloat() / totalChunks.toFloat()
                )
            }

            val activeChunkGlossary = TranslationPrompts.selectConfirmedGlossaryForText(glossary, chunk)
            val userPrompt = if (totalChunks == 1) {
                TranslationPrompts.buildUserPrompt(
                    chapterTitle = chapter.title,
                    chapterText = chunk,
                    glossary = activeChunkGlossary
                )
            } else {
                TranslationPrompts.buildChunkUserPrompt(
                    chapterTitle = chapter.title,
                    chunkIndex = idx + 1,
                    totalChunks = totalChunks,
                    chunkText = chunk,
                    glossary = activeChunkGlossary,
                    previousChunkTranslationReference = translatedChunks.lastOrNull()?.takeLast(300)
                )
            }

            var result = llmClient.executeCompletion(
                provider = provider,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                temperature = provider.temperature
            )
            recordRequestLog(result, 1, "Queue Task: Ch ${chapter.chapterIndex} [${idx + 1}/$totalChunks]")

            if (!result.isSuccess || result.text.isBlank()) {
                accountUsage(result)
                val message = result.errorMessage ?: "LLM returned empty translation"
                persistFailedUsage(message)
                throw IllegalStateException(message)
            }

            val validation = if (result.isTruncated) {
                TranslationValidation(false, listOf("translation output was truncated"))
            } else {
                TranslationQualityValidator.validate(chunk, result.text, activeChunkGlossary)
            }
            if (!validation.isAcceptable) {
                val retry = llmClient.executeCompletion(
                    provider = provider,
                    systemPrompt = systemPrompt,
                    userPrompt = TranslationPrompts.buildValidationRetryPrompt(userPrompt, validation.problems),
                    temperature = provider.temperature
                )
                recordRequestLog(retry, 2, "Queue Task: Ch ${chapter.chapterIndex} [${idx + 1}/$totalChunks] QUALITY_RETRY")
                val retryValidation = if (retry.isSuccess && retry.text.isNotBlank() && !retry.isTruncated) {
                    TranslationQualityValidator.validate(chunk, retry.text, activeChunkGlossary)
                } else {
                    TranslationValidation(false, listOf(retry.errorMessage ?: "quality retry was empty or truncated"))
                }
                result = if (retryValidation.isAcceptable) {
                    retry.copy(
                        promptTokens = result.promptTokens + retry.promptTokens,
                        completionTokens = result.completionTokens + retry.completionTokens,
                        durationMs = result.durationMs + retry.durationMs
                    )
                } else {
                    result.copy(
                        isSuccess = false,
                        errorCategory = LlmErrorCategory.QUALITY_REJECTED,
                        retryable = false,
                        errorMessage = "Translation validation failed [glossary=${retryValidation.glossaryStatus}]: ${retryValidation.problems.joinToString()}",
                        promptTokens = result.promptTokens + retry.promptTokens,
                        completionTokens = result.completionTokens + retry.completionTokens,
                        durationMs = result.durationMs + retry.durationMs
                    )
                }
            }

            accountUsage(result)

            if (!result.isSuccess || result.text.isBlank()) {
                val message = result.errorMessage ?: "Translation failed deterministic quality validation"
                persistFailedUsage(message)
                throw IllegalStateException(message)
            }

            translatedChunks.add(result.text.trim())
        }

        val fullTranslatedText = translatedChunks.joinToString("\n\n")
        val translatedFileName = fileManager.saveTranslatedChapter(task.projectId, chapter.chapterIndex, fullTranslatedText, chapter.title)

        val translatedWordCount = fullTranslatedText.length
        val updatedChapter = chapter.copy(
            translatedFileName = translatedFileName,
            translatedWordCount = translatedWordCount,
            status = ChapterStatus.COMPLETED,
            lastTranslatedAt = System.currentTimeMillis(),
            promptTokens = totalPromptTok,
            completionTokens = totalCompTok,
            estimatedCost = totalCost
        )
        chapterRepository.updateChapter(updatedChapter)

        // Insert Translation Log
        translationLogRepository.insertLog(
            TranslationLogEntity(
                projectId = task.projectId,
                chapterIndex = chapter.chapterIndex,
                chapterTitle = chapter.title,
                modelName = provider.selectedModel,
                providerName = provider.name,
                promptTokens = totalPromptTok,
                completionTokens = totalCompTok,
                totalTokens = totalPromptTok + totalCompTok,
                estimatedCost = totalCost,
                currency = provider.currency,
                durationMs = 0L,
                isSuccess = true,
                message = "Task queue completed chapter ${chapter.chapterIndex}"
            )
        )

        // Update Project Stats
        val allChapters = chapterRepository.getChaptersListByProject(task.projectId)
        val allLogs = translationLogRepository.getLogsListByProject(task.projectId)
        projectRepository.updateProjectStats(
            projectId = task.projectId,
            translatedCount = allChapters.count { it.status == ChapterStatus.COMPLETED },
            promptTokens = allLogs.sumOf { it.promptTokens },
            compTokens = allLogs.sumOf { it.completionTokens },
            cost = allLogs.sumOf { it.estimatedCost },
            currency = provider.currency
        )

        // Mark task completed
        updateTask(task.id) {
            it.copy(
                status = TaskStatus.COMPLETED,
                progressPercent = 1f,
                currentChunk = totalChunks,
                totalChunks = totalChunks,
                promptTokens = totalPromptTok,
                completionTokens = totalCompTok,
                cost = totalCost,
                completedAt = System.currentTimeMillis()
            )
        }
    }

    private fun updateTask(taskId: String, transform: (TranslationTaskItem) -> TranslationTaskItem) {
        _tasks.value = _tasks.value.map { if (it.id == taskId) transform(it) else it }
    }

    private fun splitChapterIntoChunks(text: String, maxChars: Int = 4000): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val paragraphs = text.split("\n")
        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (para in paragraphs) {
            if (currentChunk.length + para.length + 1 > maxChars && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk.clear()
            }
            if (currentChunk.isNotEmpty()) currentChunk.append("\n")
            currentChunk.append(para)
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }
        return chunks.ifEmpty { listOf(text) }
    }
}
