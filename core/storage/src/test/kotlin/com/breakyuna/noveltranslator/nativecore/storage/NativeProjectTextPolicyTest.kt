package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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
class NativeProjectTextPolicyTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var store: NativeTextProjectStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator(); store = NativeTextProjectStore(context, runtime)
    }
    @After fun cleanup() = runBlocking { store.close(); runtime.close() }
    private suspend fun project(text: String = "source") = store.createTxt(CommandId("create"), "test", RelativePath("test.txt"), text.toByteArray())
    private fun file(ref: LocalProjectRef) = File(context.filesDir, "native_projects/${ref.instanceId.value}/project.lnt")
    private suspend fun save(ref: LocalProjectRef, policy: ProjectTextPolicy, id: String = "policy"): PolicyEditReceipt {
        val before = store.textPolicy(ref)
        return store.editTextPolicy(ProjectPolicyEdit(CommandId(id), ref, before.revision, before.fingerprint, policy))
    }
    private fun downgrade(ref: LocalProjectRef) {
        NativeProjectDatabase.open(file(ref), writable = true).use { db ->
            db.setForeignKeyConstraintsEnabled(false)
            try {
                db.beginTransaction()
                try {
                    for (table in listOf("archived_items", "project_settings", "asset_receipts", "asset_revisions",
                        "text_policy", "policy_revisions", "policy_receipts")) db.execSQL("DROP TABLE $table")
                    db.execSQL("""CREATE TABLE assets_v1 (
                        id INTEGER PRIMARY KEY CHECK(id = 1), path TEXT NOT NULL UNIQUE,
                        file_type TEXT NOT NULL CHECK(file_type = 'TXT'), codec TEXT NOT NULL CHECK(codec = 'raw-v1'),
                        original_size INTEGER NOT NULL CHECK(original_size >= 0), sha256 TEXT NOT NULL)""")
                    db.execSQL("INSERT INTO assets_v1 SELECT id,path,file_type,codec,original_size,sha256 FROM assets WHERE id=1")
                    db.execSQL("DROP TABLE assets")
                    db.execSQL("ALTER TABLE assets_v1 RENAME TO assets")
                    db.execSQL("""CREATE TABLE items_v1 (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, asset_id INTEGER NOT NULL REFERENCES assets(id),
                        row_index INTEGER NOT NULL CHECK(row_index >= 0), source TEXT NOT NULL,
                        translation TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('NONE','PROCESSED','EXCLUDED')),
                        fingerprint TEXT NOT NULL, UNIQUE(asset_id, row_index))""")
                    db.execSQL("INSERT INTO items_v1(id,asset_id,row_index,source,translation,status,fingerprint) " +
                        "SELECT id,asset_id,row_index,source,translation,status,fingerprint FROM items")
                    db.execSQL("DROP TABLE items")
                    db.execSQL("ALTER TABLE items_v1 RENAME TO items")
                    val revisionsSql = db.rawQuery("SELECT sql FROM sqlite_master WHERE name='item_revisions'", null).use {
                        check(it.moveToFirst()); it.getString(0)
                    }
                    require(revisionsSql.contains("name_translation")) { "Expected the v5 history shape" }
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
                    db.execSQL("DROP TABLE IF EXISTS approved_item_receipts")
                    db.execSQL("DROP TABLE IF EXISTS approved_pdf_page_receipts")
                    db.version = 1
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } finally { db.setForeignKeyConstraintsEnabled(true) }
        }
    }
    private fun policy(prompt: String = "project prompt") = ProjectTextPolicy(listOf(ProjectTextRule("term", TextRuleKind.TERM, "source", "target")), prompt, true)

    @Test fun mixedItemAndPolicyTimelineSurvivesSnapshotImportAndCopy() = runBlocking {
        val project = project()
        val item = store.items(project.ref, 0).items.single()
        store.edit(ManualTextEdit(CommandId("before"), project.ref, item.id, 0, item.fingerprint, "first"))
        val receipt = save(project.ref, policy())
        assertEquals(2L, receipt.revision)
        val current = store.item(project.ref, item.id)!!
        store.edit(ManualTextEdit(CommandId("after"), project.ref, item.id, 2, current.item.fingerprint, "second"))
        val snapshot = ByteArrayOutputStream(); store.saveProject(project.ref) { snapshot }
        val imported = store.importProject(CommandId("import")) { ByteArrayInputStream(snapshot.toByteArray()) }
        assertEquals(3L, imported.revision)
        assertEquals(policy().fingerprint(), store.textPolicy(imported.ref).fingerprint)
        assertEquals("second", store.items(imported.ref, 3).items.single().translation)
        val copy = store.copyProject(CommandId("copy"), imported.ref)
        assertNotEquals(copy.ref.instanceId, imported.ref.instanceId)
        assertEquals(policy().fingerprint(), store.textPolicy(copy.ref).fingerprint)
    }
    @Test fun v1ReadsAndExportsStayV1UntilFirstPolicyWriteAndPreserveItemHistory() = runBlocking {
        val project = project()
        val item = store.items(project.ref, 0).items.single()
        store.edit(ManualTextEdit(CommandId("item"), project.ref, item.id, 0, item.fingerprint, "translated"))
        downgrade(project.ref)
        assertEquals(ProjectTextPolicy().fingerprint(), store.textPolicy(project.ref).fingerprint)
        NativeProjectDatabase.open(file(project.ref)).use { assertEquals(1, it.version) }
        val snapshot = ByteArrayOutputStream(); store.saveProject(project.ref) { snapshot }
        val imported = store.importProject(CommandId("old")) { ByteArrayInputStream(snapshot.toByteArray()) }
        NativeProjectDatabase.open(file(imported.ref)).use { assertEquals(1, it.version) }
        assertEquals(2L, save(imported.ref, policy()).revision)
        NativeProjectDatabase.open(file(imported.ref)).use {
            assertEquals(2, it.version); assertEquals(1L, NativeProjectDatabase.scalar(it, "SELECT COUNT(*) FROM item_revisions"))
        }
        assertEquals("translated", store.items(imported.ref, 2).items.single().translation)
    }
    @Test fun cancelledFirstPolicyTransactionRollsBackMigrationAndAllReceipts() = runBlocking {
        val project = project(); downgrade(project.ref)
        val before = store.textPolicy(project.ref)
        val write = runtime.beginProjectWrite()
        try {
            NativeProjectDatabase.open(file(project.ref), writable = true).use { db ->
                try {
                    ProjectWriteStore.editPolicy(db, ProjectPolicyEdit(CommandId("cancel"), project.ref, 0, before.fingerprint, policy())) {
                        throw CancellationException("fixture")
                    }
                    fail("Ignored cancellation")
                } catch (_: CancellationException) { }
                assertEquals(1, db.version)
                assertEquals(0L, NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE name='text_policy'"))
            }
        } finally { runtime.endProjectWrite(write) }
        assertEquals(0L, store.open(project.ref).revision)
        assertEquals(ProjectTextPolicy().fingerprint(), store.textPolicy(project.ref).fingerprint)
    }
    @Test fun committedAndRejectedCommandsReplayTheirOriginalResultsWithoutOverwritingNewPolicy() = runBlocking {
        val project = project(); val initial = store.textPolicy(project.ref)
        val command = ProjectPolicyEdit(CommandId("once"), project.ref, 0, initial.fingerprint, policy())
        val first = store.editTextPolicy(command)
        assertEquals(first, store.textPolicyReceipt(command))
        save(project.ref, policy("new prompt"), "second")
        assertEquals(first, store.editTextPolicy(command))
        assertEquals("new prompt", store.textPolicy(project.ref).policy.prompt)
        val rejected = ProjectPolicyEdit(CommandId("rejected"), project.ref, 0, initial.fingerprint, policy())
        val old = store.editTextPolicy(rejected)
        assertEquals(PolicyEditStatus.STALE_REVISION, old.status)
        save(project.ref, policy("latest"), "third")
        assertEquals(old, store.editTextPolicy(rejected))
        assertEquals(old, store.textPolicyReceipt(rejected))
        try { store.editTextPolicy(ProjectPolicyEdit(CommandId("once"), project.ref, 0, initial.fingerprint, policy("changed"))); fail("Reused command with new payload") }
        catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.COMMAND_CONFLICT, error.reason) }
    }
    @Test fun policyAndItemCommandsShareTheCommandNamespaceAndRunsBlockPolicyWrites() = runBlocking {
        val project = project(); save(project.ref, policy(), "same")
        val item = store.items(project.ref, 1).items.single()
        try { store.edit(ManualTextEdit(CommandId("same"), project.ref, item.id, 1, item.fingerprint, "bad")); fail("Reused policy command") }
        catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.COMMAND_CONFLICT, error.reason) }
        runtime.selectChat(ChatIdentity(project.ref.projectId, ChatId("workspace")))
        val lease = runtime.beginRun(RunIdentity(project.ref.projectId, null, RunId("run"), CommandId("start")), RuntimeOwner.TRANSLATION)
        try { try { save(project.ref, policy("busy"), "busy"); fail("Wrote rules during a run") } catch (_: RuntimeUnavailable) { } }
        finally { runtime.finishRun(lease) }
        assertEquals(1L, store.open(project.ref).revision)
    }
    @Test fun damagedPolicyHistoryIsRejectedWithoutPublishingAnImportedInstance() = runBlocking {
        val project = project(); save(project.ref, policy())
        NativeProjectDatabase.open(file(project.ref), writable = true).use {
            it.execSQL("UPDATE policy_revisions SET previous_fingerprint=?", arrayOf("a".repeat(64)))
        }
        val output = ByteArrayOutputStream(); store.saveProject(project.ref) { output }
        try { store.importProject(CommandId("damaged")) { ByteArrayInputStream(output.toByteArray()) }; fail("Imported broken policy history") }
        catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.CORRUPT_PROJECT, error.reason) }
        assertEquals(1, store.registeredProjects().size)
    }
    @Test fun remoteTranslationConsumesTheSavedSnapshotAndRestoresProtectedText() = runBlocking {
        val project = project("Alice <tag> source")
        val policy = ProjectTextPolicy(listOf(ProjectTextRule("term", TextRuleKind.TERM, "Alice", "艾莉丝"),
            ProjectTextRule("protect", TextRuleKind.PROTECT, "<[^>]+>", regex = true),
            ProjectTextRule("pre", TextRuleKind.PRE_REPLACE, "source", "before"),
            ProjectTextRule("post", TextRuleKind.POST_REPLACE, "before", "after")), "project instructions", true)
        save(project.ref, policy)
        runtime.selectChat(ChatIdentity(project.ref.projectId, ChatId("workspace")))
        val config = ModelConfigurationRef("remote", 1)
        val backend = object : ConfiguredTranslationBackend {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                assertEquals(policy.fingerprint(), request.policyFingerprint)
                assertEquals("project instructions", request.projectInstructions)
                assertEquals("艾莉丝", request.inputs.single().terms.single().target)
                assertFalse(request.inputs.single().source.contains("<tag>"))
                return TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, it.source) }, unknownRequestUsage(), ModelStopReason.COMPLETE)
            }
        }
        val runs = NativeRunStore(context, store, runtime)
        try {
            val service = TextTranslationService(runtime, store, store, NativeModelGateway(runtime, LocalSimulationModel(), remoteTranslation = backend), DurableRunExecutor(runtime, runs))
            val result = service.translateProject(project.ref, RunIdentity(project.ref.projectId, null, RunId("run"), CommandId("start")), config,
                1, options = RemoteTranslationOptions("en"))
            assertEquals(1, result.committedItems); assertEquals(2L, result.revision)
            assertEquals("Alice <tag> after", store.items(project.ref, 2).items.single().translation)
        } finally { runs.close() }
    }
}
