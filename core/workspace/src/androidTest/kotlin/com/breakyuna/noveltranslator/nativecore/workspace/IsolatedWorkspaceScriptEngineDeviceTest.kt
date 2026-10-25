package com.breakyuna.noveltranslator.nativecore.workspace

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Real Binder/native checks; JVM Robolectric cannot establish process isolation or VM exit. */
@RunWith(AndroidJUnit4::class)
class IsolatedWorkspaceScriptEngineDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val noMaterials = object : WorkspaceScriptMaterialReadPort {
        override fun read(id: WorkspaceMaterialId, limitBytes: Long): WorkspaceMaterialContent =
            error("Material must not be read")
    }

    private fun engine(source: String) = IsolatedWorkspaceScriptEngine(context,
        object : WorkspaceScriptSourcePort {
            override suspend fun resolve(scriptId: String, entryPoint: String) =
                WorkspaceScriptSource(scriptId, entryPoint, source)
        }, noMaterials)

    private fun request(timeout: Long = 20_000, cancellation: WorkspaceCancellation = WorkspaceCancellation(),
                        capabilities: Set<WorkspaceCapability> = emptySet()) = WorkspaceScriptRequest(
        CommandId(UUID.randomUUID().toString()), ProjectId("isolation-project"), ChatId("isolation-chat"),
        RunId(UUID.randomUUID().toString()), "fixture", "main.js", mapOf("word" to "日本語"),
        emptyList(), capabilities, WorkspaceScriptLimits.of(WorkspaceBudget()),
        System.currentTimeMillis() + timeout, cancellation)

    @Test fun isolatedUidAndLargeSourceDoNotUseBinderTransactionBody() = runBlocking {
        val service = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, WorkspaceInterpreterService::class.java), 0)
        assertTrue(service.flags and android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
        assertTrue(!service.exported)
        val source = "/*" + "x".repeat(300_000) + "*/\n" +
            "lg.log(lg.arguments.word); if (typeof process !== 'undefined') return 3; return 0;"
        val result = engine(source).execute(request())
        assertEquals(0, result.exitCode)
        assertEquals("日本語", result.logs.single().text)
        assertTrue(result.changes.isEmpty())
    }

    @Test fun caughtDeniedMutationCannotReturnSuccessfulPartialOutput() = runBlocking {
        val result = engine("try { lg.emitChange({path:'work/a.txt',operation:'CREATE',content:'bad'}); } catch (e) {} return 0;")
            .execute(request())
        assertTrue(result.exitCode != 0)
        assertTrue(result.changes.isEmpty())
    }

    @Test fun materialLargerThanBinderBufferIsReadThroughBoundedDescriptorStream() = runBlocking {
        val bytes = "a".repeat(2 * 1024 * 1024).toByteArray(Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val materialId = WorkspaceMaterialId("large-fixture")
        val source = WorkspaceScriptSource("fixture", "main.js",
            "lg.log(String(lg.readMaterial(lg.materials[0].id).length)); return 0;")
        val isolated = IsolatedWorkspaceScriptEngine(context, object : WorkspaceScriptSourcePort {
            override suspend fun resolve(scriptId: String, entryPoint: String) = source
        }, object : WorkspaceScriptMaterialReadPort {
            override fun read(id: WorkspaceMaterialId, limitBytes: Long): WorkspaceMaterialContent {
                assertEquals(materialId, id)
                assertTrue(bytes.size.toLong() <= limitBytes)
                return WorkspaceMaterialContent(id, sha, bytes)
            }
        })
        val original = request(capabilities = setOf(WorkspaceCapability.READ_MATERIALS))
        val result = isolated.execute(WorkspaceScriptRequest(original.commandId, original.projectId, original.chatId,
            original.runId, original.scriptId, original.entryPoint, materials = listOf(WorkspaceMaterialDescriptor(
                materialId, WorkspacePath("datasets/large.txt"), sha, bytes.size.toLong(), "text/plain", 0)),
            grantedCapabilities = original.grantedCapabilities, limits = original.limits,
            deadlineAtMillis = original.deadlineAtMillis, cancellation = original.cancellation))
        assertEquals(0, result.exitCode)
        assertEquals(bytes.size.toString(), result.logs.single().text)
    }

    @Test fun declaredProposalIsValidatedAndReturnedWithoutWriting() = runBlocking {
        val result = engine("lg.emitChange({path:'work/a.txt',operation:'CREATE',content:'translated'}); return 0;")
            .execute(request(capabilities = setOf(WorkspaceCapability.PROPOSE_CHANGES)))
        assertEquals(0, result.exitCode)
        assertEquals("work/a.txt", result.changes.single().path.value)
        assertEquals("translated", String((result.changes.single().content as WorkspaceChangeContent.Inline).bytes, Charsets.UTF_8))
    }

    @Test fun infiniteLoopTimesOutWithoutKillingApplicationAndNextInvocationWorks() = runBlocking {
        val pid = Process.myPid()
        try {
            engine("while (true) {}").execute(request(timeout = 5_000))
            fail("Infinite loop must time out")
        } catch (_: WorkspaceScriptTimeoutException) { }
        assertEquals(pid, Process.myPid())
        assertEquals(0, engine("return 0;").execute(request()).exitCode)
    }

    @Test fun explicitCancellationKillsBusyInterpreterAndRevokesHost() = runBlocking {
        val cancellation = WorkspaceCancellation()
        val stopper = Thread { Thread.sleep(3_000); cancellation.cancel() }.apply { start() }
        try {
            engine("while (true) {}").execute(request(cancellation = cancellation))
            fail("Cancelled script must not complete")
        } catch (_: CancellationException) { }
        finally { stopper.join() }
        assertEquals(0, engine("return 0;").execute(request()).exitCode)
    }
}
