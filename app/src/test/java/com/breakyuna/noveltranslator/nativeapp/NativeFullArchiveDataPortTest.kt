package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.AgentApprovalResult
import com.breakyuna.noveltranslator.nativecore.domain.AgentChatLine
import com.breakyuna.noveltranslator.nativecore.domain.AgentMessage
import com.breakyuna.noveltranslator.nativecore.domain.ActiveRun
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.DurableUsageSummary
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.MessageId
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RegisteredProject
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.RequestAttemptId
import com.breakyuna.noveltranslator.nativecore.domain.RequestUsageTotals
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeOwner
import com.breakyuna.noveltranslator.nativecore.domain.RuntimePhase
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeSnapshot
import com.breakyuna.noveltranslator.nativecore.domain.StoredRun
import com.breakyuna.noveltranslator.nativecore.domain.TextApprovalState
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectSummary
import com.breakyuna.noveltranslator.nativecore.domain.TranslationQueueSnapshot
import com.breakyuna.noveltranslator.nativecore.domain.TranslationQueueStorePort
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerCursor
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerEntry
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerPage
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerQuery
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerReadPort
import com.breakyuna.noveltranslator.nativecore.domain.UsageReceiptChannel
import com.breakyuna.noveltranslator.nativecore.domain.UsageReceiptState
import com.breakyuna.noveltranslator.nativecore.domain.UserPart
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupDataPort
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupEntry
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupPaths
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupRestoreSession
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupRestoreReceiptState
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupStagedEntry
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveEntry
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveEntryKind
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveExportOptions
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchivePaths
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveStagedEntry
import com.breakyuna.noveltranslator.nativecore.storage.backup.NativeApplicationBackupService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeFullArchiveDataPortTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    @Test fun fullExportContainsProjectsAndHistoryButNotDiagnosticsByDefault() = runBlocking {
        val source = sourcePort(projects = FakeProjects(listOf(PROJECT_BYTES)))
        val archive = export(source, FullArchiveExportOptions())
        val names = unzip(archive).keys
        assertTrue(names.contains("data/" + FullArchivePaths.projectSnapshot("0000.lnt")))
        assertTrue(names.contains("data/" + AppBackupPaths.MODEL_SETTINGS))
        assertTrue(names.contains("data/" + FullArchivePaths.agentSession("project-0000.json")))
        assertTrue(names.contains("data/" + FullArchivePaths.approvalAudit("audit.json")))
        assertTrue(names.contains("data/" + FullArchivePaths.queueHistory("queues.json")))
        assertFalse(names.any { it.contains("diagnostics") })
        assertFalse(names.any { path ->
            val lower = path.lowercase()
            listOf("token", "credential", "keystore", "key-state", "private-key").any { it in lower }
        })
        val contents = unzip(archive).values.map { it.toString(Charsets.UTF_8) }.joinToString("\n")
        assertFalse(contents.contains("sk-"))
        assertFalse(contents.contains("access_token"))
    }

    @Test fun diagnosticsAreIncludedOnlyWhenRequested() = runBlocking {
        val source = sourcePort(projects = FakeProjects(listOf(PROJECT_BYTES)))
        val archive = export(source, FullArchiveExportOptions(includeDiagnostics = true))
        val entries = unzip(archive)
        assertTrue(entries.containsKey("data/" + FullArchivePaths.diagnostics("diagnostics.jsonl")))
        assertTrue(entries.getValue("data/" + FullArchivePaths.diagnostics("diagnostics.jsonl"))
            .toString(Charsets.UTF_8).contains("INFO"))
    }

    @Test fun missingHistoricalPriceVersionIsUnknownInsteadOfZero() = runBlocking {
        val usage = object : UsageLedgerReadPort {
            override suspend fun export(query: UsageLedgerQuery, destination: () -> OutputStream): Long {
                destination().use { it.write("{\"id\":\"receipt-1\",\"state\":\"DISPATCHED\"}\n".toByteArray()) }
                return 1
            }

            override suspend fun summary(query: UsageLedgerQuery): DurableUsageSummary =
                DurableUsageSummary(0, 0, 0, RequestUsageTotals())

            override suspend fun entries(query: UsageLedgerQuery, cursor: UsageLedgerCursor?): UsageLedgerPage =
                UsageLedgerPage(listOf(
                    UsageLedgerEntry(
                        id = "receipt-1",
                        channel = UsageReceiptChannel.TRANSLATION,
                        attempt = RequestAttemptId("attempt-1"),
                        ref = null,
                        run = RunIdentity(
                            ProjectId("project-0"),
                            null,
                            RunId("run-1"),
                            CommandId("command-1"),
                        ),
                        configuration = ModelConfigurationRef("configuration-1", 1),
                        model = "local",
                        state = UsageReceiptState.DISPATCHED,
                        usage = null,
                        stop = null,
                        failure = null,
                    ),
                ), null)
        }
        val archive = export(sourcePort(projects = FakeProjects(listOf(PROJECT_BYTES)), usage = usage),
            FullArchiveExportOptions())
        val raw = unzip(archive).getValue("data/" + FullArchivePaths.usageHistory("receipts.jsonl")).toString(Charsets.UTF_8)
        assertTrue(raw.contains("receipt-1"))
        assertTrue(raw.contains("DISPATCHED"))
        val cost = unzip(archive).getValue("data/" + FullArchivePaths.COST_INDEX)
            .toString(Charsets.UTF_8)
        assertTrue(cost.contains("\"status\":\"UNKNOWN\""))
        assertTrue(cost.contains("\"reason\":\"MISSING_PRICE_VERSION\""))
        assertTrue(cost.contains("\"cost\":null"))
        assertFalse(cost.contains("\"cost\":0"))
    }

    @Test fun restoreStateRejectsAnActiveRuntime() = runBlocking {
        val target = targetPort(
            projects = FakeProjects(listOf(PROJECT_BYTES)),
            runtime = { activeRuntime() },
        )
        val state = target.dataPort.restoreState()
        assertFalse(state.idle)
        assertEquals("run-1", state.activeRun)
        assertTrue(state.runningRuns == 0)
    }

    @Test fun restoreRequiresIdleAndDoesNotImportWhenBusy() = runBlocking {
        val archive = export(sourcePort(projects = FakeProjects(listOf(PROJECT_BYTES))),
            FullArchiveExportOptions())
        val existing = FakeProjects(listOf(PROJECT_BYTES))
        val target = targetPort(projects = existing, runtime = { activeRuntime() })
        val failure = runCatching {
            service(target).restore { ByteArrayInputStream(archive) }
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(0, existing.importCalls)
        assertEquals(1, existing.registeredNow().size)
    }

    @Test fun failedRestoreRollsBackOnlyThisAttemptsProject() = runBlocking {
        val archive = export(sourcePort(projects = FakeProjects(listOf(PROJECT_BYTES, OTHER_BYTES))),
            FullArchiveExportOptions())
        val existing = FakeProjects(listOf(EXISTING_BYTES))
        val rollback = FakeRollback(existing)
        val target = targetPort(
            projects = existing,
            rollback = rollback,
            failImportAt = 2,
        )
        val failure = runCatching { service(target).restore { ByteArrayInputStream(archive) } }
            .exceptionOrNull()
        assertNotNull(failure)
        assertEquals(1, existing.registeredNow().size)
        assertArrayEquals(EXISTING_BYTES, requireNotNull(existing.bytes(requireNotNull(existing.registeredNow().single().summary).ref)))
        val failed = target.dataPort.restoreJournal.read()
        assertEquals(AppBackupRestoreReceiptState.FAILED, failed?.state)
        assertEquals(2, rollback.imports.size)
        assertNotNull(rollback.imports[0].newRef)
        assertNull(rollback.imports[1].newRef)
    }

    @Test fun failedSettingsApplyRollsBackImportedProjects() = runBlocking {
        val archive = export(sourcePort(projects = FakeProjects(listOf(PROJECT_BYTES))),
            FullArchiveExportOptions())
        val existing = FakeProjects(listOf(EXISTING_BYTES))
        val rollback = FakeRollback(existing)
        val target = targetPort(
            projects = existing,
            settings = FakeSettingsPort(failPublish = true),
            rollback = rollback,
        )
        val failure = runCatching { service(target).restore { ByteArrayInputStream(archive) } }
            .exceptionOrNull()
        assertNotNull(failure)
        assertEquals(1, existing.registeredNow().size)
        assertEquals(1, rollback.imports.size)
        assertNotNull(rollback.imports.single().newRef)
        assertEquals(AppBackupRestoreReceiptState.FAILED, target.dataPort.restoreJournal.read()?.state)
    }

    @Test fun successfulRestoreMapsProjectsAndWritesReadOnlyHistory() = runBlocking {
        val archive = export(sourcePort(projects = FakeProjects(listOf(PROJECT_BYTES))),
            FullArchiveExportOptions())
        val existing = FakeProjects(listOf(EXISTING_BYTES))
        val settings = FakeSettingsPort()
        val queues = FakeQueues()
        val target = targetPort(projects = existing, settings = settings, queues = queues)
        val summary = service(target).restore { ByteArrayInputStream(archive) }
        assertEquals(2, summary.schemaVersion)
        assertEquals(2, existing.registeredNow().size)
        assertTrue(settings.restored)
        assertEquals(0, queues.writes)
        assertEquals(AppBackupRestoreReceiptState.COMMITTED, target.dataPort.restoreJournal.read()?.state)

        val historyRoot = target.historyRoot
        val historyManifest = historyRoot.walkTopDown().first { it.name == "manifest.json" }
        val manifest = historyManifest.readText(Charsets.UTF_8)
        assertTrue(manifest.contains("\"mode\":\"READ_ONLY\""))
        assertTrue(manifest.contains("\"execution_authorization\":false"))
        assertTrue(manifest.contains("\"new\""))
        assertTrue(historyRoot.walkTopDown().any {
            it.relativeTo(historyRoot).invariantSeparatorsPath.endsWith("app/agent-sessions/project-0000.json")
        })
        val approvals = historyRoot.walkTopDown().first {
            it.relativeTo(historyRoot).invariantSeparatorsPath.endsWith("app/approval-audit/audit.json")
        }.readText(Charsets.UTF_8)
        assertTrue(approvals.contains("HISTORICAL_PENDING"))
        assertTrue(approvals.contains("\"executable\":false"))
        assertTrue(approvals.contains("\"mode\":\"HISTORICAL_ONLY\""))
        val queueHistory = historyRoot.walkTopDown().first {
            it.relativeTo(historyRoot).invariantSeparatorsPath.endsWith("app/queue-history/queues.json")
        }.readText(Charsets.UTF_8)
        assertTrue(queueHistory.contains("HISTORICAL_ONLY"))
        assertTrue(queueHistory.contains("\"runnable\":false"))
    }

    @Test fun savedUiCommandIsIdempotent() {
        assertNull(fullArchiveImportIntent(null, "command-1", idle = false))
        assertEquals(listOf("command-1"), fullArchiveImportIntent(null, "command-1", idle = true))
        assertEquals(
            listOf("command-1"),
            fullArchiveImportIntent(listOf("command-1"), "command-1", idle = true),
        )
        assertNull(fullArchiveImportIntent(listOf("command-1"), "command-2", idle = true))
        assertEquals("HISTORICAL_PENDING", historicalApprovalState(TextApprovalState.PENDING))
        assertEquals("COMMITTED", historicalApprovalState(TextApprovalState.COMMITTED))
        assertEquals("HISTORICAL_PENDING", historicalProposalState("PENDING"))
        assertEquals("COMMITTED", historicalProposalState("COMMITTED"))
    }

    private fun sourcePort(
        projects: FakeProjects,
        settings: FakeSettingsPort = FakeSettingsPort(),
        agent: FakeAgent = FakeAgent(),
        proposals: FakeProposals = FakeProposals(),
        runs: FakeRuns = FakeRuns(),
        queues: FakeQueues = FakeQueues(),
        usage: UsageLedgerReadPort = FakeUsage(),
        diagnostics: FullArchiveDiagnosticsSource? = FakeDiagnostics(),
    ): TargetPort = targetPort(
        projects = projects,
        settings = settings,
        agent = agent,
        proposals = proposals,
        runs = runs,
        queues = queues,
        usage = usage,
        diagnostics = diagnostics,
        runtime = { idleRuntime() },
    )

    private fun targetPort(
        projects: FakeProjects,
        settings: FakeSettingsPort = FakeSettingsPort(),
        agent: FakeAgent = FakeAgent(emptyList()),
        proposals: FakeProposals = FakeProposals(emptyList()),
        runs: FakeRuns = FakeRuns(),
        queues: FakeQueues = FakeQueues(),
        usage: UsageLedgerReadPort = FakeUsage(),
        diagnostics: FullArchiveDiagnosticsSource? = FakeDiagnostics(),
        rollback: FakeRollback = FakeRollback(projects),
        runtime: () -> RuntimeSnapshot = { idleRuntime() },
        failImportAt: Int? = null,
    ): TargetPort {
        projects.failImportAt = failImportAt
        val historyRoot = temporary.newFolder()
        val journal = File(temporary.newFolder(), "receipt.json")
        val port = NativeFullArchiveDataPort(
            projects = projects,
            settings = settings,
            agent = agent,
            proposals = proposals,
            runs = runs,
            queues = queues,
            usage = usage,
            diagnostics = diagnostics,
            runtimeSnapshot = runtime,
            projectRollback = rollback,
            appVersion = "test",
            journalFile = journal,
            historyRoot = historyRoot,
            clock = { 1_700_000_000_000L },
        )
        return TargetPort(port, historyRoot)
    }

    private fun service(target: TargetPort) =
        NativeApplicationBackupService(
            port = EmptySettingsPort(),
            temporaryDirectory = temporary.newFolder(),
            fullArchivePort = target.dataPort,
        )

    private suspend fun export(port: TargetPort, options: FullArchiveExportOptions): ByteArray {
        val output = ByteArrayOutputStream()
        NativeApplicationBackupService(
            port = EmptySettingsPort(),
            temporaryDirectory = temporary.newFolder(),
            fullArchivePort = port.dataPort,
        ).exportFullArchive(options) { output }
        return output.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    private class TargetPort(
        val dataPort: NativeFullArchiveDataPort,
        val historyRoot: File,
    )

    private class FakeProjects(initial: List<ByteArray>) : FullArchiveProjectSource {
        private val live = LinkedHashMap<String, Pair<LocalProjectRef, ByteArray>>()
        var importCalls = 0
        var failImportAt: Int? = null

        init {
            initial.forEachIndexed { index, bytes ->
                val ref = LocalProjectRef(
                    ProjectId("project-$index"),
                    ProjectInstanceId("instance-$index"),
                    RecoveryGeneration(1),
                )
                live[ref.instanceId.value] = ref to bytes
            }
        }

        fun registeredNow(): List<RegisteredProject> = live.values.map { (ref, _) ->
            RegisteredProject(ref, TextProjectSummary(
                ref = ref,
                name = "Project ${ref.instanceId.value}",
                revision = 1,
                assetPath = RelativePath("source.txt"),
                itemCount = 0,
            ), null)
        }

        fun bytes(ref: LocalProjectRef): ByteArray? = live[ref.instanceId.value]?.second

        fun remove(ref: LocalProjectRef) {
            live.remove(ref.instanceId.value)
        }

        override suspend fun registered(): List<RegisteredProject> = registeredNow()

        override suspend fun save(ref: LocalProjectRef, destination: () -> OutputStream) {
            destination().use { it.write(requireNotNull(bytes(ref))) }
        }

        override suspend fun import(commandId: CommandId, source: () -> InputStream): TextProjectSummary {
            importCalls += 1
            if (failImportAt == importCalls) throw IOException("simulated import failure")
            val bytes = source().use { it.readBytes() }
            val ref = LocalProjectRef(
                ProjectId("imported-$importCalls"),
                ProjectInstanceId("imported-instance-$importCalls"),
                RecoveryGeneration(1),
            )
            live[ref.instanceId.value] = ref to bytes
            return TextProjectSummary(
                ref = ref,
                name = "Imported $importCalls",
                revision = 1,
                assetPath = RelativePath("source.txt"),
                itemCount = 0,
            )
        }
    }

    private class FakeRollback(private val projects: FakeProjects) : FullArchiveProjectRollback {
        val imports = ArrayList<FullArchiveImportedProject>()

        override suspend fun rollback(imports: List<FullArchiveImportedProject>) {
            this.imports.addAll(imports)
            imports.forEach { imported -> imported.newRef?.let(projects::remove) }
        }
    }

    private class FakeSettingsPort(
        private val failPublish: Boolean = false,
    ) : FullArchiveSettingsPort {
        var restored = false

        override suspend fun exportEntries(): List<FullArchiveEntry> = listOf(
            FullArchiveEntry.bytes(
                AppBackupPaths.MODEL_SETTINGS,
                FullArchiveEntryKind.MODEL_SETTINGS,
                "{\"schema\":1,\"endpoint\":\"http://127.0.0.1:1234/v1\",\"model\":\"local\"," +
                    "\"protocol\":\"OPENAI_CHAT_COMPLETIONS\",\"credentialsExcluded\":true}"
                    .toByteArray(Charsets.UTF_8),
            ),
        )

        override suspend fun beginRestore(entries: List<FullArchiveStagedEntry>): AppBackupRestoreSession =
            object : AppBackupRestoreSession {
            override suspend fun publish() {
                if (failPublish) throw IOException("settings apply failure")
                restored = true
            }
        }
    }

    private class FakeAgent(
        private val chats: List<ChatId> = listOf(ChatId("chat-1")),
    ) : FullArchiveAgentSource {
        override suspend fun chats(ref: LocalProjectRef) = chats

        override suspend fun messages(ref: LocalProjectRef, chat: ChatId) =
            listOf(AgentChatLine("message-1", "USER", "hello"))

        override suspend fun history(ref: LocalProjectRef, chat: ChatId): List<AgentMessage> =
            listOf(AgentMessage.User(MessageId("message-1"), listOf(UserPart.Text("hello"))))

        override suspend fun approvals(ref: LocalProjectRef, chat: ChatId) =
            listOf(AgentApprovalResult("approval-1", 1, TextApprovalState.PENDING))
    }

    private class FakeProposals(
        private val pending: List<FullArchiveProposalAudit> = listOf(
            FullArchiveProposalAudit("ITEM", "proposal-1", "chat-1", "run-1", "command-1", 1, "PENDING", 1),
        ),
    ) : FullArchiveProposalSource {
        override suspend fun recent(ref: LocalProjectRef, chat: ChatId) = pending
    }

    private class FakeRuns : FullArchiveRunSource {
        override suspend fun recent(ref: LocalProjectRef, limit: Int): List<StoredRun> = emptyList()
    }

    private class FakeQueues : TranslationQueueStorePort {
        var writes = 0

        override suspend fun load(ref: LocalProjectRef):
            com.breakyuna.noveltranslator.nativecore.domain.StoredTranslationQueue? = null

        override suspend fun list():
            List<com.breakyuna.noveltranslator.nativecore.domain.StoredTranslationQueue> =
            emptyList()

        override suspend fun compareAndSet(
            ref: LocalProjectRef,
            expected: com.breakyuna.noveltranslator.nativecore.domain.StoredTranslationQueue?,
            next: TranslationQueueSnapshot,
        ): com.breakyuna.noveltranslator.nativecore.domain.StoredTranslationQueue {
            writes += 1
            throw UnsupportedOperationException("A restore must not write a runnable queue")
        }
    }

    private class FakeUsage : UsageLedgerReadPort {
        override suspend fun export(query: UsageLedgerQuery, destination: () -> OutputStream): Long {
            destination().use { it.write("{\"type\":\"complete\",\"entries\":0}\n".toByteArray()) }
            return 0
        }

        override suspend fun summary(query: UsageLedgerQuery): DurableUsageSummary =
            DurableUsageSummary(0, 0, 0, RequestUsageTotals())

        override suspend fun entries(query: UsageLedgerQuery, cursor: UsageLedgerCursor?): UsageLedgerPage =
            UsageLedgerPage(emptyList(), null)
    }

    private class FakeDiagnostics : FullArchiveDiagnosticsSource {
        override suspend fun export(output: OutputStream): Long {
            val text = "{\"level\":\"INFO\",\"message\":\"ok\"}\n".toByteArray(Charsets.UTF_8)
            output.write(text)
            return text.size.toLong()
        }
    }

    private class EmptySettingsPort : AppBackupDataPort {
        override suspend fun exportEntries(): List<AppBackupEntry> = emptyList()

        override suspend fun beginRestore(entries: List<AppBackupStagedEntry>): AppBackupRestoreSession =
            object : AppBackupRestoreSession {
                override suspend fun publish() = Unit
            }
    }

    private companion object {
        val PROJECT_BYTES = "SQLite format 3\u0000project-one".toByteArray(Charsets.UTF_8)
        val OTHER_BYTES = "SQLite format 3\u0000project-two".toByteArray(Charsets.UTF_8)
        val EXISTING_BYTES = "SQLite format 3\u0000existing".toByteArray(Charsets.UTF_8)

        fun idleRuntime() = RuntimeSnapshot(
            revision = 0,
            selectedChat = null,
            activeRun = null,
            projectWriteRunning = false,
            skillWriteRunning = false,
            authenticationWriteRunning = false,
            closed = false,
        )

        fun activeRuntime() = RuntimeSnapshot(
            revision = 1,
            selectedChat = null,
            activeRun = ActiveRun(
                identity = RunIdentity(
                    ProjectId("project-0"),
                    ChatId("chat-0"),
                    com.breakyuna.noveltranslator.nativecore.domain.RunId("run-1"),
                    CommandId("command-1"),
                ),
                owner = RuntimeOwner.AGENT,
                phase = RuntimePhase.RUNNING,
                outstandingOperations = 1,
            ),
            projectWriteRunning = false,
            skillWriteRunning = false,
            authenticationWriteRunning = false,
            closed = false,
        )
    }
}
