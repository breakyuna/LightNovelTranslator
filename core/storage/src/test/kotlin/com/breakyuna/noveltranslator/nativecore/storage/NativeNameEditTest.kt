package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
class NativeNameEditTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var store: NativeTextProjectStore

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        File(context.cacheDir, "native_project_snapshots").deleteRecursively()
        runtime = RuntimeCoordinator(); store = NativeTextProjectStore(context, runtime)
    }
    @After fun cleanup() = runBlocking { store.close(); runtime.close() }

    @Test fun nameEditCommitsAndReplaysWithoutReapplying() = runBlocking {
        val project = create("source")
        val item = store.item(project.ref, ItemId(1))!!
        val command = ManualNameEdit(CommandId("name"), project.ref, item.item.id, item.revision,
            item.item.fingerprint, listOf("爱丽丝"), listOf("Alice"))
        val receipt = store.editName(command)
        assertEquals(ManualEditStatus.COMMITTED, receipt.status)
        assertEquals(1L, receipt.revision)
        val after = store.item(project.ref, ItemId(1))!!.item
        assertEquals(listOf("Alice"), after.metadata.nameSource)
        assertEquals(listOf("爱丽丝"), after.metadata.nameTranslation)
        assertEquals("", after.translation)
        assertEquals(TextProjectFingerprint.item(after.id, after.row, after.source, after.translation,
            after.status, after.metadata, after.assetId, after.slot), after.fingerprint)
        assertEquals(receipt, store.checkedNameReceipt(command))
        assertEquals(receipt, store.nameReceipt(project.ref, command.commandId))
        assertEquals(receipt, store.editName(command))
        assertEquals(1L, store.open(project.ref).revision)

        store.edit(ManualTextEdit(CommandId("text"), project.ref, after.id, 1, after.fingerprint, "译文"))
        assertEquals(receipt, store.editName(command))
        assertEquals(receipt, store.checkedNameReceipt(command))
        assertEquals(2L, store.open(project.ref).revision)

        val imported = importSnapshot("import-name", project.ref)
        assertEquals(listOf("爱丽丝"), store.item(imported.ref, ItemId(1))!!.item.metadata.nameTranslation)
        assertEquals(listOf("Alice"), store.item(imported.ref, ItemId(1))!!.item.metadata.nameSource)
    }

    @Test fun nameEditPreservesOrReplacesSourceNamesAndRecomputesTheFullFingerprint() = runBlocking {
        val project = create("source")
        val asset = importNamed(project, revision = 0)
        val named = store.item(project.ref, ItemId(2))!!
        val preserved = store.editName(ManualNameEdit(CommandId("name-preserve"), project.ref, named.item.id,
            asset.revision, named.item.fingerprint, listOf("艾丽丝")))
        assertEquals(ManualEditStatus.COMMITTED, preserved.status)
        val first = store.item(project.ref, ItemId(2))!!.item
        assertEquals(listOf("Alice"), first.metadata.nameSource)
        assertEquals(listOf("艾丽丝"), first.metadata.nameTranslation)
        assertEquals(TextProjectFingerprint.item(first.id, first.row, first.source, first.translation,
            first.status, first.metadata, first.assetId, first.slot), first.fingerprint)

        val replaced = store.editName(ManualNameEdit(CommandId("name-replace"), project.ref, first.id,
            preserved.revision, first.fingerprint, listOf("爱丽丝"), listOf("Alicia")))
        assertEquals(ManualEditStatus.COMMITTED, replaced.status)
        val second = store.item(project.ref, ItemId(2))!!.item
        assertEquals(listOf("Alicia"), second.metadata.nameSource)
        assertEquals(listOf("爱丽丝"), second.metadata.nameTranslation)
        assertEquals(TextProjectFingerprint.item(second.id, second.row, second.source, second.translation,
            second.status, second.metadata, second.assetId, second.slot), second.fingerprint)
    }

    @Test fun preservingLargeSourceNamesStillValidatesTheNameCommand() = runBlocking {
        val project = create("source")
        val sourceNames = List(5) { "s".repeat(3_277) } // 16_385 chars, over the command total bound.
        val asset = store.importAsset(AssetImportPlan(CommandId("large-named"), project.ref, 0,
            RelativePath("large.txt"), ProjectAssetFormat.TXT, "Alice".toByteArray(),
            listOf(AssetImportUnit(0, 0, "Alice", nameSource = sourceNames,
                nameTranslation = listOf("爱丽丝")))))
        val named = store.item(project.ref, ItemId(2))!!
        val receipt = store.editName(ManualNameEdit(CommandId("name"), project.ref, named.item.id,
            asset.revision, named.item.fingerprint, listOf("艾丽丝")))
        assertEquals(ManualEditStatus.COMMITTED, receipt.status)
        val imported = importSnapshot("import-large-source", project.ref)
        val names = requireNotNull(store.item(imported.ref, ItemId(2))!!.item.metadata.nameSource)
        assertEquals(5, names.size)
        assertEquals(3_277, names.first().length)
    }

    @Test fun staleRevisionFingerprintUnknownItemAndPayloadMismatchAreDurableTerminals() = runBlocking {
        val project = create("source")
        val item = store.item(project.ref, ItemId(1))!!.item
        val committed = store.editName(ManualNameEdit(CommandId("name"), project.ref, item.id, 0,
            item.fingerprint, listOf("爱丽丝")))
        assertEquals(ManualEditStatus.COMMITTED, committed.status)
        val current = store.item(project.ref, ItemId(1))!!

        val staleRevision = ManualNameEdit(CommandId("stale-revision"), project.ref, current.item.id, 0,
            current.item.fingerprint, listOf("stale"))
        assertEquals(ManualEditStatus.STALE_REVISION, store.editName(staleRevision).status)
        val staleFingerprint = ManualNameEdit(CommandId("stale-fingerprint"), project.ref, current.item.id,
            1, ObjectFingerprint("0".repeat(64)), listOf("stale"))
        assertEquals(ManualEditStatus.STALE_FINGERPRINT, store.editName(staleFingerprint).status)
        val unknown = ManualNameEdit(CommandId("unknown"), project.ref, ItemId(999), 1,
            current.item.fingerprint, listOf("stale"))
        assertEquals(ManualEditStatus.UNKNOWN_ITEM, store.editName(unknown).status)
        assertEquals(1L, store.open(project.ref).revision)
        assertEquals(store.editName(staleRevision), store.checkedNameReceipt(staleRevision))
        assertEquals(store.editName(staleFingerprint), store.checkedNameReceipt(staleFingerprint))
        assertEquals(store.editName(unknown), store.checkedNameReceipt(unknown))

        val changed = ManualNameEdit(CommandId("name"), project.ref, current.item.id, 0,
            item.fingerprint, listOf("different"))
        expectConflict { store.editName(changed) }
        expectConflict { store.checkedNameReceipt(changed) }
        expectConflict { store.edit(ManualTextEdit(CommandId("name"), project.ref, current.item.id, 1,
            current.item.fingerprint, "text reuse")) }
        assertEquals(listOf("爱丽丝"), store.item(project.ref, ItemId(1))!!.item.metadata.nameTranslation)

        val text = store.edit(ManualTextEdit(CommandId("text"), project.ref, current.item.id, 1,
            current.item.fingerprint, "译文"))
        assertEquals(ManualEditStatus.COMMITTED, text.status)
        val afterText = store.item(project.ref, ItemId(1))!!.item
        expectConflict { store.editName(ManualNameEdit(CommandId("text"), project.ref, afterText.id,
            text.revision, afterText.fingerprint, listOf("reuse"))) }
    }

    @Test fun cancelledNameEditRollsBackNamesFingerprintRevisionHistoryAndReceipt() = runBlocking {
        val project = create("source")
        val item = store.item(project.ref, ItemId(1))!!.item
        val command = ManualNameEdit(CommandId("cancelled"), project.ref, item.id, 0,
            item.fingerprint, listOf("爱丽丝"))
        NativeProjectDatabase.open(file(project.ref), writable = true).use { db ->
            try {
                ProjectWriteStore.editName(db, command) { throw CancellationException("Synthetic interruption") }
                fail("Ignored cancellation")
            } catch (_: CancellationException) { }
        }
        assertEquals(0L, store.open(project.ref).revision)
        assertNull(store.nameReceipt(project.ref, command.commandId))
        assertNull(store.item(project.ref, ItemId(1))!!.item.metadata.nameTranslation)
        NativeProjectDatabase.open(file(project.ref)).use { db ->
            assertEquals(0L, NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM item_revisions"))
        }
        assertEquals(ManualEditStatus.COMMITTED, store.editName(command).status)
        assertEquals(listOf("爱丽丝"), store.item(project.ref, ItemId(1))!!.item.metadata.nameTranslation)
    }

    @Test fun unnamedTextRevisionKeepsAnExplicitNullSnapshotAfterALaterNameEdit() = runBlocking {
        val project = create("source")
        val item = store.item(project.ref, ItemId(1))!!
        store.edit(ManualTextEdit(CommandId("text"), project.ref, item.item.id, item.revision,
            item.item.fingerprint, "译文"))
        val afterText = store.item(project.ref, ItemId(1))!!
        store.editName(ManualNameEdit(CommandId("name"), project.ref, afterText.item.id, afterText.revision,
            afterText.item.fingerprint, listOf("爱丽丝")))
        NativeProjectDatabase.open(file(project.ref)).use { db ->
            assertEquals("null", db.rawQuery(
                "SELECT name_translation FROM item_revisions WHERE revision=1", null,
            ).use { assertTrue(it.moveToFirst()); it.getString(0) })
            assertEquals("""["爱丽丝"]""", db.rawQuery(
                "SELECT name_translation FROM item_revisions WHERE revision=2", null,
            ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        }
        val imported = importSnapshot("import-null-snapshot", project.ref)
        assertEquals(listOf("爱丽丝"), store.item(imported.ref, ItemId(1))!!.item.metadata.nameTranslation)
    }

    @Test fun v4MigrationKeepsUnnamedRevisionSnapshotsExplicitlyNull() = runBlocking {
        val project = create("source")
        val item = store.item(project.ref, ItemId(1))!!
        store.edit(ManualTextEdit(CommandId("text"), project.ref, item.item.id, item.revision,
            item.item.fingerprint, "译文"))
        downgradeToV4(project.ref)
        val current = store.item(project.ref, ItemId(1))!!
        store.editName(ManualNameEdit(CommandId("name"), project.ref, current.item.id, current.revision,
            current.item.fingerprint, listOf("爱丽丝")))
        NativeProjectDatabase.open(file(project.ref)).use { db ->
            val names = revisionNames(db, ItemId(1))
            assertEquals("null", names[1L])
            assertEquals("""["爱丽丝"]""", names[2L])
        }
        val imported = importSnapshot("import-null-migration", project.ref)
        assertEquals(listOf("爱丽丝"), store.item(imported.ref, ItemId(1))!!.item.metadata.nameTranslation)
    }

    @Test fun v4ProjectUpgradesToV5AndBackfillsRevisionNameSnapshots() = runBlocking {
        val project = create("source")
        val asset = importNamed(project, revision = 0)
        val named = store.item(project.ref, ItemId(2))!!
        val text = store.edit(ManualTextEdit(CommandId("text"), project.ref, named.item.id, asset.revision,
            named.item.fingerprint, "译文"))
        assertEquals(2L, text.revision)
        downgradeToV4(project.ref)
        NativeProjectDatabase.open(file(project.ref)).use { db ->
            assertEquals(4, db.version)
            db.rawQuery("PRAGMA table_info(item_revisions)", null).use {
                val names = mutableSetOf<String>()
                while (it.moveToNext()) names.add(it.getString(1))
                assertFalse("name_translation" in names)
            }
        }

        val current = store.item(project.ref, ItemId(2))!!
        val receipt = store.editName(ManualNameEdit(CommandId("name"), project.ref, current.item.id,
            current.revision, current.item.fingerprint, listOf("艾丽丝")))
        assertEquals(ManualEditStatus.COMMITTED, receipt.status)
        assertEquals(3L, receipt.revision)
        NativeProjectDatabase.open(file(project.ref)).use { db ->
            assertEquals(5, db.version)
            val snapshots = revisionNames(db, ItemId(2))
            assertEquals("""["爱丽丝"]""", snapshots[2L])
            assertEquals("""["艾丽丝"]""", snapshots[3L])
        }
        val imported = importSnapshot("import-upgraded", project.ref)
        assertEquals(listOf("艾丽丝"), store.item(imported.ref, ItemId(2))!!.item.metadata.nameTranslation)
    }

    @Test fun v5ImportFallsBackForLegacyTextRevisionButRejectsTamperedOrMissingNameHistory() = runBlocking {
        val project = create("source")
        val asset = importNamed(project, revision = 0)
        val named = store.item(project.ref, ItemId(2))!!
        store.edit(ManualTextEdit(CommandId("text"), project.ref, named.item.id, asset.revision,
            named.item.fingerprint, "译文"))
        // A v5 row whose snapshot is missing still validates when current names are the names
        // that revision used, matching the pre-v5 reconstruction rule.
        val textSnapshot = snapshot(project.ref)
        val nullTextSnapshot = tampered(textSnapshot) {
            it.execSQL("UPDATE item_revisions SET name_translation=NULL WHERE revision=2")
        }
        val fallback = importBytes("import-fallback", nullTextSnapshot)
        assertEquals(listOf("爱丽丝"), store.item(fallback.ref, ItemId(2))!!.item.metadata.nameTranslation)

        val current = store.item(project.ref, ItemId(2))!!
        store.editName(ManualNameEdit(CommandId("name"), project.ref, current.item.id, current.revision,
            current.item.fingerprint, listOf("艾丽丝")))
        val namedSnapshot = snapshot(project.ref)

        val tamperedHistory = tampered(namedSnapshot) {
            it.execSQL("UPDATE item_revisions SET name_translation='[\"evil\"]' WHERE revision=2")
        }
        expect(TextProjectException.Reason.CORRUPT_PROJECT) {
            store.importProject(CommandId("import-tampered")) { ByteArrayInputStream(tamperedHistory) }
        }
        val missingNameSnapshot = tampered(namedSnapshot) {
            it.execSQL("UPDATE item_revisions SET name_translation=NULL WHERE revision=3")
        }
        expect(TextProjectException.Reason.CORRUPT_PROJECT) {
            store.importProject(CommandId("import-missing-name")) { ByteArrayInputStream(missingNameSnapshot) }
        }
        val staleFallback = tampered(namedSnapshot) {
            it.execSQL("UPDATE item_revisions SET name_translation=NULL WHERE revision=2")
        }
        expect(TextProjectException.Reason.CORRUPT_PROJECT) {
            store.importProject(CommandId("import-stale-fallback")) { ByteArrayInputStream(staleFallback) }
        }
    }

    @Test fun archivedNameHistorySurvivesV4MigrationAndSnapshotImport() = runBlocking {
        val project = create("keep")
        val asset = importNamed(project, revision = 0)
        val named = store.item(project.ref, ItemId(2))!!
        store.edit(ManualTextEdit(CommandId("text"), project.ref, named.item.id, asset.revision,
            named.item.fingerprint, "译文"))
        downgradeToV4(project.ref)
        val removed = store.removeAsset(CommandId("remove"), project.ref, 2, requireNotNull(asset.assetId))
        assertEquals(AssetMutationStatus.COMMITTED, removed.status)
        val active = store.item(project.ref, ItemId(1))!!
        val receipt = store.editName(ManualNameEdit(CommandId("active-name"), project.ref, active.item.id,
            removed.revision, active.item.fingerprint, listOf("主角")))
        assertEquals(ManualEditStatus.COMMITTED, receipt.status)
        NativeProjectDatabase.open(file(project.ref)).use { db ->
            assertEquals(5, db.version)
            assertEquals("""["爱丽丝"]""", revisionNames(db, ItemId(2))[2L])
            assertEquals("""["爱丽丝"]""", db.rawQuery(
                "SELECT name_translation FROM archived_items WHERE id=2", null,
            ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        }
        val imported = importSnapshot("import-archived", project.ref)
        assertEquals(1, store.open(imported.ref).itemCount)
        NativeProjectDatabase.open(file(imported.ref)).use { db ->
            assertEquals(1L, NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM archived_items"))
            assertEquals("""["爱丽丝"]""", db.rawQuery(
                "SELECT name_translation FROM archived_items WHERE id=2", null,
            ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        }
    }

    @Test fun archivedItemKeepsItsPerRevisionNamesAfterANameEdit() = runBlocking {
        val project = create("keep")
        val asset = importNamed(project, revision = 0)
        val named = store.item(project.ref, ItemId(2))!!
        store.edit(ManualTextEdit(CommandId("text"), project.ref, named.item.id, asset.revision,
            named.item.fingerprint, "译文"))
        val afterText = store.item(project.ref, ItemId(2))!!
        store.editName(ManualNameEdit(CommandId("name"), project.ref, afterText.item.id, afterText.revision,
            afterText.item.fingerprint, listOf("艾丽丝")))
        val removed = store.removeAsset(CommandId("remove"), project.ref, 3, requireNotNull(asset.assetId))
        assertEquals(AssetMutationStatus.COMMITTED, removed.status)
        NativeProjectDatabase.open(file(project.ref)).use { db ->
            val snapshots = revisionNames(db, ItemId(2))
            assertEquals("""["爱丽丝"]""", snapshots[2L])
            assertEquals("""["艾丽丝"]""", snapshots[3L])
        }
        val imported = importSnapshot("import-archived-names", project.ref)
        NativeProjectDatabase.open(file(imported.ref)).use { db ->
            val snapshots = revisionNames(db, ItemId(2))
            assertEquals("""["爱丽丝"]""", snapshots[2L])
            assertEquals("""["艾丽丝"]""", snapshots[3L])
            assertEquals("""["艾丽丝"]""", db.rawQuery(
                "SELECT name_translation FROM archived_items WHERE id=2", null,
            ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        }
    }

    @Test fun v4SnapshotStillImportsAndALaterNameEditUpgradesIt() = runBlocking {
        val project = create("source")
        val asset = importNamed(project, revision = 0)
        val named = store.item(project.ref, ItemId(2))!!
        store.edit(ManualTextEdit(CommandId("text"), project.ref, named.item.id, asset.revision,
            named.item.fingerprint, "译文"))
        val v5 = snapshot(project.ref)
        val v4File = File(context.cacheDir, "v4-snapshot.lnt").apply { writeBytes(v5) }
        downgradeFile(v4File)
        val imported = store.importProject(CommandId("import-v4")) { v4File.inputStream() }
        NativeProjectDatabase.open(file(imported.ref)).use { db ->
            assertEquals(4, db.version)
            assertEquals(2L, db.rawQuery("SELECT revision FROM project WHERE singleton=1", null)
                .use { assertTrue(it.moveToFirst()); it.getLong(0) })
        }
        val item = store.item(imported.ref, ItemId(2))!!
        assertEquals(listOf("爱丽丝"), item.item.metadata.nameTranslation)
        val upgraded = store.editName(ManualNameEdit(CommandId("name-after-v4"), imported.ref, item.item.id,
            item.revision, item.item.fingerprint, listOf("艾丽丝")))
        assertEquals(ManualEditStatus.COMMITTED, upgraded.status)
        NativeProjectDatabase.open(file(imported.ref)).use { db ->
            assertEquals(5, db.version)
            assertEquals("""["爱丽丝"]""", revisionNames(db, ItemId(2))[2L])
            assertEquals("""["艾丽丝"]""", revisionNames(db, ItemId(2))[3L])
        }
        importSnapshot("import-after-upgrade", imported.ref)
    }

    @Test fun v5ImportRejectsWrongNameColumnTypesAndOversizedSnapshots() = runBlocking {
        val project = create("source")
        val asset = importNamed(project, revision = 0)
        val named = store.item(project.ref, ItemId(2))!!
        store.edit(ManualTextEdit(CommandId("text"), project.ref, named.item.id, asset.revision,
            named.item.fingerprint, "译文"))
        val original = snapshot(project.ref)
        val wrongType = tampered(original) {
            it.execSQL("UPDATE item_revisions SET name_source = x'00' WHERE revision=2")
        }
        expect(TextProjectException.Reason.CORRUPT_PROJECT) {
            store.importProject(CommandId("import-type")) { ByteArrayInputStream(wrongType) }
        }
        val oversized = tampered(original) {
            it.execSQL("UPDATE item_revisions SET name_translation=? WHERE revision=2",
                arrayOf("[\"" + "a".repeat(131_073) + "\"]"))
        }
        expect(TextProjectException.Reason.LIMIT_EXCEEDED) {
            store.importProject(CommandId("import-oversized")) { ByteArrayInputStream(oversized) }
        }
    }

    @Test fun activeRunAndForeignGenerationCannotAcquireNameWriteAuthority() = runBlocking {
        val project = create("source")
        val item = store.item(project.ref, ItemId(1))!!
        val command = ManualNameEdit(CommandId("name"), project.ref, item.item.id, 0,
            item.item.fingerprint, listOf("爱丽丝"))
        val chat = ChatId("chat")
        runtime.selectChat(ChatIdentity(project.ref.projectId, chat))
        val run = runtime.beginRun(RunIdentity(project.ref.projectId, chat, RunId("run"), CommandId("run")),
            RuntimeOwner.AGENT)
        try { try { store.editName(command); fail("Expected runtime exclusion") } catch (_: RuntimeUnavailable) { } }
        finally { runtime.finishRun(run) }
        assertNull(store.nameReceipt(project.ref, command.commandId))
        val foreign = ManualNameEdit(CommandId("foreign"), project.ref.copy(generation = RecoveryGeneration(2)),
            item.item.id, 0, item.item.fingerprint, listOf("爱丽丝"))
        expect(TextProjectException.Reason.WRONG_IDENTITY) { store.editName(foreign) }
        assertEquals(ManualEditStatus.COMMITTED, store.editName(command).status)
    }

    private suspend fun create(text: String) = store.createTxt(CommandId("create"), "Example",
        RelativePath("source.txt"), text.toByteArray())

    private suspend fun importNamed(project: TextProjectSummary, revision: Long,
        command: String = "named-asset"): AssetMutationReceipt =
        store.importAsset(AssetImportPlan(CommandId(command), project.ref, revision, RelativePath("named.txt"),
            ProjectAssetFormat.TXT, "Alice".toByteArray(),
            listOf(AssetImportUnit(0, 0, "Alice", nameSource = listOf("Alice"),
                nameTranslation = listOf("爱丽丝")))))

    private suspend fun snapshot(ref: LocalProjectRef): ByteArray {
        val output = ByteArrayOutputStream(); store.saveProject(ref) { output }; return output.toByteArray()
    }

    private suspend fun importSnapshot(command: String, ref: LocalProjectRef): TextProjectSummary =
        importBytes(command, snapshot(ref))

    private suspend fun importBytes(command: String, bytes: ByteArray): TextProjectSummary =
        store.importProject(CommandId(command)) { ByteArrayInputStream(bytes) }

    private fun tampered(bytes: ByteArray, change: (SQLiteDatabase) -> Unit): ByteArray {
        val file = File.createTempFile("tampered-", ".lnt", context.cacheDir).apply { writeBytes(bytes) }
        NativeProjectDatabase.open(file, writable = true).use(change)
        return file.readBytes()
    }

    private fun file(ref: LocalProjectRef) =
        File(context.filesDir, "native_projects/${ref.instanceId.value}/project.lnt")

    private fun downgradeToV4(ref: LocalProjectRef) = downgradeFile(file(ref))

    /** Reconstructs the shipped v4 item_revisions shape to exercise the 4 -> 5 migration. */
    private fun downgradeFile(file: File) {
        NativeProjectDatabase.open(file, writable = true).use { db ->
            db.setForeignKeyConstraintsEnabled(false)
            try {
                NativeProjectDatabase.transaction(db) {
                    db.execSQL("ALTER TABLE item_revisions RENAME TO revisions_v5")
                    db.execSQL("""CREATE TABLE item_revisions (
                        revision INTEGER PRIMARY KEY, item_id INTEGER NOT NULL,
                        previous_fingerprint TEXT NOT NULL, fingerprint TEXT NOT NULL, translation TEXT NOT NULL,
                        status TEXT NOT NULL, instance_id TEXT NOT NULL, generation INTEGER NOT NULL,
                        command_id TEXT NOT NULL, committed_at INTEGER NOT NULL)""")
                    db.execSQL("""INSERT INTO item_revisions
                        SELECT revision,item_id,previous_fingerprint,fingerprint,translation,status,
                        instance_id,generation,command_id,committed_at FROM revisions_v5""")
                    db.execSQL("DROP TABLE revisions_v5")
                    db.version = 4
                }
            } finally { db.setForeignKeyConstraintsEnabled(true) }
            NativeProjectDatabase.validateSchema(db)
        }
    }

    private fun revisionNames(db: SQLiteDatabase, id: ItemId): Map<Long, String?> {
        val values = mutableMapOf<Long, String?>()
        db.rawQuery("SELECT revision,name_translation FROM item_revisions WHERE item_id=? ORDER BY revision",
            arrayOf(id.value.toString())).use {
            while (it.moveToNext()) values[it.getLong(0)] = it.getString(1)
        }
        return values
    }

    private suspend fun expectConflict(block: suspend () -> Any?) {
        expect(TextProjectException.Reason.COMMAND_CONFLICT) { block() }
    }

    private suspend fun expect(reason: TextProjectException.Reason, block: suspend () -> Any?) {
        try { block(); fail("Expected $reason") } catch (error: TextProjectException) { assertEquals(reason, error.reason) }
    }
}
