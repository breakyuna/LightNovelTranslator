package com.breakyuna.noveltranslator.nativeapp

import kotlinx.coroutines.ensureActive
import com.breakyuna.noveltranslator.nativecore.domain.*

/** Fixed target metadata and bounded replacement parameters; restoring never grants write permission. */
internal fun encodeManualBatch(batch: ManualTextBatch): ArrayList<String> = arrayListOf(
    batch.replacement?.let { if (it.regex) "3" else "2" } ?: "1", batch.commandId.value, batch.ref.projectId.value, batch.ref.instanceId.value,
    batch.ref.generation.value.toString(), batch.expectedRevision.toString(), batch.change.name,
).apply {
    batch.replacement?.let { add(it.find); add(it.replacement); add(it.caseSensitive.toString()); if (it.regex) add("true") }
    batch.targets.forEach { add(it.id.value.toString()); add(it.row.toString()); add(it.fingerprint.sha256) }
}

internal fun decodeManualBatch(values: List<String>?, maxTargets: Int = 100): ManualTextBatch? = runCatching {
    if (values == null || values.isEmpty() || maxTargets !in 1..100_000) return null
    val offset = when (values[0]) { "1" -> 7; "2" -> 10; "3" -> 11; else -> return null }
    if (values.size !in (offset + 3)..(offset + 3 * maxTargets) || (values.size - offset) % 3 != 0) return null
    if (offset == 11 && values[10] != "true") return null
    val replacement = if (offset >= 10) TextReplacementOptions(values[7], values[8], when (values[9]) {
        "true" -> true; "false" -> false; else -> return null
    }, regex = offset == 11) else null
    ManualTextBatch(CommandId(values[1]), LocalProjectRef(ProjectId(values[2]), ProjectInstanceId(values[3]),
        RecoveryGeneration(values[4].toLong())), values[5].toLong(), TextBatchChange.valueOf(values[6]),
        values.drop(offset).chunked(3).map { TextBatchTarget(ItemId(it[0].toLong()), it[1].toInt(), ObjectFingerprint(it[2])) }, replacement)
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
