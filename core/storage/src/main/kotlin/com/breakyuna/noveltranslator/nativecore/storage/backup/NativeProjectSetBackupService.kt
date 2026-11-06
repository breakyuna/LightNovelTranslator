package com.breakyuna.noveltranslator.nativecore.storage.backup

import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectException
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectPort
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectSummary
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Portable backup of the whole registered project set. Each readable project is saved as an
 * independent validated `.lnt` snapshot; the manifest is self-contained and never contains
 * credentials, Agent chats, model settings, rule presets or template libraries. Restore creates
 * new local instances and never overwrites an existing project.
 *
 * A multi-project restore is intentionally sequential: an import failure leaves the projects
 * imported before it and reports the failure. It is not a cross-project database transaction.
 */
class NativeProjectSetBackupService(
    private val projects: TextProjectPort,
    private val temporaryDirectory: File,
) {
    suspend fun export(destination: () -> OutputStream): ProjectSetBackupSummary = withContext(Dispatchers.IO) {
        val registered = projects.registeredProjects(MAX_PROJECTS + 1)
        require(registered.isNotEmpty()) { "No projects to back up" }
        if (registered.size > MAX_PROJECTS) throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
        val unreadable = registered.filter { !it.readable }
        if (unreadable.isNotEmpty()) {
            throw TextProjectException(TextProjectException.Reason.STORAGE_IO)
        }
        val directory = directory()
        val files = ArrayList<File>(registered.size)
        val entries = ArrayList<ProjectSetEntry>(registered.size)
        try {
            var total = 0L
            registered.forEachIndexed { index, project ->
                currentCoroutineContext().ensureActive()
                val summary = requireNotNull(project.summary)
                val file = File.createTempFile("lnt-set-", ".lnt", directory)
                files.add(file)
                FileOutputStream(file).use { output -> projects.saveProject(project.ref) { output } }
                currentCoroutineContext().ensureActive()
                require(file.length() in 0..MAX_PROJECT_BYTES)
                total = Math.addExact(total, file.length())
                require(total <= MAX_TOTAL_BYTES)
                entries.add(ProjectSetEntry(index, summary.name, project.ref, summary.revision,
                    file.length(), sha256(file)))
            }
            val manifest = ProjectSetManifest(entries, total, System.currentTimeMillis())
            val target = destination()
            try {
                ZipOutputStream(target.buffered()).use { zip ->
                    zip.setLevel(6)
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(ProjectSetManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    entries.forEachIndexed { index, entry ->
                        zip.putNextEntry(ZipEntry(entryName(index)))
                        FileInputStream(files[index]).use { input -> input.copyTo(zip, 64 * 1024) }
                        zip.closeEntry()
                    }
                }
            } finally {
                runCatching { target.close() }
            }
            ProjectSetBackupSummary(entries.size, total, manifest.createdAtMillis)
        } finally {
            files.forEach { it.delete() }
        }
    }

    suspend fun restore(commandPrefix: CommandId, source: () -> InputStream): ProjectSetRestoreSummary =
        withContext(Dispatchers.IO) {
            require(commandPrefix.value.length <= MAX_COMMAND_PREFIX_CHARS)
            val directory = directory()
            val manifestFile = File.createTempFile("lnt-set-manifest-", ".json", directory)
            val files = ArrayList<File>()
            try {
                val entries = readArchive(source(), manifestFile, files)
                val manifest = ProjectSetManifestCodec.decode(manifestFile.readTextBounded(MAX_MANIFEST_BYTES))
                require(manifest.entries.size == entries.size && manifest.entries.size <= MAX_PROJECTS)
                require(manifest.totalBytes == manifest.entries.sumOf { it.bytes } && manifest.totalBytes <= MAX_TOTAL_BYTES)
                manifest.entries.forEachIndexed { index, entry ->
                    val file = entries[index]
                    require(entry.index == index)
                    require(file.length() == entry.bytes && file.length() in 0..MAX_PROJECT_BYTES)
                    require(sha256(file) == entry.sha256)
                }
                val restored = ArrayList<TextProjectSummary>(entries.size)
                entries.forEachIndexed { index, file ->
                    currentCoroutineContext().ensureActive()
                    val command = CommandId(commandPrefix.value + "-" + index)
                    FileInputStream(file).use { input -> restored.add(projects.importProject(command) { input }) }
                }
                ProjectSetRestoreSummary(restored.size, restored)
            } finally {
                manifestFile.delete()
                files.forEach { it.delete() }
            }
        }

    private suspend fun readArchive(input: InputStream, manifest: File, files: MutableList<File>): List<File> {
        var manifestSeen = false
        val seen = HashSet<Int>()
        val byIndex = HashMap<Int, File>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                currentCoroutineContext().ensureActive()
                when {
                    entry.name == MANIFEST_ENTRY -> {
                        require(!manifestSeen)
                        manifestSeen = true
                        copyBounded(zip, manifest, MAX_MANIFEST_BYTES.toLong())
                    }
                    entry.name.startsWith(PROJECT_PREFIX) && entry.name.endsWith(PROJECT_SUFFIX) -> {
                        val raw = entry.name.removePrefix(PROJECT_PREFIX).removeSuffix(PROJECT_SUFFIX)
                        val index = raw.toIntOrNull()
                        require(index != null && index in 0 until MAX_PROJECTS && entry.name == entryName(index) && seen.add(index))
                        val file = File.createTempFile("lnt-set-restore-", ".lnt", directory())
                        files.add(file)
                        copyBounded(zip, file, MAX_PROJECT_BYTES)
                        byIndex[index] = file
                    }
                    else -> throw IllegalArgumentException("Unexpected backup entry")
                }
                zip.closeEntry()
            }
        }
        require(manifestSeen && byIndex.isNotEmpty())
        require(byIndex.keys == (0 until byIndex.size).toSet()) { "Project entries are not contiguous" }
        return (0 until byIndex.size).map { requireNotNull(byIndex[it]) }
    }

    private suspend fun copyBounded(input: InputStream, target: File, limit: Long) {
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total = Math.addExact(total, count.toLong())
                require(total <= limit)
                output.write(buffer, 0, count)
            }
        }
    }

    private fun directory(): File {
        check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs())
        return temporaryDirectory
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextBounded(limit: Int): String {
        val output = ByteArrayOutputStream()
        FileInputStream(this).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= limit)
                output.write(buffer, 0, count)
            }
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
    }

    private fun entryName(index: Int) = PROJECT_PREFIX + index.toString().padStart(4, '0') + PROJECT_SUFFIX

    private companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val PROJECT_PREFIX = "projects/"
        const val PROJECT_SUFFIX = ".lnt"
        const val MAX_PROJECTS = 100
        const val MAX_MANIFEST_BYTES = 1024 * 1024
        const val MAX_PROJECT_BYTES = 1024L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_COMMAND_PREFIX_CHARS = 1_900
    }
}

