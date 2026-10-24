package com.breakyuna.noveltranslator.nativecore.workspace

import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

object WorkspaceSkillLimits {
    const val MAX_SKILLS: Int = 512
    const val MAX_METADATA_BYTES: Int = 64 * 1024
    const val MAX_CONTENT_BYTES: Int = 256 * 1024
    const val MAX_TOTAL_BYTES: Long = 32L * 1024 * 1024
    const val MAX_LANGUAGES: Int = 32
    const val MAX_TAGS: Int = 64
}

/**
 * One read-only built-in skill package. The resource source owns extraction from APK assets or
 * a shipped directory; the store owns validation and user overrides.
 */
class WorkspaceSkillResource(
    val id: WorkspaceSkillId,
    metadata: ByteArray,
    content: ByteArray,
) {
    private val metadataStorage: ByteArray = metadata.copyOf()
    private val contentStorage: ByteArray = content.copyOf()

    init {
        require(metadataStorage.isNotEmpty()) { "Empty skill metadata" }
        require(metadataStorage.size <= WorkspaceSkillLimits.MAX_METADATA_BYTES) { "Skill metadata too large" }
        require(contentStorage.size <= WorkspaceSkillLimits.MAX_CONTENT_BYTES) { "Skill content too large" }
    }

    val metadataBytes: ByteArray get() = metadataStorage.copyOf()
    val contentBytes: ByteArray get() = contentStorage.copyOf()

    override fun toString(): String =
        "WorkspaceSkillResource(id=${id.value}, metadata=${metadataStorage.size}, content=${contentStorage.size})"
}

/** Optional built-in skill root. An absent root yields an empty list, never invented skills. */
interface WorkspaceSkillResourceSource {
    suspend fun list(): List<WorkspaceSkillResource>
}

/**
 * Loads built-in skills from `<root>/<id>/skill.json` plus `<root>/<id>/SKILL.md`. The root is
 * optional; a missing directory is treated as "no built-ins". Names are never inferred from
 * anything except real directories, and canonicalization prevents symlinked escapes.
 */
class FileWorkspaceSkillResourceSource(
    private val rootDirectory: File,
    private val policy: PathPolicy = PathPolicy.DEFAULT,
    private val canonicalizer: (File) -> String = { it.canonicalPath },
) : WorkspaceSkillResourceSource {
    override suspend fun list(): List<WorkspaceSkillResource> {
        if (!rootDirectory.exists()) return emptyList()
        if (!rootDirectory.isDirectory) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED)
        }
        val root = WorkspaceRoot.open(rootDirectory, canonicalizer)
        val entries = root.directory.listFiles()
            ?: throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED)
        val candidates = entries
            .filter { it.isDirectory && File(it, METADATA_FILE).isFile }
            .sortedBy { it.name }
        if (candidates.size > WorkspaceSkillLimits.MAX_SKILLS) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE)
        }
        val result = ArrayList<WorkspaceSkillResource>(candidates.size)
        var total = 0L
        for (directory in candidates) {
            val id = resourceId(directory.name)
            val metadataFile = try {
                root.resolve(WorkspacePath(id.value + "/" + METADATA_FILE))
            } catch (failure: InvalidWorkspacePath) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id)
            }
            val contentFile = try {
                root.resolve(WorkspacePath(id.value + "/" + CONTENT_FILE))
            } catch (failure: InvalidWorkspacePath) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id)
            }
            if (!contentFile.isFile) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
            }
            val metadata = readBounded(metadataFile, WorkspaceSkillLimits.MAX_METADATA_BYTES, id)
            val content = readBounded(contentFile, WorkspaceSkillLimits.MAX_CONTENT_BYTES, id)
            total += metadata.size.toLong() + content.size.toLong()
            if (total > WorkspaceSkillLimits.MAX_TOTAL_BYTES) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
            }
            result += WorkspaceSkillResource(id, metadata, content)
        }
        return result
    }

    private fun resourceId(name: String): WorkspaceSkillId {
        val id = try {
            WorkspaceSkillId(name)
        } catch (failure: IllegalArgumentException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_ID)
        }
        validateSkillPath(id, policy)
        return id
    }

    private fun readBounded(file: File, maxBytes: Int, id: WorkspaceSkillId): ByteArray {
        if (!file.isFile) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        if (file.length() > maxBytes) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
        }
        return try {
            FileInputStream(file).use { readSkillBytesBounded(it, maxBytes, id) }
        } catch (failure: WorkspaceSkillStoreException) {
            throw failure
        } catch (failure: IOException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, id)
        }
    }
}

