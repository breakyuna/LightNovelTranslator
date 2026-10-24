package com.breakyuna.noveltranslator.nativecore.storage.backup

import com.breakyuna.noveltranslator.nativecore.domain.*
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
import kotlinx.serialization.json.*

/**
 * Project-only portable backup. A backup contains one validated `.lnt` snapshot and
 * a manifest; credentials, Agent chats, model settings and library files are not
 * copied. Restore always creates a new project identity and never overwrites an
 * existing project.
 */
class NativeProjectBackupService(private val projects: TextProjectPort, private val temporaryDirectory: File) {
    suspend fun export(ref: LocalProjectRef, destination: () -> OutputStream): BackupSummary =
        withContext(Dispatchers.IO) {
            val project = projects.open(ref)
            val directory = directory()
            val snapshot = File.createTempFile("lnt-backup-", ".lnt", directory)
            try {
                FileOutputStream(snapshot).use { output -> projects.saveProject(ref) { output } }
                currentCoroutineContext().ensureActive()
                require(snapshot.length() in 0..MAX_PROJECT_BYTES)
                val digest = sha256(snapshot)
                val manifest = BackupManifest(project.name, ref, project.revision, snapshot.length(), digest,
                    System.currentTimeMillis())
                currentCoroutineContext().ensureActive()
                val target = destination()
                try {
                    ZipOutputStream(target.buffered()).use { zip ->
                        zip.setLevel(6)
                        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                        zip.write(BackupManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry(PROJECT_ENTRY))
                        FileInputStream(snapshot).use { input -> input.copyTo(zip, 64 * 1024) }
                        zip.closeEntry()
                    }
                } finally {
                    runCatching { target.close() }
                }
                BackupSummary(manifest.name, manifest.revision, manifest.projectBytes, manifest.sha256,
                    manifest.createdAtMillis)
            } finally {
                snapshot.delete()
            }
        }

    suspend fun restore(commandId: CommandId, source: () -> InputStream): TextProjectSummary =
        withContext(Dispatchers.IO) {
            val directory = directory()
            val manifestFile = File.createTempFile("lnt-manifest-", ".json", directory)
            val projectFile = File.createTempFile("lnt-restore-", ".lnt", directory)
            try {
                readArchive(source(), manifestFile, projectFile)
                currentCoroutineContext().ensureActive()
                val manifest = BackupManifestCodec.decode(manifestFile.readTextBounded(MAX_MANIFEST_BYTES))
                require(projectFile.length() == manifest.projectBytes && projectFile.length() in 0..MAX_PROJECT_BYTES)
                require(sha256(projectFile) == manifest.sha256)
                currentCoroutineContext().ensureActive()
                FileInputStream(projectFile).use { input ->
                    projects.importProject(commandId) { input }
                }
            } finally {
                manifestFile.delete()
                projectFile.delete()
            }
        }

    private suspend fun readArchive(input: InputStream, manifest: File, project: File) {
        var manifestSeen = false
        var projectSeen = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                currentCoroutineContext().ensureActive()
                when (entry.name) {
                    MANIFEST_ENTRY -> {
                        require(!manifestSeen)
                        manifestSeen = true
                        copyBounded(zip, manifest, MAX_MANIFEST_BYTES.toLong())
                    }
                    PROJECT_ENTRY -> {
                        require(!projectSeen)
                        projectSeen = true
                        copyBounded(zip, project, MAX_PROJECT_BYTES)
                    }
                    else -> throw IllegalArgumentException("Unexpected backup entry")
                }
                zip.closeEntry()
            }
        }
        require(manifestSeen && projectSeen)
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
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
    }

    private companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val PROJECT_ENTRY = "project.lnt"
        const val MAX_MANIFEST_BYTES = 1024 * 1024
        const val MAX_PROJECT_BYTES = 1024L * 1024 * 1024
    }
}

data class BackupSummary(
    val name: String,
    val revision: Long,
    val projectBytes: Long,
    val sha256: String,
    val createdAtMillis: Long,
)

private data class BackupManifest(
    val name: String,
    val ref: LocalProjectRef,
    val revision: Long,
    val projectBytes: Long,
    val sha256: String,
    val createdAtMillis: Long,
)

private object BackupManifestCodec {
    fun encode(manifest: BackupManifest): String = buildJsonObject {
        put("format", "lnt-project-backup")
        put("version", 1)
        put("name", manifest.name)
        put("project_id", manifest.ref.projectId.value)
        put("instance_id", manifest.ref.instanceId.value)
        put("generation", manifest.ref.generation.value)
        put("revision", manifest.revision)
        put("project_bytes", manifest.projectBytes)
        put("sha256", manifest.sha256)
        put("created_at", manifest.createdAtMillis)
    }.toString()

    fun decode(text: String): BackupManifest {
        val root = JsonObjectValue.parse(text, JsonInputLimits(1024 * 1024, 8)).json
        require(root.keys == setOf("format", "version", "name", "project_id", "instance_id", "generation",
            "revision", "project_bytes", "sha256", "created_at"))
        require(root["format"] == JsonPrimitive("lnt-project-backup") && root["version"] == JsonPrimitive(1))
        fun string(key: String): String {
            val value = root.getValue(key) as? JsonPrimitive ?: throw IllegalArgumentException("Backup field")
            require(value.isString)
            return value.content
        }
        fun long(key: String, minimum: Long): Long {
            val value = root.getValue(key) as? JsonPrimitive ?: throw IllegalArgumentException("Backup field")
            require(!value.isString)
            val number = value.content.toLongOrNull() ?: throw IllegalArgumentException("Backup number")
            require(number >= minimum)
            return number
        }
        val name = string("name")
        require(name.isNotBlank() && name.length <= 200 && name.none(Char::isISOControl))
        val projectId = ProjectId(string("project_id"))
        val instanceId = ProjectInstanceId(string("instance_id"))
        val generation = RecoveryGeneration(long("generation", 1))
        val sha = string("sha256")
        require(sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' })
        return BackupManifest(name, LocalProjectRef(projectId, instanceId, generation), long("revision", 0),
            long("project_bytes", 0), sha, long("created_at", 0))
    }
}
