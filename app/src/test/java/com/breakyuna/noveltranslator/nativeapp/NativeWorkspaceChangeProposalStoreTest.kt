package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetPort
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RunLease
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectPort
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceApplyStatus
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceChange
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceChangeContent
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceChangeOperation
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceChangeSet
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceChangeSetId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceChangeSummary
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceDigest
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspacePath
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeWorkspaceChangeProposalStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val ref = LocalProjectRef(
        ProjectId("project-1"),
        ProjectInstanceId("instance-1"),
        RecoveryGeneration(1),
    )
    private val chat = ChatId("chat-1")
    private lateinit var changesRoot: File
    private lateinit var workspaceRoot: File
    private lateinit var receiptsRoot: File
    private var clock = 1_000L

    @Before
    fun setUp() {
        changesRoot = temporary.newFolder("changes")
        workspaceRoot = temporary.newFolder("workspace")
        receiptsRoot = temporary.newFolder("receipts")
    }

    private fun store(): NativeWorkspaceChangeProposalStore = NativeWorkspaceChangeProposalStore(
        changesRoot = changesRoot,
        workspaceRoots = { workspaceRoot },
        receiptRoot = receiptsRoot,
        now = { clock++ },
    )

    private fun failingStore(): NativeWorkspaceChangeProposalStore = NativeWorkspaceChangeProposalStore(
        changesRoot = changesRoot,
        workspaceRoots = { workspaceRoot },
        receiptRoot = receiptsRoot,
        now = { clock++ },
        applyApproved = { _, _ -> throw AssertionError("simulated process death after claim") },
    )

    private fun lease(
        run: String = "run-1",
        command: String = "command-1",
        chatId: ChatId = chat,
    ): RunLease = object : RunLease {
        override val identity = RunIdentity(ref.projectId, chatId, RunId(run), CommandId(command))
    }

    private fun batch(
        id: String,
        chatId: ChatId = chat,
        run: String = "run-1",
        vararg changes: WorkspaceChange,
    ): WorkspaceChangeSet = WorkspaceChangeSet(
        WorkspaceChangeSetId(id),
        ref.projectId,
        chatId,
        RunId(run),
        changes.toList(),
    )

    private fun create(path: String, content: String): WorkspaceChange = WorkspaceChange(
        WorkspacePath(path),
        WorkspaceChangeOperation.CREATE,
        null,
        WorkspaceChangeContent.Inline(content.toByteArray(Charsets.UTF_8)),
    )

    private fun withDigest(
        view: WorkspaceChangeProposalSummary,
        digest: WorkspaceDigest,
    ): WorkspaceChangeProposalSummary = WorkspaceChangeProposalSummary(
        view.id,
        view.ref,
        view.chat,
        view.run,
        view.command,
        view.baseRevision,
        digest,
        view.summary,
        view.paths,
        view.state,
        view.createdAt,
        view.receipt,
    )

    @Test
    fun prepareFreezesTheBatchAndRepeatedRunCommandIsIdempotent() = runBlocking {
        val proposalStore = store()
        val lease = lease()
        val first = proposalStore.prepare(
            lease,
            ref,
            7,
            batch("set-1", chat, "run-1", create("work/report.txt", "hello")),
        )
        assertEquals(WorkspaceChangeProposalState.PENDING, first.state)
        assertEquals(7L, first.baseRevision)
        assertEquals(WorkspaceChangeSummary(1, 0, 0, 5), first.summary)
        assertEquals(lease.identity.commandId, first.command)
        assertFalse(first.toString().contains("hello"))

        val again = proposalStore.prepare(
            lease,
            ref,
            8,
            batch("set-1", chat, "run-1", create("work/report.txt", "hello")),
        )
        assertEquals(first.id, again.id)
        assertEquals(first.baseRevision, again.baseRevision)
        assertEquals(first.createdAt, again.createdAt)
        assertEquals(first.digest, again.digest)
        assertEquals(1, proposalStore.recent(ref, chat).size)
        assertEquals(first.id, proposalStore.pending(ref, chat)?.id)
    }

    @Test
    fun onePendingProposalPerChatAndRejectReleasesTheSlot() = runBlocking {
        val proposalStore = store()
        val firstLease = lease("run-1", "command-1")
        val first = proposalStore.prepare(
            firstLease,
            ref,
            0,
            batch("set-1", chat, "run-1", create("work/a.txt", "a")),
        )

        var rejected = false
        try {
            proposalStore.prepare(
                lease("run-2", "command-2"),
                ref,
                0,
                batch("set-2", chat, "run-2", create("work/b.txt", "b")),
            )
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(WorkspaceChangeProposalState.REJECTED, proposalStore.reject(first))
        assertNull(proposalStore.pending(ref, chat))

        val second = proposalStore.prepare(
            lease("run-2", "command-2"),
            ref,
            0,
            batch("set-2", chat, "run-2", create("work/b.txt", "b")),
        )
        assertEquals(WorkspaceChangeProposalState.PENDING, second.state)
        assertNotNull(proposalStore.pending(ref, chat))
    }

    @Test
    fun applyCommitsOnceAndTerminalReplayDoesNotRewriteTheFile() = runBlocking {
        val proposalStore = store()
        val lease = lease("run-apply", "command-apply")
        val view = proposalStore.prepare(
            lease,
            ref,
            4,
            batch("set-apply", chat, "run-apply", create("work/out.txt", "hello")),
        )

        assertEquals(WorkspaceChangeProposalState.APPLIED, proposalStore.apply(lease, view))
        val target = File(workspaceRoot, "work/out.txt")
        assertEquals("hello", target.readText())
        val applied = proposalStore.find(ref, chat, view.id)
        assertEquals(WorkspaceChangeProposalState.APPLIED, applied.state)
        assertEquals(WorkspaceApplyStatus.COMMITTED, applied.receiptStatus)
        assertEquals(1, applied.receipt?.committed)
        assertNull(proposalStore.pending(ref, chat))
        assertEquals(WorkspaceChangeProposalState.APPLIED, proposalStore.apply(lease, applied))

        target.writeText("user edit")
        assertEquals(WorkspaceChangeProposalState.APPLIED, proposalStore.apply(lease, view))
        assertEquals("user edit", target.readText())
    }

    @Test
    fun readOnlyWorkspaceAreaMapsTheWriterRejectionToRejected() = runBlocking {
        val proposalStore = store()
        val lease = lease("run-readonly", "command-readonly")
        val view = proposalStore.prepare(
            lease,
            ref,
            2,
            batch("set-readonly", chat, "run-readonly", create("sources/source.txt", "blocked")),
        )

        assertEquals(WorkspaceChangeProposalState.REJECTED, proposalStore.apply(lease, view))
        val rejected = proposalStore.find(ref, chat, view.id)
        assertEquals(WorkspaceChangeProposalState.REJECTED, rejected.state)
        assertEquals(WorkspaceApplyStatus.REJECTED, rejected.receiptStatus)
        assertEquals(1, rejected.receipt?.conflicts)
        assertFalse(File(workspaceRoot, "sources/source.txt").exists())
    }

    @Test
    fun staleViewDigestIsRejectedBeforeClaimingOrWriting() = runBlocking {
        val proposalStore = store()
        val lease = lease("run-stale", "command-stale")
        val view = proposalStore.prepare(
            lease,
            ref,
            3,
            batch("set-stale", chat, "run-stale", create("work/stale.txt", "stale")),
        )
        val stale = withDigest(view, WorkspaceDigest("0".repeat(64)))

        var rejected = false
        try {
            proposalStore.apply(lease, stale)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(WorkspaceChangeProposalState.PENDING, proposalStore.find(ref, chat, view.id).state)
        assertNotNull(proposalStore.pending(ref, chat))
        assertFalse(File(workspaceRoot, "work/stale.txt").exists())
    }

    @Test
    fun aCommandCannotBeReusedForAnotherChangeSet() = runBlocking {
        val proposalStore = store()
        val lease = lease("run-1", "shared-command")
        proposalStore.prepare(
            lease,
            ref,
            0,
            batch("set-1", chat, "run-1", create("work/a.txt", "a")),
        )

        var rejected = false
        try {
            proposalStore.prepare(
                lease("run-2", "shared-command", ChatId("chat-2")),
                ref,
                0,
                batch(
                    "set-2",
                    ChatId("chat-2"),
                    "run-2",
                    create("work/b.txt", "b"),
                ),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun reconcileTurnsAnInterruptedClaimIntoConflictWithoutReplaying() = runBlocking {
        val proposalStore = failingStore()
        val lease = lease("run-crash", "command-crash")
        val view = proposalStore.prepare(
            lease,
            ref,
            1,
            batch("set-crash", chat, "run-crash", create("work/crash.txt", "crash")),
        )

        var failed = false
        try {
            proposalStore.apply(lease, view)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(WorkspaceChangeProposalState.PENDING, proposalStore.find(ref, chat, view.id).state)

        assertEquals(1, proposalStore.reconcile(ref, chat))
        assertEquals(WorkspaceChangeProposalState.CONFLICT, proposalStore.find(ref, chat, view.id).state)
        assertEquals(0, proposalStore.reconcile(ref, chat))
        assertEquals(WorkspaceChangeProposalState.CONFLICT, proposalStore.apply(lease, view))
        assertFalse(File(workspaceRoot, "work/crash.txt").exists())
    }

    @Test
    fun rejectCannotTurnAClaimedProposalIntoAUserRejection() = runBlocking {
        val proposalStore = failingStore()
        val lease = lease("run-claimed", "command-claimed")
        val view = proposalStore.prepare(
            lease,
            ref,
            1,
            batch("set-claimed", chat, "run-claimed", create("work/claimed.txt", "claimed")),
        )
        try {
            proposalStore.apply(lease, view)
        } catch (_: AssertionError) {
            // The claim was persisted before the simulated process death.
        }

        assertEquals(WorkspaceChangeProposalState.CONFLICT, proposalStore.reject(view))
        assertEquals(WorkspaceChangeProposalState.CONFLICT, proposalStore.find(ref, chat, view.id).state)
    }

    @Test
    fun productionCompositionSharesTheNativeWorkspaceRoot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val projects = Proxy.newProxyInstance(
            TextProjectPort::class.java.classLoader,
            arrayOf(TextProjectPort::class.java),
        ) { _, method, _ -> error("Unexpected project call: ${method.name}") } as TextProjectPort
        val assets = Proxy.newProxyInstance(
            ProjectAssetPort::class.java.classLoader,
            arrayOf(ProjectAssetPort::class.java),
        ) { _, method, _ -> error("Unexpected asset call: ${method.name}") } as ProjectAssetPort
        val accessRoot = temporary.newFolder("access-workspace")
        val access = NativeWorkspaceAccess(context, projects, assets, accessRoot)
        File(context.filesDir, "native_workspace_changes").deleteRecursively()
        File(context.filesDir, "native_workspace_receipts").deleteRecursively()
        val proposalStore = NativeWorkspaceChangeProposalStore(context, access)
        val accessChat = ChatId("chat-access")
        val lease = lease("run-access", "command-access", accessChat)
        val view = proposalStore.prepare(
            lease,
            ref,
            0,
            batch("set-access", accessChat, "run-access", create("work/access.txt", "accessed")),
        )

        assertEquals(WorkspaceChangeProposalState.APPLIED, proposalStore.apply(lease, view))
        val root = access.workspaceRoot(ref)
        assertTrue(root.canonicalPath.startsWith(accessRoot.canonicalPath + File.separator))
        assertEquals("accessed", File(root, "work/access.txt").readText())
    }

    @Test
    fun damagedProposalFileFailsClosedAndIsNotDeleted() = runBlocking {
        val proposalStore = store()
        val lease = lease("run-corrupt", "command-corrupt")
        proposalStore.prepare(
            lease,
            ref,
            0,
            batch("set-corrupt", chat, "run-corrupt", create("work/a.txt", "a")),
        )
        val proposalFile = changesRoot.walkTopDown()
            .first { it.isFile && it.name.startsWith("p-") && it.name.endsWith(".json") }
        proposalFile.writeText("{not-json")

        var reason: WorkspaceChangeProposalException.Reason? = null
        try {
            proposalStore.pending(ref, chat)
            fail("Corrupt proposal must not be treated as pending")
        } catch (failure: WorkspaceChangeProposalException) {
            reason = failure.reason
        }
        assertEquals(WorkspaceChangeProposalException.Reason.CORRUPT, reason)
        assertTrue(proposalFile.exists())

        var prepareReason: WorkspaceChangeProposalException.Reason? = null
        try {
            proposalStore.prepare(
                lease("run-new", "command-new"),
                ref,
                0,
                batch("set-new", chat, "run-new", create("work/b.txt", "b")),
            )
            fail("Preparation must fail closed while a damaged proposal exists")
        } catch (failure: WorkspaceChangeProposalException) {
            prepareReason = failure.reason
        }
        assertEquals(WorkspaceChangeProposalException.Reason.CORRUPT, prepareReason)
    }
}
