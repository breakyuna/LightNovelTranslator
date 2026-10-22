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
 * opens/closes its project connection. Room owns only local instance registrations.
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
) : TextProjectPort, TranslationCommitPort {
    private val io = Dispatchers.IO
    private val context = context.applicationContext
    private val app = NativeAppDatabase.open(this.context)
    private val mutex = Mutex()
    private var closed = false
    private val protectedCopies = mutableSetOf<String>()

    override suspend fun registeredProjects(limit: Int): List<RegisteredProject> = operation {
        require(limit in 1..100)
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

    override suspend fun item(ref: LocalProjectRef, itemId: ItemId): TextItemDetails? = operation {
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            db.rawQuery("SELECT ${NativeProjectDatabase.ITEM_COLUMNS} FROM items WHERE id = ?",
                arrayOf(itemId.value.toString())).use {
                if (!it.moveToFirst()) null else TextItemDetails(summary.revision, NativeProjectDatabase.item(it))
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

    override suspend fun items(ref: LocalProjectRef, revision: Long, afterId: Long, limit: Int): TextItemPage = operation {
        require(afterId >= 0 && revision >= 0 && limit in 1..limits.maxPageSize)
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            if (summary.revision != revision) fail(TextProjectException.Reason.STALE_PAGE)
            val result = db.rawQuery("SELECT ${NativeProjectDatabase.ITEM_COLUMNS} FROM items WHERE id > ? ORDER BY id LIMIT ?",
                arrayOf(afterId.toString(), limit.toString())).use { cursor ->
                buildList { while (cursor.moveToNext()) add(NativeProjectDatabase.item(cursor)) }
            }
            TextItemPage(summary.revision, result)
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

    override suspend fun saveProject(ref: LocalProjectRef, destination: () -> OutputStream): Unit = operation {
        val snapshot = temporary("lnt")
        val coroutine = currentCoroutineContext()
        withWriteLease {
            val source = file(ref) // Resolve Room identity before entering the project transaction.
            NativeProjectDatabase.open(source, writable = true).use { db ->
                NativeProjectDatabase.summary(db, ref)
                NativeProjectDatabase.snapshot(db, snapshot) { coroutine.ensureActive() }
            }
        }
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
            FileOutputStream(output).use { file ->
                val buffered = file.buffered()
                db.rawQuery("SELECT ${NativeProjectDatabase.ITEM_COLUMNS} FROM items ORDER BY row_index, id", null).use { cursor ->
                    var first = true
                    while (cursor.moveToNext()) {
                        coroutine.ensureActive()
                        val item = NativeProjectDatabase.item(cursor)
                        if (!first) buffered.write('\n'.code)
                        first = false
                        val translation = if (item.status == TextItemStatus.PROCESSED) item.translation else item.source
                        val text = when {
                            !bilingual || (deduplicateSame && item.source == translation) -> translation
                            translation.isEmpty() -> item.source
                            else -> item.source + "\n" + translation
                        }
                        buffered.write(TextProjectFingerprint.utf8(text))
                    }
                }
                buffered.flush()
                file.fd.sync()
            }
        } }
        deliver(output, destination)
    }

    override suspend fun exportOriginal(ref: LocalProjectRef, destination: () -> OutputStream): Unit = operation {
        val output = temporary("source")
        val coroutine = currentCoroutineContext()
        project(ref) { db -> NativeProjectDatabase.transaction(db) {
            NativeProjectDatabase.summary(db, ref)
            val expected = db.rawQuery("SELECT original_size, sha256, codec FROM assets WHERE id = 1", null).use {
                if (!it.moveToFirst() || it.getString(2) != "raw-v1") fail(TextProjectException.Reason.CORRUPT_PROJECT)
                it.getLong(0) to it.getString(1)
            }
            if (expected.first !in 0..limits.maxSourceBytes.toLong()) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            var part = 0
            FileOutputStream(output).use { file ->
                db.rawQuery("SELECT part, data FROM asset_chunks WHERE asset_id = 1 ORDER BY part", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        coroutine.ensureActive()
                        val bytes = cursor.getBlob(1)
                        if (cursor.getInt(0) != part++ || bytes.size !in 1..NativeProjectDatabase.CHUNK_BYTES) {
                            fail(TextProjectException.Reason.CORRUPT_PROJECT)
                        }
                        size += bytes.size
                        if (size > expected.first) fail(TextProjectException.Reason.CORRUPT_PROJECT)
                        digest.update(bytes)
                        file.write(bytes)
                    }
                }
                file.fd.sync()
            }
            val actual = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            if (size != expected.first || actual != expected.second) fail(TextProjectException.Reason.CORRUPT_PROJECT)
        } }
        deliver(output, destination)
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
        const val PROJECT_FILE = "project.lnt"
        const val REGISTRATION = "registration.json"
    }
}
