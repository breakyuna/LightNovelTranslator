package com.breakyuna.noveltranslator.legacy

import android.content.Context
import android.content.ContextWrapper
import com.breakyuna.noveltranslator.core.book.BookFileManager
import com.breakyuna.noveltranslator.core.exporter.EditionExporter
import com.breakyuna.noveltranslator.core.exporter.ExportOptions
import com.breakyuna.noveltranslator.core.quality.QualityRuleJson
import com.breakyuna.noveltranslator.core.quality.QualityRuleType
import com.breakyuna.noveltranslator.core.quality.ReplacementRuleEntry
import com.breakyuna.noveltranslator.core.quality.TextPreserveRuleEntry
import com.breakyuna.noveltranslator.data.model.AgentMessageEntity
import com.breakyuna.noveltranslator.data.model.AgentSessionEntity
import com.breakyuna.noveltranslator.data.model.BookEntity
import com.breakyuna.noveltranslator.data.model.EditionEntity
import com.breakyuna.noveltranslator.data.model.LexiconEntryEntity
import com.breakyuna.noveltranslator.data.model.PromptProfileEntity
import com.breakyuna.noveltranslator.data.model.QualityRuleSetEntity
import com.breakyuna.noveltranslator.data.model.ReaderProgressEntity
import com.breakyuna.noveltranslator.data.model.TranslationProjectV2Entity
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Exports the legacy reader/translation/agent data into a self-describing directory package.
 *
 * The legacy database is opened read-only by [LegacyExportStore]; this service never invokes a
 * write DAO, a migration, a delete of legacy data or a network client. The package is written
 * into a sibling staging directory, every payload file is re-hashed from disk, and only then is
 * the staging directory renamed to [outputDirectory]. An existing output directory is never
 * overwritten.
 *
 * Edition TXT/EPUB output is delegated to the existing [EditionExporter]. The exporter is given
 * a staging [Context] so even its own atomic write never touches the normal export directory.
 */
