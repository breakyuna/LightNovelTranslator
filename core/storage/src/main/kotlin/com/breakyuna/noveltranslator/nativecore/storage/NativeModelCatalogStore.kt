package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelCatalog
import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelCatalogCodec
import com.breakyuna.noveltranslator.nativecore.domain.ModelCatalogKey
import com.breakyuna.noveltranslator.nativecore.domain.ModelCatalogSnapshot
import com.breakyuna.noveltranslator.nativecore.domain.ModelCatalogSnapshotStore
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelEndpoint
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProtocol
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

/**
 * Atomic, versioned catalog snapshots keyed by normalized endpoint plus protocol.
 * Saving an empty snapshot is a successful override and replaces the previous list;
 * clearing a key or a damaged file is never inferred from a failed request. Any
 * corrupt, tampered or over-bound file throws [InvalidModelCatalog] and is not
 * overwritten by load/save/clear.
 *
 * This file contains no credential, header or request body.
 */
class NativeModelCatalogStore(context: Context) : ModelCatalogSnapshotStore {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
    private val mutex = libraryMutex

    override suspend fun load(endpoint: String, protocol: NativeModelProtocol): ModelCatalogSnapshot? =
        withContext(Dispatchers.IO) {
            val snapshotKey = catalogKey(endpoint, protocol)
            mutex.withLock { readAll().firstOrNull { it.key == snapshotKey } }
        }

    /** Whole-list replacement for one key, including an explicit empty descriptor list. */
    override suspend fun save(snapshot: ModelCatalogSnapshot): ModelCatalogSnapshot =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                val next = readAll().filterNot { it.key == snapshot.key } + snapshot
                writeAll(next)
                snapshot
            }
        }

    suspend fun clear(endpoint: String, protocol: NativeModelProtocol) {
        withContext(Dispatchers.IO) {
            val snapshotKey = catalogKey(endpoint, protocol)
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                val current = readAll()
                val next = current.filterNot { it.key == snapshotKey }
                if (next.size == current.size) return@withLock
                if (next.isEmpty()) {
                    file.delete()
                } else {
                    writeAll(next)
                }
                Unit
            }
        }
    }

    /** Remove every saved snapshot. Corrupt state is rejected rather than erased. */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                readAll()
                file.delete()
                Unit
            }
        }
    }

    /** Alias for [clearAll] used by callers that reset the whole local catalog. */
    suspend fun clear() {
        clearAll()
    }

    /**
     * Stored endpoints are already operation-free bases. [NativeModelEndpoint.base]
     * validates and trims only a trailing slash; it never strips a legitimate reverse
     * proxy path such as `/api/models`.
     */
    private fun catalogKey(endpoint: String, protocol: NativeModelProtocol): ModelCatalogKey {
        val normalized = try {
            NativeModelEndpoint.base(endpoint)
        } catch (_: InvalidModelSettings) {
            throw InvalidModelCatalog()
        }
        return ModelCatalogKey(normalized, protocol)
    }

    private suspend fun readAll(): List<ModelCatalogSnapshot> {
        val input = try {
            file.openRead()
        } catch (missing: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw InvalidModelCatalog()
            return emptyList()
        }
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > MAX_BYTES.toLong()) {
                    throw InvalidModelCatalog()
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw InvalidModelCatalog()
        }
        return try {
            ModelCatalogCodec.decode(text)
        } catch (invalid: InvalidModelCatalog) {
            throw invalid
        } catch (_: Exception) {
            throw InvalidModelCatalog()
        }
    }

    private suspend fun writeAll(snapshots: List<ModelCatalogSnapshot>) {
        val bytes = try {
            TextProjectFingerprint.utf8(ModelCatalogCodec.encode(snapshots))
        } catch (_: Exception) {
            throw InvalidModelCatalog()
        }
        if (bytes.size > MAX_BYTES) throw InvalidModelCatalog()
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

    private companion object {
        val libraryMutex = Mutex()
        const val FILE_NAME = "native-model-catalog.json"
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}
