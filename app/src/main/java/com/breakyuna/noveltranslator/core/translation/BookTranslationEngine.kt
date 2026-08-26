package com.breakyuna.noveltranslator.core.translation

import androidx.room.withTransaction
import com.breakyuna.noveltranslator.core.book.BookFileManager
import com.breakyuna.noveltranslator.core.llm.*
import com.breakyuna.noveltranslator.core.logger.SystemLogger
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global scheduler: different books may run concurrently; every book is protected by one mutex.
 * A project processes batches strictly in order and commits each valid chapter independently.
 */
class BookTranslationScheduler(
    private val engine: BookTranslationEngine,
    maxConcurrentBooks: Int = 3
) {
    private val bookLocks = ConcurrentHashMap<Long, Mutex>()
    private val globalSlots = Semaphore(maxConcurrentBooks.coerceAtLeast(1))

    suspend fun run(projectId: Long) {
        val bookId = engine.projectBookId(projectId) ?: return
        globalSlots.withPermit {
            bookLocks.getOrPut(bookId) { Mutex() }.withLock { engine.runStrictlySerial(projectId) }
        }
    }

    suspend fun pause(projectId: Long) = engine.pause(projectId)
    suspend fun resume(projectId: Long) = engine.resume(projectId)
    suspend fun cancel(projectId: Long) = engine.cancel(projectId)
}