/**
 * Reads optional `assets/workspace-skills/<id>/skill.json` and `SKILL.md`. This is the Android
 * wiring for built-in skills; the catalog is empty when the asset directory is absent. Tests
 * use [FileWorkspaceSkillResourceSource] instead of instantiating an AssetManager.
 */
class AndroidAssetWorkspaceSkillResourceSource(
    private val assets: AssetManager,
    root: String = DEFAULT_ROOT,
) : WorkspaceSkillResourceSource {
    private val root: String = validateAssetRoot(root)

    override suspend fun list(): List<WorkspaceSkillResource> {
        val children = try {
            assets.list(root)?.toList().orEmpty()
        } catch (failure: IOException) {
            // An absent optional asset directory is the normal "no built-ins" case.
            return emptyList()
        }
        val candidates = children.sorted()
        if (candidates.size > WorkspaceSkillLimits.MAX_SKILLS) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE)
        }
        val result = ArrayList<WorkspaceSkillResource>(candidates.size)
        var total = 0L
        for (name in candidates) {
            val id = try {
                WorkspaceSkillId(name)
            } catch (failure: IllegalArgumentException) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_ID)
            }
            validateSkillPath(id, PathPolicy.DEFAULT)
            val directory = root + "/" + name
            val members = try {
                assets.list(directory)?.toList().orEmpty()
            } catch (failure: IOException) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, id)
            }
            if (METADATA_FILE !in members) continue
            val metadata = readAsset(directory + "/" + METADATA_FILE, WorkspaceSkillLimits.MAX_METADATA_BYTES, id)
            val content = readAsset(directory + "/" + CONTENT_FILE, WorkspaceSkillLimits.MAX_CONTENT_BYTES, id)
            total += metadata.size.toLong() + content.size.toLong()
            if (total > WorkspaceSkillLimits.MAX_TOTAL_BYTES) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
            }
            result += WorkspaceSkillResource(id, metadata, content)
        }
        return result
    }

    private fun readAsset(path: String, maxBytes: Int, id: WorkspaceSkillId): ByteArray {
        val stream = try {
            assets.open(path)
        } catch (failure: IOException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        return stream.use { readSkillBytesBounded(it, maxBytes, id) }
    }

    companion object {
        const val DEFAULT_ROOT: String = "workspace-skills"

        private fun validateAssetRoot(raw: String): String {
            require(raw.isNotBlank() && raw.length <= 256)
            require(!raw.startsWith('/') && !raw.endsWith('/'))
            require('\\' !in raw && raw.none { it.isISOControl() })
            require(raw.split('/').none { it.isEmpty() || it == "." || it == ".." })
            return raw
        }
    }
}

internal const val METADATA_FILE: String = "skill.json"
internal const val CONTENT_FILE: String = "SKILL.md"

/** Validates one skill id as a single path segment under either skill root. */
internal fun validateSkillPath(id: WorkspaceSkillId, policy: PathPolicy) {
    val raw = id.value
    if (raw == "." || raw == "..") {
        throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_ID, id)
    }
    val parsed = try {
        policy.parse(raw)
    } catch (failure: InvalidWorkspacePath) {
        throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_ID, id)
    }
    if (parsed.value != raw || '/' in parsed.value || '\\' in parsed.value) {
        throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_ID, id)
    }
}

internal fun readSkillBytesBounded(
    input: InputStream,
    maxBytes: Int,
    id: WorkspaceSkillId?,
): ByteArray {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
