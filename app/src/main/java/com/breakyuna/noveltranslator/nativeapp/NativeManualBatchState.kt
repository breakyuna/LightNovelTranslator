package com.breakyuna.noveltranslator.nativeapp

import kotlinx.coroutines.ensureActive
import com.breakyuna.noveltranslator.nativecore.domain.*

/** Small metadata only. A restored draft still requires an explicit confirmation and revision check. */
internal fun encodeManualBatch(batch: ManualTextBatch): ArrayList<String> = arrayListOf(
    "1", batch.commandId.value, batch.ref.projectId.value, batch.ref.instanceId.value,
    batch.ref.generation.value.toString(), batch.expectedRevision.toString(), batch.change.name,
).apply { batch.targets.forEach { add(it.id.value.toString()); add(it.row.toString()); add(it.fingerprint.sha256) } }

internal fun decodeManualBatch(values: List<String>?, maxTargets: Int = 100): ManualTextBatch? = runCatching {
    if (values == null || values.size !in 10..(7 + 3 * maxTargets) || values[0] != "1" || (values.size - 7) % 3 != 0) return null
    ManualTextBatch(CommandId(values[1]), LocalProjectRef(ProjectId(values[2]), ProjectInstanceId(values[3]),
        RecoveryGeneration(values[4].toLong())), values[5].toLong(), TextBatchChange.valueOf(values[6]),
        values.drop(7).chunked(3).map { TextBatchTarget(ItemId(it[0].toLong()), it[1].toInt(), ObjectFingerprint(it[2])) })
}.getOrNull()

/** Large selections stay in app-private files; SavedStateHandle carries only their digest. */
internal class NativeManualBatchFiles(context: android.content.Context) {
    private val directory = java.io.File(context.filesDir, "native_manual_batches")
    suspend fun save(batch: ManualTextBatch): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val digest = batch.digest()
        check(directory.isDirectory || directory.mkdirs())
        val destination = file(digest)
        if (!destination.exists()) {
            val retained = directory.listFiles().orEmpty().filter { it.extension == "json" }
            if (retained.size >= 4 || retained.sumOf { it.length() } > 64L * 1024 * 1024)
                throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("fields", kotlinx.serialization.json.JsonArray(encodeManualBatch(batch).map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }.toString().toByteArray(Charsets.UTF_8)
            if (payload.size > MAX_BYTES || retained.sumOf { it.length() } + payload.size > 64L * 1024 * 1024)
                throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
            val temporary = java.io.File(directory, java.util.UUID.randomUUID().toString() + ".tmp")
            try {
                java.io.FileOutputStream(temporary).use { it.write(payload); it.fd.sync() }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                check(temporary.renameTo(destination))
            } finally { temporary.delete() }
        } else if (load(digest) == null) throw TextProjectException(TextProjectException.Reason.CORRUPT_PROJECT)
        digest
    }
    suspend fun cleanupExcept(protected: String?) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        directory.listFiles().orEmpty().forEach {
            if (it.isFile && it.nameWithoutExtension != protected && it.extension in setOf("json", "tmp")) it.delete()
        }
    }
    suspend fun load(digest: String): ManualTextBatch? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!digest.matches(Regex("[0-9a-f]{64}"))) return@withContext null
        val source = file(digest)
        if (!source.isFile || source.length() !in 1..MAX_BYTES.toLong()) return@withContext null
        try {
            val root = JsonObjectValue.parse(source.readText(Charsets.UTF_8), JsonInputLimits(MAX_BYTES, 4)).json
            require(root.keys == setOf("fields"))
            val fields = root.getValue("fields") as kotlinx.serialization.json.JsonArray
            val values = fields.map {
                val text = it as kotlinx.serialization.json.JsonPrimitive
                require(text.isString); text.content
            }
            decodeManualBatch(values, 100_000)?.takeIf { it.digest() == digest }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    suspend fun remove(digest: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (digest.matches(Regex("[0-9a-f]{64}"))) file(digest).delete()
        Unit
    }
    private fun file(digest: String) = java.io.File(directory, "$digest.json")
    private companion object { const val MAX_BYTES = 32 * 1024 * 1024 }
}
