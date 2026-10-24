package com.breakyuna.noveltranslator.nativecore.storage.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeApplicationBackupServiceTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    private fun service(
        port: AppBackupDataPort,
        maxEntryBytes: Long = 4_096,
        maxTotalBytes: Long = 16_384,
        maxEntries: Int = 32,
    ) = NativeApplicationBackupService(
        port = port,
        temporaryDirectory = temporary.newFolder(),
        limits = AppBackupLimits(
            maxEntries = maxEntries,
            maxEntryBytes = maxEntryBytes,
            maxTotalBytes = maxTotalBytes,
            maxManifestBytes = 8_192,
        ),
        clock = { 1_700_000_000_000L },
    )

    @Test fun roundTripRestoresEveryNonSecretEntry() = runBlocking {
        val entries = sampleEntries()
        val source = FakePort(entries)
        val exported = ByteArrayOutputStream()
        val summary = service(source).export { exported }
        assertEquals(entries.size, summary.entryCount)
        assertTrue(summary.totalBytes > 0)
        assertEquals(1, summary.schemaVersion)

        val archive = unzip(exported.toByteArray())
        val manifest = archive.getValue("manifest.json").toString(Charsets.UTF_8)
        assertTrue(manifest.contains("\"credentials_excluded\":true"))
        assertTrue(manifest.contains("\"execution_authorization_included\":false"))
        assertTrue(manifest.contains("\"project_instances_included\":false"))
        assertTrue(manifest.contains("\"schema_version\":1"))

        val target = FakePort()
        val restored = service(target).restore { ByteArrayInputStream(exported.toByteArray()) }
        assertEquals(entries.size, restored.entryCount)
        assertEquals(summary.totalBytes, restored.totalBytes)
        assertEquals(entries.associate { it.path to it.contentText() }, target.liveText())
    }

    @Test fun modelSettingsWithCredentialContentAreRejectedBeforeWriting() = runBlocking {
        for (text in listOf(
            "{\"api_key\":\"sk-abcdefghijklmnop\"}",
            "{\"oauth_token\":\"opaque-token-value\"}",
        )) {
            val destination = ByteArrayOutputStream()
            try {
                service(FakePort(listOf(entry(AppBackupPaths.MODEL_SETTINGS, AppBackupEntryKind.MODEL_SETTINGS, text))))
                    .export { destination }
                fail("Accepted model settings with credential content")
            } catch (_: IllegalArgumentException) {
            }
            assertEquals(0, destination.size())
        }
    }

    @Test fun credentialOrTraversalEntriesAreRejectedBeforeWriting() = runBlocking {
        for ((path, kind) in listOf(
            AppBackupPaths.agentChat("oauth-token.json") to AppBackupEntryKind.AGENT_CHAT_HISTORY,
            AppBackupPaths.rulePreset("client_secret.json") to AppBackupEntryKind.RULE_PRESET,
            AppBackupPaths.agentChat("../chat.json") to AppBackupEntryKind.AGENT_CHAT_HISTORY,
        )) {
            val destination = ByteArrayOutputStream()
            try {
                service(FakePort(listOf(entry(path, kind, "{}")))).export { destination }
                fail("Accepted unsafe backup entry $path")
            } catch (_: IllegalArgumentException) {
            }
            assertEquals(0, destination.size())
        }
    }

    @Test fun tamperedManifestAndPathTraversalAreRejectedWithoutTouchingTarget() = runBlocking {
        val exported = ByteArrayOutputStream()
        service(FakePort(sampleEntries())).export { exported }
        val archive = unzip(exported.toByteArray())
        val manifest = archive.getValue("manifest.json").toString(Charsets.UTF_8)
        val modelPath = AppBackupPaths.MODEL_SETTINGS
        val tampered = listOf(
            manifest.replaceFirst(
                Regex("\"sha256\":\"[0-9a-f]{64}\""),
                "\"sha256\":\"" + "0".repeat(64) + "\"",
            ),
            manifest.replace("\"schema_version\":1", "\"schema_version\":2"),
            manifest.replace("\"path\":\"$modelPath\"", "\"path\":\"../evil.json\""),
        )
        for (candidate in tampered) {
            val target = FakePort()
            target.live["existing"] = "keep".toByteArray(Charsets.UTF_8)
            val candidateArchive = LinkedHashMap(archive)
            candidateArchive["manifest.json"] = candidate.toByteArray(Charsets.UTF_8)
            try {
                service(target).restore { ByteArrayInputStream(zip(candidateArchive)) }
                fail("Accepted a tampered manifest")
            } catch (_: Exception) {
            }
            assertEquals(0, target.stageCalls)
            assertEquals(mapOf("existing" to "keep"), target.liveText())
        }

        val target = FakePort()
        target.live["existing"] = "keep".toByteArray(Charsets.UTF_8)
        val traversing = LinkedHashMap(archive)
        traversing["data/../evil.json"] = "{}".toByteArray(Charsets.UTF_8)
        try {
            service(target).restore { ByteArrayInputStream(zip(traversing)) }
            fail("Accepted a traversing ZIP entry")
        } catch (_: Exception) {
        }
        assertEquals(0, target.stageCalls)
        assertEquals(mapOf("existing" to "keep"), target.liveText())
    }

    @Test fun overBudgetExportAndRestoreAreRejected() = runBlocking {
        val oversized = FakePort(listOf(
            entry(AppBackupPaths.MODEL_SETTINGS, AppBackupEntryKind.MODEL_SETTINGS, "x".repeat(128)),
        ))
        try {
            service(oversized, maxEntryBytes = 64, maxTotalBytes = 128).export { ByteArrayOutputStream() }
            fail("Accepted an entry over the per-entry budget")
        } catch (_: IllegalArgumentException) {
        }

        val exported = ByteArrayOutputStream()
        service(FakePort(sampleEntries())).export { exported }
        val target = FakePort()
        target.live["existing"] = "keep".toByteArray(Charsets.UTF_8)
        try {
            service(target, maxEntryBytes = 32, maxTotalBytes = 48)
                .restore { ByteArrayInputStream(exported.toByteArray()) }
            fail("Accepted a backup over the restore budget")
        } catch (_: Exception) {
        }
        assertEquals(0, target.stageCalls)
        assertEquals(mapOf("existing" to "keep"), target.liveText())
    }

    @Test fun failedStagingLeavesLiveTargetUntouched() = runBlocking {
        val exported = ByteArrayOutputStream()
        service(FakePort(sampleEntries())).export { exported }
        val target = FakePort(failStage = true)
        target.live["existing"] = "keep".toByteArray(Charsets.UTF_8)
        try {
            service(target).restore { ByteArrayInputStream(exported.toByteArray()) }
            fail("Publish path ran after a staging failure")
        } catch (_: IOException) {
        }
        assertEquals(1, target.stageCalls)
        assertTrue(target.staged.isEmpty())
        assertEquals(mapOf("existing" to "keep"), target.liveText())
    }

    private fun sampleEntries(): List<FakeEntry> = listOf(
        FakeEntry(
            AppBackupPaths.MODEL_SETTINGS,
            AppBackupEntryKind.MODEL_SETTINGS,
            "{\"endpoint\":\"http://127.0.0.1:1234/v1\",\"model\":\"local\",\"protocol\":\"SAKURA_LLM\"}",
        ),
        FakeEntry(
            AppBackupPaths.PRICE_TABLE,
            AppBackupEntryKind.PRICE_TABLE,
            "{\"currency\":\"USD\",\"version\":1,\"entries\":[]}",
        ),
        FakeEntry(
            AppBackupPaths.rulePreset("a".repeat(64) + ".json"),
            AppBackupEntryKind.RULE_PRESET,
            "{\"id\":\"preset\",\"name\":\"Preset\"}",
        ),
        FakeEntry(
            AppBackupPaths.PROMPT_TEMPLATES,
            AppBackupEntryKind.PROMPT_TEMPLATES,
            "{\"templates\":[]}",
        ),
        FakeEntry(
            AppBackupPaths.agentChat("instance/chat.json"),
            AppBackupEntryKind.AGENT_CHAT_HISTORY,
            "{\"messages\":[]}",
        ),
    )

    private fun entry(path: String, kind: AppBackupEntryKind, text: String) = FakeEntry(path, kind, text)

    private data class FakeEntry(val path: String, val kind: AppBackupEntryKind, val text: String) {
        fun contentText(): String = text
    }

    private class FakePort(
        private val entries: List<FakeEntry> = emptyList(),
        private val failStage: Boolean = false,
    ) : AppBackupDataPort {
        val live = linkedMapOf<String, ByteArray>()
        var staged: Map<String, String> = emptyMap()
        var stageCalls = 0

        fun liveText(): Map<String, String> = live.mapValues { (_, value) -> value.toString(Charsets.UTF_8) }

        override suspend fun exportEntries(): List<AppBackupEntry> = entries.map { entry ->
            AppBackupEntry.bytes(entry.path, entry.kind, entry.text.toByteArray(Charsets.UTF_8))
        }

        override suspend fun beginRestore(entries: List<AppBackupStagedEntry>): AppBackupRestoreSession {
            stageCalls++
            if (failStage) throw IOException("staging failed")
            val stagedCopy = entries.associate { entry ->
                entry.path to entry.open().use { input -> input.readBytes().toString(Charsets.UTF_8) }
            }
            staged = stagedCopy
            return object : AppBackupRestoreSession {
                override suspend fun publish() {
                    live.clear()
                    stagedCopy.forEach { (path, text) -> live[path] = text.toByteArray(Charsets.UTF_8) }
                }

                override suspend fun discard() {
                    staged = emptyMap()
                }
            }
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

}
