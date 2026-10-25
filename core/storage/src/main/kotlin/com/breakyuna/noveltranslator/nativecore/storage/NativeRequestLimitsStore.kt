package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimits
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimitsCodec
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimitsProvider
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Application-wide default concurrency/RPM, not a per-model Room row. Absence means
 * [ModelRequestLimits] defaults. Corrupt state is rejected so a damaged file cannot
 * silently raise the allowance. Revision/fingerprint checks reject a stale editor.
 */
class NativeRequestLimitsStore(context: Context) : ModelRequestLimitsProvider {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
    private val mutex = libraryMutex

    override suspend fun current(): ModelRequestLimits = load()

    suspend fun load(): ModelRequestLimits = withContext(Dispatchers.IO) {
        mutex.withLock { read()?.limits ?: ModelRequestLimits() }
    }

    /** Current saved fingerprint, or null when defaults are in effect. */
    suspend fun fingerprint(): String? = withContext(Dispatchers.IO) {
        mutex.withLock { read()?.fingerprint }
    }

    /**
     * Atomically replaces the global limits. [expectedFingerprint] must be null only
     * when defaults are in effect; otherwise it must match the current fingerprint.
     */
    suspend fun save(limits: ModelRequestLimits, expectedFingerprint: String? = null): ModelRequestLimits =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val previous = read()
                if (previous == null) {
                    require(expectedFingerprint == null) { "Request limits changed" }
                } else {
                    require(expectedFingerprint != null && expectedFingerprint == previous.fingerprint) {
                        "Request limits changed"
                    }
                }
                currentCoroutineContext().ensureActive()
                val revision = Math.addExact(previous?.revision ?: 0L, 1L)
                write(revision, ModelRequestLimitsCodec.fingerprint(limits), limits)
                limits
            }
        }

    private suspend fun read(): Stored? {
        val input = try {
            file.openRead()
        } catch (missing: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw IllegalStateException("Damaged request limits")
            return null
        }
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_BYTES) { "Request limits too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw IllegalStateException("Damaged request limits")
        }
        try {
            val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_BYTES, 4)).json
            require(root.keys == ROOT_KEYS)
            val revision = integer(root, "revision")
            require(revision > 0)
            val fingerprint = string(root, "fingerprint")
            require(FINGERPRINT.matches(fingerprint))
            val settingsObject = root["settings"] as? JsonObject ?: throw IllegalStateException("Damaged request limits")
            val limits = ModelRequestLimitsCodec.decode(settingsObject.toString())
            require(ModelRequestLimitsCodec.fingerprint(limits) == fingerprint)
            return Stored(revision, fingerprint, limits)
        } catch (failure: IllegalStateException) {
            throw failure
        } catch (_: Exception) {
            throw IllegalStateException("Damaged request limits")
        }
    }

    private suspend fun write(revision: Long, fingerprint: String, limits: ModelRequestLimits) {
        val json = buildJsonObject {
            put("revision", revision)
            put("fingerprint", fingerprint)
            put("settings", Json.parseToJsonElement(ModelRequestLimitsCodec.encode(limits)))
        }.toString()
        val bytes = TextProjectFingerprint.utf8(json)
        require(bytes.size <= MAX_BYTES) { "Request limits too large" }
        currentCoroutineContext().ensureActive()
        val output = file.startWrite()
        try {
            output.write(bytes)
            currentCoroutineContext().ensureActive()
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun integer(value: JsonObject, key: String): Long {
        val primitive = value[key] as? JsonPrimitive ?: throw IllegalStateException("Damaged request limits")
        require(!primitive.isString && INTEGER.matches(primitive.content))
        return primitive.content.toLongOrNull() ?: throw IllegalStateException("Damaged request limits")
    }

    private fun string(value: JsonObject, key: String): String {
        val primitive = value[key] as? JsonPrimitive ?: throw IllegalStateException("Damaged request limits")
        require(primitive.isString)
        return primitive.content
    }

    private class Stored(val revision: Long, val fingerprint: String, val limits: ModelRequestLimits)

    private companion object {
        val libraryMutex = Mutex()
        const val FILE_NAME = "native_model_request_limits.json"
        const val MAX_BYTES = 16 * 1024
        val ROOT_KEYS = setOf("revision", "fingerprint", "settings")
        val INTEGER = Regex("0|[1-9][0-9]*")
        val FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}