class ProjectSetBackupSummary(val projectCount: Int, val totalBytes: Long, val createdAtMillis: Long) {
    init { require(projectCount >= 0 && totalBytes >= 0) }
    override fun toString() = "ProjectSetBackupSummary(projects=$projectCount, bytes=$totalBytes)"
}

class ProjectSetRestoreSummary(val importedCount: Int, projects: List<TextProjectSummary>) {
    val projects: List<TextProjectSummary> = java.util.Collections.unmodifiableList(ArrayList(projects))
    init { require(importedCount == this.projects.size && importedCount >= 0) }
}

private class ProjectSetEntry(
    val index: Int,
    val name: String,
    val ref: LocalProjectRef,
    val revision: Long,
    val bytes: Long,
    val sha256: String,
) {
    init {
        require(index >= 0 && name.isNotBlank() && name.length <= 200 && revision >= 0 && bytes >= 0)
        require(sha256.length == 64 && sha256.all { it in "0123456789abcdef" })
    }
}

private class ProjectSetManifest(val entries: List<ProjectSetEntry>, val totalBytes: Long, val createdAtMillis: Long)

private object ProjectSetManifestCodec {
    fun encode(manifest: ProjectSetManifest): String = buildJsonObject {
        put("format", "lnt-project-set-backup")
        put("version", 1)
        put("total_bytes", manifest.totalBytes)
        put("created_at", manifest.createdAtMillis)
        put("projects", JsonArray(manifest.entries.map { entry ->
            buildJsonObject {
                put("index", entry.index)
                put("entry", "projects/" + entry.index.toString().padStart(4, '0') + ".lnt")
                put("name", entry.name)
                put("project_id", entry.ref.projectId.value)
                put("instance_id", entry.ref.instanceId.value)
                put("generation", entry.ref.generation.value)
                put("revision", entry.revision)
                put("bytes", entry.bytes)
                put("sha256", entry.sha256)
            }
        }))
    }.toString()

    fun decode(text: String): ProjectSetManifest {
        require(text.length <= 1024 * 1024)
        val root = try { JsonObjectValue.parse(text, JsonInputLimits(1024 * 1024, 8)).json }
        catch (_: Exception) { throw IllegalArgumentException("Invalid project set manifest") }
        require(root.keys == setOf("format", "version", "total_bytes", "created_at", "projects"))
        require(string(root, "format") == "lnt-project-set-backup" && integer(root, "version") == 1L)
        val totalBytes = integer(root, "total_bytes")
        val createdAt = integer(root, "created_at")
        val array = root["projects"] as? JsonArray ?: throw IllegalArgumentException("Invalid projects")
        val entries = array.map { raw ->
            val obj = raw as? JsonObject ?: throw IllegalArgumentException("Invalid project entry")
            require(obj.keys == setOf("index", "entry", "name", "project_id", "instance_id", "generation",
                "revision", "bytes", "sha256"))
            val rawIndex = integer(obj, "index")
            require(rawIndex in 0L until 100L)
            val index = rawIndex.toInt()
            require(string(obj, "entry") == "projects/" + index.toString().padStart(4, '0') + ".lnt")
            val name = string(obj, "name")
            require(name.isNotBlank() && name.length <= 200 && name.none(Char::isISOControl))
            val sha = string(obj, "sha256")
            require(sha.length == 64 && sha.all { it in "0123456789abcdef" })
            ProjectSetEntry(index, name,
                LocalProjectRef(ProjectId(string(obj, "project_id")), ProjectInstanceId(string(obj, "instance_id")),
                    RecoveryGeneration(integer(obj, "generation"))),
                integer(obj, "revision"), integer(obj, "bytes"), sha)
        }
        return ProjectSetManifest(entries, totalBytes, createdAt)
    }

    private fun string(obj: JsonObject, key: String): String =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Invalid manifest field")

    private fun integer(obj: JsonObject, key: String): Long {
        val value = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid manifest number")
        require(value >= 0)
        return value
    }
}
