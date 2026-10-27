package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int

/**
 * Concrete IO adapter for the native TXT workspace. Inject the application's single
 * RuntimePort; manual writes cannot run alongside an Agent/translation run. Each operation
 * opens/closes its project connection. Room owns local instance registrations and device-local
 * approval bindings; neither authority is imported from a .lnt file.
 *
 * Read helpers open the project read-write on purpose: SQLite can only roll back a hot journal
 * on a writable connection, so keeping writes recoverable matters more than avoiding the write
 * flag on reads. All calls of one adapter are serialized by [mutex]; separate instances must not
 * share one project directory.
 */
class NativeTextProjectStore(
    context: Context,
    private val runtime: RuntimePort,
    private val limits: TextProjectLimits = TextProjectLimits(),
    private val importLimits: NativeImportLimits = NativeImportLimits(),
) : TextProjectPort, TranslationCommitPort, ProjectTextPolicyPort, TextItemNavigationPort, ProjectAssetPort, TranslationContextPort, TranslationReusePort, TranslationFailurePort, QueryManualBatchPort, ProjectNamePort, PdfPageReadPort, ProjectFileInventoryPort {
    private val io = Dispatchers.IO
    private val context = context.applicationContext
    private val app = NativeAppDatabase.open(this.context)
    private val mutex = Mutex()
    private var closed = false
    private val protectedCopies = mutableSetOf<String>()

    override suspend fun registeredProjects(limit: Int): List<RegisteredProject> = operation {
        // One extra record lets whole-set backups detect their 100-project limit.
        require(limit in 1..101)
        app.instances().list(limit).map { record ->
            val ref = record.ref()
            val summary = try {
                project(ref) { db -> NativeProjectDatabase.transaction(db) {
                    NativeProjectDatabase.summary(db, ref)
                } }
            } catch (error: TextProjectException) {
                // One missing or corrupt private copy must not hide the other recent projects.
                return@map RegisteredProject(ref, null, error.reason)
            } catch (_: IOException) {
                return@map RegisteredProject(ref, null, TextProjectException.Reason.STORAGE_IO)
            } catch (_: android.database.sqlite.SQLiteDatabaseCorruptException) {
                return@map RegisteredProject(ref, null, TextProjectException.Reason.CORRUPT_PROJECT)
            } catch (_: android.database.sqlite.SQLiteException) {
                return@map RegisteredProject(ref, null, TextProjectException.Reason.STORAGE_IO)
            }
            RegisteredProject(ref, summary, null)
        }
    }

    override suspend fun createTxt(commandId: CommandId, name: String, path: RelativePath,
                                   source: ByteArray, encoding: TxtEncoding): TextProjectSummary = operation {
        require(name.isNotBlank() && name.length <= 200 && name.none { it.isISOControl() })
        require(path.value.endsWith(".txt", ignoreCase = true)) { "TXT asset required" }
        if (source.size > limits.maxSourceBytes) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        val bytes = source.copyOf()
        TextProjectFingerprint.utf8(name)
        TextProjectFingerprint.utf8(path.value)
        val lines = TxtDocument.lines(bytes, encoding, limits)
        val digest = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(buildJsonObject {
            put("version", 1); put("name", name); put("path", path.value)
            put("source", TextProjectFingerprint.bytes(bytes)); put("encoding", encoding.name)
        }.toString()))
        val coroutine = currentCoroutineContext()
        withWriteLease {
            recoverPublished { coroutine.ensureActive() }
            val existing = app.instances().byCreationCommand(commandId.value)
            if (existing != null) return@withWriteLease existingCreation(existing, digest)
            val id = UUID.randomUUID().toString()
            val projectId = ProjectId(UUID.randomUUID().toString())
            val root = directory(context.filesDir, "native_projects")
            val staging = File(root, ".staging-$id")
            if (!staging.mkdir()) fail(TextProjectException.Reason.STORAGE_IO)
            var published = false
            try {
                NativeProjectDatabase.create(File(staging, PROJECT_FILE), projectId, name, path, bytes, lines) {
                    coroutine.ensureActive()
                }
                currentCoroutineContext().ensureActive()
                val record = ProjectInstanceRecord(id, projectId.value, 1, commandId.value, digest)
                writeRegistration(staging, record)
                val target = File(root, id)
                if (target.exists() || !staging.renameTo(target)) {
                    fail(TextProjectException.Reason.STORAGE_IO)
                }
                published = true
                // If this fails, preserve the published directory for reconciliation. Never expose
                // a half-written project via the index or delete an uncertain committed result.
                try {
                    app.instances().insert(record)
                } catch (error: SQLiteConstraintException) {
                    val winner = app.instances().byCreationCommand(commandId.value) ?: throw error
                    return@withWriteLease existingCreation(winner, digest)
                }
                project(record.ref()) { NativeProjectDatabase.summary(it, record.ref()) }
            } finally {
                if (!published) staging.deleteRecursively() // Only this call's unpublished staging.
            }
        }
    }

    override suspend fun open(ref: LocalProjectRef): TextProjectSummary = operation {
        project(ref) { db -> NativeProjectDatabase.transaction(db) { NativeProjectDatabase.summary(db, ref) } }
    }

    override suspend fun textPolicy(ref: LocalProjectRef): ProjectPolicySnapshot = operation {
        project(ref) { db -> NativeProjectDatabase.transaction(db) { ProjectWriteStore.policy(db, ref) } }
    }

    override suspend fun editTextPolicy(command: ProjectPolicyEdit): PolicyEditReceipt = operation {
        val coroutine = currentCoroutineContext()
        TextRuleValidation.validate(command.policy) { coroutine.ensureActive() }
        withWriteLease {
            project(command.ref) { db -> ProjectWriteStore.editPolicy(db, command) { coroutine.ensureActive() } }
        }
    }

    override suspend fun textPolicyReceipt(command: ProjectPolicyEdit): PolicyEditReceipt? = operation {
        project(command.ref) { db -> NativeProjectDatabase.transaction(db) {
            NativeProjectDatabase.summary(db, command.ref)
            ProjectWriteStore.checkedPolicyReceipt(db, command)
        } }
    }

    override suspend fun pdfPage(ref: LocalProjectRef, revision: Long, asset: ProjectAssetSummary, page: Int): TextProjectItem = operation {
        require(revision >= 0 && asset.format == ProjectAssetFormat.PDF && page in 1..asset.itemCount)
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            if (summary.revision != revision) fail(TextProjectException.Reason.STALE_PAGE)
            val current = summary.assets.firstOrNull { it.id == asset.id }
                ?: fail(TextProjectException.Reason.STALE_PAGE)
            if (current.format != asset.format || current.sha256 != asset.sha256 || current.itemCount != asset.itemCount)
                fail(TextProjectException.Reason.STALE_PAGE)
            db.rawQuery("SELECT ${itemColumns(db)} FROM items WHERE asset_id=? AND row_index=? ORDER BY id LIMIT 2",
                arrayOf(asset.id.toString(), page.toString())).use { cursor ->
                if (!cursor.moveToFirst()) fail(TextProjectException.Reason.CORRUPT_PROJECT)
                val result = NativeProjectDatabase.item(cursor, db.version)
                if (cursor.moveToNext() || result.metadata.tag != "page:$page") fail(TextProjectException.Reason.CORRUPT_PROJECT)
                result
            }
        } }
    }

    /** Page decisions only; the caller never receives extracted source text during approval prep. */
    internal suspend fun pdfPageDecisions(ref: LocalProjectRef, revision: Long, asset: ProjectAssetSummary,
                                         pages: List<Int>): Map<Int, PdfPageMode> = operation {
        require(revision >= 0 && asset.format == ProjectAssetFormat.PDF &&
            pages.size in 1..PdfPageChangeProposal.MAX_PAGES && pages.toSet().size == pages.size &&
            pages.all { it in 1..asset.itemCount })
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            if (summary.revision != revision) fail(TextProjectException.Reason.STALE_PAGE)
            val current = summary.assets.firstOrNull { it.id == asset.id }
                ?: fail(TextProjectException.Reason.STALE_PAGE)
            if (current.format != asset.format || current.sha256 != asset.sha256 || current.itemCount != asset.itemCount)
                fail(TextProjectException.Reason.STALE_PAGE)
            pages.sorted().associateWith { page ->
                db.rawQuery("SELECT tag,extra_field FROM items WHERE asset_id=? AND row_index=? ORDER BY id LIMIT 2",
                    arrayOf(asset.id.toString(), page.toString())).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.count != 1) fail(TextProjectException.Reason.CORRUPT_PROJECT)
                    PdfPageDecisionBinding.mode(page, cursor.getString(0), cursor.getString(1))
                }
            }
        } }
    }

    override suspend fun item(ref: LocalProjectRef, itemId: ItemId): TextItemDetails? = operation {
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            db.rawQuery("SELECT ${itemColumns(db)} FROM items WHERE id = ?",
                arrayOf(itemId.value.toString())).use {
                if (!it.moveToFirst()) null else TextItemDetails(summary.revision, NativeProjectDatabase.item(it, db.version))
            }
        } }
    }

    override suspend fun importProject(commandId: CommandId, source: () -> InputStream): TextProjectSummary = operation {
        val coroutine = currentCoroutineContext()
        withWriteLease {
            recoverPublished { coroutine.ensureActive() }
            val root = directory(context.filesDir, "native_projects")
            val id = UUID.randomUUID().toString()
            val staging = File(root, ".staging-$id")
            if (!staging.mkdir()) fail(TextProjectException.Reason.STORAGE_IO)
            var published = false
            try {
                val file = File(staging, PROJECT_FILE)
                val hash = MessageDigest.getInstance("SHA-256")
                var length = 0L
                source().use { input -> FileOutputStream(file).use { output ->
                    val buffer = ByteArray(NativeProjectDatabase.CHUNK_BYTES)
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        length += count
                        if (length > importLimits.maxFileBytes) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
                        hash.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                } }
                val digest = "import-v1:" + hash.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                val existing = app.instances().byCreationCommand(commandId.value)
                if (existing != null) return@withWriteLease existingCreation(existing, digest)
                val projectId = validateImport(file) { coroutine.ensureActive() }
                val record = ProjectInstanceRecord(id, projectId.value, 1, commandId.value, digest)
                writeRegistration(staging, record)
                coroutine.ensureActive()
                val target = File(root, id)
                if (target.exists() || !staging.renameTo(target)) fail(TextProjectException.Reason.STORAGE_IO)
                published = true
                app.instances().insert(record)
                project(record.ref()) { NativeProjectDatabase.summary(it, record.ref()) }
            } finally {
                if (!published) staging.deleteRecursively()
            }
        }
    }

    override suspend fun copyProject(commandId: CommandId, ref: LocalProjectRef): TextProjectSummary {
        val snapshot = operation { temporary("lnt").also { protectedCopies.add(it.absolutePath) } }
        try {
            saveProject(ref) { FileOutputStream(snapshot) }
            return importProject(commandId) { snapshot.inputStream() }
        } finally {
            withContext(io + NonCancellable) { mutex.withLock {
                protectedCopies.remove(snapshot.absolutePath)
                snapshot.delete()
            } }
        }
    }

    override suspend fun recoverProjects(): ProjectRecoveryReport = operation {
        val coroutine = currentCoroutineContext()
        withWriteLease { recoverPublished { coroutine.ensureActive() } }
    }

    override suspend fun cleanupTemporaryOutputs(): Int = operation {
        val files = directory(context.cacheDir, "native_project_snapshots").listFiles()
            ?: fail(TextProjectException.Reason.STORAGE_IO)
        var deleted = 0
        var failed = false
        for (file in files) {
            currentCoroutineContext().ensureActive()
            if (!file.isFile || file.canonicalFile != file.absoluteFile || !file.name.startsWith("snapshot-") ||
                file.extension !in setOf("lnt", "txt", "source") || file.absolutePath in protectedCopies) continue
            if (file.delete()) deleted++ else failed = true
        }
        if (failed) fail(TextProjectException.Reason.STORAGE_IO)
        deleted
    }

    override suspend fun readPage(ref: LocalProjectRef, afterId: Long, limit: Int): TextProjectPage = operation {
        require(afterId >= 0 && limit in 1..limits.maxPageSize)
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            TextProjectPage(summary, pageItems(db, afterId, limit), itemStatistics(db), summary.itemCount)
        } }
    }

    override suspend fun queryPage(ref: LocalProjectRef, query: TextItemQuery, afterId: Long, limit: Int): TextProjectPage = operation {
        require(afterId >= 0 && limit in 1..limits.maxPageSize)
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            queriedPage(db, ref, query, afterId, limit, null)
        } }
    }

    override suspend fun readItemWindow(ref: LocalProjectRef, query: TextItemQuery, window: TextItemWindow): TextProjectPage = operation {
        require(window.ids.size <= limits.maxPageSize)
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            queriedPage(db, ref, query, 0, window.ids.size, window)
        } }
    }

    private fun queriedPage(db: SQLiteDatabase, ref: LocalProjectRef, query: TextItemQuery,
        afterId: Long, limit: Int, window: TextItemWindow?): TextProjectPage {
        val summary = NativeProjectDatabase.summary(db, ref)
        val (where, arguments) = queryPredicate(query)
        val matchingStatistics = itemStatistics(db, where, arguments)
        val matching = matchingStatistics.total
        val selection: String
        val selectionArgs: Array<String>
        if (window == null) {
            selection = "$where AND id > ?"
            selectionArgs = (arguments + afterId.toString() + limit.toString()).toTypedArray()
        } else {
            selection = "id IN (${window.ids.joinToString(",") { "?" }})"
            selectionArgs = (window.ids.map { it.value.toString() } + limit.toString()).toTypedArray()
        }
        val rows = db.rawQuery("SELECT ${itemColumns(db)} FROM items WHERE $selection ORDER BY id LIMIT ?",
            selectionArgs).use { cursor ->
            buildList { while (cursor.moveToNext()) add(NativeProjectDatabase.item(cursor, db.version)) }
        }
        if (window != null && rows.map { it.id } != window.ids) fail(TextProjectException.Reason.STALE_PAGE)
        return TextProjectPage(summary, rows, itemStatistics(db), matching, matchingStatistics)
    }

    private fun queryPredicate(query: TextItemQuery): Pair<String, List<String>> {
        val clauses = mutableListOf("1 = 1")
        val arguments = mutableListOf<String>()
        query.assetId?.let { clauses.add("asset_id = ?"); arguments.add(it.toString()) }
        query.status?.let { clauses.add("status = ?"); arguments.add(it.name) }
        if (query.text.isNotEmpty()) {
            clauses.add(when (query.field) {
                TextSearchField.BOTH -> "(instr(source, ?) > 0 OR instr(translation, ?) > 0)"
                TextSearchField.SOURCE -> "instr(source, ?) > 0"
                TextSearchField.TRANSLATION -> "instr(translation, ?) > 0"
            })
            arguments.add(query.text)
            if (query.field == TextSearchField.BOTH) arguments.add(query.text)
        }
        return clauses.joinToString(" AND ") to arguments
    }

    override suspend fun adjacentTextItem(ref: LocalProjectRef, revision: Long, query: TextItemQuery,
        anchor: ItemId, direction: TextItemDirection): TextItemDetails? = operation {
        require(revision >= 0)
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            if (summary.revision != revision) fail(TextProjectException.Reason.STALE_PAGE)
            val (where, arguments) = queryPredicate(query)
            val comparison = if (direction == TextItemDirection.NEXT) ">" else "<"
            val order = if (direction == TextItemDirection.NEXT) "ASC" else "DESC"
            db.rawQuery("SELECT ${itemColumns(db)} FROM items WHERE $where AND id $comparison ? ORDER BY id $order LIMIT 1",
                (arguments + anchor.value.toString()).toTypedArray()).use { cursor ->
                if (cursor.moveToFirst()) TextItemDetails(summary.revision, NativeProjectDatabase.item(cursor, db.version)) else null
            }
        } }
    }

    override suspend fun committedDuplicate(ref: LocalProjectRef, revision: Long, afterRevision: Long,
        target: ItemId): TextItemDetails? = operation {
        require(afterRevision in 0..revision)
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            if (summary.revision != revision) fail(TextProjectException.Reason.STALE_PAGE)
            val item = db.rawQuery("SELECT ${itemColumns(db)} FROM items WHERE id = ?",
                arrayOf(target.value.toString())).use { cursor ->
                if (!cursor.moveToFirst()) fail(TextProjectException.Reason.STALE_PAGE)
                NativeProjectDatabase.item(cursor, db.version)
            }
            if (!item.metadata.isDefault) return@transaction null
            val asset = if (db.version >= 3) "asset_id = ? AND " else ""
            val args = mutableListOf<String>()
            if (db.version >= 3) args.add(item.assetId.toString())
            args.add(item.id.value.toString()); args.add(item.source)
            args.add(afterRevision.toString())
            db.rawQuery("SELECT ${itemColumns(db)} FROM items WHERE ${asset}id != ? AND source = ? " +
                "AND status = 'PROCESSED' AND length(translation) > 0 AND EXISTS " +
                "(SELECT 1 FROM item_revisions r WHERE r.item_id = items.id AND r.revision > ?) ORDER BY id",
                args.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    val candidate = NativeProjectDatabase.item(cursor, db.version)
                    if (candidate.metadata.isDefault && candidate.source == item.source)
                        return@transaction TextItemDetails(revision, candidate)
                }
                null
            }
        } }
    }

    override suspend fun precedingTranslationItems(ref: LocalProjectRef, revision: Long, anchor: ItemId,
        limit: Int): TextItemPage = operation {
        require(revision >= 0 && limit in 1..64)
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            if (summary.revision != revision) fail(TextProjectException.Reason.STALE_PAGE)
            val first = db.rawQuery("SELECT ${itemColumns(db)} FROM items WHERE id = ?",
                arrayOf(anchor.value.toString())).use { cursor ->
                if (!cursor.moveToFirst()) fail(TextProjectException.Reason.STALE_PAGE)
                NativeProjectDatabase.item(cursor, db.version)
            }
            val selection = if (db.version >= 3) "asset_id = ? AND slot < ?" else "row_index < ?"
            val arguments = if (db.version >= 3) arrayOf(first.assetId.toString(), first.slot.toString(), limit.toString())
                else arrayOf(first.row.toString(), limit.toString())
            val order = if (db.version >= 3) "slot DESC, id DESC" else "row_index DESC, id DESC"
            val rows = db.rawQuery("SELECT ${itemColumns(db)} FROM items WHERE $selection ORDER BY $order LIMIT ?",
                arguments).use { cursor ->
                buildList { while (cursor.moveToNext()) add(NativeProjectDatabase.item(cursor, db.version)) }
            }
            TextItemPage(revision, rows.reversed())
        } }
    }

    private fun itemStatistics(db: SQLiteDatabase, where: String = "1=1", arguments: List<String> = emptyList()): TextItemStatistics {
        val counts = mutableMapOf<TextItemStatus, Int>()
        var translatable = 0
        val enabled = if (db.version >= 3) " AND asset_id IN (SELECT id FROM assets WHERE enabled=1)" else ""
        db.rawQuery("SELECT status, COUNT(*), SUM(CASE WHEN status = 'NONE'$enabled THEN CASE WHEN length(trim(source, ?)) > 0 THEN 1 ELSE 0 END ELSE 0 END) FROM items WHERE $where GROUP BY status",
            (listOf(BLANK_SOURCE_CHARACTERS) + arguments).toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val status = try { TextItemStatus.valueOf(cursor.getString(0)) }
                catch (_: IllegalArgumentException) { fail(TextProjectException.Reason.CORRUPT_PROJECT) }
                counts[status] = cursor.getInt(1)
                if (status == TextItemStatus.NONE) translatable = cursor.getInt(2)
            }
        }
        return TextItemStatistics(counts[TextItemStatus.NONE] ?: 0, counts[TextItemStatus.PROCESSED] ?: 0,
            counts[TextItemStatus.EXCLUDED] ?: 0, translatable,
            (counts[TextItemStatus.RULE_SKIPPED] ?: 0) + (counts[TextItemStatus.LANGUAGE_SKIPPED] ?: 0),
            counts[TextItemStatus.ERROR] ?: 0, counts[TextItemStatus.DUPLICATED] ?: 0)
    }

    override suspend fun items(ref: LocalProjectRef, revision: Long, afterId: Long, limit: Int): TextItemPage = operation {
        require(afterId >= 0 && revision >= 0 && limit in 1..limits.maxPageSize)
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            if (summary.revision != revision) fail(TextProjectException.Reason.STALE_PAGE)
            TextItemPage(summary.revision, pageItems(db, afterId, limit))
        } }
    }

    private fun pageItems(db: SQLiteDatabase, afterId: Long, limit: Int): List<TextProjectItem> =
        db.rawQuery("SELECT ${itemColumns(db)} FROM items WHERE id > ? ORDER BY id LIMIT ?",
            arrayOf(afterId.toString(), limit.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(NativeProjectDatabase.item(cursor, db.version)) }
        }

    private fun itemColumns(db: SQLiteDatabase): String =
        if (db.version >= 3) NativeProjectDatabase.ITEM_COLUMNS else NativeProjectDatabase.ITEM_COLUMNS_V2

    override suspend fun fileInventory(ref: LocalProjectRef, revision: Long): ProjectFileInventory = operation {
        val coroutine = currentCoroutineContext()
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            if (summary.revision != revision) fail(TextProjectException.Reason.STALE_PAGE)
            val updated = HashMap<Long, Long>()
            db.rawQuery("""SELECT i.asset_id,MAX(r.committed_at) FROM item_revisions r
                JOIN items i ON i.id=r.item_id GROUP BY i.asset_id""", null).use { rows ->
                while (rows.moveToNext()) { coroutine.ensureActive(); updated[rows.getLong(0)] = rows.getLong(1) }
            }
            if (db.version >= 3) db.rawQuery("SELECT asset_id,MAX(committed_at) FROM asset_revisions WHERE asset_id IS NOT NULL GROUP BY asset_id", null).use { rows ->
                while (rows.moveToNext()) {
                    coroutine.ensureActive()
                    val id = rows.getLong(0)
                    updated[id] = maxOf(updated[id] ?: 0L, rows.getLong(1))
                }
            }
            val entries = summary.assets.map { asset ->
                coroutine.ensureActive()
                val present = db.rawQuery("SELECT COUNT(*),MIN(part),MAX(part),COALESCE(SUM(length(data)),0) FROM asset_chunks WHERE asset_id=?",
                    arrayOf(asset.id.toString())).use { row ->
                    check(row.moveToFirst())
                    val count = row.getLong(0)
                    row.getLong(3) == asset.originalSize &&
                        (if (count == 0L) asset.originalSize == 0L else row.getLong(1) == 0L && row.getLong(2) == count - 1)
                }
                AssetFileInventory(asset.id, updated[asset.id],
                    if (present) AssetSourceAvailability.PRESENT else AssetSourceAvailability.INCOMPLETE)
            }
            ProjectFileInventory(ref, revision, entries)
        } }
    }

    override suspend fun edit(command: ManualTextEdit): ManualEditReceipt = operation {
        if (command.translation.length > limits.maxItemChars) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        TextProjectFingerprint.utf8(command.translation)
        val coroutine = currentCoroutineContext()
        withWriteLease {
            project(command.ref) { db ->
                NativeProjectDatabase.summary(db, command.ref)
                coroutine.ensureActive()
                ProjectWriteStore.edit(db, command) { coroutine.ensureActive() }
            }
        }
    }

    override suspend fun editName(command: ManualNameEdit): ManualEditReceipt = operation {
        val coroutine = currentCoroutineContext()
        withWriteLease {
            project(command.ref) { db ->
                NativeProjectDatabase.summary(db, command.ref)
                coroutine.ensureActive()
                ProjectWriteStore.editName(db, command) { coroutine.ensureActive() }
            }
        }
    }

    override suspend fun checkedNameReceipt(command: ManualNameEdit): ManualEditReceipt? = operation {
        project(command.ref) { db ->
            NativeProjectDatabase.summary(db, command.ref)
            ProjectWriteStore.checkedNameReceipt(db, command)
        }
    }

    override suspend fun nameReceipt(ref: LocalProjectRef, commandId: CommandId): ManualEditReceipt? = operation {
        project(ref) { db ->
            NativeProjectDatabase.summary(db, ref)
            ProjectWriteStore.receipt(db, ref, commandId)
        }
    }

    override suspend fun prepareBatch(commandId: CommandId, ref: LocalProjectRef, revision: Long,
        window: TextItemWindow, change: TextBatchChange): ManualTextBatch? = operation {
        require(change != TextBatchChange.REPLACE_TRANSLATION && !change.assignsNames)
        require(revision >= 0 && window.ids.size <= limits.maxPageSize)
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            if (summary.revision != revision) fail(TextProjectException.Reason.STALE_PAGE)
            val page = queriedPage(db, ref, TextItemQuery(), 0, window.ids.size, window)
            val targets = page.items.filter { it.status != change.status || (change.clearsTranslation && it.translation.isNotEmpty()) }
                .map { TextBatchTarget(it.id, it.row, it.fingerprint) }
            if (targets.isEmpty()) null else ManualTextBatch(commandId, ref, revision, change, targets)
        } }
    }

    override suspend fun prepareQualityBatch(commandId: CommandId, ref: LocalProjectRef, revision: Long,
        selection: TextQualitySelection, change: TextBatchChange): ManualTextBatch? = operation {
        require(change != TextBatchChange.REPLACE_TRANSLATION && !change.assignsNames)
        if (selection.index.ref != ref || selection.index.revision != revision)
            fail(TextProjectException.Reason.STALE_PAGE)
        require(selection.ids.size <= limits.maxItems)
        val coroutine = currentCoroutineContext()
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            if (NativeProjectDatabase.summary(db, ref).revision != revision) fail(TextProjectException.Reason.STALE_PAGE)
            val (where, arguments) = queryPredicate(selection.index.query)
            val targets = buildList {
                for (id in selection.ids) {
                    coroutine.ensureActive()
                    val fact = selection.index.fact(id) ?: fail(TextProjectException.Reason.STALE_PAGE)
                    if (!selection.filter.matches(fact)) fail(TextProjectException.Reason.STALE_PAGE)
                    db.rawQuery("SELECT row_index,fingerprint,status,length(translation) FROM items WHERE id=? AND $where",
                        (listOf(id.value.toString()) + arguments).toTypedArray()).use { cursor ->
                        if (!cursor.moveToFirst() || cursor.getString(1) != fact.fingerprint.sha256)
                            fail(TextProjectException.Reason.STALE_PAGE)
                        if (cursor.getString(2) != change.status.name || (change.clearsTranslation && cursor.getLong(3) > 0))
                            add(TextBatchTarget(id, cursor.getInt(0), fact.fingerprint))
                    }
                }
            }
            if (targets.isEmpty()) null else ManualTextBatch(commandId, ref, revision, change, targets)
        } }
    }

    override suspend fun prepareQueryBatch(commandId: CommandId, ref: LocalProjectRef, revision: Long,
        query: TextItemQuery, change: TextBatchChange): ManualTextBatch? = operation {
        require(change != TextBatchChange.REPLACE_TRANSLATION && !change.assignsNames)
        require(revision >= 0)
        val coroutine = currentCoroutineContext()
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            if (summary.revision != revision) fail(TextProjectException.Reason.STALE_PAGE)
            val (where, arguments) = queryPredicate(query)
            val changes = if (change.clearsTranslation) "(status != ? OR translation != '')" else "status != ?"
            val targets = db.rawQuery("SELECT id,row_index,fingerprint FROM items WHERE $where AND $changes ORDER BY id",
                (arguments + change.status.name).toTypedArray()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        coroutine.ensureActive()
                        if (size >= limits.maxItems) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
                        add(TextBatchTarget(ItemId(cursor.getLong(0)), cursor.getInt(1), ObjectFingerprint(cursor.getString(2))))
                    }
                }
            }
            if (targets.isEmpty()) null else ManualTextBatch(commandId, ref, revision, change, targets)
        } }
    }

    override suspend fun prepareNameAssignmentBatch(commandId: CommandId, ref: LocalProjectRef, revision: Long,
        query: TextItemQuery, names: List<String>, quality: TextQualitySelection?): ManualTextBatch? =
        prepareTextChanges(commandId, ref, revision, query, null, names.toList(), quality, sourceNames = false)

    override suspend fun prepareSourceNameAssignmentBatch(commandId: CommandId, ref: LocalProjectRef, revision: Long,
        query: TextItemQuery, names: List<String>, quality: TextQualitySelection?): ManualTextBatch? =
        prepareTextChanges(commandId, ref, revision, query, null, names.toList(), quality, sourceNames = true)

    override suspend fun prepareReplacementBatch(commandId: CommandId, ref: LocalProjectRef, revision: Long,
        query: TextItemQuery, options: TextReplacementOptions, quality: TextQualitySelection?): ManualTextBatch? =
        prepareTextChanges(commandId, ref, revision, query, options, null, quality, sourceNames = false)

    private suspend fun prepareTextChanges(commandId: CommandId, ref: LocalProjectRef, revision: Long,
        query: TextItemQuery, options: TextReplacementOptions?, assignment: List<String>?,
        quality: TextQualitySelection?, sourceNames: Boolean): ManualTextBatch? = operation {
        require(revision >= 0)
        if (quality != null && (quality.index.ref != ref || quality.index.revision != revision || quality.index.query != query))
            fail(TextProjectException.Reason.STALE_PAGE)
        val coroutine = currentCoroutineContext()
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            if (NativeProjectDatabase.summary(db, ref).revision != revision) fail(TextProjectException.Reason.STALE_PAGE)
            val (where, arguments) = queryPredicate(query)
            val namesColumn = when {
                sourceNames -> if (db.version >= 3) "name_source" else "NULL"
                db.version >= 3 -> "name_translation"
                else -> "NULL"
            }
            val targets = ArrayList<TextBatchTarget>()
            fun consider(id: ItemId, row: Int, fingerprint: ObjectFingerprint, translation: String, names: String?) {
                coroutine.ensureActive()
                val oldNames = NativeProjectDatabase.decodeNames(names)
                if (assignment != null) {
                    // A name batch never changes the body, status or the other name column.
                    if (oldNames.orEmpty() != assignment) {
                        if (targets.size >= limits.maxItems) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
                        targets.add(TextBatchTarget(id, row, fingerprint))
                    }
                    return
                }
                val replaced = ItemTextReplacement.preview(translation, oldNames, requireNotNull(options)) { coroutine.ensureActive() }
                if (replaced.translation.length > limits.maxItemChars) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
                if (replaced.changed) {
                    if (targets.size >= limits.maxItems) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
                    targets.add(TextBatchTarget(id, row, fingerprint))
                }
            }
            if (quality == null) {
                db.rawQuery("SELECT id,row_index,fingerprint,translation,$namesColumn FROM items WHERE $where ORDER BY id",
                    arguments.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) consider(ItemId(cursor.getLong(0)), cursor.getInt(1),
                        ObjectFingerprint(cursor.getString(2)), cursor.getString(3), if (cursor.isNull(4)) null else cursor.getString(4))
                }
            } else {
                require(quality.ids.size <= limits.maxItems)
                for (id in quality.ids) {
                    coroutine.ensureActive()
                    val fact = quality.index.fact(id) ?: fail(TextProjectException.Reason.STALE_PAGE)
                    if (!quality.filter.matches(fact)) fail(TextProjectException.Reason.STALE_PAGE)
                    db.rawQuery("SELECT row_index,fingerprint,translation,$namesColumn FROM items WHERE id=? AND $where",
                        (listOf(id.value.toString()) + arguments).toTypedArray()).use { cursor ->
                        if (!cursor.moveToFirst() || cursor.getString(1) != fact.fingerprint.sha256)
                            fail(TextProjectException.Reason.STALE_PAGE)
                        consider(id, cursor.getInt(0), fact.fingerprint, cursor.getString(2), if (cursor.isNull(3)) null else cursor.getString(3))
                    }
                }
            }
            if (targets.isEmpty()) null else ManualTextBatch(commandId, ref, revision,
                if (assignment == null) TextBatchChange.REPLACE_TRANSLATION
                else if (sourceNames) TextBatchChange.ASSIGN_SOURCE_NAMES else TextBatchChange.ASSIGN_NAMES,
                targets.sortedBy { it.id.value }, options, assignment)
        } }
    }

    override suspend fun editBatch(command: ManualTextBatch): ManualBatchReceipt = operation {
        require(command.targets.size <= limits.maxItems)
        val coroutine = currentCoroutineContext()
        withWriteLease {
            project(command.ref) { db ->
                NativeProjectDatabase.summary(db, command.ref)
                val replacement = command.replacement
                if (command.nameAssignment != null || (replacement?.target != null && replacement.target != TextReplacementTarget.TRANSLATION))
                    NativeProjectDatabase.ensureV8(db)
                ProjectWriteStore.editBatch(db, command, limits.maxItemChars) { coroutine.ensureActive() }
            }
        }
    }

    /**
     * Transaction-internal mixed project entry. The parent must already be CONSUMED; the writer
     * checks its binding and every child reference, and commits all engineering members in one
     * .lnt transaction. Workspace file members remain with the later workspace coordinator.
     */
    internal suspend fun applyMixedProject(
        lease: RunLease,
        approval: MixedApproval,
        binding: MixedApprovalBinding,
        members: List<MixedProjectMemberInput>,
    ): MixedProjectWriteResult = operation {
        require(lease.identity.projectId == approval.ref.projectId && lease.identity.chatId == approval.chat &&
            lease.identity.runId == approval.run && lease.identity.commandId == approval.commandId &&
            runtime.snapshot().activeRun?.owner == RuntimeOwner.AGENT)
        // The supplied parent must be the durable consumed row, not a reconstructed authorization.
        val stored = requireNotNull(app.mixedApprovals().find(approval.id.value)) {
            "Unknown mixed approval"
        }
        require(stored.state == "CONSUMED" && stored.commandId == approval.commandId.value &&
            stored.digest == approval.digest.sha256 && stored.binding == binding.fingerprint.sha256 &&
            stored.instanceId == approval.ref.instanceId.value &&
            stored.generation == approval.ref.generation.value &&
            stored.projectId == approval.ref.projectId.value && stored.chatId == approval.chat.value &&
            stored.runId == approval.run.value && stored.baseRevision == approval.baseRevision) {
            "Mixed approval binding changed"
        }
        val coroutine = currentCoroutineContext()
        val write = runtime.beginProjectWrite(lease)
        try {
            project(approval.ref) { db ->
                NativeProjectDatabase.ensureV8(db)
                MixedProjectWriter.apply(db, approval.ref, approval, binding, members,
                    beforeCommit = { coroutine.ensureActive() }, maximumItems = limits.maxItems)
            }
        } finally { runtime.endProjectWrite(write) }
    }

    internal suspend fun approvedPolicy(lease: RunLease, proposal: PolicyChangeProposal): PolicyEditReceipt = operation {
        require(lease.identity.projectId == proposal.ref.projectId && lease.identity.chatId == proposal.chat &&
            lease.identity.runId == proposal.run && lease.identity.commandId == proposal.command &&
            runtime.snapshot().activeRun?.owner == RuntimeOwner.AGENT)
        val row = requireNotNull(app.policyProposals().find(proposal.id.value))
        require(row.state == "CONSUMED" && row.consumedAt != null && row.digest == proposal.digest.sha256)
        require(row.instanceId == proposal.ref.instanceId.value && row.generation == proposal.ref.generation.value &&
            row.projectId == proposal.ref.projectId.value && row.chatId == proposal.chat.value &&
            row.runId == proposal.run.value && row.commandId == proposal.command.value && row.revision == proposal.revision)
        val coroutine = currentCoroutineContext()
        TextRuleValidation.validate(proposal.after) { coroutine.ensureActive() }
        val write = runtime.beginProjectWrite(lease)
        try {
            project(proposal.ref) { db ->
                NativeProjectDatabase.summary(db, proposal.ref)
                ProjectWriteStore.editPolicy(db, proposal.intent()) { coroutine.ensureActive() }
            }
        } finally { runtime.endProjectWrite(write) }
    }

    internal suspend fun approvedItemsReceipt(proposal: ItemChangeProposal, selected: Set<ItemId>): ItemProposalReceipt? = operation {
        project(proposal.ref) { db ->
            NativeProjectDatabase.summary(db, proposal.ref)
            ProjectWriteStore.approvedItemsReceipt(db, proposal, selected)
        }
    }

    internal suspend fun approvedItems(lease: RunLease, proposal: ItemChangeProposal, selected: Set<ItemId>): ItemProposalReceipt = operation {
        require(lease.identity.projectId == proposal.ref.projectId && lease.identity.chatId == proposal.chat &&
            lease.identity.runId == proposal.run && lease.identity.commandId == proposal.command &&
            runtime.snapshot().activeRun?.owner == RuntimeOwner.AGENT)
        val row = requireNotNull(app.itemProposals().find(proposal.id.value))
        require(row.state == "CONSUMED" && row.digest == proposal.digest.sha256 && row.binding == proposal.binding(selected).sha256)
        require(row.instanceId == proposal.ref.instanceId.value && row.generation == proposal.ref.generation.value &&
            row.projectId == proposal.ref.projectId.value && row.chatId == proposal.chat.value &&
            row.runId == proposal.run.value && row.commandId == proposal.command.value && row.revision == proposal.revision)
        val coroutine = currentCoroutineContext()
        val write = runtime.beginProjectWrite(lease)
        try {
            project(proposal.ref) { db ->
                NativeProjectDatabase.summary(db, proposal.ref)
                ProjectWriteStore.approvedItems(db, proposal, selected) { coroutine.ensureActive() }
            }
        } finally { runtime.endProjectWrite(write) }
    }

    internal suspend fun approvedPdfPageReceipt(proposal: PdfPageChangeProposal, selected: Set<Int>): PdfPageProposalReceipt? = operation {
        project(proposal.ref) { db ->
            NativeProjectDatabase.summary(db, proposal.ref)
            ProjectWriteStore.approvedPdfPagesReceipt(db, proposal, selected)
        }
    }

    internal suspend fun approvedPdfPages(lease: RunLease, proposal: PdfPageChangeProposal,
                                          selected: Set<Int>): PdfPageProposalReceipt = operation {
        require(lease.identity.projectId == proposal.ref.projectId && lease.identity.chatId == proposal.chat &&
            lease.identity.runId == proposal.run && lease.identity.commandId == proposal.command &&
            runtime.snapshot().activeRun?.owner == RuntimeOwner.AGENT)
        val row = requireNotNull(app.pdfPageProposals().find(proposal.id.value))
        require(row.state in setOf("PENDING", "CONSUMED") && row.digest == proposal.digest.sha256)
        require(row.binding == proposal.binding(selected).sha256 &&
            PdfPageChangeProposalCodec.selection(requireNotNull(row.selected)) == selected)
        require(row.instanceId == proposal.ref.instanceId.value && row.generation == proposal.ref.generation.value &&
            row.projectId == proposal.ref.projectId.value && row.chatId == proposal.chat.value &&
            row.runId == proposal.run.value && row.commandId == proposal.command.value && row.revision == proposal.revision &&
            row.assetId == proposal.assetId && row.sha256 == proposal.assetSha256 && row.pageCount == proposal.pageCount)
        val coroutine = currentCoroutineContext()
        val write = runtime.beginProjectWrite(lease)
        try {
            project(proposal.ref) { db ->
                NativeProjectDatabase.summary(db, proposal.ref)
                ProjectWriteStore.approvedPdfPages(db, proposal, selected) { coroutine.ensureActive() }
            }
        } finally { runtime.endProjectWrite(write) }
    }

    internal suspend fun approvedReceipt(command: ManualTextEdit): ManualEditReceipt? = operation {
        project(command.ref) { db ->
            NativeProjectDatabase.summary(db, command.ref)
            ProjectWriteStore.checkedReceipt(db, command)
        }
    }

    internal suspend fun approvedEdit(lease: RunLease, command: ManualTextEdit): ManualEditReceipt = operation {
        if (lease.identity.projectId != command.ref.projectId || lease.identity.chatId == null ||
            runtime.snapshot().activeRun?.owner != RuntimeOwner.AGENT) fail(TextProjectException.Reason.WRONG_IDENTITY)
        if (command.translation.length > limits.maxItemChars) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        TextProjectFingerprint.utf8(command.translation)
        val coroutine = currentCoroutineContext()
        val write = runtime.beginProjectWrite(lease)
        try {
            project(command.ref) { db ->
                NativeProjectDatabase.summary(db, command.ref)
                ProjectWriteStore.edit(db, command) { coroutine.ensureActive() }
            }
        } finally { runtime.endProjectWrite(write) }
    }

    override suspend fun markTranslationFailures(lease: RunLease, batch: TranslationFailureBatch): Long = operation {
        if (lease.identity.projectId != batch.ref.projectId || lease.identity.chatId != null ||
            runtime.snapshot().activeRun?.owner != RuntimeOwner.TRANSLATION) fail(TextProjectException.Reason.WRONG_IDENTITY)
        val coroutine = currentCoroutineContext()
        val write = runtime.beginProjectWrite(lease)
        try {
            project(batch.ref) { db ->
                NativeProjectDatabase.ensureV3(db)
                NativeProjectDatabase.transaction(db) {
                    val summary = NativeProjectDatabase.summary(db, batch.ref)
                    if (summary.revision != batch.expectedRevision) fail(TextProjectException.Reason.STALE_PAGE)
                    var revision = summary.revision
                    for (edit in batch.edits) {
                        coroutine.ensureActive()
                        val item = db.rawQuery("SELECT ${itemColumns(db)} FROM items WHERE id = ?",
                            arrayOf(edit.itemId.value.toString())).use { cursor ->
                            if (!cursor.moveToFirst()) fail(TextProjectException.Reason.STALE_PAGE)
                            NativeProjectDatabase.item(cursor, db.version)
                        }
                        require(item.status == TextItemStatus.NONE || item.status == TextItemStatus.ERROR)
                        require(item.translation == edit.translation && item.fingerprint == edit.baseFingerprint)
                        val receipt = ProjectWriteStore.edit(db, edit) { coroutine.ensureActive() }
                        if (receipt.status != ManualEditStatus.COMMITTED) fail(TextProjectException.Reason.STALE_PAGE)
                        revision = receipt.revision
                    }
                    coroutine.ensureActive()
                    revision
                }
            }
        } finally { runtime.endProjectWrite(write) }
    }

    override suspend fun commitTranslation(lease: RunLease, batch: TranslationBatch): Long = operation {
        // Opaque lease validation is owned by RuntimePort, never by matching identity values alone.
        if (lease.identity.projectId != batch.ref.projectId || lease.identity.chatId != null ||
            runtime.snapshot().activeRun?.owner != RuntimeOwner.TRANSLATION) fail(TextProjectException.Reason.WRONG_IDENTITY)
        for (edit in batch.edits) {
            if (edit.translation.length > limits.maxItemChars) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            TextProjectFingerprint.utf8(edit.translation)
        }
        val coroutine = currentCoroutineContext()
        val write = runtime.beginProjectWrite(lease)
        try {
            project(batch.ref) { db -> NativeProjectDatabase.transaction(db) {
                val summary = NativeProjectDatabase.summary(db, batch.ref)
                if (summary.revision != batch.expectedRevision) fail(TextProjectException.Reason.STALE_PAGE)
                var revision = summary.revision
                for (edit in batch.edits) {
                    coroutine.ensureActive()
                    val receipt = ProjectWriteStore.edit(db, edit) { coroutine.ensureActive() }
                    if (receipt.status != ManualEditStatus.COMMITTED) fail(TextProjectException.Reason.STALE_PAGE)
                    revision = receipt.revision
                }
                coroutine.ensureActive()
                revision
            } }
        } finally { runtime.endProjectWrite(write) }
    }

    override suspend fun receipt(ref: LocalProjectRef, commandId: CommandId): ManualEditReceipt? = operation {
        project(ref) { db ->
            NativeProjectDatabase.summary(db, ref)
            ProjectWriteStore.receipt(db, ref, commandId)
        }
    }

    override suspend fun saveProject(ref: LocalProjectRef, destination: () -> OutputStream): Unit =
        saveProjectSnapshot(ref, destination, null)

    suspend fun saveProjectForArchive(ref: LocalProjectRef, destination: () -> OutputStream,
                                      archive: ArchiveReadLease): Unit = saveProjectSnapshot(ref, destination, archive)

    private suspend fun saveProjectSnapshot(ref: LocalProjectRef, destination: () -> OutputStream,
                                            archive: ArchiveReadLease?): Unit = operation {
        val snapshot = temporary("lnt")
        val coroutine = currentCoroutineContext()
        val write = if (archive == null) runtime.beginProjectWrite() else runtime.beginArchiveProjectWrite(archive)
        try {
            val source = file(ref) // Resolve Room identity before entering the project transaction.
            NativeProjectDatabase.open(source, writable = true).use { db ->
                NativeProjectDatabase.summary(db, ref)
                NativeProjectDatabase.snapshot(db, snapshot) { coroutine.ensureActive() }
            }
        }
        finally { runtime.endProjectWrite(write) }
        NativeProjectDatabase.open(snapshot).use { db ->
            NativeProjectDatabase.summary(db, ref)
            db.rawQuery("PRAGMA quick_check", null).use {
                if (!it.moveToFirst() || it.getString(0) != "ok") fail(TextProjectException.Reason.CORRUPT_PROJECT)
            }
        }
        deliver(snapshot, destination)
    }

    override suspend fun exportTxt(ref: LocalProjectRef, destination: () -> OutputStream): Unit = exportText(ref, false, false, destination)

    override suspend fun exportBilingual(ref: LocalProjectRef, deduplicateSame: Boolean, destination: () -> OutputStream): Unit =
        exportText(ref, true, deduplicateSame, destination)

    private suspend fun exportText(ref: LocalProjectRef, bilingual: Boolean, deduplicateSame: Boolean,
                                   destination: () -> OutputStream): Unit = operation {
        val output = temporary("txt")
        val coroutine = currentCoroutineContext()
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            NativeProjectDatabase.summary(db, ref)
            val originalPrefix = db.rawQuery(
                "SELECT data FROM asset_chunks WHERE asset_id = " +
                    "(SELECT id FROM assets WHERE file_type = 'TXT' ORDER BY ordinal, id LIMIT 1) ORDER BY part LIMIT 1",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0).copyOf(minOf(cursor.getBlob(0).size, 65_536)) else ByteArray(0) }
            fun write(encoding: TxtExportEncoding) {
                FileOutputStream(output).use { file ->
                    val buffered = file.buffered()
                    buffered.write(encoding.bom)
                    val newline = encoding.encode(encoding.newline)
                    db.rawQuery("SELECT ${itemColumns(db)} FROM items " +
                        "WHERE asset_id IN (SELECT id FROM assets WHERE file_type = 'TXT') ORDER BY row_index, id", null).use { cursor ->
                        var first = true
                        while (cursor.moveToNext()) {
                            coroutine.ensureActive()
                            val item = NativeProjectDatabase.item(cursor, db.version)
                            if (!first) buffered.write(newline)
                            first = false
                            val translation = if (item.status == TextItemStatus.PROCESSED) item.translation else item.source
                            val text = when {
                                !bilingual || (deduplicateSame && item.source == translation) -> translation
                                translation.isEmpty() -> item.source
                                else -> item.source + encoding.newline + translation
                            }
                            buffered.write(encoding.encode(text))
                        }
                    }
                    buffered.flush()
                    file.fd.sync()
                }
            }
            val detected = TxtExportEncoding.detect(originalPrefix)
            try {
                write(detected)
            } catch (_: CharacterCodingException) {
                // The translation introduced a character the original charset cannot encode;
                // publish a complete UTF-8 export instead of a partially encoded file.
                write(detected.fallbackUtf8())
            }
        } }
        deliver(output, destination)
    }

    override suspend fun exportOriginal(ref: LocalProjectRef, destination: () -> OutputStream): Unit = operation {
        val output = temporary("source")
        val coroutine = currentCoroutineContext()
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            NativeProjectDatabase.summary(db, ref)
            val expected = db.rawQuery("SELECT id, original_size, sha256, codec FROM assets WHERE file_type = 'TXT' ORDER BY ordinal, id LIMIT 1", null).use {
                if (!it.moveToFirst() || it.getString(3) != "raw-v1") fail(TextProjectException.Reason.CORRUPT_PROJECT)
                Triple(it.getLong(0), it.getLong(1), it.getString(2))
            }
            if (expected.second !in 0..limits.maxSourceBytes.toLong()) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            var part = 0
            FileOutputStream(output).use { file ->
                db.rawQuery("SELECT part, data FROM asset_chunks WHERE asset_id = ? ORDER BY part",
                    arrayOf(expected.first.toString())).use { cursor ->
                    while (cursor.moveToNext()) {
                        coroutine.ensureActive()
                        val bytes = cursor.getBlob(1)
                        if (cursor.getInt(0) != part++ || bytes.size !in 1..NativeProjectDatabase.CHUNK_BYTES) {
                            fail(TextProjectException.Reason.CORRUPT_PROJECT)
                        }
                        size += bytes.size
                        if (size > expected.second) fail(TextProjectException.Reason.CORRUPT_PROJECT)
                        digest.update(bytes)
                        file.write(bytes)
                    }
                }
                file.fd.sync()
            }
            val actual = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            if (size != expected.second || actual != expected.third) fail(TextProjectException.Reason.CORRUPT_PROJECT)
        } }
        deliver(output, destination)
    }

    override suspend fun assets(ref: LocalProjectRef): List<ProjectAssetSummary> = operation {
        project(ref) { db ->
            NativeProjectDatabase.ensureV3(db)
            NativeProjectDatabase.transaction(db) {
                NativeProjectDatabase.summary(db, ref)
                NativeProjectDatabase.assetSummaries(db)
            }
        }
    }

    override suspend fun projectSettings(ref: LocalProjectRef): ProjectSettings = operation {
        project(ref) { db ->
            NativeProjectDatabase.ensureV3(db)
            NativeProjectDatabase.transaction(db) {
                NativeProjectDatabase.summary(db, ref)
                NativeProjectDatabase.projectSettings(db)
            }
        }
    }

    override suspend fun editProjectSettings(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
        settings: ProjectSettings): AssetMutationReceipt = operation {
        require(expectedRevision >= 0)
        val coroutine = currentCoroutineContext()
        withWriteLease {
            project(ref) { db ->
                NativeProjectDatabase.ensureV3(db)
                ProjectAssetWriteStore.editSettings(db, commandId, ref, expectedRevision,
                    settings) { coroutine.ensureActive() }
            }
        }
    }

    override suspend fun importAsset(plan: AssetImportPlan): AssetMutationReceipt = operation {
        if (plan.bytes.size > limits.maxSourceBytes * 8) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        if (plan.units.size > limits.maxItems) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        val coroutine = currentCoroutineContext()
        withWriteLease {
            project(plan.ref) { db ->
                NativeProjectDatabase.ensureV3(db)
                ProjectAssetWriteStore.importAsset(db, plan, limits.maxItems) { coroutine.ensureActive() }
            }
        }
    }

    override suspend fun removeAsset(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
        assetId: Long): AssetMutationReceipt = operation {
        require(expectedRevision >= 0 && assetId > 0)
        val coroutine = currentCoroutineContext()
        withWriteLease {
            project(ref) { db ->
                NativeProjectDatabase.ensureV4(db)
                ProjectAssetWriteStore.remove(db, commandId, ref, expectedRevision, assetId) {
                    coroutine.ensureActive()
                }
            }
        }
    }

    override suspend fun reorderAssets(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
        assetIds: List<Long>): AssetMutationReceipt = operation {
        require(expectedRevision >= 0 && assetIds.size in 1..100 && assetIds.all { it > 0 })
        val coroutine = currentCoroutineContext()
        withWriteLease {
            project(ref) { db ->
                NativeProjectDatabase.ensureV3(db)
                ProjectAssetWriteStore.reorder(db, commandId, ref, expectedRevision, assetIds) {
                    coroutine.ensureActive()
                }
            }
        }
    }

    override suspend fun setAssetEnabled(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
        assetId: Long, enabled: Boolean): AssetMutationReceipt = operation {
        require(expectedRevision >= 0 && assetId > 0)
        val coroutine = currentCoroutineContext()
        withWriteLease {
            project(ref) { db ->
                NativeProjectDatabase.ensureV3(db)
                ProjectAssetWriteStore.setEnabled(db, commandId, ref, expectedRevision, assetId, enabled) {
                    coroutine.ensureActive()
                }
            }
        }
    }

    override suspend fun resetAsset(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
        assetId: Long): AssetMutationReceipt = operation {
        require(expectedRevision >= 0 && assetId > 0)
        val coroutine = currentCoroutineContext()
        withWriteLease {
            project(ref) { db ->
                NativeProjectDatabase.ensureV3(db)
                ProjectAssetWriteStore.reset(db, commandId, ref, expectedRevision, assetId) {
                    coroutine.ensureActive()
                }
            }
        }
    }

    override suspend fun readAsset(ref: LocalProjectRef, assetId: Long): ByteArray = operation {
        require(assetId > 0)
        project(ref) { db ->
            NativeProjectDatabase.ensureV3(db)
            NativeProjectDatabase.transaction(db) {
                NativeProjectDatabase.summary(db, ref)
                assetBytes(db, assetId)
            }
        }
    }

    override suspend fun exportAsset(ref: LocalProjectRef, assetId: Long, destination: () -> OutputStream): Unit = operation {
        require(assetId > 0)
        val bytes = project(ref) { db ->
            NativeProjectDatabase.ensureV3(db)
            NativeProjectDatabase.transaction(db) {
                NativeProjectDatabase.summary(db, ref)
                assetBytes(db, assetId)
            }
        }
        val output = temporary("asset")
        try {
            FileOutputStream(output).use { it.write(bytes); it.fd.sync() }
            deliver(output, destination)
        } catch (error: Throwable) {
            if (output.exists()) output.delete()
            throw error
        }
    }

    private fun assetBytes(db: SQLiteDatabase, assetId: Long): ByteArray {
        val expected = db.rawQuery("SELECT original_size,sha256 FROM assets WHERE id=?",
            arrayOf(assetId.toString())).use {
            if (!it.moveToFirst()) fail(TextProjectException.Reason.CORRUPT_PROJECT)
            it.getLong(0) to it.getString(1)
        }
        if (expected.first < 0 || expected.first > 64L * 1024 * 1024) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        val output = java.io.ByteArrayOutputStream(expected.first.toInt())
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        db.rawQuery("SELECT part,data FROM asset_chunks WHERE asset_id=? ORDER BY part",
            arrayOf(assetId.toString())).use { cursor ->
            var part = 0
            while (cursor.moveToNext()) {
                val bytes = cursor.getBlob(1)
                if (cursor.getInt(0) != part++ || bytes.size !in 1..NativeProjectDatabase.CHUNK_BYTES) {
                    fail(TextProjectException.Reason.CORRUPT_PROJECT)
                }
                size += bytes.size
                if (size > expected.first) fail(TextProjectException.Reason.CORRUPT_PROJECT)
                digest.update(bytes); output.write(bytes)
            }
        }
        val actual = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        if (size != expected.first || actual != expected.second) fail(TextProjectException.Reason.CORRUPT_PROJECT)
        return output.toByteArray()
    }

    /** Own this adapter at application scope; close only after its callers have stopped. */
    suspend fun close(): Unit = withContext(io) { mutex.withLock {
        if (!closed) { app.close(); closed = true }
    } }

    private suspend fun <T> operation(block: suspend () -> T): T = withContext(io) {
        mutex.withLock {
            check(!closed) { "Project store is closed" }
            currentCoroutineContext().ensureActive()
            try { block() }
            catch (error: android.database.sqlite.SQLiteDatabaseCorruptException) { fail(TextProjectException.Reason.CORRUPT_PROJECT) }
            catch (error: android.database.sqlite.SQLiteException) { fail(TextProjectException.Reason.STORAGE_IO) }
            catch (error: IOException) { fail(TextProjectException.Reason.STORAGE_IO) }
        }
    }

    private fun validateImport(file: File, checkActive: () -> Unit): ProjectId = try {
        NativeImportValidator.validate(file, limits, importLimits, checkActive)
    } catch (error: IllegalArgumentException) {
        fail(TextProjectException.Reason.CORRUPT_PROJECT)
    }

    private fun writeRegistration(directory: File, record: ProjectInstanceRecord) {
        require(record.creationCommand.length <= 2048)
        val text = buildJsonObject {
            put("version", 1); put("instance", record.instanceId); put("project", record.projectId)
            put("generation", record.generation); put("command", record.creationCommand); put("digest", record.creationDigest)
        }.toString()
        val bytes = TextProjectFingerprint.utf8(text)
        if (bytes.size > 16_384) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        FileOutputStream(File(directory, REGISTRATION)).use { it.write(bytes); it.fd.sync() }
    }

    /** Private manifests are never read from an imported project or exported as authorization. */
    private fun readRegistration(directory: File): ProjectInstanceRecord {
        val file = File(directory, REGISTRATION)
        if (!file.isFile || file.canonicalFile != file.absoluteFile || file.length() > 16_384) {
            fail(TextProjectException.Reason.CORRUPT_PROJECT)
        }
        val json = JsonObjectValue.parse(file.readText(), JsonInputLimits(16_384, 4)).json
        if (json.getValue("version").jsonPrimitive.int != 1) fail(TextProjectException.Reason.UNSUPPORTED_VERSION)
        val record = ProjectInstanceRecord(json.getValue("instance").jsonPrimitive.content,
            json.getValue("project").jsonPrimitive.content, json.getValue("generation").jsonPrimitive.long,
            json.getValue("command").jsonPrimitive.content, json.getValue("digest").jsonPrimitive.content)
        if (record.instanceId != directory.name || record.generation != 1L || record.creationCommand.length > 2048) {
            fail(TextProjectException.Reason.CORRUPT_PROJECT)
        }
        if (record.projectId.length > 2048 || !Regex("(?:import-v1:)?[a-f0-9]{64}").matches(record.creationDigest)) {
            fail(TextProjectException.Reason.CORRUPT_PROJECT)
        }
        CommandId(record.creationCommand)
        record.ref()
        return record
    }

    private fun recoverPublished(checkActive: () -> Unit): ProjectRecoveryReport {
        val root = directory(context.filesDir, "native_projects")
        val directories = root.listFiles() ?: fail(TextProjectException.Reason.STORAGE_IO)
        if (directories.size > 4096) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        var recovered = 0
        val problems = mutableListOf<RecoveryProblem>()
        for (candidate in directories.sortedBy { it.name }) {
            checkActive()
            val uuid = try { UUID.fromString(candidate.name).toString() } catch (_: IllegalArgumentException) { continue }
            if (uuid != candidate.name || app.instances().find(uuid) != null) continue
            try {
                if (!candidate.isDirectory || candidate.canonicalFile != candidate.absoluteFile) fail(TextProjectException.Reason.CORRUPT_PROJECT)
                val record = readRegistration(candidate)
                val file = File(candidate, PROJECT_FILE)
                if (file.canonicalFile != file.absoluteFile) fail(TextProjectException.Reason.CORRUPT_PROJECT)
                if (validateImport(file, checkActive).value != record.projectId) fail(TextProjectException.Reason.WRONG_IDENTITY)
                val prior = app.instances().byCreationCommand(record.creationCommand)
                if (prior != null && prior != record) fail(TextProjectException.Reason.COMMAND_CONFLICT)
                if (prior == null) { app.instances().insert(record); recovered++ }
            } catch (error: TextProjectException) {
                problems.add(RecoveryProblem(ProjectInstanceId(uuid), error.reason))
            } catch (_: IllegalArgumentException) {
                problems.add(RecoveryProblem(ProjectInstanceId(uuid), TextProjectException.Reason.CORRUPT_PROJECT))
            } catch (_: NoSuchElementException) {
                problems.add(RecoveryProblem(ProjectInstanceId(uuid), TextProjectException.Reason.CORRUPT_PROJECT))
            } catch (_: IOException) {
                problems.add(RecoveryProblem(ProjectInstanceId(uuid), TextProjectException.Reason.STORAGE_IO))
            } catch (_: android.database.sqlite.SQLiteDatabaseCorruptException) {
                problems.add(RecoveryProblem(ProjectInstanceId(uuid), TextProjectException.Reason.CORRUPT_PROJECT))
            } catch (_: android.database.sqlite.SQLiteException) {
                problems.add(RecoveryProblem(ProjectInstanceId(uuid), TextProjectException.Reason.STORAGE_IO))
            }
        }
        return ProjectRecoveryReport(recovered, problems)
    }

    private suspend fun <T> withWriteLease(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val lease = runtime.beginProjectWrite()
        try { return block() } finally { runtime.endProjectWrite(lease) }
    }

    private fun existingCreation(record: ProjectInstanceRecord, digest: String): TextProjectSummary {
        if (record.creationDigest != digest) fail(TextProjectException.Reason.COMMAND_CONFLICT)
        return project(record.ref()) { NativeProjectDatabase.summary(it, record.ref()) }
    }

    private fun file(ref: LocalProjectRef): File {
        val record = app.instances().find(ref.instanceId.value) ?: fail(TextProjectException.Reason.UNKNOWN_PROJECT)
        if (record.ref() != ref) fail(TextProjectException.Reason.WRONG_IDENTITY)
        // Storage paths are generated UUIDs, never project titles, external names or model values.
        val normalizedId = try {
            UUID.fromString(record.instanceId).toString()
        } catch (_: IllegalArgumentException) {
            fail(TextProjectException.Reason.CORRUPT_PROJECT)
        }
        if (normalizedId != record.instanceId) fail(TextProjectException.Reason.CORRUPT_PROJECT)
        val root = directory(context.filesDir, "native_projects")
        val parent = File(root, record.instanceId)
        val file = File(parent, PROJECT_FILE)
        if (parent.canonicalFile != parent.absoluteFile || file.canonicalFile != file.absoluteFile) {
            fail(TextProjectException.Reason.CORRUPT_PROJECT)
        }
        return file
    }

    private fun <T> project(ref: LocalProjectRef, block: (SQLiteDatabase) -> T): T =
        NativeProjectDatabase.open(file(ref), writable = true).use(block)

    private fun temporary(extension: String): File = File.createTempFile("snapshot-", ".$extension",
        directory(context.cacheDir, "native_project_snapshots"))

    private fun directory(base: File, name: String): File {
        val parent = base.canonicalFile
        val child = File(parent, name)
        if (child.canonicalFile != child.absoluteFile) fail(TextProjectException.Reason.CORRUPT_PROJECT)
        if (!child.isDirectory && !child.mkdirs()) fail(TextProjectException.Reason.STORAGE_IO)
        if (child.canonicalFile != child.absoluteFile) fail(TextProjectException.Reason.CORRUPT_PROJECT)
        return child
    }

    private suspend fun deliver(snapshot: File, destination: () -> OutputStream) {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        destination().use { output ->
            snapshot.inputStream().use { input -> copy(input, output) { coroutine.ensureActive() } }
            output.flush()
        }
        coroutine.ensureActive()
        // Failed/cancelled delivery retains the verified local artifact for later recovery.
        snapshot.delete()
    }

    private fun copy(input: InputStream, output: OutputStream, checkActive: () -> Unit) {
        val buffer = ByteArray(NativeProjectDatabase.CHUNK_BYTES)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
    }

    private fun ProjectInstanceRecord.ref(): LocalProjectRef = LocalProjectRef(
        ProjectId(projectId), ProjectInstanceId(instanceId), RecoveryGeneration(generation),
    )

    private fun fail(reason: TextProjectException.Reason): Nothing = throw TextProjectException(reason)

    private companion object {
        // SQLite's default trim only handles ASCII space. Derive the character set once
        // from Kotlin's predicate so tabs and Unicode whitespace match source.isNotBlank().
        val BLANK_SOURCE_CHARACTERS = (Char.MIN_VALUE..Char.MAX_VALUE).filter { it.isWhitespace() }.joinToString("")
        const val PROJECT_FILE = "project.lnt"
        const val REGISTRATION = "registration.json"
    }
}
