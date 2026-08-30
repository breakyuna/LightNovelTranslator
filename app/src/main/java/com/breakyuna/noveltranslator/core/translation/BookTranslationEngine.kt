package com.breakyuna.noveltranslator.core.translation

import androidx.room.withTransaction
import com.breakyuna.noveltranslator.core.agent.ExtractedTermCandidate
import com.breakyuna.noveltranslator.core.agent.LexiconCandidateAggregator
import com.breakyuna.noveltranslator.core.agent.TermCandidateValidator
import com.breakyuna.noveltranslator.core.agent.TermValidationResult
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Global scheduler: different books may run concurrently; every book is protected by one mutex.
 * A project processes draft batches strictly in order and commits each valid chapter independently;
 * an optional post-draft review pass runs only after the selected draft scope is complete.
 */
class BookTranslationScheduler(
    private val engine: BookTranslationEngine,
    maxConcurrentBooks: Int = 3
) {
    private val bookLocks = ConcurrentHashMap<Long, Mutex>()
    private val globalSlots = Semaphore(maxConcurrentBooks.coerceAtLeast(1))

    suspend fun run(projectId: Long) {
        engine.markScheduled(projectId)
        try {
            val bookId = engine.projectBookId(projectId) ?: return
            globalSlots.withPermit {
                bookLocks.getOrPut(bookId) { Mutex() }.withLock { engine.runStrictlySerial(projectId) }
            }
        } finally {
            engine.unmarkScheduled(projectId)
            // A queued job can be cancelled while waiting for the global slot or the per-book
            // mutex, before runStrictlySerial has a chance to enter its own cleanup block.
            // Drop that orphaned control so a later explicit run is not rejected by a stale
            // pre-start cancellation signal.
            engine.clearControlIfIdle(projectId)
        }
    }

    suspend fun pause(projectId: Long) = engine.pause(projectId)
    suspend fun resume(projectId: Long): Boolean = engine.resume(projectId)
    suspend fun cancel(projectId: Long) = engine.cancel(projectId)
}