class LegacyExportService(
    private val context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Exports every book in [legacyDatabase].
     *
     * @param legacyDatabase the legacy database file. It is opened with `OPEN_READONLY`.
     * @param outputDirectory a directory that must not exist yet.
     * @param options limits and rendering options.
     * @param onProgress best-effort progress callback; non-cancellation exceptions are ignored.
     * @param onFailure called for each recoverable item failure. Failures are also in the manifest.
     */
    suspend fun exportAll(
        legacyDatabase: File,
        outputDirectory: File,
        options: LegacyExportOptions = LegacyExportOptions(),
        onProgress: suspend (LegacyExportProgress) -> Unit = {},
        onFailure: suspend (LegacyExportFailure) -> Unit = {},
    ): LegacyExportResult = withContext(Dispatchers.IO) {
        val output = outputDirectory.absoluteFile
        if (output.exists()) {
            throw IOException("Output directory already exists: ${output.name}")
        }
        val parent = output.parentFile
            ?: throw IOException("Output directory must have a parent")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create the output parent directory")
        }
        val staging = File(parent, ".legacy-export-${idFactory()}.staging")
        if (!staging.mkdirs()) {
            throw IOException("Unable to create the export staging directory")
        }

        var published = false
        var openedStore: LegacyExportStore? = null
        try {
            val store = LegacyExportStore.openReadOnly(context, legacyDatabase)
            openedStore = store
            val result = exportPackage(
                store = store,
                staging = staging,
                output = output,
                options = options,
                onProgress = onProgress,
                onFailure = onFailure,
            )
            published = true
            result
        } finally {
            openedStore?.close()
            if (!published) {
                // Only this export's own staging directory is removed. The legacy database and
                // any pre-existing output directory are never touched.
                staging.deleteRecursively()
            }
        }
    }

    private suspend fun exportPackage(
        store: LegacyExportStore,
        staging: File,
        output: File,
        options: LegacyExportOptions,
        onProgress: suspend (LegacyExportProgress) -> Unit,
        onFailure: suspend (LegacyExportFailure) -> Unit,
    ): LegacyExportResult {
        val writer = LegacyPackageWriter(staging, options)
        val failures = mutableListOf<LegacyExportFailure>()
        val warnings = store.warnings.toMutableList()
        val usedPaths = mutableSetOf<String>()

        val workRoot = File(staging, ".work")
        if (!workRoot.mkdirs()) throw IOException("Unable to create the export work directory")
        val exporter = EditionExporter(
            store.database,
            BookFileManager(StagingContext(context, workRoot)),
        )
        val exportOptions = ExportOptions(
            bilingual = options.bilingual,
            translationFirst = options.translationFirst,
            deduplicateIdentical = options.deduplicateIdentical,
            // Legacy illustrations live under the old files tree; the package intentionally
            // exports text only so it does not duplicate an unbounded binary library.
            includeImages = false,
        )

        val bookDao = store.database.bookDao()
        val books = bookDao.observeAllBooks().first()
        val projects = store.database.translationProjectV2Dao().observeAll().first()
        val bookIds = books.map { it.id }.toHashSet()
        val orphanProjects = projects.count { it.bookId !in bookIds }
        if (orphanProjects > 0) {
            warnings += "$orphanProjects project(s) reference missing books; their project data was still exported."
        }

        val editionsByBook = LinkedHashMap<Long, List<EditionEntity>>()
        for (book in books) {
            try {
                editionsByBook[book.id] = bookDao.getEditions(book.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                editionsByBook[book.id] = emptyList()
                recordFailure(
                    failures = failures,
                    onFailure = onFailure,
                    failure = LegacyExportFailure(
                        scope = "editions",
                        bookId = book.id,
                        message = failureMessage(error),
                    ),
                )
            }
        }

        val formatCount = (if (options.includeTxt) 1 else 0) + (if (options.includeEpub) 1 else 0)
        val totalSteps = books.size * 2 +
            editionsByBook.values.sumOf { it.size } * formatCount +
            6
        var completedSteps = 0

        suspend fun report(
            phase: LegacyExportPhase,
            bookId: Long?,
            editionId: Long?,
            path: String?,
        ) {
            notifyProgress(
                onProgress,
                LegacyExportProgress(
                    phase = phase,
                    completed = completedSteps,
                    total = totalSteps,
                    bookId = bookId,
                    editionId = editionId,
                    path = path,
                ),
            )
        }

        report(LegacyExportPhase.PREPARING, null, null, null)

        attemptFile(
            scope = "projects",
            path = "projects.json",
            failures = failures,
            onFailure = onFailure,
        ) {
            writer.json("projects.json", "projects", projects.size) { json ->
                json.beginArray()
                for (project in projects) writeProject(json, project)
                json.endArray()
            }
        }
        completedSteps++
        report(LegacyExportPhase.FINALIZING, null, null, "projects.json")

        attemptFile(
            scope = "glossary",
            path = "glossary.csv",
            failures = failures,
            onFailure = onFailure,
        ) {
            val entries = mutableListOf<Pair<Long, LexiconEntryEntity>>()
            for (project in projects) {
                store.database.lexiconV2Dao().getAll(project.id).forEach { entry ->
                    entries += project.id to entry
                }
            }
            writer.text("glossary.csv", "glossary", entries.size) { text ->
                text.append(
                    "projectId,id,sourceTerm,targetTerm,kind,category,aliases,notes," +
                        "caseSensitive,exactMatch,priority,enabled,source,reviewStatus,createdAt,updatedAt\n"
                )
                for ((projectId, entry) in entries) {
                    text.append(csv(projectId.toString())).append(',')
                    text.append(csv(entry.id.toString())).append(',')
                    text.append(csv(entry.sourceTerm)).append(',')
                    text.append(csv(entry.targetTerm)).append(',')
                    text.append(csv(entry.kind)).append(',')
                    text.append(csv(entry.category)).append(',')
                    text.append(csv(entry.aliases)).append(',')
                    text.append(csv(entry.notes)).append(',')
                    text.append(csv(entry.caseSensitive.toString())).append(',')
                    text.append(csv(entry.exactMatch.toString())).append(',')
                    text.append(csv(entry.priority.toString())).append(',')
                    text.append(csv(entry.enabled.toString())).append(',')
                    text.append(csv(entry.source)).append(',')
                    text.append(csv(entry.reviewStatus)).append(',')
                    text.append(csv(entry.createdAt.toString())).append(',')
                    text.append(csv(entry.updatedAt.toString())).append('\n')
                }
            }
        }
        completedSteps++
        report(LegacyExportPhase.FINALIZING, null, null, "glossary.csv")

        attemptFile(
            scope = "quality-rules",
            path = "quality-rules.json",
            failures = failures,
            onFailure = onFailure,
        ) {
            val rules = mutableListOf<QualityRuleSetEntity>()
            for (project in projects) {
                rules += store.database.qualityRuleDao().getByProject(project.id)
            }
            writer.json("quality-rules.json", "quality-rules", rules.size) { json ->
                json.beginArray()
                for (rule in rules) writeQualityRule(json, rule)
                json.endArray()
            }
        }
        completedSteps++
        report(LegacyExportPhase.FINALIZING, null, null, "quality-rules.json")

        attemptFile(
            scope = "prompts",
            path = "prompts.json",
            failures = failures,
            onFailure = onFailure,
        ) {
            val prompts = store.allPromptProfiles()
            writer.json("prompts.json", "prompts", prompts.size) { json ->
                json.beginArray()
                for (prompt in prompts) writePrompt(json, prompt)
                json.endArray()
            }
        }
        completedSteps++
        report(LegacyExportPhase.FINALIZING, null, null, "prompts.json")

        for (book in books) {
            currentCoroutineContext().ensureActive()
            val bookDirectory = reservePath(
                "books/${pad(book.id)}_${LegacyExportPaths.safeSegment(book.title, "book", 48)}",
                usedPaths,
            )
            val editions = editionsByBook[book.id].orEmpty()
            val bookProjects = projects.filter { it.bookId == book.id }

            if (editions.isEmpty()) {
                warnings += "Book ${book.id} has no editions; book metadata was exported."
            }

            attemptFile(
                scope = "book",
                bookId = book.id,
                path = "$bookDirectory/book.json",
                failures = failures,
                onFailure = onFailure,
            ) {
                writer.json("$bookDirectory/book.json", "book", 1) { json ->
                    writeBook(json, book, editions, bookProjects)
                }
            }
            completedSteps++
            report(LegacyExportPhase.BOOK, book.id, null, "$bookDirectory/book.json")

            attemptFile(
                scope = "reader-progress",
                bookId = book.id,
                path = "$bookDirectory/progress.json",
                failures = failures,
                onFailure = onFailure,
            ) {
                val progress = store.database.readerProgressDao().get(book.id)
                writer.json("$bookDirectory/progress.json", "reader-progress", if (progress == null) 0 else 1) { json ->
                    writeProgress(json, book.id, progress)
                }
            }
            completedSteps++
            report(LegacyExportPhase.BOOK, book.id, null, "$bookDirectory/progress.json")

            for (edition in editions) {
                currentCoroutineContext().ensureActive()
                val editionBase = reservePath(
                    "$bookDirectory/editions/${pad(edition.id)}_" +
                        LegacyExportPaths.safeSegment(edition.name, "edition", 48),
                    usedPaths,
                )
                val chapterCount = try {
                    val editionChapters = bookDao.getEditionChapters(edition.id)
                    val chaptersDirectory = File(
                        context.filesDir,
                        "books/book_${book.id}/editions/edition_${edition.id}/chapters",
                    )
                    val missingFiles = editionChapters.count { chapter ->
                        chapter.contentFileName.isNotBlank() &&
                            !File(chaptersDirectory, File(chapter.contentFileName).name).isFile
                    }
                    if (missingFiles > 0) {
                        recordFailure(
                            failures = failures,
                            onFailure = onFailure,
                            failure = LegacyExportFailure(
                                scope = "edition-files",
                                bookId = book.id,
                                editionId = edition.id,
                                message = "Missing $missingFiles edition chapter file(s); database text was still exported.",
                            ),
                        )
                    }
                    editionChapters.size
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    recordFailure(
                        failures = failures,
                        onFailure = onFailure,
                        failure = LegacyExportFailure(
                            scope = "edition-chapters",
                            bookId = book.id,
                            editionId = edition.id,
                            message = failureMessage(error),
                        ),
                    )
                    0
                }
                if (chapterCount == 0) {
                    warnings += "Edition ${edition.id} has no chapters; empty TXT/EPUB entries were exported."
                }

                if (options.includeTxt) {
                    attemptFile(
                        scope = "edition-txt",
                        bookId = book.id,
                        editionId = edition.id,
                        path = "$editionBase.txt",
                        failures = failures,
                        onFailure = onFailure,
                    ) {
                        val exported = exporter.exportTxt(book.id, edition.id, exportOptions)
                        if (!exported.isFile) throw IOException("Edition TXT was not produced")
                        writer.copy(exported, "$editionBase.txt", "edition-txt", chapterCount)
                        runCatching { exported.delete() }
                    }
                    completedSteps++
                    report(LegacyExportPhase.EDITION, book.id, edition.id, "$editionBase.txt")
                }

                if (options.includeEpub) {
                    attemptFile(
                        scope = "edition-epub",
                        bookId = book.id,
                        editionId = edition.id,
                        path = "$editionBase.epub",
                        failures = failures,
                        onFailure = onFailure,
                    ) {
                        val exported = exporter.exportEpub(book.id, edition.id, exportOptions)
                        if (!exported.isFile) throw IOException("Edition EPUB was not produced")
                        writer.copy(exported, "$editionBase.epub", "edition-epub", chapterCount)
                        runCatching { exported.delete() }
                    }
                    completedSteps++
                    report(LegacyExportPhase.EDITION, book.id, edition.id, "$editionBase.epub")
                }
            }
        }

        val chatSessions = mutableListOf<Pair<AgentSessionEntity, List<AgentMessageEntity>>>()
        try {
            for (session in store.database.agentSessionDao().observeAll().first()) {
                currentCoroutineContext().ensureActive()
                val messages = store.database.agentMessageDao().getBySession(session.id)
                // Only user-visible conversation turns are exported. SYSTEM prompts and tool
                // arguments/results can contain provider secrets and are deliberately excluded.
                chatSessions += session to messages.filter {
                    it.role == "USER" || it.role == "ASSISTANT"
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            recordFailure(
                failures = failures,
                onFailure = onFailure,
                failure = LegacyExportFailure(
                    scope = "chat",
                    message = failureMessage(error),
                ),
            )
        }

        val chatEntries = chatSessions.size + chatSessions.sumOf { it.second.size }
        attemptFile(
            scope = "chat",
            path = "chat/sessions.json",
            failures = failures,
            onFailure = onFailure,
        ) {
            writer.json("chat/sessions.json", "chat", chatEntries) { json ->
                writeChat(json, chatSessions)
            }
        }
        completedSteps++
        report(LegacyExportPhase.CHAT, null, null, "chat/sessions.json")

        report(LegacyExportPhase.FINALIZING, null, null, null)
        writer.verify()

        val generatedAtMillis = clock()
        val manifest = LegacyExportManifest(
            generatedAt = LegacyExportTime.isoUtc(generatedAtMillis),
            generatedAtEpochMillis = generatedAtMillis,
            sourceDatabase = store.source,
            books = books.size,
            editions = editionsByBook.values.sumOf { it.size },
            files = writer.files.toList(),
            failures = failures.toList(),
            warnings = warnings.toList(),
        )
        writer.writeManifest(manifest)

        if (!workRoot.deleteRecursively() && workRoot.exists()) {
            throw IOException("Unable to remove the export work directory")
        }
        if (File(staging, "manifest.json").length() <= 0L) {
            throw IOException("Manifest was not written")
        }
        if (output.exists()) {
            throw IOException("Output directory already exists: ${output.name}")
        }
        if (!staging.renameTo(output)) {
            throw IOException("Unable to publish the export package")
        }

        val manifestFile = File(output, "manifest.json")
        return LegacyExportResult(
            outputDirectory = output,
            manifest = manifest,
            manifestFile = manifestFile,
        )
    }

    private suspend fun attemptFile(
        scope: String,
        failures: MutableList<LegacyExportFailure>,
        onFailure: suspend (LegacyExportFailure) -> Unit,
        path: String? = null,
        bookId: Long? = null,
        editionId: Long? = null,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (limit: LegacyExportLimitException) {
            throw limit
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (error: Throwable) {
            recordFailure(
                failures = failures,
                onFailure = onFailure,
                failure = LegacyExportFailure(
                    scope = scope,
                    bookId = bookId,
                    editionId = editionId,
                    path = path,
                    message = failureMessage(error),
                ),
            )
        }
    }

    private suspend fun recordFailure(
        failures: MutableList<LegacyExportFailure>,
        onFailure: suspend (LegacyExportFailure) -> Unit,
        failure: LegacyExportFailure,
    ) {
        failures += failure
        try {
            onFailure(failure)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            // A diagnostic observer must not turn a recoverable item failure into a fatal one.
        }
    }

    private suspend fun notifyProgress(
        callback: suspend (LegacyExportProgress) -> Unit,
        progress: LegacyExportProgress,
    ) {
        try {
            callback(progress)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            // Progress is informational and must not abort an otherwise valid package.
        }
    }

    private fun reservePath(relativePath: String, used: MutableSet<String>): String {
        var candidate = relativePath
        var counter = 2
        while (!used.add(candidate.lowercase(Locale.ROOT))) {
            val slash = relativePath.lastIndexOf('/')
            val dot = relativePath.lastIndexOf('.')
            candidate = if (dot > slash) {
                relativePath.substring(0, dot) + "_$counter" + relativePath.substring(dot)
            } else {
                relativePath + "_$counter"
            }
            counter++
            check(counter < 100_000) { "Unable to allocate a unique export path" }
        }
        return candidate
    }

    private fun pad(value: Long): String = String.format(Locale.US, "%06d", value)

    private fun csv(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun failureMessage(error: Throwable): String {
        val raw = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return LegacyRedaction.redact(raw).take(500)
    }

    private fun writeBook(
        json: LegacyJsonWriter,
        book: BookEntity,
        editions: List<EditionEntity>,
        projects: List<TranslationProjectV2Entity>,
    ) {
        json.beginObject()
        json.field("id", book.id)
        json.field("title", book.title)
        json.field("author", book.author)
        json.field("description", book.description)
        json.field("language", book.originalLanguage)
        json.field("createdAt", book.createdAt)
        json.field("updatedAt", book.updatedAt)
        json.field("hiddenFromShelf", book.hiddenFromShelf)
        json.field("shelfOrder", book.shelfOrder)
        nullableLong(json, "primaryEditionId", book.primaryEditionId)
        nullableLong(json, "preferredReadingEditionId", book.preferredReadingEditionId)
        json.name("editions")
        json.beginArray()
        for (edition in editions) writeEdition(json, edition)
        json.endArray()
        json.name("projectIds")
        json.beginArray()
        for (project in projects) json.value(project.id)
        json.endArray()
        json.endObject()
    }

    private fun writeEdition(json: LegacyJsonWriter, edition: EditionEntity) {
        json.beginObject()
        json.field("id", edition.id)
        json.field("bookId", edition.bookId)
        json.field("name", edition.name)
        json.field("type", edition.type)
        json.field("language", edition.language)
        nullableLong(json, "sourceEditionId", edition.sourceEditionId)
        json.field("isComplete", edition.isComplete)
        json.field("createdAt", edition.createdAt)
        json.field("updatedAt", edition.updatedAt)
        json.endObject()
    }

    private fun writeProject(json: LegacyJsonWriter, project: TranslationProjectV2Entity) {
        json.beginObject()
        json.field("id", project.id)
        json.field("bookId", project.bookId)
        json.field("sourceEditionId", project.sourceEditionId)
        json.field("targetEditionId", project.targetEditionId)
        json.field("sourceLanguage", project.sourceLanguage)
        json.field("targetLanguage", project.targetLanguage)
        json.field("providerConfigured", project.providerId != null)
        json.field("modelName", LegacyRedaction.redact(project.modelName))
        json.field("styleGuide", LegacyRedaction.redact(project.styleGuide))
        json.field("promptProtocolVersion", project.promptProtocolVersion)
        json.field("translationMode", project.translationMode)
        json.field("maxBatchChapters", project.maxBatchChapters)
        json.field("parallelChapters", project.parallelChapters)
        json.field("continuityMode", project.continuityMode)
        json.field("metadataEnabled", project.metadataEnabled)
        json.field("promptCacheEnabled", project.promptCacheEnabled)
        json.field("seamlessAheadChapters", project.seamlessAheadChapters)
        nullableInt(json, "rangeStart", project.rangeStart)
        nullableInt(json, "rangeEnd", project.rangeEnd)
        json.field("highQualityReview", project.highQualityReview)
        json.field("state", project.state)
        json.field("createdAt", project.createdAt)
        json.field("updatedAt", project.updatedAt)
        json.endObject()
    }

    private fun writeQualityRule(json: LegacyJsonWriter, rule: QualityRuleSetEntity) {
        json.beginObject()
        json.field("projectId", rule.translationProjectId)
        json.field("type", rule.type)
        json.field("enabled", rule.enabled)
        json.field("mode", rule.mode)
        json.field("revision", rule.revision)
        json.field("updatedAt", rule.updatedAt)
        json.name("entries")
        json.beginArray()
        val type = QualityRuleType.fromStorageKey(rule.type)
        if (type != null) {
            val entries = QualityRuleJson.decode(type, rule.entriesJson)
            for (entry in entries) {
                when (entry) {
                    is TextPreserveRuleEntry -> {
                        json.beginObject()
                        json.field("kind", "TEXT_PRESERVE")
                        json.field("src", LegacyRedaction.redact(entry.src))
                        json.field("note", LegacyRedaction.redact(entry.note))
                        json.endObject()
                    }
                    is ReplacementRuleEntry -> {
                        json.beginObject()
                        json.field("kind", type.storageKey)
                        json.field("pattern", LegacyRedaction.redact(entry.pattern))
                        json.field("replacement", LegacyRedaction.redact(entry.replacement))
                        json.field("isRegex", entry.isRegex)
                        json.field("caseSensitive", entry.caseSensitive)
                        json.field("note", LegacyRedaction.redact(entry.note))
                        json.endObject()
                    }
                    else -> {
                        // Future QualityRuleEntry variants are exported as an unparsed raw value.
                        json.beginObject()
                        json.field("kind", "UNKNOWN")
                        json.field("rawValue", LegacyRedaction.redact(entry.toString()))
                        json.endObject()
                    }
                }
            }
            if (entries.isEmpty() && rule.entriesJson.isNotBlank()) {
                json.beginObject()
                json.field("kind", "UNPARSED")
                json.field("rawJson", LegacyRedaction.redact(rule.entriesJson))
                json.endObject()
            }
        } else {
            json.beginObject()
            json.field("kind", "UNKNOWN_TYPE")
            json.field("rawJson", LegacyRedaction.redact(rule.entriesJson))
            json.endObject()
        }
        json.endArray()
        json.endObject()
    }

    private fun writePrompt(json: LegacyJsonWriter, prompt: PromptProfileEntity) {
        json.beginObject()
        json.field("projectId", prompt.translationProjectId)
        json.field("version", prompt.version)
        json.field("includeMetadataRules", prompt.includeMetadataRules)
        json.field("createdAt", prompt.createdAt)
        json.field("translationSystemPrompt", LegacyRedaction.redact(prompt.translationSystemPrompt))
        json.field("translationUserPromptTemplate", LegacyRedaction.redact(prompt.translationUserPromptTemplate))
        json.field("polishSystemPrompt", LegacyRedaction.redact(prompt.polishSystemPrompt))
        json.field("polishUserPromptTemplate", LegacyRedaction.redact(prompt.polishUserPromptTemplate))
        json.endObject()
    }

    private fun writeProgress(
        json: LegacyJsonWriter,
        bookId: Long,
        progress: ReaderProgressEntity?,
    ) {
        json.beginObject()
        json.field("bookId", bookId)
        if (progress == null) {
            json.nullField("progress")
        } else {
            json.name("progress")
            json.beginObject()
            nullableLong(json, "preferredEditionId", progress.preferredEditionId)
            nullableLong(json, "logicalChapterId", progress.logicalChapterId)
            nullableLong(json, "logicalSegmentId", progress.logicalSegmentId)
            json.field("segmentOffset", progress.segmentOffset)
            json.field("displayMode", progress.displayMode)
            json.field("pagingMode", progress.pagingMode)
            json.field("readerLayoutMode", progress.readerLayoutMode)
            json.field("pageAnimation", progress.pageAnimation)
            json.field("fontSizeSp", progress.fontSizeSp.toDouble())
            json.field("fontFamily", progress.fontFamily)
            json.field("letterSpacingSp", progress.letterSpacingSp.toDouble())
            json.field("lineSpacingMultiplier", progress.lineSpacingMultiplier.toDouble())
            json.field("paragraphSpacingDp", progress.paragraphSpacingDp.toDouble())
            json.field("pageMarginDp", progress.pageMarginDp.toDouble())
            json.field("useTraditionalChinese", progress.useTraditionalChinese)
            json.field("readerBackground", progress.readerBackground)
            json.field("updatedAt", progress.updatedAt)
            json.endObject()
        }
        json.endObject()
    }

    private fun writeChat(
        json: LegacyJsonWriter,
        sessions: List<Pair<AgentSessionEntity, List<AgentMessageEntity>>>,
    ) {
        json.beginObject()
        json.field("credentials_excluded", true)
        json.field("excludedRoles", "SYSTEM,TOOL_CALL,TOOL_RESULT")
        json.name("sessions")
        json.beginArray()
        for ((session, messages) in sessions) {
            json.beginObject()
            json.field("id", session.id)
            json.field("title", LegacyRedaction.redact(session.title))
            nullableLong(json, "bookId", session.bookId)
            nullableLong(json, "translationProjectId", session.translationProjectId)
            json.field("modelName", session.modelName?.let { LegacyRedaction.redact(it) })
            json.field("state", session.state)
            json.field("createdAt", session.createdAt)
            json.field("updatedAt", session.updatedAt)
            json.field("lastMessagePreview", LegacyRedaction.redact(session.lastMessagePreview))
            json.field("visibleMessageCount", messages.size)
            json.name("messages")
            json.beginArray()
            for (message in messages) {
                json.beginObject()
                json.field("id", message.id)
                json.field("role", message.role)
                json.field("content", LegacyRedaction.redact(message.content))
                json.field("isError", message.isError)
                json.field("createdAt", message.createdAt)
                json.endObject()
            }
            json.endArray()
            json.endObject()
        }
        json.endArray()
        json.endObject()
    }

    private fun nullableLong(json: LegacyJsonWriter, name: String, value: Long?) {
        if (value == null) json.nullField(name) else json.field(name, value)
    }

    private fun nullableInt(json: LegacyJsonWriter, name: String, value: Int?) {
        if (value == null) json.nullField(name) else json.field(name, value)
    }
}

/** A Context whose files tree is the disposable export work directory used by [EditionExporter]. */
private class StagingContext(
    base: Context,
    private val root: File,
) : ContextWrapper(base) {
    override fun getFilesDir(): File = root
    override fun getCacheDir(): File = File(root, ".cache")
}

/**
 * Writes files under [staging] only, enforces per-file/total/entry budgets, and records SHA-256
 * hashes. The manifest is deliberately not part of its own file list (it cannot hash itself).
 */
private class LegacyPackageWriter(
    private val staging: File,
    private val options: LegacyExportOptions,
) {
    val files = mutableListOf<LegacyExportFile>()
    private var totalBytes = 0L
    private var totalEntries = 0L
    private val root = staging.canonicalFile

    suspend fun json(
        relativePath: String,
        kind: String,
        entries: Int,
        block: (LegacyJsonWriter) -> Unit,
    ) {
        write(relativePath, kind, entries) { output ->
            val writer = OutputStreamWriter(output, Charsets.UTF_8)
            val json = LegacyJsonWriter(writer)
            block(json)
            json.flush()
        }
    }

    suspend fun text(
        relativePath: String,
        kind: String,
        entries: Int,
        block: (Appendable) -> Unit,
    ) {
        write(relativePath, kind, entries) { output ->
            val writer = OutputStreamWriter(output, Charsets.UTF_8)
            block(writer)
            writer.flush()
        }
    }

    suspend fun copy(
        source: File,
        relativePath: String,
        kind: String,
        entries: Int,
    ) {
        write(relativePath, kind, entries) { output ->
            source.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    suspend fun verify() {
        for (record in files) {
            currentCoroutineContext().ensureActive()
            val file = resolve(record.path)
            if (!file.isFile) throw IOException("Exported file is missing: ${record.path}")
            if (file.length() != record.bytes) {
                throw IOException("Exported file size changed: ${record.path}")
            }
            if (sha256(file) != record.sha256) {
                throw IOException("Exported file hash changed: ${record.path}")
            }
        }
    }

    fun writeManifest(manifest: LegacyExportManifest): File {
        val target = resolve("manifest.json")
        if (target.exists()) throw IOException("Manifest already exists in staging")
        val counting = CountingOutputStream(FileOutputStream(target), options.maxFileBytes)
        try {
            BufferedOutputStream(counting).use { buffered ->
                val writer = OutputStreamWriter(buffered, Charsets.UTF_8)
                writeManifestJson(writer, manifest)
                writer.flush()
            }
        } catch (error: Throwable) {
            target.delete()
            if (error is LegacyExportFileTooLargeException) {
                throw LegacyExportLimitException(error.message ?: "Manifest is too large")
            }
            throw error
        }
        val bytes = counting.count
        if (totalBytes + bytes > options.maxTotalBytes) {
            throw LegacyExportLimitException("Export exceeds the total size budget")
        }
        totalBytes += bytes
        // A parse check makes a truncated/corrupt manifest a hard failure before publishing.
        JSONObject(target.readText(Charsets.UTF_8))
        return target
    }

    private suspend fun write(
        relativePath: String,
        kind: String,
        entries: Int,
        block: suspend (OutputStream) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        if (totalEntries + entries > options.maxEntries) {
            throw LegacyExportLimitException("Export exceeds the entry count budget")
        }
        val target = resolve(relativePath)
        if (target.exists()) throw IOException("Export file already exists")
        target.parentFile?.mkdirs()
        val counting = CountingOutputStream(FileOutputStream(target), options.maxFileBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            DigestOutputStream(counting, digest).use { raw ->
                BufferedOutputStream(raw).use { buffered ->
                    block(buffered)
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        val bytes = counting.count
        if (totalBytes + bytes > options.maxTotalBytes) {
            throw LegacyExportLimitException("Export exceeds the total size budget")
        }
        totalBytes += bytes
        totalEntries += entries
        files += LegacyExportFile(
            path = relativePath,
            kind = kind,
            bytes = bytes,
            sha256 = digest.digest().toHex(),
            entries = entries,
        )
    }

    private fun resolve(relativePath: String): File {
        if (relativePath.isBlank() ||
            relativePath.startsWith('/') ||
            relativePath.contains('\\') ||
            relativePath.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) {
            throw IOException("Unsafe export path")
        }
        val target = File(staging, relativePath.replace('/', File.separatorChar)).canonicalFile
        if (target == root || !target.path.startsWith(root.path + File.separator)) {
            throw IOException("Export path escapes the staging directory")
        }
        return target
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun writeManifestJson(writer: java.io.Writer, manifest: LegacyExportManifest) {
        val json = LegacyJsonWriter(writer)
        json.beginObject()
        json.field("schema", manifest.schema)
        json.field("version", manifest.version)
        json.field("generatedAt", manifest.generatedAt)
        json.field("generatedAtEpochMillis", manifest.generatedAtEpochMillis)
        json.field("credentials_excluded", manifest.credentialsExcluded)
        json.field("migration", manifest.migration)
        json.field("read_only", manifest.readOnly)
        json.name("sourceDatabase")
        json.beginObject()
        json.field("fileName", manifest.sourceDatabase.fileName)
        json.field("schemaVersion", manifest.sourceDatabase.schemaVersion)
        json.field("bytes", manifest.sourceDatabase.bytes)
        json.field("sha256", manifest.sourceDatabase.sha256)
        json.endObject()
        json.name("counts")
        json.beginObject()
        json.field("books", manifest.books)
        json.field("editions", manifest.editions)
        json.field("files", manifest.files.size)
        json.field("entries", manifest.entryCount)
        json.field("bytes", manifest.totalBytes)
        json.field("failures", manifest.failures.size)
        json.endObject()
        json.name("files")
        json.beginArray()
        for (file in manifest.files) {
            json.beginObject()
            json.field("path", file.path)
            json.field("kind", file.kind)
            json.field("bytes", file.bytes)
            json.field("sha256", file.sha256)
            json.field("entries", file.entries)
            json.endObject()
        }
        json.endArray()
        json.name("failures")
        json.beginArray()
        for (failure in manifest.failures) {
            json.beginObject()
            json.field("scope", failure.scope)
            nullableLong(json, "bookId", failure.bookId)
            nullableLong(json, "editionId", failure.editionId)
            json.field("path", failure.path)
            json.field("message", failure.message)
            json.endObject()
        }
        json.endArray()
        json.name("warnings")
        json.beginArray()
        for (warning in manifest.warnings) json.value(warning)
        json.endArray()
        json.endObject()
        json.flush()
    }

    private fun nullableLong(json: LegacyJsonWriter, name: String, value: Long?) {
        if (value == null) json.nullField(name) else json.field(name, value)
    }
}

private class CountingOutputStream(
    out: OutputStream,
    private val maxBytes: Long,
) : FilterOutputStream(BufferedOutputStream(out)) {
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        ensure(1L)
        this.out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensure(len.toLong())
        this.out.write(b, off, len)
        count += len
    }

    private fun ensure(extra: Long) {
        if (count + extra > maxBytes) {
            throw LegacyExportFileTooLargeException(
                "File exceeds the single-file budget of $maxBytes bytes"
            )
        }
    }
}

private class LegacyExportFileTooLargeException(message: String) : IOException(message)

private fun ByteArray.toHex(): String {
    val builder = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xff
        builder.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
    }
    return builder.toString()
}

private val HEX = "0123456789abcdef".toCharArray()
