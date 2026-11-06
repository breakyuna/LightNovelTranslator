package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One bounded, atomically replaced native library. Never writes a project or starts a model. */
class NativePromptTemplateStore(context: Context) {
    private val source = File(context.applicationContext.filesDir, "native_prompt_templates.json")
    private val mutex = libraryMutex
    suspend fun read(): PromptTemplateLibrarySnapshot = withContext(Dispatchers.IO) { mutex.withLock { snapshot() } }
    suspend fun replace(expected: String, templates: PromptTemplateSet): PromptTemplateLibrarySnapshot =
        withContext(Dispatchers.IO) { mutex.withLock {
            require(snapshot().fingerprint == expected) { "Template library changed" }
            currentCoroutineContext().ensureActive()
            val bytes = TextProjectFingerprint.utf8(PromptTemplateCodec.encode(templates))
            require(bytes.size <= MAX_BYTES)
            val atomic = AtomicFile(source)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                currentCoroutineContext().ensureActive()
                atomic.finishWrite(output)
            } catch (error: Throwable) { atomic.failWrite(output); throw error }
            PromptTemplateLibrarySnapshot(templates, PromptTemplateCodec.fingerprint(templates))
        } }
    private fun snapshot(): PromptTemplateLibrarySnapshot {
        if (!source.exists() && !File(source.path + ".bak").exists()) {
            val empty = PromptTemplateSet(emptyList())
            return PromptTemplateLibrarySnapshot(empty, PromptTemplateCodec.fingerprint(empty))
        }
        val text = AtomicFile(source).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
        }
        val templates = PromptTemplateCodec.decode(text)
        return PromptTemplateLibrarySnapshot(templates, PromptTemplateCodec.fingerprint(templates))
    }
    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        val libraryMutex = Mutex()
    }
}
