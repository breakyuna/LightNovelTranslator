package com.example.core.translator

import com.example.core.llm.LlmClient
import com.example.core.llm.TokenCalculator
import com.example.core.llm.TranslationPrompts
import com.example.core.parser.TxtParser
import com.example.core.project.ProjectFileManager
import com.example.data.model.*
import com.example.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

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
    private val fileManager: ProjectFileManager,
    private val llmClient: LlmClient
) {
    private val _jobState = MutableStateFlow<TranslationJobState>(TranslationJobState.Idle)
    val jobState: StateFlow<TranslationJobState> = _jobState.asStateFlow()

    private var activeJob: Job? = null
    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

    fun pause() {
        isPaused.set(true)
        val current = _jobState.value
        if (current is TranslationJobState.Running) {
            _jobState.value = current.copy(isPaused = true)
        }
    }

    fun resume() {
        isPaused.set(false)
        val current = _jobState.value
        if (current is TranslationJobState.Running) {
            _jobState.value = current.copy(isPaused = false)
        }
    }

    fun stop() {
        isCancelled.set(true)
        activeJob?.cancel()
        _jobState.value = TranslationJobState.Idle
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

        isPaused.set(false)
        isCancelled.set(false)

        val failureHandler = CoroutineExceptionHandler { _, throwable ->
            if (throwable !is CancellationException) {
                _jobState.value = TranslationJobState.Error(
                    throwable.localizedMessage ?: "Unexpected translation pipeline failure"
                )
            }
        }
        activeJob = scope.launch(Dispatchers.IO + failureHandler) {
            val project = projectRepository.getProjectById(projectId) ?: run {
                _jobState.value = TranslationJobState.Error("Project not found")
                return@launch
            }
            if (project.totalCost > 0.0 &&
                (project.costCurrency.isBlank() ||
                    project.costCurrency.equals("UNKNOWN", ignoreCase = true) ||
                    project.costCurrency.equals("MIXED", ignoreCase = true) ||
                    !project.costCurrency.equals(provider.currency, ignoreCase = true))
            ) {
                _jobState.value = TranslationJobState.Error(
                    "This project has historical cost data with an unknown or different currency. Reconcile the project cost before translating again."
                )
                return@launch
            }

            val allChapters = chapterRepository.getChaptersListByProject(projectId)
            val targets = if (chapterIds != null && chapterIds.isNotEmpty()) {
                allChapters.filter { chapterIds.contains(it.id) }
            } else {
                allChapters.filter { it.status != ChapterStatus.COMPLETED }
            }

            if (targets.isEmpty()) {
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

            var glossary = glossaryRepository.getGlossaryListByProject(projectId)

            var completedCount = 0
            var errorCount = 0
            var runningPromptTokens = 0L
            var runningCompTokens = 0L
            var runningCost = 0.0

            for ((_, chapter) in targets.withIndex()) {
                if (isCancelled.get()) break

                while (isPaused.get()) {
                    delay(500)
                    if (isCancelled.get()) break
                }
                if (isCancelled.get()) break

                // Update DB status to TRANSLATING
                chapterRepository.updateStatus(chapter.id, ChapterStatus.TRANSLATING)

                val originalText = fileManager.readOriginalChapter(projectId, chapter.originalFileName)
                if (originalText.isBlank()) {
                    chapterRepository.updateTranslationResult(
                        id = chapter.id,
                        status = ChapterStatus.ERROR,
                        wordCount = 0,
                        promptTokens = 0,
                        completionTokens = 0,
                        cost = 0.0,
                        errorMsg = "Original chapter file is empty"
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
                            message = "Chapter ${chapter.chapterIndex} Error: original file is empty"
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
                        val sResult = llmClient.executeCompletion(
                            provider = provider,
                            systemPrompt = "You maintain concise, factual continuity notes for a novel translator.",
                            userPrompt = TranslationPrompts.buildChapterSummaryPrompt(prevTransText),
                            temperature = 0.2f,
                            maxTokens = 800
                        )
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
                val translatedChunks = mutableListOf<String>()
                var chapterPromptTokens = 0L
                var chapterCompTokens = 0L
                var chapterCost = 0.0
                var chapterDurationMs = 0L
                var chapterFailed = false
                var chapterErrorReason = ""

                val systemPrompt = TranslationPrompts.buildSystemPrompt(
                    sourceLanguage = project.sourceLanguage,
                    targetLanguage = project.targetLanguage,
                    style = project.translationStyle
                )

                for (chunkIdx in chunks.indices) {
                    if (isCancelled.get()) break
                    while (isPaused.get()) {
                        delay(500)
                        if (isCancelled.get()) break
                    }
                    if (isCancelled.get()) break

                    val currentChunkText = chunks[chunkIdx]

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
                        isPaused = isPaused.get()
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

                    var result = llmClient.executeCompletion(
                        provider = provider,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        maxTokens = effectiveMaxOutputTokens(provider)
                    )

                    // Continue at most three times and preserve the provider's final truncation state.
                    var continuationAttempts = 0
                    while (result.isSuccess && result.isTruncated && result.text.isNotBlank() && continuationAttempts < 3) {
                        val contResult = llmClient.executeCompletion(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            userPrompt = TranslationPrompts.buildContinuationPrompt(currentChunkText, result.text),
                            maxTokens = effectiveMaxOutputTokens(provider)
                        )
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
                    if (result.isSuccess && result.isTruncated) {
                        result = result.copy(
                            isSuccess = false,
                            errorMessage = "Translation remained truncated after $continuationAttempts continuation attempts"
                        )
                    }

                    // Reject omissions, altered illustration markers and obvious refusals, then retry once.
                    if (result.isSuccess && result.text.isNotBlank()) {
                        val validation = TranslationQualityValidator.validate(currentChunkText, result.text)
                        if (!validation.isAcceptable) {
                            val retry = llmClient.executeCompletion(
                                provider = provider,
                                systemPrompt = systemPrompt,
                                userPrompt = TranslationPrompts.buildValidationRetryPrompt(userPrompt, validation.problems),
                                maxTokens = effectiveMaxOutputTokens(provider)
                            )
                            val retryValidation = if (retry.isSuccess && !retry.isTruncated) {
                                TranslationQualityValidator.validate(currentChunkText, retry.text)
                            } else {
                                TranslationValidation(false, listOf(retry.errorMessage ?: "retry was truncated"))
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
                                    errorMessage = "Translation validation failed: ${retryValidation.problems.joinToString()}",
                                    promptTokens = result.promptTokens + retry.promptTokens,
                                    completionTokens = result.completionTokens + retry.completionTokens,
                                    durationMs = result.durationMs + retry.durationMs
                                )
                            }
                        }
                    }

                    val chunkCost = TokenCalculator.calculateCost(
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        inputPricePerMillion = provider.inputPricePerMillion,
                        outputPricePerMillion = provider.outputPricePerMillion
                    )
                    chapterPromptTokens += result.promptTokens
                    chapterCompTokens += result.completionTokens
                    chapterCost += chunkCost
                    chapterDurationMs += result.durationMs
                    if (result.isSuccess && result.text.isNotBlank()) {
                        translatedChunks.add(result.text.trim())
                    } else {
                        chapterFailed = true
                        chapterErrorReason = result.errorMessage ?: "Translation chunk ${chunkIdx + 1}/${chunks.size} failed"
                        break
                    }
                }

                if (!chapterFailed && translatedChunks.size == chunks.size) {
                    val fullTranslatedText = translatedChunks.joinToString("\n\n")
                    val transFileName = fileManager.saveTranslatedChapter(projectId, chapter.chapterIndex, fullTranslatedText, chapter.title)
                    val wordCount = TxtParser.countWords(fullTranslatedText)

                    // 1. Generate summary for this completed chapter
                    val summaryPrompt = TranslationPrompts.buildChapterSummaryPrompt(fullTranslatedText)
                    val summaryResult = llmClient.executeCompletion(
                        provider = provider,
                        systemPrompt = "You are a concise narrative context summarizer. Be brief.",
                        userPrompt = summaryPrompt,
                        temperature = 0.2f,
                        maxTokens = 800
                    )
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
                        val existingKeys = glossary.map { it.originalTerm.trim().lowercase() }.toSet()
                        val termExtractPrompt = """
                            Extract 1-6 important proper nouns from this chapter that are not already known.
                            Source language: ${project.sourceLanguage}
                            Required translation language: ${project.targetLanguage}
                            Allowed categories: CHARACTER, LOCATION, LORE, SKILL, ITEM, HONORIFIC, CUSTOM.
                            Return JSON only: [{"original":"...","suggested":"...","category":"CHARACTER","notes":"brief evidence"}].

                            Original chapter sample:
                            ${representativeExcerpt(originalText, 6000)}

                            Translation sample:
                            ${representativeExcerpt(fullTranslatedText, 6000)}
                        """.trimIndent()
                        val termResult = llmClient.executeCompletion(
                            provider = provider,
                            systemPrompt = "You are a terminology extraction assistant. Return only JSON array.",
                            userPrompt = termExtractPrompt,
                            temperature = 0.2f
                        )
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
                            val parsedNewTerms = com.example.core.agent.TermExtractionAgent.parseTermsJson(projectId, termResult.text)
                            val brandNewTerms = parsedNewTerms.filter { 
                                it.originalTerm.isNotBlank() && 
                                it.translatedTerm.isNotBlank() && 
                                !existingKeys.contains(it.originalTerm.trim().lowercase()) 
                            }
                            if (brandNewTerms.isNotEmpty()) {
                                glossaryRepository.insertTerms(brandNewTerms)
                                glossary = glossaryRepository.getGlossaryListByProject(projectId)
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

                delay(120) // Safe spacing between translation cycles
            }

            _jobState.value = TranslationJobState.Finished(
                projectId = projectId,
                translatedCount = completedCount,
                totalPromptTokens = runningPromptTokens,
                totalCompletionTokens = runningCompTokens,
                totalCost = runningCost,
                currency = provider.currency,
                errorCount = errorCount
            )
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
            if (term.isAutoExtracted || original.isBlank() || original.length > 200) continue
            val key = original.lowercase()
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
}
