package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
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
            ProjectsHome(state, {}, {}, {}, {}, {})
        } } }
        compose.onNodeWithText(context.getString(R.string.native_create_txt)).assertExists()
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/native-projects.png")
    }

    @Test fun agentLandingOffersRealWorkspaceInsteadOfAnUnconnectedSendAction() {
        var selected: NativePage? = null
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeAgentPage(NativeProjectUiState(project = summary), {}, { _, _ -> }, {}, {}, navigate = { selected = it })
        } } }
        compose.onNodeWithTag("native-agent-page").performScrollToNode(hasText(context.getString(R.string.native_open_proofreading)))
        compose.onNodeWithText(context.getString(R.string.native_open_proofreading)).performClick()
        assertEquals(NativePage.PROOFREADING, selected)
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/native-agent.png")
    }

    @Test fun workbenchOffersNativeSnapshotAndBilingualExport() {
        var selected: NativeExport? = null
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Workbench(NativeProjectUiState(project = summary), {}) { selected = it }
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
        compose.onNodeWithText(context.getString(R.string.native_simulation)).performClick()
        assertFalse(started)
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
        compose.onNodeWithTag("native-agent-page").performScrollToNode(hasText(context.getString(R.string.native_runs_uncertain)))
        compose.onNodeWithText(context.getString(R.string.native_runs_uncertain)).assertExists()
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/native-run-recovery.png")
        compose.onNodeWithTag("native-agent-page").performScrollToNode(hasText(context.getString(R.string.native_agent_query)))
        compose.onNodeWithText(context.getString(R.string.native_agent_query)).assertIsNotEnabled()
    }

    @Test fun localModelDiscoverySelectsAnIdAndSavesWithoutAKey() {
        var fetches = 0
        var savedModel = ""
        var savedKey: String? = "not saved"
        compose.setContent { NativeTheme {
            NativeModelDialog(null, false, null, false, {}, { endpoint, model, key, _ ->
                assertEquals("http://127.0.0.1:11434/v1", endpoint); savedModel = model; savedKey = key
            }, { _, key, stored, result ->
                fetches++; assertEquals("", key); assertNull(stored); result(listOf("fixture-local-model"))
            }, {})
        } }
        compose.onNodeWithText("Ollama").performClick()
        compose.onNodeWithText(context.getString(R.string.native_models_fetch)).performScrollTo().performClick()
        compose.onNodeWithText("fixture-local-model").performClick()
        compose.onNodeWithText(context.getString(R.string.native_save)).performClick()
        assertEquals(1, fetches); assertEquals("fixture-local-model", savedModel); assertEquals("", savedKey)
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/native-model-local.png")
    }

    @Test fun savedCredentialCannotFollowAnEditedEndpointImplicitly() {
        val settings = NativeModelSettings(ModelConfigurationRef("fixture", 1), "https://example.com/api/models", "model", true)
        var capturedKey: String? = "not saved"
        var capturedEndpoint = ""
        compose.setContent { NativeTheme {
            NativeModelDialog(settings, false, null, false, {}, { endpoint, _, key, _ ->
                capturedEndpoint = endpoint; capturedKey = key
            }, { _, _, _, _ -> }, {})
        } }
        compose.onNodeWithText(context.getString(R.string.native_save)).performClick()
        assertNull(capturedKey)
        assertEquals(settings.endpoint, NativeModelEndpoint.normalize(capturedEndpoint))
        compose.onNodeWithText(context.getString(R.string.native_model_endpoint)).performTextReplacement("http://192.168.1.2:1234/v1")
        compose.onNodeWithText(context.getString(R.string.native_save)).performClick()
        assertEquals("", capturedKey)
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
        val editor = compose.onNodeWithText(context.getString(R.string.native_open_proofreading))
        val baseline = editor.fetchSemanticsNode().boundsInRoot.top
        fun assertNoPreviewSpacing() {
            compose.onNodeWithTag("native-agent-stream-preview").assertDoesNotExist()
            assertEquals(baseline, editor.fetchSemanticsNode().boundsInRoot.top, 0.5f)
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
        compose.runOnIdle { session.previewCompleted(key, MessageId("saved")) }
        assertNoPreviewSpacing()
        compose.runOnIdle { controller.open(ref, run).previewStarted(key) }
        compose.onNodeWithTag("native-agent-stream-preview").assertExists()
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
