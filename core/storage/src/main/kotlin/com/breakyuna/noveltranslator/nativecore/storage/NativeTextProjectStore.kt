package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Concrete IO adapter for the first TXT slice, not yet wired to the app UI. Inject the application's single
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
) : TextProjectPort {
    private val io = Dispatchers.IO
    private val context = context.applicationContext
    private val app = NativeAppDatabase.open(this.context)
    private val mutex = Mutex()
    private var closed = false

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
                val target = File(root, id)
                if (target.exists() || !staging.renameTo(target)) {
                    fail(TextProjectException.Reason.STORAGE_IO)
                }
                published = true
                // If this fails, preserve the published directory for reconciliation. Never expose
                // a half-written project via the index or delete an uncertain committed result.
                val record = ProjectInstanceRecord(id, projectId.value, 1, commandId.value, digest)
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

    override suspend fun exportTxt(ref: LocalProjectRef, destination: () -> OutputStream): Unit = operation {
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
                        buffered.write(TextProjectFingerprint.utf8(
                            if (item.status == TextItemStatus.PROCESSED) item.translation else item.source,
                        ))
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
            block()
        }
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

    private companion object { const val PROJECT_FILE = "project.lnt" }
}
