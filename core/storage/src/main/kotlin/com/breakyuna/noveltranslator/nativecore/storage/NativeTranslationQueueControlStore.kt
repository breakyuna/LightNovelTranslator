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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** User intent kind, stored before live runner control and before the queue command. */
enum class NativeTranslationQueueControlKind { PENDING_PAUSE, PENDING_CANCEL }

/**
 * One durable pause/cancel intent. It stores only the frozen task identity, the exact command and
 * the intent kind; no source text, translation, prompt, credential or runtime token is retained.
 */
data class NativeTranslationQueueControlIntent(
    val identity: TranslationTaskQueueIdentity,
    val command: CommandId,
    val kind: NativeTranslationQueueControlKind,
) {
    init {
        require(identity.run.chatId == null) { "A control intent belongs to a translation task" }
    }

    override fun toString(): String =
        "NativeTranslationQueueControlIntent(run=${identity.run.runId.value}, kind=$kind)"
}

/**
 * Independent atomic-file storage for pause/cancel intents. One file per exact task identity means
 * a later cancel supersedes an earlier pause without touching the queue v2 document. A missing
 * file means no pending intent; a corrupt, foreign or oversized file throws [TranslationQueueCorrupt]
 * instead of silently losing a user stop request.
 *
 * [clear] is compare-and-clear: a pause that finishes after a newer cancel was persisted cannot
 * delete the newer intent.
 */
class NativeTranslationQueueControlStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, DIRECTORY)

    suspend fun load(): List<NativeTranslationQueueControlIntent> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!directory.exists()) return@withLock emptyList()
            val files = directory.listFiles()
                ?: throw TranslationQueueCorrupt("Translation queue control directory unavailable")
            if (files.size > MAX_FILES) {
                throw TranslationQueueCorrupt("Translation queue control directory exceeds its file budget")
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
            val result = ArrayList<NativeTranslationQueueControlIntent>(names.size)
            val identities = HashSet<TranslationTaskQueueIdentity>()
            for (name in names) {
                val stored = read(File(directory, name))
                    ?: throw TranslationQueueCorrupt("Translation queue control file vanished")
                if (fileFor(stored.identity).name != name) {
                    throw TranslationQueueCorrupt("Translation queue control file name does not match its task")
                }
                if (!identities.add(stored.identity)) {
                    throw TranslationQueueCorrupt("Duplicate translation queue control intent")
                }
                result.add(stored)
            }
            result
        }
    }

    suspend fun save(intent: NativeTranslationQueueControlIntent): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw TranslationQueueCorrupt("Translation queue control directory is unavailable")
            }
            val target = fileFor(intent.identity)
            if (target.exists()) {
                // A stale AtomicFile pair beside a published file must not be restored later.
                File(target.path + ".bak").delete()
                File(target.path + ".new").delete()
            }
            val body = encode(intent)
            val fingerprint = fingerprint(body)
            val document = buildJsonObject {
                body.forEach { (key, value) -> put(key, value) }
                put("fingerprint", fingerprint.sha256)
            }
            val bytes = TextProjectFingerprint.utf8(document.toString())
            if (bytes.size > MAX_BYTES) {
                throw TranslationQueueCorrupt("Translation queue control intent exceeds its bound")
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
        }
    }

    /** Deletes only the exact [intent]; a newer pause/cancel for the same task is preserved. */
    suspend fun clear(intent: NativeTranslationQueueControlIntent): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = fileFor(intent.identity)
            val current = read(target) ?: return@withLock
            if (current != intent) return@withLock
            AtomicFile(target).delete()
            // AtomicFile deletes its backup as well; an orphaned unfinished write is never a base.
            File(target.path + ".new").delete()
        }
    }

    /** Exposed for corruption/crash tests; production callers use [load]/[save]/[clear]. */
    internal fun fileFor(identity: TranslationTaskQueueIdentity): File {
        val material = JsonArray(
            listOf(
                JsonPrimitive(identity.ref.projectId.value),
                JsonPrimitive(identity.ref.instanceId.value),
                JsonPrimitive(identity.ref.generation.value),
                JsonPrimitive(identity.run.runId.value),
                JsonPrimitive(identity.run.commandId.value),
                JsonPrimitive(identity.configuration.id),
                JsonPrimitive(identity.configuration.revision),
                JsonPrimitive(identity.revision),
            ),
        )
        val name = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(material.toString()))
        return File(directory, name + SUFFIX)
    }

    private fun read(source: File): NativeTranslationQueueControlIntent? {
        val backup = File(source.path + ".bak")
        val unfinished = File(source.path + ".new")
        if (!source.exists() && !backup.exists()) {
            if (unfinished.exists()) {
                throw TranslationQueueCorrupt("Translation queue control write did not complete")
            }
            return null
        }
        if (!source.exists()) {
            if (unfinished.exists()) {
                throw TranslationQueueCorrupt("Translation queue control has both a backup and an unfinished write")
            }
            if (!backup.renameTo(source)) {
                throw TranslationQueueCorrupt("Translation queue control backup is unavailable")
            }
        }
        val bytes = try {
            AtomicFile(source).openRead().use { it.readBounded() }
        } catch (failure: TranslationQueueCorrupt) {
            throw failure
        } catch (failure: FileNotFoundException) {
            throw TranslationQueueCorrupt("Translation queue control file is unavailable")
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw TranslationQueueCorrupt("Translation queue control file is not UTF-8")
        }
        return decode(text)
    }

    private fun InputStream.readBounded(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > MAX_BYTES) {
                throw TranslationQueueCorrupt("Translation queue control file exceeds its bound")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decode(text: String): NativeTranslationQueueControlIntent {
        try {
            val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_BYTES, MAX_DEPTH)).json
            require(root.keys == ROOT_KEYS)
            require(number(root, "version") == VERSION)
            val scope = array(root, "scope")
            require(scope.size == 3)
            val ref = LocalProjectRef(
                ProjectId(stringValue(scope[0])),
                ProjectInstanceId(stringValue(scope[1])),
                RecoveryGeneration(numberValue(scope[2])),
            )
            val identity = TranslationTaskQueueIdentity(
                ref = ref,
                run = RunIdentity(
                    projectId = ref.projectId,
                    chatId = null,
                    runId = RunId(string(root, "run")),
                    commandId = CommandId(string(root, "run_command")),
                ),
                configuration = ModelConfigurationRef(
                    id = string(root, "configuration"),
                    revision = number(root, "configuration_revision"),
                ),
                revision = number(root, "revision"),
            )
            val intent = NativeTranslationQueueControlIntent(
                identity = identity,
                command = CommandId(string(root, "command")),
                kind = NativeTranslationQueueControlKind.valueOf(string(root, "intent")),
            )
            val stored = ObjectFingerprint(string(root, "fingerprint"))
            require(stored == fingerprint(encode(intent))) { "Translation queue control fingerprint mismatch" }
            return intent
        } catch (failure: TranslationQueueCorrupt) {
            throw failure
        } catch (_: Exception) {
            throw TranslationQueueCorrupt("Invalid translation queue control intent")
        }
    }

    private fun encode(intent: NativeTranslationQueueControlIntent): JsonObject = buildJsonObject {
        put("version", VERSION)
        put(
            "scope",
            JsonArray(
                listOf(
                    JsonPrimitive(intent.identity.ref.projectId.value),
                    JsonPrimitive(intent.identity.ref.instanceId.value),
                    JsonPrimitive(intent.identity.ref.generation.value),
                ),
            ),
        )
        put("run", intent.identity.run.runId.value)
        put("run_command", intent.identity.run.commandId.value)
        put("configuration", intent.identity.configuration.id)
        put("configuration_revision", intent.identity.configuration.revision)
        put("revision", intent.identity.revision)
        put("command", intent.command.value)
        put("intent", intent.kind.name)
    }

    private fun fingerprint(payload: JsonObject): ObjectFingerprint =
        ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(payload.toString())))

    private fun array(row: JsonObject, key: String): JsonArray = row[key] as? JsonArray ?: invalid()

    private fun string(row: JsonObject, key: String): String = stringValue(row[key] ?: invalid())

    private fun stringValue(value: JsonElement): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()

    private fun number(row: JsonObject, key: String): Long =
        numberValue(row[key] ?: invalid())

    private fun numberValue(value: JsonElement): Long {
        val primitive = value as? JsonPrimitive ?: invalid()
        if (primitive.isString) invalid()
        val text = primitive.content
        require(text == "0" || (text.isNotEmpty() && text[0] in '1'..'9' && text.all { it in '0'..'9' })) {
            "Invalid translation queue control number"
        }
        return text.toLongOrNull() ?: invalid()
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid translation queue control intent")

    internal companion object {
        const val MAX_BYTES = 64 * 1024
        const val MAX_DEPTH = 8
        const val MAX_FILES = 4_096

        private const val VERSION = 1L
        private const val DIRECTORY = "native_translation_queue_control"
        private const val SUFFIX = ".json"
        private val mutex = Mutex()

        private val ROOT_KEYS = setOf(
            "version", "scope", "run", "run_command", "configuration", "configuration_revision",
            "revision", "command", "intent", "fingerprint",
        )
    }
}
