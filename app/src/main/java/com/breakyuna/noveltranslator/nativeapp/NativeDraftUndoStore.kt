package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Hard bounds for the private undo journal. States are full snapshots so recovery never needs
 * to replay a command or reuse a write authorization. The current state is always retained;
 * older undo/redo states are dropped first.
 */
internal const val NATIVE_DRAFT_UNDO_MAX_STATES = 51 // current + at most 50 undo/redo states
internal const val NATIVE_DRAFT_UNDO_MAX_FILE_BYTES = 2 * 1024 * 1024
internal const val NATIVE_DRAFT_UNDO_MAX_DIRECTORY_BYTES = 8 * 1024 * 1024

private const val NATIVE_DRAFT_UNDO_VERSION = 1
private val MAX_TEXT_CHARS = TextProjectLimits().maxItemChars
private val STRICT_JSON = Json { isLenient = false }
private const val MAX_STORED_NAMES = 256
private const val MAX_STORED_NAME_CHARS = 4_096
private const val MAX_STORED_TOTAL_NAME_CHARS = 262_144

/** One process-wide write lock so two ViewModels cannot interleave the same journal file. */
private val NATIVE_DRAFT_UNDO_FILE_MUTEX = Mutex()
private val NATIVE_DRAFT_UNDO_SEQUENCE_LOCK = Any()
private var NATIVE_DRAFT_UNDO_SEQUENCE = 0L
private val NATIVE_DRAFT_UNDO_APPLIED = HashMap<String, Long>()

private fun nextDraftUndoRequest(): Long = synchronized(NATIVE_DRAFT_UNDO_SEQUENCE_LOCK) {
    ++NATIVE_DRAFT_UNDO_SEQUENCE
}

private fun draftUndoApplied(path: String): Long = synchronized(NATIVE_DRAFT_UNDO_SEQUENCE_LOCK) {
    NATIVE_DRAFT_UNDO_APPLIED[path] ?: 0L
}

private fun markDraftUndoApplied(path: String, request: Long) {
    synchronized(NATIVE_DRAFT_UNDO_SEQUENCE_LOCK) { NATIVE_DRAFT_UNDO_APPLIED[path] = request }
}

/** Field scope keeps body/status and name drafts from ever sharing an undo timeline. */
internal enum class NativeDraftUndoScope(val wire: String) {
    BODY_STATUS("body-status"),
    NAMES("names");

