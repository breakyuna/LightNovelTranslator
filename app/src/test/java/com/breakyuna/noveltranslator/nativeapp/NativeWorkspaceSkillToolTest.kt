package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.AgentToolPort
import com.breakyuna.noveltranslator.nativecore.domain.AgentToolCall
import com.breakyuna.noveltranslator.nativecore.domain.AgentToolOutcome
import com.breakyuna.noveltranslator.nativecore.domain.ChatIdentity
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RunLease
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeOwner
import com.breakyuna.noveltranslator.nativecore.domain.ToolCallId
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.storage.NativeAgentStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeItemProposalStore
import com.breakyuna.noveltranslator.nativecore.storage.NativePdfPageProposalStore
import com.breakyuna.noveltranslator.nativecore.storage.NativePolicyProposalStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeTextProjectStore
import com.breakyuna.noveltranslator.nativecore.workspace.ProjectWorkspaceAnalysis
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillDraft
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillPolicy
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillPolicyScriptSource
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeWorkspaceSkillToolTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var agent: NativeAgentStore
    private lateinit var items: NativeItemProposalStore
    private lateinit var policies: NativePolicyProposalStore
    private lateinit var pdfPages: NativePdfPageProposalStore
    private lateinit var skills: WorkspaceSkillStore
    private lateinit var tools: AgentToolPort
    private lateinit var ref: LocalProjectRef
    private lateinit var lease: RunLease

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        runtime = RuntimeCoordinator()
        projects = NativeTextProjectStore(context, runtime)
        agent = NativeAgentStore(context, projects, runtime)
        items = NativeItemProposalStore(context, projects, runtime)
        policies = NativePolicyProposalStore(context, projects, runtime)
        pdfPages = NativePdfPageProposalStore(context, projects, runtime)
        skills = WorkspaceSkillStore(temporary.newFolder("skills"))
        ref = projects.createTxt(
            CommandId("create"), "fixture", RelativePath("source.txt"), "source".toByteArray(),
        ).ref
        val chat = agent.currentChat(ref)
        runtime.selectChat(ChatIdentity(ref.projectId, chat))
        lease = runtime.beginRun(
            RunIdentity(ref.projectId, chat, RunId("run"), CommandId("send")),
            RuntimeOwner.AGENT,
        )
        val workspaceRun = NativeWorkspaceRun(
            ref,
            lease,
            NativeWorkspaceAccess(context, projects, projects),
        )
        tools = nativeWorkspaceAnalysisTools(
            runtime,
            ProjectWorkspaceAnalysis(context, projects),
            ref,
            lease,
            items,
            policies,
            pdfPages,
            workspaceRun,
            skills = skills,
            skillFiles = skills,
            skillPolicyCeiling = NATIVE_SKILL_RUNTIME_CEILING,
        )
    }

    @After fun cleanup() = runBlocking {
        runtime.finishRun(lease)
        runtime.selectChat(null)
        pdfPages.close()
        policies.close()
        items.close()
        agent.close()
        projects.close()
        runtime.close()
    }

    private suspend fun call(name: String, json: String): AgentToolOutcome =
        tools.execute(lease, AgentToolCall(
            ToolCallId("call"),
            name,
            JsonObjectValue.parse(json, JsonInputLimits(json.length.coerceAtLeast(1), 8)),
        ))

    @Test fun runProjectSkillSchemaHasNoModelNetworkSwitch() {
        val definition = tools.definitions().single { it.name == "run_project_skill" }
        val properties = (definition.inputSchema.json["properties"] as JsonObject).keys
        assertFalse("allow_fetch" in properties)
        assertTrue("skill_id" in properties)
        assertTrue("entry_point" in properties)
        assertTrue("input_json" in properties)
        assertTrue(definition.description.contains("stored policy"))
        assertFalse(definition.description.contains("allow_fetch"))
    }

    @Test fun modelSuppliedAllowFetchIsRejectedAsAnUnknownField() = runBlocking {
        val outcome = call("run_project_skill", """{"skill_id":"fixture","allow_fetch":true}""")
        assertTrue(outcome is AgentToolOutcome.Failure)
        assertEquals("unknown_field", (outcome as AgentToolOutcome.Failure).field)
    }

    @Test fun runtimeCeilingAndStoredPolicyCanOnlyNarrowNetworkAndProposals() = runBlocking {
        val id = WorkspaceSkillId("fixture")
        val created = skills.create(WorkspaceSkillDraft(
            id = id,
            name = "Fixture",
            content = "body",
            policy = WorkspaceSkillPolicy(allowFetch = true, allowProposeChanges = true),
        ))
        skills.writeResource(id, "scripts/main.js", "module.exports = () => 1",
            created.fingerprint, null)

        val restricted = WorkspaceSkillPolicyScriptSource(
            skills,
            skills,
            WorkspaceSkillPolicy(allowFetch = false, allowProposeChanges = false),
        ).resolveForExecution(id.value, "scripts/main.js")
        assertFalse(restricted.policy.allowFetch)
        assertFalse(restricted.policy.allowProposeChanges)

        val denyId = WorkspaceSkillId("deny")
        val denying = skills.create(WorkspaceSkillDraft(
            id = denyId,
            name = "Deny",
            content = "body",
            order = 1,
        ))
        skills.writeResource(denyId, "scripts/main.js", "module.exports = () => 1",
            denying.fingerprint, null)
        val ceilingOnly = WorkspaceSkillPolicyScriptSource(
            skills,
            skills,
            NATIVE_SKILL_RUNTIME_CEILING,
        ).resolveForExecution(denyId.value, "scripts/main.js")
        assertFalse(ceilingOnly.policy.allowFetch)
        assertFalse(ceilingOnly.policy.allowProposeChanges)
        assertEquals(WorkspaceSkillPolicy.DEFAULT.maxRunMillis, ceilingOnly.policy.maxRunMillis)
    }
}
