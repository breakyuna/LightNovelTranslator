package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeAgentBusinessProposalsTest {
    @Test fun interruptedApprovalSurvivesRestartAndCannotReuseOldDigest() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("agent-business-").toFile()
        try {
            val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
            val chat = ChatId("chat")
            val run = RunIdentity(ref.projectId, chat, RunId("run"), CommandId("command"))
            val store = NativeAgentBusinessProposals(root)
            val pending = store.prepare(ref, run, 1, "enqueue_translation", buildJsonObject { put("name", "Fixed content") })
            val dispatchCommand = CommandId("agent-enqueue-" + pending.id)
            assertFalse(store.translationAuthorized(ref, dispatchCommand))
            assertTrue(store.translationAuthorized(ref, CommandId("manual-queue-command")))
            val applying = store.transition(ref, chat, pending, "APPLYING")
            val reopened = NativeAgentBusinessProposals(root)
            assertEquals("APPLYING", reopened.pending(ref, chat)!!.status)
            assertFalse(reopened.translationAuthorized(ref, dispatchCommand))
            try {
                reopened.transition(ref, chat, pending, "COMMITTED")
                fail("Reused old approval digest")
            } catch (_: IllegalArgumentException) { }
            reopened.transition(ref, chat, applying, "COMMITTED")
            assertNull(reopened.pending(ref, chat))
            assertTrue(reopened.translationAuthorized(ref, dispatchCommand))
            assertFalse(reopened.translationAuthorized(ref.copy(generation = RecoveryGeneration(2)), dispatchCommand))
            assertEquals("COMMITTED", reopened.status(ref, chat, pending.id)!!.status)
            assertNull(reopened.status(ref.copy(instanceId = ProjectInstanceId("other")), chat, pending.id))
            store.prepare(ref, run, 1, "install_skill", buildJsonObject { put("name", "Next content") })
            assertEquals("COMMITTED", reopened.status(ref, chat, pending.id)!!.status)
        } finally { root.deleteRecursively() }
    }
}
