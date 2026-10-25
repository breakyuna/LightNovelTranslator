package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.storage.*
import com.breakyuna.noveltranslator.nativecore.workspace.ProjectWorkspaceAnalysis
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactDraft
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspacePath
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
class NativeAssetImportProposalToolTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var agent: NativeAgentStore
    private lateinit var items: NativeItemProposalStore
    private lateinit var policies: NativePolicyProposalStore
    private lateinit var pdfPages: NativePdfPageProposalStore
    private lateinit var assets: NativeAssetImportProposalStore
    private lateinit var access: NativeWorkspaceAccess
    private lateinit var workspace: NativeWorkspaceRun
    private lateinit var tools: AgentToolPort
    private lateinit var ref: LocalProjectRef
    private lateinit var lease: RunLease
    private lateinit var root: File

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        runtime = RuntimeCoordinator()
        projects = NativeTextProjectStore(context, runtime)
        agent = NativeAgentStore(context, projects, runtime)
        items = NativeItemProposalStore(context, projects, runtime)
        policies = NativePolicyProposalStore(context, projects, runtime)
        pdfPages = NativePdfPageProposalStore(context, projects, runtime)
        assets = NativeAssetImportProposalStore(context, projects, runtime,
            AssetImportApplier { _, _ -> error("The tool test never applies") })
        ref = projects.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"),
            "source".toByteArray()).ref
        val chat = agent.currentChat(ref)
        runtime.selectChat(ChatIdentity(ref.projectId, chat))
        lease = runtime.beginRun(RunIdentity(ref.projectId, chat, RunId("run"), CommandId("send")),
            RuntimeOwner.AGENT)
        root = File(context.cacheDir, "native-asset-import-tool-" + UUID.randomUUID()).apply { mkdirs() }
        access = NativeWorkspaceAccess(context, projects, projects, root)
        workspace = NativeWorkspaceRun(ref, lease, access)
        tools = nativeWorkspaceAnalysisTools(runtime, ProjectWorkspaceAnalysis(context, projects), ref, lease,
            items, policies, pdfPages, workspace, assetImportProposals = assets)
    }

    @After fun cleanup() = runBlocking {
        runCatching { root.deleteRecursively() }
        runCatching { runtime.finishRun(lease) }
        runCatching { runtime.selectChat(null) }
        assets.close(); pdfPages.close(); policies.close(); items.close(); agent.close(); projects.close()
        runtime.close()
    }

    private suspend fun call(name: String, json: String): AgentToolOutcome =
        tools.execute(lease, AgentToolCall(ToolCallId("call-" + UUID.randomUUID()), name,
            JsonObjectValue.parse(json, JsonInputLimits(json.length.coerceAtLeast(1), 8))))

    private fun success(outcome: AgentToolOutcome): JsonObject {
        assertTrue(outcome is AgentToolOutcome.Success)
        return (outcome as AgentToolOutcome.Success).result.json
    }

    private fun failure(outcome: AgentToolOutcome): AgentToolOutcome.Failure {
        assertTrue(outcome is AgentToolOutcome.Failure)
        return outcome as AgentToolOutcome.Failure
    }

    @Test fun prepareUsesOnlyThisRunsPersistedArtifactAndStatusNeverApplies() = runBlocking {
        val artifact = access.publishArtifacts(ref, lease.identity.runId, listOf(
            WorkspaceArtifactDraft(WorkspacePath("report.md"), "text/markdown", "# report\n".toByteArray()),
        )).single()
        val names = tools.definitions().map { it.name }.toSet()
        assertTrue("prepare_asset_import" in names)
        assertTrue("asset_import_status" in names)

        val prepared = success(call("prepare_asset_import",
            """{"artifact_id":"${artifact.id.value}","target_path":"imports/report.md","enabled":false}"""))
        val id = prepared.getValue("proposal").jsonPrimitive.content
        assertEquals("PENDING", prepared.getValue("state").jsonPrimitive.content)
        assertEquals(artifact.sha256, prepared.getValue("artifact_sha256").jsonPrimitive.content)
        assertEquals(artifact.sizeBytes, prepared.getValue("artifact_size").jsonPrimitive.long)
        assertFalse(prepared.getValue("enabled").jsonPrimitive.content.toBooleanStrict())

        val status = success(call("asset_import_status", """{"proposal":"$id"}"""))
        assertEquals("PENDING", status.getValue("state").jsonPrimitive.content)
        assertFalse(status.getValue("receipt_present").jsonPrimitive.content.toBooleanStrict())
        assertEquals(id, assets.pending(ref, requireNotNull(lease.identity.chatId))!!.id.value)

        assertEquals("tool_failed", failure(call("prepare_asset_import",
            """{"artifact_id":"missing","target_path":"imports/other.md"}""")).code)
        assertEquals("tool_failed", failure(call("prepare_asset_import",
            """{"artifact_id":"${artifact.id.value}","target_path":"../escape.md"}""")).code)
        assertEquals(id, assets.pending(ref, requireNotNull(lease.identity.chatId))!!.id.value)
    }
}
