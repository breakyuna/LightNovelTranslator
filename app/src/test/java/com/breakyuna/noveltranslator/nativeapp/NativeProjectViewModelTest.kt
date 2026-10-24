package com.breakyuna.noveltranslator.nativeapp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.TranslatorApplication
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real native storage and lifecycle scopes; no platform Service or device recreation evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeProjectViewModelTest {
    private lateinit var app: TranslatorApplication
    private lateinit var graph: NativeAppGraph
    private val owner = SupervisorJob()
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase("lnt_native_app.db")
        File(app.filesDir, "native_projects").deleteRecursively()
        graph = app.nativeGraph
    }

    @After fun cleanup() = runBlocking {
        try {
            stores.forEach { it.clear() }
            owner.cancelAndJoin()
            graph.agentStore.close(); graph.modelSettings.close(); graph.translationAudit.close(); graph.runStore.close(); graph.projects.close()
            graph.runtime.close()
        } finally { Dispatchers.resetMain() }
    }

    private fun model(saved: SavedStateHandle, projects: TextProjectPort, runner: ForegroundTranslationRunner): Pair<ViewModelStore, NativeProjectViewModel> {
        val store = ViewModelStore().also(stores::add)
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NativeProjectViewModel(app, saved, projects, runner) as T
        })
        return store to provider[NativeProjectViewModel::class.java]
    }

    private fun request(project: TextProjectSummary, after: Long = 0) = ForegroundTranslationRequest(project.ref,
        RunIdentity(project.ref.projectId, null, RunId("run"), CommandId("simulate")), graph.simulationConfiguration,
        project.revision, after)

    @Test fun qualitySelectionBatchLeavesOtherRowsUntouchedAndInvalidatesTheOldIndex() = runBlocking {
        withTimeout(20_000) {
            val project = graph.projects.createTxt(CommandId("quality-batch-ui"), "fixture", RelativePath("source.txt"),
                (1..146).joinToString("\n") { "row-$it" }.toByteArray())
            val first = graph.projects.readPage(project.ref).items.first()
            graph.projects.edit(ManualTextEdit(CommandId("processed-first"), project.ref, first.id, 0, first.fingerprint, "saved"))
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            model.scanQuality(TextProofreadingOptions()); model.state.first { !it.busy }
            model.filterQuality(TextQualityFilter(TextQualityOutcome.NOT_EVALUATED)); model.state.first { !it.busy }
            assertEquals(145, model.state.value.qualityView!!.ids.size)
            model.prepareQueryBatch(TextBatchChange.EXCLUDE); model.state.first { !it.busy }
            val fixed = requireNotNull(model.state.value.manualBatch)
            assertEquals(145, fixed.targets.size)
            assertTrue(fixed.targets.none { it.id == first.id })
            model.confirmManualBatch(fixed); model.state.first { !it.busy }
            assertTrue(model.state.value.qualityInvalidated)
            assertFalse(model.state.value.qualityCurrent)
            val page = graph.projects.readPage(project.ref)
            assertEquals(145, page.statistics!!.excluded)
            assertEquals(1, page.statistics!!.processed)
            assertEquals("saved", page.items.first().translation)
            model.prepareQueryBatch(TextBatchChange.CLEAR_AND_RESET); model.state.first { !it.busy }
            assertNull(model.state.value.manualBatch)
            assertEquals(146L, graph.projects.open(project.ref).revision)
        }
    }

    @Test fun wholeQueryPreviewRestoresFromPrivateFileAndRequiresExplicitConfirmation() = runBlocking {
        withTimeout(20_000) {
            val project = graph.projects.createTxt(CommandId("query-batch-ui"), "fixture", RelativePath("source.txt"),
                (1..145).joinToString("\n") { "target-$it" }.toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (previousOwner, original) = model(saved, graph.projects, graph.backgroundTranslations)
            original.state.first { !it.busy }
            original.queryProofreading(TextItemQuery(text = "target-")); original.state.first { !it.busy }
            original.prepareQueryBatch(TextBatchChange.EXCLUDE); original.state.first { !it.busy }
            assertEquals(145, original.state.value.manualBatch!!.targets.size)
            assertTrue(original.state.value.manualBatchQuery)
            assertNull(saved.get<ArrayList<String>>("manual_batch"))
            assertNotNull(saved.get<String>("manual_batch_disk"))
            assertEquals(0L, graph.projects.open(project.ref).revision)
            previousOwner.clear()
            val (_, restored) = model(saved, graph.projects, graph.backgroundTranslations)
            restored.state.first { !it.busy }
            val fixed = requireNotNull(restored.state.value.manualBatch)
            assertEquals(145, fixed.targets.size)
            assertTrue(restored.state.value.manualBatchQuery)
            assertEquals(0L, graph.projects.open(project.ref).revision)
            restored.confirmManualBatch(fixed); restored.state.first { !it.busy }
            assertNull(restored.state.value.manualBatch)
            assertNull(saved.get<String>("manual_batch_disk"))
            assertEquals(145L, graph.projects.open(project.ref).revision)
            assertEquals(145, graph.projects.readPage(project.ref).statistics!!.excluded)
        }
    }

    @Test fun assetFilterRestoresForTheSameProjectAndDoesNotWidenAfterRemoval() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("asset-filter-create"), "fixture", RelativePath("first.txt"), "first".toByteArray())
            graph.projects.importAsset(AssetImportPlan(CommandId("asset-filter-import"), project.ref, 0,
                RelativePath("second.txt"), ProjectAssetFormat.TXT, "second".toByteArray(), listOf(AssetImportUnit(0, 0, "second"))))
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (owner, firstModel) = model(saved, graph.projects, graph.backgroundTranslations)
            firstModel.state.first { !it.busy }
            val asset = firstModel.state.value.project!!.assets.last().id
            val query = TextItemQuery(assetId = asset)
            firstModel.queryProofreading(query); firstModel.state.first { !it.busy }
            assertEquals(1, firstModel.state.value.matchingItems)
            assertEquals(1, firstModel.state.value.matchingStatistics!!.total)
            assertEquals(asset, firstModel.state.value.items.single().assetId)
            owner.clear()
            val (_, restored) = model(saved, graph.projects, graph.backgroundTranslations)
            restored.state.first { !it.busy }
            assertEquals(query, restored.state.value.proofreadingQuery)
            restored.mutateAsset(NativeAssetMutation(project.ref, 1, asset, NativeAssetChange.REMOVE, CommandId("remove-filtered")))
            restored.state.first { !it.busy }
            assertTrue(restored.state.value.items.isEmpty())
            assertEquals(query, restored.state.value.proofreadingQuery)
            assertEquals(0, restored.state.value.matchingItems)
            assertEquals(1, restored.state.value.project!!.itemCount)
        }
    }

    @Test fun assetSwitchRefreshesTheStoredMembershipWithoutChangingText() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("switch-create"), "fixture", RelativePath("source.txt"), "source".toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            val asset = model.state.value.project!!.assets.single()
            model.mutateAsset(NativeAssetMutation(project.ref, 0, asset.id, NativeAssetChange.DISABLE, CommandId("disable-ui")))
            model.state.first { !it.busy }
            assertFalse(model.state.value.project!!.assets.single().enabled)
            assertEquals("source", graph.projects.readPage(project.ref).items.single().source)
            model.mutateAsset(NativeAssetMutation(project.ref, 1, asset.id, NativeAssetChange.ENABLE, CommandId("enable-ui")))
            model.state.first { !it.busy }
            assertTrue(model.state.value.project!!.assets.single().enabled)
            assertEquals(2L, model.state.value.project!!.revision)
        }
    }

    @Test fun projectSettingsPersistAndFreezeExportOptionsBeforeThePicker() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("settings-ui-create"), "fixture", RelativePath("source.txt"), "source".toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            val settings = ProjectSettings("ja", "de", bilingualDeduplicateSame = false, writeTranslatedNames = true)
            model.saveProjectSettings(NativeProjectSettingsChange(project.ref, 0, CommandId("save-settings"), settings))
            model.state.first { !it.busy }
            assertEquals(settings.fingerprint(), graph.projects.projectSettings(project.ref).fingerprint())
            assertEquals(settings.fingerprint(), model.state.value.project!!.settings!!.fingerprint())
            assertEquals(1L, model.state.value.project!!.revision)
            assertEquals(settings.targetLanguage, model.state.value.project!!.settings!!.targetLanguage)
            val asset = model.state.value.project!!.assets.single()
            assertNotNull(model.prepareAssetExport(asset.id, true))
            val pending = requireNotNull(saved.get<ArrayList<String>>("native_asset_export"))
            assertEquals(9, pending.size)
            assertEquals(listOf("true", "false", "true", "ja"), pending.subList(5, 9))
            assertNotNull(model.prepareExport(NativeExport.BILINGUAL))
            assertEquals("false", saved.get<ArrayList<String>>("native_export")!![5])
            model.saveProjectSettings(NativeProjectSettingsChange(project.ref, 0, CommandId("stale-settings"), ProjectSettings("en")))
            model.state.first { !it.busy }
            assertEquals(R.string.native_conflict, model.state.value.error)
            assertEquals(1L, graph.projects.open(project.ref).revision)
            model.saveProjectSettings(NativeProjectSettingsChange(project.ref, 1, CommandId("new-settings"), ProjectSettings("en")))
            model.state.first { !it.busy }
            model.completeAssetExport(android.net.Uri.parse("content://unopened-export/target"))
            model.state.first { !it.busy }
            assertEquals(R.string.native_conflict, model.state.value.error)
            assertEquals(2L, graph.projects.open(project.ref).revision)
        }
    }

    @Test fun assetCommandsBindRevisionRefreshListsAndProtectTheLastAsset() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("asset-ui-create"), "fixture", RelativePath("first.txt"), "first".toByteArray())
            graph.projects.importAsset(AssetImportPlan(CommandId("asset-ui-import"), project.ref, 0,
                RelativePath("second.txt"), ProjectAssetFormat.TXT, "second".toByteArray(), listOf(AssetImportUnit(0, 0, "second"))))
            val row = graph.projects.readPage(project.ref).items.last()
            graph.projects.edit(ManualTextEdit(CommandId("asset-ui-edit"), project.ref, row.id, 1, row.fingerprint, "translated"))
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            val second = model.state.value.project!!.assets.last().id
            val stale = NativeAssetMutation(project.ref, 2, second, NativeAssetChange.REMOVE, CommandId("stale-remove"))
            model.mutateAsset(NativeAssetMutation(project.ref, 2, second, NativeAssetChange.RESET, CommandId("reset-asset")))
            model.state.first { !it.busy }
            assertEquals("", graph.projects.readPage(project.ref).items.last().translation)
            assertEquals(TextItemStatus.NONE, graph.projects.readPage(project.ref).items.last().status)
            assertEquals(2, model.state.value.project!!.assets.size)
            val afterReset = model.state.value.project!!.revision
            model.mutateAsset(stale); model.state.first { !it.busy }
            assertEquals(R.string.native_conflict, model.state.value.error)
            assertEquals(afterReset, graph.projects.open(project.ref).revision)
            model.mutateAsset(NativeAssetMutation(project.ref, afterReset, second, NativeAssetChange.MOVE_UP, CommandId("move-asset")))
            model.state.first { !it.busy }
            assertEquals(second, model.state.value.project!!.assets.first().id)
            model.mutateAsset(NativeAssetMutation(project.ref, model.state.value.project!!.revision, second,
                NativeAssetChange.REMOVE, CommandId("remove-asset")))
            model.state.first { !it.busy }
            assertEquals(1, model.state.value.project!!.assets.size)
            assertEquals(1, model.state.value.project!!.itemCount)
            val remaining = model.state.value.project!!
            model.mutateAsset(NativeAssetMutation(project.ref, remaining.revision, remaining.assets.single().id,
                NativeAssetChange.REMOVE, CommandId("remove-last")))
            model.state.first { !it.busy }
            assertEquals(remaining.revision, graph.projects.open(project.ref).revision)
        }
    }

    @Test fun replacementPreviewOnlyUpdatesTheMatchingDraftAfterApplyAndNeverSavesImplicitly() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("replace-ui"), "fixture", RelativePath("source.txt"), "source".toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }; model.edit(ItemId(1)); model.state.first { !it.busy }
            model.editText("one one")
            model.prepareEditorReplacement(TextReplacementOptions("one", "two")); model.state.first { !it.busy }
            val stale = model.state.value.editorReplacement!!
            assertEquals("one one", model.state.value.editor!!.text)
            assertEquals(0L, graph.projects.open(project.ref).revision)
            model.editText("changed"); model.applyEditorReplacement(stale)
            assertEquals("changed", model.state.value.editor!!.text); assertNull(model.state.value.editorReplacement)
            model.prepareEditorReplacement(TextReplacementOptions("changed", "translated")); model.state.first { !it.busy }
            model.applyEditorReplacement(model.state.value.editorReplacement!!)
            assertEquals("translated", model.state.value.editor!!.text); assertTrue(model.state.value.editor!!.dirty)
            assertEquals("", graph.projects.item(project.ref, ItemId(1))!!.item.translation)
            assertEquals(0L, graph.projects.open(project.ref).revision)
            model.saveEditor(); model.state.first { !it.busy }
            assertEquals("translated", graph.projects.item(project.ref, ItemId(1))!!.item.translation)
            assertEquals(1L, graph.projects.open(project.ref).revision)
        }
    }

    @Test fun qualityPagingUsesTheIndexAndSavingAnEditInvalidatesItUntilExplicitClear() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("quality-pages"), "fixture", RelativePath("source.txt"),
                (1..45).joinToString("\n") { "source $it" }.toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            model.scanQuality(TextProofreadingOptions()); val scanned = model.state.first { !it.busy }
            assertTrue(scanned.qualityCurrent); assertEquals(45, scanned.qualityView!!.ids.size)
            model.nextPage(); val next = model.state.first { !it.busy }
            assertEquals(5, next.items.size); assertEquals(ItemId(41), next.items.first().id)
            model.edit(next.items.first().id); model.state.first { !it.busy }
            model.editText("译文"); model.saveEditor(); val savedEdit = model.state.first { !it.busy }
            assertFalse(savedEdit.qualityCurrent); assertNotNull(savedEdit.qualityView)
            assertEquals(1L, savedEdit.policyDraft!!.revision); assertFalse(savedEdit.policyDraft!!.dirty)
            assertEquals(ItemId(41), savedEdit.items.first().id)
            model.clearQuality(); val cleared = model.state.first { !it.busy }
            assertNull(cleared.qualityView); assertEquals(ItemId(1), cleared.items.first().id)
            assertFalse(cleared.hasPrevious); assertEquals(1L, graph.projects.open(project.ref).revision)
            model.scanQuality(TextProofreadingOptions()); model.state.first { !it.busy }
            model.changeTextPolicy(model.state.value.policyDraft!!.policy.changed(prompt = "changed"))
            assertFalse(model.state.value.qualityCurrent)
            model.saveTextPolicy(); val savedRules = model.state.first { !it.busy }
            assertFalse(savedRules.policyLoadIncomplete); assertFalse(savedRules.qualityCurrent)
            assertEquals(2L, savedRules.project!!.revision)
            model.refresh(); val refreshed = model.state.first { !it.busy }
            assertNull(refreshed.qualityView); assertEquals(ItemId(1), refreshed.items.first().id)
        }
    }

    @Test fun editorNavigationUsesMatchingItemsAndRequiresExplicitDraftDiscardWithoutWriting() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("adjacent-ui"), "fixture", RelativePath("source.txt"),
                "keep first\nskip\nkeep last".toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            model.queryProofreading(TextItemQuery(text = "keep", field = TextSearchField.SOURCE)); model.state.first { !it.busy }
            model.edit(ItemId(1)); model.state.first { !it.busy }; model.editText("draft")
            model.navigateEditor(TextItemDirection.NEXT); model.state.first { !it.busy }
            assertEquals(ItemId(1), model.state.value.editor!!.item.id); assertEquals("draft", model.state.value.editor!!.text)
            model.navigateEditor(TextItemDirection.NEXT, true); model.state.first { !it.busy }
            assertEquals(ItemId(3), model.state.value.editor!!.item.id); assertEquals("", model.state.value.editor!!.text)
            model.navigateEditor(TextItemDirection.PREVIOUS); model.state.first { !it.busy }
            assertEquals(ItemId(1), model.state.value.editor!!.item.id)
            assertEquals(0L, graph.projects.open(project.ref).revision)
            assertEquals("", graph.projects.item(project.ref, ItemId(1))!!.item.translation)
            model.closeEditor(); model.scanQuality(TextProofreadingOptions()); model.state.first { !it.busy }
            model.filterQuality(TextQualityFilter(TextQualityOutcome.NOT_EVALUATED)); model.state.first { !it.busy }
            model.edit(ItemId(1)); model.state.first { !it.busy }
            model.navigateEditor(TextItemDirection.NEXT); val moved = model.state.first { !it.busy }
            assertEquals(ItemId(3), moved.editor!!.item.id)
            model.navigateEditor(TextItemDirection.NEXT); val boundary = model.state.first { !it.busy }
            assertEquals(ItemId(3), boundary.editor!!.item.id); assertEquals(R.string.native_editor_boundary, boundary.notice)
        }
    }

    @Test fun qualityUsesTheFullDraftAndSavedRulesWithoutWritingAndEditsInvalidateItsResult() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("quality-ui"), "fixture", RelativePath("source.txt"),
                ("a".repeat(300) + " Alice <x>").toByteArray())
            val initial = graph.projects.textPolicy(project.ref)
            val policy = ProjectTextPolicy(listOf(ProjectTextRule("term", TextRuleKind.TERM, "Alice", "艾莉丝"),
                ProjectTextRule("protect", TextRuleKind.PROTECT, "<x>")))
            graph.projects.editTextPolicy(ProjectPolicyEdit(CommandId("quality-rules"), project.ref, initial.revision, initial.fingerprint, policy))
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            val item = model.state.value.items.first()
            assertFalse(item.source.contains("Alice"))
            model.edit(item.id); model.state.first { !it.busy }
            model.editText("译文")
            model.inspectEditor(TextProofreadingOptions()); val checked = model.state.first { !it.busy }
            assertTrue(TextWarningCode.TEXT_PRESERVE in checked.editorQuality!!.codes)
            assertEquals(listOf("term"), checked.editorQuality!!.missingTermRuleIds)
            assertEquals(1L, graph.projects.open(project.ref).revision)
            assertEquals("", graph.projects.item(project.ref, item.id)!!.item.translation)
            model.editText("艾莉丝 <x>")
            assertNull(model.state.value.editorQuality)
            model.inspectEditor(TextProofreadingOptions()); val corrected = model.state.first { !it.busy }
            assertFalse(TextWarningCode.TEXT_PRESERVE in corrected.editorQuality!!.codes)
            assertFalse(TextWarningCode.GLOSSARY in corrected.editorQuality!!.codes)
            model.changeTextPolicy(policy.changed(prompt = "unsaved"))
            assertNull(model.state.value.editorQuality)
            model.inspectEditor(TextProofreadingOptions()); val blocked = model.state.first { !it.busy }
            assertNull(blocked.editorQuality); assertEquals(R.string.native_quality_unavailable, blocked.error)
            assertEquals(1L, graph.projects.open(project.ref).revision)
        }
    }

    @Test fun policyDraftSurvivesControllerRecreationAndExplicitSaveUpdatesTheGlobalRevision() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("rules-ui"), "fixture", RelativePath("source.txt"), "source".toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (firstStore, first) = model(saved, graph.projects, graph.backgroundTranslations)
            first.state.first { !it.busy }
            val policy = ProjectTextPolicy(listOf(ProjectTextRule("term", TextRuleKind.TERM, "source", "target")), "prompt", true)
            first.changeTextPolicy(policy)
            assertTrue(first.state.value.policyDraft!!.dirty)
            assertEquals(0L, graph.projects.open(project.ref).revision)
            first.simulateProject(); first.state.first { !it.busy }
            assertNull(graph.backgroundTranslations.state.value)
            firstStore.clear(); first.viewModelScope.coroutineContext.job.join()
            val (_, second) = model(saved, graph.projects, graph.backgroundTranslations)
            second.state.first { !it.busy }
            assertEquals(policy.fingerprint(), second.state.value.policyDraft!!.policy.fingerprint())
            assertTrue(second.state.value.policyDraft!!.dirty)
            second.saveTextPolicy(); val done = second.state.first { !it.busy }
            assertFalse(done.policyDraft!!.dirty); assertEquals(1L, done.project!!.revision)
            assertEquals(policy.fingerprint(), graph.projects.textPolicy(project.ref).fingerprint)
            assertNull(saved.get<ArrayList<String>>("native_policy_draft"))
        }
    }

    private suspend fun commitItemForPolicyTest(model: NativeProjectViewModel, batch: Boolean) {
        if (batch) {
            model.selectItems(true); model.toggleItem(ItemId(1))
            model.prepareManualBatch(TextBatchChange.EXCLUDE)
            val prepared = model.state.first { !it.busy }.manualBatch!!
            model.confirmManualBatch(prepared)
        } else {
            model.edit(ItemId(1)); model.state.first { !it.busy }
            model.editText("translated"); model.saveEditor()
        }
        model.state.first { !it.busy }
    }

    @Test fun itemCommitsRefreshCleanRulesBeforeTheNextPolicyEdit() = runBlocking {
        withTimeout(10_000) {
            for (batch in listOf(false, true)) {
                val project = graph.projects.createTxt(CommandId("clean-rules-$batch"), "fixture",
                    RelativePath("source.txt"), "source".toByteArray())
                val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                    project.ref.instanceId.value, project.ref.generation.value.toString())))
                val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
                model.state.first { !it.busy }
                commitItemForPolicyTest(model, batch)
                val clean = model.state.value.policyDraft!!
                assertEquals(1L, clean.revision); assertFalse(clean.dirty); assertFalse(clean.conflict)
                assertFalse(model.state.value.policyLoadIncomplete)
                model.changeTextPolicy(clean.policy.changed(prompt = "after item commit"))
                model.saveTextPolicy(); val done = model.state.first { !it.busy }
                assertNull(done.error); assertFalse(done.policyDraft!!.dirty)
                assertEquals(2L, done.project!!.revision)
                assertEquals("after item commit", graph.projects.textPolicy(project.ref).policy.prompt)
                assertNull(saved.get<ArrayList<String>>("native_policy_draft"))
            }
        }
    }

    @Test fun itemCommitsPreserveDirtyRulesAndTheirStaleRevisionProtection() = runBlocking {
        withTimeout(10_000) {
            for (batch in listOf(false, true)) {
                val project = graph.projects.createTxt(CommandId("dirty-rules-$batch"), "fixture",
                    RelativePath("source.txt"), "source".toByteArray())
                val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                    project.ref.instanceId.value, project.ref.generation.value.toString())))
                val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
                model.state.first { !it.busy }
                model.changeTextPolicy(model.state.value.policyDraft!!.policy.changed(prompt = "unsaved"))
                val dirty = model.state.value.policyDraft!!
                commitItemForPolicyTest(model, batch)
                assertSame(dirty, model.state.value.policyDraft)
                assertEquals(encodePolicyDraft(dirty), saved.get<ArrayList<String>>("native_policy_draft"))
                assertEquals(1L, graph.projects.open(project.ref).revision)
                model.saveTextPolicy(); val rejected = model.state.first { !it.busy }
                assertEquals(R.string.native_conflict, rejected.error)
                assertTrue(rejected.policyDraft!!.conflict); assertTrue(rejected.policyDraft!!.dirty)
                assertEquals(0L, rejected.policyDraft!!.revision)
                assertEquals(dirty.command, rejected.policyDraft!!.command)
                assertEquals("unsaved", rejected.policyDraft!!.policy.prompt)
                assertEquals(1L, graph.projects.open(project.ref).revision)
                assertEquals("", graph.projects.textPolicy(project.ref).policy.prompt)
            }
        }
    }

    @Test fun aLostPolicySaveResponseReconcilesWithoutOverwritingLaterRules() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("rules-reconcile"), "fixture", RelativePath("source.txt"), "source".toByteArray())
            val initial = graph.projects.textPolicy(project.ref)
            val submitted = ProjectTextPolicy(prompt = "submitted", promptEnabled = true)
            val draft = NativePolicyDraft(project.ref, 0, initial.fingerprint, submitted, CommandId("lost-response"))
            graph.projects.editTextPolicy(ProjectPolicyEdit(draft.command, draft.ref, draft.revision, draft.base, draft.policy))
            val newer = ProjectTextPolicy(prompt = "newer", promptEnabled = true)
            val first = graph.projects.textPolicy(project.ref)
            graph.projects.editTextPolicy(ProjectPolicyEdit(CommandId("newer"), project.ref, first.revision, first.fingerprint, newer))
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString()), "native_policy_draft" to encodePolicyDraft(draft)))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            val restored = model.state.first { !it.busy }
            assertEquals("newer", restored.policyDraft!!.policy.prompt)
            assertFalse(restored.policyDraft!!.dirty); assertFalse(restored.policyDraft!!.conflict)
            assertNull(saved.get<ArrayList<String>>("native_policy_draft"))
            assertEquals(2L, graph.projects.open(project.ref).revision)
        }
    }

    @Test fun directProjectSimulationWithOnlyBlankPendingRowsDoesNotReserveOrStartAService() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("blank-ui"), "fixture", RelativePath("source.txt"),
                " \t\n\u00A0\u3000".toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            assertEquals(0, model.state.value.itemStatistics?.translatableUnprocessed)
            model.simulateProject(); model.state.first { !it.busy }
            assertNull(graph.backgroundTranslations.state.value)
            assertNull(graph.runtime.snapshot().activeRun)
            assertTrue(graph.runStore.recent(project.ref).isEmpty())
            assertEquals(0L, graph.projects.open(project.ref).revision)
        }
    }

    @Test fun manualBatchRequiresConfirmationAndKeepsFilteredMembersAfterAnAtomicStatusChange() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("batch-ui"), "fixture", RelativePath("source.txt"), "one\ntwo\nthree".toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            model.queryProofreading(TextItemQuery(status = TextItemStatus.NONE)); model.state.first { !it.busy }
            model.selectItems(true); model.toggleItem(ItemId(1)); model.toggleItem(ItemId(3)); model.toggleItem(ItemId(999))
            assertEquals(setOf(ItemId(1), ItemId(3)), model.state.value.selectedItems)
            model.prepareManualBatch(TextBatchChange.EXCLUDE)
            val prepared = model.state.first { !it.busy }
            assertNotNull(prepared.manualBatch); assertEquals(0L, graph.projects.open(project.ref).revision)
            model.confirmManualBatch(prepared.manualBatch!!)
            val done = model.state.first { !it.busy }
            assertNull(done.manualBatch); assertFalse(done.selectingItems); assertTrue(done.selectedItems.isEmpty())
            assertEquals(listOf(ItemId(1), ItemId(2), ItemId(3)), done.items.map { it.id })
            assertEquals(listOf(TextItemStatus.EXCLUDED, TextItemStatus.NONE, TextItemStatus.EXCLUDED), done.items.map { it.status })
            assertEquals(1, done.matchingItems); assertEquals(TextItemStatistics(1, 0, 2, 1), done.itemStatistics)
            model.confirmManualBatch(prepared.manualBatch!!); model.state.first { !it.busy }
            assertEquals(2L, graph.projects.open(project.ref).revision)
        }
    }

    @Test fun restoredBatchReconcilesAnUnacknowledgedCommitWithoutOverwritingLaterFacts() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("restore-batch"), "fixture", RelativePath("source.txt"), "one\ntwo".toByteArray())
            var failAfterCommit = true
            val projects = object : TextProjectPort by graph.projects {
                override suspend fun editBatch(command: ManualTextBatch): ManualBatchReceipt {
                    val result = graph.projects.editBatch(command)
                    if (failAfterCommit) { failAfterCommit = false; throw java.io.IOException("Synthetic lost acknowledgement") }
                    return result
                }
            }
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (owner, first) = model(saved, projects, graph.backgroundTranslations)
            first.state.first { !it.busy }
            first.selectItems(true); first.selectPageItems(); first.prepareManualBatch(TextBatchChange.EXCLUDE)
            val prepared = first.state.first { !it.busy }.manualBatch!!
            first.confirmManualBatch(prepared)
            val uncertain = first.state.first { !it.busy }
            assertEquals(prepared.commandId, uncertain.manualBatch!!.commandId)
            assertEquals(2L, graph.projects.open(project.ref).revision)
            owner.clear()
            val item = graph.projects.item(project.ref, ItemId(1))!!
            graph.projects.edit(ManualTextEdit(CommandId("later-edit"), project.ref, item.item.id,
                item.revision, item.item.fingerprint, "later saved", TextItemStatus.PROCESSED))
            val (_, restored) = model(saved, projects, graph.backgroundTranslations)
            val ready = restored.state.first { !it.busy }
            assertEquals(prepared.digest(), ready.manualBatch!!.digest()); assertEquals(NativePage.PROOFREADING, ready.page)
            restored.confirmManualBatch(ready.manualBatch!!)
            val done = restored.state.first { !it.busy }
            assertNull(done.manualBatch); assertEquals(3L, done.project!!.revision)
            assertEquals("later saved", done.items.first().translation)
            assertEquals(TextItemStatus.PROCESSED, done.items.first().status)
        }
    }

    @Test fun knownCommittedBatchIsNotReportedAsRolledBackWhenWindowRefreshFails() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("batch-read-failure"), "fixture", RelativePath("source.txt"), "one\ntwo".toByteArray())
            val projects = object : TextProjectPort by graph.projects {
                override suspend fun readItemWindow(ref: LocalProjectRef, query: TextItemQuery, window: TextItemWindow): TextProjectPage {
                    throw java.io.IOException("Synthetic refresh failure")
                }
            }
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            model.selectItems(true); model.selectPageItems(); model.prepareManualBatch(TextBatchChange.EXCLUDE)
            val batch = model.state.first { !it.busy }.manualBatch!!; model.confirmManualBatch(batch)
            val done = model.state.first { !it.busy }
            assertNull(done.manualBatch); assertEquals(2L, done.project!!.revision)
            assertEquals(R.string.native_batch_refresh_failed, done.error)
            assertNull(done.itemStatistics); assertNull(done.matchingItems)
            assertTrue(done.items.all { it.status == TextItemStatus.EXCLUDED })
            assertTrue(graph.projects.readPage(project.ref).items.all { it.status == TextItemStatus.EXCLUDED })
        }
    }

    @Test fun batchConflictRetainsItsPreviewAndNeverAutomaticallyPreparesANewCommand() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("conflict-batch"), "fixture", RelativePath("source.txt"), "one\ntwo".toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            model.selectItems(true); model.selectPageItems(); model.prepareManualBatch(TextBatchChange.CLEAR_AND_RESET)
            val noop = model.state.first { !it.busy }
            assertNull(noop.manualBatch); assertEquals(R.string.native_batch_no_changes, noop.notice)
            model.prepareManualBatch(TextBatchChange.EXCLUDE)
            val batch = model.state.first { !it.busy }.manualBatch!!
            val item = graph.projects.item(project.ref, ItemId(2))!!
            graph.projects.edit(ManualTextEdit(CommandId("intervening"), project.ref, item.item.id,
                item.revision, item.item.fingerprint, "saved"))
            model.confirmManualBatch(batch)
            val conflict = model.state.first { !it.busy }
            assertTrue(conflict.manualBatchConflict); assertEquals(batch.commandId, conflict.manualBatch!!.commandId)
            model.confirmManualBatch(batch); model.state.first { !it.busy }
            assertEquals(1L, graph.projects.open(project.ref).revision)
            assertEquals(TextItemStatus.NONE, graph.projects.item(project.ref, ItemId(1))!!.item.status)
            model.dismissManualBatch(batch); assertNull(model.state.value.manualBatch)
            assertNull(saved.get<ArrayList<String>>("manual_batch"))
        }
    }

    @Test fun lateConfirmationAndDismissalFromAnOldPreviewCannotAffectANewerBatch() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("late-batch"), "fixture", RelativePath("source.txt"), "one\ntwo".toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            model.selectItems(true); model.selectPageItems(); model.prepareManualBatch(TextBatchChange.EXCLUDE)
            val first = model.state.first { !it.busy }.manualBatch!!
            model.dismissManualBatch(first); model.prepareManualBatch(TextBatchChange.MARK_PROCESSED)
            val next = model.state.first { !it.busy }.manualBatch!!
            model.confirmManualBatch(first); model.state.first { !it.busy }; model.dismissManualBatch(first)
            assertEquals(next.commandId, model.state.value.manualBatch!!.commandId)
            assertEquals(0L, graph.projects.open(project.ref).revision)
            model.confirmManualBatch(next); model.state.first { !it.busy }
            assertEquals(2L, graph.projects.open(project.ref).revision)
        }
    }

    @Test fun proofreadingQueryKeepsEditedMembersAndSupportsExplicitRequeryPagingAndClear() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("create-query"), "fixture", RelativePath("source.txt"),
                (1..85).joinToString("\n") { if (it % 2 == 1) "needle-$it" else "other-$it" }.toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            val query = TextItemQuery(TextItemStatus.NONE, "needle", TextSearchField.SOURCE)
            model.queryProofreading(query)
            val filtered = model.state.first { !it.busy }
            assertEquals(43, filtered.matchingItems); assertTrue(filtered.hasNext); assertFalse(filtered.hasPrevious)
            val members = filtered.items.map { it.id }
            model.edit(members.first()); model.state.first { !it.busy }
            model.editText("saved translation"); model.saveEditor()
            val edited = model.state.first { !it.busy }
            assertNull(edited.editor); assertEquals(members, edited.items.map { it.id })
            assertEquals(TextItemStatus.PROCESSED, edited.items.first().status)
            assertEquals(TextItemStatistics(84, 1, 0, 84), edited.itemStatistics); assertEquals(42, edited.matchingItems)
            model.simulatePage(); model.state.first { !it.busy }
            assertNull(graph.backgroundTranslations.state.value) // A filtered window never widens the simulation scope.
            model.queryProofreading(query)
            val reselected = model.state.first { !it.busy }
            assertEquals(ItemId(3), reselected.items.first().id); assertTrue(reselected.hasNext)
            model.nextPage()
            val next = model.state.first { !it.busy }
            assertEquals(listOf(ItemId(83), ItemId(85)), next.items.map { it.id })
            assertTrue(next.hasPrevious); assertFalse(next.hasNext)
            model.previousPage()
            val previous = model.state.first { !it.busy }
            assertEquals(reselected.items.map { it.id }, previous.items.map { it.id }); assertFalse(previous.hasPrevious)
            model.queryProofreading(TextItemQuery(text = "no matches"))
            val empty = model.state.first { !it.busy }
            assertTrue(empty.items.isEmpty()); assertEquals(0, empty.matchingItems)
            assertFalse(empty.hasPrevious); assertFalse(empty.hasNext)
            model.queryProofreading(TextItemQuery())
            val cleared = model.state.first { !it.busy }
            assertEquals(ItemId(1), cleared.items.first().id); assertEquals(85, cleared.matchingItems)
            assertEquals(TextItemStatistics(84, 1, 0, 84), cleared.itemStatistics)
            assertFalse(cleared.hasPrevious); assertNull(graph.runtime.snapshot().activeRun)
        }
    }

    @Test fun committedEditSurvivesWindowRefreshFailureWithoutShowingOldCountsAsCurrent() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("query-refresh"), "fixture", RelativePath("source.txt"), "one\ntwo".toByteArray())
            val projects = object : TextProjectPort by graph.projects {
                override suspend fun readItemWindow(ref: LocalProjectRef, query: TextItemQuery, window: TextItemWindow): TextProjectPage {
                    throw TextProjectException(TextProjectException.Reason.STORAGE_IO)
                }
            }
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            val query = TextItemQuery(status = TextItemStatus.NONE)
            model.queryProofreading(query); model.state.first { !it.busy }
            model.edit(ItemId(1)); model.state.first { !it.busy }
            model.editText("saved"); model.saveEditor()
            val failedRefresh = model.state.first { !it.busy }
            assertNull(failedRefresh.editor); assertEquals(R.string.native_io_error, failedRefresh.error)
            assertEquals(listOf(ItemId(1), ItemId(2)), failedRefresh.items.map { it.id })
            assertEquals("saved", failedRefresh.items.first().translation)
            assertNull(failedRefresh.itemStatistics); assertNull(failedRefresh.matchingItems)
            assertEquals("saved", graph.projects.item(project.ref, ItemId(1))!!.item.translation)
            model.queryProofreading(query)
            val refreshed = model.state.first { !it.busy }
            assertEquals(listOf(ItemId(2)), refreshed.items.map { it.id })
            assertEquals(1, refreshed.matchingItems); assertEquals(TextItemStatistics(1, 1, 0, 1), refreshed.itemStatistics)
        }
    }

    @Test fun queryFailureRetainsTheWindowAndSavedFiltersAreBoundToTheFullProjectIdentity() = runBlocking {
        withTimeout(10_000) {
            val first = graph.projects.createTxt(CommandId("first-query"), "first", RelativePath("first.txt"), "needle\nother".toByteArray())
            val second = graph.projects.createTxt(CommandId("second-query"), "second", RelativePath("second.txt"), "another".toByteArray())
            val rejectQueries = AtomicBoolean(false)
            val projects = object : TextProjectPort by graph.projects {
                override suspend fun queryPage(ref: LocalProjectRef, query: TextItemQuery, afterId: Long, limit: Int): TextProjectPage {
                    if (rejectQueries.get()) throw TextProjectException(TextProjectException.Reason.STORAGE_IO)
                    return graph.projects.queryPage(ref, query, afterId, limit)
                }
            }
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(first.ref.projectId.value,
                first.ref.instanceId.value, first.ref.generation.value.toString())))
            val (owner, original) = model(saved, projects, graph.backgroundTranslations)
            original.state.first { !it.busy }
            val query = TextItemQuery(text = "needle")
            original.queryProofreading(query)
            val before = original.state.first { !it.busy }
            rejectQueries.set(true)
            original.queryProofreading(TextItemQuery(text = "other"))
            val failed = original.state.first { !it.busy }
            assertEquals(R.string.native_io_error, failed.error)
            assertEquals(query, failed.proofreadingQuery); assertEquals(before.items.map { it.id }, failed.items.map { it.id })
            rejectQueries.set(false); owner.clear()
            val (_, recreated) = model(saved, projects, graph.backgroundTranslations)
            val restored = recreated.state.first { !it.busy }
            assertEquals(query, restored.proofreadingQuery); assertEquals(1, restored.matchingItems)
            recreated.open(second.ref)
            val switched = recreated.state.first { !it.busy }
            assertEquals(TextItemQuery(), switched.proofreadingQuery)
            assertEquals(second.ref, switched.project!!.ref); assertEquals(1, switched.matchingItems)
            assertEquals("another", switched.items.single().source)
        }
    }

    @Test fun recreatedViewModelsAttachAcrossABatchCommitAndRecoverDirectoryAfterSettlement() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"),
                (1..61).joinToString("\n") { "source-$it" }.toByteArray())
            val enteredFirst = CompletableDeferred<Unit>(); val enteredSecond = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>(); val releaseSecond = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val local = LocalSimulationModel()
            val backend = object : TranslationModelBackend {
                override val configuration = local.configuration
                override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                    when (calls.incrementAndGet()) {
                        1 -> { enteredFirst.complete(Unit); releaseFirst.await() }
                        2 -> { enteredSecond.complete(Unit); releaseSecond.await() }
                        else -> error("Reattachment must not replay a request")
                    }
                    return local.complete(request)
                }
            }
            val translations = TextTranslationService(graph.runtime, graph.projects, graph.projects,
                NativeModelGateway(graph.runtime, backend), graph.runs)
            val runner = ForegroundTranslationRunner(graph.runtime, graph.runs, CoroutineScope(owner + Dispatchers.Default), translations)
            val handle = runner.prepare(request(project, 40)); runner.foregroundStarted(handle.token)
            enteredFirst.await()
            val recoveries = AtomicInteger()
            var firstRead = true
            val projects = object : TextProjectPort by graph.projects {
                override suspend fun readPage(ref: LocalProjectRef, afterId: Long, limit: Int): TextProjectPage {
                    if (firstRead) {
                        firstRead = false
                        val old = graph.projects.open(ref)
                        releaseFirst.complete(Unit); enteredSecond.await()
                        assertTrue(graph.projects.open(ref).revision > old.revision)
                    }
                    return graph.projects.readPage(ref, afterId, limit)
                }
                override suspend fun items(ref: LocalProjectRef, revision: Long, afterId: Long, limit: Int): TextItemPage =
                    error("UI refresh must use one consistent page read")
                override suspend fun recoverProjects(): ProjectRecoveryReport {
                    recoveries.incrementAndGet()
                    assertNull(graph.runtime.snapshot().activeRun)
                    return graph.projects.recoverProjects()
                }
            }
            fun remembered() = SavedStateHandle(mapOf("native_simulation_token" to handle.token,
                "native_simulation_cursors" to arrayListOf(0L, 40L), "native_recovery_problems" to 2))
            val (firstStore, firstModel) = model(remembered(), projects, runner)
            val first = firstModel.state.first { it.page == NativePage.PROOFREADING && it.items.size == 21 &&
                it.translationProgress?.committedItems == 20 }
            assertTrue(first.busy); assertEquals(project.ref, first.project!!.ref)
            assertEquals(20L, first.project.revision); assertTrue(first.hasPrevious); assertFalse(first.hasNext)
            assertEquals(2, first.recoveryProblems); assertEquals(0, recoveries.get()); assertNull(first.error)
            assertTrue(first.foregroundTranslationActive)
            firstModel.selectPage(NativePage.WORKBENCH)
            assertEquals(NativePage.WORKBENCH, firstModel.state.value.page)
            assertTrue(firstModel.state.value.busy)
            assertEquals(ForegroundTranslationPhase.RUNNING, runner.state.value!!.phase)
            firstModel.selectPage(NativePage.PROOFREADING)
            firstStore.clear(); firstModel.viewModelScope.coroutineContext.job.join()
            assertEquals(ForegroundTranslationPhase.RUNNING, runner.state.value!!.phase)
            assertNotNull(graph.runtime.snapshot().activeRun)

            val saved = remembered()
            val (_, secondModel) = model(saved, projects, runner)
            val second = secondModel.state.first { it.items.size == 21 && it.translationProgress?.committedItems == 20 }
            assertTrue(second.busy); assertTrue(second.hasPrevious); assertEquals(2, second.recoveryProblems)
            assertEquals(0, recoveries.get())
            releaseSecond.complete(Unit)
            val done = secondModel.state.first { !it.busy }
            assertFalse(done.foregroundTranslationActive)
            assertEquals(21, done.translationProgress!!.committedItems)
            assertTrue(done.items.all { it.status == TextItemStatus.PROCESSED })
            assertEquals(21L, done.project!!.revision); assertEquals(0, done.recoveryProblems)
            assertEquals(1, recoveries.get()); assertEquals(2, calls.get())
            assertEquals(1, graph.runStore.recent(project.ref).size)
            assertEquals(0, saved.get<Int>("native_recovery_problems"))
            assertNull(saved.get<String>("native_simulation_token")); assertNull(graph.runtime.snapshot().selectedChat)
        }
    }

    @Test fun failedPageReadsStillObserveAndStopTheRunAndFailedRecoveryRetainsWarnings() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"), "source".toByteArray())
            val entered = CompletableDeferred<Unit>()
            val runner = ForegroundTranslationRunner(graph.runtime, graph.runs, CoroutineScope(owner + Dispatchers.Default),
                translate = { _, _, _, emit ->
                    emit(TranslationProgress(0, 0, 0, unknownRequestUsage()))
                    entered.complete(Unit); awaitCancellation()
                })
            val handle = runner.prepare(request(project)); runner.foregroundStarted(handle.token); entered.await()
            val rejectReads = AtomicBoolean(true)
            val recoveries = AtomicInteger()
            val projects = object : TextProjectPort by graph.projects {
                override suspend fun readPage(ref: LocalProjectRef, afterId: Long, limit: Int): TextProjectPage {
                    if (rejectReads.get()) throw TextProjectException(TextProjectException.Reason.STORAGE_IO)
                    return graph.projects.readPage(ref, afterId, limit)
                }
                override suspend fun recoverProjects(): ProjectRecoveryReport {
                    recoveries.incrementAndGet()
                    throw TextProjectException(TextProjectException.Reason.STORAGE_IO)
                }
            }
            val saved = SavedStateHandle(mapOf("native_recovery_problems" to 3))
            val (_, model) = model(saved, projects, runner)
            val attached = model.state.first { it.error == R.string.native_background_refresh_failed && it.translationProgress != null }
            assertTrue(attached.busy); assertNull(attached.project)
            assertEquals(3, attached.recoveryProblems); assertEquals(0, recoveries.get())
            rejectReads.set(false)
            model.cancelAction() // No project summary is loaded; ownership still comes from the handle.
            val done = model.state.first { !it.busy }
            assertEquals(ForegroundTranslationPhase.CANCELLED, runner.state.value!!.phase)
            assertEquals(project.ref, done.project!!.ref); assertEquals(3, done.recoveryProblems)
            assertEquals(1, recoveries.get()); assertEquals(true, saved.get<Boolean>("native_recovery_pending"))
            assertEquals(R.string.native_io_error, done.error)
            assertEquals(StoredRunState.CANCELLED, graph.runStore.recent(project.ref).single().state)
            assertNull(graph.runtime.snapshot().activeRun)
        }
    }
}
