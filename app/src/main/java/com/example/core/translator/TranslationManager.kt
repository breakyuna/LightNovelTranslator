package com.example.core.translator

import com.example.core.llm.LlmClient
import com.example.core.llm.LlmResult
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

        activeJob = scope.launch(Dispatchers.IO) {
            val project = projectRepository.getProjectById(projectId) ?: run {
                _jobState.value = TranslationJobState.Error("Project not found")
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
                    errorCount++
                    continue
                }

                // 1. Context isolation: Find summary strictly from the previous chapter (index - 1)
                var previousContextSummary: String? = null
                if (chapter.chapterIndex > 1) {
                    val prevChapter = allChapters.firstOrNull { it.chapterIndex == chapter.chapterIndex - 1 }
                    if (prevChapter != null) {
                        if (prevChapter.summary.isNotBlank()) {
                            previousContextSummary = prevChapter.summary
                        } else if (prevChapter.status == ChapterStatus.COMPLETED) {
                            // Generate summary on-the-fly for previous chapter
                            val prevTransText = fileManager.readTranslatedChapter(projectId, prevChapter.translatedFileName)
                            if (prevTransText.isNotBlank()) {
                                val sPrompt = TranslationPrompts.buildChapterSummaryPrompt(prevTransText)
                                val sResult = llmClient.executeCompletion(
                                    provider = provider,
                                    systemPrompt = "You are a concise narrative context summarizer.",
                                    userPrompt = sPrompt,
                                    temperature = 0.2f
                                )
                                if (sResult.isSuccess && sResult.text.isNotBlank()) {
                                    val summaryText = sResult.text.trim()
                                    chapterRepository.updateSummary(prevChapter.id, summaryText)
                                    previousContextSummary = summaryText

                                    val sCost = TokenCalculator.calculateCost(
                                        promptTokens = sResult.promptTokens,
                                        completionTokens = sResult.completionTokens,
                                        inputPricePerMillion = provider.inputPricePerMillion,
                                        outputPricePerMillion = provider.outputPricePerMillion
                                    )
                                    runningPromptTokens += sResult.promptTokens
                                    runningCompTokens += sResult.completionTokens
                                    runningCost += sCost

                                    translationLogRepository.insertLog(
                                        TranslationLogEntity(
                                            projectId = projectId,
                                            chapterIndex = prevChapter.chapterIndex,
                                            chapterTitle = prevChapter.title,
                                            modelName = provider.selectedModel,
                                            providerName = provider.name,
                                            promptTokens = sResult.promptTokens,
                                            completionTokens = sResult.completionTokens,
                                            totalTokens = sResult.promptTokens + sResult.completionTokens,
                                            estimatedCost = sCost,
                                            durationMs = sResult.durationMs,
                                            isSuccess = true,
                                            message = "Generated narrative summary for context continuity"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Token Budget & Natural Paragraph Chunking
                val overheadEstimate = 700L + (glossary.size * 25L) + (if (previousContextSummary != null) 200L else 0L)
                val maxChunkTokens = TokenCalculator.calculateChunkBudget(
                    maxContextTokens = provider.maxContextTokens,
                    overheadEstimate = overheadEstimate
                )

                val chunks = splitIntoParagraphChunks(originalText, maxChunkTokens)
                val translatedChunks = mutableListOf<String>()
                var chapterPromptTokens = 0L
                var chapterCompTokens = 0L
                var chapterCost = 0.0
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
                            glossary = glossary,
                            previousContextSummary = previousContextSummary,
                            previousChunkTranslationReference = prevChunkRef
                        )
                    } else {
                        TranslationPrompts.buildUserPrompt(
                            chapterTitle = chapter.title,
                            chapterText = currentChunkText,
                            glossary = glossary,
                            previousContextSummary = previousContextSummary
                        )
                    }

                    var result = llmClient.executeCompletion(
                        provider = provider,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        maxTokens = minOf(4096, provider.maxContextTokens / 2)
                    )

                    // Handle Truncation / Continuation
                    if (result.isSuccess && result.isTruncated && result.text.isNotBlank()) {
                        val continuationPrompt = TranslationPrompts.buildContinuationPrompt(
                            originalChunkText = currentChunkText,
                            partialTranslation = result.text
                        )
                        val contResult = llmClient.executeCompletion(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            userPrompt = continuationPrompt,
                            maxTokens = minOf(4096, provider.maxContextTokens / 2)
                        )
                        if (contResult.isSuccess && contResult.text.isNotBlank()) {
                            val mergedText = result.text.trim() + "\n" + contResult.text.trim()
                            result = result.copy(
                                text = mergedText,
                                promptTokens = result.promptTokens + contResult.promptTokens,
                                completionTokens = result.completionTokens + contResult.completionTokens,
                                durationMs = result.durationMs + contResult.durationMs,
                                isTruncated = false
                            )
                        }
                    }

                    if (result.isSuccess && result.text.isNotBlank()) {
                        val chunkCost = TokenCalculator.calculateCost(
                            promptTokens = result.promptTokens,
                            completionTokens = result.completionTokens,
                            inputPricePerMillion = provider.inputPricePerMillion,
                            outputPricePerMillion = provider.outputPricePerMillion
                        )
                        translatedChunks.add(result.text.trim())
                        chapterPromptTokens += result.promptTokens
                        chapterCompTokens += result.completionTokens
                        chapterCost += chunkCost
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
                        temperature = 0.2f
                    )
                    var finalSummary = ""
                    if (summaryResult.isSuccess && summaryResult.text.isNotBlank()) {
                        finalSummary = summaryResult.text.trim()
                        val summaryCost = TokenCalculator.calculateCost(
                            promptTokens = summaryResult.promptTokens,
                            completionTokens = summaryResult.completionTokens,
                            inputPricePerMillion = provider.inputPricePerMillion,
                            outputPricePerMillion = provider.outputPricePerMillion
                        )
                        chapterPromptTokens += summaryResult.promptTokens
                        chapterCompTokens += summaryResult.completionTokens
                        chapterCost += summaryCost
                    }

                    // 2. Progressive Glossary Expansion: auto-extract unrecorded key proper nouns
                    try {
                        val existingKeys = glossary.map { it.originalTerm.trim().lowercase() }.toSet()
                        val termExtractPrompt = "Extract 1-4 novel character names, factions or magical items from this chapter translation that are not in current terms. Format as JSON: [{\"original\": \"...\", \"suggested\": \"...\", \"category\": \"CHARACTER/LOCATION/ITEM/FACTION\", \"notes\": \"...\"}]. Return only JSON array.\n\nOriginal sample:\n${originalText.take(1200)}\n\nTranslation sample:\n${fullTranslatedText.take(1200)}"
                        val termResult = llmClient.executeCompletion(
                            provider = provider,
                            systemPrompt = "You are a terminology extraction assistant. Return only JSON array.",
                            userPrompt = termExtractPrompt,
                            temperature = 0.2f
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
                        }
                    } catch (_: Exception) {}

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
                        errorMsg = null
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
                            durationMs = 0,
                            isSuccess = true,
                            message = if (chunks.size > 1) {
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
                            durationMs = 0,
                            isSuccess = false,
                            message = "Chapter ${chapter.chapterIndex} Error: $errorMsg"
                        )
                    )
                }

                // Update project cumulative stats in DB
                val updatedChapters = chapterRepository.getChaptersListByProject(projectId)
                val totalDone = updatedChapters.count { it.status == ChapterStatus.COMPLETED }
                val totalP = updatedChapters.sumOf { it.promptTokens }
                val totalC = updatedChapters.sumOf { it.completionTokens }
                val totalCostSum = updatedChapters.sumOf { it.estimatedCost }
                projectRepository.updateProjectStats(projectId, totalDone, totalP, totalC, totalCostSum)

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
}
