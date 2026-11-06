package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.InvalidLaboratorySettings
import com.breakyuna.noveltranslator.nativecore.domain.LaboratorySettings
import com.breakyuna.noveltranslator.nativecore.domain.LaboratorySettingsCodec
import com.breakyuna.noveltranslator.nativecore.domain.LaboratorySettingsProvider
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
 * Application-scoped laboratory switches. Absence is [LaboratorySettings.DEFAULT];
 * corrupt or partially written state fails closed instead of silently turning a
 * switch on. No model content, credential or project data enters this file.
 */
class NativeLaboratorySettingsStore(context: Context) : LaboratorySettingsProvider {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
    private val mutex = Mutex()

    override suspend fun current(): LaboratorySettings = load()

    suspend fun load(): LaboratorySettings = withContext(Dispatchers.IO) {
        mutex.withLock { read()?.settings ?: LaboratorySettings.DEFAULT }
    }

    suspend fun fingerprint(): String? = withContext(Dispatchers.IO) {
        mutex.withLock { read()?.fingerprint }
    }

    suspend fun save(settings: LaboratorySettings, expectedFingerprint: String? = null): LaboratorySettings =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val previous = read()
                if (previous == null) require(expectedFingerprint == null) { "Laboratory settings changed" }
                else require(expectedFingerprint != null && expectedFingerprint == previous.fingerprint) {
                    "Laboratory settings changed"
                }
                currentCoroutineContext().ensureActive()
                val revision = Math.addExact(previous?.revision ?: 0L, 1L)
                val fingerprint = LaboratorySettingsCodec.fingerprint(settings)
                write(revision, fingerprint, settings)
                settings
            }
        }

    private suspend fun read(): Stored? {
        val input = try { file.openRead() } catch (missing: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                throw InvalidLaboratorySettings()
            return null
        }
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { throw InvalidLaboratorySettings() }
        val root = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            ?: throw InvalidLaboratorySettings()
        if (root.keys != ENVELOPE_KEYS) throw InvalidLaboratorySettings()
        if (string(root, "format") != ENVELOPE_FORMAT || integer(root, "formatVersion") != ENVELOPE_VERSION.toLong())
            throw InvalidLaboratorySettings()
        val revision = integer(root, "revision")
        val fingerprint = string(root, "fingerprint")
        val settingsObject = root["settings"] as? JsonObject ?: throw InvalidLaboratorySettings()
        val settings = try { LaboratorySettingsCodec.decode(settingsObject.toString()) }
        catch (_: Exception) { throw InvalidLaboratorySettings() }
        if (LaboratorySettingsCodec.fingerprint(settings) != fingerprint) throw InvalidLaboratorySettings()
        return Stored(revision, fingerprint, settings)
    }

    private fun write(revision: Long, fingerprint: String, settings: LaboratorySettings) {
        val payload = buildJsonObject {
            put("format", ENVELOPE_FORMAT)
            put("formatVersion", ENVELOPE_VERSION)
            put("revision", revision)
            put("fingerprint", fingerprint)
            put("settings", Json.parseToJsonElement(LaboratorySettingsCodec.encode(settings)))
        }.toString()
        val output = file.startWrite()
        try {
            output.write(payload.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun string(root: JsonObject, key: String): String {
        val value = root[key] as? JsonPrimitive ?: throw InvalidLaboratorySettings()
        if (!value.isString) throw InvalidLaboratorySettings()
        return value.content
    }

    private fun integer(root: JsonObject, key: String): Long {
        val item = root[key] as? JsonPrimitive ?: throw InvalidLaboratorySettings()
        if (item.isString) throw InvalidLaboratorySettings()
        val text = item.content
        if (text.isEmpty() || text.length > 18 || text.any { it !in '0'..'9' }) throw InvalidLaboratorySettings()
        return text.toLongOrNull() ?: throw InvalidLaboratorySettings()
    }

    private class Stored(val revision: Long, val fingerprint: String, val settings: LaboratorySettings)

    private companion object {
        const val FILE_NAME = "native-laboratory-settings.json"
        const val ENVELOPE_FORMAT = "lnt-laboratory-envelope"
        const val ENVELOPE_VERSION = 1
        const val MAX_BYTES = 16 * 1024
        val ENVELOPE_KEYS = setOf("format", "formatVersion", "revision", "fingerprint", "settings")
    }
}
