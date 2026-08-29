package com.breakyuna.noveltranslator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.breakyuna.noveltranslator.core.agent.ChapterSplitAgent
import com.breakyuna.noveltranslator.core.agent.LexiconCandidateAggregator
import com.breakyuna.noveltranslator.core.agent.TermExtractionAgent
import com.breakyuna.noveltranslator.core.exporter.EpubExporter
import com.breakyuna.noveltranslator.core.exporter.TxtExporter
import com.breakyuna.noveltranslator.core.exporter.EditionExporter
import com.breakyuna.noveltranslator.core.llm.LlmClient
import com.breakyuna.noveltranslator.core.llm.RetryingLlmGateway
import com.breakyuna.noveltranslator.core.llm.executeCompletion
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.core.llm.TranslationControlSignal
import com.breakyuna.noveltranslator.core.parser.*
import com.breakyuna.noveltranslator.core.project.ProjectFileManager
import com.breakyuna.noveltranslator.core.book.BookFileManager
import com.breakyuna.noveltranslator.core.book.BookImporter
import com.breakyuna.noveltranslator.core.book.AcquiredBook
import com.breakyuna.noveltranslator.core.book.AcquiredChapter
import com.breakyuna.noveltranslator.core.translation.BookTranslationEngine
import com.breakyuna.noveltranslator.core.translation.BookTranslationScheduler
import com.breakyuna.noveltranslator.core.translation.ContextEngine
import com.breakyuna.noveltranslator.core.sample.SampleNovelProvider
import com.breakyuna.noveltranslator.core.security.ApiKeyCipher
import com.breakyuna.noveltranslator.core.task.TranslationTaskManager
import com.breakyuna.noveltranslator.core.translator.TranslationJobState
import com.breakyuna.noveltranslator.core.translator.TranslationManager
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.*
import com.breakyuna.noveltranslator.data.repository.*
import com.breakyuna.noveltranslator.ui.i18n.AppLanguage
import com.breakyuna.noveltranslator.ui.i18n.AppStrings
import com.breakyuna.noveltranslator.ui.i18n.getAppStrings
import com.breakyuna.noveltranslator.ui.screens.glossary.ExtractionScope
import android.content.Context
import android.provider.OpenableColumns
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
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
    val projectRepo = ProjectRepository(db.projectDao())
    val chapterRepo = ChapterRepository(db.chapterDao())
    val glossaryRepo = GlossaryRepository(db.glossaryDao())
    val providerRepo = ApiProviderRepository(db.apiProviderDao(), ApiKeyCipher())
    val logRepo = TranslationLogRepository(db.translationLogDao())
    val translationRunRepo = TranslationRunRepository(db.translationRunDao())
    val translationChunkRepo = TranslationChunkRepository(db.translationChunkDao())
    val llmRequestLogRepo = LlmRequestLogRepository(db.llmRequestLogDao())
    private val translationAuditRepo = TranslationAuditRepository(db)
    val chapterSegmentRepo = ChapterSegmentRepository(db.chapterSegmentDao())

    val fileManager = ProjectFileManager(application)
    private val rawLlmClient = LlmClient()
    private val translationControlSignal = TranslationControlSignal()
    val llmClient = RetryingLlmGateway(rawLlmClient, controlSignal = translationControlSignal)
    private val reliableLlmGateway = llmClient

    private val bookFiles = BookFileManager(application)
    private val bookImporter = BookImporter(db, bookFiles)
    private val editionExporter = EditionExporter(db, bookFiles)
    val bookPlatformRepo = BookPlatformRepository(db, bookFiles)
    private val contextEngineV2 = ContextEngine(db.lexiconV2Dao(), db.memoryDao())
    private val lexiconCandidateAggregator = LexiconCandidateAggregator(db)
    private val bookTranslationEngine = BookTranslationEngine(
        db,
        bookFiles,
        reliableLlmGateway,
        contextEngineV2,
        providerRepo::getProviderById,
        debugEnabled = { _debugModeEnabled.value }
    )
    private val bookTranslationScheduler = BookTranslationScheduler(bookTranslationEngine)
    private val bookTranslationJobs = ConcurrentHashMap<Long, Job>()
    private val seamlessJobs = ConcurrentHashMap<Long, Job>()
    private val lastSeamlessChapter = ConcurrentHashMap<Long, Long?>()

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

    val translationManager = TranslationManager(
        projectRepository = projectRepo,
        chapterRepository = chapterRepo,
        glossaryRepository = glossaryRepo,
        translationLogRepository = logRepo,
        translationRunRepository = translationRunRepo,
        translationChunkRepository = translationChunkRepo,
        llmRequestLogRepository = llmRequestLogRepo,
        translationAuditRepository = translationAuditRepo,
        fileManager = fileManager,
        llmClient = reliableLlmGateway,
        controlSignal = translationControlSignal
    )

    val chapterSplitAgent = ChapterSplitAgent(reliableLlmGateway)
    val termExtractionAgent = TermExtractionAgent(reliableLlmGateway)

    val taskManager = TranslationTaskManager(
        context = application,
        projectRepository = projectRepo,
        chapterRepository = chapterRepo,
        glossaryRepository = glossaryRepo,
        translationLogRepository = logRepo,
        llmRequestLogRepository = llmRequestLogRepo,
        fileManager = fileManager,
        llmClient = reliableLlmGateway,
        scope = viewModelScope
    )

    private val _termExtractionState = MutableStateFlow<TermExtractionUiState>(TermExtractionUiState.Idle)
    val termExtractionState: StateFlow<TermExtractionUiState> = _termExtractionState.asStateFlow()
    private var extractionJob: Job? = null
    private var isExtractionPaused = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            providerRepo.removeUnusedLegacyPresets()
            providerRepo.encryptLegacyKeys()
            translationRunRepo.markInFlightInterrupted()
            translationChunkRepo.resetRunningChunks()
            chapterRepo.resetTranslatingStatuses()
            db.translationProjectV2Dao().markInterrupted()
            db.platformTaskDao().markInterrupted()
        }
    }

    // Data flows
    val allProjects: StateFlow<List<ProjectEntity>> = projectRepo.allProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allProviders: StateFlow<List<ApiProviderEntity>> = providerRepo.allProviders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeProjectId = MutableStateFlow<Long?>(null)
    val activeProjectId: StateFlow<Long?> = _activeProjectId.asStateFlow()

    private val _recoverableRun = MutableStateFlow<TranslationRunEntity?>(null)
    val recoverableRun: StateFlow<TranslationRunEntity?> = _recoverableRun.asStateFlow()

    val activeProject: StateFlow<ProjectEntity?> = _activeProjectId.flatMapLatest { id ->
        if (id == null) flowOf(null)
        else projectRepo.getProjectFlowById(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeChapters: StateFlow<List<ChapterEntity>> = _activeProjectId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else chapterRepo.getChaptersByProject(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeGlossary: StateFlow<List<GlossaryEntity>> = _activeProjectId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else glossaryRepo.getGlossaryByProject(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeLogs: StateFlow<List<TranslationLogEntity>> = _activeProjectId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else logRepo.getLogsByProject(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeRequestLogs: StateFlow<List<LlmRequestLogEntity>> = _activeProjectId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else llmRequestLogRepo.getFlowByProject(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTranslationLogs: StateFlow<List<TranslationLogEntity>> = logRepo.allLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allRequestLogs: StateFlow<List<LlmRequestLogEntity>> = llmRequestLogRepo.allLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearAllHistoryLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            logRepo.deleteAllLogs()
            llmRequestLogRepo.deleteAll()
            withContext(Dispatchers.Main) {
                showMessage("已清空所有翻译历史记录")
            }
        }
    }

    private val _selectedChapterId = MutableStateFlow<Long?>(null)
    val selectedChapterId: StateFlow<Long?> = _selectedChapterId.asStateFlow()

    val selectedChapter: StateFlow<ChapterEntity?> = combine(activeChapters, _selectedChapterId) { chapters, id ->
        if (id == null) chapters.firstOrNull() else chapters.find { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val translationState: StateFlow<TranslationJobState> = translationManager.jobState
    val liveLogs: StateFlow<List<LiveLogMessage>> = translationManager.liveLogs
    val systemLogs: StateFlow<List<com.breakyuna.noveltranslator.core.logger.SystemLogEntry>> = com.breakyuna.noveltranslator.core.logger.SystemLogger.logsFlow

    fun clearLiveLogs() {
        translationManager.clearLiveLogs()
    }

    fun clearSystemLogs() {
        com.breakyuna.noveltranslator.core.logger.SystemLogger.clearLogs()
    }

    suspend fun getChapterSegments(chapterId: Long): List<ChapterSegmentEntity> =
        chapterSegmentRepo.getByChapter(chapterId)

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

    fun setActiveProject(projectId: Long?) {
        _activeProjectId.value = projectId
        _selectedChapterId.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val candidate = projectId?.let { translationRunRepo.findLatestResumable(it) }
            if (_activeProjectId.value == projectId) {
                _recoverableRun.value = candidate
            }
            projectId?.let { syncProjectSegments(it) }
        }
    }

    fun resumeRecoverableTranslation() {
        val projectId = _activeProjectId.value ?: return
        val run = _recoverableRun.value ?: return
        val provider = allProviders.value.firstOrNull { it.id == run.providerId }
        if (provider == null) {
            showMessage("无法继续任务：原 Provider 已不存在，请检查设置")
            return
        }
        _recoverableRun.value = null
        translationManager.startTranslation(
            scope = viewModelScope,
            projectId = projectId,
            provider = provider
        )
    }

    fun abandonRecoverableTranslation() {
        val runId = _recoverableRun.value?.id ?: return
        _recoverableRun.value = null
        viewModelScope.launch(Dispatchers.IO) {
            translationRunRepo.updateState(
                id = runId,
                state = TranslationRunState.CANCELLED.name,
                category = "CANCELLED",
                message = "Abandoned by user",
                nextRetryAt = null
            )
        }
    }

    fun setSelectedChapter(chapterId: Long?) {
        _selectedChapterId.value = chapterId
    }

    // ==========================================
    // Project Creation & Import
    // ==========================================

    fun createProjectFromSample() {
        viewModelScope.launch(Dispatchers.IO) {
            val title = SampleNovelProvider.sampleTitle
            val author = SampleNovelProvider.sampleAuthor
            val sampleContent = SampleNovelProvider.sampleContent

            val project = ProjectEntity(
                title = title,
                author = author,
                sourceFileName = "chrono_alchemist_sample.txt",
                fileType = "TXT",
                projectDirPath = "",
                sourceLanguage = SampleNovelProvider.sampleSourceLanguage,
                targetLanguage = SampleNovelProvider.sampleTargetLanguage,
                translationStyle = "Literary Novel",
                totalChapters = 0,
                translatedChapters = 0
            )

            val projectId = projectRepo.insertProject(project)
            val dirPath = fileManager.getProjectDir(projectId).absolutePath
            val projectWithDir = project.copy(id = projectId, projectDirPath = dirPath)
            projectRepo.updateProject(projectWithDir)

            // Save raw sample file
            fileManager.saveRawFile(projectId, "chrono_alchemist_sample.txt", sampleContent.toByteArray(Charsets.UTF_8))

            // Split chapters
            val parsedChapters = TxtParser.splitIntoChapters(sampleContent, TxtParser.REGEX_CHINESE)
            val chapterEntities = parsedChapters.map { parsed ->
                val origName = fileManager.saveOriginalChapter(projectId, parsed.index, parsed.content, parsed.title)
                val transName = fileManager.sanitizeChapterFileName(parsed.index, parsed.title, isTranslated = true)
                ChapterEntity(
                    projectId = projectId,
                    chapterIndex = parsed.index,
                    title = parsed.title,
                    originalFileName = origName,
                    translatedFileName = transName,
                    originalWordCount = parsed.wordCount,
                    status = ChapterStatus.PENDING
                )
            }
            chapterRepo.insertChapters(chapterEntities)

            // Insert sample terms
            val terms = SampleNovelProvider.sampleTerms.map { (orig, trans, note) ->
                GlossaryEntity(
                    projectId = projectId,
                    originalTerm = orig,
                    translatedTerm = trans,
                    category = TermCategory.CHARACTER,
                    notes = note,
                    isAutoExtracted = false
                )
            }
            glossaryRepo.insertTerms(terms)

            val totalWords = chapterEntities.sumOf { it.originalWordCount }
            projectRepo.updateProject(projectWithDir.copy(totalChapters = chapterEntities.size, totalOriginalWords = totalWords))

            withContext(Dispatchers.Main) {
                setActiveProject(projectId)
                showMessage("Demo project 《$title》 loaded successfully!")
            }
        }
    }

    fun createSampleBook() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
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
            runCatching {
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
            runCatching {
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
            runCatching {
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
            runCatching {
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
            runCatching { bookPlatformRepo.selectReadingEdition(bookId, editionId) }
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
        if (bookTranslationJobs[projectId] != null) return
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
                bookTranslationJobs.remove(projectId)
            }
        }
        bookTranslationJobs[projectId] = job
        job.start()
    }

    fun saveReaderProgress(progress: ReaderProgressEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            bookPlatformRepo.saveReaderProgress(progress)
            val chapterId = progress.logicalChapterId ?: return@launch
            if (lastSeamlessChapter.put(progress.bookId, chapterId) == chapterId) return@launch
            platformTranslationProjects.value
                .filter { it.bookId == progress.bookId && it.translationMode == TranslationMode.SEAMLESS.name }
                .forEach { project ->
                    if (seamlessJobs[project.id]?.isActive != true) {
                        seamlessJobs[project.id] = viewModelScope.launch(Dispatchers.IO) {
                            runCatching { bookTranslationScheduler.run(project.id) }
                                .onFailure { showMessage("无感翻译缓冲失败：${it.localizedMessage}") }
                        }
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
            runCatching {
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

    fun retranslateChapter(bookId: Long, editionId: Long, logicalChapterId: Long, projectId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
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
                requireNotNull(targetProjectId) { "请先创建并选择一个翻译版本，再扫描术语" }
                val book = bookPlatformRepo.getBook(bookId) ?: error("找不到目标书籍")
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
                    projectId = targetProjectId
                )
                val confirmedLexicon = db.lexiconV2Dao().getAll(targetProjectId)
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
                                        projectId = targetProjectId,
                                        sampleText = window,
                                        provider = provider,
                                        sourceLanguage = book.originalLanguage,
                                        targetLanguage = targetLanguage,
                                        existingTerms = existingConfirmedTerms
                                    )
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (error: Exception) {
                                    failedWindows++
                                    com.breakyuna.noveltranslator.core.logger.SystemLogger.warn(
                                        "GLOSSARY_SCAN",
                                        "窗口失败，已保留此前聚合证据: $label · ${error.message}",
                                        projectId = targetProjectId,
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
                                        projectId = targetProjectId,
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
                                        projectId = targetProjectId,
                                        chapterIndex = chapter.chapterIndex
                                    )
                                }
                                val aggregation = lexiconCandidateAggregator.observeWindow(
                                    projectId = targetProjectId,
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
                                        projectId = targetProjectId,
                                        chapterIndex = chapter.chapterIndex
                                    )
                                }
                                count = db.lexiconCandidateAggregateDao().getAllActive(targetProjectId).size
                                if (aggregation.updated.isNotEmpty()) {
                                    val detail = aggregation.updated.joinToString("; ") { aggregate ->
                                        val review = LexiconCandidateVoting.review(aggregate)
                                        "${aggregate.sourceTerm} obs=${aggregate.observationCount} " +
                                            "target=${review.winnerTargetTerm} category=${review.winnerCategory}"
                                    }
                                    com.breakyuna.noveltranslator.core.logger.SystemLogger.info(
                                        "GLOSSARY_SCAN",
                                        "聚合更新 window=${windowIndex + 1}: $detail",
                                        projectId = targetProjectId,
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
                    projectId = targetProjectId
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
            bookIds.forEach { bookPlatformRepo.deletePermanently(it) }
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
        viewModelScope.launch(Dispatchers.IO) { bookPlatformRepo.deletePermanently(bookId) }
    }

    fun exportEdition(bookId: Long, editionId: Long, epub: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (epub) editionExporter.exportEpub(bookId, editionId) else editionExporter.exportTxt(bookId, editionId)
            }.onSuccess { showMessage("已导出：${it.name}") }
                .onFailure { showMessage("导出失败：${it.localizedMessage}") }
        }
    }

    fun importFile(
        fileName: String,
        fileBytes: ByteArray,
        sourceLang: String = "Auto",
        targetLang: String = "Chinese",
        style: String = "Literary Novel",
        customRegex: String? = null,
        cropTableOfContents: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            performImport(fileName, sourceLang, targetLang, style, customRegex, cropTableOfContents, fileBytes) { projectId ->
                fileManager.saveRawFile(projectId, fileName, fileBytes)
            }
        }
    }

    fun importFileFromUri(
        uri: android.net.Uri,
        fileName: String,
        sourceLang: String = "Auto",
        targetLang: String = "Chinese",
        style: String = "Literary Novel",
        customRegex: String? = null,
        cropTableOfContents: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            performImport(fileName, sourceLang, targetLang, style, customRegex, cropTableOfContents, null) { projectId ->
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    fileManager.saveRawFile(projectId, fileName, input, MAX_IMPORT_BYTES.toLong())
                } ?: error("Unable to open the selected file")
            }
        }
    }

    private suspend fun performImport(
        fileName: String,
        sourceLang: String,
        targetLang: String,
        style: String,
        customRegex: String?,
        cropTableOfContents: Boolean,
        txtBytes: ByteArray?,
        saveRawFile: (Long) -> File
    ) {
        var createdProjectId: Long? = null
        try {
            require(txtBytes == null || txtBytes.size <= MAX_IMPORT_BYTES) { "File exceeds the 100 MB import limit" }
            val isEpub = fileName.endsWith(".epub", ignoreCase = true) || fileName.endsWith(".equb", ignoreCase = true)
            val fileType = if (isEpub) "EPUB" else "TXT"
            val defaultTitle = fileName.substringBeforeLast(".").trim().take(300).ifBlank { "Imported novel" }
            val tempProject = ProjectEntity(
                title = defaultTitle,
                author = "Unknown",
                sourceFileName = fileName,
                fileType = fileType,
                projectDirPath = "",
                sourceLanguage = sourceLang.trim().take(80).ifBlank { "Auto" },
                targetLanguage = targetLang.trim().take(80).ifBlank { "Chinese" },
                translationStyle = style.trim().take(120).ifBlank { "Literary Novel" }
            )
            val projectId = projectRepo.insertProject(tempProject)
            createdProjectId = projectId
            val rawFile = saveRawFile(projectId)
            var parsedTitle = defaultTitle
            var parsedAuthor = "Unknown"
            val parsedChapters = if (isEpub) {
                val epubBook = EpubParser.parseEpubFile(
                    rawFile,
                    fileManager.getImagesDir(projectId),
                    cropTableOfContents = cropTableOfContents
                )
                if (epubBook.title.isNotBlank()) parsedTitle = epubBook.title.trim().take(300)
                if (epubBook.author.isNotBlank()) parsedAuthor = epubBook.author.trim().take(300)
                epubBook.chapters
            } else {
                val bytes = txtBytes ?: rawFile.readBytes()
                val (text, _) = TxtParser.detectCharsetAndRead(bytes)
                TxtParser.splitIntoChapters(
                    fullText = text,
                    regexPattern = customRegex?.takeIf { it.isNotBlank() } ?: TxtParser.REGEX_CHINESE,
                    cropTableOfContents = cropTableOfContents
                )
            }
            require(parsedChapters.isNotEmpty()) { "No readable chapters were found in this file" }
            val chapterEntities = parsedChapters.map { parsed ->
                ChapterEntity(
                    projectId = projectId,
                    chapterIndex = parsed.index,
                    title = parsed.title,
                    originalFileName = fileManager.saveOriginalChapter(projectId, parsed.index, parsed.content, parsed.title),
                    translatedFileName = fileManager.sanitizeChapterFileName(parsed.index, parsed.title, isTranslated = true),
                    originalWordCount = parsed.wordCount,
                    status = ChapterStatus.PENDING
                )
            }
            chapterRepo.insertChapters(chapterEntities)
            projectRepo.updateProject(
                tempProject.copy(
                    id = projectId,
                    title = parsedTitle,
                    author = parsedAuthor,
                    projectDirPath = fileManager.getProjectDir(projectId).absolutePath,
                    totalChapters = chapterEntities.size,
                    totalOriginalWords = chapterEntities.sumOf { it.originalWordCount }
                )
            )
            withContext(Dispatchers.Main) {
                setActiveProject(projectId)
                showMessage("Successfully imported \"$parsedTitle\" with ${chapterEntities.size} chapters!")
            }
        } catch (error: Exception) {
            createdProjectId?.let { projectId ->
                projectRepo.deleteProjectById(projectId)
                fileManager.deleteProjectFiles(projectId)
            }
            withContext(Dispatchers.Main) {
                showMessage("Import failed: ${error.localizedMessage}")
            }
        }
    }

    fun deleteProject(projectId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            projectRepo.deleteProjectById(projectId)
            fileManager.deleteProjectFiles(projectId)
            if (_activeProjectId.value == projectId) {
                withContext(Dispatchers.Main) {
                    _activeProjectId.value = null
                    _selectedChapterId.value = null
                }
            }
            withContext(Dispatchers.Main) {
                showMessage("Project deleted.")
            }
        }
    }

    // ==========================================
    // Chapter Split Management & Agent Splitter
    // ==========================================

    fun reSplitChapters(
        projectId: Long,
        regexPattern: String,
        cropTableOfContents: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
            val project = projectRepo.getProjectById(projectId) ?: return@launch
            val rawFile = fileManager.getRawDir(projectId).listFiles()?.firstOrNull() ?: return@launch
            val rawBytes = rawFile.readBytes()

            val (fullText, _) = TxtParser.detectCharsetAndRead(rawBytes)
            val parsedChapters = TxtParser.splitIntoChapters(
                fullText = fullText,
                regexPattern = regexPattern,
                cropTableOfContents = cropTableOfContents
            )

            require(parsedChapters.isNotEmpty()) { "No chapters matched this pattern" }
            val chapterEntities = replaceChaptersSafely(project, parsedChapters)

            withContext(Dispatchers.Main) {
                showMessage("Chapters re-split into ${chapterEntities.size} parts.")
            }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showMessage("Chapter split failed: ${e.localizedMessage ?: "invalid input"}")
                }
            }
        }
    }

    fun runAgentChapterSplit(projectId: Long, provider: ApiProviderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val usage = mutableListOf<LlmResult>()
            try {
            showMessage("Agent is analyzing book structure...")
            val project = projectRepo.getProjectById(projectId) ?: return@launch
            requireCompatibleCurrency(project, provider)
            val rawFile = fileManager.getRawDir(projectId).listFiles()?.firstOrNull() ?: return@launch
            val (fullText, _) = TxtParser.detectCharsetAndRead(rawFile.readBytes())

            val parsedChapters = chapterSplitAgent.analyzeAndSplit(
                fullText = fullText,
                provider = provider,
                onProgress = { completed, total -> showMessage("AI splitter analyzed window $completed/$total...") },
                onUsage = { usage += it }
            )

            if (parsedChapters.isEmpty()) {
                withContext(Dispatchers.Main) { showMessage("AI splitter did not find any valid chapters.") }
                return@launch
            }
            val chapterEntities = replaceChaptersSafely(project, parsedChapters)
            recordAgentUsage(projectId, "AI chapter split", provider, usage)

            withContext(Dispatchers.Main) {
                showMessage("Agent identified ${chapterEntities.size} chapters successfully!")
            }
            } catch (e: Exception) {
                if (usage.isNotEmpty()) {
                    try {
                        recordAgentUsage(
                            projectId,
                            "AI chapter split (partial)",
                            provider,
                            usage,
                            operationSuccessful = false
                        )
                    } catch (_: Exception) {
                        // Preserve the original splitter failure for the user.
                    }
                }
                withContext(Dispatchers.Main) {
            showMessage("AI chapter split failed: ${e.localizedMessage ?: "provider or input error"}")
                }
            }
        }
    }

    /** Produces a V2 chapter-split preview without mutating the imported book. */
    fun previewAgentBookChapterSplit(
        bookId: Long,
        provider: ApiProviderEntity,
        onPreview: (List<ParsedChapter>) -> Unit
    ) {
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
                withContext(Dispatchers.Main) {
                    onPreview(parsed)
                    showMessage("AI 已识别 ${parsed.size} 个候选章节，请确认后应用")
                }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    showMessage("AI 章节识别失败：${error.localizedMessage ?: "输入或供应商错误"}")
                }
            }
        }
    }

    /** Applies a user-confirmed V2 chapter-split preview atomically. */
    fun applyAgentBookChapterSplit(bookId: Long, chapters: List<ParsedChapter>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val count = bookImporter.applyAiChapterSplit(bookId, chapters)
                withContext(Dispatchers.Main) {
                    showMessage("已应用 AI 章节识别结果，共 $count 章")
                }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    showMessage("应用 AI 章节识别失败：${error.localizedMessage ?: "存储或版本状态错误"}")
                }
            }
        }
    }

    private suspend fun replaceChaptersSafely(
        project: ProjectEntity,
        parsedChapters: List<ParsedChapter>
    ): List<ChapterEntity> {
        val transaction = fileManager.beginChapterFileTransaction(project.id)
        try {
            val chapterEntities = parsedChapters.map { parsed ->
                val origName = fileManager.saveOriginalChapter(transaction, parsed.index, parsed.content, parsed.title)
                val transName = fileManager.sanitizeChapterFileName(parsed.index, parsed.title, isTranslated = true)
                ChapterEntity(
                    projectId = project.id,
                    chapterIndex = parsed.index,
                    title = parsed.title,
                    originalFileName = origName,
                    translatedFileName = transName,
                    originalWordCount = parsed.wordCount,
                    status = ChapterStatus.PENDING
                )
            }
            fileManager.commitChapterFileTransaction(transaction)
            val totalWords = chapterEntities.sumOf { it.originalWordCount }
            db.withTransaction {
                chapterRepo.replaceChapters(project.id, chapterEntities)
                projectRepo.updateProject(
                    project.copy(
                        totalChapters = chapterEntities.size,
                        translatedChapters = 0,
                        totalOriginalWords = totalWords,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            fileManager.finalizeChapterFileTransaction(transaction)
            return chapterEntities
        } catch (error: Exception) {
            fileManager.rollbackChapterFileTransaction(transaction)
            throw error
        }
    }

    fun extractTermsFromChapter(chapterId: Long, provider: ApiProviderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chapter = chapterRepo.getChapterById(chapterId) ?: return@launch
                val projectId = chapter.projectId
                val project = projectRepo.getProjectById(projectId) ?: return@launch
                requireCompatibleCurrency(project, provider)
                val origText = fileManager.readOriginalChapter(projectId, chapter.originalFileName)
                val transText = fileManager.readTranslatedChapter(projectId, chapter.translatedFileName)
                val sample = if (origText.isNotBlank()) origText else transText

                if (sample.isBlank()) {
                    withContext(Dispatchers.Main) { showMessage("Chapter content is empty.") }
                    return@launch
                }

                showMessage("Extracting terms from chapter ${chapter.chapterIndex}...")
                val currentTerms = glossaryRepo.getGlossaryListByProject(projectId)
                val confirmedTerms = currentTerms.filter { it.reviewStatus == ReviewStatus.CONFIRMED.name }
                var observedCount = 0
                splitTermScanWindows(sample).forEachIndexed { windowIndex, window ->
                    val extraction = termExtractionAgent.extractTermsWithUsage(
                        projectId = projectId,
                        sampleText = window,
                        provider = provider,
                        sourceLanguage = project.sourceLanguage,
                        targetLanguage = project.targetLanguage,
                        existingTerms = confirmedTerms.map { it.originalTerm }
                    )
                    logTermExtractionDebug(projectId, chapter.chapterIndex, windowIndex + 1, extraction)
                    recordAgentUsage(
                        projectId,
                        "Chapter ${chapter.chapterIndex} terminology extraction part ${windowIndex + 1}",
                        provider,
                        listOf(extraction.usage),
                        operationSuccessful = extraction.parseError == null && extraction.usage.isSuccess
                    )
                    check(extraction.usage.isSuccess && extraction.usage.text.isNotBlank()) {
                        extraction.usage.errorMessage ?: "Terminology extraction returned no usable response"
                    }
                    check(extraction.parseError == null) { extraction.parseError ?: "Invalid terminology JSON" }
                    val updatedCandidates = glossaryRepo.observeAiCandidates(
                        projectId = projectId,
                        chapterIndex = chapter.chapterIndex,
                        observations = extraction.terms
                    )
                    observedCount += updatedCandidates.size
                    logLegacyCandidateUpdates(projectId, chapter.chapterIndex, windowIndex + 1, updatedCandidates)
                }

                if (observedCount > 0) {
                    withContext(Dispatchers.Main) {
                        showMessage("Recorded $observedCount terminology observations from chapter ${chapter.chapterIndex} for review.")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showMessage("No new unique terms found in chapter ${chapter.chapterIndex}.")
                    }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    showMessage("Terminology extraction failed: ${error.localizedMessage ?: "provider or format error"}")
                }
            }
        }
    }

    // ==========================================
    // Glossary / Terminology Operations
    // ==========================================

    fun addGlossaryTerm(term: GlossaryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            glossaryRepo.insertTerm(term)
        }
    }

    fun updateGlossaryTerm(term: GlossaryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            glossaryRepo.updateTerm(term)
        }
    }

    fun approveGlossaryTerm(term: GlossaryEntity) {
        updateGlossaryTerm(LegacyGlossaryCandidateVoting.confirm(term))
    }

    fun deleteGlossaryTerm(termId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val term = glossaryRepo.getTermById(termId)
            if (
                term != null &&
                term.reviewStatus == ReviewStatus.CANDIDATE.name &&
                term.source == LexiconSource.AI.name
            ) {
                glossaryRepo.updateTerm(LegacyGlossaryCandidateVoting.markIgnored(term))
            } else {
                glossaryRepo.deleteTermById(termId)
            }
        }
    }

    fun startControlledTermExtraction(
        projectId: Long,
        provider: ApiProviderEntity,
        scopeType: ExtractionScope,
        firstN: Int? = null,
        startChapter: Int = 1,
        endChapter: Int = 1000
    ) {
        extractionJob?.cancel()
        isExtractionPaused = false

        extractionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val project = projectRepo.getProjectById(projectId) ?: run {
                    _termExtractionState.value = TermExtractionUiState.Error("Project not found")
                    return@launch
                }
                requireCompatibleCurrency(project, provider)

                var chapters = chapterRepo.getChaptersListByProject(projectId).sortedBy { it.chapterIndex }
                if (chapters.isEmpty()) {
                    _termExtractionState.value = TermExtractionUiState.Error("No chapters available in project")
                    return@launch
                }

                // Filter chapters by selected scope
                chapters = when (scopeType) {
                    ExtractionScope.FIRST_5 -> chapters.take(5)
                    ExtractionScope.FIRST_20 -> chapters.take(20)
                    ExtractionScope.CUSTOM_RANGE -> chapters.filter { it.chapterIndex in startChapter..endChapter }
                    ExtractionScope.ALL -> chapters
                }

                if (chapters.isEmpty()) {
                    _termExtractionState.value = TermExtractionUiState.Error("No chapters match the selected scope")
                    return@launch
                }

                val knownTerms = glossaryRepo.getGlossaryListByProject(projectId).toMutableList()
                val confirmedTerms = knownTerms.filter { it.reviewStatus == ReviewStatus.CONFIRMED.name }
                val discoveredCandidates = knownTerms.asSequence()
                    .filter {
                        it.reviewStatus == ReviewStatus.CANDIDATE.name &&
                            !LegacyGlossaryCandidateVoting.isIgnored(it)
                    }
                    .associateByTo(linkedMapOf()) { normalizeTerm(it.originalTerm) }

                // Window samples with chapter metadata
                data class ScanWindow(val chapter: ChapterEntity, val text: String, val index: Int)
                val allWindows = mutableListOf<ScanWindow>()
                for (chapter in chapters) {
                    val originalText = fileManager.readOriginalChapter(projectId, chapter.originalFileName)
                    val windows = splitTermScanWindows(originalText)
                    for (w in windows) {
                        allWindows.add(
                            ScanWindow(
                                chapter = chapter,
                                text = w,
                                index = allWindows.size + 1
                            )
                        )
                    }
                }

                val totalWindows = allWindows.size
                var totalPromptTokens = 0L
                var totalCompletionTokens = 0L
                var totalEstimatedCost = 0.0

                _termExtractionState.value = TermExtractionUiState.Scanning(
                    projectId = projectId,
                    currentChapterIndex = chapters.first().chapterIndex,
                    currentChapterTitle = chapters.first().title,
                    currentWindowIndex = 1,
                    totalWindows = totalWindows,
                    discoveredTerms = discoveredCandidates.values.map(::TermExtractionCandidate),
                    promptTokens = 0L,
                    completionTokens = 0L,
                    estimatedCost = 0.0,
                    currency = provider.currency,
                    isPaused = false
                )

                for ((windowIdx, windowItem) in allWindows.withIndex()) {
                    while (isExtractionPaused && isActive) {
                        delay(200)
                    }
                    if (!isActive) break

                    _termExtractionState.value = TermExtractionUiState.Scanning(
                        projectId = projectId,
                        currentChapterIndex = windowItem.chapter.chapterIndex,
                        currentChapterTitle = windowItem.chapter.title,
                        currentWindowIndex = windowIdx + 1,
                        totalWindows = totalWindows,
                        discoveredTerms = discoveredCandidates.values.map(::TermExtractionCandidate),
                        promptTokens = totalPromptTokens,
                        completionTokens = totalCompletionTokens,
                        estimatedCost = totalEstimatedCost,
                        currency = provider.currency,
                        isPaused = isExtractionPaused
                    )

                    val extraction = termExtractionAgent.extractTermsWithUsage(
                        projectId = projectId,
                        sampleText = windowItem.text,
                        provider = provider,
                        sourceLanguage = project.sourceLanguage,
                        targetLanguage = project.targetLanguage,
                        existingTerms = confirmedTerms.map { it.originalTerm }
                    )
                    logTermExtractionDebug(
                        projectId,
                        windowItem.chapter.chapterIndex,
                        windowIdx + 1,
                        extraction
                    )

                    val updatedCandidates = glossaryRepo.observeAiCandidates(
                        projectId = projectId,
                        chapterIndex = windowItem.chapter.chapterIndex,
                        observations = extraction.terms
                    )
                    updatedCandidates.forEach { term ->
                        discoveredCandidates[normalizeTerm(term.originalTerm)] = term
                    }
                    logLegacyCandidateUpdates(
                        projectId,
                        windowItem.chapter.chapterIndex,
                        windowIdx + 1,
                        updatedCandidates
                    )

                    totalPromptTokens += extraction.usage.promptTokens
                    totalCompletionTokens += extraction.usage.completionTokens
                    val cost = TokenCalculator.calculateCost(
                        extraction.usage.promptTokens,
                        extraction.usage.completionTokens,
                        provider.inputPricePerMillion,
                        provider.outputPricePerMillion
                    )
                    totalEstimatedCost += cost

                    // Keep Stop/Review in sync with evidence already persisted for this window.
                    _termExtractionState.value = TermExtractionUiState.Scanning(
                        projectId = projectId,
                        currentChapterIndex = windowItem.chapter.chapterIndex,
                        currentChapterTitle = windowItem.chapter.title,
                        currentWindowIndex = windowIdx + 1,
                        totalWindows = totalWindows,
                        discoveredTerms = discoveredCandidates.values.map(::TermExtractionCandidate),
                        promptTokens = totalPromptTokens,
                        completionTokens = totalCompletionTokens,
                        estimatedCost = totalEstimatedCost,
                        currency = provider.currency,
                        isPaused = isExtractionPaused
                    )

                    recordAgentUsage(
                        projectId,
                        "Terminology scan ${windowIdx + 1}/$totalWindows",
                        provider,
                        listOf(extraction.usage),
                        operationSuccessful = extraction.parseError == null && extraction.usage.isSuccess
                    )
                }

                // Completed scanning - go to Review
                _termExtractionState.value = TermExtractionUiState.Review(
                    projectId = projectId,
                    candidates = discoveredCandidates.values.map(::TermExtractionCandidate),
                    promptTokens = totalPromptTokens,
                    completionTokens = totalCompletionTokens,
                    estimatedCost = totalEstimatedCost,
                    currency = provider.currency
                )
            } catch (ce: CancellationException) {
                // Cancelled or stopped early by user
            } catch (e: Exception) {
                _termExtractionState.value = TermExtractionUiState.Error(
                    e.localizedMessage ?: "Terminology scan failed"
                )
            }
        }
    }

    fun pauseTermExtraction() {
        isExtractionPaused = true
        val current = _termExtractionState.value
        if (current is TermExtractionUiState.Scanning) {
            _termExtractionState.value = current.copy(isPaused = true)
        }
    }

    fun resumeTermExtraction() {
        isExtractionPaused = false
        val current = _termExtractionState.value
        if (current is TermExtractionUiState.Scanning) {
            _termExtractionState.value = current.copy(isPaused = false)
        }
    }

    fun stopTermExtraction() {
        extractionJob?.cancel()
        val current = _termExtractionState.value
        if (current is TermExtractionUiState.Scanning) {
            _termExtractionState.value = TermExtractionUiState.Review(
                projectId = current.projectId,
                candidates = current.discoveredTerms,
                promptTokens = current.promptTokens,
                completionTokens = current.completionTokens,
                estimatedCost = current.estimatedCost,
                currency = current.currency
            )
        } else {
            _termExtractionState.value = TermExtractionUiState.Idle
        }
    }

    fun saveExtractedTerms(projectId: Long, terms: List<GlossaryEntity>) {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = glossaryRepo.getGlossaryListByProject(projectId)
            var confirmedCount = 0
            terms.forEach { selected ->
                val persisted = selected.id.takeIf { it > 0L }?.let { glossaryRepo.getTermById(it) }
                    ?: rows.firstOrNull {
                        normalizeTerm(it.originalTerm) == normalizeTerm(selected.originalTerm)
                    }
                if (persisted?.reviewStatus == ReviewStatus.CANDIDATE.name) {
                    glossaryRepo.updateTerm(LegacyGlossaryCandidateVoting.confirm(persisted))
                    confirmedCount++
                }
            }
            withContext(Dispatchers.Main) {
                showMessage("Confirmed $confirmedCount terminology candidates.")
            }
        }
    }

    fun dismissTermExtraction() {
        extractionJob?.cancel()
        _termExtractionState.value = TermExtractionUiState.Idle
    }

    fun runAutoExtractTerms(projectId: Long, provider: ApiProviderEntity) {
        startControlledTermExtraction(projectId, provider, ExtractionScope.ALL)
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

    private fun logTermExtractionDebug(
        projectId: Long,
        chapterIndex: Int,
        windowIndex: Int,
        extraction: TermExtractionAgent.ExtractionResult
    ) {
        if (!_debugModeEnabled.value) return
        val rejectionDetails = extraction.validationRejections
            .joinToString("; ") { "${it.sourceTerm}: ${it.reason}" }
            .takeIf(String::isNotBlank)
        com.breakyuna.noveltranslator.core.logger.SystemLogger.debug(
            "GLOSSARY_SCAN",
            "chapter=$chapterIndex window=$windowIndex parsed=${extraction.terms.size} " +
                "validationRejects=${extraction.validationRejections.size}",
            details = listOfNotNull(
                extraction.parseError,
                rejectionDetails,
                extraction.usage.text.take(12_000).takeIf(String::isNotBlank)
                    ?.let { "modelOutput=$it" }
            ).joinToString("\n").takeIf(String::isNotBlank),
            projectId = projectId,
            chapterIndex = chapterIndex
        )
    }

    private fun logLegacyCandidateUpdates(
        projectId: Long,
        chapterIndex: Int,
        windowIndex: Int,
        updated: List<GlossaryEntity>
    ) {
        if (!_debugModeEnabled.value || updated.isEmpty()) return
        val details = updated.joinToString("; ") { term ->
            val evidence = LegacyGlossaryCandidateVoting.decode(term)
            "${term.originalTerm} obs=${evidence?.observationCount ?: 1} " +
                "winner=${term.translatedTerm}/${term.category.name}"
        }
        com.breakyuna.noveltranslator.core.logger.SystemLogger.debug(
            "GLOSSARY_SCAN",
            "legacy aggregate updates=${updated.size} window=$windowIndex",
            details = details,
            projectId = projectId,
            chapterIndex = chapterIndex
        )
    }

    private fun requireCompatibleCurrency(project: ProjectEntity, provider: ApiProviderEntity) {
        if (project.totalCost <= 0.0) return
        require(
            project.costCurrency.isNotBlank() &&
                !project.costCurrency.equals("UNKNOWN", ignoreCase = true) &&
                !project.costCurrency.equals("MIXED", ignoreCase = true) &&
                project.costCurrency.equals(provider.currency, ignoreCase = true)
        ) {
            "This project has historical cost data with an unknown or different currency. Reconcile the project cost before another AI request."
        }
    }

    private suspend fun recordAgentUsage(
        projectId: Long,
        title: String,
        provider: ApiProviderEntity,
        results: List<LlmResult>,
        operationSuccessful: Boolean = results.all { it.isSuccess }
    ) {
        results.forEach { aggregate ->
            val attempts = aggregate.attempts.ifEmpty { listOf(com.breakyuna.noveltranslator.core.llm.LlmAttempt(1, aggregate)) }
            attempts.forEach { attempt ->
                val item = attempt.result
                val itemCost = TokenCalculator.calculateCost(
                    item.promptTokens,
                    item.completionTokens,
                    provider.inputPricePerMillion,
                    provider.outputPricePerMillion
                )
                llmRequestLogRepo.insert(
                    LlmRequestLogEntity(
                        projectId = projectId,
                        attemptNumber = attempt.attemptNumber,
                        operation = title.take(120),
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
                        estimatedCost = itemCost,
                        durationMs = item.durationMs,
                        httpStatus = item.httpStatus,
                        errorCategory = item.errorCategory?.name,
                        errorMessage = item.errorMessage?.take(500),
                        finishReason = item.finishReason,
                        requestId = item.requestId,
                        isSuccess = item.isSuccess
                    )
                )
            }
        }
        val promptTokens = results.sumOf { it.promptTokens }
        val completionTokens = results.sumOf { it.completionTokens }
        val cost = TokenCalculator.calculateCost(
            promptTokens,
            completionTokens,
            provider.inputPricePerMillion,
            provider.outputPricePerMillion
        )
        logRepo.insertLog(
            TranslationLogEntity(
                projectId = projectId,
                chapterIndex = 0,
                chapterTitle = title,
                modelName = provider.selectedModel,
                providerName = provider.name,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = promptTokens + completionTokens,
                estimatedCost = cost,
                currency = provider.currency,
                durationMs = results.sumOf { it.durationMs },
                isSuccess = operationSuccessful && results.all { it.isSuccess },
                message = title
            )
        )
        refreshProjectStatsFromLogs(projectId, provider.currency)
    }

    private suspend fun refreshProjectStatsFromLogs(projectId: Long, fallbackCurrency: String) {
        val chapters = chapterRepo.getChaptersListByProject(projectId)
        val logs = logRepo.getLogsListByProject(projectId)
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
        projectRepo.updateProjectStats(
            projectId,
            chapters.count { it.status == ChapterStatus.COMPLETED },
            logs.sumOf { it.promptTokens },
            logs.sumOf { it.completionTokens },
            logs.sumOf { it.estimatedCost },
            currency
        )
    }

    companion object {
        private const val TERM_SCAN_WINDOW_CHARS = 9_000
        private const val TERM_SCAN_OVERLAP_CHARS = 1_000
        private const val MAX_IMPORT_BYTES = 100 * 1024 * 1024
    }

    // ==========================================
    // Translation Execution & Controls
    // ==========================================

    fun setActiveProjectProvider(providerId: Long) {
        val projectId = _activeProjectId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val project = projectRepo.getProjectById(projectId) ?: return@launch
            projectRepo.updateProject(
                project.copy(defaultProviderId = providerId, updatedAt = System.currentTimeMillis())
            )
        }
    }

    fun startContinuousTranslation(provider: ApiProviderEntity) {
        val projId = _activeProjectId.value ?: return
        translationManager.startTranslation(
            scope = viewModelScope,
            projectId = projId,
            provider = provider
        )
    }

    fun translateSingleChapter(chapterId: Long, provider: ApiProviderEntity) {
        val projId = _activeProjectId.value ?: return
        translationManager.startTranslation(
            scope = viewModelScope,
            projectId = projId,
            provider = provider,
            chapterIds = listOf(chapterId)
        )
    }

    fun translateRange(startChapterIndex: Int, endChapterIndex: Int, provider: ApiProviderEntity) {
        val projId = _activeProjectId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val all = chapterRepo.getChaptersListByProject(projId)
            val selectedIds = all.filter { it.chapterIndex in startChapterIndex..endChapterIndex }.map { it.id }
            if (selectedIds.isNotEmpty()) {
                translationManager.startTranslation(
                    scope = viewModelScope,
                    projectId = projId,
                    provider = provider,
                    chapterIds = selectedIds
                )
            }
        }
    }

    fun translateNextPendingChapter(provider: ApiProviderEntity) {
        val projId = _activeProjectId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val all = chapterRepo.getChaptersListByProject(projId)
            val next = all.firstOrNull { it.status != ChapterStatus.COMPLETED }
            if (next != null) {
                translationManager.startTranslation(
                    scope = viewModelScope,
                    projectId = projId,
                    provider = provider,
                    chapterIds = listOf(next.id)
                )
            } else {
                withContext(Dispatchers.Main) {
                    showMessage("All chapters in this novel are already translated!")
                }
            }
        }
    }

    fun pauseTranslation() {
        translationManager.pause()
    }

    fun resumeTranslation() {
        translationManager.resume()
    }

    fun stopTranslation() {
        translationManager.stop()
    }

    // ==========================================
    // Reader & In-Place Editing
    // ==========================================

    fun saveChapterTranslationDirectly(chapterId: Long, newTranslatedContent: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val chapter = chapterRepo.getChapterById(chapterId) ?: return@launch
            val translatedFileName = fileManager.saveTranslatedChapter(
                chapter.projectId,
                chapter.chapterIndex,
                newTranslatedContent,
                chapter.title
            )
            val wordCount = TxtParser.countWords(newTranslatedContent)
            chapterRepo.updateChapter(
                chapter.copy(
                    translatedWordCount = wordCount,
                    translatedFileName = translatedFileName,
                    status = ChapterStatus.COMPLETED,
                    summary = "",
                    updatedAt = System.currentTimeMillis()
                )
            )
            syncChapterSegments(chapter.id, chapter.projectId, newTranslatedContent)

            val updatedChapters = chapterRepo.getChaptersListByProject(chapter.projectId)
            val totalDone = updatedChapters.count { it.status == ChapterStatus.COMPLETED }
            val project = projectRepo.getProjectById(chapter.projectId)
            if (project != null) {
                projectRepo.updateProject(project.copy(translatedChapters = totalDone, updatedAt = System.currentTimeMillis()))
            }

            withContext(Dispatchers.Main) {
                showMessage("Translation updated.")
            }
        }
    }

    fun reTranslateParagraph(
        chapterId: Long,
        paragraphIndex: Int,
        originalParagraph: String,
        customInstruction: String,
        provider: ApiProviderEntity,
        segmentId: String? = null,
        onResult: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chapter = chapterRepo.getChapterById(chapterId) ?: return@launch
                val project = projectRepo.getProjectById(chapter.projectId) ?: return@launch
                requireCompatibleCurrency(project, provider)
                val stableSource = if (!segmentId.isNullOrBlank()) {
                    val rows = chapterSegmentRepo.getByChapter(chapterId)
                    val selected = rows.firstOrNull { it.sourceSegmentId == segmentId }
                    val related = when (selected?.relation) {
                        "MANY_TO_ONE" -> rows.filter { it.translatedSegmentId == selected.translatedSegmentId }
                        "ONE_TO_MANY" -> rows.filter { it.sourceSegmentId == selected.sourceSegmentId }
                        else -> listOfNotNull(selected)
                    }
                    related.sortedBy { it.sourceOrdinal ?: Int.MAX_VALUE }
                        .map { it.sourceText }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString("\n\n")
                        .takeIf { it.isNotBlank() }
                        ?: run {
                            val sourceText = fileManager.readOriginalChapter(chapter.projectId, chapter.originalFileName)
                            com.breakyuna.noveltranslator.core.translator.StableSegmentParser
                                .parse(chapterId, sourceText)
                                .firstOrNull { it.segmentId == segmentId }
                                ?.sourceText
                        }
                } else null
                val resolvedOriginalParagraph = stableSource ?: originalParagraph
                require(resolvedOriginalParagraph.isNotBlank()) { "Original paragraph is empty" }
                val paragraphBudget = TokenCalculator.calculateChunkBudget(provider.maxContextTokens, 1_200L)
                require(TokenCalculator.estimateTokens(resolvedOriginalParagraph) <= paragraphBudget) {
                    "Paragraph is too long for this provider's context window"
                }
                val glossary = glossaryRepo.getGlossaryListByProject(chapter.projectId)
                    .asSequence()
                    .filter { it.reviewStatus == ReviewStatus.CONFIRMED.name && it.originalTerm.length in 1..200 }
                    .filter { resolvedOriginalParagraph.contains(it.originalTerm, ignoreCase = true) }
                    .distinctBy { LexiconCandidateVoting.normalizeSourceTerm(it.originalTerm) }
                    .take(12)
                    .toList()

                val prompt = """
Re-translate the following paragraph from ${project.sourceLanguage.take(80)} to ${project.targetLanguage.take(80)} with instruction: "${customInstruction.take(1_000)}".
Maintain consistency with glossary:
${glossary.joinToString("\n") { "• ${it.originalTerm.take(120)} -> ${it.translatedTerm.take(120)}" }}

Original Paragraph:
                    $resolvedOriginalParagraph

Output ONLY the new translated paragraph text.
                """.trimIndent()

                val result = llmClient.executeCompletion(
                    provider = provider,
                    systemPrompt = "You are a master novel translator. Output only the revised paragraph.",
                    userPrompt = prompt,
                    maxTokens = minOf(16_384, maxOf(1_024, provider.maxContextTokens / 2))
                )
                val validation = if (result.isSuccess && !result.isTruncated && result.text.isNotBlank()) {
                    com.breakyuna.noveltranslator.core.translator.TranslationQualityValidator.validate(resolvedOriginalParagraph, result.text, glossary)
                } else {
                    com.breakyuna.noveltranslator.core.translator.TranslationValidation(false, listOf(result.errorMessage ?: "empty or truncated response"))
                }
                recordAgentUsage(
                    chapter.projectId,
                    "Chapter ${chapter.chapterIndex} segment ${segmentId ?: "ordinal_${paragraphIndex}"} re-translation",
                    provider,
                    listOf(result),
                    operationSuccessful = validation.isAcceptable
                )
                check(validation.isAcceptable) {
                    "Re-translation rejected: ${validation.problems.joinToString()}"
                }
                withContext(Dispatchers.Main) {
                    onResult(result.text.trim())
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    showMessage("Re-translation failed: ${error.localizedMessage ?: "provider or quality error"}")
                }
            }
        }
    }

    // ==========================================
    // Export Operations
    // ==========================================

    fun exportToTxt(
        projectId: Long,
        includeGlossary: Boolean,
        includeParallel: Boolean,
        onExportComplete: (File) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val project = projectRepo.getProjectById(projectId) ?: return@launch
            val chapters = chapterRepo.getChaptersListByProject(projectId)
            val glossary = glossaryRepo.getGlossaryListByProject(projectId)

            val file = TxtExporter.exportMergedTxt(
                project = project,
                chapters = chapters,
                glossary = glossary,
                fileManager = fileManager,
                includeGlossaryAppendix = includeGlossary,
                includeOriginalParallel = includeParallel
            )

            withContext(Dispatchers.Main) {
                onExportComplete(file)
            }
        }
    }

    fun exportToEpub(
        projectId: Long,
        onExportComplete: (File) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val project = projectRepo.getProjectById(projectId) ?: return@launch
            val chapters = chapterRepo.getChaptersListByProject(projectId)

            val file = EpubExporter.exportEpub(
                project = project,
                chapters = chapters,
                fileManager = fileManager
            )

            withContext(Dispatchers.Main) {
                onExportComplete(file)
            }
        }
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
            recordStandaloneRequest(provider, result)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    onComplete(true, "Success: ${result.text.trim()} (${result.durationMs}ms)")
                } else {
                    onComplete(false, result.errorMessage ?: "Connection failed")
                }
            }
        }
    }

    private suspend fun recordStandaloneRequest(provider: ApiProviderEntity, aggregate: LlmResult) {
        val attempts = aggregate.attempts.ifEmpty {
            listOf(com.breakyuna.noveltranslator.core.llm.LlmAttempt(1, aggregate))
        }
        val logs = attempts.map { attempt ->
            val item = attempt.result
            LlmRequestLogEntity(
                projectId = null,
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
                estimatedCost = TokenCalculator.calculateCost(
                    item.promptTokens,
                    item.completionTokens,
                    provider.inputPricePerMillion,
                    provider.outputPricePerMillion
                ),
                durationMs = item.durationMs,
                httpStatus = item.httpStatus,
                errorCategory = item.errorCategory?.name,
                errorMessage = item.errorMessage?.take(500),
                finishReason = item.finishReason,
                requestId = item.requestId,
                isSuccess = item.isSuccess
            )
        }
        translationAuditRepo.record(null, null, logs)
    }

    fun fetchModelsFromEndpoint(provider: ApiProviderEntity, onComplete: (Result<List<String>>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = llmClient.fetchAvailableModels(provider)
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    private fun normalizeTerm(value: String): String =
        LexiconCandidateVoting.normalizeSourceTerm(value)

    private suspend fun syncProjectSegments(projectId: Long) {
        chapterRepo.getChaptersListByProject(projectId).forEach { chapter ->
            val original = fileManager.readOriginalChapter(projectId, chapter.originalFileName)
            val translated = fileManager.readTranslatedChapter(projectId, chapter.translatedFileName)
            syncChapterSegments(chapter.id, projectId, translated, originalOverride = original)
        }
    }

    private suspend fun syncChapterSegments(
        chapterId: Long,
        projectId: Long,
        translatedText: String,
        originalOverride: String? = null
    ) {
        val chapter = chapterRepo.getChapterById(chapterId) ?: return
        val original = originalOverride
            ?: fileManager.readOriginalChapter(projectId, chapter.originalFileName)
        val rows = com.breakyuna.noveltranslator.core.translator.StableSegmentParser.toPersistedRelations(
            chapterId = chapterId,
            sourceText = original,
            translatedText = translatedText
        )
        chapterSegmentRepo.replaceForChapter(chapterId, rows)
    }

    // ==========================================
    // Background Task Queue Controls
    // ==========================================
    val taskQueueTasks = taskManager.tasks
    val maxConcurrentTasks = taskManager.maxConcurrency
    val isQueuePaused = taskManager.isQueuePaused

    fun setMaxConcurrentTasks(limit: Int) {
        taskManager.setMaxConcurrency(limit)
    }

    fun pauseTask(taskId: String) {
        taskManager.pauseTask(taskId)
    }

    fun resumeTask(taskId: String) {
        taskManager.resumeTask(taskId)
    }

    fun cancelTask(taskId: String) {
        taskManager.cancelTask(taskId)
    }

    fun retryTask(taskId: String) {
        taskManager.retryTask(taskId)
    }

    fun clearCompletedTasks() {
        taskManager.clearCompletedTasks()
    }

    fun enqueueBatchChapters(project: ProjectEntity, chapters: List<ChapterEntity>, provider: ApiProviderEntity) {
        taskManager.enqueueChapters(project, chapters, provider)
        showMessage("已将 ${chapters.size} 个章节加入并发翻译队列")
    }

    fun extractGlossaryWithAi(projectId: Long, provider: ApiProviderEntity, sampleChapterCount: Int, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val chapters = chapterRepo.getChaptersListByProject(projectId).take(sampleChapterCount)
            for (chapter in chapters) {
                val origText = fileManager.readOriginalChapter(projectId, chapter.originalFileName)
                if (origText.isNotBlank()) {
                    val currentTerms = glossaryRepo.getGlossaryListByProject(projectId)
                    val confirmedTerms = currentTerms.filter { it.reviewStatus == ReviewStatus.CONFIRMED.name }
                    splitTermScanWindows(origText).forEachIndexed { windowIndex, window ->
                        val extraction = termExtractionAgent.extractTermsWithUsage(
                            projectId = projectId,
                            sampleText = window,
                            provider = provider,
                            sourceLanguage = "auto",
                            targetLanguage = "zh",
                            existingTerms = confirmedTerms.map { it.originalTerm }
                        )
                        logTermExtractionDebug(projectId, chapter.chapterIndex, windowIndex + 1, extraction)
                        check(extraction.usage.isSuccess && extraction.usage.text.isNotBlank()) {
                            extraction.usage.errorMessage ?: "Terminology extraction returned no usable response"
                        }
                        check(extraction.parseError == null) { extraction.parseError ?: "Invalid terminology JSON" }
                        val updatedCandidates = glossaryRepo.observeAiCandidates(
                            projectId = projectId,
                            chapterIndex = chapter.chapterIndex,
                            observations = extraction.terms
                        )
                        logLegacyCandidateUpdates(
                            projectId,
                            chapter.chapterIndex,
                            windowIndex + 1,
                            updatedCandidates
                        )
                    }
                }
            }
            withContext(Dispatchers.Main) {
                showMessage("AI 专有名词提取已完成")
                onComplete()
            }
        }
    }

    fun testProvider(provider: ApiProviderEntity, onComplete: (Boolean, String) -> Unit) {
        testProviderConnection(provider, onComplete)
    }

}
