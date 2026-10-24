package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Private bounded metadata. Missing means new; corrupt/foreign state never means a fresh budget. */
class NativeTranslationRetryCheckpointStore(context: Context, private val runtime: RuntimePort) : TranslationRetryCheckpointPort {
    private val directory = File(context.applicationContext.filesDir, "native_translation_retries")
    override suspend fun read(lease: RunLease, ref: LocalProjectRef, configuration: ModelConfigurationRef): TranslationRetryCheckpoint? =
        withContext(Dispatchers.IO) { mutex.withLock {
            authorized(lease, ref)
            readFile(file(lease.identity, ref), lease.identity, ref, configuration)
        } }
    /** Caller holds the runtime's exclusive project-write reservation during task preflight. */
    internal suspend fun readReserved(ref: LocalProjectRef, run: RunIdentity,
        configuration: ModelConfigurationRef): TranslationRetryCheckpoint? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = runtime.snapshot()
            require(snapshot.activeRun == null && snapshot.projectWriteRunning && run.projectId == ref.projectId && run.chatId == null)
            readFile(file(run, ref), run, ref, configuration)
        }
    }
    /** Read-only task recovery runs either under its real run or the exclusive preflight reservation. */
    internal suspend fun readCoordinated(ref: LocalProjectRef, run: RunIdentity,
        configuration: ModelConfigurationRef): TranslationRetryCheckpoint? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = runtime.snapshot()
            require(run.projectId == ref.projectId && run.chatId == null &&
                (snapshot.activeRun?.let { it.identity == run && it.owner == RuntimeOwner.TRANSLATION } == true ||
                    snapshot.activeRun == null && snapshot.projectWriteRunning))
            readFile(file(run, ref), run, ref, configuration)
        }
    }
    override suspend fun write(lease: RunLease, ref: LocalProjectRef, configuration: ModelConfigurationRef,
        checkpoint: TranslationRetryCheckpoint): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        authorized(lease, ref)
        writeLocked(lease.identity, ref, configuration, checkpoint) { authorized(lease, ref) }
    } }
    internal suspend fun writeReserved(ref: LocalProjectRef, run: RunIdentity,
        configuration: ModelConfigurationRef, checkpoint: TranslationRetryCheckpoint): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            reserved(ref, run)
            writeLocked(run, ref, configuration, checkpoint) { reserved(ref, run) }
        }
    }
    private fun reserved(ref: LocalProjectRef, run: RunIdentity) {
        val snapshot = runtime.snapshot()
        require(snapshot.activeRun == null && snapshot.projectWriteRunning && run.projectId == ref.projectId && run.chatId == null)
    }
    private suspend fun writeLocked(run: RunIdentity, ref: LocalProjectRef, configuration: ModelConfigurationRef,
        checkpoint: TranslationRetryCheckpoint, authorize: () -> Unit) {
        require(directory.isDirectory || directory.mkdirs())
        val source = file(run, ref)
        val previous = readFile(source, run, ref, configuration)
        if (previous != null) {
            val sameUnit = previous.targets == checkpoint.targets
            if (previous.terminalFailure != null) {
                fun block(value: TranslationRetryPending?) = value?.let { Triple(it.ids, it.attempts, it.splitDepth) }
                require(sameUnit && previous.initialAdditional == checkpoint.initialAdditional &&
                    previous.remainingAdditional == checkpoint.remainingAdditional && previous.attempt == checkpoint.attempt &&
                    previous.terminalFailure == checkpoint.terminalFailure && previous.notBeforeMillis == checkpoint.notBeforeMillis &&
                    block(previous.active) == block(checkpoint.active) && previous.pending.map(::block) == checkpoint.pending.map(::block))
            }
            if (sameUnit) {
                require(previous.initialAdditional == checkpoint.initialAdditional &&
                    checkpoint.remainingAdditional <= previous.remainingAdditional)
                if (previous.pending.isEmpty() && previous.active == null)
                    require(checkpoint.pending.isEmpty() && checkpoint.active == null)
            } else require(previous.pending.isEmpty() && previous.active == null && previous.terminalFailure == null)
        } else require((directory.listFiles() ?: error("Checkpoint directory unavailable")).size < MAX_FILES)
        val bytes = TextProjectFingerprint.utf8(encode(run, ref, configuration, checkpoint).toString())
        require(bytes.size <= MAX_BYTES)
        currentCoroutineContext().ensureActive()
        authorize()
        val atomic = AtomicFile(source); val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
    private fun authorized(lease: RunLease, ref: LocalProjectRef) {
        require(lease.identity.projectId == ref.projectId && lease.identity.chatId == null)
        val active = runtime.snapshot().activeRun
        require(active != null && active.identity == lease.identity && active.owner == RuntimeOwner.TRANSLATION)
        val operation = runtime.beginOperation(lease, OperationKind.HOST_REQUEST)
        runtime.endOperation(operation)
    }
    private fun scope(run: RunIdentity, ref: LocalProjectRef) = JsonArray(listOf(ref.projectId.value,
        ref.instanceId.value, ref.generation.value.toString(), run.runId.value,
        run.commandId.value).map(::JsonPrimitive))
    private fun file(run: RunIdentity, ref: LocalProjectRef): File {
        return File(directory, TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(scope(run, ref).toString())) + ".json")
    }
    private fun readFile(source: File, run: RunIdentity, ref: LocalProjectRef, configuration: ModelConfigurationRef): TranslationRetryCheckpoint? {
        if (!source.exists() && !File(source.path + ".bak").exists()) return null
        val bytes = AtomicFile(source).openRead().use { it.readBytesBounded() }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_BYTES, 12)).json
        require(root.keys == setOf("version", "scope", "configuration", "revision", "targets", "initial", "remaining", "pending", "active", "attempt", "failure", "due"))
        require(number(root, "version") == 1L)
        require(root["scope"] == scope(run, ref) && string(root, "configuration") == configuration.id &&
            number(root, "revision") == configuration.revision)
        val targets = array(root, "targets").map { value ->
            val row = value.jsonObject; require(row.keys == setOf("id", "fingerprint"))
            TranslationRetryTarget(ItemId(number(row, "id")), ObjectFingerprint(string(row, "fingerprint")))
        }
        fun block(value: JsonElement): TranslationRetryPending {
            val row = value.jsonObject; require(row.keys == setOf("ids", "attempts", "depth"))
            val ids = array(row, "ids").map { number(buildJsonObject { put("id", it) }, "id") }.map(::ItemId)
            return TranslationRetryPending(ids, bounded(row, "attempts", 1_000_000), bounded(row, "depth", 16))
        }
        return TranslationRetryCheckpoint(targets, bounded(root, "initial", 5), bounded(root, "remaining", 5),
            array(root, "pending").map(::block), root["active"]?.takeUnless { it == JsonNull }?.let(::block),
            root["attempt"]?.takeUnless { it == JsonNull }?.let { RequestAttemptId(string(root, "attempt")) },
            root["failure"]?.takeUnless { it == JsonNull }?.let { ModelFailureKind.valueOf(string(root, "failure")) }, number(root, "due"))
    }
    private fun encode(run: RunIdentity, ref: LocalProjectRef, configuration: ModelConfigurationRef, state: TranslationRetryCheckpoint): JsonObject = buildJsonObject {
        put("version", 1)
        put("scope", scope(run, ref))
        put("configuration", configuration.id); put("revision", configuration.revision)
        put("targets", JsonArray(state.targets.map { buildJsonObject { put("id", it.id.value); put("fingerprint", it.fingerprint.sha256) } }))
        put("initial", state.initialAdditional); put("remaining", state.remainingAdditional)
        fun block(value: TranslationRetryPending): JsonObject = buildJsonObject {
            put("ids", JsonArray(value.ids.map { JsonPrimitive(it.value) })); put("attempts", value.attempts); put("depth", value.splitDepth)
        }
        put("pending", JsonArray(state.pending.map(::block))); put("active", state.active?.let(::block) ?: JsonNull)
        put("attempt", state.attempt?.let { JsonPrimitive(it.value) } ?: JsonNull)
        put("failure", state.terminalFailure?.let { JsonPrimitive(it.name) } ?: JsonNull); put("due", state.notBeforeMillis)
    }
    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer); if (count < 0) break
            require(output.size().toLong() + count <= MAX_BYTES); output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun array(row: JsonObject, key: String) = row[key] as? JsonArray ?: error("Invalid checkpoint")
    private fun string(row: JsonObject, key: String) = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid checkpoint")
    private fun number(row: JsonObject, key: String): Long {
        val value = row[key] as? JsonPrimitive ?: error("Invalid checkpoint")
        require(!value.isString && value.content.matches(Regex("0|[1-9][0-9]*")))
        return value.content.toLongOrNull() ?: error("Invalid checkpoint")
    }
    private fun bounded(row: JsonObject, key: String, max: Int): Int = number(row, key).also { require(it <= max) }.toInt()
    private companion object { val mutex = Mutex(); const val MAX_BYTES = 128 * 1024; const val MAX_FILES = 4096 }
}
