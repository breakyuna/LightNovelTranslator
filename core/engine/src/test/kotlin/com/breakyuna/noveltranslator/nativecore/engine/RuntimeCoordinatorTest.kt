package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.ChatIdentity
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.OperationKind
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RunLease
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeOwner
import com.breakyuna.noveltranslator.nativecore.domain.RuntimePhase
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeUnavailable
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCoordinatorTest {
    private val firstChat = ChatIdentity(ProjectId("project-a"), ChatId("chat-a"))
    private val secondChat = ChatIdentity(ProjectId("project-b"), ChatId("chat-b"))

    @Test
    fun reservationBlocksSwitchingEvenBeforeWorkLaunches() {
        val runtime = runtime()
        val run = runtime.beginRun(identity("one"), RuntimeOwner.AGENT)
        assertReason(RuntimeUnavailable.Reason.BUSY) { runtime.selectChat(secondChat) }
        assertReason(RuntimeUnavailable.Reason.BUSY) {
            runtime.beginRun(identity("two"), RuntimeOwner.AGENT)
        }
        assertEquals(firstChat, runtime.snapshot().selectedChat)
        runtime.finishRun(run)
        runtime.selectChat(secondChat)
        assertEquals(secondChat, runtime.snapshot().selectedChat)
    }

    @Test
    fun concurrentSendAndSwitchHaveOneConsistentWinner() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(50) {
                val runtime = runtime()
                val start = CountDownLatch(1)
                val send = pool.submit(Callable {
                    check(start.await(5, TimeUnit.SECONDS))
                    try { runtime.beginRun(identity("race"), RuntimeOwner.AGENT) }
                    catch (_: RuntimeUnavailable) { null }
                })
                val switch = pool.submit(Callable {
                    check(start.await(5, TimeUnit.SECONDS))
                    try { runtime.selectChat(secondChat); true }
                    catch (_: RuntimeUnavailable) { false }
                })
                start.countDown()
                val lease = send.get(5, TimeUnit.SECONDS)
                val switched = switch.get(5, TimeUnit.SECONDS)
                assertEquals(lease == null, switched)
                assertEquals(if (switched) secondChat else firstChat, runtime.snapshot().selectedChat)
                assertEquals(lease?.identity, runtime.snapshot().activeRun?.identity)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun stopAndFinishWaitForRequestsScriptsAndWritesToClose() {
        val runtime = runtime()
        val run = runtime.beginRun(identity("one"), RuntimeOwner.AGENT)
        val request = runtime.beginOperation(run, OperationKind.MODEL_REQUEST)
        val script = runtime.beginOperation(run, OperationKind.SCRIPT)
        val write = runtime.beginProjectWrite(run)
        assertTrue(runtime.requestStop(run))
        assertEquals(RuntimePhase.STOPPING, runtime.snapshot().activeRun?.phase)
        assertReason(RuntimeUnavailable.Reason.STOPPING) {
            runtime.beginOperation(run, OperationKind.MODEL_REQUEST)
        }
        runtime.finishRun(run)
        assertEquals(RuntimePhase.FINISHING, runtime.snapshot().activeRun?.phase)
        runtime.endOperation(request)
        runtime.endOperation(script)
        assertNotNull(runtime.snapshot().activeRun)
        assertReason(RuntimeUnavailable.Reason.BUSY) {
            runtime.beginRun(identity("two"), RuntimeOwner.AGENT)
        }
        runtime.endProjectWrite(write)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test
    fun finishBlocksNewChildrenAndRepeatedWriteReleaseDoesNotUnlockAnotherWrite() {
        val runtime = runtime()
        val run = runtime.beginRun(identity("one"), RuntimeOwner.AGENT)
        val first = runtime.beginProjectWrite(run)
        runtime.endProjectWrite(first)
        val second = runtime.beginProjectWrite(run)
        runtime.endProjectWrite(first)
        assertTrue(runtime.snapshot().projectWriteRunning)
        runtime.finishRun(run)
        assertReason(RuntimeUnavailable.Reason.STOPPING) {
            runtime.beginOperation(run, OperationKind.HOST_REQUEST)
        }
        runtime.endProjectWrite(second)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test
    fun staleCleanupAndStopCannotReleaseOrCancelANewRun() {
        val runtime = runtime()
        val old = runtime.beginRun(identity("old"), RuntimeOwner.AGENT)
        val child = runtime.beginOperation(old, OperationKind.TOOL)
        runtime.endOperation(child)
        runtime.finishRun(old)
        val current = runtime.beginRun(identity("new"), RuntimeOwner.AGENT)
        runtime.endOperation(child)
        runtime.finishRun(old)
        assertFalse(runtime.requestStop(old))
        assertReason(RuntimeUnavailable.Reason.STALE_EXECUTION) {
            runtime.beginOperation(old, OperationKind.MODEL_REQUEST)
        }
        assertEquals(current.identity, runtime.snapshot().activeRun?.identity)
        assertEquals(RuntimePhase.RUNNING, runtime.snapshot().activeRun?.phase)
    }

    @Test
    fun equalIdentityDoesNotAuthorizeAForgedHandle() {
        val runtime = runtime()
        val run = runtime.beginRun(identity("one"), RuntimeOwner.AGENT)
        val forged = object : RunLease { override val identity = run.identity }
        assertFalse(runtime.requestStop(forged))
        runtime.finishRun(forged)
        assertReason(RuntimeUnavailable.Reason.STALE_EXECUTION) {
            runtime.beginProjectWrite(forged)
        }
        assertNotNull(runtime.snapshot().activeRun)
    }

    @Test
    fun skillWritesBlockAgentButAllowIndependentTranslation() {
        val runtime = runtime()
        val skill = runtime.beginSkillWrite()
        assertReason(RuntimeUnavailable.Reason.BUSY) {
            runtime.beginRun(identity("agent"), RuntimeOwner.AGENT)
        }
        val translation = runtime.beginRun(identity("translation"), RuntimeOwner.TRANSLATION)
        val write = runtime.beginProjectWrite(translation)
        runtime.endProjectWrite(write)
        runtime.finishRun(translation)
        runtime.endSkillWrite(skill)
        val agent = runtime.beginRun(identity("agent"), RuntimeOwner.AGENT)
        assertReason(RuntimeUnavailable.Reason.BUSY) { runtime.beginSkillWrite() }
        runtime.finishRun(agent)
    }

    @Test
    fun authenticationCommitBlocksNewRunsAndRejectsDuplicateRelease() {
        val runtime = runtime()
        val first = runtime.beginAuthenticationWrite()
        assertReason(RuntimeUnavailable.Reason.BUSY) {
            runtime.beginRun(identity("agent"), RuntimeOwner.AGENT)
        }
        runtime.endAuthenticationWrite(first)
        val second = runtime.beginAuthenticationWrite()
        runtime.endAuthenticationWrite(first)
        assertTrue(runtime.snapshot().authenticationWriteRunning)
        runtime.endAuthenticationWrite(second)
        assertFalse(runtime.snapshot().authenticationWriteRunning)
    }

    @Test
    fun closedRuntimeRetainsOccupancyUntilResourcesActuallyFinish() {
        val runtime = runtime()
        val run = runtime.beginRun(identity("one"), RuntimeOwner.AGENT)
        val request = runtime.beginOperation(run, OperationKind.HOST_REQUEST)
        runtime.close()
        assertTrue(runtime.snapshot().closed)
        assertNotNull(runtime.snapshot().activeRun)
        assertReason(RuntimeUnavailable.Reason.CLOSED) {
            runtime.beginOperation(run, OperationKind.MODEL_REQUEST)
        }
        runtime.finishRun(run)
        runtime.endOperation(request)
        assertNull(runtime.snapshot().activeRun)
        assertReason(RuntimeUnavailable.Reason.CLOSED) { runtime.selectChat(secondChat) }
    }

    @Test
    fun standaloneWritesAndForeignProjectsCannotStealRunOccupancy() {
        val runtime = runtime()
        val write = runtime.beginProjectWrite()
        assertReason(RuntimeUnavailable.Reason.BUSY) { runtime.selectChat(secondChat) }
        assertReason(RuntimeUnavailable.Reason.BUSY) {
            runtime.beginRun(identity("agent"), RuntimeOwner.AGENT)
        }
        runtime.endProjectWrite(write)
        assertReason(RuntimeUnavailable.Reason.WRONG_PROJECT) {
            runtime.beginRun(identity("foreign").copy(projectId = secondChat.projectId), RuntimeOwner.AGENT)
        }
        assertReason(RuntimeUnavailable.Reason.WRONG_PROJECT) {
            runtime.beginRun(identity("missing-chat").copy(chatId = null), RuntimeOwner.AGENT)
        }
    }

    @Test
    fun snapshotPublicationAlwaysIncludesSelectedIdentityAndOccupancy() {
        val runtime = runtime()
        val before = runtime.snapshots.value
        val run = runtime.beginRun(identity("agent"), RuntimeOwner.AGENT)
        val after = runtime.snapshots.value
        assertTrue(after.revision > before.revision)
        assertEquals(firstChat, after.selectedChat)
        assertEquals(run.identity, after.activeRun?.identity)
        runtime.finishRun(run)
        assertNull(runtime.snapshots.value.activeRun)
        assertNotNull(after.activeRun)
    }

    private fun runtime() = RuntimeCoordinator().also { it.selectChat(firstChat) }

    private fun identity(label: String) = RunIdentity(
        firstChat.projectId, firstChat.chatId, RunId(label), CommandId(label),
    )

    private fun assertReason(reason: RuntimeUnavailable.Reason, action: () -> Unit) {
        assertEquals(reason, assertThrows(RuntimeUnavailable::class.java) { action() }.reason)
    }
}
