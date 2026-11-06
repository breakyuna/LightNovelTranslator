package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Application-owned native presets. They never modify a project or authorize an Agent write. */
class NativeRulePresetStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "native_rule_presets")
    private val mutex = libraryMutex
    suspend fun list(): List<RulePresetSummary> = withContext(Dispatchers.IO) { mutex.withLock {
        files().map { file ->
            currentCoroutineContext().ensureActive()
            val preset = read(file)
            RulePresetSummary(preset.id, preset.name, preset.description, preset.rules.size)
        }.sortedWith(compareBy<RulePresetSummary> { it.name }.thenBy { it.id })
    } }
    suspend fun load(id: String): RulePreset = withContext(Dispatchers.IO) { mutex.withLock {
        read(file(id)).also { require(it.id == id) }
    } }
    /** Expected fingerprint prevents a stale editor from overwriting a different saved preset. */
    suspend fun save(preset: RulePreset, expected: String? = null): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        check(directory.isDirectory || directory.mkdirs())
        val destination = file(preset.id)
        val existing = files()
        if (destination.exists() || File(destination.path + ".bak").exists()) {
            require(expected != null && fingerprint(read(destination)) == expected)
        } else {
            require(expected == null)
            require(existing.size < RulePresetCatalog.MAX_PRESETS)
        }
        val text = RulePresetCodec.encode(preset)
        val bytes = TextProjectFingerprint.utf8(text)
        require(existing.filter { it != destination }.sumOf { retainedSize(it) } + bytes.size <= MAX_BYTES)
        currentCoroutineContext().ensureActive()
        val atomic = AtomicFile(destination)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            currentCoroutineContext().ensureActive()
            atomic.finishWrite(output)
        } catch (error: Throwable) { atomic.failWrite(output); throw error }
    } }
    suspend fun remove(id: String, expected: String): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        val source = file(id)
        require(fingerprint(read(source)) == expected)
        currentCoroutineContext().ensureActive()
        AtomicFile(source).delete()
    } }
    private fun read(source: File): RulePreset {
        val atomic = AtomicFile(source)
        val text = atomic.openRead().use { input ->
            val bytes = input.readBytesBounded(RulePresetCodec.MAX_CHARS * 4)
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
        val preset = RulePresetCodec.decode(text)
        require(file(preset.id).name == source.name)
        return preset
    }
    private fun files(): List<File> {
        val files = directory.listFiles().orEmpty().mapNotNull {
            val name = it.name.removeSuffix(".bak")
            if (name.matches(Regex("[0-9a-f]{64}\\.json"))) File(directory, name) else null
        }.distinctBy { it.name }
        require(files.size <= RulePresetCatalog.MAX_PRESETS && files.sumOf { retainedSize(it) } <= MAX_BYTES)
        return files
    }
    private fun retainedSize(file: File): Long = if (file.isFile) file.length() else File(file.path + ".bak").length()
    private fun file(id: String): File {
        require(id.isNotBlank() && id.length <= RulePreset.MAX_ID_CHARS && id.none(Char::isISOControl))
        return File(directory, TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(id)) + ".json")
    }
    companion object {
        private val libraryMutex = Mutex()
        private const val MAX_BYTES = 16L * 1024 * 1024
        fun fingerprint(preset: RulePreset): String = RulePresetCodec.fingerprint(preset)
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
