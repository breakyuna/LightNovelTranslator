package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsCodec
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsProvider
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsSource
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
 * Credential-free network policy for compatible model requests. Absence means
 * [ModelNetworkSettings.DEFAULT]; corrupt or partially written state is an error,
 * never a silent direct-connect fallback. The envelope adds a revision and content
 * fingerprint so a stale editor cannot overwrite a newer saved policy.
 *
 * This file contains no API key, authorization header or proxy password. The proxy
 * host is configuration rather than a secret, but no logging is performed here.
 */
class NativeModelNetworkSettingsStore(context: Context) : ModelNetworkSettingsProvider {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
    private val mutex = libraryMutex

    override suspend fun networkSettings(): ModelNetworkSettings = load()

    suspend fun load(): ModelNetworkSettings = withContext(Dispatchers.IO) {
        mutex.withLock { read()?.settings ?: ModelNetworkSettings.DEFAULT }
    }

    /** Current saved fingerprint, or null when no saved policy exists. */
    suspend fun fingerprint(): String? = withContext(Dispatchers.IO) {
        mutex.withLock { read()?.fingerprint }
    }

    /**
     * Atomically replaces the saved policy. [expectedFingerprint] must be null only
     * when no saved policy exists; otherwise it must match the current fingerprint.
     */
    suspend fun save(settings: ModelNetworkSettings, expectedFingerprint: String? = null): ModelNetworkSettings =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val previous = read()
                if (previous == null) {
                    require(expectedFingerprint == null) { "Network settings changed" }
                } else {
                    require(expectedFingerprint != null && expectedFingerprint == previous.fingerprint) {
                        "Network settings changed"
                    }
                }
                currentCoroutineContext().ensureActive()
                val saved = settings.saved()
                val revision = Math.addExact(previous?.revision ?: 0L, 1L)
                write(revision, ModelNetworkSettingsCodec.fingerprint(saved), saved)
                saved
            }
        }

    private suspend fun read(): Stored? {
        val input = try {
            file.openRead()
        } catch (missing: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw InvalidModelNetworkSettings()
            return null
        }
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_BYTES) { "Network settings too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw InvalidModelNetworkSettings()
        }
        try {
            val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_BYTES, 8)).json
            require(root.keys == ROOT_KEYS)
            val revision = integer(root, "revision")
            require(revision > 0)
            val fingerprint = string(root, "fingerprint")
            require(FINGERPRINT.matches(fingerprint))
            val settingsObject = root["settings"] as? JsonObject ?: throw InvalidModelNetworkSettings()
            val settings = ModelNetworkSettingsCodec.decode(settingsObject.toString())
            require(settings.source == ModelNetworkSettingsSource.SAVED)
            require(ModelNetworkSettingsCodec.fingerprint(settings) == fingerprint)
            return Stored(revision, fingerprint, settings)
        } catch (invalid: InvalidModelNetworkSettings) {
            throw invalid
        } catch (_: Exception) {
            throw InvalidModelNetworkSettings()
        }
    }

    private suspend fun write(revision: Long, fingerprint: String, settings: ModelNetworkSettings) {
        val json = buildJsonObject {
            put("revision", revision)
            put("fingerprint", fingerprint)
            put("settings", Json.parseToJsonElement(ModelNetworkSettingsCodec.encode(settings)))
        }.toString()
        val bytes = TextProjectFingerprint.utf8(json)
        require(bytes.size <= MAX_BYTES) { "Network settings too large" }
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
        val primitive = value[key] as? JsonPrimitive ?: throw InvalidModelNetworkSettings()
        require(!primitive.isString && INTEGER.matches(primitive.content))
        return primitive.content.toLongOrNull() ?: throw InvalidModelNetworkSettings()
    }

    private fun string(value: JsonObject, key: String): String {
        val primitive = value[key] as? JsonPrimitive ?: throw InvalidModelNetworkSettings()
        require(primitive.isString)
        return primitive.content
    }

    private class Stored(val revision: Long, val fingerprint: String, val settings: ModelNetworkSettings)

    private companion object {
        val libraryMutex = Mutex()
        const val FILE_NAME = "native_model_network_settings.json"
        const val MAX_BYTES = 64 * 1024
        val ROOT_KEYS = setOf("revision", "fingerprint", "settings")
        val INTEGER = Regex("0|[1-9][0-9]*")
        val FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}
