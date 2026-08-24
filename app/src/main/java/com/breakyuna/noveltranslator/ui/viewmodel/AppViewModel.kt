package com.breakyuna.noveltranslator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.breakyuna.noveltranslator.core.agent.ChapterSplitAgent
import com.breakyuna.noveltranslator.core.agent.TermExtractionAgent
import com.breakyuna.noveltranslator.core.exporter.EpubExporter
import com.breakyuna.noveltranslator.core.exporter.TxtExporter
import com.breakyuna.noveltranslator.core.llm.LlmClient
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.core.parser.*
import com.breakyuna.noveltranslator.core.project.ProjectFileManager
import com.breakyuna.noveltranslator.core.sample.SampleNovelProvider
import com.breakyuna.noveltranslator.core.security.ApiKeyCipher
import com.breakyuna.noveltranslator.core.translator.TranslationJobState
import com.breakyuna.noveltranslator.core.translator.TranslationManager
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.*
import com.breakyuna.noveltranslator.data.repository.*
import com.breakyuna.noveltranslator.ui.i18n.AppLanguage
import com.breakyuna.noveltranslator.ui.i18n.AppStrings
import com.breakyuna.noveltranslator.ui.i18n.getAppStrings
import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("novel_translator_prefs", Context.MODE_PRIVATE)

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

    val fileManager = ProjectFileManager(application)
    val llmClient = LlmClient()

    val translationManager = TranslationManager(
        projectRepository = projectRepo,
        chapterRepository = chapterRepo,
        glossaryRepository = glossaryRepo,
        translationLogRepository = logRepo,
        fileManager = fileManager,
        llmClient = llmClient
    )

    val chapterSplitAgent = ChapterSplitAgent(llmClient)
    val termExtractionAgent = TermExtractionAgent(llmClient)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            providerRepo.encryptLegacyKeys()
        }
    }

    // Data flows
    val allProjects: StateFlow<List<ProjectEntity>> = projectRepo.allProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allProviders: StateFlow<List<ApiProviderEntity>> = providerRepo.allProviders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeProjectId = MutableStateFlow<Long?>(null)
    val activeProjectId: StateFlow<Long?> = _activeProjectId.asStateFlow()

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

    fun importFile(
        fileName: String,
        fileBytes: ByteArray,
        sourceLang: String = "Auto",
        targetLang: String = "Chinese",
        style: String = "Literary Novel",
        customRegex: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var createdProjectId: Long? = null
            try {
                require(fileBytes.size <= MAX_IMPORT_BYTES) { "File exceeds the 100 MB import limit" }
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
                val dirPath = fileManager.getProjectDir(projectId).absolutePath
                fileManager.saveRawFile(projectId, fileName, fileBytes)

                var parsedTitle = defaultTitle
                var parsedAuthor = "Unknown"
                var parsedChapters: List<ParsedChapter> = emptyList()

                if (isEpub) {
                    val imagesDir = fileManager.getImagesDir(projectId)
                    val epubBook = EpubParser.parseEpub(fileBytes, imagesDir)
                    if (epubBook.title.isNotBlank()) parsedTitle = epubBook.title.trim().take(300)
                    if (epubBook.author.isNotBlank()) parsedAuthor = epubBook.author.trim().take(300)
                    parsedChapters = epubBook.chapters
                } else {
                    val (text, _) = TxtParser.detectCharsetAndRead(fileBytes)
                    val regex = if (!customRegex.isNullOrBlank()) customRegex else TxtParser.REGEX_CHINESE
                    parsedChapters = TxtParser.splitIntoChapters(text, regex)
                }

                require(parsedChapters.isNotEmpty()) { "No readable chapters were found in this file" }

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

                val totalWords = chapterEntities.sumOf { it.originalWordCount }
                val updatedProject = tempProject.copy(
                    id = projectId,
                    title = parsedTitle,
                    author = parsedAuthor,
                    projectDirPath = dirPath,
                    totalChapters = chapterEntities.size,
                    totalOriginalWords = totalWords
                )
                projectRepo.updateProject(updatedProject)

                withContext(Dispatchers.Main) {
                    setActiveProject(projectId)
                    showMessage("Successfully imported \"$parsedTitle\" with ${chapterEntities.size} chapters!")
                }
            } catch (e: Exception) {
                createdProjectId?.let { projectId ->
                    projectRepo.deleteProjectById(projectId)
                    fileManager.deleteProjectFiles(projectId)
                }
                withContext(Dispatchers.Main) {
                    showMessage("Import failed: ${e.localizedMessage}")
                }
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

    fun reSplitChapters(projectId: Long, regexPattern: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
            val project = projectRepo.getProjectById(projectId) ?: return@launch
            val rawFile = fileManager.getRawDir(projectId).listFiles()?.firstOrNull() ?: return@launch
            val rawBytes = rawFile.readBytes()

            val (fullText, _) = TxtParser.detectCharsetAndRead(rawBytes)
            val parsedChapters = TxtParser.splitIntoChapters(fullText, regexPattern)

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
                val extraction = termExtractionAgent.extractTermsWithUsage(
                    projectId = projectId,
                    sampleText = sample,
                    provider = provider,
                    sourceLanguage = project.sourceLanguage,
                    targetLanguage = project.targetLanguage,
                    existingTerms = currentTerms.map { it.originalTerm }
                )
                val existing = currentTerms.map { normalizeTerm(it.originalTerm) }.toSet()
                val newTerms = extraction.terms.distinctBy { normalizeTerm(it.originalTerm) }
                    .filter { normalizeTerm(it.originalTerm) !in existing }
                recordAgentUsage(
                    projectId,
                    "Chapter ${chapter.chapterIndex} terminology extraction",
                    provider,
                    listOf(extraction.usage),
                    operationSuccessful = extraction.parseError == null && extraction.usage.isSuccess
                )
                check(extraction.usage.isSuccess && extraction.usage.text.isNotBlank()) {
                    extraction.usage.errorMessage ?: "Terminology extraction returned no usable response"
                }
                check(extraction.parseError == null) { extraction.parseError ?: "Invalid terminology JSON" }

                if (newTerms.isNotEmpty()) {
                    glossaryRepo.insertTerms(newTerms)
                    withContext(Dispatchers.Main) {
                        showMessage("Added ${newTerms.size} terminology candidates from chapter ${chapter.chapterIndex} for review.")
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
        updateGlossaryTerm(term.copy(isAutoExtracted = false))
    }

    fun deleteGlossaryTerm(termId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            glossaryRepo.deleteTermById(termId)
        }
    }

    fun runAutoExtractTerms(projectId: Long, provider: ApiProviderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                showMessage("Extracting key character and lore terms...")
                val chapters = chapterRepo.getChaptersListByProject(projectId)
                if (chapters.isEmpty()) {
                    withContext(Dispatchers.Main) { showMessage("No chapters to extract from.") }
                    return@launch
                }

                val project = projectRepo.getProjectById(projectId) ?: return@launch
                requireCompatibleCurrency(project, provider)
                val knownTerms = glossaryRepo.getGlossaryListByProject(projectId).toMutableList()
                val knownKeys = knownTerms.mapTo(mutableSetOf()) { normalizeTerm(it.originalTerm) }
                val extractedCandidates = mutableListOf<GlossaryEntity>()
                val windows = chapters.flatMap { chapter ->
                    val text = fileManager.readOriginalChapter(projectId, chapter.originalFileName)
                    splitTermScanWindows(text).map { window -> "[Chapter ${chapter.chapterIndex}: ${chapter.title}]\n$window" }
                }

                for ((windowIndex, sample) in windows.withIndex()) {
                    val extraction = termExtractionAgent.extractTermsWithUsage(
                        projectId = projectId,
                        sampleText = sample,
                        provider = provider,
                        sourceLanguage = project.sourceLanguage,
                        targetLanguage = project.targetLanguage,
                        existingTerms = knownTerms.map { it.originalTerm } + extractedCandidates.map { it.originalTerm }
                    )
                    extraction.terms.distinctBy { normalizeTerm(it.originalTerm) }.forEach { term ->
                        if (knownKeys.add(normalizeTerm(term.originalTerm))) extractedCandidates += term
                    }
                    recordAgentUsage(
                        projectId,
                        "Terminology scan ${windowIndex + 1}",
                        provider,
                        listOf(extraction.usage),
                        operationSuccessful = extraction.parseError == null && extraction.usage.isSuccess
                    )
                    check(extraction.usage.isSuccess && extraction.usage.text.isNotBlank()) {
                        extraction.usage.errorMessage ?: "Terminology extraction returned no usable response"
                    }
                    check(extraction.parseError == null) { extraction.parseError ?: "Invalid terminology JSON" }
                    withContext(Dispatchers.Main) {
                        showMessage("Terminology scan ${windowIndex + 1}/${windows.size} completed.")
                    }
                }

                if (extractedCandidates.isNotEmpty()) {
                    glossaryRepo.insertTerms(extractedCandidates)
                    withContext(Dispatchers.Main) {
                        showMessage("Added ${extractedCandidates.size} terminology candidates for review!")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showMessage("Extraction completed (0 new terms identified).")
                    }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    showMessage("Terminology scan failed: ${error.localizedMessage ?: "provider or format error"}")
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
        onResult: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chapter = chapterRepo.getChapterById(chapterId) ?: return@launch
                val project = projectRepo.getProjectById(chapter.projectId) ?: return@launch
                requireCompatibleCurrency(project, provider)
                require(originalParagraph.isNotBlank()) { "Original paragraph is empty" }
                val paragraphBudget = TokenCalculator.calculateChunkBudget(provider.maxContextTokens, 1_200L)
                require(TokenCalculator.estimateTokens(originalParagraph) <= paragraphBudget) {
                    "Paragraph is too long for this provider's context window"
                }
                val glossary = glossaryRepo.getGlossaryListByProject(chapter.projectId)
                    .asSequence()
                    .filter { !it.isAutoExtracted && it.originalTerm.length in 1..200 }
                    .filter { originalParagraph.contains(it.originalTerm, ignoreCase = true) }
                    .distinctBy { it.originalTerm.trim().lowercase() }
                    .take(12)
                    .toList()

                val prompt = """
Re-translate the following paragraph from ${project.sourceLanguage.take(80)} to ${project.targetLanguage.take(80)} with instruction: "${customInstruction.take(1_000)}".
Maintain consistency with glossary:
${glossary.joinToString("\n") { "• ${it.originalTerm.take(120)} -> ${it.translatedTerm.take(120)}" }}

Original Paragraph:
$originalParagraph

Output ONLY the new translated paragraph text.
                """.trimIndent()

                val result = llmClient.executeCompletion(
                    provider = provider,
                    systemPrompt = "You are a master novel translator. Output only the revised paragraph.",
                    userPrompt = prompt,
                    maxTokens = minOf(16_384, maxOf(1_024, provider.maxContextTokens / 2))
                )
                val validation = if (result.isSuccess && !result.isTruncated && result.text.isNotBlank()) {
                    com.breakyuna.noveltranslator.core.translator.TranslationQualityValidator.validate(originalParagraph, result.text)
                } else {
                    com.breakyuna.noveltranslator.core.translator.TranslationValidation(false, listOf(result.errorMessage ?: "empty or truncated response"))
                }
                recordAgentUsage(
                    chapter.projectId,
                    "Chapter ${chapter.chapterIndex} paragraph ${paragraphIndex + 1} re-translation",
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
                temperature = 0.1f
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

    private fun normalizeTerm(value: String): String =
        java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC).lowercase()

}
