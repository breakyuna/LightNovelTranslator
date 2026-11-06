package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Credential-free rotation metadata. Corruption never silently re-enables an authentication failure. */
class NativeModelKeyStateStore(context: Context) : ModelKeyStatePort {
    private val directory = File(context.applicationContext.filesDir, "native_model_key_states")

    override suspend fun read(configuration: ModelConfigurationRef): ModelKeyPoolState? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val source = file(configuration)
            if (!source.exists() && !File(source.path + ".bak").exists()) return@withLock null
            val bytes = AtomicFile(source).openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= MAX_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_BYTES, 8)).json
            require(root.keys == setOf("format", "version", "configuration", "revision", "next", "keys"))
            require(string(root, "format") == "lnt-key-state" && integer(root, "version") == 1L)
            require(string(root, "configuration") == configuration.id && integer(root, "revision") == configuration.revision)
            val values = root["keys"] as? JsonArray ?: error("Invalid key state")
            require(values.size <= NativeModelSettings.MAX_KEYS)
            val keys = values.map { value ->
                val row = value as? JsonObject ?: error("Invalid key state")
                require(row.keys == setOf("enabled", "failures", "until", "failure"))
                val enabled = (row["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                    ?: error("Invalid key state")
                val failures = integer(row, "failures"); require(failures in 0..Int.MAX_VALUE.toLong())
                val until = integer(row, "until"); require(until >= 0)
                val failure = if (row["failure"] == JsonNull) null else ModelKeyFailure.valueOf(string(row, "failure"))
                ModelKeyState(enabled, failures.toInt(), until, failure)
            }
            val next = integer(root, "next"); require(next in 0..Int.MAX_VALUE.toLong())
            ModelKeyPoolState(configuration, next.toInt(), keys)
        }
    }

    override suspend fun write(state: ModelKeyPoolState): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(directory.isDirectory || directory.mkdirs())
            val source = file(state.configuration)
            if (!source.exists() && !File(source.path + ".bak").exists()) {
                // Never evict old disable/cooldown facts just to make room for a new revision.
                require((directory.listFiles() ?: error("Key state directory unavailable")).size < MAX_FILES)
            }
            val json = buildJsonObject {
                put("format", "lnt-key-state"); put("version", 1)
                put("configuration", state.configuration.id); put("revision", state.configuration.revision)
                put("next", state.nextIndex)
                put("keys", buildJsonArray {
                    state.keys.forEach { key -> add(buildJsonObject {
                        put("enabled", key.enabled); put("failures", key.failures); put("until", key.cooldownUntilMillis)
                        put("failure", key.lastFailure?.let { JsonPrimitive(it.name) } ?: JsonNull)
                    }) }
                })
            }
            val bytes = TextProjectFingerprint.utf8(json.toString()); require(bytes.size <= MAX_BYTES)
            currentCoroutineContext().ensureActive()
            val atomic = AtomicFile(source)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                currentCoroutineContext().ensureActive()
                atomic.finishWrite(output)
            } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        }
    }

    private fun file(configuration: ModelConfigurationRef): File {
        val identity = buildJsonArray { add(JsonPrimitive(configuration.id)); add(JsonPrimitive(configuration.revision)) }.toString()
        val name = MessageDigest.getInstance("SHA-256").digest(TextProjectFingerprint.utf8(identity))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "$name.json")
    }

    private fun string(value: JsonObject, key: String): String =
        (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid key state")

    private fun integer(value: JsonObject, key: String): Long {
        val primitive = value[key] as? JsonPrimitive ?: error("Invalid key state")
        require(!primitive.isString && primitive.content.matches(Regex("0|[1-9][0-9]*")))
        return primitive.content.toLongOrNull() ?: error("Invalid key state")
    }

    private companion object {
        val mutex = Mutex()
        const val MAX_BYTES = 128 * 1024
        const val MAX_FILES = 4096
    }
}
