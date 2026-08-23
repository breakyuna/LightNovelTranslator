package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.agent.ChapterSplitAgent
import com.example.core.agent.TermExtractionAgent
import com.example.core.exporter.EpubExporter
import com.example.core.exporter.TxtExporter
import com.example.core.llm.LlmClient
import com.example.core.llm.LlmResult
import com.example.core.parser.*
import com.example.core.project.ProjectFileManager
import com.example.core.sample.SampleNovelProvider
import com.example.core.translator.TranslationJobState
import com.example.core.translator.TranslationManager
import com.example.data.db.AppDatabase
import com.example.data.model.*
import com.example.data.repository.*
import com.example.ui.i18n.AppLanguage
import com.example.ui.i18n.AppStrings
import com.example.ui.i18n.getAppStrings
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("novel_translator_prefs", Context.MODE_PRIVATE)

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
    val providerRepo = ApiProviderRepository(db.apiProviderDao())
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
                val origName = fileManager.saveOriginalChapter(projectId, parsed.index, parsed.content)
                val transName = "trans_${String.format("%04d", parsed.index)}.txt"
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
                    isAutoExtracted = true
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
        uri: Uri,
        fileName: String,
        fileBytes: ByteArray,
        sourceLang: String = "Auto",
        targetLang: String = "Chinese",
        style: String = "Literary Novel",
        customRegex: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val isEpub = fileName.endsWith(".epub", ignoreCase = true) || fileName.endsWith(".equb", ignoreCase = true)
                val fileType = if (isEpub) "EPUB" else "TXT"
                val defaultTitle = fileName.substringBeforeLast(".")

                val tempProject = ProjectEntity(
                    title = defaultTitle,
                    author = "Unknown",
                    sourceFileName = fileName,
                    fileType = fileType,
                    projectDirPath = "",
                    sourceLanguage = sourceLang,
                    targetLanguage = targetLang,
                    translationStyle = style
                )

                val projectId = projectRepo.insertProject(tempProject)
                val dirPath = fileManager.getProjectDir(projectId).absolutePath
                fileManager.saveRawFile(projectId, fileName, fileBytes)

                var parsedTitle = defaultTitle
                var parsedAuthor = "Unknown"
                var parsedChapters: List<ParsedChapter> = emptyList()

                if (isEpub) {
                    val imagesDir = fileManager.getImagesDir(projectId)
                    val epubBook = EpubParser.parseEpub(fileBytes, imagesDir)
                    if (epubBook.title.isNotBlank()) parsedTitle = epubBook.title
                    if (epubBook.author.isNotBlank()) parsedAuthor = epubBook.author
                    parsedChapters = epubBook.chapters
                } else {
                    val (text, _) = TxtParser.detectCharsetAndRead(fileBytes)
                    val regex = if (!customRegex.isNullOrBlank()) customRegex else TxtParser.REGEX_CHINESE
                    parsedChapters = TxtParser.splitIntoChapters(text, regex)
                }

                val chapterEntities = parsedChapters.map { parsed ->
                    val origName = fileManager.saveOriginalChapter(projectId, parsed.index, parsed.content)
                    val transName = "trans_${String.format("%04d", parsed.index)}.txt"
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
            val project = projectRepo.getProjectById(projectId) ?: return@launch
            val rawFile = fileManager.getRawDir(projectId).listFiles()?.firstOrNull() ?: return@launch
            val rawBytes = rawFile.readBytes()

            val (fullText, _) = TxtParser.detectCharsetAndRead(rawBytes)
            val parsedChapters = TxtParser.splitIntoChapters(fullText, regexPattern)

            // Clear old chapters
            chapterRepo.deleteChaptersByProject(projectId)

            val chapterEntities = parsedChapters.map { parsed ->
                val origName = fileManager.saveOriginalChapter(projectId, parsed.index, parsed.content)
                val transName = "trans_${String.format("%04d", parsed.index)}.txt"
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
            projectRepo.updateProject(project.copy(totalChapters = chapterEntities.size, translatedChapters = 0, totalOriginalWords = totalWords))

            withContext(Dispatchers.Main) {
                showMessage("Chapters re-split into ${chapterEntities.size} parts.")
            }
        }
    }

    fun runAgentChapterSplit(projectId: Long, provider: ApiProviderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            showMessage("Agent is analyzing book structure...")
            val project = projectRepo.getProjectById(projectId) ?: return@launch
            val rawFile = fileManager.getRawDir(projectId).listFiles()?.firstOrNull() ?: return@launch
            val (fullText, _) = TxtParser.detectCharsetAndRead(rawFile.readBytes())

            val parsedChapters = chapterSplitAgent.analyzeAndSplit(fullText, provider)

            chapterRepo.deleteChaptersByProject(projectId)
            val chapterEntities = parsedChapters.map { parsed ->
                val origName = fileManager.saveOriginalChapter(projectId, parsed.index, parsed.content)
                val transName = "trans_${String.format("%04d", parsed.index)}.txt"
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
            projectRepo.updateProject(project.copy(totalChapters = chapterEntities.size, translatedChapters = 0, totalOriginalWords = totalWords))

            withContext(Dispatchers.Main) {
                showMessage("Agent identified ${chapterEntities.size} chapters successfully!")
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

    fun deleteGlossaryTerm(termId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            glossaryRepo.deleteTermById(termId)
        }
    }

    fun runAutoExtractTerms(projectId: Long, provider: ApiProviderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            showMessage("Extracting key character and lore terms...")
            val chapters = chapterRepo.getChaptersListByProject(projectId)
            if (chapters.isEmpty()) {
                withContext(Dispatchers.Main) { showMessage("No chapters to extract from.") }
                return@launch
            }

            val sampleBuilder = StringBuilder()
            for (ch in chapters.take(4)) {
                sampleBuilder.append(fileManager.readOriginalChapter(projectId, ch.originalFileName)).append("\n")
            }

            val extracted = termExtractionAgent.extractTerms(projectId, sampleBuilder.toString(), provider)
            if (extracted.isNotEmpty()) {
                glossaryRepo.insertTerms(extracted)
                withContext(Dispatchers.Main) {
                    showMessage("Extracted ${extracted.size} new terminology entries!")
                }
            } else {
                withContext(Dispatchers.Main) {
                    showMessage("Extraction completed (0 new terms identified).")
                }
            }
        }
    }

    // ==========================================
    // Translation Execution & Controls
    // ==========================================

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
            fileManager.saveTranslatedChapter(chapter.projectId, chapter.chapterIndex, newTranslatedContent)
            val wordCount = TxtParser.countWords(newTranslatedContent)
            chapterRepo.updateChapter(
                chapter.copy(
                    translatedWordCount = wordCount,
                    status = ChapterStatus.COMPLETED,
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
            val chapter = chapterRepo.getChapterById(chapterId) ?: return@launch
            val glossary = glossaryRepo.getGlossaryListByProject(chapter.projectId)

            val prompt = """
Re-translate the following paragraph with instruction: "$customInstruction".
Maintain consistency with glossary:
${glossary.joinToString("\n") { "• ${it.originalTerm} -> ${it.translatedTerm}" }}

Original Paragraph:
$originalParagraph

Output ONLY the new translated paragraph text.
            """.trimIndent()

            val result = llmClient.executeCompletion(
                provider = provider,
                systemPrompt = "You are a master novel translator. Output only the revised paragraph.",
                userPrompt = prompt
            )

            if (result.isSuccess) {
                withContext(Dispatchers.Main) {
                    onResult(result.text.trim())
                }
            } else {
                withContext(Dispatchers.Main) {
                    showMessage("Re-translation failed: ${result.errorMessage}")
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
}