    companion object {
        fun fromWire(value: String): NativeDraftUndoScope? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Complete identity of one undo timeline. Revision and fingerprint are part of the binding, so
 * a timeline from an older saved revision can never be replayed onto a newer item.
 */
internal data class NativeDraftUndoKey(
    val ref: LocalProjectRef,
    val assetId: Long,
    val revision: Long,
    val itemId: ItemId,
    val fingerprint: ObjectFingerprint,
    val scope: NativeDraftUndoScope,
) {
    init {
        require(assetId > 0)
        require(revision >= 0)
    }

    override fun toString(): String = "NativeDraftUndoKey(redacted)"
}

/** A full editor state. Bodies and names stay out of toString/log output. */
internal data class NativeDraftUndoSnapshot(
    val text: String,
    val status: TextItemStatus,
    val sourceNames: List<String> = emptyList(),
    val translationNames: List<String> = emptyList(),
) {
    override fun toString(): String = "NativeDraftUndoSnapshot(redacted)"
}

internal data class NativeDraftUndoTimeline(
    val key: NativeDraftUndoKey,
    val states: List<NativeDraftUndoSnapshot>,
    val currentIndex: Int,
) {
    init {
        require(states.isNotEmpty())
        require(currentIndex in states.indices)
    }

    val undoCount: Int get() = currentIndex
    val canUndo: Boolean get() = currentIndex > 0
    val canRedo: Boolean get() = currentIndex < states.lastIndex

    override fun toString(): String = "NativeDraftUndoTimeline(redacted)"
}

/**
 * Session history with snapshot states. A key change starts a new baseline instead of carrying
 * patches across projects, assets, items, revisions or fingerprints.
 */
internal class NativeDraftUndoHistory {
    var key: NativeDraftUndoKey? = null
        private set

    private val states = ArrayList<NativeDraftUndoSnapshot>()
    private var index = -1

    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index >= 0 && index < states.lastIndex
    val undoCount: Int get() = index.coerceAtLeast(0)

    fun currentSnapshot(): NativeDraftUndoSnapshot? = states.getOrNull(index)

    fun reset(key: NativeDraftUndoKey, snapshot: NativeDraftUndoSnapshot) {
        this.key = key
        states.clear()
        states.add(snapshot)
        index = 0
    }

    /** Returns true when the timeline changed and needs persistence. */
    fun record(key: NativeDraftUndoKey, snapshot: NativeDraftUndoSnapshot): Boolean {
        if (this.key != key) {
            reset(key, snapshot)
            return true
        }
        if (states.getOrNull(index) == snapshot) return false
        while (states.size > index + 1) states.removeAt(states.lastIndex)
        states.add(snapshot)
        index = states.lastIndex
        if (states.size > NATIVE_DRAFT_UNDO_MAX_STATES) {
            states.removeAt(0)
            index--
        }
        return true
    }

    fun restore(timeline: NativeDraftUndoTimeline, key: NativeDraftUndoKey,
                current: NativeDraftUndoSnapshot): Boolean {
        if (timeline.key != key || timeline.states.size > NATIVE_DRAFT_UNDO_MAX_STATES ||
            timeline.states[timeline.currentIndex] != current) return false
        this.key = key
        states.clear()
        states.addAll(timeline.states)
        index = timeline.currentIndex
        return true
    }

    fun undo(key: NativeDraftUndoKey, current: NativeDraftUndoSnapshot): NativeDraftUndoSnapshot? {
        if (this.key != key || !canUndo || states.getOrNull(index) != current) return null
        index--
        return states[index]
    }

    fun redo(key: NativeDraftUndoKey, current: NativeDraftUndoSnapshot): NativeDraftUndoSnapshot? {
        if (this.key != key || !canRedo || states.getOrNull(index) != current) return null
        index++
        return states[index]
    }

    fun timeline(): NativeDraftUndoTimeline? {
        val key = key ?: return null
        if (states.isEmpty() || index !in states.indices) return null
        return NativeDraftUndoTimeline(key, states.toList(), index)
    }

    fun clear() {
        key = null
        states.clear()
        index = -1
    }
}

internal sealed interface NativeDraftUndoLoad {
    data object Missing : NativeDraftUndoLoad
    data class Restored(val timeline: NativeDraftUndoTimeline) : NativeDraftUndoLoad

    /** The file belongs to the same item but another revision/fingerprint. */
    data object Stale : NativeDraftUndoLoad
    data object Corrupt : NativeDraftUndoLoad
    data object Unsupported : NativeDraftUndoLoad
}

/**
 * App-private, versioned snapshot journal under filesDir/native-draft-undo/<hash>.json.
 * Writes run on a store-owned IO scope and are serialized; failed or corrupt files are
 * deleted instead of being partially interpreted.
 */
internal class NativeDraftUndoStore(
    context: Context,
    private val directory: File = File(context.filesDir, "native-draft-undo"),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = NATIVE_DRAFT_UNDO_FILE_MUTEX

    fun requestSave(timeline: NativeDraftUndoTimeline) {
        val file = file(timeline.key)
        val path = file.absolutePath
        val request = nextDraftUndoRequest()
        scope.launch {
            mutex.withLock {
                if (request <= draftUndoApplied(path)) return@withLock
                try {
                    val payload = encodeBounded(timeline)
                    if (payload == null) delete(file) else writeAtomic(file, payload)
                    markDraftUndoApplied(path, request)
                    if (payload != null) trimDirectory(except = file)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    delete(file)
                    markDraftUndoApplied(path, request)
                }
            }
        }
    }

    fun requestClear(key: NativeDraftUndoKey) {
        val file = file(key)
        val path = file.absolutePath
        val request = nextDraftUndoRequest()
        scope.launch {
            mutex.withLock {
                if (request <= draftUndoApplied(path)) return@withLock
                delete(file)
                markDraftUndoApplied(path, request)
            }
        }
    }

    suspend fun load(key: NativeDraftUndoKey): NativeDraftUndoLoad = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = file(key)
            if (!file.exists() && !sidecar(file, ".new").exists() && !sidecar(file, ".bak").exists()) {
                return@withLock NativeDraftUndoLoad.Missing
            }
            if (file.exists() && file.length() !in 1..NATIVE_DRAFT_UNDO_MAX_FILE_BYTES.toLong()) {
                return@withLock reject(file)
            }
            val bytes = try {
                AtomicFile(file).openRead().use { readBounded(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock reject(file)
            }
            val result = try {
                parse(bytes, key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NativeDraftUndoLoad.Corrupt
            }
            if (result !is NativeDraftUndoLoad.Restored) reject(file)
            result
        }
    }

    internal fun fileFor(key: NativeDraftUndoKey): File = file(key)

    private fun reject(file: File): NativeDraftUndoLoad {
        delete(file)
        // Suppress an older queued write for the file just rejected as stale/corrupt.
        markDraftUndoApplied(file.absolutePath, nextDraftUndoRequest())
        return NativeDraftUndoLoad.Corrupt
    }

    /** Test/lifecycle barrier; never called on the main thread by the UI. */
    internal suspend fun awaitIdle() {
        while (true) {
            val jobs = scope.coroutineContext[Job]?.children?.toList().orEmpty()
            if (jobs.isEmpty()) return
            jobs.forEach { it.join() }
        }
    }

    private fun file(key: NativeDraftUndoKey): File {
        val identity = listOf(
            "native-draft-undo-v1",
            key.ref.projectId.value,
            key.ref.instanceId.value,
            key.ref.generation.value.toString(),
            key.assetId.toString(),
            key.itemId.value.toString(),
            key.scope.wire,
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return File(directory, "$name.json")
    }

    private fun encodeBounded(timeline: NativeDraftUndoTimeline): ByteArray? {
        var states = timeline.states.toMutableList()
        var index = timeline.currentIndex
        if (states.isEmpty() || index !in states.indices) return null
        if (states.any { !validSnapshot(it) }) return null
        while (true) {
            while (states.size > NATIVE_DRAFT_UNDO_MAX_STATES) {
                if (index > 0) {
                    states.removeAt(0)
                    index--
                } else {
                    states.removeAt(states.lastIndex)
                }
            }
            val bytes = encode(timeline.copy(states = states.toList(), currentIndex = index))
            if (bytes.size <= NATIVE_DRAFT_UNDO_MAX_FILE_BYTES) return bytes
            if (states.size <= 1) return null
            val before = index
            val after = states.lastIndex - index
            when {
                before == 0 -> states.removeAt(states.lastIndex)
                after == 0 -> {
                    states.removeAt(0)
                    index--
                }
                before >= after -> {
                    states.removeAt(0)
                    index--
                }
                else -> states.removeAt(states.lastIndex)
            }
        }
    }

    private fun encode(timeline: NativeDraftUndoTimeline): ByteArray {
        val key = timeline.key
        val root = buildJsonObject {
            put("version", NATIVE_DRAFT_UNDO_VERSION)
            put("record", buildJsonObject {
                put("project", key.ref.projectId.value)
                put("instance", key.ref.instanceId.value)
                put("generation", key.ref.generation.value)
                put("asset", key.assetId)
                put("revision", key.revision)
                put("item", key.itemId.value)
                put("scope", key.scope.wire)
                put("fingerprint", key.fingerprint.sha256)
            })
            put("current", timeline.currentIndex)
            put("states", JsonArray(timeline.states.map { snapshot ->
                buildJsonObject {
                    put("text", snapshot.text)
                    put("status", snapshot.status.name)
                    put("sourceNames", JsonArray(snapshot.sourceNames.map { JsonPrimitive(it) }))
                    put("translationNames", JsonArray(snapshot.translationNames.map { JsonPrimitive(it) }))
                }
            }))
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun parse(bytes: ByteArray, expected: NativeDraftUndoKey): NativeDraftUndoLoad {
        val text = decodeUtf8(bytes) ?: return NativeDraftUndoLoad.Corrupt
        val root = try {
            STRICT_JSON.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return NativeDraftUndoLoad.Corrupt
        val version = root["version"]?.let { it as? JsonPrimitive }?.let(::strictInt)
            ?: return NativeDraftUndoLoad.Corrupt
        if (version != NATIVE_DRAFT_UNDO_VERSION) return NativeDraftUndoLoad.Unsupported
        if (root.keys != ROOT_KEYS) return NativeDraftUndoLoad.Corrupt
        val record = root["record"] as? JsonObject ?: return NativeDraftUndoLoad.Corrupt
        if (record.keys != RECORD_KEYS) return NativeDraftUndoLoad.Corrupt
        val project = record.string("project") ?: return NativeDraftUndoLoad.Corrupt
        val instance = record.string("instance") ?: return NativeDraftUndoLoad.Corrupt
        val generation = record.long("generation") ?: return NativeDraftUndoLoad.Corrupt
        val asset = record.long("asset") ?: return NativeDraftUndoLoad.Corrupt
        val revision = record.long("revision") ?: return NativeDraftUndoLoad.Corrupt
        val item = record.long("item") ?: return NativeDraftUndoLoad.Corrupt
        val scope = record.string("scope")?.let(NativeDraftUndoScope::fromWire)
            ?: return NativeDraftUndoLoad.Corrupt
        val fingerprint = record.string("fingerprint")?.let { runCatching { ObjectFingerprint(it) }.getOrNull() }
            ?: return NativeDraftUndoLoad.Corrupt
        val key = runCatching {
            NativeDraftUndoKey(
                LocalProjectRef(ProjectId(project), ProjectInstanceId(instance), RecoveryGeneration(generation)),
                asset, revision, ItemId(item), fingerprint, scope,
            )
        }.getOrNull() ?: return NativeDraftUndoLoad.Corrupt
        if (key != expected) {
            val sameIdentity = key.ref == expected.ref && key.assetId == expected.assetId &&
                key.itemId == expected.itemId && key.scope == expected.scope
            return if (sameIdentity) NativeDraftUndoLoad.Stale else NativeDraftUndoLoad.Corrupt
        }
        val statesArray = root["states"] as? JsonArray ?: return NativeDraftUndoLoad.Corrupt
        if (statesArray.size !in 1..NATIVE_DRAFT_UNDO_MAX_STATES) return NativeDraftUndoLoad.Corrupt
        val states = statesArray.map { element ->
            val state = element as? JsonObject ?: return NativeDraftUndoLoad.Corrupt
            if (state.keys != STATE_KEYS) return NativeDraftUndoLoad.Corrupt
            val body = state.string("text") ?: return NativeDraftUndoLoad.Corrupt
            if (body.length > MAX_TEXT_CHARS) return NativeDraftUndoLoad.Corrupt
            val status = state.string("status")?.let { name ->
                runCatching { TextItemStatus.valueOf(name) }.getOrNull()
            } ?: return NativeDraftUndoLoad.Corrupt
            val source = state.names("sourceNames") ?: return NativeDraftUndoLoad.Corrupt
            val translation = state.names("translationNames") ?: return NativeDraftUndoLoad.Corrupt
            NativeDraftUndoSnapshot(body, status, source, translation)
        }
        val current = root["current"]?.let { it as? JsonPrimitive }?.let(::strictInt)
            ?: return NativeDraftUndoLoad.Corrupt
        if (current !in states.indices) return NativeDraftUndoLoad.Corrupt
        return NativeDraftUndoLoad.Restored(NativeDraftUndoTimeline(key, states, current))
    }

    private fun validSnapshot(snapshot: NativeDraftUndoSnapshot): Boolean {
        if (snapshot.text.length > MAX_TEXT_CHARS) return false
        val names = snapshot.sourceNames + snapshot.translationNames
        return names.size <= MAX_STORED_NAMES * 2 &&
            names.all { it.length <= MAX_STORED_NAME_CHARS && '\u0000' !in it } &&
            names.sumOf { it.length.toLong() } <= MAX_STORED_TOTAL_NAME_CHARS
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("draft undo unavailable")
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            runCatching { atomic.failWrite(stream) }
            throw error
        }
    }

    private fun trimDirectory(except: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "json" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= NATIVE_DRAFT_UNDO_MAX_DIRECTORY_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= NATIVE_DRAFT_UNDO_MAX_DIRECTORY_BYTES) break
            if (file == except) continue
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun delete(file: File) {
        runCatching { AtomicFile(file).delete() }
        runCatching { sidecar(file, ".new").delete() }
        runCatching { sidecar(file, ".bak").delete() }
    }

    private fun sidecar(file: File, suffix: String) = File(file.path + suffix)

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > NATIVE_DRAFT_UNDO_MAX_FILE_BYTES) throw IOException("draft undo exceeds limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private companion object {
        val ROOT_KEYS = setOf("version", "record", "current", "states")
        val RECORD_KEYS = setOf("project", "instance", "generation", "asset", "revision", "item",
            "scope", "fingerprint")
        val STATE_KEYS = setOf("text", "status", "sourceNames", "translationNames")
    }
}

private fun strictInt(primitive: JsonPrimitive): Int? =
    if (primitive.isString) null else primitive.content.toIntOrNull()

private fun JsonObject.string(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}

private fun JsonObject.long(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return if (primitive.isString) null else primitive.content.toLongOrNull()
}

private fun JsonObject.names(key: String): List<String>? {
    val array = this[key] as? JsonArray ?: return null
    if (array.size > MAX_STORED_NAMES) return null
    val names = array.map { element ->
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        val name = primitive.content
        if (name.length > MAX_STORED_NAME_CHARS || '\u0000' in name) return null
        name
    }
    if (names.sumOf { it.length.toLong() } > MAX_STORED_TOTAL_NAME_CHARS) return null
    return names
}
