package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.AgentStreamPreviewController
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic component fixtures only; screenshots still require human visual inspection. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class NativeWorkspaceScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val ref = LocalProjectRef(ProjectId("fixture"), ProjectInstanceId("local-fixture"), RecoveryGeneration(1))
    private val summary = TextProjectSummary(ref, "Synthetic project", 0, RelativePath("sample.txt"), 2)
    private val first = TextProjectItem(ItemId(41), 0, "Synthetic first line", "", TextItemStatus.NONE,
        TextProjectFingerprint.item(ItemId(41), 0, "Synthetic first line", "", TextItemStatus.NONE))
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun projectHome() {
        val state = NativeProjectUiState(projects = listOf(RegisteredProject(ref, summary, null)))
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            ProjectsHome(state, {}, {}, {}, {}, {}, {})
        } } }
        compose.onNodeWithText(context.getString(R.string.native_create_txt)).assertExists()
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/native-projects.png")
    }

    @Test fun agentLandingOffersRealWorkspaceInsteadOfAnUnconnectedSendAction() {
        var selected: NativePage? = null
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeAgentPage(NativeProjectUiState(project = summary), {}, { _, _ -> }, {}, {}, navigate = { selected = it })
        } } }
        compose.onNodeWithContentDescription(context.getString(R.string.native_agent_actions)).performClick()
        compose.onNodeWithText(context.getString(R.string.native_open_proofreading)).performClick()
        assertEquals(NativePage.PROOFREADING, selected)
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/native-agent.png")
    }

    @Test fun agentSuggestionsPreserveTheDraftAndRequireExplicitSend() {
        val draft = mutableStateOf("")
        var sent = 0
        val settings = NativeModelSettings(ModelConfigurationRef("fixture", 1), "http://localhost:1234/v1", "fixture-model", false)
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeAgentPage(
                NativeProjectUiState(project = summary, agentInput = draft.value, modelSettings = settings),
                {}, { _, _ -> }, {}, {}, {},
                input = { draft.value = it },
                send = { sent++ },
            )
        } } }
        val suggestion = context.getString(R.string.native_agent_suggestion_summary)
        compose.onNodeWithTag("native-agent-page").performScrollToNode(hasText(suggestion))
        compose.onNodeWithText(suggestion).performClick()
        compose.runOnIdle {
            assertEquals(suggestion, draft.value)
            assertEquals(0, sent)
        }
        val otherSuggestion = context.getString(R.string.native_agent_suggestion_translate)
        compose.onNodeWithTag("native-agent-page").performScrollToNode(hasText(otherSuggestion))
        compose.onNodeWithText(otherSuggestion).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.native_agent_send)).performClick()
        compose.runOnIdle { assertEquals(1, sent); assertEquals(suggestion, draft.value) }
    }

    @Test fun oversizedPasteShowsFeedbackAndRetainsTheExistingDraft() {
        val draft = mutableStateOf("Keep this draft")
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeAgentPage(
                NativeProjectUiState(project = summary, agentInput = draft.value),
                {}, { _, _ -> }, {}, {}, {}, input = { draft.value = it },
            )
        } } }
        compose.onNodeWithTag("native-agent-input").performTextReplacement("x".repeat(8001))
        compose.onNodeWithText(context.getString(R.string.native_agent_message_too_long, 8001)).assertIsDisplayed()
        compose.runOnIdle { assertEquals("Keep this draft", draft.value) }
        compose.onNodeWithTag("native-agent-input").performTextReplacement("Shorter text")
        compose.onNodeWithText(context.getString(R.string.native_agent_message_too_long, 8001)).assertDoesNotExist()
        compose.runOnIdle { assertEquals("Shorter text", draft.value) }
    }

    @Test fun activeRunKeepsGuidanceAndStopAsSeparateActions() {
        var guided = 0
        var stopped = 0
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeAgentPage(
                NativeProjectUiState(
                    project = summary,
                    busy = true,
                    foregroundAgentActive = true,
                    agentInput = "Use the approved terminology",
                ),
                {}, { _, _ -> }, {}, {}, {},
                cancel = { stopped++ },
                steer = { guided++ },
            )
        } } }
        compose.onNodeWithText(context.getString(R.string.native_agent_steer)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.native_agent_stop)).assertIsDisplayed().performClick()
        assertEquals(1, guided)
        assertEquals(1, stopped)
    }

    @Test fun workbenchOffersNativeSnapshotAndBilingualExport() {
        var selected: NativeExport? = null
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Workbench(NativeProjectUiState(project = summary), cleanup = {}, export = { selected = it },
                addAsset = {}, exportAsset = {})
        } } }
        compose.onNodeWithText(context.getString(R.string.native_export_bilingual)).performClick()
        assertEquals(NativeExport.BILINGUAL, selected)
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/native-workbench.png")
    }

    @Test fun proofreadingDispatchesStableItemIdentity() {
        var selected: ItemId? = null
        val state = NativeProjectUiState(project = summary, items = listOf(ItemPreview.of(first)))
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Proofreading(state, { selected = it }, {}, {}, {})
        } } }
        compose.onNodeWithText(first.source).performClick()
        assertEquals(ItemId(41), selected)
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/native-proofreading.png")
    }

    @Test fun pendingAgentDifferenceRequiresExplicitAllowOnce() {
        var allowed = false
        val approval = TextApproval(ApprovalRequestId("approval"), ref, ChatId("chat"), RunId("run"), CommandId("command"),
            "a".repeat(64), first.id, 0, first.fingerprint, first.row, first.status, first.translation, "proposed", TextApprovalState.PENDING)
        val state = NativeProjectUiState(project = summary, items = listOf(ItemPreview.of(first)), approval = approval)
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeAgentPage(state, {}, { _, _ -> }, { allowed = true }, {}, {})
        } } }
        assertFalse(allowed)
        compose.onNodeWithTag("native-agent-page").performScrollToNode(hasText(context.getString(R.string.native_agent_allow_once)))
        compose.onNodeWithText(context.getString(R.string.native_agent_allow_once)).performClick()
        assertTrue(allowed)
    }

    @Test fun localSimulationRequiresExplicitConfirmation() {
        var started = false
        val state = NativeProjectUiState(project = summary, items = listOf(ItemPreview.of(first)))
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Proofreading(state, {}, {}, {}, { started = true })
        } } }
        compose.onNodeWithTag("native-proof-actions").performClick()
        compose.onNodeWithText(context.getString(R.string.native_simulation)).performClick()
        assertFalse(started)
        compose.onNodeWithText(context.getString(R.string.native_simulation_start)).performClick()
        assertTrue(started)
    }

    @Test fun requestingOptionalNotificationsDoesNotStartTheSimulation() {
        var requested = 0
        var started = false
        val state = NativeProjectUiState(project = summary, items = listOf(ItemPreview.of(first)))
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Proofreading(state, {}, {}, {}, { started = true }, notificationsGranted = false) { requested++ }
        } } }
        compose.onNodeWithTag("native-proof-actions").performClick()
        compose.onNodeWithText(context.getString(R.string.native_simulation)).performClick()
        compose.onNodeWithText(context.getString(R.string.native_background_notifications)).performScrollTo().performClick()
        assertEquals(1, requested); assertFalse(started)
        compose.onNodeWithText(context.getString(R.string.native_simulation_start)).performClick()
        assertTrue(started)
    }

    @Test fun editorPresentsDraftAndExplicitSave() {
        var saved = false
        val draft = NativeEditDraft(ref, first, 0, first.fingerprint, "Synthetic translation", TextItemStatus.PROCESSED, CommandId("save"))
        compose.setContent { NativeTheme {
            Editor(draft, false, {}, {}, {}, { saved = true }, {}, {})
        } }
        compose.onNodeWithText(context.getString(R.string.native_save)).performClick()
        assertTrue(saved)
        compose.onNode(isRoot() and hasAnyDescendant(hasText(context.getString(R.string.native_edit_row, 1))))
            .captureRoboImage(filePath = "src/test/screenshots/native-editor.png")
    }

    @Test fun interruptedRunShowsReviewWarningAndFailedRecoveryDisablesNewOperations() {
        val run = StoredRun(RunJournalHandle("execution", ref,
            RunIdentity(ref.projectId, ChatId("chat"), RunId("run"), CommandId("start"))),
            RuntimeOwner.AGENT, StoredRunState.INTERRUPTED, RunCheckpoint(StoredRunStage.REQUESTING, requests = 1), true)
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeAgentPage(NativeProjectUiState(project = summary, recentRuns = listOf(run), runLoadIncomplete = true),
                {}, { _, _ -> }, {}, {}, {})
        } } }
        compose.onNodeWithTag("native-agent-interrupted").performClick()
        compose.onNodeWithText(context.getString(R.string.native_runs_uncertain)).assertExists()
        compose.onNode(isRoot() and hasAnyDescendant(hasText(context.getString(R.string.native_runs_uncertain))))
            .captureRoboImage(filePath = "src/test/screenshots/native-run-recovery.png")
        compose.onNodeWithText(context.getString(R.string.native_close)).performClick()
        compose.onNodeWithTag("native-agent-page").performScrollToNode(hasText(context.getString(R.string.native_agent_query)))
        compose.onNodeWithText(context.getString(R.string.native_agent_query)).assertIsNotEnabled()
    }

    @Test fun localModelDiscoverySelectsAnIdAndSavesWithoutAKey() {
        var fetches = 0
        var saves = 0
        var savedModel = ""
        var savedKey: String? = "not saved"
        val visible = mutableStateOf(true)
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            if (visible.value) NativeModelDialog(null, false, null, false, { visible.value = false }, { endpoint, model, key, done ->
                assertEquals("http://127.0.0.1:11434/v1", endpoint); saves++; savedModel = model; savedKey = key; done()
            }, { _, key, stored, result ->
                fetches++; assertEquals("", key); assertNull(stored); result(listOf("fixture-local-model"))
            }, {})
        } } }
        compose.onNodeWithText("Ollama").performClick()
        fetchModels()
        compose.runOnIdle { assertEquals("Discovery click must reach the callback", 1, fetches) }
        assertModelStage("native-model-picker")
        compose.onNodeWithTag("native-model-form").assertDoesNotExist()
        compose.onNodeWithText("fixture-local-model").assertIsDisplayed()
        compose.onNodeWithText("fixture-local-model").performClick()
        assertModelStage("native-model-form")
        compose.onNodeWithTag("native-model-picker").assertDoesNotExist()
        compose.onNodeWithTag("native-model-form").captureRoboImage(filePath = "src/test/screenshots/native-model-local.png")
        compose.onNodeWithText(context.getString(R.string.native_save)).performClick()
        compose.runOnIdle {
            assertEquals(1, fetches); assertEquals(1, saves)
            assertEquals("fixture-local-model", savedModel); assertEquals("", savedKey); assertFalse(visible.value)
        }
        compose.onNodeWithTag("native-model-window").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land-mdpi", sdk = [35])
    fun delayedDiscoveryCanScrollSearchAndReturnToTheSameFormInACompactWindow() {
        val loading = mutableStateOf(false)
        var deliver: ((List<String>) -> Unit)? = null
        var savedModel = ""
        var savedKey: String? = null
        val settings = NativeModelSettings(ModelConfigurationRef("fixture", 1), "http://localhost:1234/v1", "original", false)
        compose.setContent { NativeTheme {
            NativeModelDialog(settings, loading.value, null, loading.value, {}, { _, model, key, _ ->
                savedModel = model; savedKey = key
            }, { _, _, _, result -> deliver = result; loading.value = true }, { loading.value = false })
        } }
        compose.onNodeWithText(context.getString(R.string.native_save)).assertIsDisplayed()
        fetchModels()
        assertModelStage("native-model-form")
        compose.runOnIdle {
            assertTrue("Fixture must receive a discovery request", loading.value)
            requireNotNull(deliver)((1..80).map { "fixture-model-${it.toString().padStart(3, '0')}" })
            loading.value = false
        }
        assertModelStage("native-model-picker")
        compose.onNodeWithTag("native-model-list").performScrollToNode(hasText("fixture-model-080"))
        compose.onNodeWithText("fixture-model-080").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_models_search)).performTextReplacement("missing")
        compose.onNodeWithText(context.getString(R.string.native_models_no_match)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_models_search)).performTextReplacement("070")
        compose.onNodeWithTag("native-model-list").performScrollToNode(hasText("fixture-model-070"))
        compose.onNodeWithText("fixture-model-070").performClick()
        assertModelStage("native-model-form")
        compose.onNodeWithText(context.getString(R.string.native_save)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("fixture-model-070", savedModel); assertEquals("", savedKey) }
    }

    @Test fun pickerCancelAndOutsideTapPreserveTheFormAndDoNotRefetch() {
        var fetches = 0
        var dismisses = 0
        var savedModel = ""
        val visible = mutableStateOf(true)
        val settings = NativeModelSettings(ModelConfigurationRef("fixture", 1), "http://localhost:1234/v1", "original", false)
        compose.setContent { NativeTheme {
            if (visible.value) NativeModelDialog(settings, false, null, false, { dismisses++; visible.value = false },
                { _, model, _, _ -> savedModel = model }, { _, _, _, result -> fetches++; result(listOf("fixture-model")) }, {})
        } }
        // A touch on ordinary card content must not hit the backdrop behind it.
        compose.onNodeWithText(context.getString(R.string.native_model_configure)).performClick()
        compose.runOnIdle { assertEquals(0, dismisses) }
        fetchModels()
        assertModelStage("native-model-picker")
        compose.onNodeWithText(context.getString(R.string.native_cancel)).performClick()
        assertModelStage("native-model-form")
        compose.onNodeWithText(context.getString(R.string.native_models_count, 1)).performScrollTo().performClick()
        assertModelStage("native-model-picker")
        compose.onNodeWithTag("native-model-scrim").performTouchInput { click(Offset(2f, 2f)) }
        assertModelStage("native-model-form")
        compose.onNodeWithText(context.getString(R.string.native_save)).performClick()
        compose.runOnIdle { assertEquals(1, fetches); assertEquals(0, dismisses); assertEquals("original", savedModel) }
        compose.onNodeWithTag("native-model-scrim").performTouchInput { click(Offset(2f, 2f)) }
        compose.onNodeWithTag("native-model-window").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismisses) }
    }

    @Test fun emptyDiscoveryStillAllowsAnExplicitManualModel() {
        var fetches = 0
        var savedModel = ""
        compose.setContent { NativeTheme {
            NativeModelDialog(null, false, null, false, {}, { _, model, _, _ -> savedModel = model },
                { _, _, _, result -> fetches++; result(emptyList()) }, {})
        } }
        compose.onNodeWithText("LM Studio").performClick()
        fetchModels()
        compose.runOnIdle { assertEquals(1, fetches) }
        assertModelStage("native-model-form")
        compose.onNodeWithTag("native-model-picker").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.native_models_empty)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_model_name)).performScrollTo().performTextReplacement("manual-model")
        compose.onNodeWithText(context.getString(R.string.native_save)).performClick()
        compose.runOnIdle { assertEquals("manual-model", savedModel) }
    }

    private fun fetchModels() {
        compose.onNodeWithText(context.getString(R.string.native_models_fetch))
            .performScrollTo().assertIsDisplayed().performClick()
    }

    private fun assertModelStage(tag: String) {
        try {
            compose.onAllNodesWithTag("native-model-window").assertCountEquals(1)
            compose.onNodeWithTag(tag).assertIsDisplayed()
        } catch (failure: AssertionError) {
            // Keep all dialog/activity roots in the CI XML report before the fixture is disposed.
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE)
            throw AssertionError("Expected model dialog stage: $tag\n$roots", failure)
        }
    }

    @Test fun savedCredentialCannotFollowAnEditedEndpointImplicitly() {
        val settings = NativeModelSettings(ModelConfigurationRef("fixture", 1), "https://example.com/api/models", "model", true)
        var capturedKey: String? = "not saved"
        var capturedEndpoint = ""
        var saves = 0
        compose.setContent { NativeTheme {
            NativeModelDialog(settings, false, null, false, {}, { endpoint, _, key, _ ->
                saves++; capturedEndpoint = endpoint; capturedKey = key
            }, { _, _, _, _ -> }, {})
        } }
        compose.onNodeWithText(context.getString(R.string.native_save)).performClick()
        assertEquals(1, saves)
        assertNull(capturedKey)
        assertEquals(settings.endpoint, NativeModelEndpoint.normalize(capturedEndpoint))
        compose.onNodeWithText(context.getString(R.string.native_model_endpoint)).performTextReplacement("http://192.168.1.2:1234/v1")
        compose.onNodeWithText(context.getString(R.string.native_save)).performClick()
        assertEquals(2, saves); assertEquals("", capturedKey)
        assertEquals("http://192.168.1.2:1234/v1", capturedEndpoint)
    }

    @Test fun composerStaysVisibleWhileTimelineScrollsAndMessagesUseRoles() {
        val settings = NativeModelSettings(ModelConfigurationRef("fixture", 1), "http://localhost:1234/v1", "fixture-model", false)
        val lines = (1..20).map { AgentChatLine("line-$it", if (it % 2 == 0) "ASSISTANT" else "USER", "Synthetic message $it") }
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeAgentPage(NativeProjectUiState(project = summary, modelSettings = settings, agentMessages = lines),
                {}, { _, _ -> }, {}, {}, {})
        } } }
        compose.onNodeWithTag("native-agent-page").performScrollToNode(hasText("Synthetic message 1"))
        compose.onNodeWithText(context.getString(R.string.native_agent_send)).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.native_role_you))[0].assertExists()
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/native-agent-conversation.png")
    }

    @Test fun absentForeignAndAlreadySavedPreviewsDoNotLeaveTimelineSpacing() {
        val chat = ChatId("chat")
        val run = RunIdentity(ref.projectId, chat, RunId("stream"), CommandId("send"))
        val key = AgentRequestKey(run, BranchId("main"), RequestAttemptId("attempt"))
        val controller = AgentStreamPreviewController()
        val enabled = mutableStateOf(false)
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeAgentPage(NativeProjectUiState(project = summary, agentChatId = chat,
                agentMessages = listOf(AgentChatLine("saved", "ASSISTANT", "Saved message"))),
                {}, { _, _ -> }, {}, {}, {}, stream = if (enabled.value) controller.state else null)
        } } }
        fun assertNoPreviewSpacing() {
            compose.onNodeWithTag("native-agent-stream-slot").assertDoesNotExist()
            compose.onNodeWithTag("native-agent-stream-preview").assertDoesNotExist()
        }
        compose.runOnIdle { enabled.value = true }
        assertNoPreviewSpacing()
        compose.runOnIdle {
            controller.open(ref.copy(generation = RecoveryGeneration(2)), run).previewStarted(key)
        }
        assertNoPreviewSpacing()
        compose.runOnIdle {
            val otherRun = run.copy(chatId = ChatId("other-chat"))
            controller.open(ref, otherRun).previewStarted(AgentRequestKey(otherRun, BranchId("main"), RequestAttemptId("other")))
        }
        assertNoPreviewSpacing()
        lateinit var session: AgentStreamPreviewController.Session
        compose.runOnIdle {
            session = controller.open(ref, run)
            session.previewStarted(key); session.preview(key, AgentModelEvent.TextDelta("Live preview"))
        }
        compose.onNodeWithTag("native-agent-stream-preview").assertExists()
        compose.onNodeWithTag("native-agent-stream-slot").assertExists()
        compose.runOnIdle { session.previewCompleted(key, MessageId("saved")) }
        assertNoPreviewSpacing()
        compose.runOnIdle { controller.open(ref, run).previewStarted(key) }
        compose.onNodeWithTag("native-agent-stream-preview").assertExists()
        compose.onNodeWithTag("native-agent-stream-slot").assertExists()
        compose.runOnIdle { controller.clear() }
        assertNoPreviewSpacing()
    }

    @Test fun streamPreviewShowsPartialTextAndRemainsDistinctFromSavedConversation() {
        val chat = ChatId("chat")
        val run = RunIdentity(ref.projectId, chat, RunId("stream"), CommandId("send"))
        val controller = AgentStreamPreviewController(); val session = controller.open(ref, run)
        val key = AgentRequestKey(run, BranchId("main"), RequestAttemptId("stream-attempt"))
        session.previewStarted(key); session.preview(key, AgentModelEvent.TextDelta("Synthetic streaming text"))
        session.preview(key, AgentModelEvent.ThinkingDelta("Synthetic streaming reasoning"))
        session.previewFinished(key); session.close(keepPartial = true)
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeAgentStreamPreview(ref, chat, emptySet(), controller.state)
        } } }
        compose.onNodeWithTag("native-agent-stream-preview").assertExists()
        compose.onNodeWithText(context.getString(R.string.native_stream_interrupted)).assertExists()
        compose.onNodeWithText("Synthetic streaming text").assertExists()
        compose.onNodeWithText(context.getString(R.string.native_agent_reasoning)).performClick()
        compose.onNodeWithText("Synthetic streaming reasoning").assertExists()
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/native-agent-stream.png")
    }
}
