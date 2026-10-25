package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import java.io.File
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
class NativePdfPageSettingsTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var store: NativeTextProjectStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator(); store = NativeTextProjectStore(context, runtime)
    }
    @After fun cleanup() = runBlocking { store.close(); runtime.close() }

    @Test fun pageSettingsAreRevisionCheckedReplayableAndSurviveValidatedCopyWithoutChangingItems() = runBlocking {
        val project = store.createTxt(CommandId("create"), "test", RelativePath("test.txt"), "text".toByteArray())
        store.importAsset(AssetImportPlan(CommandId("pdf"), project.ref, 0, RelativePath("book.pdf"),
            ProjectAssetFormat.PDF, "%PDF-fixture".toByteArray(), (1..3).map { page ->
                AssetImportUnit(page - 1, page, "page $page", tag = "page:$page", extraField =
                    """{"v":1,"page":$page,"decision":"translate","digest":"${"a".repeat(64)}"}""")
            }))
        val snapshot = store.open(project.ref)
        val asset = snapshot.assets.single { it.format == ProjectAssetFormat.PDF }
        val before = store.items(project.ref, snapshot.revision, 0, 100).items
        val preview = store.pdfPage(project.ref, snapshot.revision, asset, 2)
        assertEquals("page 2", preview.source)
        assertEquals(asset.id, preview.assetId)
        try {
            store.pdfPage(project.ref, snapshot.revision, asset.copy(sha256 = "b".repeat(64)), 2)
            fail("Accepted another source identity")
        } catch (failure: TextProjectException) { assertEquals(TextProjectException.Reason.STALE_PAGE, failure.reason) }
        val settings = requireNotNull(snapshot.settings).withPdfPages(listOf(PdfAssetPagePolicy(asset.id, asset.sha256, "kot")))
        val saved = store.editProjectSettings(CommandId("pages"), project.ref, snapshot.revision, settings)
        assertEquals(AssetMutationStatus.COMMITTED, saved.status)
        try {
            store.pdfPage(project.ref, snapshot.revision, asset, 2)
            fail("Loaded a stale page revision")
        } catch (failure: TextProjectException) { assertEquals(TextProjectException.Reason.STALE_PAGE, failure.reason) }
        assertEquals(saved, store.editProjectSettings(CommandId("pages"), project.ref, snapshot.revision, settings))
        assertEquals(AssetMutationStatus.STALE_REVISION,
            store.editProjectSettings(CommandId("stale"), project.ref, snapshot.revision, ProjectSettings()).status)
        val after = store.items(project.ref, saved.revision, 0, 100).items
        assertEquals(before.map { it.fingerprint }, after.map { it.fingerprint })
        val copy = store.copyProject(CommandId("copy"), project.ref)
        assertEquals(settings.fingerprint(), store.projectSettings(copy.ref).fingerprint())
        val filter = ProjectPageTranslationPolicy(store.projectSettings(copy.ref), store.assets(copy.ref))
        assertEquals(listOf(3), store.items(copy.ref, saved.revision, 0, 100).items
            .filter { it.assetId == asset.id && filter.accepts(it) }.map { it.row })
        val tasks = NativeTranslationTaskStore(context, store, runtime)
        val run = RunIdentity(project.ref.projectId, null, RunId("translate"), CommandId("translate"))
        val lease = runtime.beginRunSelectingChat(run, RuntimeOwner.TRANSLATION,
            ChatIdentity(project.ref.projectId, ChatId("translation-workspace")))
        try {
            tasks.open(lease, TranslationTaskSpec(project.ref, run, ModelConfigurationRef("fixture", 1),
                saved.revision, 0, 100, true, RemoteTranslationOptions("en")))
            val remaining = tasks.remaining(lease, project.ref, after)
            assertEquals(after.filter { it.assetId != asset.id || it.row == 3 }.map { it.id }.toSet(), remaining)
        } finally { runtime.finishRun(lease); tasks.close() }
    }
}