class BookTranslationEngine(
    private val database: AppDatabase,
    private val files: BookFileManager,
    private val gateway: LlmGateway,
    private val contextEngine: ContextEngine,
    private val providerResolver: suspend (Long) -> ApiProviderEntity?,
    private val promptCacheCapability: ProviderPromptCacheCapability = NoPromptCacheCapability
) {
    private val books = database.bookDao()
    private val projects = database.translationProjectV2Dao()
    private val tasks = database.platformTaskDao()
    private val controls = ConcurrentHashMap<Long, ProjectControl>()
    private val activeRuns = ConcurrentHashMap<Long, Long>()

    suspend fun projectBookId(projectId: Long): Long? = projects.get(projectId)?.bookId

    suspend fun pause(projectId: Long) {
        controls.getOrPut(projectId) { ProjectControl() }.paused.set(true)
        projects.updateState(projectId, "PAUSED")
        activeRuns[projectId]?.let { runId -> tasks.getRun(runId)?.let { tasks.updateRun(it.copy(state = "PAUSED", updatedAt = System.currentTimeMillis())) } }
    }

    suspend fun resume(projectId: Long) {
        controls.getOrPut(projectId) { ProjectControl() }.paused.set(false)
        projects.updateState(projectId, "RUNNING")
        activeRuns[projectId]?.let { runId -> tasks.getRun(runId)?.let { tasks.updateRun(it.copy(state = "RUNNING", updatedAt = System.currentTimeMillis())) } }
    }

    suspend fun cancel(projectId: Long) {
        controls.getOrPut(projectId) { ProjectControl() }.cancelled.set(true)
        projects.updateState(projectId, "CANCELLED")
        activeRuns[projectId]?.let { runId -> tasks.getRun(runId)?.let { tasks.updateRun(it.copy(state = "CANCELLED", updatedAt = System.currentTimeMillis())) } }
    }

    suspend fun runStrictlySerial(projectId: Long) {
        val project = projects.get(projectId) ?: return
        // Provider credentials are encrypted at rest. Always resolve through the repository
        // decryption boundary instead of reading ApiProviderDao directly.
        val provider = project.providerId?.let { providerResolver(it) }?.let { resolved ->
            resolved.copy(selectedModel = project.modelName.ifBlank { resolved.selectedModel })
        }
            ?: error("Translation project has no available provider")
        val targets = selectTargets(project)
        if (targets.isEmpty()) {
            SystemLogger.info("TRANSLATION", "项目 #${project.id} 所有章节已全部翻译完成，无需新批次。", projectId = project.id)
            return
        }
        val control = controls.getOrPut(projectId) { ProjectControl() }.apply { paused.set(false); cancelled.set(false) }
        val runId = tasks.insertRun(
            PlatformTranslationRunEntity(
                translationProjectId = project.id,
                bookId = project.bookId,
                providerId = provider.id,
                providerName = provider.name,
                modelName = provider.selectedModel,
                state = "RUNNING",
                currency = provider.currency
            )
        )
        activeRuns[projectId] = runId
        projects.updateState(project.id, "RUNNING")
        SystemLogger.info(
            "TRANSLATION",
            "🚀 启动翻译任务: Project#$projectId (Book#${project.bookId})，使用模型: ${provider.name}/${provider.selectedModel}，共待翻译 ${targets.size} 个章节",
            projectId = project.id
        )
        try {
            var cursor = 0
            var batchIndex = 0
            while (cursor < targets.size) {
                awaitBoundary(projectId, control)
                val candidates = targets.drop(cursor).take(project.maxBatchChapters.coerceIn(1, 5))
                val sourceChapters = candidates.mapIndexed { index, chapter -> loadSourceChapter(project, chapter, index + 1) }
                val sourceTokens = sourceChapters.map { chapter -> chapter.segments.sumOf { TokenBudgetPlanner.estimate(it.text) } }
                val combinedSource = sourceChapters.flatMap { it.segments }.joinToString("\n") { it.text }
                val context = contextEngine.prepare(project, combinedSource, sourceChapters.first().chapterIndex)
                val fixedTokens = TokenBudgetPlanner.estimate(context.stablePrefix + context.recentContext) + 700
                val budget = TokenBudgetPlanner.plan(
                    maxContextTokens = provider.maxContextTokens,
                    userMaxBatchSize = project.maxBatchChapters,
                    sourceTokenEstimates = sourceTokens,
                    fixedContextTokens = fixedTokens
                )
                val actual = if (budget.requiresSingleChapterChunking) 1 else budget.actualBatchSize.coerceAtLeast(1)
                val batchSources = sourceChapters.take(actual)
                val batchId = tasks.insertBatch(
                    PlatformTranslationBatchEntity(
                        runId = runId,
                        batchIndex = batchIndex,
                        firstChapterIndex = batchSources.first().chapterIndex,
                        lastChapterIndex = batchSources.last().chapterIndex,
                        state = "RUNNING"
                    )
                )
                SystemLogger.info(
                    "BATCH",
                    "📦 开始处理批次 #$batchIndex (章节: ${batchSources.map { "第${it.chapterIndex}章" }.joinToString(", ")})，预估Token: ${sourceTokens.take(actual).sum()}",
                    projectId = project.id,
                    chapterIndex = batchSources.first().chapterIndex
                )
                if (budget.requiresSingleChapterChunking) {
                    translateOversizedChapter(project, provider, runId, batchId, context, batchSources.first())
                } else {
                    translateBatch(project, provider, runId, batchId, context, batchSources)
                }
                cursor += actual
                batchIndex++
            }
            val target = books.getEdition(project.targetEditionId)
            val allChapters = books.getChapters(project.bookId)
            val editionComplete = allChapters.all { books.getEditionChapter(project.targetEditionId, it.id) != null }
            target?.let { books.updateEdition(it.copy(isComplete = editionComplete, updatedAt = System.currentTimeMillis())) }
            val run = tasks.getRun(runId)
            val finalState = if ((run?.failedChapters ?: 0) > 0) "COMPLETED_WITH_ERRORS" else "COMPLETED"
            projects.updateState(project.id, finalState)
            run?.let { tasks.updateRun(it.copy(state = finalState, updatedAt = System.currentTimeMillis())) }
            SystemLogger.info(
                "TRANSLATION",
                "✅ 翻译任务执行完毕！最终状态: $finalState，已成功翻译 ${run?.completedChapters ?: 0} 章，失败 ${run?.failedChapters ?: 0} 章",
                projectId = project.id
            )
        } catch (cancelled: CancellationException) {
            projects.updateState(project.id, "CANCELLED")
            tasks.getRun(runId)?.let { tasks.updateRun(it.copy(state = "CANCELLED", updatedAt = System.currentTimeMillis())) }
            SystemLogger.warn("TRANSLATION", "🛑 翻译任务已被用户取消", projectId = project.id)
        } catch (error: Throwable) {
            val failureReport = buildFailureReport(error)
            projects.updateState(project.id, "FAILED")
            tasks.getRun(runId)?.let {
                tasks.updateRun(it.copy(state = "FAILED", lastError = failureReport.take(2_000), updatedAt = System.currentTimeMillis()))
            }
            tasks.insertRequestLog(
                PlatformRequestLogEntity(
                    runId = runId,
                    batchId = null,
                    operation = "TRANSLATION_RUN",
                    attemptCount = 1,
                    promptTokens = 0,
                    completionTokens = 0,
                    estimatedCost = 0.0,
                    durationMs = 0,
                    errorCategory = error::class.simpleName ?: "UNEXPECTED_ERROR",
                    errorMessage = failureReport,
                    isSuccess = false
                )
            )
            SystemLogger.error("TRANSLATION", "❌ 翻译任务异常终止: ${error.message}", details = failureReport, projectId = project.id)
            throw error
        } finally {
            activeRuns.remove(projectId)
        }
    }

    private fun buildFailureReport(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            val throwable = current
            if (depth > 0) append("\nCaused by: ")
            append(throwable::class.simpleName ?: "Error")
            throwable.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            current = throwable.cause?.takeUnless { it === throwable }
            depth++
        }
    }

    private suspend fun awaitBoundary(projectId: Long, control: ProjectControl) {
        if (control.cancelled.get()) throw CancellationException("Translation cancelled")
        while (control.paused.get()) {
            delay(200)
            if (control.cancelled.get()) throw CancellationException("Translation cancelled")
        }
        projects.updateState(projectId, "RUNNING")
    }

    private suspend fun selectTargets(project: TranslationProjectV2Entity): List<LogicalChapterEntity> {
        val all = books.getChapters(project.bookId)
        val remaining = all.filter { books.getEditionChapter(project.targetEditionId, it.id) == null }
        return when (TranslationMode.valueOf(project.translationMode)) {
            TranslationMode.FULL_BOOK -> remaining
            TranslationMode.CHAPTER_RANGE -> remaining.filter {
                it.chapterIndex in (project.rangeStart ?: 1)..(project.rangeEnd ?: Int.MAX_VALUE)
            }
            TranslationMode.SEAMLESS -> {
                val progress = database.readerProgressDao().get(project.bookId)
                val currentIndex = all.firstOrNull { it.id == progress?.logicalChapterId }?.chapterIndex ?: 1
                val completed = all.size - remaining.size
                val desiredEnd = if (completed == 0) 5 else currentIndex + project.seamlessAheadChapters.coerceAtLeast(1)
                remaining.filter { it.chapterIndex <= desiredEnd }
            }
        }
    }

    private suspend fun loadSourceChapter(
        project: TranslationProjectV2Entity,
        chapter: LogicalChapterEntity,
        shortId: Int
    ): ProtocolChapter {
        val logical = books.getLogicalSegments(chapter.id)
        val editionChapter = books.getEditionChapter(project.sourceEditionId, chapter.id) ?: error("Missing source chapter ${chapter.chapterIndex}")
        val editionSegments = books.getEditionSegments(editionChapter.id).associateBy { it.id }
        val revisions = books.getActiveRevisions(editionSegments.keys.toList())
            .groupBy { it.editionSegmentId }
            .mapValues { (_, rows) -> rows.maxWithOrNull(compareBy<SegmentRevisionEntity> { it.priority }.thenBy { it.createdAt }) }
        val mappings = books.getMappings(logical.map { it.id }).groupBy { it.logicalSegmentId }
        val segments = logical.mapIndexed { index, item ->
            val text = mappings[item.id].orEmpty().sortedBy { it.mappingOrder }
                .mapNotNull { mapping -> editionSegments[mapping.editionSegmentId]?.let { revisions[it.id]?.text ?: it.baseText } }
                .joinToString("\n\n")
            ProtocolSegment(index + 1, item.id, text)
        }
        return ProtocolChapter(shortId, chapter.id, chapter.chapterIndex, chapter.canonicalTitle, segments)
    }

    private suspend fun translateBatch(
        project: TranslationProjectV2Entity,
        provider: ApiProviderEntity,
        runId: Long,
        batchId: Long,
        context: ContextPackage,
        sources: List<ProtocolChapter>
    ) {
        val cacheHint = prepareCache(project, provider, context)
        val firstIndex = sources.first().chapterIndex
        val lastIndex = sources.last().chapterIndex
        SystemLogger.info(
            "PROMPT",
            "🧩 正在组装提示词: 匹配到 ${context.matchedLexicon.size} 个专有术语，注入上下文历史 (包含章节 $firstIndex-$lastIndex)",
            projectId = project.id,
            chapterIndex = firstIndex
        )
        SystemLogger.info(
            "LLM_API",
            "📡 发起模型调用 -> ${provider.name} (${provider.selectedModel})，章节: $firstIndex-$lastIndex，Temp: ${provider.temperature}",
            projectId = project.id,
            chapterIndex = firstIndex
        )
        val result = gateway.executeCompletion(
            LlmRequest(
                provider = provider,
                systemPrompt = TranslationProtocol.systemPrompt(project.sourceLanguage, project.targetLanguage),
                userPrompt = TranslationProtocol.userPrompt(context, sources),
                temperature = provider.temperature,
                maxTokens = outputLimit(provider.maxContextTokens),
                operation = "BOOK_TRANSLATION",
                promptCacheHint = cacheHint
            )
        )
        recordUsage(runId, batchId, provider, result)
        check(result.isSuccess) {
            SystemLogger.error("LLM_API", "❌ 模型调用失败: ${result.errorMessage}", projectId = project.id, chapterIndex = firstIndex)
            result.errorMessage ?: "Translation request failed"
        }
        val cost = TokenCalculator.calculateCost(result.promptTokens, result.completionTokens, provider.inputPricePerMillion, provider.outputPricePerMillion)
        SystemLogger.info(
            "LLM_API",
            "📥 收到模型响应 (耗时 ${result.durationMs}ms): 消耗输入 ${result.promptTokens} Tokens, 输出 ${result.completionTokens} Tokens, 预估费用 $${String.format(java.util.Locale.US, "%.5f", cost)}",
            projectId = project.id,
            chapterIndex = firstIndex
        )
        val parsed = TranslationProtocol.parse(result.text)
        val mandatoryTerms = context.matchedLexicon.map { it.sourceTerm to it.targetTerm }
        var failed = 0
        sources.forEach { source ->
            var translated = parsed.chapters.firstOrNull { it.shortId == source.shortId }
            var qa = DeterministicTranslationQa.validate(source, translated, mandatoryTerms)
            if (!qa.accepted) {
                SystemLogger.warn("QA_CHECK", "⚠️ 章节 #${source.chapterIndex} 首次质检不合格 (${qa.problems.joinToString()})，启动自动修复...", projectId = project.id, chapterIndex = source.chapterIndex)
                translated = repairChapter(project, provider, runId, batchId, context, source, translated)
                qa = DeterministicTranslationQa.validate(source, translated, mandatoryTerms)
            }
            if (qa.accepted && translated != null) {
                commitChapter(project, source, translated)
                SystemLogger.info("STORAGE", "💾 章节 #${source.chapterIndex} 《${source.title}》 译文已持久化落库", projectId = project.id, chapterIndex = source.chapterIndex)
            } else {
                failed++
                SystemLogger.error("QA_CHECK", "❌ 章节 #${source.chapterIndex} 质检失败且无法自动修复", projectId = project.id, chapterIndex = source.chapterIndex)
            }
        }
        applyMetadata(project, sources, parsed.metaJson)
        val batch = (tasks.getBatch(batchId) ?: error("Batch not found")).copy(
            state = if (failed == 0) "COMPLETED" else "PARTIAL",
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens,
            cost = cost,
            errorMessage = if (failed > 0) "$failed chapter(s) failed deterministic QA" else null
        )
        tasks.updateBatch(batch)
        updateRunCounters(runId, sources.size - failed, failed)
        SystemLogger.info(
            "BATCH",
            "✨ 批次处理完毕: 章节 $firstIndex-$lastIndex, 成功 ${sources.size - failed} 章, 失败 $failed 章",
            projectId = project.id,
            chapterIndex = firstIndex
        )
    }

    private suspend fun repairChapter(
        project: TranslationProjectV2Entity,
        provider: ApiProviderEntity,
        runId: Long,
        batchId: Long,
        context: ContextPackage,
        source: ProtocolChapter,
        partial: ParsedTranslationChapter?
    ): ParsedTranslationChapter? {
        val missing = source.segments.filter { partial?.segments?.containsKey(it.shortId) != true }
        val retrySource = if (missing.isNotEmpty() && partial != null) source.copy(segments = missing) else source
        val retry = gateway.executeCompletion(
            LlmRequest(
                provider,
                TranslationProtocol.systemPrompt(project.sourceLanguage, project.targetLanguage),
                TranslationProtocol.userPrompt(context, listOf(retrySource)),
                provider.temperature,
                outputLimit(provider.maxContextTokens),
                "CHAPTER_REPAIR",
                prepareCache(project, provider, context)
            )
        )
        recordUsage(runId, batchId, provider, retry)
        if (!retry.isSuccess) return partial
        val repaired = TranslationProtocol.parse(retry.text).chapters.firstOrNull() ?: return partial
        return if (partial == null) repaired else partial.copy(segments = partial.segments + repaired.segments)
    }

    private suspend fun translateOversizedChapter(
        project: TranslationProjectV2Entity,
        provider: ApiProviderEntity,
        runId: Long,
        batchId: Long,
        context: ContextPackage,
        source: ProtocolChapter
    ) {
        val safeSourceBudget = (provider.maxContextTokens * 0.28).toLong().coerceAtLeast(800)
        val groups = mutableListOf<List<ProtocolSegment>>()
        var current = mutableListOf<ProtocolSegment>()
        var tokens = 0L
        source.segments.forEach { segment ->
            val pieces = splitSegmentForBudget(segment, safeSourceBudget)
            if (pieces.size > 1) {
                if (current.isNotEmpty()) groups += current
                current = mutableListOf()
                tokens = 0
                pieces.forEach { groups += listOf(it) }
                return@forEach
            }
            val piece = pieces.single()
            val size = TokenBudgetPlanner.estimate(piece.text)
            if (current.isNotEmpty() && tokens + size > safeSourceBudget) {
                groups += current
                current = mutableListOf()
                tokens = 0
            }
            current += piece
            tokens += size
        }
        if (current.isNotEmpty()) groups += current
        val translated = linkedMapOf<Int, String>()
        var prompt = 0L
        var completion = 0L
        groups.forEach { group ->
            val chunkSource = source.copy(segments = group)
            val result = gateway.executeCompletion(
                LlmRequest(provider, TranslationProtocol.systemPrompt(project.sourceLanguage, project.targetLanguage), TranslationProtocol.userPrompt(context, listOf(chunkSource)), provider.temperature, outputLimit(provider.maxContextTokens), "OVERSIZED_CHAPTER_CHUNK", prepareCache(project, provider, context))
            )
            recordUsage(runId, batchId, provider, result)
            check(result.isSuccess) { result.errorMessage ?: "Oversized chapter chunk failed" }
            val parsed = TranslationProtocol.parse(result.text).chapters.firstOrNull()
            val qa = DeterministicTranslationQa.validate(chunkSource, parsed, context.matchedLexicon.map { it.sourceTerm to it.targetTerm })
            check(qa.accepted && parsed != null) { qa.problems.joinToString() }
            parsed.segments.forEach { (id, text) ->
                translated[id] = translated[id].orEmpty() + text
            }
            prompt += result.promptTokens
            completion += result.completionTokens
        }
        commitChapter(project, source, ParsedTranslationChapter(source.shortId, translated))
        tasks.updateBatch(
            (tasks.getBatch(batchId) ?: error("Batch not found")).copy(
                state = "COMPLETED", promptTokens = prompt, completionTokens = completion,
                cost = TokenCalculator.calculateCost(prompt, completion, provider.inputPricePerMillion, provider.outputPricePerMillion)
            )
        )
        database.memoryDao().upsertChapterMemory(
            ChapterMemoryEntity(
                translationProjectId = project.id,
                logicalChapterId = source.logicalChapterId,
                chapterIndex = source.chapterIndex,
                summary = "",
                repairState = MemoryRepairState.PENDING_REPAIR.name
            )
        )
        updateRunCounters(runId, 1, 0)
    }

    private suspend fun commitChapter(project: TranslationProjectV2Entity, source: ProtocolChapter, translated: ParsedTranslationChapter) {
        val joined = source.segments.joinToString("\n\n") { translated.segments[it.shortId].orEmpty() }
        val fileName = files.saveEditionChapter(project.bookId, project.targetEditionId, source.chapterIndex, source.title, joined)
        database.withTransaction {
            val existingTargetChapter = books.getEditionChapter(project.targetEditionId, source.logicalChapterId)
            val targetChapter = if (existingTargetChapter == null) {
                val id = books.insertEditionChapter(
                    EditionChapterEntity(
                        editionId = project.targetEditionId,
                        logicalChapterId = source.logicalChapterId,
                        title = source.title,
                        contentFileName = fileName,
                        wordCount = joined.length,
                        isAvailable = true
                    )
                )
                books.getEditionChapter(project.targetEditionId, source.logicalChapterId)?.copy(id = id)
                    ?: error("Inserted target chapter could not be reloaded")
            } else {
                books.updateEditionChapter(
                    existingTargetChapter.copy(
                        contentFileName = fileName,
                        wordCount = joined.length,
                        isAvailable = true,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                existingTargetChapter
            }
            val existing = books.getEditionSegments(targetChapter.id)
            val existingByLogical = if (existing.isEmpty()) emptyMap() else {
                val ids = source.segments.map { it.logicalSegmentId }
                val mappings = books.getMappings(ids).filter { mapping -> existing.any { it.id == mapping.editionSegmentId } }
                mappings.associate { it.logicalSegmentId to it.editionSegmentId }
            }
            source.segments.forEach { segment ->
                val text = translated.segments[segment.shortId] ?: return@forEach
                val existingId = existingByLogical[segment.logicalSegmentId]
                if (existingId != null) {
                    books.insertRevision(SegmentRevisionEntity(editionSegmentId = existingId, revisionType = RevisionType.AI_TRANSLATION.name, text = text))
                } else {
                    val segmentId = books.insertEditionSegments(
                        listOf(
                            EditionSegmentEntity(
                                editionChapterId = targetChapter.id,
                                segmentIndex = segment.shortId - 1,
                                baseText = text,
                                sourceHash = "translated:${segment.logicalSegmentId}"
                            )
                        )
                    ).single()
                    books.insertMappings(listOf(EditionSegmentMappingEntity(segment.logicalSegmentId, segmentId)))
                    books.insertRevision(SegmentRevisionEntity(editionSegmentId = segmentId, revisionType = RevisionType.AI_TRANSLATION.name, text = text))
                }
            }
        }
    }

    private suspend fun applyMetadata(project: TranslationProjectV2Entity, sources: List<ProtocolChapter>, metaJson: String?) {
        if (metaJson.isNullOrBlank()) {
            sources.forEach {
                database.memoryDao().upsertChapterMemory(ChapterMemoryEntity(translationProjectId = project.id, logicalChapterId = it.logicalChapterId, chapterIndex = it.chapterIndex, summary = "", repairState = MemoryRepairState.PENDING_REPAIR.name))
            }
            return
        }
        val parsed = runCatching { MetadataParser.parse(metaJson) }.getOrNull()
        if (parsed == null) {
            sources.forEach {
                database.memoryDao().upsertChapterMemory(ChapterMemoryEntity(translationProjectId = project.id, logicalChapterId = it.logicalChapterId, chapterIndex = it.chapterIndex, summary = "", repairState = MemoryRepairState.PENDING_REPAIR.name))
            }
            return
        }
        sources.forEach { source ->
            val memory = parsed.chapterMemory.firstOrNull { it.chapterIndex == source.chapterIndex || it.chapterId == source.shortId }
            database.memoryDao().upsertChapterMemory(
                ChapterMemoryEntity(
                    translationProjectId = project.id, logicalChapterId = source.logicalChapterId, chapterIndex = source.chapterIndex,
                    summary = memory?.summary.orEmpty(), entities = memory?.entities.orEmpty(), stateChanges = memory?.stateChanges.orEmpty(),
                    newFacts = memory?.newFacts.orEmpty(), unresolvedThreads = memory?.unresolvedThreads.orEmpty(),
                    repairState = if (memory == null) MemoryRepairState.PENDING_REPAIR.name else MemoryRepairState.READY.name
                )
            )
        }
        parsed.storyDelta.forEach { delta ->
            if (delta.key.isNotBlank() && delta.value.isNotBlank()) {
                val existing = database.memoryDao().getStoryFact(project.id, delta.key)
                database.memoryDao().upsertStoryMemory(
                    StoryMemoryEntity(
                        id = existing?.id ?: 0,
                        translationProjectId = project.id,
                        factKey = delta.key,
                        factValue = delta.value,
                        entities = delta.entities,
                        sourceChapterIndex = existing?.sourceChapterIndex ?: sources.first().chapterIndex,
                        lastUpdatedChapterIndex = sources.last().chapterIndex
                    )
                )
                SystemLogger.info("MEMORY", "🧠 随进度更新故事记忆: [${delta.key}] = ${delta.value}", projectId = project.id, chapterIndex = sources.first().chapterIndex)
            }
        }
        parsed.lexiconCandidates.forEach { candidate ->
            if (candidate.source.isNotBlank() && candidate.target.isNotBlank()) {
                database.lexiconV2Dao().upsert(
                    LexiconEntryEntity(
                        translationProjectId = project.id,
                        sourceTerm = candidate.source,
                        targetTerm = candidate.target,
                        notes = candidate.notes,
                        source = LexiconSource.AI.name,
                        reviewStatus = ReviewStatus.CONFIRMED.name
                    )
                )
                SystemLogger.info("GLOSSARY", "✨ 随翻译进度自动入库术语: '${candidate.source}' -> '${candidate.target}' (${candidate.notes})", projectId = project.id, chapterIndex = sources.first().chapterIndex)
            }
        }
    }

    private suspend fun recordUsage(runId: Long, batchId: Long, provider: ApiProviderEntity, result: LlmResult) {
        val cost = TokenCalculator.calculateCost(result.promptTokens, result.completionTokens, provider.inputPricePerMillion, provider.outputPricePerMillion)
        tasks.insertRequestLog(
            PlatformRequestLogEntity(
                runId = runId, batchId = batchId, operation = result.operation,
                attemptCount = result.attempts.size.coerceAtLeast(1), promptTokens = result.promptTokens,
                completionTokens = result.completionTokens, estimatedCost = cost, durationMs = result.durationMs,
                finishReason = result.finishReason, errorCategory = result.errorCategory?.name,
                errorMessage = result.errorMessage, isSuccess = result.isSuccess
            )
        )
        tasks.getRun(runId)?.let { run ->
            tasks.updateRun(run.copy(promptTokens = run.promptTokens + result.promptTokens, completionTokens = run.completionTokens + result.completionTokens, totalCost = run.totalCost + cost, updatedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun updateRunCounters(runId: Long, completed: Int, failed: Int) {
        tasks.getRun(runId)?.let { run -> tasks.updateRun(run.copy(completedChapters = run.completedChapters + completed, failedChapters = run.failedChapters + failed, updatedAt = System.currentTimeMillis())) }
    }

    private suspend fun prepareCache(
        project: TranslationProjectV2Entity,
        provider: ApiProviderEntity,
        context: ContextPackage
    ): PromptCacheHint? {
        if (!promptCacheCapability.supports(provider)) return null
        val stableTokens = TokenBudgetPlanner.estimate(context.stablePrefix)
        if (!PromptCachePolicy.shouldCreate(stableTokens, expectedReuseCount = 2)) return null
        val handle = promptCacheCapability.prepare(provider, context.fingerprint, context.stablePrefix, 2) ?: return null
        database.providerCacheDao().upsert(
            ProviderCacheRecordEntity(
                translationProjectId = project.id,
                providerName = provider.name,
                modelName = provider.selectedModel,
                fingerprint = handle.fingerprint,
                remoteCacheId = handle.remoteCacheId,
                cachedTokenCount = handle.cachedTokenCount,
                expiresAt = handle.expiresAt
            )
        )
        return PromptCacheHint(handle.fingerprint, handle.remoteCacheId, stableTokens, handle.expiresAt)
    }

    private fun splitSegmentForBudget(segment: ProtocolSegment, budget: Long): List<ProtocolSegment> {
        if (TokenBudgetPlanner.estimate(segment.text) <= budget) return listOf(segment)
        val pieces = mutableListOf<ProtocolSegment>()
        var remaining = segment.text
        while (remaining.isNotEmpty()) {
            var low = 1
            var high = remaining.length
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (TokenBudgetPlanner.estimate(remaining.substring(0, mid)) <= budget) low = mid else high = mid - 1
            }
            var cut = low.coerceAtLeast(1)
            if (cut < remaining.length) {
                val preferredStart = (cut * 0.65).toInt()
                val preferred = remaining.substring(preferredStart, cut)
                    .indexOfLast { it == '\n' || it == '。' || it == '！' || it == '？' || it == '.' || it == '!' || it == '?' || it.isWhitespace() }
                if (preferred >= 0) cut = preferredStart + preferred + 1
                val openImage = remaining.lastIndexOf("[IMG:", startIndex = cut - 1)
                val closeImage = remaining.lastIndexOf(']', startIndex = cut - 1)
                if (openImage > closeImage && openImage > 0) cut = openImage
            }
            pieces += segment.copy(text = remaining.substring(0, cut))
            remaining = remaining.substring(cut)
        }
        return pieces
    }

    private fun outputLimit(context: Int): Int = (context * 0.42).toInt().coerceIn(1024, 16_384)

    private data class ProjectControl(val paused: AtomicBoolean = AtomicBoolean(false), val cancelled: AtomicBoolean = AtomicBoolean(false))
}

private data class MetadataChapter(val chapterId: Int?, val chapterIndex: Int?, val summary: String, val entities: String, val stateChanges: String, val newFacts: String, val unresolvedThreads: String)
private data class MetadataStoryDelta(val operation: String, val key: String, val value: String, val entities: String)
private data class MetadataLexicon(val source: String, val target: String, val notes: String)
private data class TranslationMetadata(val chapterMemory: List<MetadataChapter>, val storyDelta: List<MetadataStoryDelta>, val lexiconCandidates: List<MetadataLexicon>)

private object MetadataParser {
    private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun parse(json: String): TranslationMetadata {
        val root = adapter.fromJson(json).orEmpty()
        fun list(key: String) = root[key] as? List<*> ?: emptyList<Any?>()
        fun map(value: Any?) = value as? Map<*, *> ?: emptyMap<Any?, Any?>()
        fun str(row: Map<*, *>, key: String) = when (val value = row[key]) {
            is List<*> -> value.joinToString("|") { it.toString() }
            null -> ""
            else -> value.toString()
        }
        fun int(row: Map<*, *>, key: String) = (row[key] as? Number)?.toInt() ?: row[key]?.toString()?.toIntOrNull()
        val chapter = list("chapterMemory").map { map(it) }.map { row ->
            MetadataChapter(int(row, "chapterId"), int(row, "chapterIndex"), str(row, "summary"), str(row, "entities"), str(row, "stateChanges"), str(row, "newFacts"), str(row, "unresolvedThreads"))
        }
        val story = list("storyMemoryDelta").map { map(it) }.map { row ->
            MetadataStoryDelta(str(row, "operation").ifBlank { "ADD" }, str(row, "key").ifBlank { str(row, "factKey") }, str(row, "value").ifBlank { str(row, "factValue") }, str(row, "entities"))
        }
        val lexicon = list("lexiconCandidate").map { map(it) }.map { row ->
            MetadataLexicon(str(row, "source").ifBlank { str(row, "sourceTerm") }, str(row, "target").ifBlank { str(row, "targetTerm") }, str(row, "notes"))
        }
        return TranslationMetadata(chapter, story, lexicon)
    }
}
