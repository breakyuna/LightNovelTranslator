package com.breakyuna.noveltranslator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.breakyuna.noveltranslator.core.agent.ChapterSplitAgent
import com.breakyuna.noveltranslator.core.agent.LexiconCandidateAggregator
import com.breakyuna.noveltranslator.core.agent.TermExtractionAgent
import com.breakyuna.noveltranslator.core.exporter.EditionExporter
import com.breakyuna.noveltranslator.core.llm.LlmClient
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.core.llm.RetryingLlmGateway
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.core.llm.TranslationControlSignal
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class BatchImportProgress(
    val isImporting: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val currentBookName: String? = null
)

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
    /** The start action waits for the latest in-flight configuration write for the same project. */
    private val translationConfigJobs = ConcurrentHashMap<Long, Job>()
    private val translationConfigLocks = ConcurrentHashMap<Long, Mutex>()
    /** Every translation worker waits until stale durable RUNNING/PAUSED rows are recovered. */
    private val translationRecoveryJob: Job
    private val translationJobRegistryLock = Any()
    /** Blocks new progress/translation workers while their Book or Edition is being removed. */
    private val deletingBooks = ConcurrentHashMap.newKeySet<Long>()
    private val deletingTranslationProjects = ConcurrentHashMap.newKeySet<Long>()
    private val readerProgressJobs = ConcurrentHashMap<Long, Job>()
    private val auxiliaryAiJobs = ConcurrentHashMap<String, Job>()
    private val auxiliaryAiControls = ConcurrentHashMap<String, TranslationControlSignal>()
    private val auxiliaryAiRegistryLock = Any()
    private val lastSeamlessChapter = ConcurrentHashMap<Long, Long?>()
    /** Full split bodies stay outside Compose state until the user explicitly confirms them. */
    private val pendingChapterSplits = ConcurrentHashMap<Long, List<ParsedChapter>>()

    private val _batchImportProgress = MutableStateFlow<BatchImportProgress?>(null)
    val batchImportProgress: StateFlow<BatchImportProgress?> = _batchImportProgress.asStateFlow()

    val shelfBooks: StateFlow<List<ShelfBook>> = bookPlatformRepo.shelf
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val readingHistory: StateFlow<List<ReadingHistoryItem>> = db.readerProgressDao().observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hiddenBooks: StateFlow<List<BookEntity>> = bookPlatformRepo.hiddenBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allPlatformBooks: StateFlow<List<BookEntity>> = bookPlatformRepo.allBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allPlatformEditions: StateFlow<List<EditionEntity>> = bookPlatformRepo.allEditions
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
        translationRecoveryJob = viewModelScope.launch(Dispatchers.IO) {
            // Recover durable task state before any provider normalization can suspend. Otherwise
            // a newly started task may be incorrectly marked INTERRUPTED by this startup cleanup.
            db.translationProjectV2Dao().markInterrupted()
            db.platformTaskDao().markInterrupted()
            providerRepo.normalizeBuiltInPresets()
            providerRepo.encryptUnprotectedSecrets()
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
            data class ImportCandidate(val uri: android.net.Uri, val fileName: String)
            val candidates = mutableListOf<ImportCandidate>()
            var skippedCount = 0

            for (uri in uris) {
                val fileName = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: "imported_novel.txt"

                val lowerName = fileName.lowercase(Locale.ROOT)
                if (!lowerName.endsWith(".txt") && !lowerName.endsWith(".epub")) {
                    skippedCount++
                    continue
                }
                candidates.add(ImportCandidate(uri, fileName))
            }

            if (candidates.isEmpty()) {
                withContext(Dispatchers.Main) {
                    if (skippedCount > 0) {
                        showMessage("导入忽略：仅支持 .txt 与 .epub 格式文件")
                    }
                }
                return@launch
            }

            val total = candidates.size
            var completedCount = 0
            val successCount = java.util.concurrent.atomic.AtomicInteger(0)
            val failCount = java.util.concurrent.atomic.AtomicInteger(0)
            val errors = java.util.concurrent.CopyOnWriteArrayList<String>()

            _batchImportProgress.value = BatchImportProgress(
                isImporting = true,
                total = total,
                completed = 0,
                currentBookName = candidates.firstOrNull()?.fileName
            )

            // Concurrency bounded to 3 parallel imports (optimal for mobile IO & SQLite WAL)
            val parallelDispatcher = Dispatchers.IO.limitedParallelism(3)

            kotlinx.coroutines.coroutineScope {
                candidates.map { candidate ->
                    launch(parallelDispatcher) {
                        val temp = File.createTempFile("book_import_", ".tmp", app.cacheDir)
                        try {
                            app.contentResolver.openInputStream(candidate.uri)?.use { input ->
                                copyImportToTemp(input, temp)
                            } ?: error("无法读取所选文件")
                            bookImporter.import(
                                fileName = candidate.fileName,
                                sourceFile = temp,
                                originalLanguage = originalLanguage,
                                customRegex = customRegex,
                                cropTableOfContents = cropTableOfContents
                            )
                            successCount.incrementAndGet()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            failCount.incrementAndGet()
                            errors.add("${candidate.fileName}: ${error.localizedMessage}")
                        } finally {
                            temp.delete()
                            val currentDone = synchronized(candidates) {
                                ++completedCount
                            }
                            _batchImportProgress.value = BatchImportProgress(
                                isImporting = currentDone < total,
                                total = total,
                                completed = currentDone,
                                currentBookName = candidate.fileName
                            )
                        }
                    }
                }
            }

            _batchImportProgress.value = null

            val sCount = successCount.get()
            val fCount = failCount.get()

            withContext(Dispatchers.Main) {
                if (fCount == 0) {
                    val msg = if (sCount == 1) "已成功加入书架" else "成功批量导入 $sCount 本图书"
                    if (skippedCount > 0) {
                        showMessage("$msg (已自动过滤 $skippedCount 个非 txt/epub 文件)")
                    } else {
                        showMessage(msg)
                    }
                } else if (sCount > 0) {
                    showMessage("成功导入 $sCount 本，失败 $fCount 本")
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
                    copyImportToTemp(input, temp)
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
                    regexPattern = customRegex?.takeIf(String::isNotBlank)
                        ?: TxtParser.inferChapterRegex(text, originalLanguage),
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

    /** Copies picker content with the same hard limit enforced by the normalized importer. */
    private fun copyImportToTemp(input: java.io.InputStream, target: File) {
        target.outputStream().buffered(64 * 1024).use { output ->
            input.buffered(64 * 1024).use { bufInput ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = bufInput.read(buffer)
                    if (count < 0) break
                    total += count.toLong()
                    require(total <= BookFileManager.MAX_IMPORT_BYTES) {
                        "File exceeds the 100 MB import limit"
                    }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    fun reSplitBookChapters(
        bookId: Long,
        regexPattern: String = "",
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
                    showMessage("翻译 Edition 已创建，可在翻译工作台配置翻译方式")
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
        promptProfile: PromptProfileDraft? = null,
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
                    highQualityReview = highQualityReview,
                    promptProfile = promptProfile
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
        viewModelScope.launch(Dispatchers.IO) {
            // Saving range/model settings and pressing Start are separate UI actions. Serialize
            // them here so a fast tap cannot start from the stale FULL_BOOK snapshot and then
            // reject the range save because the project has already become RUNNING.
            translationConfigJobs[projectId]?.join()
            // Seamless prefetch uses the same project and durable run tables. Do not queue a
            // second explicit worker while the background buffer is already running.
            val seamlessRunning = synchronized(translationJobRegistryLock) {
                seamlessJobs.containsKey(projectId)
            }
            if (!launchBookTranslation(projectId, "翻译任务失败")) {
                withContext(Dispatchers.Main) {
                    showMessage(if (seamlessRunning) "该翻译任务正在进行无感预翻译" else "该翻译任务已经在运行")
                }
            }
        }
    }

    fun pauseBookTranslation(projectId: Long) {
        viewModelScope.launch(Dispatchers.IO) { bookTranslationScheduler.pause(projectId) }
    }

    fun resumeBookTranslation(projectId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val resumed = bookTranslationScheduler.resume(projectId)
            if (!resumed) {
                withContext(Dispatchers.Main) { showMessage("该翻译任务当前不可恢复") }
                return@launch
            }
            // A PAUSED worker normally remains registered, but an app restart or an external
            // interruption can leave only the durable PAUSED/RUNNING state behind. In that case
            // resume must install a fresh scheduler worker instead of merely flipping the flag.
            val hasWorker = synchronized(translationJobRegistryLock) {
                bookTranslationJobs.containsKey(projectId) || seamlessJobs.containsKey(projectId)
            }
            if (!hasWorker) launchBookTranslation(projectId, "继续翻译失败")
        }
    }

    fun cancelBookTranslation(projectId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            // Keep both references registered until their own finally blocks run. Removing a
            // seamless job here would let a reader-progress callback launch a replacement worker
            // while the cancelled coroutine is still unwinding.
            val jobs = synchronized(translationJobRegistryLock) {
                listOfNotNull(bookTranslationJobs[projectId], seamlessJobs[projectId]).distinct()
            }
            jobs.forEach { it.cancel() }
            // Cancel the local coroutine before awaiting the provider gate. OkHttp's cancellable
            // call then releases an in-flight request promptly, while the durable state update
            // still runs after the gate is closed and prevents a replacement worker from starting.
            bookTranslationScheduler.cancel(projectId)
        }
    }

    fun cancelGlossaryScan(bookId: Long) {
        cancelAuxiliaryAiJob("glossary-scan:$bookId")
    }

    fun cancelAgentBookChapterSplit(bookId: Long) {
        cancelAuxiliaryAiJob("chapter-split:$bookId")
    }

    private fun launchBookTranslation(projectId: Long, failureLabel: String): Boolean {
        val job: Job
        synchronized(translationJobRegistryLock) {
            // Explicit and seamless workers share one durable project/run state. The lock makes
            // the cross-map check atomic so a reader progress callback cannot race a manual start.
            if (projectId in deletingTranslationProjects || seamlessJobs.containsKey(projectId)) return false
            job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    translationRecoveryJob.join()
                    val project = bookPlatformRepo.getTranslationProject(projectId)
                    if (project == null) return@launch
                    val blocked = synchronized(translationJobRegistryLock) {
                        project.bookId in deletingBooks || projectId in deletingTranslationProjects
                    }
                    if (blocked) return@launch
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
            if (bookTranslationJobs.putIfAbsent(projectId, job) != null) {
                job.cancel()
                return false
            }
            // Start while the registry lock is held. A cancellation cannot otherwise land in the
            // gap between registration and start, leaving a cancelled LAZY Job as a stale blocker.
            job.start()
        }
        return true
    }

    /** Runs one paid helper operation at a time per key and preserves cancellation across retries. */
    private fun launchAuxiliaryAiJob(
        key: String,
        block: suspend (TranslationControlSignal) -> Unit
    ): Boolean {
        val signal = TranslationControlSignal()
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                block(signal)
            } finally {
                auxiliaryAiControls.remove(key, signal)
                coroutineContext[Job]?.let { currentJob -> auxiliaryAiJobs.remove(key, currentJob) }
            }
        }
        val bookId = key.substringAfter(':', missingDelimiterValue = "").toLongOrNull()
        val duplicate = synchronized(translationJobRegistryLock) {
            // Deletion marks the book before it cancels helper workers. Registering under the same
            // lock closes the gap where a new scan could be inserted after cancellation but before
            // the database/file removal.
            if (bookId != null && bookId in deletingBooks) {
                true
            } else {
                synchronized(auxiliaryAiRegistryLock) {
                    if (auxiliaryAiJobs.containsKey(key)) {
                        true
                    } else {
                        auxiliaryAiJobs[key] = job
                        auxiliaryAiControls[key] = signal
                        // Keep registration and start atomic with respect to deletion/cancellation
                        // so a cancelled LAZY helper can never remain in the registry forever.
                        job.start()
                        false
                    }
                }
            }
        }
        if (duplicate) {
            job.cancel()
            return false
        }
        return true
    }

    private fun cancelAuxiliaryAiJob(key: String) {
        auxiliaryAiControls[key]?.cancel()
        auxiliaryAiJobs[key]?.cancel()
    }

    private fun logAuxiliaryUsage(
        operation: String,
        bookId: Long,
        provider: ApiProviderEntity,
        result: LlmResult,
        projectId: Long? = null,
        chapterIndex: Int? = null
    ) {
        val cost = TokenCalculator.calculateCost(
            result.promptTokens,
            result.completionTokens,
            provider.inputPricePerMillion,
            provider.outputPricePerMillion
        )
        val details = buildString {
            append("bookId=").append(bookId)
            append("; model=").append(provider.name).append('/').append(provider.selectedModel)
            append("; promptTokens=").append(result.promptTokens)
            append("; completionTokens=").append(result.completionTokens)
            append("; cost=").append(String.format(java.util.Locale.US, "%.6f", cost))
            append("; durationMs=").append(result.durationMs)
            append("; attempts=").append(result.attempts.size.coerceAtLeast(1))
            append("; usageSource=").append(result.usageSource.name)
            result.errorCategory?.let { append("; errorCategory=").append(it.name) }
            result.errorMessage?.takeIf { it.isNotBlank() }?.let { append("; error=").append(it) }
        }
        val message = if (result.isSuccess) {
            "$operation helper request completed"
        } else {
            "$operation helper request failed"
        }
        if (result.isSuccess) {
            com.breakyuna.noveltranslator.core.logger.SystemLogger.info(
                "LLM_AUXILIARY",
                message,
                details = details,
                projectId = projectId,
                chapterIndex = chapterIndex
            )
        } else {
            com.breakyuna.noveltranslator.core.logger.SystemLogger.warn(
                "LLM_AUXILIARY",
                message,
                details = details,
                projectId = projectId,
                chapterIndex = chapterIndex
            )
        }
    }

    fun saveReaderProgress(progress: ReaderProgressEntity) {
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val savedProgress = bookPlatformRepo.saveReaderProgress(progress) ?: return@launch
                val chapterId = savedProgress.logicalChapterId ?: return@launch
                if (lastSeamlessChapter.put(savedProgress.bookId, chapterId) == chapterId) return@launch
                // Read the source of truth directly: this callback can run from the reader while
                // the app-level WhileSubscribed StateFlow has no active collector and still holds
                // its initial empty value.
                bookPlatformRepo.getTranslationProjects(savedProgress.bookId)
                    // Automatic prefetch is opt-in for a live, resumable project. A cancelled or
                    // failed project must stay terminal until the user explicitly starts it again;
                    // a paused project must not be silently resumed by scrolling the reader.
                    .filter {
                        it.translationMode == TranslationMode.SEAMLESS.name &&
                            it.state in setOf("IDLE", "INTERRUPTED", "COMPLETED", "COMPLETED_WITH_ERRORS")
                    }
                    .forEach { project ->
                        val seamlessJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                            try {
                                translationRecoveryJob.join()
                                runCancellable { bookTranslationScheduler.run(project.id) }
                                    .onFailure { showMessage("无感翻译缓冲失败：${it.localizedMessage}") }
                            } finally {
                                coroutineContext[Job]?.let { currentJob -> seamlessJobs.remove(project.id, currentJob) }
                            }
                        }
                        val selected = synchronized(translationJobRegistryLock) {
                            if (savedProgress.bookId in deletingBooks || project.id in deletingTranslationProjects ||
                                bookTranslationJobs.containsKey(project.id)
                            ) {
                                null
                            } else {
                                val chosen = seamlessJobs.compute(project.id) { _, existing ->
                                    if (existing?.isActive == true) existing else seamlessJob
                                }
                                if (chosen === seamlessJob) {
                                    // Do not leave a registered LAZY Job unstarted if cancellation
                                    // wins immediately after this critical section.
                                    seamlessJob.start()
                                }
                                chosen
                            }
                        }
                        if (selected == null) {
                            seamlessJob.cancel()
                        } else if (selected !== seamlessJob) {
                            seamlessJob.cancel()
                        }
                    }
            } finally {
                coroutineContext[Job]?.let { currentJob -> readerProgressJobs.remove(progress.bookId, currentJob) }
            }
        }
        var canStart = false
        val previous = synchronized(translationJobRegistryLock) {
            if (progress.bookId in deletingBooks) {
                null
            } else {
                canStart = true
                readerProgressJobs.put(progress.bookId, job).also {
                    // Registration and start must be one critical section; otherwise deletion can
                    // cancel this LAZY job before its finally block is able to remove the entry.
                    job.start()
                }
            }
        }
        if (!canStart) {
            job.cancel()
        } else {
            previous?.cancel()
        }
    }

    fun saveManualRevision(editionSegmentId: Long, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable { bookPlatformRepo.saveManualRevision(editionSegmentId, text) }
                .onSuccess { withContext(Dispatchers.Main) { showMessage("修改已保存，并保留修订历史") } }
                .onFailure { error -> withContext(Dispatchers.Main) { showMessage("保存修改失败：${error.localizedMessage ?: "文本无效或过长"}") } }
        }
    }

    fun renameBook(bookId: Long, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable { bookPlatformRepo.renameBook(bookId, title) }
                .onFailure { error -> withContext(Dispatchers.Main) { showMessage("重命名失败：${error.localizedMessage ?: "书名无效"}") } }
        }
    }

    fun updateBookMetadata(bookId: Long, title: String, author: String, description: String, language: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable { bookPlatformRepo.updateBookMetadata(bookId, title, author, description, language) }
                .onSuccess { withContext(Dispatchers.Main) { showMessage("书籍信息已保存") } }
                .onFailure { error -> withContext(Dispatchers.Main) { showMessage("保存书籍信息失败：${error.localizedMessage ?: "输入无效"}") } }
        }
    }

    fun setBookCover(bookId: Long, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable {
                require(bookPlatformRepo.getBook(bookId) != null) { "Book not found" }
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

    fun observePromptProfile(projectId: Long): Flow<PromptProfileEntity?> =
        bookPlatformRepo.observePromptProfile(projectId)

    fun savePromptProfile(
        projectId: Long,
        draft: PromptProfileDraft,
        onSaved: (Int) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable { bookPlatformRepo.savePromptProfile(projectId, draft) }
                .onSuccess { profile ->
                    withContext(Dispatchers.Main) {
                        showMessage("提示词已保存为第 ${profile.version} 版")
                        onSaved(profile.version)
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        showMessage("保存提示词失败：${error.localizedMessage ?: "提示词内容无效"}")
                    }
                }
        }
    }

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
        seamlessAheadChapters: Int? = null,
        highQualityReview: Boolean? = null,
        promptProfile: PromptProfileDraft? = null,
        onSuccess: () -> Unit = {}
    ) {
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                translationConfigLocks.getOrPut(projectId) { Mutex() }.withLock {
                    val existing = bookPlatformRepo.getTranslationProject(projectId) ?: return@launch
                    val updated = existing.copy(
                        providerId = providerId,
                        modelName = modelName.trim().take(200),
                        translationMode = mode.name,
                        maxBatchChapters = maxBatchChapters.coerceIn(1, 5),
                        rangeStart = rangeStart,
                        rangeEnd = rangeEnd,
                        seamlessAheadChapters = (seamlessAheadChapters ?: existing.seamlessAheadChapters)
                            .coerceIn(1, 50),
                        styleGuide = styleGuide.trim().take(2_000).ifBlank { "保持文学韵味与专有名词一致性" },
                        highQualityReview = highQualityReview ?: existing.highQualityReview,
                        updatedAt = System.currentTimeMillis()
                    )
                    bookPlatformRepo.updateTranslationProject(updated, promptProfile)
                    withContext(Dispatchers.Main) {
                        showMessage("翻译配置已保存")
                        onSuccess()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    showMessage("保存翻译配置失败：${error.localizedMessage ?: "任务状态或参数无效"}")
                }
            } finally {
                coroutineContext[Job]?.let { currentJob ->
                    translationConfigJobs.remove(projectId, currentJob)
                }
            }
        }
        translationConfigJobs[projectId] = job
        job.start()
    }

    fun upsertLexiconEntry(entry: LexiconEntryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable { bookPlatformRepo.upsertLexiconEntry(entry) }
                .onSuccess { withContext(Dispatchers.Main) { showMessage("专有术语已保存") } }
                .onFailure { error -> withContext(Dispatchers.Main) { showMessage("保存术语失败：${error.localizedMessage ?: "术语格式无效或重复"}") } }
        }
    }

    fun deleteLexiconEntry(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable { bookPlatformRepo.deleteLexiconEntry(id) }
                .onSuccess { withContext(Dispatchers.Main) { showMessage("专有术语已删除") } }
                .onFailure { error -> withContext(Dispatchers.Main) { showMessage("删除术语失败：${error.localizedMessage ?: "存储错误"}") } }
        }
    }

    fun confirmLexiconEntry(entry: LexiconEntryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCancellable {
                bookPlatformRepo.updateLexiconEntry(
                    entry.copy(
                        reviewStatus = ReviewStatus.CONFIRMED.name,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }.onSuccess { withContext(Dispatchers.Main) { showMessage("术语已确认，将在后续翻译中生效") } }
                .onFailure { error -> withContext(Dispatchers.Main) { showMessage("确认术语失败：${error.localizedMessage ?: "术语格式无效"}") } }
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
            val result = try {
                db.withTransaction {
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                CandidateImportResult.Failed(error.localizedMessage ?: "Glossary entry is invalid")
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
        onComplete: (Result<Int>) -> Unit = {}
    ) {
        val started = launchAuxiliaryAiJob("glossary-scan:$bookId") { control ->
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
                        onComplete(Result.success(0))
                    }
                    return@launchAuxiliaryAiJob
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
                    if (control.isCancelled) throw CancellationException("Terminology scan cancelled")
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
                                if (control.isCancelled) throw CancellationException("Terminology scan cancelled")
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
                                        existingTerms = existingConfirmedTerms,
                                        controlSignal = control
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
                                logAuxiliaryUsage(
                                    operation = "TERM_EXTRACTION",
                                    bookId = bookId,
                                    provider = provider,
                                    result = extraction.usage,
                                    projectId = projectId,
                                    chapterIndex = chapter.chapterIndex
                                )
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
                        onComplete(Result.success(0))
                    }
                    return@launchAuxiliaryAiJob
                }
                if (failedWindows > 0) {
                    val partialFailure = IllegalStateException(
                        "术语扫描有 $failedWindows 个窗口失败，已保留成功窗口的候选证据"
                    )
                    com.breakyuna.noveltranslator.core.logger.SystemLogger.warn(
                        "GLOSSARY_SCAN",
                        "⚠️ 扫描未完整完成: processed=$readableChapters, candidates=$count, failedWindows=$failedWindows",
                        projectId = projectId
                    )
                    withContext(Dispatchers.Main) {
                        showMessage("术语扫描未完整完成：$failedWindows 个窗口失败，已保留成功窗口结果")
                        onComplete(Result.failure(partialFailure))
                    }
                    return@launchAuxiliaryAiJob
                }
                com.breakyuna.noveltranslator.core.logger.SystemLogger.info(
                    "GLOSSARY_SCAN",
                    "✅ 扫描完成！已处理 $readableChapters 章，共 $count 个待审核候选，失败窗口 $failedWindows",
                    projectId = projectId
                )
                withContext(Dispatchers.Main) {
                    showMessage("专有术语扫描完成，共 $count 个待审核候选")
                    onComplete(Result.success(count))
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
                withContext(Dispatchers.Main) {
                    showMessage("术语扫描失败: ${e.localizedMessage}")
                    onComplete(Result.failure(e))
                }
            }
        }
        if (!started) {
            showMessage("当前书籍已有术语扫描任务正在运行")
            onComplete(Result.failure(IllegalStateException("当前书籍已有术语扫描任务正在运行")))
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
            try {
                var deletedCount = 0
                bookIds.forEach { bookId ->
                    if (deleteBookAndData(bookId)) deletedCount++
                }
                withContext(Dispatchers.Main) {
                    showMessage("已永久删除 $deletedCount/${bookIds.size} 本图书及相关文件")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    showMessage("永久删除失败：${error.localizedMessage ?: "存储或任务状态错误"}")
                }
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
            try {
                if (deleteBookAndData(bookId)) {
                    withContext(Dispatchers.Main) { showMessage("已永久删除图书及相关文件") }
                } else {
                    withContext(Dispatchers.Main) { showMessage("该书籍正在删除，请稍后再试") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    showMessage("永久删除失败：${error.localizedMessage ?: "存储或任务状态错误"}")
                }
            }
        }
    }

    fun deleteEdition(bookId: Long, editionId: Long, onSuccess: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            var projectIds = emptySet<Long>()
            val acquired = synchronized(translationJobRegistryLock) { deletingBooks.add(bookId) }
            if (!acquired) {
                showMessage("该书籍正在删除，请稍后再试")
                return@launch
            }
            try {
                // Install the book-wide guard before loading child projects. A progress callback
                // or newly-created project must not slip into the deletion window.
                val projects = bookPlatformRepo.getTranslationProjectsForEdition(editionId)
                projectIds = projects.mapTo(linkedSetOf()) { it.id }
                synchronized(translationJobRegistryLock) {
                    deletingTranslationProjects.addAll(projectIds)
                }
                stopReaderProgressJobs(bookId)
                stopAuxiliaryAiJobs(bookId)
                for (project in projects) stopTranslationProject(project)
                bookPlatformRepo.deleteEdition(bookId, editionId)
                withContext(Dispatchers.Main) {
                    showMessage("译本及其翻译记录已删除")
                    onSuccess()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    showMessage("删除译本失败：${error.localizedMessage ?: "存储或任务状态错误"}")
                }
            } finally {
                synchronized(translationJobRegistryLock) {
                    deletingTranslationProjects.removeAll(projectIds)
                    deletingBooks.remove(bookId)
                }
            }
        }
    }

    /** Stops active workers while a deletion guard blocks new callbacks, then removes DB/files. */
    private suspend fun deleteBookAndData(bookId: Long): Boolean {
        var projectIds = emptySet<Long>()
        val acquired = synchronized(translationJobRegistryLock) { deletingBooks.add(bookId) }
        if (!acquired) return false
        try {
            val projects = bookPlatformRepo.getTranslationProjects(bookId)
            projectIds = projects.mapTo(linkedSetOf()) { it.id }
            synchronized(translationJobRegistryLock) {
                deletingTranslationProjects.addAll(projectIds)
            }
            stopReaderProgressJobs(bookId)
            stopAuxiliaryAiJobs(bookId)
            for (project in projects) stopTranslationProject(project)
            bookPlatformRepo.deletePermanently(bookId)
            return true
        } finally {
            synchronized(translationJobRegistryLock) {
                deletingTranslationProjects.removeAll(projectIds)
                deletingBooks.remove(bookId)
            }
        }
    }

    private suspend fun stopReaderProgressJobs(bookId: Long) {
        readerProgressJobs.remove(bookId)?.let { job ->
            job.cancel()
            job.join()
        }
        lastSeamlessChapter.remove(bookId)
    }

    /** Auxiliary scans read and write project-bound rows; wait for them before deleting a book. */
    private suspend fun stopAuxiliaryAiJobs(bookId: Long) {
        listOf("glossary-scan:$bookId", "chapter-split:$bookId").forEach { key ->
            val (signal, job) = synchronized(auxiliaryAiRegistryLock) {
                auxiliaryAiControls.remove(key) to auxiliaryAiJobs.remove(key)
            }
            // Mark the provider gate before cancelling the coroutine so retry/continuation logic
            // cannot start another paid request while the worker is unwinding.
            signal?.cancel()
            job?.let {
                job.cancel()
            }
            // The worker cancellation above releases the provider-call gate promptly; request
            // cancellation still gives the gateway a durable signal before the row is removed.
            signal?.requestCancel()
            job?.let {
                job.join()
            }
        }
    }

    private suspend fun stopTranslationProject(project: TranslationProjectV2Entity) {
        val (explicitJob, seamlessJob) = synchronized(translationJobRegistryLock) {
            bookTranslationJobs[project.id] to seamlessJobs[project.id]
        }
        if (explicitJob?.isActive == true || seamlessJob?.isActive == true ||
            project.state == "RUNNING" || project.state == "PAUSED"
        ) {
            bookTranslationScheduler.cancel(project.id)
        }
        listOfNotNull(explicitJob, seamlessJob).distinct().forEach { job ->
            job.cancel()
            job.join()
        }
        synchronized(translationJobRegistryLock) {
            explicitJob?.let { bookTranslationJobs.remove(project.id, it) }
            seamlessJob?.let { seamlessJobs.remove(project.id, it) }
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
        onPreview: (List<ChapterSplitPreview>) -> Unit,
        onFinished: () -> Unit = {}
    ) {
        pendingChapterSplits.remove(bookId)
        val started = launchAuxiliaryAiJob("chapter-split:$bookId") { control ->
            try {
                val sourceFile = bookFiles.sourceDir(bookId)
                    .listFiles()
                    ?.filter { it.isFile && !it.name.startsWith(".") }
                    ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                    ?.firstOrNull()
                    ?: error("找不到保留的原始文件")
                require(sourceFile.extension.equals("txt", ignoreCase = true)) {
                    "EPUB 已由解析器完成章节识别；AI 章节识别目前只对 TXT 提供预览"
                }
                val fullText = TxtParser.openDetectedReader(sourceFile).use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(16 * 1024)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        text.append(buffer, 0, count)
                        require(text.length <= ChapterSplitAgent.MAX_AI_SPLIT_CHARS) {
                            "AI splitting is limited to 2,000,000 characters; use regex splitting for larger books"
                        }
                    }
                    text.toString()
                }
                showMessage("AI 正在分析书籍章节结构...")
                val parsed = chapterSplitAgent.analyzeAndSplit(
                    fullText = fullText,
                    provider = provider,
                    onProgress = { completed, total -> showMessage("AI 章节识别进度 $completed/$total...") },
                    onUsage = { result ->
                        logAuxiliaryUsage(
                            operation = "CHAPTER_SPLIT",
                            bookId = bookId,
                            provider = provider,
                            result = result
                        )
                    },
                    controlSignal = control
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
                pendingChapterSplits.remove(bookId)
                throw cancelled
            } catch (error: Throwable) {
                pendingChapterSplits.remove(bookId)
                withContext(Dispatchers.Main) {
                    showMessage("AI 章节识别失败：${error.localizedMessage ?: "输入或供应商错误"}")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { onFinished() }
            }
        }
        if (!started) {
            showMessage("当前书籍已有 AI 章节识别任务正在运行")
            onFinished()
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
                    providerRepo.insertProvider(provider)
                } else {
                    providerRepo.updateProvider(provider)
                }
                withContext(Dispatchers.Main) {
                    showMessage("API Provider \"${provider.name}\" saved.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    showMessage("Failed to save provider: ${error.localizedMessage ?: "storage error"}")
                }
            }
        }
    }

    fun deleteProvider(providerId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                providerRepo.deleteProviderById(providerId)
                withContext(Dispatchers.Main) {
                    showMessage("API Provider removed.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    showMessage("Failed to remove provider: ${error.localizedMessage ?: "storage error"}")
                }
            }
        }
    }

    fun setDefaultProvider(providerId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                providerRepo.setDefaultProvider(providerId)
                withContext(Dispatchers.Main) {
                    showMessage("Default provider updated.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    showMessage("Failed to set default provider: ${error.localizedMessage ?: "storage error"}")
                }
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
        readerProgressJobs.clear()
        auxiliaryAiControls.clear()
        auxiliaryAiJobs.clear()
        synchronized(translationJobRegistryLock) {
            bookTranslationJobs.clear()
            seamlessJobs.clear()
            translationConfigJobs.clear()
            translationConfigLocks.clear()
            deletingBooks.clear()
            deletingTranslationProjects.clear()
        }
        lastSeamlessChapter.clear()
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
