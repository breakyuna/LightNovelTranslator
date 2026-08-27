package com.breakyuna.noveltranslator.core.translator

import com.breakyuna.noveltranslator.core.llm.LlmGateway
import com.breakyuna.noveltranslator.core.llm.LlmAttempt
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.core.llm.LlmErrorCategory
import com.breakyuna.noveltranslator.core.llm.UsageSource
import com.breakyuna.noveltranslator.core.llm.executeCompletion
import com.breakyuna.noveltranslator.core.llm.DelayProvider
import com.breakyuna.noveltranslator.core.llm.CoroutineDelayProvider
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.core.llm.TranslationPrompts
import com.breakyuna.noveltranslator.core.llm.TranslationControlSignal
import com.breakyuna.noveltranslator.core.logger.SystemLogger
import com.breakyuna.noveltranslator.core.parser.TxtParser
import com.breakyuna.noveltranslator.core.project.ProjectFileManager
import com.breakyuna.noveltranslator.data.model.*
import com.breakyuna.noveltranslator.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.io.IOException
import java.util.ArrayDeque

sealed class TranslationJobState {
    object Idle : TranslationJobState()
    data class Running(
        val projectId: Long,
        val currentChapterIndex: Int,
        val currentChapterTitle: String,
        val currentChunkIndex: Int = 1,
        val totalChunksInChapter: Int = 1,
        val completedCount: Int,
        val totalToTranslate: Int,
        val currentPromptTokens: Long,
        val currentCompletionTokens: Long,
        val currentCost: Double,
        val currency: String = "USD",
        val isPaused: Boolean = false
    ) : TranslationJobState()
    data class Finished(
        val projectId: Long,
        val translatedCount: Int,
        val totalPromptTokens: Long,
        val totalCompletionTokens: Long,
        val totalCost: Double,
        val currency: String = "USD",
        val errorCount: Int
    ) : TranslationJobState()
    data class Error(val message: String) : TranslationJobState()
}