class BookTranslationEngine(
    private val database: AppDatabase,
    private val files: BookFileManager,
    private val gateway: LlmGateway,
    private val contextEngine: ContextEngine,
    private val providerResolver: suspend (Long) -> ApiProviderEntity?,
    private val promptCacheCapability: ProviderPromptCacheCapability = NoPromptCacheCapability,
    private val debugEnabled: () -> Boolean = { false }
) {
    private data class ChapterRepairOutcome(
        val chapter: ParsedTranslationChapter?,
        val promptTokens: Long,
        val completionTokens: Long,
        val metaJson: String? = null
    )

    private data class DraftReviewOutcome(
        val chapter: ParsedTranslationChapter?,
        val promptTokens: Long,
        val completionTokens: Long,
        val reason: String? = null
    )

    private companion object {
        // Review batches share the run with translation batches while remaining easy to filter in
        // the task center. Negative indexes are reserved for isolated translation retries.
        const val POST_DRAFT_REVIEW_BATCH_OFFSET = 1_000_000
        // A chapter with hundreds of XML Segment tags is structurally difficult even when its
        // text fits the provider context window. Keep the normal one-request path for ordinary
        // chapters, but partition dense chapters before the model starts dropping IDs.
        const val MAX_PROTOCOL_SEGMENTS_PER_REQUEST = 48
        const val MIN_REQUEST_OUTPUT_TOKENS = 1_024
        val TERMINAL_PROJECT_STATES = setOf("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED")
    }

    private val books = database.bookDao()
    private val projects = database.translationProjectV2Dao()
    private val tasks = database.platformTaskDao()
    private val promptProfiles = database.promptProfileDao()
    private val controls = ConcurrentHashMap<Long, ProjectControl>()
    /** Projects that have claimed the per-book execution slot, including startup I/O. */
    private val claimedProjects = ConcurrentHashMap.newKeySet<Long>()
    /** Counts queued scheduler workers so stale cleanup cannot remove a newer control signal. */
    private val scheduledProjects = ConcurrentHashMap<Long, AtomicInteger>()
    private val activeRuns = ConcurrentHashMap<Long, Long>()
    private val lexiconCandidateAggregator = LexiconCandidateAggregator(database)

    suspend fun projectBookId(projectId: Long): Long? = projects.get(projectId)?.bookId

    internal fun markScheduled(projectId: Long) {
        scheduledProjects.compute(projectId) { _, count ->
            (count ?: AtomicInteger()).also { it.incrementAndGet() }
        }
    }

    internal fun unmarkScheduled(projectId: Long) {
        scheduledProjects.computeIfPresent(projectId) { _, count ->
            if (count.decrementAndGet() <= 0) null else count
        }
    }

    internal fun clearControlIfIdle(projectId: Long) {
        if (projectId !in claimedProjects && activeRuns[projectId] == null &&
            (scheduledProjects[projectId]?.get() ?: 0) <= 0
        ) {
            controls.remove(projectId)
        }
    }

    suspend fun pause(projectId: Long) {
        val project = projects.get(projectId) ?: return
        if (project.state in TERMINAL_PROJECT_STATES) return
        controls.getOrPut(projectId) { ProjectControl() }.requestPause()
        projects.updateState(projectId, "PAUSED")
        activeRuns[projectId]?.let { runId -> tasks.getRun(runId)?.let { tasks.updateRun(it.copy(state = "PAUSED", updatedAt = System.currentTimeMillis())) } }
    }

    suspend fun resume(projectId: Long): Boolean {
        val project = projects.get(projectId) ?: return false
        if (project.state !in setOf("RUNNING", "PAUSED", "IDLE", "INTERRUPTED")) return false
        val control = controls.getOrPut(projectId) { ProjectControl() }
        if (!control.resume()) return false
        projects.updateState(projectId, "RUNNING")
        activeRuns[projectId]?.let { runId -> tasks.getRun(runId)?.let { tasks.updateRun(it.copy(state = "RUNNING", updatedAt = System.currentTimeMillis())) } }
        return true
    }

    suspend fun cancel(projectId: Long) {
        val project = projects.get(projectId) ?: return
        if (project.state in TERMINAL_PROJECT_STATES) return
        controls.getOrPut(projectId) { ProjectControl() }.cancel()
        projects.updateState(projectId, "CANCELLED")
        activeRuns[projectId]?.let { runId ->
            tasks.getRunningBatches(runId).forEach { batch ->
                tasks.updateBatch(batch.copy(state = "CANCELLED", errorMessage = "cancelled by user"))
            }
            tasks.getRun(runId)?.let { tasks.updateRun(it.copy(state = "CANCELLED", updatedAt = System.currentTimeMillis())) }
        }
    }

    suspend fun runStrictlySerial(projectId: Long) {
        // Acquire the gate before any startup I/O. A pause/cancel issued while the project,
        // provider, or chapter list is loading must not be erased by a late reset.
        // A control signal may be created by pause/cancel while the scheduler is still waiting
        // for a book slot. Reusing it preserves that decision instead of starting a paid run
        // after a cancellation that arrived before startup I/O.
        claimedProjects.add(projectId)
        val control = controls.computeIfAbsent(projectId) { ProjectControl() }
        try {
            val project = projects.get(projectId) ?: return
            if (control.cancelled.get()) throw CancellationException("Translation cancelled")
            val targets = selectTargets(project)
            // A completed scope should not require provider credentials just to discover that
            // there is no work left. Persist the terminal state so the task center does not keep
            // showing an already-finished project as idle, while still honoring cancellation.
            val reviewOnlyTargets = if (targets.isEmpty() && project.highQualityReview) {
                selectReviewTargets(project)
            } else {
                emptyList()
            }
            if (targets.isEmpty() && reviewOnlyTargets.isEmpty()) {
                if (!control.cancelled.get()) {
                    if (projects.completeIfNotActive(project.id) > 0) {
                        SystemLogger.info("TRANSLATION", "项目 #${project.id} 所有章节已完成，暂无新的初稿或待审校章节。", projectId = project.id)
                    }
                }
                return
            }
            // Capture one immutable prompt profile for the whole run.  Materialize the default
            // for projects created before Prompt Profiles existed, so their first run also has a
            // durable v1 that later edits can advance to v2.
            val promptProfile = promptProfiles.getLatest(project.id)?.asDraft() ?: run {
                val default = TranslationProtocol.defaultPromptProfile()
                val persisted = PromptProfileEntity(
                    translationProjectId = project.id,
                    version = 1,
                    translationSystemPrompt = default.translationSystemPrompt,
                    translationUserPromptTemplate = default.translationUserPromptTemplate,
                    polishSystemPrompt = default.polishSystemPrompt,
                    polishUserPromptTemplate = default.polishUserPromptTemplate
                )
                promptProfiles.insert(persisted)
                persisted.asDraft()
            }
            // Provider credentials are encrypted at rest. Always resolve through the repository
            // decryption boundary instead of reading ApiProviderDao directly.
            val provider = project.providerId?.let { providerResolver(it) }?.let { resolved ->
                resolved.copy(selectedModel = project.modelName.ifBlank { resolved.selectedModel })
            }
                ?: error("Translation project has no available provider")
            // Provider resolution may suspend while the user pauses or cancels the task. Check the
            // gate before creating a durable run so a late provider result cannot resurrect a
            // cancelled task or make a paused task look newly started.
            awaitBoundary(project.id, control, publishRunning = false)
            // If the draft already exists, enabling the option should still allow a one-time
            // post-draft review. Only chapters without an active AI_POLISH revision are selected,
            // so pressing run again does not pay for the same review repeatedly.
            val runId = tasks.insertRun(
                PlatformTranslationRunEntity(
                    translationProjectId = project.id,
                    bookId = project.bookId,
                    providerId = provider.id,
                    providerName = provider.name,
                    modelName = provider.selectedModel,
                    promptProfileVersion = promptProfile.version,
                    state = "RUNNING",
                    currency = provider.currency
                )
            )
            activeRuns[projectId] = runId
            // Publish RUNNING through the same pause/cancel gate used before each paid request.
            // A pause or cancellation may arrive between provider resolution and run insertion;
            // bypassing this boundary would overwrite that durable decision with RUNNING.
            awaitBoundary(project.id, control)
            SystemLogger.info(
                "TRANSLATION",
                "🚀 启动翻译任务: Project#$projectId (Book#${project.bookId})，使用模型: ${provider.name}/${provider.selectedModel}，待翻译 ${targets.size} 章，待二次审校 ${reviewOnlyTargets.size} 章",
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
                // Context matching is source-dependent, so choose the batch and build context in
                // the same scope. This prevents future chapters from leaking glossary/story facts
                // into a smaller batch after token planning shrinks the request.
                var actual = sourceChapters.size
                var selectedContext: ContextPackage? = null
                var selectedBudget: TokenBudgetPlan? = null
                while (true) {
                    val considered = sourceChapters.take(actual)
                    val candidateContext = prepareContext(project, considered)
                    // Budget the final rendered request, including the editable Prompt Profile,
                    // protocol tags and the non-removable system rules. The old estimate only
                    // counted ContextPackage fields and could silently under-budget large custom
                    // templates or dense Segment lists.
                    val sourcePayloadTokens = sourceTokens.take(actual).sum()
                    val fixedTokens = exactFixedContextTokens(
                        project = project,
                        promptProfile = promptProfile,
                        context = candidateContext,
                        sources = considered,
                        sourcePayloadTokens = sourcePayloadTokens
                    )
                    val candidateBudget = TokenBudgetPlanner.plan(
                        maxContextTokens = provider.maxContextTokens,
                        userMaxBatchSize = project.maxBatchChapters,
                        sourceTokenEstimates = sourceTokens.take(actual),
                        fixedContextTokens = fixedTokens,
                        fixedOutputTokens = protocolOutputOverhead(considered)
                    )
                    selectedContext = candidateContext
                    selectedBudget = candidateBudget
                    // translateOversizedChapter operates on exactly one chapter. Shrink a mixed
                    // batch before leaving the planner loop so a dense later chapter cannot be
                    // skipped when the caller advances the cursor by `actual`.
                    if (considered.size > 1 && considered.any { it.segments.size > MAX_PROTOCOL_SEGMENTS_PER_REQUEST }) {
                        actual = 1
                        continue
                    }
                    if (candidateBudget.requiresSingleChapterChunking) {
                        if (actual == 1) break
                        actual = 1
                        continue
                    }
                    val allowed = candidateBudget.actualBatchSize.coerceAtLeast(1).coerceAtMost(actual)
                    if (allowed < actual) {
                        actual = allowed
                        continue
                    }
                    break
                }
                val context = selectedContext ?: error("Context planning produced no context")
                val budget = selectedBudget ?: error("Token planning produced no budget")
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
                if (budget.requiresSingleChapterChunking || batchSources.any { it.segments.size > MAX_PROTOCOL_SEGMENTS_PER_REQUEST }) {
                    translateOversizedChapter(
                        project,
                        provider,
                        runId,
                        batchId,
                        context,
                        batchSources.first(),
                        promptProfile
                    )
                } else {
                    translateBatch(project, provider, runId, batchId, context, batchSources, promptProfile = promptProfile)
                }
                cursor += actual
                batchIndex++
            }
            val draftStageComplete = if (targets.isEmpty()) {
                reviewOnlyTargets.isNotEmpty()
            } else {
                targets.all { hasCompleteDraft(project, it) }
            }
            if (project.highQualityReview && draftStageComplete) {
                val reviewTargets = if (targets.isEmpty()) reviewOnlyTargets else selectReviewTargets(project)
                if (reviewTargets.isNotEmpty()) {
                    runPostDraftReview(project, provider, runId, reviewTargets, control, promptProfile)
                }
            } else if (project.highQualityReview && targets.isNotEmpty()) {
                SystemLogger.warn(
                    "AI_POLISH",
                    "初稿仍有失败章节，暂不启动整本二次审校；完成初稿后可再次运行。",
                    projectId = project.id
                )
            }
            awaitCommitBoundary(project.id)
            val target = books.getEdition(project.targetEditionId)
            val allChapters = books.getChapters(project.bookId)
            val editionComplete = allChapters.all { hasCompleteDraft(project, it) }
            awaitCommitBoundary(project.id)
            target?.let { books.updateEdition(it.copy(isComplete = editionComplete, updatedAt = System.currentTimeMillis())) }
            val run = tasks.getRun(runId)
            val finalState = if ((run?.failedChapters ?: 0) > 0) "COMPLETED_WITH_ERRORS" else "COMPLETED"
            awaitCommitBoundary(project.id)
            projects.updateState(project.id, finalState)
            awaitCommitBoundary(project.id)
            run?.let { tasks.updateRun(it.copy(state = finalState, updatedAt = System.currentTimeMillis())) }
            SystemLogger.info(
                "TRANSLATION",
                "✅ 翻译任务执行完毕！最终状态: $finalState，已成功翻译 ${run?.completedChapters ?: 0} 章，失败 ${run?.failedChapters ?: 0} 章",
                projectId = project.id
            )
            } catch (cancelled: CancellationException) {
                // The worker may already be cancelled when this handler runs. Keep the durable
                // cancellation marker outside the cancelled Job so a Run cannot remain RUNNING
                // merely because its coroutine was interrupted during a provider call.
                withContext(NonCancellable) {
                    runCatching {
                        projects.updateState(project.id, "CANCELLED")
                        tasks.getRunningBatches(runId).forEach { batch ->
                            tasks.updateBatch(batch.copy(state = "CANCELLED", errorMessage = "cancelled by user"))
                        }
                        tasks.getRun(runId)?.let { tasks.updateRun(it.copy(state = "CANCELLED", updatedAt = System.currentTimeMillis())) }
                    }.onFailure { cleanupError ->
                        SystemLogger.error(
                            "TRANSLATION",
                            "取消清理未能完整落盘: ${cleanupError.localizedMessage ?: cleanupError.javaClass.simpleName}",
                            projectId = project.id
                        )
                    }
                    SystemLogger.warn("TRANSLATION", "🛑 翻译任务已被用户取消", projectId = project.id)
                }
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
                        isSuccess = false,
                        status = RequestLogStatus.FAILURE.name
                    )
                )
                SystemLogger.error("TRANSLATION", "❌ 翻译任务异常终止: ${error.message}", details = failureReport, projectId = project.id)
                throw error
            } finally {
                activeRuns.remove(projectId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Startup failures happen before a durable run exists (for example a deleted provider
            // or a missing source Edition). Keep the project state truthful so the task center does
            // not leave an un-runnable project looking idle after showing the error.
            runCatching {
                projects.get(projectId)?.takeUnless { it.state in TERMINAL_PROJECT_STATES }
                    ?.let { projects.updateState(projectId, "FAILED") }
            }
            throw error
        } finally {
            // The scheduler marks queued workers before they wait for the global/book mutex. If a
            // second worker is already queued, keep this control object so a cancellation issued
            // during the hand-off is observed by that worker instead of being replaced by a fresh
            // non-cancelled signal.
            if ((scheduledProjects[projectId]?.get() ?: 0) <= 1) {
                controls.remove(projectId, control)
            }
            claimedProjects.remove(projectId)
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

    private suspend fun awaitBoundary(
        projectId: Long,
        control: ProjectControl,
        publishRunning: Boolean = true
    ) {
        while (true) {
            if (control.cancelled.get()) throw CancellationException("Translation cancelled")
            if (control.paused.get()) {
                projects.updateState(projectId, "PAUSED")
                delay(200)
                continue
            }
            if (!publishRunning) return
            // Pause can race with the state write above. Re-check after publishing RUNNING so a
            // request cannot observe a running project while its gate is already paused.
            projects.updateState(projectId, "RUNNING")
            if (!control.paused.get() && !control.cancelled.get()) return
        }
    }

    /** Checks the project gate after a provider call and immediately before any durable write. */
    private suspend fun awaitCommitBoundary(projectId: Long) {
        val control = controls[projectId] ?: throw CancellationException("Translation control is missing")
        awaitBoundary(projectId, control)
    }

    private suspend fun prepareContext(
        project: TranslationProjectV2Entity,
        sources: List<ProtocolChapter>
    ): ContextPackage {
        require(sources.isNotEmpty()) { "Cannot build context for an empty chapter batch" }
        val combinedSource = sources.flatMap { it.segments }.joinToString("\n") { it.originalText }
        val previousTails = loadPreviousContextTails(project, sources.first().chapterIndex)
        return contextEngine.prepare(
            project = project,
            sourceText = combinedSource,
            firstChapterIndex = sources.first().chapterIndex,
            previousChapterOriginalTail = previousTails.first,
            previousChapterTranslationTail = previousTails.second
        )
    }

    private fun exactFixedContextTokens(
        project: TranslationProjectV2Entity,
        promptProfile: PromptProfileDraft,
        context: ContextPackage,
        sources: List<ProtocolChapter>,
        sourcePayloadTokens: Long
    ): Long {
        val system = TranslationProtocol.translationSystemPrompt(
            promptProfile,
            project.sourceLanguage,
            project.targetLanguage,
            project.styleGuide
        )
        val user = TranslationProtocol.translationUserPrompt(promptProfile, context, sources)
        return (TokenBudgetPlanner.estimate(system + user) - sourcePayloadTokens).coerceAtLeast(0)
    }

    /** Counts structural output tokens separately from the text expansion ratio. */
    private fun protocolOutputOverhead(sources: List<ProtocolChapter>): Long {
        val shape = buildString {
            append("<TRANSLATION>\n")
            sources.forEach { chapter ->
                append("<C id=\"").append(chapter.shortId).append("\">\n")
                chapter.segments.forEach { segment ->
                    append("<S id=\"").append(segment.shortId).append("\"></S>\n")
                }
                append("</C>\n")
            }
            append("</TRANSLATION>\n<META>{}</META>")
        }
        return TokenBudgetPlanner.estimate(shape)
    }

    private fun dynamicOutputLimit(provider: ApiProviderEntity, systemPrompt: String, userPrompt: String): Int {
        val promptTokens = TokenBudgetPlanner.estimate(systemPrompt + userPrompt)
        val safety = (provider.maxContextTokens * 0.12).toLong().coerceAtLeast(512)
        val remaining = (provider.maxContextTokens.toLong() - promptTokens - safety)
        // Keep at least one output token for providers that reject zero, but never inflate a
        // small/negative remainder to 1,024: an oversized custom prompt must be rejected by the
        // local preflight below instead of being sent with an impossible max_tokens value.
        return minOf(outputLimit(provider.maxContextTokens).toLong(), remaining.coerceAtLeast(1L))
            .toInt()
    }

    /**
     * Prevents a user-editable prompt profile from reaching a provider when its estimated input
     * already consumes the complete context window. This is intentionally a local, non-billable
     * failure; retrying the same oversized request cannot help and would only waste a call.
     */
    private suspend fun executeCompletionSafely(request: LlmRequest): LlmResult {
        val promptTokens = TokenBudgetPlanner.estimate(request.systemPrompt + request.userPrompt)
        val requestedOutput = request.maxTokens?.toLong()?.coerceAtLeast(0L)
            ?: outputLimit(request.provider.maxContextTokens).toLong()
        val safety = (request.provider.maxContextTokens * 0.12).toLong().coerceAtLeast(512L)
        val outputTooSmall = requestedOutput < MIN_REQUEST_OUTPUT_TOKENS
        if (outputTooSmall || promptTokens + requestedOutput + safety > request.provider.maxContextTokens.toLong()) {
            return LlmResult(
                text = "",
                // No provider request was made, so this preflight failure is not billable and
                // must not inflate the run's usage counters.
                promptTokens = 0,
                completionTokens = 0,
                isSuccess = false,
                errorCategory = LlmErrorCategory.CONTEXT_OVERFLOW,
                errorMessage = if (outputTooSmall) {
                    "提示词剩余输出预算不足 ${MIN_REQUEST_OUTPUT_TOKENS} tokens（当前 ${requestedOutput}）"
                } else {
                    "提示词已超过模型上下文预算（${promptTokens} + ${requestedOutput} + ${safety} safety tokens）"
                },
                usageSource = UsageSource.ESTIMATED,
                operation = request.operation
            )
        }
        return gateway.executeCompletion(request)
    }

    private suspend fun selectTargets(project: TranslationProjectV2Entity): List<LogicalChapterEntity> {
        val all = books.getChapters(project.bookId)
        // An EditionChapter row alone is not a durable draft-complete marker: a crash or an
        // imported partial edition may leave the row without every logical segment. Reuse the
        // same completeness predicate for draft selection and the post-draft gate.
        val remaining = all.filter { !hasCompleteDraft(project, it) }
        return when (TranslationMode.valueOf(project.translationMode)) {
            TranslationMode.FULL_BOOK -> remaining
            TranslationMode.CHAPTER_RANGE -> remaining.filter {
                it.chapterIndex in (project.rangeStart ?: 1)..(project.rangeEnd ?: Int.MAX_VALUE)
            }
            TranslationMode.SEAMLESS -> {
                val progress = database.readerProgressDao().get(project.bookId)
                val currentIndex = all.firstOrNull { it.id == progress?.logicalChapterId }?.chapterIndex ?: 1
                // The buffer is measured relative to the reader's current chapter, including the
                // initial run where no chapter has completed yet. This keeps the configured value
                // effective instead of silently replacing it with a hard-coded five chapters.
                val desiredEnd = currentIndex + project.seamlessAheadChapters.coerceAtLeast(1)
                remaining.filter { it.chapterIndex <= desiredEnd }
            }
        }
    }

    /**
     * Selects already translated chapters that still need the optional second pass. The review
     * stage is intentionally idempotent: an active AI_POLISH revision is the durable marker that
     * a chapter has completed the second pass.
     */
    private suspend fun selectReviewTargets(project: TranslationProjectV2Entity): List<LogicalChapterEntity> {
        val all = books.getChapters(project.bookId)
        val scoped = when (TranslationMode.valueOf(project.translationMode)) {
            TranslationMode.FULL_BOOK -> all
            TranslationMode.CHAPTER_RANGE -> all.filter {
                it.chapterIndex in (project.rangeStart ?: 1)..(project.rangeEnd ?: Int.MAX_VALUE)
            }
            // Seamless translation deliberately reviews the completed ahead window, not chapters
            // outside the user's current reading buffer.
            TranslationMode.SEAMLESS -> {
                val progress = database.readerProgressDao().get(project.bookId)
                val currentIndex = all.firstOrNull { it.id == progress?.logicalChapterId }?.chapterIndex ?: 1
                val desiredEnd = currentIndex + project.seamlessAheadChapters.coerceAtLeast(1)
                all.filter { it.chapterIndex <= desiredEnd }
            }
        }
        return scoped.filter { chapter ->
            hasCompleteDraft(project, chapter) && !chapterHasActivePolish(project, chapter)
        }
    }

    private suspend fun hasCompleteDraft(
        project: TranslationProjectV2Entity,
        chapter: LogicalChapterEntity
    ): Boolean {
        if (books.getEditionChapter(project.targetEditionId, chapter.id) == null) return false
        val source = try {
            loadSourceChapter(project, chapter, shortId = 1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return false
        }
        if (source.segments.isEmpty()) return false
        val translated = loadTranslatedChapter(project, source) ?: return false
        if (!source.segments.all { segment ->
            !translated.segments[segment.shortId].isNullOrBlank()
        }) return false
        val mandatoryTerms = database.lexiconV2Dao().getConfirmed(project.id)
            .filter(LexiconEntryPolicy::isEligibleForTranslation)
        return DeterministicTranslationQa.validate(source, translated, mandatoryTerms).accepted
    }

    private suspend fun chapterHasActivePolish(
        project: TranslationProjectV2Entity,
        chapter: LogicalChapterEntity
    ): Boolean {
        val source = try {
            loadSourceChapter(project, chapter, shortId = 1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A damaged or partially imported source cannot be reviewed safely. Treat it as
            // not reviewable so the rest of the task can continue and surface the source error
            // when the chapter is explicitly retried.
            return false
        }
        if (source.segments.isEmpty()) return false
        val targetChapter = books.getEditionChapter(project.targetEditionId, chapter.id) ?: return false
        val targetSegments = books.getEditionSegments(targetChapter.id)
        if (targetSegments.isEmpty()) return false
        val targetById = targetSegments.associateBy { it.id }
        val targetIds = targetSegments.mapTo(mutableSetOf()) { it.id }
        val mappings = books.getMappings(source.segments.map { it.logicalSegmentId })
            .filter { it.editionSegmentId in targetIds }
            .groupBy { it.logicalSegmentId }
        val revisionsBySegment = books.getActiveRevisions(targetIds.toList()).groupBy { it.editionSegmentId }
        return source.segments.all { segment ->
            mappings[segment.logicalSegmentId].orEmpty().any { mapping ->
                val target = targetById[mapping.editionSegmentId] ?: return@any false
                val revisions = revisionsBySegment[target.id].orEmpty()
                val visibleText = revisions.maxWithOrNull(compareBy<SegmentRevisionEntity> { it.priority }.thenBy { it.createdAt })?.text
                    ?.takeIf { it.isNotBlank() }
                    ?: target.baseText
                visibleText.isNotBlank() && revisions.any { it.revisionType == RevisionType.AI_POLISH.name }
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
            val rawText = mappings[item.id].orEmpty().sortedBy { it.mappingOrder }
                .mapNotNull { mapping -> editionSegments[mapping.editionSegmentId]?.let { segment ->
                    revisions[segment.id]?.text?.takeIf { it.isNotBlank() } ?: segment.baseText
                } }
                .joinToString("\n\n")
            val masked = TranslationTextProtection.protect(rawText)
            ProtocolSegment(
                shortId = index + 1,
                logicalSegmentId = item.id,
                text = masked.masked,
                originalText = rawText,
                protectedTokens = masked.tokens
            )
        }
        return ProtocolChapter(shortId, chapter.id, chapter.chapterIndex, chapter.canonicalTitle, segments)
    }

    private suspend fun loadPreviousContextTails(
        project: TranslationProjectV2Entity,
        firstChapterIndex: Int
    ): Pair<String, String> {
        val previous = books.getChapters(project.bookId)
            .filter { it.chapterIndex < firstChapterIndex }
            .maxByOrNull { it.chapterIndex }
            ?: return "" to ""
        // Prefer the revision-resolved text so manual edits and glossary replacements are visible
        // in continuity context; fall back to the chapter file for partially imported rows.
        val previousSource = try {
            loadSourceChapter(project, previous, shortId = 1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val original = previousSource?.segments
            ?.joinToString("\n\n") { it.originalText }
            ?.takeIf { it.isNotBlank() }
            ?: books.getEditionChapter(project.sourceEditionId, previous.id)
                ?.let { files.readEditionChapter(project.bookId, project.sourceEditionId, it.contentFileName) }
                .orEmpty()
        val translated = previousSource?.let { source ->
            loadTranslatedChapter(project, source)?.segments
                ?.values
                ?.joinToString("\n\n")
                ?.takeIf { it.isNotBlank() }
        } ?: books.getEditionChapter(project.targetEditionId, previous.id)
            ?.let { files.readEditionChapter(project.bookId, project.targetEditionId, it.contentFileName) }
            .orEmpty()
        return original.takeLast(900) to translated.takeLast(900)
    }

    /** Reconstruct the currently visible target text from EditionSegment revisions. */
    private suspend fun loadTranslatedChapter(
        project: TranslationProjectV2Entity,
        source: ProtocolChapter
    ): ParsedTranslationChapter? {
        val targetChapter = books.getEditionChapter(project.targetEditionId, source.logicalChapterId) ?: return null
        val targetSegments = books.getEditionSegments(targetChapter.id)
        if (targetSegments.isEmpty()) return null
        val targetById = targetSegments.associateBy { it.id }
        val targetIds = targetById.keys
        val mappings = books.getMappings(source.segments.map { it.logicalSegmentId })
            .filter { it.editionSegmentId in targetIds }
            .groupBy { it.logicalSegmentId }
        val revisionsByTarget = books.getActiveRevisions(targetIds.toList())
            .groupBy { it.editionSegmentId }
            .mapValues { (_, rows) ->
                rows.maxWithOrNull(compareBy<SegmentRevisionEntity> { it.priority }.thenBy { it.createdAt })
            }
        val segments = linkedMapOf<Int, String>()
        source.segments.forEach { sourceSegment ->
            val text = mappings[sourceSegment.logicalSegmentId].orEmpty()
                .sortedBy { it.mappingOrder }
                .mapNotNull { mapping ->
                    targetById[mapping.editionSegmentId]?.let { targetSegment ->
                        revisionsByTarget[targetSegment.id]?.text?.takeIf { it.isNotBlank() } ?: targetSegment.baseText
                    }
                }
                .joinToString("\n\n")
            segments[sourceSegment.shortId] = text
        }
        return ParsedTranslationChapter(shortId = source.shortId, segments = segments)
    }

    private suspend fun translateBatch(
        project: TranslationProjectV2Entity,
        provider: ApiProviderEntity,
        runId: Long,
        batchId: Long,
        context: ContextPackage,
        sources: List<ProtocolChapter>,
        promptProfile: PromptProfileDraft,
        allowChapterIsolation: Boolean = true
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
        val systemPrompt = TranslationProtocol.translationSystemPrompt(
            promptProfile,
            project.sourceLanguage,
            project.targetLanguage,
            project.styleGuide
        )
        val userPrompt = TranslationProtocol.translationUserPrompt(promptProfile, context, sources)
        val request = LlmRequest(
            provider = provider,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = provider.temperature,
            maxTokens = dynamicOutputLimit(provider, systemPrompt, userPrompt),
            operation = "BOOK_TRANSLATION",
            promptCacheHint = cacheHint,
            controlSignal = controls[project.id]?.signal
        )
        val result = executeCompletionSafely(request)
        recordUsage(runId, batchId, provider, request, result)
        awaitCommitBoundary(project.id)
        // RetryingLlmGateway deliberately marks provider-truncated responses as unsuccessful so
        // they are never retried blindly. If the response still contains parseable chapters,
        // keep those complete chapters and repair only the missing/malformed ones below.
        val parsedResponse = runCatching { TranslationProtocol.parse(result.text) }.getOrNull()
        val canRecoverTruncated = result.isTruncated && parsedResponse?.chapters?.isNotEmpty() == true
        if ((!result.isSuccess && !canRecoverTruncated) || (result.isTruncated && !canRecoverTruncated)) {
            val failure = result.errorMessage ?: "Translation request failed"
            SystemLogger.error("LLM_API", "❌ 模型调用失败: $failure", projectId = project.id, chapterIndex = firstIndex)
            val requestCost = TokenCalculator.calculateCost(
                result.promptTokens,
                result.completionTokens,
                provider.inputPricePerMillion,
                provider.outputPricePerMillion
            )
            val isolationCategories = setOf(
                LlmErrorCategory.CONTEXT_OVERFLOW,
                LlmErrorCategory.EMPTY_RESPONSE,
                LlmErrorCategory.PARSE_ERROR,
                LlmErrorCategory.TRUNCATED_OUTPUT
            )
            val effectiveCategory = result.errorCategory ?: when {
                result.isTruncated -> LlmErrorCategory.TRUNCATED_OUTPUT
                parsedResponse?.chapters.isNullOrEmpty() -> LlmErrorCategory.PARSE_ERROR
                else -> null
            }
            if (allowChapterIsolation && sources.size > 1 && effectiveCategory?.let { it in isolationCategories } == true) {
                // A multi-chapter request can fail because of one malformed/oversized chapter.
                // Keep the parent batch as an audit record, then retry each chapter independently.
                tasks.updateBatch(
                    (tasks.getBatch(batchId) ?: error("Batch not found")).copy(
                        state = "PARTIAL",
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        cost = requestCost,
                        errorMessage = "批次请求失败，已按章节隔离重试: $failure"
                    )
                )
                var isolatedFailure = false
                sources.forEach { source ->
                    val isolatedBatchId = tasks.insertBatch(
                        PlatformTranslationBatchEntity(
                            runId = runId,
                            // Negative indexes are reserved for children created by isolation and
                            // cannot collide with the normal zero-based scheduler indexes.
                            batchIndex = (-source.chapterIndex.toLong() - 1L)
                                .coerceIn(Int.MIN_VALUE.toLong(), -1L).toInt(),
                            firstChapterIndex = source.chapterIndex,
                            lastChapterIndex = source.chapterIndex,
                            state = "RUNNING",
                            errorMessage = "isolated retry from batch #$batchId"
                        )
                    )
                    translateBatch(
                        project = project,
                        provider = provider,
                        runId = runId,
                        batchId = isolatedBatchId,
                        context = prepareContext(project, listOf(source)),
                        sources = listOf(source),
                        promptProfile = promptProfile,
                        allowChapterIsolation = false
                    )
                    val childState = tasks.getBatch(isolatedBatchId)?.state
                    isolatedFailure = isolatedFailure || childState == "FAILED" || childState == "PARTIAL"
                }
                tasks.getBatch(batchId)?.let { parent ->
                    tasks.updateBatch(
                        parent.copy(
                            state = if (isolatedFailure) "PARTIAL" else "COMPLETED",
                            errorMessage = if (isolatedFailure) {
                                "批次已隔离重试，仍有章节失败: $failure"
                            } else {
                                null
                            }
                        )
                    )
                }
            } else {
                tasks.updateBatch(
                    (tasks.getBatch(batchId) ?: error("Batch not found")).copy(
                        state = "FAILED",
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        cost = requestCost,
                        errorMessage = failure
                    )
                )
                updateRunCounters(runId, completed = 0, failed = sources.size)
            }
            return
        }
        val initialCost = TokenCalculator.calculateCost(result.promptTokens, result.completionTokens, provider.inputPricePerMillion, provider.outputPricePerMillion)
        SystemLogger.info(
            "LLM_API",
            "📥 收到模型响应 (耗时 ${result.durationMs}ms): 消耗输入 ${result.promptTokens} Tokens, 输出 ${result.completionTokens} Tokens, 预估费用 ${TokenCalculator.formatCost(initialCost, provider.currency)}",
            projectId = project.id,
            chapterIndex = firstIndex
        )
        val parsed = parsedResponse
        // Parse each chapter independently. A response can be structurally truncated after one or
        // more complete chapters; QA accepts those complete chapters and repairs only the missing
        // or malformed chapter instead of discarding valid work from the same paid request.
        val completeParsed = parsed
        val mandatoryTerms = context.matchedLexicon
        var failed = 0
        var totalPromptTokens = result.promptTokens
        var totalCompletionTokens = result.completionTokens
        val initialMetadataSources = mutableListOf<ProtocolChapter>()
        val repairedMetadata = mutableListOf<Pair<ProtocolChapter, String?>>()
        sources.forEach { source ->
            val matchingChapters = completeParsed?.chapters.orEmpty().filter { it.shortId == source.shortId }
            var translated = matchingChapters.singleOrNull()?.let { restoreProtectedChapter(source, it) }
            var qa = DeterministicTranslationQa.validate(source, translated, mandatoryTerms)
            var usedRepair = false
            var repairMetaJson: String? = null
            if (!qa.accepted) {
                val initialRepairScope = DeterministicTranslationQa.repairScope(source, translated, qa, mandatoryTerms)
                SystemLogger.warn("QA_CHECK", "⚠️ 章节 #${source.chapterIndex} 首次质检不合格 [glossary=${qa.glossaryStatus}] (${qa.problems.joinToString()})，启动一次自动修复...", projectId = project.id, chapterIndex = source.chapterIndex)
                recordQaDiagnostic(runId, batchId, source.chapterIndex, "QA_REPAIR_TRIGGERED", qa.problems)
                val repair = repairChapter(
                    project = project,
                    provider = provider,
                    runId = runId,
                    batchId = batchId,
                    context = context,
                    source = source,
                    partial = translated,
                    promptProfile = promptProfile,
                    problems = qa.problems,
                    qa = qa,
                    mandatoryTerms = mandatoryTerms
                )
                translated = repair.chapter
                repairMetaJson = repair.metaJson
                usedRepair = true
                totalPromptTokens += repair.promptTokens
                totalCompletionTokens += repair.completionTokens
                qa = DeterministicTranslationQa.validate(source, translated, mandatoryTerms)
                // A local response can be syntactically valid yet leave a different affected
                // segment untouched. One bounded full-chapter fallback is safer than preserving
                // an empty or numerically corrupted segment indefinitely.
                if (!qa.accepted && initialRepairScope.mode == QaRepairMode.LOCAL_SEGMENTS) {
                    recordQaDiagnostic(runId, batchId, source.chapterIndex, "QA_REPAIR_FALLBACK_TRIGGERED", qa.problems)
                    val fallback = repairChapter(
                        project = project,
                        provider = provider,
                        runId = runId,
                        batchId = batchId,
                        context = context,
                        source = source,
                        partial = translated,
                        promptProfile = promptProfile,
                        problems = qa.problems,
                        qa = qa,
                        mandatoryTerms = mandatoryTerms,
                        forceFullChapter = true
                    )
                    translated = fallback.chapter
                    repairMetaJson = fallback.metaJson ?: repairMetaJson
                    totalPromptTokens += fallback.promptTokens
                    totalCompletionTokens += fallback.completionTokens
                    qa = DeterministicTranslationQa.validate(source, translated, mandatoryTerms)
                }
            }
            SystemLogger.info(
                "QA_CHECK",
                "章节 #${source.chapterIndex} glossary consistency=${qa.glossaryStatus}",
                projectId = project.id,
                chapterIndex = source.chapterIndex
            )
            if (qa.accepted && translated != null) {
                awaitCommitBoundary(project.id)
                commitChapter(project, source, translated)
                if (usedRepair) repairedMetadata += source to repairMetaJson else initialMetadataSources += source
                SystemLogger.info("STORAGE", "💾 章节 #${source.chapterIndex} 《${source.title}》 译文已持久化落库", projectId = project.id, chapterIndex = source.chapterIndex)
            } else {
                failed++
                recordQaDiagnostic(runId, batchId, source.chapterIndex, "QA_VALIDATION_FAILED", qa.problems + "glossary=${qa.glossaryStatus}")
                SystemLogger.error("QA_CHECK", "❌ 章节 #${source.chapterIndex} 质检失败且无法自动修复", projectId = project.id, chapterIndex = source.chapterIndex)
            }
        }
        if (initialMetadataSources.isNotEmpty()) {
            applyMetadata(project, initialMetadataSources, completeParsed?.metaJson)
        }
        repairedMetadata.forEach { (source, metaJson) ->
            // A repair response is authoritative for the chapter it repaired. If it omitted META,
            // applyMetadata records PENDING_REPAIR without invalidating the accepted translation.
            applyMetadata(project, listOf(source), metaJson)
        }
        val totalCost = TokenCalculator.calculateCost(
            totalPromptTokens,
            totalCompletionTokens,
            provider.inputPricePerMillion,
            provider.outputPricePerMillion
        )
        val batch = (tasks.getBatch(batchId) ?: error("Batch not found")).copy(
            state = if (failed == 0) "COMPLETED" else "PARTIAL",
            promptTokens = totalPromptTokens,
            completionTokens = totalCompletionTokens,
            cost = totalCost,
            errorMessage = when {
                failed > 0 -> "$failed chapter(s) failed deterministic QA"
                result.isTruncated -> "模型响应曾截断，已恢复可解析章节"
                else -> null
            }
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

    /**
     * Runs only after the selected draft scope is complete. A failed review never invalidates a
     * valid draft: the original chapter remains the active text and the request is retained in
     * the normal request-audit stream with operation=BOOK_POLISH.
     */
    private suspend fun runPostDraftReview(
        project: TranslationProjectV2Entity,
        provider: ApiProviderEntity,
        runId: Long,
        chapters: List<LogicalChapterEntity>,
        control: ProjectControl,
        promptProfile: PromptProfileDraft
    ) {
        SystemLogger.info(
            "AI_POLISH",
            "📝 初稿范围已完成，开始二次审校 ${chapters.size} 章（默认关闭的可选阶段）",
            projectId = project.id
        )
        chapters.forEachIndexed { offset, logicalChapter ->
            awaitBoundary(project.id, control)
            val batchId = tasks.insertBatch(
                PlatformTranslationBatchEntity(
                    runId = runId,
                    batchIndex = POST_DRAFT_REVIEW_BATCH_OFFSET + offset,
                    firstChapterIndex = logicalChapter.chapterIndex,
                    lastChapterIndex = logicalChapter.chapterIndex,
                    state = "RUNNING",
                    errorMessage = "post-draft AI review"
                )
            )
            val source = loadSourceChapter(project, logicalChapter, shortId = 1)
            val current = loadTranslatedChapter(project, source)
            val mandatoryTerms = database.lexiconV2Dao().getConfirmed(project.id)
                .filter { LexiconEntryPolicy.isEligibleForTranslation(it) }
            val baseQa = DeterministicTranslationQa.validate(source, current, mandatoryTerms)
            if (current == null || !baseQa.accepted) {
                val reason = if (current == null) {
                    "找不到完整初稿，跳过二次审校"
                } else {
                    "初稿未通过基线 QA，跳过二次审校: ${baseQa.problems.distinct().joinToString()}"
                }
                recordQaDiagnostic(runId, batchId, source.chapterIndex, "AI_POLISH_SKIPPED_BASE_QA", listOf(reason))
                tasks.getBatch(batchId)?.let {
                    tasks.updateBatch(it.copy(state = "PARTIAL", errorMessage = reason))
                }
                updateRunCounters(runId, completed = 0, failed = 1)
                return@forEachIndexed
            }
            val currentDraft = current ?: return@forEachIndexed

            val previousTails = loadPreviousContextTails(project, source.chapterIndex)
            val context = contextEngine.prepare(
                project = project,
                sourceText = source.segments.joinToString("\n") { it.originalText },
                firstChapterIndex = source.chapterIndex,
                previousChapterOriginalTail = previousTails.first,
                previousChapterTranslationTail = previousTails.second
            )
            val reviewTokens = source.segments.sumOf { segment ->
                TokenBudgetPlanner.estimate(segment.text) +
                    TokenBudgetPlanner.estimate(currentDraft.segments[segment.shortId].orEmpty())
            }
            val reviewSystemPrompt = TranslationProtocol.polishSystemPrompt(
                promptProfile,
                project.sourceLanguage,
                project.targetLanguage,
                project.styleGuide
            )
            val reviewUserPrompt = TranslationProtocol.polishUserPrompt(promptProfile, context, source, currentDraft)
            val fixedTokens = (TokenBudgetPlanner.estimate(reviewSystemPrompt + reviewUserPrompt) - reviewTokens)
                .coerceAtLeast(0)
            val budget = TokenBudgetPlanner.plan(
                maxContextTokens = provider.maxContextTokens,
                userMaxBatchSize = 1,
                sourceTokenEstimates = listOf(reviewTokens),
                fixedContextTokens = fixedTokens,
                fixedOutputTokens = protocolOutputOverhead(listOf(source))
            )
            if (budget.requiresSingleChapterChunking || budget.actualBatchSize < 1) {
                val reason = "章节过长，无法在单次二次审校预算内安全处理，已保留初稿"
                recordQaDiagnostic(runId, batchId, source.chapterIndex, "AI_POLISH_SKIPPED_BUDGET", listOf(reason))
                tasks.getBatch(batchId)?.let {
                    tasks.updateBatch(it.copy(state = "PARTIAL", errorMessage = reason))
                }
                updateRunCounters(runId, completed = 0, failed = 1)
                SystemLogger.warn("AI_POLISH", reason, projectId = project.id, chapterIndex = source.chapterIndex)
                return@forEachIndexed
            }

            // Re-check immediately before the paid second-pass request so a pause/cancel issued
            // while QA or prompt assembly was running cannot start another call.
            awaitBoundary(project.id, control)
            val outcome = polishChapter(
                project,
                provider,
                runId,
                batchId,
                context,
                source,
                currentDraft,
                mandatoryTerms,
                promptProfile
            )
            if (outcome.chapter != null) {
                awaitCommitBoundary(project.id)
                commitChapter(project, source, outcome.chapter, RevisionType.AI_POLISH)
                SystemLogger.info(
                    "AI_POLISH",
                    "✨ 初稿完成后已提交第 ${source.chapterIndex} 章二次审校结果",
                    projectId = project.id,
                    chapterIndex = source.chapterIndex
                )
            } else {
                val reason = outcome.reason ?: "二次审校未返回可用结果，已保留初稿"
                recordQaDiagnostic(runId, batchId, source.chapterIndex, "AI_POLISH_FALLBACK", listOf(reason))
                SystemLogger.warn("AI_POLISH", reason, projectId = project.id, chapterIndex = source.chapterIndex)
                updateRunCounters(runId, completed = 0, failed = 1)
            }
            awaitCommitBoundary(project.id)
            val cost = TokenCalculator.calculateCost(
                outcome.promptTokens,
                outcome.completionTokens,
                provider.inputPricePerMillion,
                provider.outputPricePerMillion
            )
            tasks.getBatch(batchId)?.let {
                tasks.updateBatch(
                    it.copy(
                        state = if (outcome.chapter != null) "COMPLETED" else "PARTIAL",
                        promptTokens = outcome.promptTokens,
                        completionTokens = outcome.completionTokens,
                        cost = cost,
                        errorMessage = outcome.reason
                    )
                )
            }
        }
        SystemLogger.info(
            "AI_POLISH",
            "✅ 二次审校阶段处理完毕，失败结果均保留原初稿",
            projectId = project.id
        )
    }

    private suspend fun polishChapter(
        project: TranslationProjectV2Entity,
        provider: ApiProviderEntity,
        runId: Long,
        batchId: Long,
        context: ContextPackage,
        source: ProtocolChapter,
        current: ParsedTranslationChapter,
        mandatoryTerms: List<LexiconEntryEntity>,
        promptProfile: PromptProfileDraft
    ): DraftReviewOutcome {
        val systemPrompt = TranslationProtocol.polishSystemPrompt(
            promptProfile,
            project.sourceLanguage,
            project.targetLanguage,
            project.styleGuide
        )
        val userPrompt = TranslationProtocol.polishUserPrompt(promptProfile, context, source, current)
        val request = LlmRequest(
            provider = provider,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            // A second pass should be conservative even when the translation temperature is
            // configured higher for literary variation.
            temperature = provider.temperature.coerceAtMost(0.35f),
            maxTokens = dynamicOutputLimit(provider, systemPrompt, userPrompt),
            operation = "BOOK_POLISH",
            promptCacheHint = prepareCache(project, provider, context),
            controlSignal = controls[project.id]?.signal
        )
        val result = try {
            executeCompletionSafely(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            LlmResult(
                text = "",
                promptTokens = 0,
                completionTokens = 0,
                isSuccess = false,
                errorCategory = LlmErrorCategory.UNKNOWN,
                errorMessage = error.localizedMessage ?: error.javaClass.simpleName,
                operation = request.operation
            )
        }
        recordUsage(runId, batchId, provider, request, result)
        awaitCommitBoundary(project.id)
        if (!result.isSuccess || result.isTruncated) {
            return DraftReviewOutcome(
                chapter = null,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                reason = "二次审校模型调用失败: ${result.errorMessage ?: result.errorCategory ?: "unknown"}"
            )
        }
        val parsed = runCatching { TranslationProtocol.parse(result.text) }.getOrNull()
        val candidate = parsed
            ?.takeUnless { result.isTruncated }
            ?.chapters
            ?.singleOrNull()
            ?.takeIf { it.shortId == source.shortId }
            ?.let { restoreProtectedChapter(source, it) }
        if (candidate == null) {
            return DraftReviewOutcome(
                chapter = null,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                reason = "二次审校响应缺少严格匹配的 Chapter/Segment 结构"
            )
        }
        val qa = DeterministicTranslationQa.validate(source, candidate, mandatoryTerms)
        if (!qa.accepted) {
            return DraftReviewOutcome(
                chapter = null,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                reason = "二次审校结果未通过 QA: ${qa.problems.distinct().joinToString()}"
            )
        }
        val rewriteProblems = polishChangeProblems(current, candidate)
        if (rewriteProblems.isNotEmpty()) {
            return DraftReviewOutcome(
                chapter = null,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                reason = "二次审校疑似重写初稿，已保留初稿: ${rewriteProblems.joinToString()}"
            )
        }
        return DraftReviewOutcome(candidate, result.promptTokens, result.completionTokens)
    }

    /** Conservative guard against a copy-editor call silently becoming a fresh translation. */
    private fun polishChangeProblems(
        current: ParsedTranslationChapter,
        candidate: ParsedTranslationChapter
    ): List<String> {
        val problems = mutableListOf<String>()
        if (candidate.segments.keys != current.segments.keys) {
            problems += "segment ids changed"
            return problems
        }
        current.segments.forEach { (segmentId, oldText) ->
            val newText = candidate.segments[segmentId].orEmpty()
            if (oldText.isBlank() || newText.isBlank()) return@forEach
            val oldLength = oldText.codePointCount(0, oldText.length).coerceAtLeast(1)
            val newLength = newText.codePointCount(0, newText.length).coerceAtLeast(1)
            val ratio = newLength.toDouble() / oldLength
            if (ratio < 0.45 || ratio > 2.20) {
                problems += "segment $segmentId length changed too much"
                return@forEach
            }
            val oldLines = oldText.count { it == '\n' }
            val newLines = newText.count { it == '\n' }
            if (kotlin.math.abs(oldLines - newLines) > maxOf(2, oldLines / 2)) {
                problems += "segment $segmentId paragraph boundaries changed"
                return@forEach
            }
            val oldShingles = textShingles(oldText)
            val newShingles = textShingles(newText)
            if (oldShingles.size >= 8) {
                val overlap = oldShingles.intersect(newShingles).size.toDouble() / oldShingles.size
                if (overlap < 0.08) problems += "segment $segmentId has insufficient draft overlap"
            }
        }
        return problems.distinct()
    }

    private fun textShingles(text: String): Set<String> {
        val normalized = text.filterNot(Char::isWhitespace)
        if (normalized.length < 2) return setOf(normalized)
        return normalized.windowed(size = 2, step = 1).toSet()
    }

    private suspend fun repairChapter(
        project: TranslationProjectV2Entity,
        provider: ApiProviderEntity,
        runId: Long,
        batchId: Long,
        context: ContextPackage,
        source: ProtocolChapter,
        partial: ParsedTranslationChapter?,
        promptProfile: PromptProfileDraft,
        problems: List<String> = emptyList(),
        previousChunkTranslationTail: String = "",
        qa: QaResult? = null,
        mandatoryTerms: List<LexiconEntryEntity> = emptyList(),
        forceFullChapter: Boolean = false
    ): ChapterRepairOutcome {
        val scope = if (forceFullChapter) {
            QaRepairScope(QaRepairMode.FULL_CHAPTER, reasons = problems)
        } else {
            qa?.let { DeterministicTranslationQa.repairScope(source, partial, it, mandatoryTerms) }
                ?: QaRepairScope(QaRepairMode.FULL_CHAPTER, reasons = problems)
        }
        val retrySource = if (scope.mode == QaRepairMode.LOCAL_SEGMENTS && partial != null) {
            source.copy(segments = source.segments.filter { it.shortId in scope.segmentIds })
        } else {
            source
        }
        if (retrySource.segments.isEmpty()) {
            return ChapterRepairOutcome(partial, 0, 0)
        }
        val systemPrompt = TranslationProtocol.translationSystemPrompt(
            promptProfile,
            project.sourceLanguage,
            project.targetLanguage,
            project.styleGuide
        )
        val userPrompt = TranslationProtocol.repairUserPrompt(
            promptProfile,
            context,
            listOf(retrySource),
            problems,
            previousChunkTranslationTail
        )
        val request = LlmRequest(
            provider = provider,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = provider.temperature.coerceIn(0f, 0.2f),
            maxTokens = dynamicOutputLimit(provider, systemPrompt, userPrompt),
            operation = "CHAPTER_REPAIR",
            promptCacheHint = prepareCache(project, provider, context),
            controlSignal = controls[project.id]?.signal
        )
        val retry = executeCompletionSafely(request)
        recordUsage(runId, batchId, provider, request, retry)
        awaitCommitBoundary(project.id)
        val repairedResponse = runCatching { TranslationProtocol.parse(retry.text) }.getOrNull()
        val canRecoverTruncated = retry.isTruncated && repairedResponse?.chapters?.isNotEmpty() == true
        if ((!retry.isSuccess && !canRecoverTruncated) || (retry.isTruncated && !canRecoverTruncated)) {
            return ChapterRepairOutcome(partial, retry.promptTokens, retry.completionTokens, repairedResponse?.metaJson)
        }
        val repaired = repairedResponse
            ?.chapters
            ?.filter { it.shortId == source.shortId }
            ?.singleOrNull()
            ?.let { restoreProtectedChapter(source, it) }
            ?: return ChapterRepairOutcome(partial, retry.promptTokens, retry.completionTokens, repairedResponse?.metaJson)
        if (partial == null) return ChapterRepairOutcome(repaired, retry.promptTokens, retry.completionTokens, repairedResponse?.metaJson)
        val sourceSegmentIds = source.segments.mapTo(hashSetOf<Int>()) { it.shortId }
        val merged = source.segments.mapNotNull { segment ->
            val repairedText = repaired.segments[segment.shortId]
            val partialText = partial.segments[segment.shortId]
            // A returned replacement wins even when it is an empty string; preserving the old
            // value here would keep the very EMPTY_SEGMENT that triggered the repair alive.
            (if (segment.shortId in retrySource.segments.map { it.shortId }) repairedText else partialText)
                ?.let { segment.shortId to it }
        }.toMap(LinkedHashMap<Int, String>()).also { mergedSegments ->
            // Do not silently discard an unexpected ID emitted by the repair response. Keeping it
            // in the merged map lets deterministic QA reject the response instead of mistaking a
            // malformed local repair for a clean chapter.
            repaired.segments
                .filterKeys { it !in sourceSegmentIds }
                .forEach { (id, text) -> mergedSegments[id] = text }
        }
        val repairedCoversSource = retrySource.segments.all { it.shortId in repaired.segments }
        return ChapterRepairOutcome(
            chapter = partial.copy(
                segments = merged,
                duplicateSegmentIds = if (repairedCoversSource) {
                    if (scope.mode == QaRepairMode.FULL_CHAPTER) {
                        repaired.duplicateSegmentIds
                    } else {
                        partial.duplicateSegmentIds + repaired.duplicateSegmentIds
                    }
                } else {
                    partial.duplicateSegmentIds + repaired.duplicateSegmentIds
                }
            ),
            promptTokens = retry.promptTokens,
            completionTokens = retry.completionTokens,
            metaJson = repairedResponse?.metaJson
        )
    }

    private suspend fun translateOversizedChapter(
        project: TranslationProjectV2Entity,
        provider: ApiProviderEntity,
        runId: Long,
        batchId: Long,
        context: ContextPackage,
        source: ProtocolChapter,
        promptProfile: PromptProfileDraft
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
            if (current.isNotEmpty() &&
                (tokens + size > safeSourceBudget || current.size >= MAX_PROTOCOL_SEGMENTS_PER_REQUEST)
            ) {
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
        var previousChunkTranslationTail = ""
        groups.forEach { group ->
            val chunkSource = source.copy(segments = group)
            val systemPrompt = TranslationProtocol.translationSystemPrompt(
                promptProfile,
                project.sourceLanguage,
                project.targetLanguage,
                project.styleGuide
            )
            val userPrompt = TranslationProtocol.translationUserPrompt(
                promptProfile,
                context,
                listOf(chunkSource),
                previousChunkTranslationTail
            )
            val request = LlmRequest(
                provider = provider,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                temperature = provider.temperature,
                maxTokens = dynamicOutputLimit(provider, systemPrompt, userPrompt),
                operation = "OVERSIZED_CHAPTER_CHUNK",
                promptCacheHint = prepareCache(project, provider, context),
                controlSignal = controls[project.id]?.signal
            )
            val result = executeCompletionSafely(request)
            recordUsage(runId, batchId, provider, request, result)
            awaitCommitBoundary(project.id)
            prompt += result.promptTokens
            completion += result.completionTokens
            val parsedResponse = runCatching { TranslationProtocol.parse(result.text) }.getOrNull()
            val canRecoverTruncated = result.isTruncated && parsedResponse?.chapters?.isNotEmpty() == true
            if ((!result.isSuccess && !canRecoverTruncated) || (result.isTruncated && !canRecoverTruncated)) {
                val failure = result.errorMessage ?: "Oversized chapter chunk failed"
                val cost = TokenCalculator.calculateCost(
                    prompt,
                    completion,
                    provider.inputPricePerMillion,
                    provider.outputPricePerMillion
                )
                tasks.updateBatch(
                    (tasks.getBatch(batchId) ?: error("Batch not found")).copy(
                        state = "FAILED",
                        promptTokens = prompt,
                        completionTokens = completion,
                        cost = cost,
                        errorMessage = failure
                    )
                )
                updateRunCounters(runId, completed = 0, failed = 1)
                SystemLogger.error("LLM_API", "❌ 超大章节分块调用失败: $failure", projectId = project.id, chapterIndex = source.chapterIndex)
                return
            }
            val mandatoryTerms = context.matchedLexicon
            var parsed = parsedResponse
                ?.chapters
                ?.filter { it.shortId == chunkSource.shortId }
                ?.singleOrNull()
                ?.let { restoreProtectedChapter(chunkSource, it) }
            var qa = DeterministicTranslationQa.validate(chunkSource, parsed, mandatoryTerms)
            if (!qa.accepted) {
                val initialRepairScope = DeterministicTranslationQa.repairScope(chunkSource, parsed, qa, mandatoryTerms)
                recordQaDiagnostic(runId, batchId, source.chapterIndex, "QA_CHUNK_REPAIR_TRIGGERED", qa.problems)
                val repair = repairChapter(
                    project = project,
                    provider = provider,
                    runId = runId,
                    batchId = batchId,
                    context = context,
                    source = chunkSource,
                    partial = parsed,
                    promptProfile = promptProfile,
                    problems = qa.problems,
                    qa = qa,
                    mandatoryTerms = mandatoryTerms,
                    previousChunkTranslationTail = previousChunkTranslationTail
                )
                parsed = repair.chapter
                prompt += repair.promptTokens
                completion += repair.completionTokens
                qa = DeterministicTranslationQa.validate(chunkSource, parsed, mandatoryTerms)
                if (!qa.accepted && initialRepairScope.mode == QaRepairMode.LOCAL_SEGMENTS) {
                    recordQaDiagnostic(runId, batchId, source.chapterIndex, "QA_CHUNK_REPAIR_FALLBACK_TRIGGERED", qa.problems)
                    val fallback = repairChapter(
                        project = project,
                        provider = provider,
                        runId = runId,
                        batchId = batchId,
                        context = context,
                        source = chunkSource,
                        partial = parsed,
                        promptProfile = promptProfile,
                        problems = qa.problems,
                        qa = qa,
                        mandatoryTerms = mandatoryTerms,
                        previousChunkTranslationTail = previousChunkTranslationTail,
                        forceFullChapter = true
                    )
                    parsed = fallback.chapter
                    prompt += fallback.promptTokens
                    completion += fallback.completionTokens
                    qa = DeterministicTranslationQa.validate(chunkSource, parsed, mandatoryTerms)
                }
            }
            if (!qa.accepted || parsed == null) {
                recordQaDiagnostic(runId, batchId, source.chapterIndex, "QA_CHUNK_FAILED", qa.problems + "glossary=${qa.glossaryStatus}")
                tasks.updateBatch(
                    (tasks.getBatch(batchId) ?: error("Batch not found")).copy(
                        state = "FAILED",
                        promptTokens = prompt,
                        completionTokens = completion,
                        cost = TokenCalculator.calculateCost(
                            prompt,
                            completion,
                            provider.inputPricePerMillion,
                            provider.outputPricePerMillion
                        ),
                        errorMessage = qa.problems.joinToString()
                    )
                )
                updateRunCounters(runId, completed = 0, failed = 1)
                return
            }
            val validated = parsed ?: error("QA accepted a missing parsed chunk")
            validated.segments.forEach { (id, text) ->
                val previous = translated[id]
                translated[id] = if (previous.isNullOrBlank()) text else "$previous\n\n$text"
            }
            previousChunkTranslationTail = validated.segments.values.joinToString("\n").takeLast(900)
        }
        awaitCommitBoundary(project.id)
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

    private suspend fun commitChapter(
        project: TranslationProjectV2Entity,
        source: ProtocolChapter,
        translated: ParsedTranslationChapter,
        revisionType: RevisionType = RevisionType.AI_TRANSLATION
    ) {
        val joined = source.segments.joinToString("\n\n") { translated.segments[it.shortId].orEmpty() }
        val previousFileName = books.getEditionChapter(project.targetEditionId, source.logicalChapterId)?.contentFileName
        val fileName = files.saveEditionChapterVersion(project.bookId, project.targetEditionId, source.chapterIndex, source.title, joined)
        try {
            awaitCommitBoundary(project.id)
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
                    val revisionTypeName = revisionType.name
                    if (existingId != null) {
                        // A new translation invalidates the previous second-pass marker. Likewise,
                        // repeated review calls replace the previous AI_POLISH revision without
                        // touching manual or confirmed glossary revisions.
                        books.getActiveRevisions(listOf(existingId))
                            .filter { it.revisionType == RevisionType.AI_POLISH.name }
                            .forEach { books.deactivateRevision(it.id) }
                        books.insertRevision(SegmentRevisionEntity(editionSegmentId = existingId, revisionType = revisionTypeName, text = text))
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
                        books.insertRevision(SegmentRevisionEntity(editionSegmentId = segmentId, revisionType = revisionTypeName, text = text))
                    }
                }
            }
        } catch (error: Throwable) {
            files.deleteEditionChapterFile(project.bookId, project.targetEditionId, fileName)
            throw error
        }
        // The database now points at the new immutable file. A cleanup failure must not make the
        // committed chapter look failed and must not delete the file that the row references.
        runCatching {
            files.deleteEditionChapterFile(project.bookId, project.targetEditionId, previousFileName)
        }.onFailure { cleanupError ->
            SystemLogger.warn(
                "STORAGE",
                "旧章节文件清理失败，已保留数据库与新文件: ${cleanupError.localizedMessage ?: cleanupError.javaClass.simpleName}",
                projectId = project.id,
                chapterIndex = source.chapterIndex
            )
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
        val metadataSource = sources.flatMap { it.segments }.joinToString("\n") { it.originalText }
        val confirmedSourceTerms = database.lexiconV2Dao().getAll(project.id)
            .filter { it.reviewStatus == ReviewStatus.CONFIRMED.name }
            .mapTo(mutableSetOf()) { LexiconCandidateVoting.normalizeSourceTerm(it.sourceTerm) }
        val metadataCandidates = mutableListOf<ExtractedTermCandidate>()
        parsed.lexiconCandidates.forEach { candidate ->
            val normalized = LexiconCandidateVoting.normalizeSourceTerm(candidate.source)
            if (normalized in confirmedSourceTerms) return@forEach
            when (
                val validation = TermCandidateValidator.validate(
                    original = candidate.source,
                    suggested = candidate.target,
                    categoryRaw = candidate.category,
                    notes = candidate.notes,
                    sourceText = metadataSource
                )
            ) {
                is TermValidationResult.Accepted -> metadataCandidates += ExtractedTermCandidate(
                    originalTerm = validation.originalTerm,
                    translatedTerm = validation.translatedTerm,
                    category = validation.category,
                    notes = validation.notes
                )
                is TermValidationResult.Rejected -> SystemLogger.warn(
                    "GLOSSARY",
                    "丢弃 META 术语候选: ${validation.rejection.reason}",
                    projectId = project.id,
                    chapterIndex = sources.firstOrNull()?.chapterIndex
                )
            }
        }
        metadataCandidates.groupBy { candidate ->
            sources.firstOrNull { chapter ->
                chapter.segments.any { segment ->
                    segment.originalText.contains(candidate.originalTerm, ignoreCase = true)
                }
            } ?: sources.first()
        }.forEach { (chapter, candidates) ->
            val chapterSource = chapter.segments.joinToString("\n") { it.originalText }
            val aggregation = lexiconCandidateAggregator.observeWindow(
                projectId = project.id,
                chapterIndex = chapter.chapterIndex,
                sourceText = chapterSource,
                candidates = candidates
            )
            aggregation.updated.forEach { aggregate ->
                val review = LexiconCandidateVoting.review(aggregate)
                SystemLogger.info(
                    "GLOSSARY",
                    "META 候选已聚合: ${aggregate.sourceTerm} obs=${aggregate.observationCount} " +
                        "winner=${review.winnerTargetTerm}/${review.winnerCategory}",
                    projectId = project.id,
                    chapterIndex = chapter.chapterIndex
                )
            }
        }
    }

    private suspend fun recordUsage(runId: Long, batchId: Long, provider: ApiProviderEntity, request: LlmRequest, result: LlmResult) = withContext(NonCancellable) {
        // The provider call has already completed when this method is entered. Keep the audit
        // write durable even if the worker is cancelled during the short persistence window.
        val cost = TokenCalculator.calculateCost(result.promptTokens, result.completionTokens, provider.inputPricePerMillion, provider.outputPricePerMillion)
        val status = when {
            result.isSuccess -> RequestLogStatus.SUCCESS
            result.isTruncated && result.text.isNotBlank() -> RequestLogStatus.WARNING
            else -> RequestLogStatus.FAILURE
        }
        val captureDebug = debugEnabled()
        val attemptTrace = if (captureDebug) result.attempts.joinToString("\n") { attempt ->
            buildString {
                append("Attempt ").append(attempt.attemptNumber)
                append(": success=").append(attempt.result.isSuccess)
                append(", durationMs=").append(attempt.result.durationMs)
                attempt.result.httpStatus?.let { append(", http=").append(it) }
                attempt.result.finishReason?.let { append(", finish=").append(it) }
                attempt.result.errorCategory?.let { append(", category=").append(it.name) }
                attempt.result.errorMessage?.let { append(", error=").append(it) }
            }
        }.ifBlank { null } else null
        tasks.insertRequestLog(
            PlatformRequestLogEntity(
                // The request is the source of truth even for a lightweight/custom gateway that
                // returns the default LlmResult operation.
                runId = runId, batchId = batchId, operation = request.operation,
                attemptCount = result.attempts.size.coerceAtLeast(1), promptTokens = result.promptTokens,
                completionTokens = result.completionTokens, estimatedCost = cost, durationMs = result.durationMs,
                finishReason = result.finishReason, errorCategory = result.errorCategory?.name,
                errorMessage = result.errorMessage,
                systemPrompt = request.systemPrompt.takeIf { captureDebug },
                userPrompt = request.userPrompt.takeIf { captureDebug },
                responseText = result.text.takeIf { captureDebug },
                attemptTrace = attemptTrace,
                isSuccess = result.isSuccess,
                status = status.name
            )
        )
        tasks.getRun(runId)?.let { run ->
            tasks.updateRun(run.copy(promptTokens = run.promptTokens + result.promptTokens, completionTokens = run.completionTokens + result.completionTokens, totalCost = run.totalCost + cost, updatedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun recordQaDiagnostic(
        runId: Long,
        batchId: Long,
        chapterIndex: Int,
        operation: String,
        problems: List<String>
    ) {
        tasks.insertRequestLog(
            PlatformRequestLogEntity(
                runId = runId,
                batchId = batchId,
                operation = "$operation · Chapter $chapterIndex",
                attemptCount = 1,
                promptTokens = 0,
                completionTokens = 0,
                estimatedCost = 0.0,
                durationMs = 0,
                errorCategory = LlmErrorCategory.QUALITY_REJECTED.name,
                errorMessage = problems.distinct().joinToString("; "),
                isSuccess = false,
                status = when {
                    operation.contains("TRIGGERED", ignoreCase = true) ||
                        operation.contains("SKIPPED", ignoreCase = true) ||
                        operation.contains("FALLBACK", ignoreCase = true) -> RequestLogStatus.WARNING.name
                    else -> RequestLogStatus.FAILURE.name
                }
            )
        )
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
                val markerPrefix = "__LNT_PROTECTED_"
                val openMarker = remaining.lastIndexOf(markerPrefix, startIndex = cut - 1)
                if (openMarker >= 0) {
                    val closeMarker = remaining.indexOf("__", startIndex = openMarker + markerPrefix.length)
                    if (closeMarker >= cut) {
                        cut = if (openMarker > 0) openMarker else (closeMarker + 2).coerceAtMost(remaining.length)
                    }
                }
            }
            val maskedPiece = remaining.substring(0, cut)
            val pieceTokens = segment.protectedTokens.filter { maskedPiece.contains(it.marker) }
            pieces += segment.copy(
                text = maskedPiece,
                originalText = TranslationTextProtection.restore(maskedPiece, pieceTokens),
                protectedTokens = pieceTokens
            )
            remaining = remaining.substring(cut)
        }
        return pieces
    }

    private fun restoreProtectedChapter(
        source: ProtocolChapter,
        translated: ParsedTranslationChapter?
    ): ParsedTranslationChapter? {
        if (translated == null) return null
        val sourceById = source.segments.associateBy { it.shortId }
        val restored = translated.segments.mapValues { (shortId, text) ->
            val segment = sourceById[shortId]
            if (segment == null) text else TranslationTextProtection.restore(text, segment.protectedTokens)
        }
        return translated.copy(segments = restored)
    }

    private fun outputLimit(context: Int): Int = (context * 0.42).toInt().coerceIn(1024, 16_384)

    private data class ProjectControl(
        val paused: AtomicBoolean = AtomicBoolean(false),
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val signal: TranslationControlSignal = TranslationControlSignal()
    ) {
        suspend fun requestPause() {
            paused.set(true)
            signal.requestPause()
        }

        fun resume(): Boolean {
            if (cancelled.get()) return false
            paused.set(false)
            signal.resume()
            return true
        }

        suspend fun cancel() {
            cancelled.set(true)
            signal.requestCancel()
        }

    }
}

private data class MetadataChapter(val chapterId: Int?, val chapterIndex: Int?, val summary: String, val entities: String, val stateChanges: String, val newFacts: String, val unresolvedThreads: String)
private data class MetadataStoryDelta(val operation: String, val key: String, val value: String, val entities: String)
private data class MetadataLexicon(val source: String, val target: String, val category: String, val notes: String)
private data class TranslationMetadata(val chapterMemory: List<MetadataChapter>, val storyDelta: List<MetadataStoryDelta>, val lexiconCandidates: List<MetadataLexicon>)

private object MetadataParser {
    private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun parse(json: String): TranslationMetadata {
        val normalized = json.trim()
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
        val root = adapter.fromJson(normalized).orEmpty()
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
        val lexiconRows = (root["lexiconCandidates"] as? List<*> ?: root["lexiconCandidate"] as? List<*>).orEmpty()
        val lexicon = lexiconRows.map { map(it) }.map { row ->
            MetadataLexicon(
                str(row, "source").ifBlank { str(row, "sourceTerm") },
                str(row, "target").ifBlank { str(row, "targetTerm") },
                str(row, "category"),
                str(row, "notes")
            )
        }
        return TranslationMetadata(chapter, story, lexicon)
    }
}
