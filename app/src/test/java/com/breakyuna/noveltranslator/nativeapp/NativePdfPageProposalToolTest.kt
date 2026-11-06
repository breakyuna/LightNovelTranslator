package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.storage.NativeAgentStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeItemProposalStore
import com.breakyuna.noveltranslator.nativecore.storage.NativePdfPageProposalStore
import com.breakyuna.noveltranslator.nativecore.storage.NativePolicyProposalStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeTextProjectStore
import com.breakyuna.noveltranslator.nativecore.workspace.ProjectWorkspaceAnalysis
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
class NativePdfPageProposalToolTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var agent: NativeAgentStore
    private lateinit var items: NativeItemProposalStore
    private lateinit var policies: NativePolicyProposalStore
    private lateinit var pdfPages: NativePdfPageProposalStore
    private lateinit var tools: AgentToolPort
    private lateinit var ref: LocalProjectRef
    private lateinit var lease: RunLease
    private var chat: ChatId? = null

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        runtime = RuntimeCoordinator()
        projects = NativeTextProjectStore(context, runtime)
        agent = NativeAgentStore(context, projects, runtime)
        items = NativeItemProposalStore(context, projects, runtime)
        policies = NativePolicyProposalStore(context, projects, runtime)
        pdfPages = NativePdfPageProposalStore(context, projects, runtime)
        ref = projects.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"), "source".toByteArray()).ref
        val selected = agent.currentChat(ref)
        chat = selected
        runtime.selectChat(ChatIdentity(ref.projectId, selected))
        lease = runtime.beginRun(RunIdentity(ref.projectId, selected, RunId("run"), CommandId("send")), RuntimeOwner.AGENT)
        tools = nativeWorkspaceAnalysisTools(runtime, ProjectWorkspaceAnalysis(context, projects), ref, lease,
            items, policies, pdfPages)
    }

    @After fun cleanup() = runBlocking {
        runtime.finishRun(lease); runtime.selectChat(null)
        pdfPages.close(); policies.close(); items.close(); agent.close(); projects.close(); runtime.close()
    }

    private suspend fun call(name: String, json: String): AgentToolOutcome =
        tools.execute(lease, AgentToolCall(ToolCallId("call"), name,
            JsonObjectValue.parse(json, JsonInputLimits(json.length.coerceAtLeast(1), 8))))

    private fun failure(outcome: AgentToolOutcome): AgentToolOutcome.Failure {
        assertTrue(outcome is AgentToolOutcome.Failure)
        return outcome as AgentToolOutcome.Failure
    }

    @Test fun pdfPagePreparationIsExposedOnlyAsApprovalPreparationAndBoundsScalars() = runBlocking {
        val names = tools.definitions().map { it.name }
        assertTrue("prepare_pdf_page_changes" in names)
        assertTrue("pdf_page_proposal_status" in names)
        assertEquals("asset_id",
            failure(call("prepare_pdf_page_changes", """{"asset_id":"1","base_revision":0,"base_sha256":"${"a".repeat(64)}",
                "pages_json":"{\"pages\":[{\"page\":1,\"decision\":\"KEEP\"}]}"}""")).field)
        assertEquals("base_revision",
            failure(call("prepare_pdf_page_changes", """{"asset_id":1,"base_revision":-1,"base_sha256":"${"a".repeat(64)}",
                "pages_json":"{\"pages\":[{\"page\":1,\"decision\":\"KEEP\"}]}"}""")).field)
        assertEquals("base_sha256",
            failure(call("prepare_pdf_page_changes", """{"asset_id":1,"base_revision":0,"base_sha256":"short",
                "pages_json":"{\"pages\":[{\"page\":1,\"decision\":\"KEEP\"}]}"}""")).field)
        assertEquals("pages_json",
            failure(call("prepare_pdf_page_changes", """{"asset_id":1,"base_revision":0,"base_sha256":"${"a".repeat(64)}",
                "pages_json":""}""")).field)
    }

    @Test fun invalidPagePayloadFailsWithoutCreatingPendingAuthority() = runBlocking {
        val outcome = call("prepare_pdf_page_changes", """{"asset_id":1,"base_revision":0,"base_sha256":"${"a".repeat(64)}",
            "pages_json":"{\"pages\":[{\"page\":1,\"decision\":\"keep\"}]}"}""")
        assertEquals("tool_failed", failure(outcome).code)
        assertNull(pdfPages.pending(ref, requireNotNull(chat)))
        assertFalse(agent.hasPending(ref, requireNotNull(chat)))
    }
}
