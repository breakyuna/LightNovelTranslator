package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.agent.AgentToolRegistry
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.storage.NativeTextProjectStore
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactDraft
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialUnavailable
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspacePath
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillDraft
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
class NativeWorkspaceToolTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var skills: WorkspaceSkillStore
    private lateinit var access: NativeWorkspaceAccess
    private lateinit var workspace: NativeWorkspaceRun
    private lateinit var ref: LocalProjectRef
    private lateinit var lease: RunLease
    private lateinit var root: File
    private lateinit var tools: AgentToolPort
    private var chat = ChatId("workspace-chat")

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        runtime = RuntimeCoordinator()
        projects = NativeTextProjectStore(context, runtime)
        ref = projects.createTxt(CommandId("create"), "workspace-fixture", RelativePath("source.txt"),
            "hello world".toByteArray(Charsets.UTF_8)).ref
        runtime.selectChat(ChatIdentity(ref.projectId, chat))
        lease = runtime.beginRun(
            RunIdentity(ref.projectId, chat, RunId("run"), CommandId("send")), RuntimeOwner.AGENT)
        root = File(context.cacheDir, "native-workspace-tool-" + UUID.randomUUID()).apply { mkdirs() }
        access = NativeWorkspaceAccess(context, projects, projects, root)
        workspace = NativeWorkspaceRun(ref, lease, access)
        skills = WorkspaceSkillStore(File(root, "skills"))
        skills.create(WorkspaceSkillDraft(WorkspaceSkillId("user-guide"), "Guide", "Skill body",
            "A test skill", enabled = true, order = 0))
        tools = AgentToolRegistry(runtime, nativeWorkspaceInspectionTools(workspace, skills))
    }

    @After fun cleanup() = runBlocking {
        runCatching { root.deleteRecursively() }
        runCatching { runtime.finishRun(lease) }
        runCatching { runtime.selectChat(null) }
        projects.close(); runtime.close()
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

    private fun materialSource(list: JsonObject): JsonObject =
        list.getValue("materials").jsonArray.map { it.jsonObject }
            .first { it.getValue("kind").jsonPrimitive.content == "SOURCE" }

    @Test fun manifestMaterialsAndSkillsAreReadableOnlyAsBoundedText() = runBlocking {
        assertEquals(setOf("list_workspace_materials", "read_workspace_material", "list_workspace_artifacts",
            "read_workspace_artifact", "list_project_skills", "read_project_skill"),
            tools.definitions().map { it.name }.toSet())
        assertTrue(nativeWorkspaceInspectionTools(workspace, skills).all { it.effect == ToolEffect.READ_ONLY })

        val list = success(call("list_workspace_materials", "{}"))
        assertTrue(list.getValue("revision").jsonPrimitive.long >= 0)
        assertFalse(list.getValue("truncated").jsonPrimitive.content.toBooleanStrict())
        val material = materialSource(list)
        val id = material.getValue("id").jsonPrimitive.content
        assertEquals("sources/source.txt", material.getValue("path").jsonPrimitive.content)
        assertTrue(material.getValue("sha256").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))

        val read = success(call("read_workspace_material", """{"material_id":"$id"}"""))
        assertEquals("hello world", read.getValue("text").jsonPrimitive.content)
        assertEquals(material.getValue("sha256"), read.getValue("sha256"))
        assertEquals("SOURCE", read.getValue("kind").jsonPrimitive.content)

        val skillList = success(call("list_project_skills", "{}"))
        val skill = skillList.getValue("skills").jsonArray.single().jsonObject
        val skillId = skill.getValue("id").jsonPrimitive.content
        assertEquals("Guide", skill.getValue("name").jsonPrimitive.content)
        val skillRead = success(call("read_project_skill", """{"skill_id":"$skillId"}"""))
        assertEquals("Skill body", skillRead.getValue("text").jsonPrimitive.content)
        assertEquals("USER", skillRead.getValue("skill").jsonObject.getValue("source").jsonPrimitive.content)
    }

    @Test fun toolArgumentsNeverBecomeFilesystemPathsAndBinaryOrOversizedBodiesFail() = runBlocking {
        assertEquals("unknown_field",
            failure(call("read_workspace_material", """{"material_id":"x","path":"/etc/passwd"}""")).field)
        assertEquals("material_id", failure(call("read_workspace_material", """{"material_id":""}""")).field)
        assertEquals("tool_failed",
            failure(call("read_workspace_material", """{"material_id":"../../secret.txt"}""")).code)
        assertEquals("tool_failed",
            failure(call("read_workspace_artifact", """{"artifact_id":"../store.json"}""")).code)
        assertEquals("tool_failed",
            failure(call("read_project_skill", """{"skill_id":"../user-guide"}""")).code)

        val textBatch = access.publishArtifacts(ref, lease.identity.runId, listOf(
            WorkspaceArtifactDraft(WorkspacePath("note.md"), "text/markdown", "artifact body".toByteArray()),
            WorkspaceArtifactDraft(WorkspacePath("blob.bin"), "application/octet-stream", byteArrayOf(0, -1)),
            WorkspaceArtifactDraft(WorkspacePath("big.txt"), "text/plain", "x".repeat(70_000).toByteArray()),
        ))
        assertEquals(3, textBatch.size)
        val artifactList = success(call("list_workspace_artifacts", "{}"))
        assertEquals(3, artifactList.getValue("total").jsonPrimitive.content.toInt())
        assertEquals(lease.identity.runId.value, artifactList.getValue("run").jsonPrimitive.content)
        assertEquals(setOf("note.md", "blob.bin", "big.txt"),
            artifactList.getValue("artifacts").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }.toSet())
        val noteEntry = artifactList.getValue("artifacts").jsonArray.map { it.jsonObject }
            .first { it.getValue("path").jsonPrimitive.content == "note.md" }
        assertEquals("MARKDOWN", noteEntry.getValue("kind").jsonPrimitive.content)
        assertEquals(13L, noteEntry.getValue("size").jsonPrimitive.long)
        val note = textBatch.first { it.path.value == "note.md" }
        val read = success(call("read_workspace_artifact", """{"artifact_id":"${note.id.value}"}"""))
        assertEquals("artifact body", read.getValue("text").jsonPrimitive.content)
        assertEquals(note.sha256, read.getValue("sha256").jsonPrimitive.content)
        assertEquals("MARKDOWN", read.getValue("kind").jsonPrimitive.content)
        assertArrayEquals("artifact body".toByteArray(),
            access.readArtifactBytes(ref, lease.identity.runId, note.id))

        val binary = textBatch.first { it.path.value == "blob.bin" }
        assertEquals("tool_failed",
            failure(call("read_workspace_artifact", """{"artifact_id":"${binary.id.value}"}""")).code)
        val big = textBatch.first { it.path.value == "big.txt" }
        assertEquals("tool_failed",
            failure(call("read_workspace_artifact", """{"artifact_id":"${big.id.value}"}""")).code)

        val materialId = WorkspaceMaterialId(materialSource(success(call("list_workspace_materials", "{}")))
            .getValue("id").jsonPrimitive.content)
        var tooLarge: WorkspaceMaterialUnavailable? = null
        try {
            access.readMaterialText(ref, projects.open(ref).revision, materialId, 4)
        } catch (failure: WorkspaceMaterialUnavailable) {
            tooLarge = failure
        }
        assertEquals(WorkspaceMaterialUnavailable.Reason.TOO_LARGE, tooLarge?.reason)
    }

    @Test fun artifactsArePersistedPerRunAndInactiveRunsArePruned() = runBlocking {
        for (index in 0..9) {
            val runId = RunId("run-$index")
            access.publishArtifacts(ref, runId, listOf(WorkspaceArtifactDraft(
                WorkspacePath("report-$index.txt"), "text/plain", "body-$index".toByteArray()
            )), keepInactiveRuns = 1)
        }
        val all = access.listArtifacts(ref, null)
        assertEquals(setOf("run-8", "run-9"), all.map { it.runId.value }.toSet())
        assertTrue(access.listArtifacts(ref, RunId("run-0")).isEmpty())
        val active = access.listArtifacts(ref, RunId("run-9"))
        assertEquals("report-9.txt", active.single().path.value)
        assertEquals("body-9", access.readArtifactText(ref, RunId("run-9"), active.single().id).text)
        assertEquals("tool_failed",
            failure(call("read_workspace_artifact", """{"artifact_id":"${active.single().id.value}"}""")).code)
    }
}