class TranslationManager(
    private val projectRepository: ProjectRepository,
    private val chapterRepository: ChapterRepository,
    private val glossaryRepository: GlossaryRepository,
    private val translationLogRepository: TranslationLogRepository,
    private val translationRunRepository: TranslationRunRepository,
    private val translationChunkRepository: TranslationChunkRepository,
    private val llmRequestLogRepository: LlmRequestLogRepository,
    private val translationAuditRepository: TranslationAuditRepository,
    private val fileManager: ProjectFileManager,
    private val llmClient: LlmGateway,
    private val delayProvider: DelayProvider = CoroutineDelayProvider,
    private val controlSignal: TranslationControlSignal = TranslationControlSignal()
) {
    private val _jobState = MutableStateFlow<TranslationJobState>(TranslationJobState.Idle)
    val jobState: StateFlow<TranslationJobState> = _jobState.asStateFlow()

    private val liveLogLock = Any()
    private val liveLogQueue = ArrayDeque<LiveLogMessage>()
    private val _liveLogs = MutableStateFlow<List<LiveLogMessage>>(emptyList())
    val liveLogs: StateFlow<List<LiveLogMessage>> = _liveLogs.asStateFlow()

    private var activeJob: Job? = null
    private var controlScope: CoroutineScope? = null
    private var activeRunId: Long? = null
    private var activeProjectId: Long? = null

    private fun emitLiveLog(
        type: LiveLogType,
        message: String,
        chapterIndex: Int? = null,
        chunkInfo: String? = null,
        detail: String? = null,
        tokensInfo: String? = null,
        costInfo: String? = null,
        projectId: Long? = null
    ) {
        val entry = LiveLogMessage(
            timestamp = System.currentTimeMillis(),
            chapterIndex = chapterIndex,
            chunkInfo = chunkInfo,
            type = type,
            message = message,
            detail = detail,
            tokensInfo = tokensInfo,
            costInfo = costInfo
        )
        synchronized(liveLogLock) {
            liveLogQueue.addFirst(entry)
            while (liveLogQueue.size > 200) {
                liveLogQueue.removeLast()
            }
            _liveLogs.value = liveLogQueue.toList()
        }

        // Also record to system-wide logger
        when (type) {
            LiveLogType.ERROR -> SystemLogger.error("TRANSLATION", message, detail, projectId, chapterIndex)
            LiveLogType.WARNING -> SystemLogger.warn("TRANSLATION", message, detail, projectId, chapterIndex)
            else -> SystemLogger.info("TRANSLATION", message, detail, projectId, chapterIndex)
        }
    }

    fun clearLiveLogs() {
        synchronized(liveLogLock) {
            liveLogQueue.clear()
            _liveLogs.value = emptyList()
        }
    }

    fun pause() {
        controlSignal.requestPause()
        val current = _jobState.value
        if (current is TranslationJobState.Running) {
            _jobState.value = current.copy(isPaused = true)
        }
        controlScope?.launch(Dispatchers.IO) {
            activeRunId?.let { translationRunRepository.updateState(it, TranslationRunState.PAUSE_REQUESTED.name) }
        }
        emitLiveLog(LiveLogType.WARNING, "Translation execution paused by user.")
    }

    fun resume() {
        controlSignal.resume()
        val current = _jobState.value
        if (current is TranslationJobState.Running) {
            _jobState.value = current.copy(isPaused = false)
        }
        controlScope?.launch(Dispatchers.IO) {
            activeRunId?.let { translationRunRepository.updateState(it, TranslationRunState.RUNNING.name) }
        }
        emitLiveLog(LiveLogType.INFO, "Translation execution resumed.")
    }

    fun stop() {
        controlSignal.cancel()
        controlScope?.launch(Dispatchers.IO) {
            activeRunId?.let { translationRunRepository.updateState(it, TranslationRunState.CANCELLED.name) }
            activeProjectId?.let { projectId ->
                chapterRepository.getChaptersListByProject(projectId)
                    .filter { it.status == ChapterStatus.TRANSLATING }
                    .forEach { chapterRepository.updateStatus(it.id, ChapterStatus.PENDING) }
            }
        }
        activeJob?.cancel()
        _jobState.value = TranslationJobState.Idle
        emitLiveLog(LiveLogType.WARNING, "Translation pipeline stopped.")
    }

    /**
     * Start continuous automated translation for all pending/error chapters or specified range
     */
    fun startTranslation(
        scope: CoroutineScope,
        projectId: Long,
        provider: ApiProviderEntity,
        chapterIds: List<Long>? = null,
        onChapterFinished: ((ChapterEntity) -> Unit)? = null
    ) {
        if (activeJob?.isActive == true) return

        controlScope = scope
        activeProjectId = projectId

        controlSignal.reset()

        emitLiveLog(
            type = LiveLogType.STEP,
            message = "Initializing translation pipeline with provider '${provider.name}' (${provider.selectedModel})",
            projectId = projectId
        )

        val failureHandler = CoroutineExceptionHandler { _, throwable ->
            if (throwable !is CancellationException) {
                val errMsg = throwable.localizedMessage ?: "Unexpected translation pipeline failure"
                _jobState.value = TranslationJobState.Error(errMsg)
                emitLiveLog(LiveLogType.ERROR, "Fatal pipeline error: $errMsg", detail = throwable.stackTraceToString(), projectId = projectId)
                activeRunId?.let { runId ->
                    CoroutineScope(Dispatchers.IO).launch {
                        translationRunRepository.updateState(
                            runId,
                            TranslationRunState.FAILED.name,
                            LlmErrorCategory.UNKNOWN.name,
                            errMsg.take(500)
                        )
                    }
                }
            }
        }
        activeJob = scope.launch(Dispatchers.IO + failureHandler) {
            val project = projectRepository.getProjectById(projectId) ?: run {
                _jobState.value = TranslationJobState.Error("Project not found")
                emitLiveLog(LiveLogType.ERROR, "Project ID $projectId not found", projectId = projectId)
                return@launch
            }
            if (project.totalCost > 0.0 &&
                (project.costCurrency.isBlank() ||
                    project.costCurrency.equals("UNKNOWN", ignoreCase = true) ||
                    project.costCurrency.equals("MIXED", ignoreCase = true) ||
                    !project.costCurrency.equals(provider.currency, ignoreCase = true))
            ) {
                val msg = "This project has historical cost data with an unknown or different currency. Reconcile the project cost before translating again."
                _jobState.value = TranslationJobState.Error(msg)
                emitLiveLog(LiveLogType.ERROR, msg, projectId = projectId)
                return@launch
            }

            val allChapters = chapterRepository.getChaptersListByProject(projectId)
            val targets = if (chapterIds != null && chapterIds.isNotEmpty()) {
                allChapters.filter { chapterIds.contains(it.id) }
            } else {
                allChapters.filter { it.status != ChapterStatus.COMPLETED }
            }

            if (targets.isEmpty()) {
                emitLiveLog(LiveLogType.INFO, "All chapters are already translated. Nothing to process.", projectId = projectId)
                _jobState.value = TranslationJobState.Finished(
                    projectId = projectId,
                    translatedCount = 0,
                    totalPromptTokens = 0,
                    totalCompletionTokens = 0,
                    totalCost = 0.0,
                    currency = provider.currency,
                    errorCount = 0
                )
                return@launch
            }

            val existingRun = translationRunRepository.findResumable(projectId, provider.id)
            val runId = if (existingRun != null) {
                translationRunRepository.updateState(existingRun.id, TranslationRunState.RUNNING.name)
                existingRun.id
            } else {
                translationRunRepository.insert(
                    TranslationRunEntity(
                        projectId = projectId,
                        providerId = provider.id,
                        providerName = provider.name,
                        modelName = provider.selectedModel,
                        inputPricePerMillion = provider.inputPricePerMillion,
                        outputPricePerMillion = provider.outputPricePerMillion,
                        currency = provider.currency,
                        state = TranslationRunState.RUNNING.name
                    )
                )
            }
            activeRunId = runId

            emitLiveLog(
                type = LiveLogType.INFO,
                message = "Selected ${targets.size} chapter(s) to translate (Range: ${targets.first().chapterIndex} -> ${targets.last().chapterIndex})",
                projectId = projectId
            )

            var glossary = glossaryRepository.getGlossaryListByProject(projectId)

            var completedCount = 0
            var errorCount = 0
            var runningPromptTokens = 0L
            var runningCompTokens = 0L
            var runningCost = 0.0
            var batchSystemFailure = false
            var batchFailureCategory: LlmErrorCategory? = null
            var consecutiveTransientFailures = 0

            for ((_, chapter) in targets.withIndex()) {
                try {
                if (controlSignal.isCancelled || batchSystemFailure) break

                awaitRequestBoundary(runId)

                emitLiveLog(
                    type = LiveLogType.STEP,
                    message = "Starting Chapter ${chapter.chapterIndex}: 《${chapter.title}》 (${chapter.originalWordCount} words)",
                    chapterIndex = chapter.chapterIndex,
                    projectId = projectId
                )

                // Update DB status to TRANSLATING
                chapterRepository.updateStatus(chapter.id, ChapterStatus.TRANSLATING)

                val originalText = fileManager.readOriginalChapter(projectId, chapter.originalFileName)
                if (originalText.isBlank()) {
                    val err = "Original chapter file '${chapter.originalFileName}' is empty on disk"
                    emitLiveLog(LiveLogType.ERROR, err, chapterIndex = chapter.chapterIndex, projectId = projectId)
                    chapterRepository.updateTranslationResult(
                        id = chapter.id,
                        status = ChapterStatus.ERROR,
                        wordCount = 0,
                        promptTokens = 0,
                        completionTokens = 0,
                        cost = 0.0,
                        errorMsg = err
                    )
                    translationLogRepository.insertLog(
                        TranslationLogEntity(
                            projectId = projectId,
                            chapterIndex = chapter.chapterIndex,
                            chapterTitle = chapter.title,
                            modelName = provider.selectedModel,
                            providerName = provider.name,
                            promptTokens = 0,
                            completionTokens = 0,
                            totalTokens = 0,
                            estimatedCost = 0.0,
                            currency = provider.currency,
                            durationMs = 0,
                            isSuccess = false,
                            message = "Chapter ${chapter.chapterIndex} Error: $err"
                        )
                    )
                    refreshProjectStats(projectId, provider.currency)
                    errorCount++
                    continue
                }

                // Only reviewed terms that actually occur in this chapter are mandatory.
                val activeGlossary = selectGlossaryForPrompt(
                    glossary = glossary,
                    originalText = originalText,
                    maxContextTokens = provider.maxContextTokens
                )
                if (activeGlossary.isNotEmpty()) {
                    emitLiveLog(
                        type = LiveLogType.INFO,
                        message = "Matched ${activeGlossary.size} glossary term(s) for Chapter ${chapter.chapterIndex}",
                        chapterIndex = chapter.chapterIndex,
                        detail = activeGlossary.joinToString(", ") { "${it.originalTerm}➔${it.translatedTerm}" },
                        projectId = projectId
                    )
                }

                // Always reload context so summaries generated earlier in this same batch are visible.
                val latestChapters = chapterRepository.getChaptersListByProject(projectId)
                val previousChapters = latestChapters
                    .filter { it.chapterIndex < chapter.chapterIndex }
                    .sortedBy { it.chapterIndex }
                val immediatePrevious = previousChapters.lastOrNull()

                var immediateSummary = immediatePrevious?.summary.orEmpty()
                if (immediatePrevious != null && immediateSummary.isBlank() && immediatePrevious.status == ChapterStatus.COMPLETED) {
                    val prevTransText = fileManager.readTranslatedChapter(projectId, immediatePrevious.translatedFileName)
                    if (prevTransText.isNotBlank()) {
                        emitLiveLog(
                            type = LiveLogType.INFO,
                            message = "Generating continuity summary for previous Chapter ${immediatePrevious.chapterIndex}...",
                            chapterIndex = immediatePrevious.chapterIndex,
                            projectId = projectId
                        )
                        awaitRequestBoundary(runId)
                        val sResult = llmClient.executeCompletion(
                            provider = provider,
                            systemPrompt = "You maintain concise, factual continuity notes for a novel translator.",
                            userPrompt = TranslationPrompts.buildChapterSummaryPrompt(prevTransText),
                            temperature = 0.2f,
                            maxTokens = 800,
                            operation = "SUMMARY"
                        )
                        recordLlmAttempts(runId, projectId, immediatePrevious.id, immediatePrevious.chapterIndex, null, provider, sResult)
                        val sCost = TokenCalculator.calculateCost(
                            sResult.promptTokens,
                            sResult.completionTokens,
                            provider.inputPricePerMillion,
                            provider.outputPricePerMillion
                        )
                        runningPromptTokens += sResult.promptTokens
                        runningCompTokens += sResult.completionTokens
                        runningCost += sCost
                        if (sResult.isSuccess && sResult.text.isNotBlank()) {
                            immediateSummary = sResult.text.trim()
                            chapterRepository.updateSummary(immediatePrevious.id, immediateSummary)
                            emitLiveLog(
                                type = LiveLogType.SUCCESS,
                                message = "Generated continuity summary for Chapter ${immediatePrevious.chapterIndex} (${sResult.promptTokens + sResult.completionTokens} tok, ${sResult.durationMs}ms)",
                                chapterIndex = immediatePrevious.chapterIndex,
                                projectId = projectId
                            )
                        }
                        translationLogRepository.insertLog(
                            TranslationLogEntity(
                                projectId = projectId,
                                chapterIndex = immediatePrevious.chapterIndex,
                                chapterTitle = immediatePrevious.title,
                                modelName = provider.selectedModel,
                                providerName = provider.name,
                                promptTokens = sResult.promptTokens,
                                completionTokens = sResult.completionTokens,
                                totalTokens = sResult.promptTokens + sResult.completionTokens,
                                estimatedCost = sCost,
                                currency = provider.currency,
                                durationMs = sResult.durationMs,
                                isSuccess = sResult.isSuccess && sResult.text.isNotBlank(),
                                message = if (sResult.isSuccess && sResult.text.isNotBlank()) {
                                    "Generated missing narrative summary for continuity"
                                } else {
                                    "Failed to generate missing narrative summary: ${sResult.errorMessage ?: "empty response"}"
                                }
                            )
                        )
                    }
                }

                val lowContextMode = provider.maxContextTokens < 8_192
                val recentSummaries = previousChapters.takeLast(if (lowContextMode) 1 else 3).mapNotNull { previous ->
                    val summary = if (previous.id == immediatePrevious?.id && immediateSummary.isNotBlank()) {
                        immediateSummary
                    } else {
                        previous.summary
                    }
                    summary.takeIf { it.isNotBlank() }?.let {
                        "Chapter ${previous.chapterIndex} (${previous.title.take(120)}): ${it.take(if (lowContextMode) 300 else 600)}"
                    }
                }

                val previousContextSummary = buildString {
                    if (recentSummaries.isNotEmpty()) {
                        append("RECENT CHAPTER SUMMARIES:\n")
                        append(recentSummaries.joinToString("\n"))
                    }
                    if (immediatePrevious != null && !lowContextMode) {
                        val previousOriginal = fileManager.readOriginalChapter(projectId, immediatePrevious.originalFileName)
                        val previousTranslation = fileManager.readTranslatedChapter(projectId, immediatePrevious.translatedFileName)
                        if (previousOriginal.isNotBlank()) {
                            append("\n\nPREVIOUS CHAPTER SOURCE ENDING:\n")
                            append(previousOriginal.takeLast(600))
                        }
                        if (previousTranslation.isNotBlank()) {
                            append("\n\nPREVIOUS CHAPTER TRANSLATION ENDING:\n")
                            append(previousTranslation.takeLast(600))
                        }
                    }
                }.ifBlank { null }

                // 2. Token Budget & Natural Paragraph Chunking
                val glossaryOverhead = activeGlossary.sumOf {
                    TokenCalculator.estimateTokens(it.originalTerm + it.translatedTerm + it.notes) + 12L
                }
                val overheadEstimate = 700L + glossaryOverhead +
                    (previousContextSummary?.let(TokenCalculator::estimateTokens) ?: 0L)
                val maxChunkTokens = TokenCalculator.calculateChunkBudget(
                    maxContextTokens = provider.maxContextTokens,
                    overheadEstimate = overheadEstimate
                )

                val chunks = splitIntoParagraphChunks(originalText, maxChunkTokens)
                val sourceHash = sha256(originalText)
                var persistedChunks = translationChunkRepository.getByChapter(runId, chapter.id)
                if (persistedChunks.isEmpty()) {
                    translationChunkRepository.insertAll(
                        chunks.mapIndexed { index, _ ->
                            TranslationChunkEntity(
                                runId = runId,
                                chapterId = chapter.id,
                                chapterIndex = chapter.chapterIndex,
                                chunkIndex = index + 1,
                                totalChunks = chunks.size,
                                sourceHash = sourceHash
                            )
                        }
                    )
                    persistedChunks = translationChunkRepository.getByChapter(runId, chapter.id)
                }
                check(persistedChunks.all { it.sourceHash == sourceHash && it.totalChunks == chunks.size }) {
                    "Persisted chunk boundaries no longer match the source chapter; task paused for review"
                }
                // Startup/file correction: a committed temp file is authoritative even if the
                // process died between the file rename and the Room update.
                val requestLogsForRun = llmRequestLogRepository.getByRun(runId)
                persistedChunks = persistedChunks.map { chunk ->
                    if (chunk.sourceHash == sourceHash && chunk.state != TranslationChunkState.COMPLETED.name) {
                        val deterministicName = "chunk_${runId}_${chapter.id}_${chunk.chunkIndex}.txt"
                        val recovered = fileManager.readTranslationChunk(projectId, deterministicName)
                        if (recovered.isNotBlank()) {
                            val logs = requestLogsForRun.filter {
                                it.chapterId == chapter.id && it.chunkIndex == chunk.chunkIndex
                            }
                            val recoveredChunk = chunk.copy(
                                state = TranslationChunkState.COMPLETED.name,
                                translatedTempFileName = deterministicName,
                                promptTokens = logs.sumOf { it.promptTokens },
                                completionTokens = logs.sumOf { it.completionTokens },
                                cost = logs.sumOf { it.estimatedCost },
                                durationMs = logs.sumOf { it.durationMs },
                                updatedAt = System.currentTimeMillis()
                            )
                            translationChunkRepository.update(recoveredChunk)
                            recoveredChunk
                        } else chunk
                    } else chunk
                }
                val translatedChunks = mutableListOf<String>()
                var chapterPromptTokens = 0L
                var chapterCompTokens = 0L
                var chapterCost = 0.0
                var chapterDurationMs = 0L
                var chapterFailed = false
                var chapterErrorReason = ""
                var chapterFailureCategory: LlmErrorCategory? = null

                emitLiveLog(
                    type = LiveLogType.INFO,
                    message = "Chapter ${chapter.chapterIndex} sliced into ${chunks.size} chunk(s) (Budget: ${maxChunkTokens} tokens/chunk)",
                    chapterIndex = chapter.chapterIndex,
                    projectId = projectId
                )

                val systemPrompt = TranslationPrompts.buildSystemPrompt(
                    sourceLanguage = project.sourceLanguage,
                    targetLanguage = project.targetLanguage,
                    style = project.translationStyle
                )

                for (chunkIdx in chunks.indices) {
                    if (controlSignal.isCancelled) break
                    awaitRequestBoundary(runId)

                    val currentChunkText = chunks[chunkIdx]
                    val chunkDisplay = "${chunkIdx + 1}/${chunks.size}"
                    val persistedChunk = persistedChunks.firstOrNull {
                        it.chunkIndex == chunkIdx + 1 && it.parentChunkId == null
                    }

                    if (persistedChunk?.sourceHash == sourceHash &&
                        persistedChunk.state == TranslationChunkState.COMPLETED.name &&
                        !persistedChunk.translatedTempFileName.isNullOrBlank()
                    ) {
                        val savedText = fileManager.readTranslationChunk(projectId, persistedChunk.translatedTempFileName!!)
                        if (savedText.isNotBlank()) {
                            translatedChunks.add(savedText)
                            chapterPromptTokens += persistedChunk.promptTokens
                            chapterCompTokens += persistedChunk.completionTokens
                            chapterCost += persistedChunk.cost
                            chapterDurationMs += persistedChunk.durationMs
                            continue
                        }
                    }

                    persistedChunk?.let {
                        translationChunkRepository.update(
                            it.copy(
                                state = TranslationChunkState.RUNNING.name,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }

                    _jobState.value = TranslationJobState.Running(
                        projectId = projectId,
                        currentChapterIndex = chapter.chapterIndex,
                        currentChapterTitle = chapter.title,
                        currentChunkIndex = chunkIdx + 1,
                        totalChunksInChapter = chunks.size,
                        completedCount = completedCount,
                        totalToTranslate = targets.size,
                        currentPromptTokens = runningPromptTokens + chapterPromptTokens,
                        currentCompletionTokens = runningCompTokens + chapterCompTokens,
                        currentCost = runningCost + chapterCost,
                        currency = provider.currency,
                        isPaused = controlSignal.isPaused
                    )

                    emitLiveLog(
                        type = LiveLogType.INFO,
                        message = "Translating Chapter ${chapter.chapterIndex} (Chunk $chunkDisplay)...",
                        chapterIndex = chapter.chapterIndex,
                        chunkInfo = chunkDisplay,
                        projectId = projectId
                    )

                    // Reference to the end of previous chunk translation for coherence
                    val prevChunkRef = if (chunkIdx > 0 && translatedChunks.isNotEmpty()) {
                        val lastTrans = translatedChunks.last()
                        lastTrans.lines().takeLast(3).joinToString("\n").takeLast(400)
                    } else null

                    val userPrompt = if (chunks.size > 1) {
                        TranslationPrompts.buildChunkUserPrompt(
                            chapterTitle = chapter.title,
                            chunkIndex = chunkIdx + 1,
                            totalChunks = chunks.size,
                            chunkText = currentChunkText,
                            glossary = activeGlossary,
                            previousContextSummary = previousContextSummary,
                            previousChunkTranslationReference = prevChunkRef
                        )
                    } else {
                        TranslationPrompts.buildUserPrompt(
                            chapterTitle = chapter.title,
                            chapterText = currentChunkText,
                            glossary = activeGlossary,
                            previousContextSummary = previousContextSummary
                        )
                    }

                    awaitRequestBoundary(runId)
                    var result = llmClient.executeCompletion(
                        provider = provider,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        maxTokens = effectiveMaxOutputTokens(provider),
                        operation = "TRANSLATION"
                    )
                    recordLlmAttempts(runId, projectId, chapter.id, chapter.chapterIndex, chunkIdx + 1, provider, result, persistedChunk?.id)

                    if (!result.isSuccess && result.errorCategory == LlmErrorCategory.CONTEXT_OVERFLOW) {
                        val smaller = translateContextFallback(
                            runId = runId,
                            projectId = projectId,
                            chapterId = chapter.id,
                            chapterIndex = chapter.chapterIndex,
                            chunkIndex = chunkIdx + 1,
                            provider = provider,
                            systemPrompt = systemPrompt,
                            originalPrompt = userPrompt,
                            sourceText = currentChunkText,
                            maxTokens = effectiveMaxOutputTokens(provider),
                            maxChunkTokens = maxOf(512L, maxChunkTokens / 2),
                            parentChunkId = persistedChunk?.id
                        )
                        if (smaller != null) result = smaller
                    }

                    // Continue at most twice and preserve the provider's final truncation state.
                    var continuationAttempts = 0
                    while (result.isSuccess && result.isTruncated && result.text.isNotBlank() && continuationAttempts < 2) {
                        awaitRequestBoundary(runId)
                        emitLiveLog(
                            type = LiveLogType.WARNING,
                            message = "Output truncated on Chunk $chunkDisplay (attempt ${continuationAttempts + 1}/2), auto-continuing...",
                            chapterIndex = chapter.chapterIndex,
                            chunkInfo = chunkDisplay,
                            projectId = projectId
                        )
                        val contResult = llmClient.executeCompletion(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            userPrompt = TranslationPrompts.buildContinuationPrompt(currentChunkText, result.text),
                            maxTokens = effectiveMaxOutputTokens(provider),
                            operation = "CONTINUATION"
                        )
                        recordLlmAttempts(runId, projectId, chapter.id, chapter.chapterIndex, chunkIdx + 1, provider, contResult, persistedChunk?.id)
                        if (!contResult.isSuccess || contResult.text.isBlank()) {
                            result = result.copy(
                                isSuccess = false,
                                errorMessage = contResult.errorMessage ?: "Truncated translation could not be continued",
                                promptTokens = result.promptTokens + contResult.promptTokens,
                                completionTokens = result.completionTokens + contResult.completionTokens,
                                durationMs = result.durationMs + contResult.durationMs
                            )
                            break
                        }
                        result = result.copy(
                            text = mergeContinuation(result.text, contResult.text),
                            promptTokens = result.promptTokens + contResult.promptTokens,
                            completionTokens = result.completionTokens + contResult.completionTokens,
                            durationMs = result.durationMs + contResult.durationMs,
                            finishReason = contResult.finishReason,
                            isTruncated = contResult.isTruncated
                        )
                        continuationAttempts++
                    }
                    if (result.isTruncated) {
                        val discarded = result
                        var fallbackBudget = maxOf(256L, maxChunkTokens / 2)
                        var rebuilt: LlmResult? = null
                        repeat(2) {
                            if (rebuilt?.isSuccess == true) return@repeat
                            rebuilt = translateContextFallback(
                                runId = runId,
                                projectId = projectId,
                                chapterId = chapter.id,
                                chapterIndex = chapter.chapterIndex,
                                chunkIndex = chunkIdx + 1,
                                provider = provider,
                                systemPrompt = systemPrompt,
                                originalPrompt = userPrompt,
                                sourceText = currentChunkText,
                                maxTokens = effectiveMaxOutputTokens(provider),
                                maxChunkTokens = fallbackBudget,
                                parentChunkId = persistedChunk?.id
                            )
                            fallbackBudget = maxOf(128L, fallbackBudget / 2)
                        }
                        val rebuiltResult = rebuilt
                        result = rebuiltResult?.copy(
                            promptTokens = discarded.promptTokens + rebuiltResult.promptTokens,
                            completionTokens = discarded.completionTokens + rebuiltResult.completionTokens,
                            durationMs = discarded.durationMs + rebuiltResult.durationMs
                        ) ?: discarded.copy(
                            isSuccess = false,
                            errorCategory = LlmErrorCategory.TRUNCATED_OUTPUT,
                            retryable = false,
                            errorMessage = "Translation remained truncated after $continuationAttempts continuation attempts and could not be safely re-chunked"
                        )
                    }

                    // Reject omissions, altered illustration markers and obvious refusals, then retry once.
                    if (result.isSuccess && result.text.isNotBlank()) {
                        val validation = TranslationQualityValidator.validate(currentChunkText, result.text, activeGlossary)
                        if (!validation.isAcceptable) {
                            awaitRequestBoundary(runId)
                            emitLiveLog(
                                type = LiveLogType.WARNING,
                                message = "Quality check flagged Chunk $chunkDisplay: ${validation.problems.joinToString()}, retrying...",
                                chapterIndex = chapter.chapterIndex,
                                chunkInfo = chunkDisplay,
                                projectId = projectId
                            )
                            val retry = llmClient.executeCompletion(
                                provider = provider,
                                systemPrompt = systemPrompt,
                                userPrompt = TranslationPrompts.buildValidationRetryPrompt(userPrompt, validation.problems),
                                maxTokens = effectiveMaxOutputTokens(provider),
                                operation = "QUALITY_RETRY"
                            )
                            recordLlmAttempts(runId, projectId, chapter.id, chapter.chapterIndex, chunkIdx + 1, provider, retry, persistedChunk?.id)
                            val retryValidation = if (retry.isSuccess && !retry.isTruncated) {
                                TranslationQualityValidator.validate(currentChunkText, retry.text, activeGlossary)
                            } else {
                                TranslationValidation(false, listOf(retry.errorMessage ?: "retry was truncated"))
                            }
                            result = if (retryValidation.isAcceptable) {
                                emitLiveLog(
                                    type = LiveLogType.SUCCESS,
                                    message = "Chunk $chunkDisplay retry passed quality check!",
                                    chapterIndex = chapter.chapterIndex,
                                    chunkInfo = chunkDisplay,
                                    projectId = projectId
                                )
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
                                    errorMessage = "Translation validation failed: ${retryValidation.problems.joinToString()}",
                                    promptTokens = result.promptTokens + retry.promptTokens,
                                    completionTokens = result.completionTokens + retry.completionTokens,
                                    durationMs = result.durationMs + retry.durationMs
                                )
                            }
                        }
                    }

                    val auditedChunkLogs = llmRequestLogRepository.getByRun(runId).filter {
                        it.chapterId == chapter.id && it.chunkIndex == chunkIdx + 1
                    }
                    val auditedPromptTokens = auditedChunkLogs.sumOf { it.promptTokens }
                    val auditedCompletionTokens = auditedChunkLogs.sumOf { it.completionTokens }
                    val auditedDurationMs = auditedChunkLogs.sumOf { it.durationMs }
                    val chunkCost = auditedChunkLogs.sumOf { it.estimatedCost }
                    chapterPromptTokens += auditedPromptTokens
                    chapterCompTokens += auditedCompletionTokens
                    chapterCost += chunkCost
                    chapterDurationMs += auditedDurationMs
                    if (result.isSuccess && result.text.isNotBlank()) {
                        translatedChunks.add(result.text.trim())
                        persistedChunk?.let {
                            val tempFile = fileManager.saveTranslationChunkAtomically(
                                projectId = projectId,
                                runId = runId,
                                chapterId = chapter.id,
                                chunkIndex = chunkIdx + 1,
                                content = result.text.trim()
                            )
                            val currentPersistedChunk = translationChunkRepository.getById(it.id) ?: it
                            translationChunkRepository.update(
                                currentPersistedChunk.copy(
                                    state = TranslationChunkState.COMPLETED.name,
                                    translatedTempFileName = tempFile,
                                    promptTokens = auditedPromptTokens,
                                    completionTokens = auditedCompletionTokens,
                                    cost = chunkCost,
                                    durationMs = auditedDurationMs,
                                    lastErrorCategory = null,
                                    lastErrorMessage = null,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                        emitLiveLog(
                            type = LiveLogType.SUCCESS,
                            message = "Chunk $chunkDisplay completed in ${auditedDurationMs}ms (${auditedPromptTokens + auditedCompletionTokens} tok)",
                            chapterIndex = chapter.chapterIndex,
                            chunkInfo = chunkDisplay,
                            tokensInfo = "${auditedPromptTokens + auditedCompletionTokens} tok",
                            costInfo = TokenCalculator.formatCost(chunkCost, provider.currency),
                            projectId = projectId
                        )
                    } else {
                        chapterFailed = true
                        chapterErrorReason = result.errorMessage ?: "Translation chunk $chunkDisplay failed"
                        chapterFailureCategory = result.errorCategory
                        if (result.errorCategory == LlmErrorCategory.AUTHENTICATION ||
                            result.errorCategory == LlmErrorCategory.LOCAL_STORAGE
                        ) {
                            batchSystemFailure = true
                            batchFailureCategory = result.errorCategory
                        }
                        persistedChunk?.let {
                            val currentPersistedChunk = translationChunkRepository.getById(it.id) ?: it
                            translationChunkRepository.update(
                                currentPersistedChunk.copy(
                                    state = TranslationChunkState.FAILED.name,
                                    promptTokens = auditedPromptTokens,
                                    completionTokens = auditedCompletionTokens,
                                    cost = chunkCost,
                                    durationMs = auditedDurationMs,
                                    lastErrorCategory = result.errorCategory?.name,
                                    lastErrorMessage = result.errorMessage?.take(500),
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                        emitLiveLog(
                            type = LiveLogType.ERROR,
                            message = "Chunk $chunkDisplay failed: $chapterErrorReason",
                            chapterIndex = chapter.chapterIndex,
                            chunkInfo = chunkDisplay,
                            detail = result.errorMessage,
                            projectId = projectId
                        )
                        break
                    }
                }

                if (!chapterFailed && translatedChunks.size == chunks.size) {
                    val fullTranslatedText = translatedChunks.joinToString("\n\n")
                    val transFileName = fileManager.saveTranslatedChapter(projectId, chapter.chapterIndex, fullTranslatedText, chapter.title)
                    val wordCount = TxtParser.countWords(fullTranslatedText)

                    emitLiveLog(
                        type = LiveLogType.INFO,
                        message = "Generating chapter summary and extracting new terms for Chapter ${chapter.chapterIndex}...",
                        chapterIndex = chapter.chapterIndex,
                        projectId = projectId
                    )

                    // 1. Generate summary for this completed chapter
                    val summaryPrompt = TranslationPrompts.buildChapterSummaryPrompt(fullTranslatedText)
                    awaitRequestBoundary(runId)
                    val summaryResult = llmClient.executeCompletion(
                        provider = provider,
                        systemPrompt = "You are a concise narrative context summarizer. Be brief.",
                        userPrompt = summaryPrompt,
                        temperature = 0.2f,
                        maxTokens = 800,
                        operation = "SUMMARY"
                    )
                    recordLlmAttempts(runId, projectId, chapter.id, chapter.chapterIndex, null, provider, summaryResult)
                    chapterDurationMs += summaryResult.durationMs
                    chapterPromptTokens += summaryResult.promptTokens
                    chapterCompTokens += summaryResult.completionTokens
                    chapterCost += TokenCalculator.calculateCost(
                        promptTokens = summaryResult.promptTokens,
                        completionTokens = summaryResult.completionTokens,
                        inputPricePerMillion = provider.inputPricePerMillion,
                        outputPricePerMillion = provider.outputPricePerMillion
                    )
                    var finalSummary = ""
                    if (summaryResult.isSuccess && summaryResult.text.isNotBlank()) {
                        finalSummary = summaryResult.text.trim()
                    }

                    // 2. Progressive Glossary Expansion: auto-extract unrecorded key proper nouns
                    var termExtractionWarning: String? = null
                    try {
                        val confirmedTerms = glossary
                            .filter { it.reviewStatus == ReviewStatus.CONFIRMED.name }
                            .map { it.originalTerm }
                        val termScanSource = representativeExcerpt(originalText, 6000)
                        val termExtractPrompt = TranslationPrompts.buildTermExtractionPrompt(
                            textSample = termScanSource,
                            sourceLanguage = project.sourceLanguage,
                            targetLanguage = project.targetLanguage,
                            existingTerms = confirmedTerms
                        )
                        awaitRequestBoundary(runId)
                        val termResult = llmClient.executeCompletion(
                            provider = provider,
                            systemPrompt = "You are a terminology extraction assistant. Return only JSON array.",
                            userPrompt = termExtractPrompt,
                            temperature = 0.2f,
                            operation = "TERM_EXTRACTION"
                        )
                        recordLlmAttempts(runId, projectId, chapter.id, chapter.chapterIndex, null, provider, termResult)
                        chapterPromptTokens += termResult.promptTokens
                        chapterCompTokens += termResult.completionTokens
                        chapterDurationMs += termResult.durationMs
                        chapterCost += TokenCalculator.calculateCost(
                            termResult.promptTokens,
                            termResult.completionTokens,
                            provider.inputPricePerMillion,
                            provider.outputPricePerMillion
                        )
                        if (termResult.isSuccess && termResult.text.isNotBlank()) {
                            val parsedNewTerms = com.breakyuna.noveltranslator.core.agent.TermExtractionAgent.parseTermsJson(
                                projectId = projectId,
                                rawText = termResult.text,
                                sourceText = termScanSource
                            )
                            val observations = glossaryRepository.observeAiCandidates(
                                projectId = projectId,
                                chapterIndex = chapter.chapterIndex,
                                observations = parsedNewTerms
                            )
                            if (observations.isNotEmpty()) {
                                glossary = glossaryRepository.getGlossaryListByProject(projectId)
                                emitLiveLog(
                                    type = LiveLogType.SUCCESS,
                                    message = "Recorded ${observations.size} terminology observation(s) from Chapter ${chapter.chapterIndex}",
                                    chapterIndex = chapter.chapterIndex,
                                    detail = observations.joinToString(", ") { "${it.originalTerm}➔${it.translatedTerm}" },
                                    projectId = projectId
                                )
                            }
                        } else {
                            termExtractionWarning = termResult.errorMessage ?: "terminology extraction returned no usable response"
                        }
                    } catch (error: Exception) {
                        termExtractionWarning = error.localizedMessage ?: "terminology extraction failed"
                    }

                    if (!summaryResult.isSuccess || summaryResult.text.isBlank()) {
                        val summaryWarning = summaryResult.errorMessage ?: "chapter summary returned no usable response"
                        termExtractionWarning = listOfNotNull(termExtractionWarning, summaryWarning).joinToString("; ")
                    }

                    runningPromptTokens += chapterPromptTokens
                    runningCompTokens += chapterCompTokens
                    runningCost += chapterCost
                    completedCount++

                    chapterRepository.updateSummary(chapter.id, finalSummary)
                    chapterRepository.updateTranslationResult(
                        id = chapter.id,
                        status = ChapterStatus.COMPLETED,
                        wordCount = wordCount,
                        promptTokens = chapterPromptTokens,
                        completionTokens = chapterCompTokens,
                        cost = chapterCost,
                        errorMsg = null,
                        translatedFileName = transFileName
                    )

                    val successSummary = "Chapter ${chapter.chapterIndex} finished successfully ($wordCount words, total ${chapterPromptTokens + chapterCompTokens} tok, ${TokenCalculator.formatCost(chapterCost, provider.currency)})"
                    emitLiveLog(
                        type = LiveLogType.SUCCESS,
                        message = successSummary,
                        chapterIndex = chapter.chapterIndex,
                        tokensInfo = "${chapterPromptTokens + chapterCompTokens} tok",
                        costInfo = TokenCalculator.formatCost(chapterCost, provider.currency),
                        projectId = projectId
                    )

                    // Insert Log
                    translationLogRepository.insertLog(
                        TranslationLogEntity(
                            projectId = projectId,
                            chapterIndex = chapter.chapterIndex,
                            chapterTitle = chapter.title,
                            modelName = provider.selectedModel,
                            providerName = provider.name,
                            promptTokens = chapterPromptTokens,
                            completionTokens = chapterCompTokens,
                            totalTokens = chapterPromptTokens + chapterCompTokens,
                            estimatedCost = chapterCost,
                            currency = provider.currency,
                            durationMs = chapterDurationMs,
                            isSuccess = true,
                            message = if (termExtractionWarning != null) {
                                "Chapter ${chapter.chapterIndex} completed with warning: $termExtractionWarning"
                            } else if (chunks.size > 1) {
                                "Chapter ${chapter.chapterIndex} completed across ${chunks.size} chunks ($wordCount words)"
                            } else {
                                "Chapter ${chapter.chapterIndex} translated successfully ($wordCount words)"
                            }
                        )
                    )

                    val updatedChapter = chapter.copy(
                        status = ChapterStatus.COMPLETED,
                        translatedFileName = transFileName,
                        translatedWordCount = wordCount,
                        summary = finalSummary,
                        promptTokens = chapterPromptTokens,
                        completionTokens = chapterCompTokens,
                        estimatedCost = chapterCost
                    )
                    onChapterFinished?.invoke(updatedChapter)
                } else {
                    runningPromptTokens += chapterPromptTokens
                    runningCompTokens += chapterCompTokens
                    runningCost += chapterCost
                    errorCount++
                    val errorMsg = chapterErrorReason.ifBlank { "Translation failed" }
                    emitLiveLog(
                        type = LiveLogType.ERROR,
                        message = "Chapter ${chapter.chapterIndex} failed: $errorMsg",
                        chapterIndex = chapter.chapterIndex,
                        detail = errorMsg,
                        projectId = projectId
                    )
                    chapterRepository.updateTranslationResult(
                        id = chapter.id,
                        status = ChapterStatus.ERROR,
                        wordCount = 0,
                        promptTokens = chapterPromptTokens,
                        completionTokens = chapterCompTokens,
                        cost = chapterCost,
                        errorMsg = errorMsg
                    )

                    translationLogRepository.insertLog(
                        TranslationLogEntity(
                            projectId = projectId,
                            chapterIndex = chapter.chapterIndex,
                            chapterTitle = chapter.title,
                            modelName = provider.selectedModel,
                            providerName = provider.name,
                            promptTokens = chapterPromptTokens,
                            completionTokens = chapterCompTokens,
                            totalTokens = chapterPromptTokens + chapterCompTokens,
                            estimatedCost = chapterCost,
                            currency = provider.currency,
                            durationMs = chapterDurationMs,
                            isSuccess = false,
                            message = "Chapter ${chapter.chapterIndex} Error: $errorMsg"
                        )
                    )
                }

                // Update project cumulative stats in DB
                refreshProjectStats(projectId, provider.currency)

                if (chapterFailureCategory == LlmErrorCategory.NETWORK_UNAVAILABLE ||
                    chapterFailureCategory == LlmErrorCategory.TIMEOUT ||
                    chapterFailureCategory == LlmErrorCategory.RATE_LIMIT ||
                    chapterFailureCategory == LlmErrorCategory.SERVER_ERROR
                ) {
                    consecutiveTransientFailures++
                } else {
                    consecutiveTransientFailures = 0
                }
                if (consecutiveTransientFailures >= 2 && !controlSignal.isCancelled) {
                    val waitMs = minOf(60_000L, 5_000L * consecutiveTransientFailures)
                    translationRunRepository.updateState(
                        runId,
                        TranslationRunState.RETRY_WAIT.name,
                        category = chapterFailureCategory?.name,
                        message = "Several chapters hit the same transient provider failure",
                        nextRetryAt = System.currentTimeMillis() + waitMs
                    )
                    emitLiveLog(
                        LiveLogType.WARNING,
                        "Repeated provider failures; waiting ${waitMs / 1000}s before the next chapter",
                        projectId = projectId
                    )
                    delayProvider.delayFor(waitMs)
                    translationRunRepository.updateState(runId, TranslationRunState.RUNNING.name)
                } else {
                    delayProvider.delayFor(120) // Safe spacing between translation cycles
                }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val errorMessage = error.localizedMessage ?: error.javaClass.simpleName
                    val localStorageFailure = error is IOException ||
                        error is SecurityException ||
                        error is IllegalStateException ||
                        error.javaClass.name.startsWith("android.database") ||
                        error.javaClass.name.contains("SQLite")
                    if (localStorageFailure) {
                        batchSystemFailure = true
                        batchFailureCategory = LlmErrorCategory.LOCAL_STORAGE
                    }
                    errorCount++
                    chapterRepository.updateTranslationResult(
                        id = chapter.id,
                        status = ChapterStatus.ERROR,
                        wordCount = 0,
                        promptTokens = 0,
                        completionTokens = 0,
                        cost = 0.0,
                        errorMsg = errorMessage
                    )
                    translationLogRepository.insertLog(
                        TranslationLogEntity(
                            projectId = projectId,
                            chapterIndex = chapter.chapterIndex,
                            chapterTitle = chapter.title,
                            modelName = provider.selectedModel,
                            providerName = provider.name,
                            promptTokens = 0,
                            completionTokens = 0,
                            totalTokens = 0,
                            estimatedCost = 0.0,
                            currency = provider.currency,
                            durationMs = 0,
                            isSuccess = false,
                            message = "Chapter ${chapter.chapterIndex} failed unexpectedly: $errorMessage"
                        )
                    )
                    emitLiveLog(
                        LiveLogType.ERROR,
                        "Chapter ${chapter.chapterIndex} failed unexpectedly: $errorMessage",
                        chapterIndex = chapter.chapterIndex,
                        projectId = projectId
                    )
                }
            }

            emitLiveLog(
                type = if (errorCount == 0) LiveLogType.SUCCESS else LiveLogType.WARNING,
                message = "Batch complete! Translated: $completedCount, Errors: $errorCount, Total Cost: ${TokenCalculator.formatCost(runningCost, provider.currency)}",
                projectId = projectId
            )

            translationRunRepository.updateState(
                runId,
                when {
                    controlSignal.isCancelled -> TranslationRunState.CANCELLED.name
                    batchSystemFailure -> TranslationRunState.PAUSED.name
                    else -> TranslationRunState.COMPLETED.name
                },
                category = batchFailureCategory?.name,
                message = when {
                    batchSystemFailure -> "Systemic provider or storage error; task paused and can be resumed after correction"
                    errorCount > 0 -> "$errorCount chapter(s) failed"
                    else -> null
                }
            )

            _jobState.value = TranslationJobState.Finished(
                projectId = projectId,
                translatedCount = completedCount,
                totalPromptTokens = runningPromptTokens,
                totalCompletionTokens = runningCompTokens,
                totalCost = runningCost,
                currency = provider.currency,
                errorCount = errorCount
            )
            activeRunId = null
            activeProjectId = null
        }
        activeJob?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                val runId = activeRunId
                val projectIdForAudit = activeProjectId
                if (runId != null && projectIdForAudit != null) {
                    controlScope?.launch(Dispatchers.IO) {
                        val running = _jobState.value as? TranslationJobState.Running
                        val runSnapshot = translationRunRepository.getById(runId)
                        val cancellationLog = LlmRequestLogEntity(
                                runId = runId,
                                projectId = projectIdForAudit,
                                chapterIndex = running?.currentChapterIndex,
                                chunkIndex = running?.currentChunkIndex,
                                attemptNumber = -1,
                                operation = "CANCELLED",
                                providerId = runSnapshot?.providerId ?: 0,
                                providerName = runSnapshot?.providerName ?: "unknown",
                                modelName = runSnapshot?.modelName ?: "unknown",
                                inputPricePerMillion = runSnapshot?.inputPricePerMillion ?: 0.0,
                                outputPricePerMillion = runSnapshot?.outputPricePerMillion ?: 0.0,
                                currency = runSnapshot?.currency ?: "UNKNOWN",
                                promptTokens = 0,
                                completionTokens = 0,
                                totalTokens = 0,
                                usageSource = UsageSource.UNKNOWN.name,
                                estimatedCost = 0.0,
                                durationMs = 0,
                                errorCategory = LlmErrorCategory.CANCELLED.name,
                                errorMessage = "Request cancellation interrupted an in-flight call; provider usage is unknown",
                                isSuccess = false
                            )
                        translationAuditRepository.record(runId, null, listOf(cancellationLog))
                    }
                }
            }
        }
    }

    private suspend fun refreshProjectStats(projectId: Long, fallbackCurrency: String) {
        val chapters = chapterRepository.getChaptersListByProject(projectId)
        val logs = translationLogRepository.getLogsListByProject(projectId)
        val currencies = logs.map { it.currency.trim() }
            .filter { it.isNotBlank() && !it.equals("UNKNOWN", ignoreCase = true) }
            .toSet()
        val hasUnknownCost = logs.any {
            it.estimatedCost > 0.0 &&
                (it.currency.isBlank() || it.currency.equals("UNKNOWN", ignoreCase = true))
        }
        val currency = when {
            hasUnknownCost -> "UNKNOWN"
            currencies.size > 1 -> "MIXED"
            currencies.size == 1 -> currencies.first()
            else -> fallbackCurrency
        }
        projectRepository.updateProjectStats(
            projectId = projectId,
            translatedCount = chapters.count { it.status == ChapterStatus.COMPLETED },
            promptTokens = logs.sumOf { it.promptTokens },
            compTokens = logs.sumOf { it.completionTokens },
            cost = logs.sumOf { it.estimatedCost },
            currency = currency
        )
    }

    private suspend fun awaitRequestBoundary(runId: Long) {
        if (controlSignal.isCancelled) throw CancellationException("Translation cancelled")
        if (!controlSignal.isPaused) return
        translationRunRepository.updateState(runId, TranslationRunState.PAUSED.name)
        while (controlSignal.isPaused) {
            if (controlSignal.isCancelled) throw CancellationException("Translation cancelled")
            delayProvider.delayFor(250)
        }
        if (controlSignal.isCancelled) throw CancellationException("Translation cancelled")
        translationRunRepository.updateState(runId, TranslationRunState.RUNNING.name)
    }

    /** Persists every physical provider attempt immediately; no prompt or novel body is stored. */
    private suspend fun recordLlmAttempts(
        runId: Long,
        projectId: Long,
        chapterId: Long?,
        chapterIndex: Int?,
        chunkIndex: Int?,
        provider: ApiProviderEntity,
        result: LlmResult,
        chunkId: Long? = null
    ) {
        val attempts = result.attempts.ifEmpty { listOf(LlmAttempt(1, result)) }
        val logs = attempts.map { attempt ->
            val item = attempt.result
            val cost = TokenCalculator.calculateCost(
                item.promptTokens,
                item.completionTokens,
                provider.inputPricePerMillion,
                provider.outputPricePerMillion
            )
            LlmRequestLogEntity(
                    runId = runId,
                    projectId = projectId,
                    chapterId = chapterId,
                    chapterIndex = chapterIndex,
                    chunkIndex = chunkIndex,
                    attemptNumber = attempt.attemptNumber,
                    operation = item.operation,
                    providerId = provider.id,
                    providerName = provider.name,
                    modelName = provider.selectedModel,
                    inputPricePerMillion = provider.inputPricePerMillion,
                    outputPricePerMillion = provider.outputPricePerMillion,
                    currency = provider.currency,
                    promptTokens = item.promptTokens,
                    completionTokens = item.completionTokens,
                    totalTokens = item.promptTokens + item.completionTokens,
                    usageSource = item.usageSource.name,
                    estimatedCost = cost,
                    durationMs = item.durationMs,
                    httpStatus = item.httpStatus,
                    errorCategory = item.errorCategory?.name,
                    errorMessage = item.errorMessage?.take(500),
                    finishReason = item.finishReason,
                    requestId = item.requestId,
                    isSuccess = item.isSuccess
                )
        }
        translationAuditRepository.record(runId, chunkId, logs)
    }

    /**
     * Splits text into natural paragraph chunks that respect the token budget limit.
     */
    private fun splitIntoParagraphChunks(
        text: String,
        maxTokensPerChunk: Long
    ): List<String> {
        val totalTokens = TokenCalculator.estimateTokens(text)
        if (totalTokens <= maxTokensPerChunk) {
            return listOf(text.trim())
        }

        val paragraphs = text.split(Regex("(?<=\n\n)|(?<=\r\n\r\n)"))
            .ifEmpty { text.lines() }
            .filter { it.isNotBlank() }

        if (paragraphs.isEmpty()) return listOf(text.trim())

        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()
        var currentTokens = 0L

        for (para in paragraphs) {
            val paraTokens = TokenCalculator.estimateTokens(para)

            // If a single paragraph is oversized, split by sentence endings
            if (paraTokens > maxTokensPerChunk) {
                if (currentChunk.isNotBlank()) {
                    chunks.add(currentChunk.toString().trim())
                    currentChunk = StringBuilder()
                    currentTokens = 0L
                }
                val sentenceChunks = splitLongParagraphBySentences(para, maxTokensPerChunk)
                chunks.addAll(sentenceChunks)
                continue
            }

            if (currentTokens + paraTokens > maxTokensPerChunk && currentChunk.isNotBlank()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()
                currentTokens = 0L
            }

            if (currentChunk.isNotEmpty()) {
                currentChunk.append("\n\n")
            }
            currentChunk.append(para.trim())
            currentTokens += paraTokens
        }

        if (currentChunk.isNotBlank()) {
            chunks.add(currentChunk.toString().trim())
        }

        return if (chunks.isNotEmpty()) chunks else listOf(text.trim())
    }

    private fun splitLongParagraphBySentences(paragraph: String, maxTokensPerChunk: Long): List<String> {
        val sentences = paragraph.split(Regex("(?<=[。！？!?.\n])")).filter { it.isNotBlank() }
        val result = mutableListOf<String>()
        var cur = StringBuilder()
        var curTok = 0L
        for (s in sentences) {
            val st = TokenCalculator.estimateTokens(s)
            if (st > maxTokensPerChunk) {
                if (cur.isNotBlank()) {
                    result.add(cur.toString().trim())
                    cur = StringBuilder()
                    curTok = 0L
                }
                // A sentence without usable punctuation must still respect the request budget.
                val safeChars = maxOf(1, (maxTokensPerChunk / 1.6).toInt())
                s.chunked(safeChars).forEach { part -> result.add(part.trim()) }
                continue
            }
            if (curTok + st > maxTokensPerChunk && cur.isNotBlank()) {
                result.add(cur.toString().trim())
                cur = StringBuilder()
                curTok = 0L
            }
            cur.append(s)
            curTok += st
        }
        if (cur.isNotBlank()) {
            result.add(cur.toString().trim())
        }
        return if (result.isNotEmpty()) result else listOf(paragraph.trim())
    }

    private fun representativeExcerpt(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val part = maxChars / 3
        val middleStart = (text.length / 2 - part / 2).coerceAtLeast(0)
        return text.take(part) + "\n[…]\n" +
            text.substring(middleStart, (middleStart + part).coerceAtMost(text.length)) +
            "\n[…]\n" + text.takeLast(part)
    }

    private fun selectGlossaryForPrompt(
        glossary: List<GlossaryEntity>,
        originalText: String,
        maxContextTokens: Int
    ): List<GlossaryEntity> {
        val budget = maxOf(200L, maxContextTokens.coerceAtLeast(4_096).toLong() / 12L)
        var used = 0L
        val selected = mutableListOf<GlossaryEntity>()
        val seen = mutableSetOf<String>()
        for (term in glossary) {
            val original = term.originalTerm.trim()
            if (
                term.reviewStatus != ReviewStatus.CONFIRMED.name ||
                original.isBlank() ||
                term.translatedTerm.isBlank() ||
                original.length > 200
            ) continue
            val key = LexiconCandidateVoting.normalizeSourceTerm(original)
            if (!seen.add(key) || !originalText.contains(original, ignoreCase = true)) continue
            val bounded = term.copy(
                originalTerm = original.take(120),
                translatedTerm = term.translatedTerm.trim().take(120),
                notes = term.notes.trim().take(240)
            )
            val cost = TokenCalculator.estimateTokens(
                bounded.originalTerm + bounded.translatedTerm + bounded.notes
            ) + 12L
            if (used + cost > budget) continue
            selected += bounded
            used += cost
            if (selected.size == 40) break
        }
        return selected
    }

    private fun mergeContinuation(previous: String, continuation: String): String {
        val left = previous.trimEnd()
        val right = continuation.trimStart()
        val leftTail = left.takeLast(800)
        val rightHead = right.take(800)
        val maxOverlap = minOf(leftTail.length, rightHead.length)
        var overlap = 0
        for (size in maxOverlap downTo 20) {
            if (leftTail.takeLast(size) == rightHead.take(size)) {
                overlap = size
                break
            }
        }
        return left + "\n" + right.drop(overlap).trimStart()
    }

    private fun effectiveMaxOutputTokens(provider: ApiProviderEntity): Int =
        minOf(16_384, maxOf(1_024, provider.maxContextTokens / 2))

    private suspend fun translateContextFallback(
        runId: Long,
        projectId: Long,
        chapterId: Long,
        chapterIndex: Int,
        chunkIndex: Int,
        provider: ApiProviderEntity,
        systemPrompt: String,
        originalPrompt: String,
        sourceText: String,
        maxTokens: Int,
        maxChunkTokens: Long,
        parentChunkId: Long?
    ): LlmResult? {
        val pieces = splitLongParagraphBySentences(sourceText, maxChunkTokens)
            .filter { it.isNotBlank() }
        if (pieces.size <= 1) return null
        val childRecords = if (parentChunkId != null) {
            val existingChildren = translationChunkRepository.getChildren(runId, parentChunkId)
            if (existingChildren.size == pieces.size && existingChildren.zip(pieces).all { (child, piece) ->
                    child.sourceHash == sha256(piece)
                }) {
                existingChildren
            } else {
                translationChunkRepository.insertAll(
                    pieces.mapIndexed { index, piece ->
                        TranslationChunkEntity(
                            runId = runId,
                            chapterId = chapterId,
                            chapterIndex = chapterIndex,
                            chunkIndex = index + 1,
                            totalChunks = pieces.size,
                            sourceHash = sha256(piece),
                            parentChunkId = parentChunkId,
                            parentChunkKey = parentChunkId,
                            state = TranslationChunkState.PENDING.name
                        )
                    }
                )
                translationChunkRepository.getChildren(runId, parentChunkId)
            }
        } else {
            emptyList()
        }

        val results = pieces.mapIndexed { index, piece ->
            val child = childRecords.firstOrNull { it.chunkIndex == index + 1 }
            if (child?.state == TranslationChunkState.COMPLETED.name &&
                !child.translatedTempFileName.isNullOrBlank()
            ) {
                val saved = fileManager.readTranslationChunk(projectId, child.translatedTempFileName!!)
                if (saved.isNotBlank()) {
                    return@mapIndexed LlmResult(
                        text = saved,
                        promptTokens = child.promptTokens,
                        completionTokens = child.completionTokens,
                        isSuccess = true,
                        usageSource = UsageSource.ACTUAL,
                        durationMs = child.durationMs,
                        operation = "CONTEXT_RECHUNK_REUSED"
                    )
                }
            }
            child?.let {
                val currentChild = translationChunkRepository.getById(it.id) ?: it
                translationChunkRepository.update(
                    currentChild.copy(
                        state = TranslationChunkState.RUNNING.name,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            val prompt = originalPrompt.replace(sourceText, piece)
            awaitRequestBoundary(runId)
            val result = llmClient.executeCompletion(
                provider = provider,
                systemPrompt = systemPrompt,
                userPrompt = prompt,
                maxTokens = maxTokens,
                operation = "CONTEXT_RECHUNK"
            ).also { result ->
                recordLlmAttempts(runId, projectId, chapterId, chapterIndex, chunkIndex, provider, result, child?.id)
            }
            val childCost = TokenCalculator.calculateCost(
                result.promptTokens,
                result.completionTokens,
                provider.inputPricePerMillion,
                provider.outputPricePerMillion
            )
            child?.let {
                val tempFile = if (result.isSuccess && result.text.isNotBlank() && !result.isTruncated) {
                    fileManager.saveTranslationChunkAtomically(
                        projectId = projectId,
                        runId = runId,
                        chapterId = chapterId,
                        chunkIndex = index + 1,
                        content = result.text.trim(),
                        chunkKey = "parent_${parentChunkId}_child_${index + 1}"
                    )
                } else null
                val currentChild = translationChunkRepository.getById(it.id) ?: it
                translationChunkRepository.update(
                    currentChild.copy(
                        state = if (result.isSuccess && result.text.isNotBlank() && !result.isTruncated) {
                            TranslationChunkState.COMPLETED.name
                        } else {
                            TranslationChunkState.FAILED.name
                        },
                        translatedTempFileName = tempFile,
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        cost = childCost,
                        durationMs = result.durationMs,
                        lastErrorCategory = result.errorCategory?.name,
                        lastErrorMessage = result.errorMessage,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            result
        }
        val successful = results.all { it.isSuccess && it.text.isNotBlank() && !it.isTruncated }
        val truncated = results.any { it.isTruncated || it.errorCategory == LlmErrorCategory.TRUNCATED_OUTPUT }
        return LlmResult(
            text = results.joinToString("\n\n") { it.text.trim() },
            promptTokens = results.sumOf { it.promptTokens },
            completionTokens = results.sumOf { it.completionTokens },
            isSuccess = successful,
            errorCategory = when {
                successful -> null
                truncated -> LlmErrorCategory.TRUNCATED_OUTPUT
                else -> results.firstOrNull { !it.isSuccess }?.errorCategory
            },
            retryable = false,
            usageSource = if (results.any { it.usageSource == UsageSource.ACTUAL }) UsageSource.ACTUAL else UsageSource.ESTIMATED,
            errorMessage = if (successful) null else results.firstOrNull { !it.isSuccess }?.errorMessage,
            durationMs = results.sumOf { it.durationMs },
            isTruncated = truncated,
            operation = "CONTEXT_RECHUNK"
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
