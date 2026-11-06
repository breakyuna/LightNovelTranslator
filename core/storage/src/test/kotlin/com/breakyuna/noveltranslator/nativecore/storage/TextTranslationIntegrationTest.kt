package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.*
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TextTranslationIntegrationTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var store: NativeTextProjectStore
    private lateinit var runs: NativeRunStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator(); store = NativeTextProjectStore(context, runtime)
        runs = NativeRunStore(context, store, runtime)
    }
    @After fun cleanup() = runBlocking { runs.close(); store.close(); runtime.close() }
    private suspend fun create(text: String): TextProjectSummary {
        val project = store.createTxt(CommandId("create"), "test", RelativePath("test.txt"), text.toByteArray())
        runtime.selectChat(ChatIdentity(project.ref.projectId, ChatId("workspace")))
        return project
    }
    private fun identity(project: TextProjectSummary) = RunIdentity(project.ref.projectId, null, RunId("run"), CommandId("start"))
    private fun service(model: TranslationModelBackend) = TextTranslationService(runtime, store, store, NativeModelGateway(runtime, model), DurableRunExecutor(runtime, runs))

    @Test fun qualityBatchUsesOnlyTheFrozenWarningSelectionAndRejectsStaleIndexes() = runBlocking {
        val project = create("Alice\nBob\nAlice")
        val policy = store.textPolicy(project.ref)
        store.editTextPolicy(ProjectPolicyEdit(CommandId("quality-rules"), project.ref, 0, policy.fingerprint,
            ProjectTextPolicy(listOf(ProjectTextRule("term", TextRuleKind.TERM, "Alice", "艾莉丝")))))
        val items = store.readPage(project.ref).items
        for ((offset, text) in listOf("她", "你好", "艾莉丝").withIndex()) {
            val item = items[offset]
            store.edit(ManualTextEdit(CommandId("quality-translation-$offset"), project.ref, item.id,
                1L + offset, item.fingerprint, text))
        }
        val index = ProjectQualityScanner(store, store).scan(project.ref, 4, TextItemQuery(), TextProofreadingOptions())
        val selected = index.select(TextQualityFilter(TextQualityOutcome.ANY_WARNING, TextWarningCode.GLOSSARY, "term"))
        assertEquals(listOf(items.first().id), selected.ids)
        val empty = index.select(TextQualityFilter(TextQualityOutcome.ANY_WARNING, TextWarningCode.GLOSSARY, "absent-rule"))
        assertNull(store.prepareQualityBatch(CommandId("empty-quality"), project.ref, 4, empty, TextBatchChange.EXCLUDE))
        val batch = requireNotNull(store.prepareQualityBatch(CommandId("quality-selection"), project.ref, 4, selected, TextBatchChange.EXCLUDE))
        assertEquals(selected.ids, batch.targets.map { it.id })
        assertEquals(1, store.editBatch(batch).affectedItems)
        assertEquals(listOf(TextItemStatus.EXCLUDED, TextItemStatus.PROCESSED, TextItemStatus.PROCESSED),
            store.readPage(project.ref).items.map { it.status })
        try {
            store.prepareQualityBatch(CommandId("stale-quality"), project.ref, 5, selected, TextBatchChange.CLEAR_AND_RESET)
            fail("Accepted stale quality index")
        } catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.STALE_PAGE, error.reason) }
        assertEquals(5L, store.open(project.ref).revision)
    }

    @Test fun wholeQueryBatchExceedsOnePageAndReplaysWithoutWideningTheSnapshot() = runBlocking {
        val project = create((1..145).joinToString("\n") { "target-$it" })
        val query = TextItemQuery(TextItemStatus.NONE, "target-", TextSearchField.SOURCE)
        val batch = requireNotNull(store.prepareQueryBatch(CommandId("all-matches"), project.ref, 0, query, TextBatchChange.EXCLUDE))
        assertEquals(145, batch.targets.size)
        assertEquals(0L, store.open(project.ref).revision)
        val receipt = store.editBatch(batch)
        assertEquals(145, receipt.affectedItems)
        assertEquals(145L, receipt.revision)
        assertEquals(receipt, store.editBatch(batch))
        assertEquals(0, store.queryPage(project.ref, query, 0, 20).matchingItems)
        val snapshot = ByteArrayOutputStream()
        store.saveProject(project.ref) { snapshot }
        val imported = store.importProject(CommandId("query-batch-roundtrip")) { ByteArrayInputStream(snapshot.toByteArray()) }
        assertEquals(145, imported.itemCount)
        assertEquals(145L, imported.revision)
        assertEquals(145, store.readPage(imported.ref).statistics!!.excluded)
    }

    @Test fun changedRevisionRejectsEveryTargetOfAWholeQueryBatch() = runBlocking {
        val project = create((1..145).joinToString("\n") { "target-$it" })
        // First make a real change, then prepare a status-changing snapshot at that revision.
        val item = store.readPage(project.ref).items.first()
        store.edit(ManualTextEdit(CommandId("intervening"), project.ref, item.id, 0, item.fingerprint, "keep"))
        val all = requireNotNull(store.prepareQueryBatch(CommandId("fixed-all"), project.ref, 1,
            TextItemQuery(), TextBatchChange.EXCLUDE))
        val latest = store.readPage(project.ref).items.first()
        store.edit(ManualTextEdit(CommandId("intervening-again"), project.ref, latest.id, 1, latest.fingerprint, "latest"))
        val rejected = store.editBatch(all)
        assertEquals(ManualEditStatus.STALE_REVISION, rejected.status)
        assertEquals(0, rejected.affectedItems)
        assertEquals(2L, store.open(project.ref).revision)
        assertEquals("latest", store.readPage(project.ref).items.first().translation)
        assertEquals(0, store.readPage(project.ref).statistics!!.excluded)
        assertEquals(rejected, store.editBatch(all))
    }

    @Test fun assetQuerySharesScopeAcrossCountsNavigationAndQualityScan() = runBlocking {
        val project = create("needle\nneedle")
        store.importAsset(AssetImportPlan(CommandId("query-second"), project.ref, 0, RelativePath("second.txt"),
            ProjectAssetFormat.TXT, "needle\nother".toByteArray(),
            listOf(AssetImportUnit(0, 0, "needle"), AssetImportUnit(1, 1, "other"))))
        val all = store.readPage(project.ref).items
        val asset = all.last().assetId
        val query = TextItemQuery(TextItemStatus.NONE, "needle", TextSearchField.SOURCE, asset)
        val page = store.queryPage(project.ref, query, 0, 1)
        assertEquals(listOf(all[2].id), page.items.map { it.id })
        assertEquals(1, page.matchingItems)
        assertEquals(4, page.statistics!!.total)
        assertEquals(1, page.matchingStatistics!!.total)
        assertEquals(1, page.matchingStatistics!!.unprocessed)
        assertEquals(0, store.queryPage(project.ref, query, all[2].id.value, 1).items.size)
        assertEquals(all[2].id, store.adjacentTextItem(project.ref, 1, query, all[0].id, TextItemDirection.NEXT)!!.item.id)
        assertEquals(all[2].id, store.adjacentTextItem(project.ref, 1, query, all[3].id, TextItemDirection.PREVIOUS)!!.item.id)
        val index = ProjectQualityScanner(store, store).scan(project.ref, 1, query, TextProofreadingOptions())
        assertEquals(2, index.scanned)
        assertEquals(listOf(all[2].id), index.facts.map { it.id })
        val missing = store.queryPage(project.ref, query.copy(assetId = Long.MAX_VALUE), 0, 20)
        assertTrue(missing.items.isEmpty())
        assertEquals(0, missing.matchingStatistics!!.total)
    }

    @Test fun assetEnableCommandsAreDurableAndDisabledSourcesNeverDispatch() = runBlocking {
        val project = create("source")
        val original = store.readPage(project.ref).items.single()
        val disable = CommandId("disable-source")
        val receipt = store.setAssetEnabled(disable, project.ref, 0, original.assetId, false)
        assertEquals(AssetMutationStatus.COMMITTED, receipt.status)
        assertEquals(receipt, store.setAssetEnabled(disable, project.ref, 0, original.assetId, false))
        try {
            store.setAssetEnabled(disable, project.ref, 0, original.assetId, true)
            fail("Rebound enable command")
        } catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.COMMAND_CONFLICT, error.reason) }
        assertEquals(AssetMutationStatus.STALE_REVISION,
            store.setAssetEnabled(CommandId("stale-enable"), project.ref, 0, original.assetId, true).status)
        assertEquals(original.fingerprint, store.readPage(project.ref).items.single().fingerprint)
        val bytes = ByteArrayOutputStream()
        store.saveProject(project.ref) { bytes }
        val copy = store.importProject(CommandId("disabled-copy")) { ByteArrayInputStream(bytes.toByteArray()) }
        assertFalse(copy.assets.single().enabled)
        assertEquals(0, store.readPage(project.ref).statistics!!.translatableUnprocessed)
        assertEquals(1, store.readPage(project.ref).statistics!!.unprocessed)
        val local = LocalSimulationModel()
        var calls = 0
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                calls++
                return local.complete(request)
            }
        }
        val translation = service(backend)
        val empty = translation.translateProject(project.ref, identity(project), backend.configuration, 1)
        assertEquals(0, empty.requests)
        assertEquals(0, calls)
        val enabled = store.setAssetEnabled(CommandId("enable-source"), project.ref, 1, original.assetId, true)
        assertEquals(2L, enabled.revision)
        val translated = translation.translateProject(project.ref,
            RunIdentity(project.ref.projectId, null, RunId("enabled-run"), CommandId("enabled-run")), backend.configuration, 2)
        assertEquals(1, translated.committedItems)
        assertEquals(1, calls)
    }

    @Test fun removingAnEditedAssetPreservesHistoryReceiptsAndNeverReusesItsIds() = runBlocking {
        val project = create("keep")
        val imported = store.importAsset(AssetImportPlan(CommandId("append-for-remove"), project.ref, 0,
            RelativePath("remove.txt"), ProjectAssetFormat.TXT, "removed source".toByteArray(),
            listOf(AssetImportUnit(0, 0, "removed source"))))
        val removed = store.readPage(project.ref).items.last()
        val command = CommandId("edit-before-remove")
        store.edit(ManualTextEdit(command, project.ref, removed.id, imported.revision, removed.fingerprint, "historical translation"))
        val receiptBefore = store.receipt(project.ref, command)
        // Reconstruct the shipped v3 shape to exercise the history-preserving migration.
        val originalFile = File(context.filesDir, "native_projects/${project.ref.instanceId.value}/project.lnt")
        NativeProjectDatabase.open(originalFile, writable = true).use { db ->
            db.setForeignKeyConstraintsEnabled(false)
            try {
                NativeProjectDatabase.transaction(db) {
                    db.execSQL("ALTER TABLE item_revisions RENAME TO revisions_v5")
                    db.execSQL("""CREATE TABLE item_revisions (
                        revision INTEGER PRIMARY KEY, item_id INTEGER NOT NULL REFERENCES items(id),
                        previous_fingerprint TEXT NOT NULL, fingerprint TEXT NOT NULL, translation TEXT NOT NULL,
                        status TEXT NOT NULL, instance_id TEXT NOT NULL, generation INTEGER NOT NULL,
                        command_id TEXT NOT NULL, committed_at INTEGER NOT NULL)""")
                    db.execSQL("""INSERT INTO item_revisions(revision,item_id,previous_fingerprint,fingerprint,
                        translation,status,instance_id,generation,command_id,committed_at)
                        SELECT revision,item_id,previous_fingerprint,fingerprint,translation,status,
                        instance_id,generation,command_id,committed_at FROM revisions_v5""")
                    db.execSQL("DROP TABLE revisions_v5")
                    db.execSQL("DROP TABLE archived_items")
                    db.execSQL("DROP TABLE IF EXISTS approved_item_receipts")
                    db.execSQL("DROP TABLE IF EXISTS approved_pdf_page_receipts")
                    db.version = 3
                }
            } finally { db.setForeignKeyConstraintsEnabled(true) }
            NativeProjectDatabase.validateSchema(db)
        }
        val mutation = store.removeAsset(CommandId("remove-edited"), project.ref, 2, removed.assetId)
        assertEquals(AssetMutationStatus.COMMITTED, mutation.status)
        assertEquals(1, store.open(project.ref).itemCount)
        assertEquals(receiptBefore, store.receipt(project.ref, command))
        val snapshot = ByteArrayOutputStream()
        store.saveProject(project.ref) { snapshot }
        val copy = store.importProject(CommandId("removed-roundtrip")) { ByteArrayInputStream(snapshot.toByteArray()) }
        assertEquals(1, copy.itemCount)
        assertEquals(3L, copy.revision)
        val file = File(context.filesDir, "native_projects/${copy.ref.instanceId.value}/project.lnt")
        NativeProjectDatabase.open(file).use { db ->
            assertEquals(4, db.version)
            assertEquals(1L, NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM archived_items"))
            assertEquals(1L, NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM item_revisions"))
            assertEquals("historical translation", db.rawQuery("SELECT translation FROM archived_items", null).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            })
        }
        store.importAsset(AssetImportPlan(CommandId("append-again"), project.ref, 3,
            RelativePath("new.txt"), ProjectAssetFormat.TXT, "new".toByteArray(), listOf(AssetImportUnit(0, 0, "new"))))
        assertTrue(store.readPage(project.ref).items.last().id.value > removed.id.value)
        assertEquals(receiptBefore, store.receipt(project.ref, command))
        val tampered = File(context.cacheDir, "tampered-archive.lnt")
        tampered.writeBytes(snapshot.toByteArray())
        NativeProjectDatabase.open(tampered, writable = true).use {
            it.execSQL("UPDATE archived_items SET translation='changed without fingerprint'")
        }
        try {
            store.importProject(CommandId("reject-tampered-archive")) { tampered.inputStream() }
            fail("Accepted corrupt archived history")
        } catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.CORRUPT_PROJECT, error.reason) }
    }

    @Test fun terminalProtocolFailurePersistsErrorsAndExplicitContinueCanTranslateThem() = runBlocking {
        val project = create("first\nsecond")
        var reject = true
        val backend = object : ConfiguredTranslationBackend {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                if (reject) throw AgentModelFailure(ModelFailureKind.PARSING)
                return TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, "translated:" + it.source) },
                    unknownRequestUsage(), ModelStopReason.COMPLETE)
            }
        }
        val config = ModelConfigurationRef("remote", 1)
        val translation = TextTranslationService(runtime, store, store,
            NativeModelGateway(runtime, LocalSimulationModel(), remoteTranslation = backend), DurableRunExecutor(runtime, runs))
        try {
            translation.translateProject(project.ref, identity(project), config, 0, options = RemoteTranslationOptions("en"))
            fail("Expected protocol failure")
        } catch (failure: AgentModelFailure) { assertEquals(ModelFailureKind.PARSING, failure.kind) }
        assertTrue(store.readPage(project.ref).items.all { it.status == TextItemStatus.ERROR && it.translation.isEmpty() })
        val failed = runs.recent(project.ref).single()
        assertEquals(0, failed.checkpoint.committedItems)
        assertEquals(StoredRunState.FAILED, failed.state)
        assertEquals(2L, store.open(project.ref).revision)
        reject = false
        val result = translation.translateProject(project.ref,
            RunIdentity(project.ref.projectId, null, RunId("continue-errors"), CommandId("continue-errors")), config, 2,
            options = RemoteTranslationOptions("en", startMode = TranslationStartMode.CONTINUE))
        assertEquals(2, result.committedItems)
        assertTrue(store.readPage(project.ref).items.all { it.status == TextItemStatus.PROCESSED })
    }

    @Test fun sameFileDeduplicationReusesAcrossPagesWithoutCrossingAssets() = runBlocking {
        val project = create("repeat\nrepeat\nrepeat")
        assertEquals(AssetMutationStatus.COMMITTED, store.importAsset(AssetImportPlan(CommandId("other-file"),
            project.ref, 0, RelativePath("other.txt"), ProjectAssetFormat.TXT, "repeat".toByteArray(),
            listOf(AssetImportUnit(0, 0, "repeat")))).status)
        var calls = 0
        val backend = object : ConfiguredTranslationBackend {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                calls++
                return TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, "translation-$calls") },
                    unknownRequestUsage(), ModelStopReason.COMPLETE)
            }
        }
        val translation = TextTranslationService(runtime, store, store,
            NativeModelGateway(runtime, LocalSimulationModel(), remoteTranslation = backend), DurableRunExecutor(runtime, runs))
        val result = translation.translateProject(project.ref, identity(project), ModelConfigurationRef("remote", 1),
            store.open(project.ref).revision, pageSize = 1,
            options = RemoteTranslationOptions("en", deduplicateSameFile = true))
        assertEquals(2, calls)
        assertEquals(2, result.requests)
        assertEquals(4, result.committedItems)
        assertEquals(listOf("translation-1", "translation-1", "translation-1", "translation-2"),
            store.readPage(project.ref).items.map { it.translation })
    }

    @Test fun duplicateReuseDoesNotUseTranslationsFromBeforeTheConfirmedRun() = runBlocking {
        val project = create("repeat\nrepeat\nrepeat")
        val first = store.readPage(project.ref).items.first()
        store.edit(ManualTextEdit(CommandId("old-rule-result"), project.ref, first.id, 0, first.fingerprint, "old"))
        var sent = 0
        val backend = object : ConfiguredTranslationBackend {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                sent += request.inputs.size
                return TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, "fresh") },
                    unknownRequestUsage(), ModelStopReason.COMPLETE)
            }
        }
        val translation = TextTranslationService(runtime, store, store,
            NativeModelGateway(runtime, LocalSimulationModel(), remoteTranslation = backend), DurableRunExecutor(runtime, runs))
        val result = translation.translateProject(project.ref, identity(project), ModelConfigurationRef("remote", 1), 1,
            options = RemoteTranslationOptions("en", deduplicateSameFile = true))
        assertEquals(1, sent)
        assertEquals(2, result.committedItems)
        assertEquals(listOf("old", "fresh", "fresh"), store.readPage(project.ref).items.map { it.translation })
    }

    @Test fun confirmedRetryBudgetSplitsProtocolFailureAndCommitsOnlySuccessfulChildren() = runBlocking {
        val project = create("first\nsecond")
        val config = ModelConfigurationRef("remote", 1)
        val requests = mutableListOf<TranslationModelRequest>()
        val backend = object : ConfiguredTranslationBackend {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                requests.add(request)
                if (request.inputs.size > 1) throw AgentModelFailure(ModelFailureKind.PARSING)
                return TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, "translated:" + it.source) },
                    unknownRequestUsage(), ModelStopReason.COMPLETE)
            }
        }
        val translation = TextTranslationService(runtime, store, store,
            NativeModelGateway(runtime, LocalSimulationModel(), remoteTranslation = backend), DurableRunExecutor(runtime, runs))
        val result = translation.translateProject(project.ref, identity(project), config, 0,
            options = RemoteTranslationOptions("en", additionalRequestsPerBatch = 2))
        assertEquals(listOf(2, 1, 1), requests.map { it.inputs.size })
        assertEquals(3, requests.map { it.attempt }.toSet().size)
        assertEquals(3, result.requests)
        assertEquals(2, result.committedItems)
        assertEquals(2L, store.open(project.ref).revision)
        assertTrue(store.readPage(project.ref).items.all { it.status == TextItemStatus.PROCESSED })
    }

    @Test fun authenticationFailureNeverConsumesConfirmedRetryAllowance() = runBlocking {
        val project = create("source")
        var calls = 0
        val backend = object : ConfiguredTranslationBackend {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                calls++
                throw AgentModelFailure(ModelFailureKind.AUTHENTICATION)
            }
        }
        val translation = TextTranslationService(runtime, store, store,
            NativeModelGateway(runtime, LocalSimulationModel(), remoteTranslation = backend), DurableRunExecutor(runtime, runs))
        try {
            translation.translateProject(project.ref, identity(project), ModelConfigurationRef("remote", 1), 0,
                options = RemoteTranslationOptions("en", additionalRequestsPerBatch = 5))
            fail("Expected authentication refusal")
        } catch (failure: AgentModelFailure) { assertEquals(ModelFailureKind.AUTHENTICATION, failure.kind) }
        assertEquals(1, calls)
        assertEquals(0L, store.open(project.ref).revision)
    }

    @Test fun stopAfterFailedAttemptPreventsAllReplacementRequests() = runBlocking {
        val project = create("source")
        var calls = 0
        val backend = object : ConfiguredTranslationBackend {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                calls++
                runtime.requestStop(lease)
                throw AgentModelFailure(ModelFailureKind.SERVER)
            }
        }
        val translation = TextTranslationService(runtime, store, store,
            NativeModelGateway(runtime, LocalSimulationModel(), remoteTranslation = backend), DurableRunExecutor(runtime, runs))
        try {
            translation.translateProject(project.ref, identity(project), ModelConfigurationRef("remote", 1), 0,
                options = RemoteTranslationOptions("en", additionalRequestsPerBatch = 5))
            fail("Expected stopped run")
        } catch (failure: RuntimeUnavailable) { assertEquals(RuntimeUnavailable.Reason.STOPPING, failure.reason) }
        assertEquals(1, calls)
        assertEquals(0L, store.open(project.ref).revision)
    }

    @Test fun precedingContextCrossesPagesButNeverJoinsTheOutputSet() = runBlocking {
        val project = create("First.\nSecond.\nThird.")
        val config = ModelConfigurationRef("remote", 1)
        val seen = mutableListOf<TranslationModelRequest>()
        val backend = object : ConfiguredTranslationBackend {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                seen.add(request)
                assertTrue(request.precedings.none { previous -> request.inputs.any { it.id == previous.id } })
                return TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, "translated:" + it.source) },
                    unknownRequestUsage(), ModelStopReason.COMPLETE)
            }
        }
        val service = TextTranslationService(runtime, store, store,
            NativeModelGateway(runtime, LocalSimulationModel(), remoteTranslation = backend), DurableRunExecutor(runtime, runs))
        val result = service.translateProject(project.ref, identity(project), config, 0, pageSize = 1,
            options = RemoteTranslationOptions("en", precedingLines = 2))
        assertEquals(3, result.committedItems)
        assertEquals(listOf(0, 1, 2), seen.map { it.precedings.size })
        assertEquals(listOf("First.", "Second."), seen.last().precedings.map { it.source })
        assertEquals(3L, store.open(project.ref).revision)
    }

    @Test fun confirmedModesRetryErrorsAndRetranslateWithoutClearingSavedTextOnFailure() = runBlocking {
        val project = create("original")
        val original = store.readPage(project.ref).items.single()
        store.edit(ManualTextEdit(CommandId("save-original"), project.ref, original.id, 0, original.fingerprint, "saved"))
        val imported = store.importAsset(AssetImportPlan(CommandId("errors"), project.ref, 1,
            RelativePath("errors.txt"), ProjectAssetFormat.TXT, "failed".toByteArray(),
            listOf(AssetImportUnit(0, 0, "failed", status = TextItemStatus.ERROR))))
        assertEquals(AssetMutationStatus.COMMITTED, imported.status)
        val model = ModelConfigurationRef("remote", 1)
        val calls = mutableListOf<List<ItemId>>()
        var reject = false
        val backend = object : ConfiguredTranslationBackend {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                calls.add(request.inputs.map { it.id })
                if (reject) throw AgentModelFailure(ModelFailureKind.SERVER)
                return TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, "new:" + it.source) },
                    unknownRequestUsage(), ModelStopReason.COMPLETE)
            }
        }
        val translation = TextTranslationService(runtime, store, store,
            NativeModelGateway(runtime, LocalSimulationModel(), remoteTranslation = backend), DurableRunExecutor(runtime, runs))
        suspend fun run(mode: TranslationStartMode, key: String) = translation.translateProject(project.ref,
            RunIdentity(project.ref.projectId, null, RunId(key), CommandId(key)), model,
            store.open(project.ref).revision, options = RemoteTranslationOptions("en", startMode = mode))
        assertEquals(0, run(TranslationStartMode.NEW, "new").requests)
        assertEquals(1, run(TranslationStartMode.CONTINUE, "continue").committedItems)
        assertEquals("saved", store.readPage(project.ref).items.first().translation)
        val saved = store.readPage(project.ref).items.associate { it.id to it.translation }
        reject = true
        try { run(TranslationStartMode.RESET, "failed-reset"); fail("Expected failed request") }
        catch (error: AgentModelFailure) { assertEquals(ModelFailureKind.SERVER, error.kind) }
        assertEquals(saved, store.readPage(project.ref).items.associate { it.id to it.translation })
        reject = false
        assertEquals(2, run(TranslationStartMode.RESET, "reset").committedItems)
        assertEquals("new:original", store.readPage(project.ref).items.first().translation)
        assertTrue(calls.first().none { it == original.id })
    }

    @Test fun projectPlanningSeparatesAssetsAndNeverDispatchesDisabledSources() = runBlocking {
        val project = create("first\nsecond")
        suspend fun append(command: String, enabled: Boolean): AssetMutationReceipt = store.importAsset(
            AssetImportPlan(CommandId(command), project.ref, store.open(project.ref).revision,
                RelativePath("$command.txt"), ProjectAssetFormat.TXT, "third\nfourth".toByteArray(),
                listOf(AssetImportUnit(0, 0, "third"), AssetImportUnit(1, 1, "fourth")), enabled = enabled))
        assertEquals(AssetMutationStatus.COMMITTED, append("enabled", true).status)
        assertEquals(AssetMutationStatus.COMMITTED, append("disabled", false).status)
        val before = store.readPage(project.ref).items
        val assetsByItem = before.associate { it.id to it.assetId }
        val enabled = store.assets(project.ref).filter { it.enabled }.map { it.id }.toSet()
        val received = mutableListOf<ItemId>()
        var calls = 0
        val local = LocalSimulationModel()
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                calls++
                val files = request.inputs.map { assetsByItem.getValue(it.id) }.toSet()
                assertEquals(1, files.size)
                assertTrue(files.all { it in enabled })
                received.addAll(request.inputs.map { it.id })
                return local.complete(request)
            }
        }
        val result = service(backend).translateProject(project.ref, identity(project), backend.configuration,
            store.open(project.ref).revision)
        assertEquals(2, calls)
        assertEquals(4, result.committedItems)
        assertEquals(before.filter { it.assetId in enabled }.map { it.id }.toSet(), received.toSet())
        assertEquals(received.size, received.toSet().size)
        store.readPage(project.ref).items.filter { it.assetId !in enabled }.forEach {
            assertEquals(TextItemStatus.NONE, it.status)
            assertEquals("", it.translation)
        }
    }

    @Test fun remoteProjectRunCarriesFrozenOptionsAndKeepsEarlierBatchesAfterFailure() = runBlocking {
        val project = create("one\ntwo\nthree\nfour\nfive")
        val config = ModelConfigurationRef("remote", 1)
        val options = RemoteTranslationOptions("en", instructions = "fixture", batchSize = 2)
        var calls = 0
        val remote = object : ConfiguredTranslationBackend {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                assertEquals(options, request.options); assertEquals(config, request.configuration)
                assertTrue(request.inputs.size <= 2)
                if (++calls == 2) throw AgentModelFailure(ModelFailureKind.SERVER)
                return TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, "translated:" + it.source) },
                    unknownRequestUsage(), ModelStopReason.COMPLETE)
            }
        }
        val service = TextTranslationService(runtime, store, store,
            NativeModelGateway(runtime, LocalSimulationModel(), remoteTranslation = remote), DurableRunExecutor(runtime, runs))
        try { service.translateProject(project.ref, identity(project), config, 0, options = options); fail("Expected second batch failure") }
        catch (error: AgentModelFailure) { assertEquals(ModelFailureKind.SERVER, error.kind) }
        assertEquals(2, calls); assertEquals(2L, store.open(project.ref).revision)
        assertEquals(listOf(TextItemStatus.PROCESSED, TextItemStatus.PROCESSED, TextItemStatus.NONE, TextItemStatus.NONE, TextItemStatus.NONE),
            store.readPage(project.ref).items.map { it.status })
    }

    @Test fun foregroundCommandRejectsChangedRemoteLanguageBeforeAnyDispatch() = runBlocking {
        val project = create("source")
        val owner = SupervisorJob()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, runs),
            CoroutineScope(owner + Dispatchers.Default), service(LocalSimulationModel()))
        val request = ForegroundTranslationRequest(project.ref, identity(project), ModelConfigurationRef("remote", 1),
            0, 0, options = RemoteTranslationOptions("en"))
        try {
            val handle = runner.prepare(request)
            try { runner.prepare(request.copy(options = RemoteTranslationOptions("ja"))); fail("Rebound command") }
            catch (_: ForegroundTranslationConflict) { }
            assertSame(handle, runner.prepare(request))
            assertEquals(0L, store.open(project.ref).revision)
            runner.stop(handle.token)
            try { handle.await() } catch (_: CancellationException) { }
            assertNull(runtime.snapshot().activeRun)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun foregroundProjectScopeProcessesEveryPageWithinOneExecution() = runBlocking {
        val project = create((1..45).joinToString("\n") { "source-$it" })
        val local = LocalSimulationModel()
        val owner = SupervisorJob()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, runs),
            CoroutineScope(owner + Dispatchers.Default), service(local))
        try {
            val handle = runner.prepare(ForegroundTranslationRequest(project.ref, identity(project), local.configuration,
                0, 0, 20, TranslationScope.PROJECT))
            assertEquals(0L, store.open(project.ref).revision)
            assertTrue(runner.foregroundStarted(handle.token))
            val result = withTimeout(10_000) { handle.await() }
            assertEquals(45, result.committedItems); assertEquals(3, result.requests)
            assertEquals(45L, store.open(project.ref).revision)
            assertEquals(1, runs.recent(project.ref).size)
            assertEquals(StoredRunState.SUCCEEDED, runs.recent(project.ref).single().state)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun foregroundProjectStopOnAnotherPageRetainsOnlyCommittedFactsAndNeverRestarts() = runBlocking {
        val project = create((1..45).joinToString("\n") { "source-$it" })
        val local = LocalSimulationModel()
        val entered = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                if (calls.incrementAndGet() == 2) { entered.complete(Unit); awaitCancellation() }
                return local.complete(request)
            }
        }
        val owner = SupervisorJob()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, runs),
            CoroutineScope(owner + Dispatchers.Default), service(backend))
        try {
            val handle = runner.prepare(ForegroundTranslationRequest(project.ref, identity(project), backend.configuration,
                0, 0, 20, TranslationScope.PROJECT))
            assertTrue(runner.foregroundStarted(handle.token))
            withTimeout(10_000) { entered.await() }
            assertTrue(runner.stop(handle.token))
            try { withTimeout(10_000) { handle.await() }; fail("Expected stop") }
            catch (timeout: TimeoutCancellationException) { throw timeout }
            catch (_: CancellationException) { }
            assertEquals(20L, store.open(project.ref).revision)
            assertEquals(2, calls.get())
            assertEquals(StoredRunState.INTERRUPTED, runs.recent(project.ref).single().state)
            runs.recoverInterrupted(); assertEquals(2, calls.get())
            assertEquals(ForegroundTranslationPhase.CANCELLED, runner.state.value!!.phase)
            assertNull(runtime.snapshot().activeRun)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun usageSurvivesUnknownResponsesAndLongOverflowWithoutInterruptingCommits() = runBlocking {
        val project = create("one\ntwo\nthree")
        val local = LocalSimulationModel()
        var calls = 0
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                val measurement = if (++calls <= 2) TokenMeasurement(Long.MAX_VALUE, UsageSource.ACTUAL)
                    else TokenMeasurement(null, UsageSource.UNKNOWN)
                val response = local.complete(request)
                return TranslationModelResponse(response.outputs,
                    RequestUsage(measurement, measurement, measurement, measurement, measurement), response.stop)
            }
        }
        val result = service(backend).translateProject(project.ref, identity(project), backend.configuration, 0, 1)
        assertEquals(3, result.committedItems); assertEquals(3L, result.revision)
        assertEquals(UsageSource.UNKNOWN, result.usage.input.source)
        val totals = requireNotNull(result.usageTotals)
        assertEquals(3L, totals.responses)
        assertEquals(java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.valueOf(2)), totals.input.actual)
        assertEquals(1L, totals.input.unknownRequests)
    }

    @Test fun wholeProjectStreamsPastNoOpPagesAndAccumulatesOneDurableRun() = runBlocking {
        val sources = (0..12).map { if (it == 5) "" else "source-$it" }
        val project = create(sources.joinToString("\n"))
        val rows = store.items(project.ref, 0).items
        for (index in 0..3) store.edit(ManualTextEdit(CommandId("saved-$index"), project.ref,
            rows[index].id, index.toLong(), rows[index].fingerprint, "saved-$index"))
        store.edit(ManualTextEdit(CommandId("exclude"), project.ref, rows[4].id, 4, rows[4].fingerprint, "", TextItemStatus.EXCLUDED))
        val requested = mutableListOf<List<ItemId>>()
        val local = LocalSimulationModel()
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                requested.add(request.inputs.map { it.id }); return local.complete(request)
            }
        }
        val result = service(backend).translateProject(project.ref, identity(project), backend.configuration, 5, 4)
        assertEquals(7, result.committedItems); assertEquals(3, result.requests); assertEquals(12L, result.revision)
        assertEquals(listOf(listOf(7L, 8L), listOf(9L, 10L, 11L, 12L), listOf(13L)),
            requested.map { page -> page.map { it.value } })
        val after = store.items(project.ref, 12).items
        assertEquals("saved-0", after[0].translation)
        assertEquals(TextItemStatus.EXCLUDED, after[4].status)
        assertEquals(TextItemStatus.NONE, after[5].status)
        assertEquals(0, store.readPage(project.ref).statistics?.translatableUnprocessed)
        assertEquals(1, store.readPage(project.ref).statistics?.unprocessed)
        assertEquals("[SIMULATION] source-12", after.last().translation)
        val journal = runs.recent(project.ref).single()
        assertEquals(StoredRunState.SUCCEEDED, journal.state)
        assertEquals(7, journal.checkpoint.committedItems); assertEquals(3, journal.checkpoint.requests)
        val snapshot = ByteArrayOutputStream(); store.saveProject(project.ref) { snapshot }
        val imported = store.importProject(CommandId("whole-roundtrip")) { ByteArrayInputStream(snapshot.toByteArray()) }
        assertEquals(12L, imported.revision)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun failureOnAnotherPageKeepsTheFirstPageAndOnlyExplicitNewRunContinues() = runBlocking {
        val project = create((1..45).joinToString("\n") { "source-$it" })
        val local = LocalSimulationModel()
        var calls = 0
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                if (++calls == 2) throw AgentModelFailure(ModelFailureKind.AUTHENTICATION)
                return local.complete(request)
            }
        }
        try { service(backend).translateProject(project.ref, identity(project), backend.configuration, 0, 20); fail("Expected failure") }
        catch (failure: AgentModelFailure) { assertEquals(ModelFailureKind.AUTHENTICATION, failure.kind) }
        assertEquals(2, calls); assertEquals(20L, store.open(project.ref).revision)
        runs.recoverInterrupted(); assertEquals(2, calls)
        val next = RunIdentity(project.ref.projectId, null, RunId("next-run"), CommandId("explicit-next"))
        val result = service(local).translateProject(project.ref, next, local.configuration, 20, 20)
        assertEquals(25, result.committedItems); assertEquals(2, result.requests); assertEquals(45L, result.revision)
        assertEquals(45, store.items(project.ref, 45).items.count { it.status == TextItemStatus.PROCESSED })
        assertEquals(2, runs.recent(project.ref).size)
    }

    @Test fun noOpProjectStillChecksRevisionAndNeverCallsTheModel() = runBlocking {
        val project = create("\n\n")
        val local = LocalSimulationModel()
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse = error("Blank rows reached model")
        }
        val result = service(backend).translateProject(project.ref, identity(project), backend.configuration, 0, 1)
        assertEquals(0, result.requests); assertEquals(0, result.committedItems); assertEquals(0L, result.revision)
        val next = RunIdentity(project.ref.projectId, null, RunId("wrong-revision"), CommandId("wrong-start"))
        try { service(backend).translateProject(project.ref, next, backend.configuration, 1, 1); fail("Accepted stale revision") }
        catch (_: TextProjectException) { }
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun foregroundHostUsesOneDurableExecutionAndOnlyCommitsAfterAcknowledgement() = runBlocking {
        val project = create("one\ntwo")
        val local = LocalSimulationModel()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                calls.incrementAndGet(); return local.complete(request)
            }
        }
        val owner = SupervisorJob()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, runs),
            CoroutineScope(owner + Dispatchers.Default), service(backend))
        try {
            val handle = runner.prepare(ForegroundTranslationRequest(project.ref, identity(project), backend.configuration, 0, 0))
            assertNotNull(runtime.snapshot().activeRun)
            assertEquals(0, calls.get()); assertEquals(0L, store.open(project.ref).revision)
            assertTrue(runner.foregroundStarted(handle.token))
            val result = withTimeout(10_000) { handle.await() }
            assertEquals(2, result.committedItems); assertEquals(1, calls.get())
            assertEquals(2L, store.open(project.ref).revision)
            assertEquals(StoredRunState.SUCCEEDED, runs.recent(project.ref).single().state)
            assertNull(runtime.snapshot().activeRun)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun foregroundStopDuringNextRequestKeepsSavedBatchAndRequiresReviewWithoutReplay() = runBlocking {
        val project = create((1..21).joinToString("\n") { "source-$it" })
        val local = LocalSimulationModel()
        val enteredSecondRequest = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                if (calls.incrementAndGet() == 2) {
                    enteredSecondRequest.complete(Unit)
                    awaitCancellation()
                }
                return local.complete(request)
            }
        }
        val owner = SupervisorJob()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, runs),
            CoroutineScope(owner + Dispatchers.Default), service(backend))
        try {
            val handle = runner.prepare(ForegroundTranslationRequest(project.ref, identity(project), backend.configuration, 0, 0))
            assertTrue(runner.foregroundStarted(handle.token))
            withTimeout(10_000) { enteredSecondRequest.await() }
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { handle.await() }
            waiter.cancelAndJoin()
            assertEquals(ForegroundTranslationPhase.RUNNING, runner.state.value!!.phase)
            assertNotNull(runtime.snapshot().activeRun)
            assertTrue(runner.stop(handle.token))
            try { withTimeout(10_000) { handle.await() }; fail("Expected stopped execution") }
            catch (timeout: TimeoutCancellationException) { throw timeout }
            catch (_: CancellationException) { }
            assertNull(runtime.snapshot().activeRun)
            assertEquals(2, calls.get()); assertEquals(20L, store.open(project.ref).revision)
            assertEquals(20, store.items(project.ref, 20).items.count { it.status == TextItemStatus.PROCESSED })
            assertEquals(20, runner.state.value!!.progress!!.committedItems)
            assertEquals(ForegroundTranslationPhase.CANCELLED, runner.state.value!!.phase)
            // The in-flight request has no recorded response. Journal uncertainty is distinct
            // from the runner's cancellation, and neither attachment nor recovery replays it.
            val stored = runs.recent(project.ref).single()
            assertEquals(StoredRunState.INTERRUPTED, stored.state); assertTrue(stored.needsReview)
            assertEquals(20, stored.checkpoint.committedItems); assertEquals(2, stored.checkpoint.requests)
            runs.recoverInterrupted()
            assertNotNull(runner.attach(handle.token)); assertEquals(2, calls.get())
        } finally { owner.cancelAndJoin() }
    }

    @Test fun translatesOnlyCurrentPageAndPreservesProcessedExcludedAndBlankRows() = runBlocking {
        val project = create("keep\nexclude\n\nnew\nnext")
        val rows = store.items(project.ref, 0).items
        store.edit(ManualTextEdit(CommandId("manual"), project.ref, rows[0].id, 0, rows[0].fingerprint, "saved"))
        store.edit(ManualTextEdit(CommandId("exclude"), project.ref, rows[1].id, 1, rows[1].fingerprint, "", TextItemStatus.EXCLUDED))
        val result = service(LocalSimulationModel()).translatePage(project.ref, identity(project), LocalSimulationModel().configuration, 2, 0, 4)
        val after = store.items(project.ref, result.revision).items
        assertEquals(1, result.committedItems); assertEquals(3L, result.revision)
        assertEquals("saved", after[0].translation); assertEquals(TextItemStatus.EXCLUDED, after[1].status)
        assertEquals(TextItemStatus.NONE, after[2].status)
        assertEquals("[SIMULATION] new", after[3].translation); assertEquals(TextItemStatus.NONE, after[4].status)
        val snapshot = ByteArrayOutputStream(); store.saveProject(project.ref) { snapshot }
        val imported = store.importProject(CommandId("roundtrip")) { ByteArrayInputStream(snapshot.toByteArray()) }
        assertEquals(3L, imported.revision)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun secondBatchFailureKeepsFirstBatchAndDoesNotRetry() = runBlocking {
        val project = create((1..21).joinToString("\n") { "source-$it" })
        var calls = 0
        val local = LocalSimulationModel()
        val model = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                calls++; if (calls == 2) throw AgentModelFailure(ModelFailureKind.AUTHENTICATION)
                return local.complete(request)
            }
        }
        try { service(model).translatePage(project.ref, identity(project), model.configuration, 0, 0); fail("Expected failure") }
        catch (_: AgentModelFailure) { }
        val after = store.open(project.ref)
        assertEquals(2, calls); assertEquals(20L, after.revision)
        assertEquals(20, store.items(project.ref, after.revision).items.count { it.status == TextItemStatus.PROCESSED })
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun cancellationDuringNextRequestKeepsCommittedFactsAndReleasesRun() = runBlocking {
        val project = create((1..21).joinToString("\n") { "source-$it" })
        var calls = 0
        val local = LocalSimulationModel()
        val model = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                calls++; if (calls == 2) throw CancellationException("synthetic cancellation")
                return local.complete(request)
            }
        }
        try { service(model).translatePage(project.ref, identity(project), model.configuration, 0, 0); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(20L, store.open(project.ref).revision); assertEquals(2, calls)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun stopAfterFirstBatchPreventsAnotherBackendCallAndAllowsExplicitContinuation() = runBlocking {
        val project = create((1..21).joinToString("\n") { "source-$it" })
        var calls = 0
        val local = LocalSimulationModel()
        val model = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                calls++; return local.complete(request)
            }
        }
        val service = service(model)
        try {
            service.translatePage(project.ref, identity(project), model.configuration, 0, 0) {
                if (it.committedItems == 20) service.stop()
            }
            fail("Expected stopped runtime")
        } catch (_: RuntimeUnavailable) { }
        assertEquals(1, calls)
        assertEquals(20L, store.open(project.ref).revision)
        assertNull(runtime.snapshot().activeRun)
        val continued = service.translatePage(project.ref, identity(project), model.configuration, 20, 0)
        assertEquals(1, continued.committedItems); assertEquals(21L, continued.revision)
        assertEquals(2, calls)
    }

    @Test fun conflictingSecondItemRollsBackEntireBatchIncludingFirstReceipt() = runBlocking {
        val project = create("one\ntwo")
        val rows = store.items(project.ref, 0).items
        val lease = runtime.beginRun(identity(project), RuntimeOwner.TRANSLATION)
        val edits = listOf(
            ManualTextEdit(CommandId("first"), project.ref, rows[0].id, 0, rows[0].fingerprint, "first-result"),
            ManualTextEdit(CommandId("second"), project.ref, rows[1].id, 1, rows[0].fingerprint, "second-result"))
        try {
            try { store.commitTranslation(lease, TranslationBatch(project.ref, 0, edits)); fail("Accepted conflict") }
            catch (failure: TextProjectException) { assertEquals(TextProjectException.Reason.STALE_PAGE, failure.reason) }
        } finally { runtime.finishRun(lease) }
        assertEquals(0L, store.open(project.ref).revision)
        assertNull(store.receipt(project.ref, CommandId("first")))
        assertTrue(store.items(project.ref, 0).items.all { it.status == TextItemStatus.NONE })
    }

    @Test fun forgedRunCannotWriteEvenWithSameIdentity() = runBlocking {
        val project = create("one")
        val row = store.items(project.ref, 0).items.single()
        val identity = identity(project)
        val real = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        val forged = object : RunLease { override val identity = identity }
        try {
            try { store.commitTranslation(forged, TranslationBatch(project.ref, 0,
                listOf(ManualTextEdit(CommandId("forged"), project.ref, row.id, 0, row.fingerprint, "bad")))); fail("Accepted forged lease") }
            catch (_: RuntimeUnavailable) { }
        } finally { runtime.finishRun(real) }
        assertEquals(0L, store.open(project.ref).revision)
    }
}
