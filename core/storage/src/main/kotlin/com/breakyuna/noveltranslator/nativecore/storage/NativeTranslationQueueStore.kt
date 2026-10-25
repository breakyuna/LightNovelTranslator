package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One private atomic JSON file per local project: frozen task specs, queue states, bounded failure
 * batches, command records, sequence and timestamps. It never stores a source text, a translation,
 * a prompt body, a credential or an HTTP header.
 *
 * Current schema is v2 (audited execution revision per task). Version 1 documents are still read
 * non-destructively and upgraded on the next compare-and-set write. Missing means a new queue. A
 * corrupt, foreign, oversized, truncated or unknown-version file throws [TranslationQueueCorrupt]
 * and is never treated as empty, so admitted tasks cannot vanish silently. Writes compare revision
 * and payload fingerprint, and only return after AtomicFile has published the new document.
 */
class NativeTranslationQueueStore(context: Context) : TranslationQueueStorePort {
    private val directory = File(context.applicationContext.filesDir, DIRECTORY)

    override suspend fun load(ref: LocalProjectRef): StoredTranslationQueue? =
        withContext(Dispatchers.IO) { mutex.withLock { read(fileFor(ref), ref) } }

    override suspend fun list(): List<StoredTranslationQueue> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!directory.exists()) return@withLock emptyList()
                val files = directory.listFiles()
                    ?: throw TranslationQueueCorrupt("Translation queue directory unavailable")
                if (files.size > MAX_FILES) {
                    throw TranslationQueueCorrupt("Translation queue directory exceeds its file budget")
                }
                val names = sortedSetOf<String>()
                for (source in files) {
                    if (!source.isFile) continue
                    val name = source.name
                    when {
                        name.endsWith(SUFFIX) -> names.add(name)
                        name.endsWith(SUFFIX + ".bak") -> names.add(name.removeSuffix(".bak"))
                        name.endsWith(SUFFIX + ".new") -> names.add(name.removeSuffix(".new"))
                        else -> Unit
                    }
                }
                names.map { name ->
                    val stored = read(File(directory, name), null)
                        ?: throw TranslationQueueCorrupt("Translation queue file vanished")
                    if (fileFor(stored.snapshot.ref).name != name) {
                        throw TranslationQueueCorrupt("Translation queue file name does not match its scope")
                    }
                    stored
                }
            }
        }

    override suspend fun compareAndSet(
        ref: LocalProjectRef,
        expected: StoredTranslationQueue?,
        next: TranslationQueueSnapshot,
    ): StoredTranslationQueue = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(next.ref == ref) { "Translation queue snapshot belongs to another project" }
            val current = read(fileFor(ref), ref)
            if (expected == null) {
                if (current != null) throw TranslationQueueConflict("Translation queue already exists")
            } else if (current == null || current.snapshot.revision != expected.snapshot.revision ||
                current.fingerprint != expected.fingerprint
            ) {
                throw TranslationQueueConflict("Translation queue changed")
            }
            val expectedRevision = expected?.snapshot?.revision ?: 0L
            require(next.revision == Math.addExact(expectedRevision, 1L)) {
                "Translation queue revision must advance by exactly one"
            }
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw TranslationQueueCorrupt("Translation queue directory is unavailable")
            }
            val target = fileFor(ref)
            if (target.exists()) {
                // A backup/new pair beside a published base is a stale artifact; AtomicFile would
                // otherwise restore the older backup before the next write.
                File(target.path + ".bak").delete()
                File(target.path + ".new").delete()
            }
            val body = payload(next, CURRENT_VERSION)
            val fingerprint = fingerprint(body)
            val document = buildJsonObject {
                body.forEach { (key, value) -> put(key, value) }
                put("fingerprint", fingerprint.sha256)
            }
            val bytes = TextProjectFingerprint.utf8(document.toString())
            if (bytes.size > MAX_BYTES) {
                throw TranslationQueueCorrupt("Translation queue snapshot exceeds its bound")
            }
            val atomic = AtomicFile(target)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                throw failure
            }
            StoredTranslationQueue(next, fingerprint)
        }
    }

    /** Exposed for corruption/crash tests; production callers only use the port methods. */
    internal fun fileFor(ref: LocalProjectRef): File {
        val scope = JsonArray(
            listOf(
                JsonPrimitive(ref.projectId.value),
                JsonPrimitive(ref.instanceId.value),
                JsonPrimitive(ref.generation.value),
            ),
        )
        val name = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(scope.toString()))
        return File(directory, name + SUFFIX)
    }

    private fun read(source: File, expected: LocalProjectRef?): StoredTranslationQueue? {
        val backup = File(source.path + ".bak")
        val unfinished = File(source.path + ".new")
        if (!source.exists() && !backup.exists()) {
            if (unfinished.exists()) {
                throw TranslationQueueCorrupt("Translation queue write did not complete")
            }
            return null
        }
        if (!source.exists()) {
            if (unfinished.exists()) {
                throw TranslationQueueCorrupt("Translation queue has both a backup and an unfinished write")
            }
            if (!backup.renameTo(source)) {
                throw TranslationQueueCorrupt("Translation queue backup is unavailable")
            }
        }
        val bytes = try {
            AtomicFile(source).openRead().use { it.readBounded() }
        } catch (failure: TranslationQueueCorrupt) {
            throw failure
        } catch (failure: FileNotFoundException) {
            throw TranslationQueueCorrupt("Translation queue file is unavailable")
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: CharacterCodingException) {
            throw TranslationQueueCorrupt("Translation queue file is not UTF-8")
        }
        return decode(text, expected)
    }

    private fun InputStream.readBounded(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > MAX_BYTES) {
                throw TranslationQueueCorrupt("Translation queue file exceeds its bound")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decode(text: String, expected: LocalProjectRef?): StoredTranslationQueue =
        try {
            decodeStrict(text, expected)
        } catch (failure: TranslationQueueCorrupt) {
            throw failure
        } catch (failure: Exception) {
            throw TranslationQueueCorrupt("Invalid translation queue snapshot")
        }

    private fun decodeStrict(text: String, expected: LocalProjectRef?): StoredTranslationQueue {
        val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_BYTES, MAX_DEPTH)).json
        require(root.keys == ROOT_KEYS)
        val version = number(root, "version")
        require(version in LEGACY_VERSION..CURRENT_VERSION)
        val scope = array(root, "scope")
        require(scope.size == 3)
        val ref = LocalProjectRef(
            projectId = ProjectId(stringValue(scope[0])),
            instanceId = ProjectInstanceId(stringValue(scope[1])),
            generation = RecoveryGeneration(numberValue(scope[2])),
        )
        if (expected != null) require(ref == expected)
        val revision = number(root, "revision")
        val updated = number(root, "updated")
        val limitsNode = obj(root, "limits")
        require(limitsNode.keys == LIMIT_KEYS)
        val limits = TranslationTaskQueueLimits(
            maxTasks = bounded(limitsNode, "tasks", TranslationTaskQueueLimits.HARD_MAX_TASKS),
            maxBatchesPerTask = bounded(limitsNode, "batches", TranslationTaskQueueLimits.HARD_MAX_BATCHES),
            maxCommandsPerTask = bounded(limitsNode, "commands", TranslationTaskQueueLimits.HARD_MAX_COMMANDS),
        )
        val decoded = array(root, "tasks").map { decodeTask(it, version) }
        require(decoded.size <= limits.maxTasks)
        require(number(root, "next_sequence") == decoded.size.toLong()) {
            "Translation queue lost its admitted task tail"
        }
        val snapshot = TranslationQueueSnapshot.restore(
            ref = ref,
            revision = revision,
            updatedAtMillis = updated,
            limits = limits,
            tasks = decoded.map { it.first },
            metas = decoded.map { it.second },
        )
        val stored = ObjectFingerprint(string(root, "fingerprint"))
        require(stored == fingerprint(payload(snapshot, version))) { "Translation queue fingerprint mismatch" }
        return StoredTranslationQueue(snapshot, stored)
    }

    private fun decodeTask(value: JsonElement, version: Long): Pair<TranslationQueuedTaskFacts, TranslationQueueTaskMeta> {
        val row = value as? JsonObject ?: invalid()
        require(row.keys == if (version == LEGACY_VERSION) TASK_KEYS_V1 else TASK_KEYS)
        val sequence = number(row, "sequence")
        val spec = decodeSpec(obj(row, "spec"))
        val state = TranslationTaskQueueState.valueOf(string(row, "state"))
        val enqueued = number(row, "enqueued")
        val updated = number(row, "updated")
        val batches = array(row, "batches").map(::decodeBatch)
        val commands = array(row, "commands").map(::decodeCommand)
        val executionRevision = if (version == LEGACY_VERSION) null else nullableNumber(row, "execution_revision")
        val facts = TranslationQueuedTaskFacts(spec, sequence, state, batches, commands, executionRevision)
        return facts to TranslationQueueTaskMeta(enqueued, updated)
    }

    private fun decodeBatch(value: JsonElement): TranslationTaskFailureBatch {
        val row = value as? JsonObject ?: invalid()
        require(row.keys == BATCH_KEYS)
        val id = TranslationTaskFailureBatchId(string(row, "id"))
        val members = array(row, "members").map(::decodeMember)
        return TranslationTaskFailureBatch(id, members)
    }

    private fun decodeMember(value: JsonElement): TranslationFailureMember {
        val row = value as? JsonObject ?: invalid()
        require(row.keys == MEMBER_KEYS)
        return TranslationFailureMember(
            itemId = ItemId(number(row, "item")),
            fingerprint = ObjectFingerprint(string(row, "fingerprint")),
            failure = ModelFailureKind.valueOf(string(row, "failure")),
            receipt = TranslationFailureReceipt.valueOf(string(row, "receipt")),
            usage = UsageSource.valueOf(string(row, "usage")),
            committed = boolean(row, "committed"),
            terminal = boolean(row, "terminal"),
        )
    }

    private fun decodeCommand(value: JsonElement): TranslationTaskCommandFacts {
        val row = value as? JsonObject ?: invalid()
        require(row.keys == COMMAND_KEYS)
        return TranslationTaskCommandFacts(
            commandId = CommandId(string(row, "command")),
            action = TranslationTaskCommandAction.valueOf(string(row, "action")),
            batchId = nullableString(row, "batch")?.let(::TranslationTaskFailureBatchId),
            memberIds = array(row, "members").map { ItemId(numberValue(it)) },
            evidence = array(row, "evidence").map(::decodeMember),
        )
    }

    private fun decodeSpec(row: JsonObject): TranslationTaskSpec {
        require(row.keys == SPEC_KEYS)
        require(number(row, "version") == 1L)
        val ref = LocalProjectRef(
            projectId = ProjectId(string(row, "project")),
            instanceId = ProjectInstanceId(string(row, "instance")),
            generation = RecoveryGeneration(number(row, "generation")),
        )
        return TranslationTaskSpec(
            ref = ref,
            run = RunIdentity(
                projectId = ref.projectId,
                chatId = null,
                runId = RunId(string(row, "run")),
                commandId = CommandId(string(row, "command")),
            ),
            configuration = ModelConfigurationRef(
                id = string(row, "configuration"),
                revision = number(row, "configuration_revision"),
            ),
            revision = number(row, "revision"),
            afterId = number(row, "after"),
            pageSize = int(row, "page_size"),
            wholeProject = boolean(row, "whole"),
            options = RemoteTranslationOptions(
                targetLanguage = string(row, "target_language"),
                sourceLanguage = string(row, "source_language"),
                instructions = string(row, "instructions"),
                batchSize = int(row, "batch_size"),
                batchCharacters = int(row, "batch_characters"),
                startMode = TranslationStartMode.valueOf(string(row, "mode")),
                precedingLines = int(row, "preceding"),
                additionalRequestsPerBatch = int(row, "additional"),
                deduplicateSameFile = boolean(row, "deduplicate"),
            ),
        )
    }

    private fun payload(snapshot: TranslationQueueSnapshot, version: Long): JsonObject = buildJsonObject {
        put("version", version)
        put(
            "scope",
            JsonArray(
                listOf(
                    JsonPrimitive(snapshot.ref.projectId.value),
                    JsonPrimitive(snapshot.ref.instanceId.value),
                    JsonPrimitive(snapshot.ref.generation.value),
                ),
            ),
        )
        put("revision", snapshot.revision)
        put("updated", snapshot.updatedAtMillis)
        put("next_sequence", snapshot.queue.tasks.size)
        put(
            "limits",
            buildJsonObject {
                put("tasks", snapshot.queue.limits.maxTasks)
                put("batches", snapshot.queue.limits.maxBatchesPerTask)
                put("commands", snapshot.queue.limits.maxCommandsPerTask)
            },
        )
        put(
            "tasks",
            JsonArray(
                snapshot.entries.map { entry ->
                    buildJsonObject {
                        put("sequence", entry.task.sequence)
                        put("spec", encodeSpec(entry.spec))
                        put("state", entry.task.state.name)
                        put("enqueued", entry.meta.enqueuedAtMillis)
                        put("updated", entry.meta.updatedAtMillis)
                        if (version >= CURRENT_VERSION) {
                            put("execution_revision", entry.task.executionRevision?.let { JsonPrimitive(it) } ?: JsonNull)
                        }
                        put("batches", JsonArray(entry.task.batches.map(::encodeBatch)))
                        put("commands", JsonArray(entry.task.commands.map(::encodeCommand)))
                    }
                },
            ),
        )
    }

    /**
     * Version-1 frozen spec contract shared with the task store's stored specification. The field
     * names and bounds are intentionally identical; a change requires a new codec version.
     */
    private fun encodeSpec(spec: TranslationTaskSpec): JsonObject = buildJsonObject {
        put("version", 1)
        put("project", spec.ref.projectId.value)
        put("instance", spec.ref.instanceId.value)
        put("generation", spec.ref.generation.value)
        put("run", spec.run.runId.value)
        put("command", spec.run.commandId.value)
        put("configuration", spec.configuration.id)
        put("configuration_revision", spec.configuration.revision)
        put("revision", spec.revision)
        put("after", spec.afterId)
        put("page_size", spec.pageSize)
        put("whole", spec.wholeProject)
        put("target_language", spec.options.targetLanguage)
        put("source_language", spec.options.sourceLanguage)
        put("instructions", spec.options.instructions)
        put("batch_size", spec.options.batchSize)
        put("batch_characters", spec.options.batchCharacters)
        put("mode", spec.options.startMode.name)
        put("preceding", spec.options.precedingLines)
        put("additional", spec.options.additionalRequestsPerBatch)
        put("deduplicate", spec.options.deduplicateSameFile)
    }

    private fun encodeBatch(batch: TranslationTaskFailureBatch): JsonObject = buildJsonObject {
        put("id", batch.id.value)
        put("members", JsonArray(batch.members.map(::encodeMember)))
    }

    private fun encodeMember(member: TranslationFailureMember): JsonObject = buildJsonObject {
        put("item", member.itemId.value)
        put("fingerprint", member.fingerprint.sha256)
        put("failure", member.failure.name)
        put("receipt", member.receipt.name)
        put("usage", member.usage.name)
        put("committed", member.committed)
        put("terminal", member.terminal)
    }

    private fun encodeCommand(command: TranslationTaskCommandRecord): JsonObject = buildJsonObject {
        put("command", command.commandId.value)
        put("action", command.action.name)
        put("batch", command.batchId?.let { JsonPrimitive(it.value) } ?: JsonNull)
        put("members", JsonArray(command.memberIds.map { JsonPrimitive(it.value) }))
        put("evidence", JsonArray(command.evidence.map(::encodeMember)))
    }

    private fun fingerprint(payload: JsonObject): ObjectFingerprint =
        ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(payload.toString())))

    private fun obj(row: JsonObject, key: String): JsonObject = row[key] as? JsonObject ?: invalid()

    private fun array(row: JsonObject, key: String): JsonArray = row[key] as? JsonArray ?: invalid()

    private fun string(row: JsonObject, key: String): String = stringValue(row[key] ?: invalid())

    private fun stringValue(value: JsonElement): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()

    private fun nullableString(row: JsonObject, key: String): String? {
        val value = row[key] ?: invalid()
        return if (value is JsonNull) null else stringValue(value)
    }

    private fun nullableNumber(row: JsonObject, key: String): Long? {
        val value = row[key] ?: invalid()
        return if (value is JsonNull) null else numberValue(value)
    }

    private fun number(row: JsonObject, key: String): Long = numberValue(row[key] ?: invalid())

    private fun numberValue(value: JsonElement): Long {
        val primitive = value as? JsonPrimitive ?: invalid()
        if (primitive.isString) invalid()
        val text = primitive.content
        require(text == "0" || (text.isNotEmpty() && text[0] in '1'..'9' && text.all { it in '0'..'9' })) {
            "Invalid translation queue number"
        }
        return text.toLongOrNull() ?: invalid()
    }

    private fun boolean(row: JsonObject, key: String): Boolean {
        val primitive = row[key] as? JsonPrimitive ?: invalid()
        if (primitive.isString) invalid()
        return when (primitive.content) {
            "true" -> true
            "false" -> false
            else -> invalid()
        }
    }

    private fun int(row: JsonObject, key: String): Int =
        number(row, key).also { require(it <= Int.MAX_VALUE) }.toInt()

    private fun bounded(row: JsonObject, key: String, max: Int): Int =
        number(row, key).also { require(it <= max) }.toInt()

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid translation queue snapshot")

    internal companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_DEPTH = 16
        const val MAX_FILES = 4_096

        /** v2 adds the audited execution revision; v1 documents remain readable. */
        const val CURRENT_VERSION = 2L
        const val LEGACY_VERSION = 1L

        private const val DIRECTORY = "native_translation_queues"
        private const val SUFFIX = ".json"
        private val mutex = Mutex()

        private val ROOT_KEYS = setOf(
            "version", "scope", "revision", "updated", "next_sequence", "limits", "tasks", "fingerprint",
        )
        private val LIMIT_KEYS = setOf("tasks", "batches", "commands")
        private val TASK_KEYS = setOf(
            "sequence", "spec", "state", "enqueued", "updated", "execution_revision", "batches", "commands",
        )
        private val TASK_KEYS_V1 = setOf(
            "sequence", "spec", "state", "enqueued", "updated", "batches", "commands",
        )
        private val BATCH_KEYS = setOf("id", "members")
        private val MEMBER_KEYS = setOf(
            "item", "fingerprint", "failure", "receipt", "usage", "committed", "terminal",
        )
        private val COMMAND_KEYS = setOf("command", "action", "batch", "members", "evidence")
        private val SPEC_KEYS = setOf(
            "version", "project", "instance", "generation", "run", "command",
            "configuration", "configuration_revision", "revision", "after", "page_size",
            "whole", "target_language", "source_language", "instructions", "batch_size",
            "batch_characters", "mode", "preceding", "additional", "deduplicate",
        )
    }
}
