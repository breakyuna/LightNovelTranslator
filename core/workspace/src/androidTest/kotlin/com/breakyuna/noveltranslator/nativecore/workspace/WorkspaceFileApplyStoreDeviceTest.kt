package com.breakyuna.noveltranslator.nativecore.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device coverage for the workspace file writer: writable-area enforcement, receipt idempotency
 * and crash recovery. The store uses `android.util.AtomicFile`, so this lives in androidTest.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceFileApplyStoreDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val chat = ChatId("chat")

    @Test
    fun applyCommitsWritableBatchAndRepeatsTheSameReceipt() = runBlocking {
        val (root, receipts) = directories()
        val command = CommandId("cmd-commit")
        val changeSet = changeSet("set-commit", listOf(
            create("work/a.txt", "hello"),
        ))
        val store = WorkspaceFileApplyStore(root, ref, receipts)

        val first = store.apply(approved(command, changeSet), WorkspaceConflictPolicy.ACCEPT_UNCONFLICTED)
        assertEquals(WorkspaceApplyStatus.COMMITTED, first.status)
        assertEquals("hello", File(root, "work/a.txt").readText())

        val second = store.apply(approved(command, changeSet), WorkspaceConflictPolicy.ACCEPT_UNCONFLICTED)
        assertEquals(first.status, second.status)
        assertEquals(first.committed.map { it.path.value }, second.committed.map { it.path.value })
        assertEquals(first.committedAtMillis, second.committedAtMillis)
    }

    @Test
    fun applyRejectsGeneratedAndMaterialAreasEvenWhenPreflightDidNot() = runBlocking {
        val (root, receipts) = directories()
        val changeSet = changeSet("set-readonly", listOf(
            create("sources/x.txt", "nope"),
            create("datasets/y.txt", "nope"),
            create("artifacts/z.txt", "nope"),
        ))
        val receipt = WorkspaceFileApplyStore(root, ref, receipts)
            .apply(approved(CommandId("cmd-readonly"), changeSet), WorkspaceConflictPolicy.ACCEPT_UNCONFLICTED)

        assertEquals(WorkspaceApplyStatus.REJECTED, receipt.status)
        assertTrue(receipt.committed.isEmpty())
        assertEquals(3, receipt.conflicts.size)
        assertTrue(receipt.conflicts.all { it.reason == WorkspaceConflictReason.PATH_POLICY })
        assertFalse(File(root, "sources/x.txt").exists())
        assertFalse(File(root, "datasets/y.txt").exists())
        assertFalse(File(root, "artifacts/z.txt").exists())
    }

    @Test
    fun recoveryNeverClaimsAPreflightRejectedChangeAsCommitted() = runBlocking {
        val (root, receipts) = directories()
        val command = CommandId("cmd-recover-rejected")
        val expected = WorkspaceDigest("a".repeat(64))
        val changeSet = changeSet("set-recover-rejected", listOf(
            WorkspaceChange(WorkspacePath("work/gone.txt"), WorkspaceChangeOperation.DELETE, expected, null),
        ))
        // The writer recorded an intent, then the process died before any write was attempted.
        // This DELETE had already failed preflight, so a missing file is not a success.
        writeIntent(receipts, command, changeSet, accepted = false)
        File(root, "work").mkdirs()
        assertFalse(File(root, "work/gone.txt").exists())

        val receipt = WorkspaceFileApplyStore(root, ref, receipts).apply(
            approved(command, changeSet, resumed = true),
            WorkspaceConflictPolicy.ACCEPT_UNCONFLICTED,
        )

        assertEquals(WorkspaceApplyStatus.REJECTED, receipt.status)
        assertTrue(receipt.committed.isEmpty())
        assertEquals(WorkspaceConflictReason.STALE_FINGERPRINT, receipt.conflicts.single().reason)
        assertFalse(File(receipts, "${sha256(command.value)}.intent.json").exists())
    }

    @Test
    fun recoveryRecognizesAChangeThatWasWrittenBeforeTheCrash() = runBlocking {
        val (root, receipts) = directories()
        val command = CommandId("cmd-recover-committed")
        val content = WorkspaceChangeContent.Inline("done".toByteArray())
        val changeSet = changeSet("set-recover-committed", listOf(
            WorkspaceChange(WorkspacePath("work/done.txt"), WorkspaceChangeOperation.CREATE, null, content),
        ))
        writeIntent(receipts, command, changeSet, accepted = true)
        File(root, "work").mkdirs()
        File(root, "work/done.txt").writeText("done")

        val receipt = WorkspaceFileApplyStore(root, ref, receipts).apply(
            approved(command, changeSet, resumed = true),
            WorkspaceConflictPolicy.ACCEPT_UNCONFLICTED,
        )

        assertEquals(WorkspaceApplyStatus.COMMITTED, receipt.status)
        assertEquals("work/done.txt", receipt.committed.single().path.value)
        assertEquals(content.sha256, receipt.committed.single().fingerprint)
    }

    private fun directories(): Pair<File, File> {
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "apply-root-$token").also { it.mkdirs() }
        val receipts = File(context.cacheDir, "apply-receipts-$token").also { it.mkdirs() }
        return root to receipts
    }

    private fun changeSet(id: String, changes: List<WorkspaceChange>) = WorkspaceChangeSet(
        WorkspaceChangeSetId(id), ref.projectId, chat, RunId("run-$id"), changes,
    )

    private fun create(path: String, text: String) = WorkspaceChange(
        WorkspacePath(path), WorkspaceChangeOperation.CREATE, null,
        WorkspaceChangeContent.Inline(text.toByteArray()),
    )

    private fun approved(
        command: CommandId,
        changeSet: WorkspaceChangeSet,
        resumed: Boolean = false,
    ): ApprovedWorkspaceChangeSet {
        val request = ApprovalRequestId("approval-${command.value}")
        return WorkspaceApprovalGrants.issue(
            changeSet = changeSet,
            commandId = command,
            approvalRequestId = request,
            approvedAtMillis = 1L,
            decision = WorkspaceApprovalDecision.Approved(request, changeSet.digest, 1L),
            resumed = resumed,
        )
    }

    private fun writeIntent(
        receipts: File,
        command: CommandId,
        changeSet: WorkspaceChangeSet,
        accepted: Boolean,
    ) {
        val text = buildJsonObject {
            put("version", 2)
            put("command", command.value)
            put("change_set", changeSet.id.value)
            put("digest", changeSet.digest.sha256)
            put("changes", JsonArray(changeSet.changes.map { change ->
                buildJsonObject {
                    put("path", change.path.value)
                    put("operation", change.operation.name)
                    put("expected", change.expectedFingerprint?.sha256 ?: "")
                    put("new", change.content?.sha256?.sha256 ?: "")
                    put("size", change.content?.sizeBytes ?: 0L)
                    put("accepted", accepted)
                    put("reason", if (accepted) "" else WorkspaceConflictReason.STALE_FINGERPRINT.name)
                    put("detail", if (accepted) "" else "not_committed")
                }
            }))
        }.toString()
        File(receipts, "${sha256(command.value)}.intent.json").writeText(text)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
