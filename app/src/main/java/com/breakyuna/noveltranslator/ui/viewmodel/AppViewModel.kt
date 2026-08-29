package com.breakyuna.noveltranslator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.breakyuna.noveltranslator.core.agent.ChapterSplitAgent
import com.breakyuna.noveltranslator.core.agent.LexiconCandidateAggregator
import com.breakyuna.noveltranslator.core.agent.TermExtractionAgent
import com.breakyuna.noveltranslator.core.exporter.EditionExporter
import com.breakyuna.noveltranslator.core.llm.LlmClient
import com.breakyuna.noveltranslator.core.llm.RetryingLlmGateway
import com.breakyuna.noveltranslator.core.llm.executeCompletion
import com.breakyuna.noveltranslator.core.parser.*
import com.breakyuna.noveltranslator.core.book.BookFileManager
import com.breakyuna.noveltranslator.core.book.BookImporter
import com.breakyuna.noveltranslator.core.book.AcquiredBook
import com.breakyuna.noveltranslator.core.book.AcquiredChapter
import com.breakyuna.noveltranslator.core.translation.BookTranslationEngine
import com.breakyuna.noveltranslator.core.translation.BookTranslationScheduler
import com.breakyuna.noveltranslator.core.translation.ContextEngine
import com.breakyuna.noveltranslator.core.sample.SampleNovelProvider
import com.breakyuna.noveltranslator.core.security.ApiKeyCipher
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.*
import com.breakyuna.noveltranslator.data.repository.*
import com.breakyuna.noveltranslator.ui.i18n.AppLanguage
import com.breakyuna.noveltranslator.ui.i18n.AppStrings
import com.breakyuna.noveltranslator.ui.i18n.getAppStrings
import android.content.Context
import android.provider.OpenableColumns
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("novel_translator_prefs", Context.MODE_PRIVATE)

    private val _debugModeEnabled = MutableStateFlow(prefs.getBoolean("debug_mode_enabled", false))
    val debugModeEnabled: StateFlow<Boolean> = _debugModeEnabled.asStateFlow()

    fun setDebugModeEnabled(enabled: Boolean) {
        _debugModeEnabled.value = enabled
        prefs.edit().putBoolean("debug_mode_enabled", enabled).apply()
    }

    private val _themeMode = MutableStateFlow(
        try {
            val modeStr = prefs.getString("app_theme_mode", com.breakyuna.noveltranslator.ui.theme.AppThemeMode.SYSTEM.name)
            com.breakyuna.noveltranslator.ui.theme.AppThemeMode.valueOf(modeStr ?: com.breakyuna.noveltranslator.ui.theme.AppThemeMode.SYSTEM.name)
        } catch (_: Exception) {
            com.breakyuna.noveltranslator.ui.theme.AppThemeMode.SYSTEM
        }
    )
    val themeMode: StateFlow<com.breakyuna.noveltranslator.ui.theme.AppThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: com.breakyuna.noveltranslator.ui.theme.AppThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("app_theme_mode", mode.name).apply()
    }

    private val _dontShowContinuousWarning = MutableStateFlow(
        prefs.getBoolean("dont_show_continuous_warning", false)
    )
    val dontShowContinuousWarning: StateFlow<Boolean> = _dontShowContinuousWarning.asStateFlow()

    fun setDontShowContinuousWarning(dontShow: Boolean) {
        _dontShowContinuousWarning.value = dontShow
        prefs.edit().putBoolean("dont_show_continuous_warning", dontShow).apply()
    }

    private val _currentLanguage = MutableStateFlow(
        try {
            val code = prefs.getString("app_language", AppLanguage.CHINESE.code) ?: AppLanguage.CHINESE.code
            AppLanguage.values().find { it.code == code } ?: AppLanguage.CHINESE
        } catch (e: Exception) {
            AppLanguage.CHINESE
        }
    )
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    val currentStrings: StateFlow<AppStrings> = _currentLanguage.map { lang ->
        getAppStrings(lang)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, getAppStrings(_currentLanguage.value))

    fun setLanguage(language: AppLanguage) {
        _currentLanguage.value = language
        prefs.edit().putString("app_language", language.code).apply()
    }

    private val db = AppDatabase.getDatabase(application)
    val providerRepo = ApiProviderRepository(db.apiProviderDao(), ApiKeyCipher())
    private val rawLlmClient = LlmClient()
    val llmClient = RetryingLlmGateway(rawLlmClient)

    private val bookFiles = BookFileManager(application)
    private val bookImporter = BookImporter(db, bookFiles)
    private val editionExporter = EditionExporter(db, bookFiles)
    val bookPlatformRepo = BookPlatformRepository(db, bookFiles)
    private val contextEngineV2 = ContextEngine(db.lexiconV2Dao(), db.memoryDao())
    private val lexiconCandidateAggregator = LexiconCandidateAggregator(db)
    private val bookTranslationEngine = BookTranslationEngine(
        db,
        bookFiles,
        llmClient,
        contextEngineV2,
        providerRepo::getProviderById,
        debugEnabled = { _debugModeEnabled.value }
    )
    private val bookTranslationScheduler = BookTranslationScheduler(bookTranslationEngine)
    private val bookTranslationJobs = ConcurrentHashMap<Long, Job>()
    private val seamlessJobs = ConcurrentHashMap<Long, Job>()
    private val lastSeamlessChapter = ConcurrentHashMap<Long, Long?>()
    /** Full split bodies stay outside Compose state until the user explicitly confirms them. */
    private val pendingChapterSplits = ConcurrentHashMap<Long, List<ParsedChapter>>()

    val shelfBooks: StateFlow<List<ShelfBook>> = bookPlatformRepo.shelf
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val readingHistory: StateFlow<List<ReadingHistoryItem>> = db.readerProgressDao().observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hiddenBooks: StateFlow<List<BookEntity>> = bookPlatformRepo.hiddenBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allPlatformBooks: StateFlow<List<BookEntity>> = bookPlatformRepo.allBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val platformTranslationProjects: StateFlow<List<TranslationProjectV2Entity>> = bookPlatformRepo.allTranslationProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val platformTaskRuns: StateFlow<List<PlatformTranslationRunEntity>> = db.platformTaskDao().observeRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun observePlatformTaskBatches(runId: Long) = db.platformTaskDao().observeBatches(runId)
    fun observePlatformRequestLogs(runId: Long) = db.platformTaskDao().observeRequestLogs(runId)

    val chapterSplitAgent = ChapterSplitAgent(llmClient)
    val termExtractionAgent = TermExtractionAgent(llmClient)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            providerRepo.normalizeBuiltInPresets()
            providerRepo.encryptUnprotectedSecrets()
            db.translationProjectV2Dao().markInterrupted()
            db.platformTaskDao().markInterrupted()
        }
    }

    val allProviders: StateFlow<List<ApiProviderEntity>> = providerRepo.allProviders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val systemLogs: StateFlow<List<com.breakyuna.noveltranslator.core.logger.SystemLogEntry>> = com.breakyuna.noveltranslator.core.logger.SystemLogger.logsFlow

    fun clearSystemLogs() {
        com.breakyuna.noveltranslator.core.logger.SystemLogger.clearLogs()
    }

    fun getSystemLogFile(): File? {
        return com.breakyuna.noveltranslator.core.logger.SystemLogger.getLogFile()
    }

    // UI Notice / Toast
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    fun showMessage(msg: String) {
        _userMessage.value = msg
    }

    fun clearMessage() {
        _userMessage.value = null
    }


    fun createSampleBook() {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable {
                bookImporter.importAcquired(
                    AcquiredBook(
                        title = SampleNovelProvider.sampleTitle,
                        author = SampleNovelProvider.sampleAuthor,
                        chapters = TxtParser.splitIntoChapters(SampleNovelProvider.sampleContent, TxtParser.REGEX_CHINESE)
                            .map { AcquiredChapter(it.title, it.content) },
                        acquisitionType = AcquisitionType.PASTED_TEXT
                    ),
                    language = SampleNovelProvider.sampleSourceLanguage
                )
            }.onSuccess { showMessage("示例小说已加入书架") }
                .onFailure { showMessage("示例导入失败：${it.localizedMessage}") }
        }
    }

    fun importBooksFromUris(
        uris: List<android.net.Uri>,
        originalLanguage: String = "Auto",
        customRegex: String? = null,
        cropTableOfContents: Boolean = false
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            var successCount = 0
            var failCount = 0
            var skippedCount = 0
            val errors = mutableListOf<String>()

            for (uri in uris) {
                val fileName = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: "imported_novel.txt"

                val lowerName = fileName.lowercase()
                if (!lowerName.endsWith(".txt") && !lowerName.endsWith(".epub")) {
                    skippedCount++
                    continue
                }

                val temp = File.createTempFile("book_import_", ".tmp", app.cacheDir)
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取所选文件")
                    bookImporter.import(
                        fileName = fileName,
                        sourceFile = temp,
                        originalLanguage = originalLanguage,
                        customRegex = customRegex,
                        cropTableOfContents = cropTableOfContents
                    )
                    successCount++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failCount++
                    errors.add("$fileName: ${error.localizedMessage}")
                } finally {
                    temp.delete()
                }
            }

            withContext(Dispatchers.Main) {
                if (skippedCount > 0 && successCount == 0 && failCount == 0) {
                    showMessage("导入忽略：仅支持 .txt 与 .epub 格式文件")
                } else if (failCount == 0) {
                    val msg = if (successCount == 1) "已成功加入书架" else "成功批量导入 $successCount 本图书"
                    if (skippedCount > 0) {
                        showMessage("$msg (已自动过滤 $skippedCount 个非 txt/epub 文件)")
                    } else {
                        showMessage(msg)
                    }
                } else if (successCount > 0) {
                    showMessage("成功导入 $successCount 本，失败 $failCount 本")
                } else {
                    showMessage("导入失败：${errors.firstOrNull() ?: "未知错误"}")
                }
            }
        }
    }

    fun importBookFromUri(
        uri: android.net.Uri,
        fileName: String,
        originalLanguage: String = "Auto",
        customRegex: String? = null,
        cropTableOfContents: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val temp = File.createTempFile("book_import_", ".tmp", getApplication<Application>().cacheDir)
            try {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Unable to open the selected file")
                val bookId = bookImporter.import(
                    fileName = fileName,
                    sourceFile = temp,
                    originalLanguage = originalLanguage,
                    customRegex = customRegex,
                    cropTableOfContents = cropTableOfContents
                )
                withContext(Dispatchers.Main) { showMessage("小说已加入书架（Book #$bookId）") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) { showMessage("导入失败：${error.localizedMessage}") }
            } finally {
                temp.delete()
            }
        }
    }

    fun importPastedBook(
        title: String,
        text: String,
        originalLanguage: String = "Auto",
        customRegex: String? = null,
        cropTableOfContents: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable {
                val chapters = TxtParser.splitIntoChapters(
                    fullText = text,
                    regexPattern = customRegex?.takeIf(String::isNotBlank) ?: TxtParser.REGEX_CHINESE,
                    cropTableOfContents = cropTableOfContents
                )
                bookImporter.importAcquired(
                    AcquiredBook(
                        title = title.trim().ifBlank { "粘贴的小说" },
                        chapters = chapters.map { AcquiredChapter(it.title, it.content) },
                        acquisitionType = AcquisitionType.PASTED_TEXT
                    ),
                    originalLanguage
                )
            }.onSuccess { showMessage("小说已加入书架") }
                .onFailure { showMessage("导入失败：${it.localizedMessage}") }
        }
    }

    fun reSplitBookChapters(
        bookId: Long,
        regexPattern: String = TxtParser.REGEX_CHINESE,
        cropTableOfContents: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable {
                bookImporter.reSplit(
                    bookId = bookId,
                    regexPattern = regexPattern,
                    cropTableOfContents = cropTableOfContents
                )
            }.onSuccess { chapterCount ->
                withContext(Dispatchers.Main) {
                    showMessage("已重新分章，共 $chapterCount 章")
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    showMessage("重新分章失败：${error.localizedMessage ?: "无法处理原文"}")
                }
            }
        }
    }

    fun createTranslationEdition(
        bookId: Long,
        sourceEditionId: Long,
        targetLanguage: String,
        editionName: String,
        onCreated: (Long) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable {
                bookPlatformRepo.createTranslationEdition(bookId, sourceEditionId, targetLanguage, editionName)
            }.onSuccess { editionId ->
                withContext(Dispatchers.Main) {
                    showMessage("翻译 Edition 已创建，可在详情页配置翻译方式")
                    onCreated(editionId)
                }
            }.onFailure { showMessage("创建 Edition 失败：${it.localizedMessage}") }
        }
    }

    fun configureEditionTranslation(
        bookId: Long,
        sourceEditionId: Long,
        targetEditionId: Long,
        providerId: Long?,
        modelName: String,
        mode: TranslationMode,
        maxBatchChapters: Int,
        rangeStart: Int? = null,
        rangeEnd: Int? = null,
        seamlessAheadChapters: Int = 5,
        styleGuide: String = "保持文学韵味与专有名词一致性",
        highQualityReview: Boolean = false,
        startImmediately: Boolean = true
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable {
                bookPlatformRepo.createTranslationProject(
                    bookId = bookId,
                    sourceEditionId = sourceEditionId,
                    targetEditionId = targetEditionId,
                    providerId = providerId,
                    modelName = modelName,
                    mode = mode,
                    maxBatchChapters = maxBatchChapters,
                    rangeStart = rangeStart,
                    rangeEnd = rangeEnd,
                    seamlessAheadChapters = seamlessAheadChapters,
                    styleGuide = styleGuide,
                    highQualityReview = highQualityReview
                )
            }.onSuccess { projectId ->
                showMessage("Edition 翻译任务已配置")
                if (startImmediately && providerId != null) {
                    launchBookTranslation(projectId, "翻译任务失败")
                }
            }.onFailure { showMessage("配置翻译失败：${it.localizedMessage}") }
        }
    }

    fun selectReadingEdition(bookId: Long, editionId: Long, onSelected: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable { bookPlatformRepo.selectReadingEdition(bookId, editionId) }
                .onSuccess { withContext(Dispatchers.Main) { onSelected() } }
                .onFailure { showMessage("切换阅读 Edition 失败：${it.localizedMessage}") }
        }
    }

    fun bookImagesDir(bookId: Long): File = bookFiles.sharedImagesDir(bookId)

    fun runBookTranslation(projectId: Long) {
        launchBookTranslation(projectId, "翻译任务失败")
    }

    fun pauseBookTranslation(projectId: Long) {
        viewModelScope.launch(Dispatchers.IO) { bookTranslationScheduler.pause(projectId) }
    }

    fun resumeBookTranslation(projectId: Long) {
        viewModelScope.launch(Dispatchers.IO) { bookTranslationScheduler.resume(projectId) }
    }

    fun cancelBookTranslation(projectId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            bookTranslationScheduler.cancel(projectId)
            bookTranslationJobs[projectId]?.cancel()
            seamlessJobs.remove(projectId)?.cancel()
        }
    }

    private fun launchBookTranslation(projectId: Long, failureLabel: String) {
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                bookTranslationScheduler.run(projectId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    showMessage("$failureLabel：${error.localizedMessage ?: "未知错误"}")
                }
            } finally {
                // Do not remove a newer run that may have been installed after this job was
                // cancelled. The identity check keeps the one-project/one-job invariant intact.
                coroutineContext[Job]?.let { currentJob -> bookTranslationJobs.remove(projectId, currentJob) }
            }
        }
        if (bookTranslationJobs.putIfAbsent(projectId, job) == null) {
            job.start()
        } else {
            job.cancel()
        }
    }

    fun saveReaderProgress(progress: ReaderProgressEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            bookPlatformRepo.saveReaderProgress(progress)
            val chapterId = progress.logicalChapterId ?: return@launch
            if (lastSeamlessChapter.put(progress.bookId, chapterId) == chapterId) return@launch
            // Read the source of truth directly: this callback can run from the reader while the
            // app-level WhileSubscribed StateFlow has no active collector and still holds its
            // initial empty value.
            bookPlatformRepo.getTranslationProjects(progress.bookId)
                .filter { it.translationMode == TranslationMode.SEAMLESS.name }
                .forEach { project ->
                    val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                        try {
                            runCancellable { bookTranslationScheduler.run(project.id) }
                                .onFailure { showMessage("无感翻译缓冲失败：${it.localizedMessage}") }
                        } finally {
                            coroutineContext[Job]?.let { currentJob -> seamlessJobs.remove(project.id, currentJob) }
                        }
                    }
                    val selected = seamlessJobs.compute(project.id) { _, existing ->
                        if (existing?.isActive == true) existing else job
                    }
                    if (selected === job) {
                        job.start()
                    } else {
                        job.cancel()
                    }
                }
        }
    }

    fun saveManualRevision(editionSegmentId: Long, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookPlatformRepo.saveManualRevision(editionSegmentId, text)
            showMessage("修改已保存，并保留修订历史")
        }
    }

    fun renameBook(bookId: Long, title: String) {
        viewModelScope.launch(Dispatchers.IO) { bookPlatformRepo.renameBook(bookId, title) }
    }

    fun updateBookMetadata(bookId: Long, title: String, author: String, description: String, language: String) {
        viewModelScope.launch(Dispatchers.IO) { bookPlatformRepo.updateBookMetadata(bookId, title, author, description, language) }
    }

    fun setBookCover(bookId: Long, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable {
                val name = uri.lastPathSegment ?: "cover.jpg"
                val cover = getApplication<Application>().contentResolver.openInputStream(uri)?.use { bookFiles.saveCover(bookId, name, it) }
                    ?: error("Unable to open cover image")
                bookPlatformRepo.updateCover(bookId, cover.absolutePath)
            }.onFailure { showMessage("更换封面失败：${it.localizedMessage}") }
        }
    }

    fun updateShelfOrderList(orderedIds: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            db.withTransaction {
                orderedIds.forEachIndexed { order, bookId ->
                    bookPlatformRepo.updateShelfOrder(bookId, order)
                }
            }
        }
    }

    fun observeRunsByBook(bookId: Long): Flow<List<PlatformTranslationRunEntity>> =
        bookPlatformRepo.observeRunsByBook(bookId)

    fun observeRunsByProject(projectId: Long): Flow<List<PlatformTranslationRunEntity>> =
        bookPlatformRepo.observeRunsByProject(projectId)

    fun observeBatches(runId: Long): Flow<List<PlatformTranslationBatchEntity>> =
        bookPlatformRepo.observeBatches(runId)

    fun observeRequestLogs(runId: Long): Flow<List<PlatformRequestLogSummary>> =
        bookPlatformRepo.observeRequestLogs(runId)

    suspend fun getRequestLogDetail(id: Long): PlatformRequestLogEntity? =
        db.platformTaskDao().getRequestLog(id)

    fun observeLexicon(projectId: Long): Flow<List<LexiconEntryEntity>> =
        bookPlatformRepo.observeLexicon(projectId)

    fun observeLexiconCandidates(projectId: Long): Flow<List<LexiconCandidateAggregateEntity>> =
        bookPlatformRepo.observeLexiconCandidates(projectId)

    fun observeStoryMemory(projectId: Long): Flow<List<StoryMemoryEntity>> =
        bookPlatformRepo.observeStoryMemory(projectId)

    fun updateTranslationProjectConfig(
        projectId: Long,
        providerId: Long?,
        modelName: String,
        mode: TranslationMode,
        maxBatchChapters: Int,
        rangeStart: Int?,
        rangeEnd: Int?,
        styleGuide: String,
        highQualityReview: Boolean? = null,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = bookPlatformRepo.getTranslationProject(projectId) ?: return@launch
            val updated = existing.copy(
                providerId = providerId,
                modelName = modelName,
                translationMode = mode.name,
                maxBatchChapters = maxBatchChapters.coerceIn(1, 5),
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                styleGuide = styleGuide.trim().take(2_000).ifBlank { "保持文学韵味与专有名词一致性" },
                highQualityReview = highQualityReview ?: existing.highQualityReview,
                updatedAt = System.currentTimeMillis()
            )
            bookPlatformRepo.updateTranslationProject(updated)
            withContext(Dispatchers.Main) {
                showMessage("翻译配置已保存")
                onSuccess()
            }
        }
    }

    fun upsertLexiconEntry(entry: LexiconEntryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            bookPlatformRepo.upsertLexiconEntry(entry)
            withContext(Dispatchers.Main) {
                showMessage("专有术语已保存")
            }
        }
    }

    fun deleteLexiconEntry(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            bookPlatformRepo.deleteLexiconEntry(id)
            withContext(Dispatchers.Main) {
                showMessage("专有术语已删除")
            }
        }
    }

    fun confirmLexiconEntry(entry: LexiconEntryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            bookPlatformRepo.updateLexiconEntry(
                entry.copy(
                    reviewStatus = ReviewStatus.CONFIRMED.name,
                    updatedAt = System.currentTimeMillis()
                )
            )
            withContext(Dispatchers.Main) {
                showMessage("术语已确认，将在后续翻译中生效")
            }
        }
    }

    fun confirmLexiconCandidate(
        candidateId: Long,
        targetTerm: String? = null,
        category: String? = null,
        notes: String? = null,
        overwrite: Boolean = false,
        onResult: (CandidateImportResult) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = db.withTransaction {
                val candidate = db.lexiconCandidateAggregateDao().getById(candidateId)
                    ?: return@withTransaction CandidateImportResult.Failed("Candidate not found")
                if (candidate.state != LexiconCandidateState.ACTIVE.name) {
                    return@withTransaction CandidateImportResult.Failed("Candidate is no longer active")
                }
                val review = LexiconCandidateVoting.review(candidate)
                val existing = db.lexiconV2Dao().getAll(candidate.translationProjectId)
                    .firstOrNull {
                        it.reviewStatus == ReviewStatus.CONFIRMED.name &&
                        LexiconCandidateVoting.normalizeSourceTerm(it.sourceTerm) == candidate.normalizedSourceTerm
                    }
                if (existing != null && !overwrite) {
                    return@withTransaction CandidateImportResult.Conflict(
                        CandidateImportConflict(review, existing)
                    )
                }
                val proposedTarget = targetTerm ?: review.winnerTargetTerm
                val proposedCategory = category ?: review.winnerCategory
                val proposedNotes = notes ?: review.winnerNotes
                if (proposedTarget.isBlank() || !LexiconCandidateImportPlanner.isImportableCategory(proposedCategory)) {
                    return@withTransaction CandidateImportResult.Failed("Candidate has no usable winner")
                }
                if (existing == null) {
                    db.lexiconV2Dao().upsert(
                        LexiconCandidateImportPlanner.createOfficialEntry(
                            review = review,
                            targetTerm = proposedTarget,
                            category = proposedCategory,
                            notes = proposedNotes
                        )
                    )
                } else {
                    db.lexiconV2Dao().update(
                        LexiconCandidateImportPlanner.overwriteOfficialEntry(
                            existing = existing,
                            review = review,
                            targetTerm = proposedTarget,
                            category = proposedCategory,
                            notes = proposedNotes
                        )
                    )
                }
                db.lexiconCandidateAggregateDao().markImported(candidateId)
                CandidateImportResult.Imported(candidateId, overwritten = existing != null)
            }
            when (result) {
                is CandidateImportResult.Imported -> com.breakyuna.noveltranslator.core.logger.SystemLogger.info(
                    "GLOSSARY_REVIEW",
                    "导入候选 id=${result.candidateId}, mode=${if (result.overwritten) "Overwrite" else "Create"}",
                    projectId = db.lexiconCandidateAggregateDao().getById(candidateId)?.translationProjectId
                )
                is CandidateImportResult.Conflict -> com.breakyuna.noveltranslator.core.logger.SystemLogger.warn(
                    "GLOSSARY_REVIEW",
                    "导入候选冲突 id=$candidateId，等待用户选择 Skip / Overwrite",
                    projectId = result.details.candidate.aggregate.translationProjectId
                )
                is CandidateImportResult.Failed -> com.breakyuna.noveltranslator.core.logger.SystemLogger.warn(
                    "GLOSSARY_REVIEW",
                    "导入候选失败 id=$candidateId: ${result.message}"
                )
                is CandidateImportResult.Skipped -> Unit
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun skipLexiconCandidate(candidateId: Long, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            var projectId: Long? = null
            val skipped = db.withTransaction {
                val candidate = db.lexiconCandidateAggregateDao().getById(candidateId)
                    ?: return@withTransaction false
                projectId = candidate.translationProjectId
                if (candidate.state != LexiconCandidateState.ACTIVE.name) return@withTransaction false
                val officialStillExists = db.lexiconV2Dao().getAll(candidate.translationProjectId).any { entry ->
                    entry.reviewStatus == ReviewStatus.CONFIRMED.name &&
                        LexiconCandidateVoting.normalizeSourceTerm(entry.sourceTerm) == candidate.normalizedSourceTerm
                }
                if (!officialStillExists) return@withTransaction false
                db.lexiconCandidateAggregateDao().markImported(candidateId)
                true
            }
            if (skipped) {
                com.breakyuna.noveltranslator.core.logger.SystemLogger.info(
                    "GLOSSARY_REVIEW",
                    "候选 id=$candidateId Skip，保留现有正式术语",
                    projectId = projectId
                )
            } else {
                com.breakyuna.noveltranslator.core.logger.SystemLogger.warn(
                    "GLOSSARY_REVIEW",
                    "候选 id=$candidateId Skip 取消：正式冲突已不存在或候选状态已变化",
                    projectId = projectId
                )
            }
            withContext(Dispatchers.Main) {
                showMessage(
                    if (skipped) "已跳过冲突候选，保留现有正式术语"
                    else "正式术语已变化，候选仍保留，请重新审核"
                )
                onComplete()
            }
        }
    }

    fun ignoreLexiconCandidate(candidateId: Long, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val projectId = db.lexiconCandidateAggregateDao().getById(candidateId)?.translationProjectId
            db.lexiconCandidateAggregateDao().markIgnored(candidateId)
            com.breakyuna.noveltranslator.core.logger.SystemLogger.info(
                "GLOSSARY_REVIEW",
                "候选 id=$candidateId 已 Ignore",
                projectId = projectId
            )
            withContext(Dispatchers.Main) {
                showMessage("候选已忽略，后续扫描不会立即再次提示")
                onComplete()
            }
        }
    }

    fun confirmLexiconCandidatesBatch(candidateIds: List<Long>, onComplete: (Int, Int) -> Unit = { _, _ -> }) {
        if (candidateIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = db.withTransaction {
                var imported = 0
                var conflicts = 0
                candidateIds.distinct().forEach { candidateId ->
                    val candidate = db.lexiconCandidateAggregateDao().getById(candidateId) ?: return@forEach
                    if (candidate.state != LexiconCandidateState.ACTIVE.name) return@forEach
                    val review = LexiconCandidateVoting.review(candidate)
                    val existing = db.lexiconV2Dao().getAll(candidate.translationProjectId)
                        .firstOrNull {
                            it.reviewStatus == ReviewStatus.CONFIRMED.name &&
                            LexiconCandidateVoting.normalizeSourceTerm(it.sourceTerm) == candidate.normalizedSourceTerm
                        }
                    if (existing != null) {
                        conflicts++
                    } else if (
                        review.isHighConfidenceForBatch &&
                        review.winnerTargetTerm.isNotBlank() &&
                        LexiconCandidateImportPlanner.isImportableCategory(review.winnerCategory)
                    ) {
                        db.lexiconV2Dao().upsert(LexiconCandidateImportPlanner.createOfficialEntry(review))
                        db.lexiconCandidateAggregateDao().markImported(candidateId)
                        imported++
                    }
                }
                imported to conflicts
            }
            com.breakyuna.noveltranslator.core.logger.SystemLogger.info(
                "GLOSSARY_REVIEW",
                "批量确认完成: imported=${result.first}, conflicts=${result.second}",
                projectId = candidateIds.firstOrNull()?.let { id ->
                    db.lexiconCandidateAggregateDao().getById(id)?.translationProjectId
                }
            )
            withContext(Dispatchers.Main) {
                showMessage("已确认 ${result.first} 个候选${if (result.second > 0) "，${result.second} 个存在正式术语冲突" else ""}")
                onComplete(result.first, result.second)
            }
        }
    }

    fun retranslateChapter(editionId: Long, logicalChapterId: Long, projectId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            if (projectId != null) {
                val project = bookPlatformRepo.getTranslationProject(projectId)
                if (project?.state == "RUNNING" || project?.state == "PAUSED") {
                    withContext(Dispatchers.Main) {
                        showMessage("请先终止当前翻译任务，再重译单章")
                    }
                    return@launch
                }
            }
            bookPlatformRepo.retranslateChapter(editionId, logicalChapterId)
            withContext(Dispatchers.Main) {
                showMessage("已重置该章节译文，将重新翻译")
            }
            if (projectId != null) {
                runBookTranslation(projectId)
            }
        }
    }

    fun scanGlossaryForBook(
        bookId: Long,
        sourceEditionId: Long,
        targetProjectId: Long?,
        startChapter: Int,
        endChapter: Int,
        provider: ApiProviderEntity,
        targetLanguage: String = "zh",
        onProgress: (String) -> Unit = {},
        onComplete: (Int) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val projectId = requireNotNull(targetProjectId) { "请先创建并选择一个翻译版本，再扫描术语" }
                val book = bookPlatformRepo.getBook(bookId) ?: error("找不到目标书籍")
                val project = bookPlatformRepo.getTranslationProject(projectId)
                    ?: error("找不到目标翻译工程")
                require(project.bookId == bookId && project.sourceEditionId == sourceEditionId) {
                    "翻译工程与当前书籍或原始版本不匹配"
                }
                val effectiveTargetLanguage = project.targetLanguage.ifBlank { targetLanguage }
                val normalizedStart = minOf(startChapter, endChapter).coerceAtLeast(1)
                val normalizedEnd = maxOf(startChapter, endChapter).coerceAtLeast(normalizedStart)
                val allChapters = bookPlatformRepo.getChapters(bookId)
                    .filter { it.chapterIndex in normalizedStart..normalizedEnd }
                if (allChapters.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showMessage("所选范围内未找到章节")
                        onComplete(0)
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    onProgress("正在读取待扫描章节内容 (共 ${allChapters.size} 章)...")
                }
                com.breakyuna.noveltranslator.core.logger.SystemLogger.info(
                    "GLOSSARY_SCAN",
                    "🔍 启动专有术语扫描: Book#$bookId, 范围第 $startChapter ~ $endChapter 章 (${allChapters.size}章)，使用模型: ${provider.name}/${provider.selectedModel}",
                    projectId = projectId
                )
                val confirmedLexicon = db.lexiconV2Dao().getAll(projectId)
                    .filter { it.reviewStatus == ReviewStatus.CONFIRMED.name }
                val existingConfirmedTerms = confirmedLexicon
                    .mapTo(linkedSetOf()) { it.sourceTerm }
                val normalizedConfirmedTerms = confirmedLexicon
                    .mapTo(linkedSetOf()) { LexiconCandidateVoting.normalizeSourceTerm(it.sourceTerm) }
                var count = 0
                var failedWindows = 0
                var readableChapters = 0
                allChapters.forEach { chapter ->
                    val segments = db.bookDao().getLogicalSegments(chapter.id)
                    val editionChapter = db.bookDao().getEditionChapter(sourceEditionId, chapter.id)
                    if (editionChapter != null) {
                        val editionSegments = db.bookDao().getEditionSegments(editionChapter.id).associateBy { it.id }
                        val mappings = db.bookDao().getMappings(segments.map { it.id }).groupBy { it.logicalSegmentId }
                        val chapterText = segments.joinToString("\n") { segment ->
                            mappings[segment.id].orEmpty().sortedBy { it.mappingOrder }
                                .mapNotNull { editionSegments[it.editionSegmentId]?.baseText }
                                .joinToString("\n")
                        }
                        if (chapterText.isNotBlank()) {
                            readableChapters++
                            val windows = splitTermScanWindows(chapterText)
                            windows.forEachIndexed { windowIndex, window ->
                                val label = if (chapterText.length <= TERM_SCAN_WINDOW_CHARS) {
                                    "第 ${chapter.chapterIndex} 章"
                                } else {
                                    "第 ${chapter.chapterIndex} 章片段 ${windowIndex + 1}/${windows.size}"
                                }
                                withContext(Dispatchers.Main) {
                                    onProgress("AI 正在扫描 $label（累计 $count 条候选）...")
                                }
                                val extraction = try {
                                    termExtractionAgent.extractTermsWithUsage(
                                        sampleText = window,
                                        provider = provider,
                                        sourceLanguage = book.originalLanguage,
                                        targetLanguage = effectiveTargetLanguage,
                                        existingTerms = existingConfirmedTerms
                                    )
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (error: Exception) {
                                    failedWindows++
                                    com.breakyuna.noveltranslator.core.logger.SystemLogger.warn(
                                        "GLOSSARY_SCAN",
                                        "窗口失败，已保留此前聚合证据: $label · ${error.message}",
                                        projectId = projectId,
                                        chapterIndex = chapter.chapterIndex
                                    )
                                    return@forEachIndexed
                                }
                                if (!extraction.usage.isSuccess || extraction.parseError != null) {
                                    failedWindows++
                                    com.breakyuna.noveltranslator.core.logger.SystemLogger.warn(
                                        "GLOSSARY_SCAN",
                                        "窗口未产生可用候选: $label",
                                        details = listOfNotNull(
                                            extraction.usage.errorMessage,
                                            extraction.parseError,
                                            extraction.validationRejections.takeIf { it.isNotEmpty() }
                                                ?.joinToString { "${it.sourceTerm}: ${it.reason}" }
                                        ).joinToString("; ").takeIf { it.isNotBlank() },
                                        projectId = projectId,
                                        chapterIndex = chapter.chapterIndex
                                    )
                                    return@forEachIndexed
                                }
                                if (_debugModeEnabled.value) {
                                    com.breakyuna.noveltranslator.core.logger.SystemLogger.debug(
                                        "GLOSSARY_SCAN",
                                        "window=${windowIndex + 1} parsed candidates=${extraction.terms.size}, " +
                                            "validation rejects=${extraction.validationRejections.size}\n" +
                                            "模型原始输出: ${extraction.usage.text.take(12_000)}",
                                        projectId = projectId,
                                        chapterIndex = chapter.chapterIndex
                                    )
                                }
                                val aggregation = lexiconCandidateAggregator.observeWindow(
                                    projectId = projectId,
                                    chapterIndex = chapter.chapterIndex,
                                    sourceText = window,
                                    candidates = extraction.terms
                                        .filterNot { LexiconCandidateVoting.normalizeSourceTerm(it.originalTerm) in normalizedConfirmedTerms }
                                )
                                val rejectionCount = extraction.validationRejections.size + aggregation.rejected.size
                                if (rejectionCount > 0) {
                                    com.breakyuna.noveltranslator.core.logger.SystemLogger.debug(
                                        "GLOSSARY_SCAN",
                                        "validation reject=$rejectionCount" +
                                            (extraction.validationRejections + aggregation.rejected)
                                                .joinToString(prefix = ": ") { "${it.sourceTerm} (${it.reason})" },
                                        projectId = projectId,
                                        chapterIndex = chapter.chapterIndex
                                    )
                                }
                                count = db.lexiconCandidateAggregateDao().getAllActive(projectId).size
                                if (aggregation.updated.isNotEmpty()) {
                                    val detail = aggregation.updated.joinToString("; ") { aggregate ->
                                        val review = LexiconCandidateVoting.review(aggregate)
                                        "${aggregate.sourceTerm} obs=${aggregate.observationCount} " +
                                            "target=${review.winnerTargetTerm} category=${review.winnerCategory}"
                                    }
                                    com.breakyuna.noveltranslator.core.logger.SystemLogger.info(
                                        "GLOSSARY_SCAN",
                                        "聚合更新 window=${windowIndex + 1}: $detail",
                                        projectId = projectId,
                                        chapterIndex = chapter.chapterIndex
                                    )
                                }
                            }
                        }
                    }
                }
                if (readableChapters == 0) {
                    withContext(Dispatchers.Main) {
                        showMessage("未读取到章节文本，无法扫描")
                        onComplete(0)
                    }
                    return@launch
                }
                com.breakyuna.noveltranslator.core.logger.SystemLogger.info(
                    "GLOSSARY_SCAN",
                    "✅ 扫描完成！已处理 $readableChapters 章，共 $count 个待审核候选，失败窗口 $failedWindows",
                    projectId = projectId
                )
                withContext(Dispatchers.Main) {
                    showMessage("专有术语扫描完成，共 $count 个待审核候选")
                    onComplete(count)
                }
            } catch (ce: CancellationException) {
                // A cancelled scan keeps every aggregate persisted by completed windows.
                throw ce
            } catch (e: Exception) {
                com.breakyuna.noveltranslator.core.logger.SystemLogger.error(
                    "GLOSSARY_SCAN",
                    "❌ 专有术语扫描失败: ${e.message}",
                    projectId = targetProjectId
                )
                val retainedCount = targetProjectId?.let { db.lexiconCandidateAggregateDao().getAllActive(it).size } ?: 0
                withContext(Dispatchers.Main) {
                    showMessage("术语扫描失败: ${e.localizedMessage}")
                    onComplete(retainedCount)
                }
            }
        }
    }

    fun removeBooksFromShelf(bookIds: Set<Long>) {
        if (bookIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { bookPlatformRepo.removeFromShelf(it) }
            withContext(Dispatchers.Main) {
                showMessage("已清理 ${bookIds.size} 本图书至归档箱")
            }
        }
    }

    fun deleteBooksPermanently(bookIds: Set<Long>) {
        if (bookIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { bookId ->
                stopBookTranslations(bookId)
                bookPlatformRepo.deletePermanently(bookId)
            }
            withContext(Dispatchers.Main) {
                showMessage("已永久删除 ${bookIds.size} 本图书及相关文件")
            }
        }
    }

    fun moveBook(bookId: Long, direction: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val ordered = shelfBooks.value.toMutableList()
            val index = ordered.indexOfFirst { it.id == bookId }
            if (index < 0 || ordered.isEmpty()) return@launch
            val target = (index + direction).coerceIn(0, ordered.lastIndex)
            if (target != index) {
                val item = ordered.removeAt(index)
                ordered.add(target, item)
                ordered.forEachIndexed { order, book -> bookPlatformRepo.updateShelfOrder(book.id, order) }
            }
        }
    }

    fun removeBookFromShelf(bookId: Long) {
        viewModelScope.launch(Dispatchers.IO) { bookPlatformRepo.removeFromShelf(bookId) }
    }

    fun restoreBookToShelf(bookId: Long) {
        viewModelScope.launch(Dispatchers.IO) { bookPlatformRepo.restoreToShelf(bookId) }
    }

    fun deleteBookPermanently(bookId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            stopBookTranslations(bookId)
            bookPlatformRepo.deletePermanently(bookId)
        }
    }

    /** Stops active jobs before deleting their Book rows and authoritative files. */
    private suspend fun stopBookTranslations(bookId: Long) {
        val projects = bookPlatformRepo.getTranslationProjects(bookId)
        projects.forEach { project ->
            val explicitJob = bookTranslationJobs[project.id]
            val seamlessJob = seamlessJobs[project.id]
            if (explicitJob?.isActive == true || seamlessJob?.isActive == true ||
                project.state == "RUNNING" || project.state == "PAUSED"
            ) {
                bookTranslationScheduler.cancel(project.id)
            }
            bookTranslationJobs.remove(project.id)?.let { job ->
                job.cancel()
                job.join()
            }
            seamlessJobs.remove(project.id)?.let { job ->
                job.cancel()
                job.join()
            }
        }
    }

    fun exportEdition(bookId: Long, editionId: Long, epub: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable {
                if (epub) editionExporter.exportEpub(bookId, editionId) else editionExporter.exportTxt(bookId, editionId)
            }.onSuccess { showMessage("已导出：${it.name}") }
                .onFailure { showMessage("导出失败：${it.localizedMessage}") }
        }
    }

    /** Produces a V2 chapter-split preview without mutating the imported book. */
    fun previewAgentBookChapterSplit(
        bookId: Long,
        provider: ApiProviderEntity,
        onPreview: (List<ChapterSplitPreview>) -> Unit
    ) {
        pendingChapterSplits.remove(bookId)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sourceFile = bookFiles.sourceDir(bookId)
                    .listFiles()
                    ?.filter { it.isFile && !it.name.startsWith(".") }
                    ?.sortedBy { it.name.lowercase() }
                    ?.firstOrNull()
                    ?: error("找不到保留的原始文件")
                require(sourceFile.extension.equals("txt", ignoreCase = true)) {
                    "EPUB 已由解析器完成章节识别；AI 章节识别目前只对 TXT 提供预览"
                }
                val fullText = TxtParser.openDetectedReader(sourceFile).use { it.readText() }
                showMessage("AI 正在分析书籍章节结构...")
                val parsed = chapterSplitAgent.analyzeAndSplit(
                    fullText = fullText,
                    provider = provider,
                    onProgress = { completed, total -> showMessage("AI 章节识别进度 $completed/$total...") }
                )
                require(parsed.isNotEmpty()) { "AI 未识别出有效章节" }
                pendingChapterSplits[bookId] = parsed
                withContext(Dispatchers.Main) {
                    onPreview(parsed.map { chapter ->
                        ChapterSplitPreview(chapter.index, chapter.title, chapter.wordCount)
                    })
                    showMessage("AI 已识别 ${parsed.size} 个候选章节，请确认后应用")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                pendingChapterSplits.remove(bookId)
                withContext(Dispatchers.Main) {
                    showMessage("AI 章节识别失败：${error.localizedMessage ?: "输入或供应商错误"}")
                }
            }
        }
    }

    /** Applies a user-confirmed V2 chapter-split preview atomically. */
    fun discardAgentBookChapterSplit(bookId: Long) {
        pendingChapterSplits.remove(bookId)
    }

    fun applyAgentBookChapterSplit(bookId: Long) {
        val chapters = pendingChapterSplits.remove(bookId)
        if (chapters.isNullOrEmpty()) {
            showMessage("章节识别结果已失效，请重新识别")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val count = bookImporter.applyAiChapterSplit(bookId, chapters)
                withContext(Dispatchers.Main) {
                    showMessage("已应用 AI 章节识别结果，共 $count 章")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    showMessage("应用 AI 章节识别失败：${error.localizedMessage ?: "存储或版本状态错误"}")
                }
            }
        }
    }

    private fun splitTermScanWindows(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        if (text.length <= TERM_SCAN_WINDOW_CHARS) return listOf(text)
        val windows = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(text.length, start + TERM_SCAN_WINDOW_CHARS)
            windows += text.substring(start, end)
            if (end == text.length) break
            start = end - TERM_SCAN_OVERLAP_CHARS
        }
        return windows
    }

    companion object {
        private const val TERM_SCAN_WINDOW_CHARS = 9_000
        private const val TERM_SCAN_OVERLAP_CHARS = 1_000
    }

    // ==========================================
    // API Provider Operations
    // ==========================================

    fun saveProvider(provider: ApiProviderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (provider.id == 0L) {
                    val newId = providerRepo.insertProvider(provider)
                    if (provider.isDefault) {
                        providerRepo.setDefaultProvider(newId)
                    }
                } else {
                    providerRepo.updateProvider(provider)
                    if (provider.isDefault) {
                        providerRepo.setDefaultProvider(provider.id)
                    }
                }
                withContext(Dispatchers.Main) {
                    showMessage("API Provider \"${provider.name}\" saved.")
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    showMessage("Failed to save provider: ${error.localizedMessage ?: "storage error"}")
                }
            }
        }
    }

    fun deleteProvider(providerId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            providerRepo.deleteProviderById(providerId)
            withContext(Dispatchers.Main) {
                showMessage("API Provider removed.")
            }
        }
    }

    fun setDefaultProvider(providerId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            providerRepo.setDefaultProvider(providerId)
            withContext(Dispatchers.Main) {
                showMessage("Default provider updated.")
            }
        }
    }

    fun testProviderConnection(provider: ApiProviderEntity, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = llmClient.executeCompletion(
                provider = provider,
                systemPrompt = "You are a test ping responder.",
                userPrompt = "Say 'Connection OK' and nothing else.",
                temperature = 0.1f,
                operation = "TEST_CONNECTION"
            )
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    onComplete(true, "Success: ${result.text.trim()} (${result.durationMs}ms)")
                } else {
                    onComplete(false, result.errorMessage ?: "Connection failed")
                }
            }
        }
    }

    fun fetchModelsFromEndpoint(provider: ApiProviderEntity, onComplete: (Result<List<String>>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = llmClient.fetchAvailableModels(provider)
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    fun testProvider(provider: ApiProviderEntity, onComplete: (Boolean, String) -> Unit) {
        testProviderConnection(provider, onComplete)
    }

    override fun onCleared() {
        pendingChapterSplits.clear()
        super.onCleared()
    }

    /** Result-style error reporting that never turns structured coroutine cancellation into a UI error. */
    private suspend fun <T> runCancellable(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

}
