package com.breakyuna.noveltranslator.nativecore.storage.backup

import com.breakyuna.noveltranslator.nativecore.domain.CurrencyCode
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.PriceTable
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.StoredRunState
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeApplicationFullArchiveBackupTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    private fun service(
        port: AppBackupDataPort = MemorySettingsPort(),
        full: FullArchiveDataPort? = null,
        maxEntryBytes: Long = 65_536,
        maxTotalBytes: Long = 262_144,
        maxEntries: Int = 64,
    ) = NativeApplicationBackupService(
        port = port,
        temporaryDirectory = temporary.newFolder(),
        limits = AppBackupLimits(
            maxEntries = maxEntries,
            maxEntryBytes = maxEntryBytes,
            maxTotalBytes = maxTotalBytes,
            maxManifestBytes = 32_768,
        ),
        clock = { 1_700_000_000_000L },
        fullArchivePort = full,
    )

    @Test fun schemaOneSettingsArchiveStillRestores() = runBlocking {
        val source = MemorySettingsPort()
        source.live[AppBackupPaths.MODEL_SETTINGS] =
            "{\"endpoint\":\"http://127.0.0.1:1234/v1\",\"model\":\"local\"}"
                .toByteArray(Charsets.UTF_8)
        val exported = ByteArrayOutputStream()
        val summary = service(source).export { exported }
        assertEquals(1, summary.schemaVersion)
        assertEquals(AppBackupScope.SETTINGS, summary.backupScope)

        val archive = unzip(exported.toByteArray())
        val manifest = archive.getValue("manifest.json").toString(Charsets.UTF_8)
        assertTrue(manifest.contains("\"schema_version\":1"))
        assertTrue(manifest.contains("\"credentials_excluded\":true"))
        assertTrue(manifest.contains("\"execution_authorization_included\":false"))

        val target = MemorySettingsPort()
        val restored = service(target).restore { ByteArrayInputStream(exported.toByteArray()) }
        assertEquals(1, restored.schemaVersion)
        assertEquals(AppBackupScope.SETTINGS, restored.backupScope)
        assertEquals(source.liveText(), target.liveText())
    }

    @Test fun schemaTwoSettingsScopeRestoresThroughTheLegacyPort() = runBlocking {
        val content = "{\"endpoint\":\"http://127.0.0.1:1234/v1\",\"model\":\"local\"}"
            .toByteArray(Charsets.UTF_8)
        val manifest = AppBackupManifestV2(
            scope = AppBackupScope.SETTINGS,
            appVersion = "1.2.3",
            appSchemaVersion = 18,
            queueSchemaVersion = 2,
            snapshotId = SNAPSHOT_ID,
            cutAtMillis = 1_699_999_000_000L,
            createdAtMillis = 1_700_000_000_000L,
            quiesced = true,
            activeRun = null,
            queueRunning = false,
            totalBytes = content.size.toLong(),
            projectInstances = emptyList(),
            entries = listOf(
                AppBackupManifestV2Entry(
                    path = AppBackupPaths.MODEL_SETTINGS,
                    kind = FullArchiveEntryKind.MODEL_SETTINGS,
                    bytes = content.size.toLong(),
                    sha256 = digest(content),
                ),
            ),
        )
        val archive = zip(
            linkedMapOf(
                "manifest.json" to AppBackupManifestV2Codec.encode(manifest).toByteArray(Charsets.UTF_8),
                "data/" + AppBackupPaths.MODEL_SETTINGS to content,
            ),
        )
        val target = MemorySettingsPort()
        val restored = service(target).restore { ByteArrayInputStream(archive) }
        assertEquals(2, restored.schemaVersion)
        assertEquals(AppBackupScope.SETTINGS, restored.backupScope)
        assertEquals(
            content.toString(Charsets.UTF_8),
            target.liveText().getValue(AppBackupPaths.MODEL_SETTINGS),
        )
    }

    @Test fun fullArchiveManifestRecordsCutScopeAndProjectFacts() = runBlocking {
        val source = FakeFullPort(fullExport())
        val exported = ByteArrayOutputStream()
        val summary = service(full = source).exportFullArchive { exported }
        assertEquals(2, summary.schemaVersion)
        assertEquals(AppBackupScope.FULL_ARCHIVE, summary.backupScope)
        assertEquals(SNAPSHOT_ID, summary.snapshotId)

        val archive = unzip(exported.toByteArray())
        val manifest = archive.getValue("manifest.json").toString(Charsets.UTF_8)
        assertTrue(manifest.contains("\"schema_version\":2"))
        assertTrue(manifest.contains("\"backup_scope\":\"FULL_ARCHIVE\""))
        assertTrue(manifest.contains("\"app_version\":\"1.2.3\""))
        assertTrue(manifest.contains("\"app_schema_version\":18"))
        assertTrue(manifest.contains("\"queue_schema_version\":2"))
        assertTrue(manifest.contains("\"snapshot_id\":\"$SNAPSHOT_ID\""))
        assertTrue(manifest.contains("\"quiesced\":true"))
        assertTrue(manifest.contains("\"active_run\":null"))
        assertTrue(manifest.contains("\"queue_running\":false"))
        assertTrue(manifest.contains("\"credentials_excluded\":true"))
        assertTrue(manifest.contains("\"execution_authorization_included\":false"))
        assertTrue(manifest.contains("\"pending_approvals_included\":false"))
        assertTrue(manifest.contains("\"runnable_queue_included\":false"))
        assertTrue(manifest.contains("\"old_ref\""))
        assertTrue(manifest.contains("\"content_class\":\"PROJECT_SNAPSHOT\""))
        assertTrue(manifest.contains("\"consistent_cut\":true"))
        assertTrue(archive.containsKey("data/" + FullArchivePaths.projectSnapshot("0000.lnt")))
        assertTrue(archive.containsKey("data/" + FullArchivePaths.agentSession("session-1.json")))
        assertTrue(archive.containsKey("data/" + FullArchivePaths.approvalAudit("approvals.json")))
        assertTrue(archive.containsKey("data/" + FullArchivePaths.queueHistory("queues.json")))

        val target = FakeFullPort(fullExport())
        target.live["prior"] = "keep"
        val restored = service(full = target).restore { ByteArrayInputStream(exported.toByteArray()) }
        assertEquals(2, restored.schemaVersion)
        assertEquals(AppBackupScope.FULL_ARCHIVE, restored.backupScope)
        assertEquals(1, target.beginCalls)
        assertEquals(1, target.publishCalls)
        assertEquals(0, target.discardCalls)
        val plan = requireNotNull(target.plan)
        assertTrue(plan.approvalsAuditOnly)
        assertTrue(plan.clearPendingApprovals)
        assertTrue(plan.pauseUnfinishedRuns)
        assertTrue(plan.pauseQueuedTasks)
        assertTrue(plan.clearExecutionTokens)
        assertFalse(plan.autoStartRuns)
        assertEquals(
            listOf(AppBackupRestoreReceiptState.PREPARED, AppBackupRestoreReceiptState.COMMITTED),
            target.journal.writes.map { it.state },
        )
        assertEquals(SNAPSHOT_ID, target.journal.current?.snapshotId)
        assertEquals("keep", target.live["prior"])
    }

    @Test fun fullArchiveRejectsTraversalAndDigestMismatchBeforeStaging() = runBlocking {
        val exported = ByteArrayOutputStream()
        service(full = FakeFullPort(fullExport())).exportFullArchive { exported }
        val original = unzip(exported.toByteArray())
        val manifest = original.getValue("manifest.json").toString(Charsets.UTF_8)

        val tamperedDigest = manifest.replaceFirst(
            Regex("(\"path\":\"${Regex.escape(AppBackupPaths.MODEL_SETTINGS)}\".*?\"sha256\":\")[0-9a-f]{64}"),
            "$" + "1" + "0".repeat(64),
        )
        assertTrue(tamperedDigest != manifest)
        val digestArchive = LinkedHashMap(original)
        digestArchive["manifest.json"] = tamperedDigest.toByteArray(Charsets.UTF_8)
        val digestTarget = FakeFullPort(fullExport())
        digestTarget.live["existing"] = "keep"
        try {
            service(full = digestTarget).restore { ByteArrayInputStream(zip(digestArchive)) }
            fail("Accepted a mismatched entry digest")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, digestTarget.beginCalls)
        assertEquals(mapOf("existing" to "keep"), digestTarget.live)

        val traversing = LinkedHashMap(original)
        traversing["data/../evil.json"] = "{}".toByteArray(Charsets.UTF_8)
        val traversalTarget = FakeFullPort(fullExport())
        traversalTarget.live["existing"] = "keep"
        try {
            service(full = traversalTarget).restore { ByteArrayInputStream(zip(traversing)) }
            fail("Accepted a traversing archive entry")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, traversalTarget.beginCalls)
        assertEquals(mapOf("existing" to "keep"), traversalTarget.live)
    }

    @Test fun failedPublishDiscardsNewDataAndMarksReceiptFailed() = runBlocking {
        val exported = ByteArrayOutputStream()
        service(full = FakeFullPort(fullExport())).exportFullArchive { exported }
        val target = FakeFullPort(fullExport(), failPublishAfter = 1)
        target.live["legacy"] = "keep"
        try {
            service(full = target).restore { ByteArrayInputStream(exported.toByteArray()) }
            fail("Published a partially failed restore")
        } catch (_: IOException) {
        }
        assertEquals(1, target.beginCalls)
        assertEquals(1, target.publishCalls)
        assertEquals(1, target.discardCalls)
        assertEquals(mapOf("legacy" to "keep"), target.live)
        assertEquals(
            listOf(AppBackupRestoreReceiptState.PREPARED, AppBackupRestoreReceiptState.FAILED),
            target.journal.writes.map { it.state },
        )
        assertEquals("FAILED", target.journal.current?.state?.name)
    }

    @Test fun restoreRequiresPortDeclaredIdleAndNeverReenablesExecution() = runBlocking {
        val exported = ByteArrayOutputStream()
        service(full = FakeFullPort(fullExport())).exportFullArchive { exported }

        val busy = FakeFullPort(
            fullExport(),
            state = FullArchiveRestoreState(idle = false, activeRun = "run-1", runningRuns = 1),
        )
        busy.live["existing"] = "keep"
        try {
            service(full = busy).restore { ByteArrayInputStream(exported.toByteArray()) }
            fail("Restored while a run was active")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, busy.beginCalls)
        assertEquals(mapOf("existing" to "keep"), busy.live)
        assertTrue(busy.journal.writes.isEmpty())

        val idleWithHistory = FakeFullPort(
            fullExport(),
            state = FullArchiveRestoreState(
                idle = true,
                waitingApprovalRuns = 2,
                pendingApprovals = 3,
                queuedTasks = 4,
                executionTokens = 5,
            ),
        )
        service(full = idleWithHistory).restore { ByteArrayInputStream(exported.toByteArray()) }
        val plan = requireNotNull(idleWithHistory.plan)
        assertTrue(plan.clearPendingApprovals && plan.clearExecutionTokens)
        assertEquals(StoredRunState.INTERRUPTED, AppArchiveRestorePolicy.runState(StoredRunState.RUNNING))
        assertEquals(StoredRunState.INTERRUPTED, AppArchiveRestorePolicy.runState(StoredRunState.WAITING_APPROVAL))
        assertEquals(StoredRunState.PAUSED, AppArchiveRestorePolicy.runState(StoredRunState.PAUSED))
        assertEquals(TranslationTaskQueueState.PAUSED,
            AppArchiveRestorePolicy.queueState(TranslationTaskQueueState.QUEUED))
        assertEquals(TranslationTaskQueueState.PAUSED,
            AppArchiveRestorePolicy.queueState(TranslationTaskQueueState.RUNNING))
        assertEquals(0, AppArchiveRestorePolicy.restoredExecutionTokens())
        assertTrue(AppArchiveRestorePolicy.APPROVALS_ARE_HISTORICAL)
    }

    @Test fun credentialFieldsAndCredentialPathsAreRejected() = runBlocking {
        val credentials = listOf(
            fullEntry(
                AppBackupPaths.MODEL_SETTINGS,
                FullArchiveEntryKind.MODEL_SETTINGS,
                "{\"api_key\":\"sk-abcdefghijklmnop\"}",
            ),
        )
        val destination = ByteArrayOutputStream()
        try {
            service(full = FakeFullPort(fullExport(entries = credentials))).exportFullArchive { destination }
            fail("Exported model settings with credentials")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, destination.size())

        val unsafePath = listOf(
            fullEntry(
                FullArchivePaths.agentSession("oauth-token.json"),
                FullArchiveEntryKind.AGENT_SESSION,
                "{\"messages\":[]}",
            ),
        )
        val second = ByteArrayOutputStream()
        try {
            service(full = FakeFullPort(fullExport(entries = unsafePath))).exportFullArchive { second }
            fail("Exported a credential path")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, second.size())
    }

    @Test fun missingHistoricalPriceVersionIsUnknownInsteadOfZero() = runBlocking {
        val receipt = ArchiveUsageReceipt(
            id = "receipt-1",
            priceVersion = 9,
            priceFingerprint = null,
            knownCost = "1.250000000000",
        )
        val missing = ArchivePriceResolver.resolve(receipt, emptyList())
        assertEquals(ArchiveUsageCostStatus.UNKNOWN, missing.status)
        assertNull(missing.cost)
        assertEquals(ArchiveUsageCostReason.MISSING_PRICE_VERSION, missing.reason)

        val history = ArchiveUsageHistory(receipts = listOf(receipt))
        val archive = ByteArrayOutputStream()
        service(full = FakeFullPort(fullExport(usageHistory = history))).exportFullArchive { archive }
        val text = unzip(archive.toByteArray()).getValue("data/" + FullArchivePaths.COST_INDEX)
            .toString(Charsets.UTF_8)
        assertTrue(text.contains("\"status\":\"UNKNOWN\""))
        assertTrue(text.contains("\"reason\":\"MISSING_PRICE_VERSION\""))
        assertTrue(text.contains("\"cost\":null"))
        assertFalse(text.contains("\"cost\":0"))

        val known = ArchivePriceResolver.resolve(
            receipt,
            listOf(PriceTable(CurrencyCode.USD, 9, emptyList())),
        )
        assertEquals(ArchiveUsageCostStatus.KNOWN, known.status)
        assertEquals("1.250000000000", known.cost)
    }

    @Test fun diagnosticsNeedAnExplicitOptInAtTheSameCut() = runBlocking {
        val diagnosticsEntry = fullEntry(
            FullArchivePaths.diagnostics("diagnostics.jsonl"),
            FullArchiveEntryKind.DIAGNOSTICS,
            "{\"message\":\"ok\"}",
        )
        val hidden = fullExport(entries = defaultEntries() + diagnosticsEntry)
        val first = ByteArrayOutputStream()
        try {
            service(full = FakeFullPort(hidden)).exportFullArchive { first }
            fail("Exported diagnostics without opt-in")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, first.size())

        val mismatchedCut = fullExport(
            entries = defaultEntries() + diagnosticsEntry,
            diagnosticsIncluded = false,
        )
        val second = ByteArrayOutputStream()
        try {
            service(full = FakeFullPort(mismatchedCut)).exportFullArchive(
                FullArchiveExportOptions(includeDiagnostics = true),
            ) { second }
            fail("Accepted a cut that did not include diagnostics")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, second.size())

        val included = fullExport(
            entries = defaultEntries() + diagnosticsEntry,
            diagnosticsIncluded = true,
        )
        val third = ByteArrayOutputStream()
        val summary = service(full = FakeFullPort(included)).exportFullArchive(
            FullArchiveExportOptions(includeDiagnostics = true),
        ) { third }
        assertEquals(defaultEntries().size + 1, summary.entryCount)
        assertTrue(unzip(third.toByteArray()).containsKey("data/" + diagnosticsEntry.path))
    }

    @Test fun projectInstanceDigestMustMatchASnapshotEntry() = runBlocking {
        val wrong = AppBackupProjectInstance(
            oldRef = sampleRef(),
            name = "Novel",
            revision = 7,
            sha256 = "0".repeat(64),
        )
        val destination = ByteArrayOutputStream()
        try {
            service(full = FakeFullPort(fullExport(projectInstances = listOf(wrong))))
                .exportFullArchive { destination }
            fail("Accepted a project instance without its snapshot digest")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, destination.size())
    }

    @Test fun preparedReceiptRollsBackBeforeANewRestore() = runBlocking {
        val target = FakeFullPort(fullExport())
        val receipt = AppBackupRestoreReceipt(
            snapshotId = SNAPSHOT_ID,
            state = AppBackupRestoreReceiptState.PREPARED,
            preparedAtMillis = 1L,
            updatedAtMillis = 1L,
            cutAtMillis = 1L,
            paths = listOf(FullArchivePaths.agentSession("session-1.json")),
            projectInstances = emptyList(),
        )
        target.journal.current = receipt
        target.live[FullArchivePaths.agentSession("session-1.json")] = "partial"
        assertTrue(service(full = target).recoverPendingRestore())
        assertEquals(1, target.recoveryCalls)
        assertTrue(target.live.isEmpty())
        assertEquals(AppBackupRestoreReceiptState.FAILED, target.journal.current?.state)
        assertEquals("INTERRUPTED", target.journal.current?.failure)
    }

    private fun fullExport(
        entries: List<FullArchiveEntry> = defaultEntries(),
        projectInstances: List<AppBackupProjectInstance> = listOf(sampleProject()),
        diagnosticsIncluded: Boolean = false,
        quiesced: Boolean = true,
        activeRun: String? = null,
        queueRunning: Boolean = false,
        usageHistory: ArchiveUsageHistory = ArchiveUsageHistory.EMPTY,
    ): FullArchiveExport = FullArchiveExport(
        cut = FullArchiveCut(
            appVersion = "1.2.3",
            appSchemaVersion = 18,
            queueSchemaVersion = 2,
            cutAtMillis = 1_699_999_000_000L,
            quiesced = quiesced,
            activeRun = activeRun,
            queueRunning = queueRunning,
            projectInstances = projectInstances,
            diagnosticsIncluded = diagnosticsIncluded,
            snapshotId = SNAPSHOT_ID,
        ),
        entries = entries,
        usageHistory = usageHistory,
    )

    private fun defaultEntries(): List<FullArchiveEntry> = listOf(
        fullEntry(
            FullArchivePaths.projectSnapshot("0000.lnt"),
            FullArchiveEntryKind.PROJECT_SNAPSHOT,
            PROJECT_BYTES,
        ),
        fullEntry(
            AppBackupPaths.MODEL_SETTINGS,
            FullArchiveEntryKind.MODEL_SETTINGS,
            "{\"endpoint\":\"http://127.0.0.1:1234/v1\",\"model\":\"local\"}",
        ),
        fullEntry(
            FullArchivePaths.agentSession("session-1.json"),
            FullArchiveEntryKind.AGENT_SESSION,
            "{\"messages\":[{\"role\":\"USER\",\"text\":\"hello\"}]}",
        ),
        fullEntry(
            FullArchivePaths.approvalAudit("approvals.json"),
            FullArchiveEntryKind.APPROVAL_AUDIT,
            "{\"approvals\":[],\"mode\":\"HISTORICAL_ONLY\"}",
        ),
        fullEntry(
            FullArchivePaths.queueHistory("queues.json"),
            FullArchiveEntryKind.QUEUE_HISTORY,
            "{\"tasks\":[{\"state\":\"PAUSED\"}]}",
        ),
    )

    private fun fullEntry(
        path: String,
        kind: FullArchiveEntryKind,
        text: String,
    ): FullArchiveEntry = FullArchiveEntry.bytes(path, kind, text.toByteArray(Charsets.UTF_8))

    private fun fullEntry(
        path: String,
        kind: FullArchiveEntryKind,
        bytes: ByteArray,
    ): FullArchiveEntry = FullArchiveEntry.bytes(path, kind, bytes)

    private fun sampleProject(): AppBackupProjectInstance = AppBackupProjectInstance(
        oldRef = sampleRef(),
        name = "Novel",
        revision = 7,
        sha256 = digest(PROJECT_BYTES),
    )

    private fun sampleRef(): LocalProjectRef = LocalProjectRef(
        ProjectId("project-1"),
        ProjectInstanceId("instance-1"),
        RecoveryGeneration(1),
    )

    private class MemorySettingsPort : AppBackupDataPort {
        val live = linkedMapOf<String, ByteArray>()

        fun liveText(): Map<String, String> = live.mapValues { it.value.toString(Charsets.UTF_8) }

        override suspend fun exportEntries(): List<AppBackupEntry> = live.map { (path, bytes) ->
            AppBackupEntry.bytes(path, AppBackupEntryKind.MODEL_SETTINGS, bytes)
        }

        override suspend fun beginRestore(entries: List<AppBackupStagedEntry>): AppBackupRestoreSession {
            val staged = entries.associate { entry ->
                entry.path to entry.open().use { it.readBytes() }
            }
            return object : AppBackupRestoreSession {
                override suspend fun publish() {
                    live.clear()
                    live.putAll(staged)
                }
            }
        }
    }

    private class InMemoryJournal : FullArchiveRestoreJournal {
        var current: AppBackupRestoreReceipt? = null
        val writes = ArrayList<AppBackupRestoreReceipt>()

        override suspend fun read(): AppBackupRestoreReceipt? = current

        override suspend fun write(receipt: AppBackupRestoreReceipt) {
            current = receipt
            writes.add(receipt)
        }
    }

    private class FakeFullPort(
        private val archive: FullArchiveExport,
        private val state: FullArchiveRestoreState = FullArchiveRestoreState(idle = true),
        private val failPublishAfter: Int? = null,
    ) : FullArchiveDataPort {
        val journal = InMemoryJournal()
        val live = linkedMapOf<String, String>()
        var beginCalls = 0
        var publishCalls = 0
        var discardCalls = 0
        var recoveryCalls = 0
        var plan: FullArchiveRestorePlan? = null

        override suspend fun exportArchive(options: FullArchiveExportOptions): FullArchiveExport = archive

        override suspend fun restoreState(): FullArchiveRestoreState = state

        override val restoreJournal: FullArchiveRestoreJournal get() = journal

        override suspend fun beginRestore(
            plan: FullArchiveRestorePlan,
            entries: List<FullArchiveStagedEntry>,
        ): AppBackupRestoreSession {
            beginCalls++
            this.plan = plan
            val before = LinkedHashMap(live)
            return object : AppBackupRestoreSession {
                override suspend fun publish() {
                    publishCalls++
                    entries.forEachIndexed { index, entry ->
                        if (failPublishAfter != null && index == failPublishAfter) {
                            throw IOException("publish failed")
                        }
                        live[entry.path] = entry.open().use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                }

                override suspend fun discard() {
                    discardCalls++
                    live.keys.filter { it !in before.keys }.forEach { live.remove(it) }
                    live.putAll(before)
                }
            }
        }

        override suspend fun recoverPendingRestore(receipt: AppBackupRestoreReceipt) {
            recoveryCalls++
            receipt.paths.forEach { live.remove(it) }
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

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SNAPSHOT_ID = "snapshot-1"
        val PROJECT_BYTES = "SQLite format 3\u0000project snapshot".toByteArray(Charsets.UTF_8)
    }
}
