package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.formats.ProjectFormatService
import com.breakyuna.noveltranslator.nativecore.storage.NativeTextProjectStore
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactUnavailable
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
class NativeAssetImportApplierTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var formats: ProjectFormatService
    private lateinit var ref: LocalProjectRef

    private val content = "report\n".toByteArray()

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        runtime = RuntimeCoordinator()
        projects = NativeTextProjectStore(context, runtime)
        formats = ProjectFormatService(projects, projects)
        ref = projects.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"),
            "source".toByteArray()).ref
    }

    @After fun cleanup() = runBlocking { projects.close(); runtime.close() }

    private fun proposal(revision: Long = 0, target: String = "imports/report.txt",
                         bytes: ByteArray = content) = AssetImportProposal(
        ApprovalRequestId("proposal"), ref, ChatId("chat"), RunId("run"), CommandId("import-command"),
        revision, RunId("run"), AssetImportArtifactId("artifact"),
        RelativePath("reports/report.txt"), TextProjectFingerprint.bytes(bytes), bytes.size.toLong(),
        RelativePath(target), enabled = true, createdAt = 1_000,
    )

    private fun applier(frozen: () -> ByteArray) = NativeAssetImportApplier(
        projectRevision = { project -> projects.open(project).revision },
        readArtifactBytes = { _, _, _, sha256, size ->
            val bytes = frozen()
            if (bytes.size.toLong() != size || TextProjectFingerprint.bytes(bytes) != sha256) {
                throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.FINGERPRINT_MISMATCH)
            }
            bytes
        },
        importAsset = { command, project, revision, path, bytes, enabled ->
            formats.importAsset(command, project, revision, path, bytes, enabled)
        },
    )

    @Test fun committedImportReturnsTheWriterReceiptAndAddsOneAsset() = runBlocking {
        val proposal = proposal(revision = projects.open(ref).revision)
        val receipt = applier { content }.apply(proposal, proposal.command)
        assertEquals(AssetMutationStatus.COMMITTED, receipt.status)
        assertEquals(AssetMutationKind.IMPORT, receipt.kind)
        assertNotNull(receipt.assetId)
        assertEquals(1L, projects.open(ref).revision)
        assertTrue(projects.open(ref).assets.any { it.path == RelativePath("imports/report.txt") })
    }

    @Test fun staleProjectRevisionFailsBeforeReadingOrWriting() = runBlocking {
        val proposal = proposal(revision = 0)
        formats.importAsset(CommandId("other-import"), ref, 0, RelativePath("imports/other.txt"),
            "other".toByteArray(), true)
        var reads = 0
        try {
            applier { reads += 1; content }.apply(proposal, proposal.command)
            fail("Accepted a stale project revision")
        } catch (failure: AssetImportApplyFailure) {
            assertEquals(AssetImportProposalState.STALE, failure.state)
        }
        assertEquals(0, reads)
        assertEquals(1, projects.open(ref).assets.size)
    }

    @Test fun changedArtifactShaFailsBeforeTheAssetWriter() = runBlocking {
        val proposal = proposal(revision = projects.open(ref).revision)
        try {
            applier { "changed artifact".toByteArray() }.apply(proposal, proposal.command)
            fail("Imported changed artifact bytes")
        } catch (failure: AssetImportApplyFailure) {
            assertEquals(AssetImportProposalState.STALE, failure.state)
        }
        assertEquals(0L, projects.open(ref).revision)
        assertTrue(projects.open(ref).assets.isEmpty())
    }

    @Test fun targetPathConflictIsDecidedByTheAssetWriter() = runBlocking {
        formats.importAsset(CommandId("seed"), ref, 0, RelativePath("imports/report.txt"), content, true)
        val proposal = proposal(revision = projects.open(ref).revision)
        val receipt = applier { content }.apply(proposal, proposal.command)
        assertEquals(AssetMutationStatus.CONFLICT, receipt.status)
        assertEquals(1L, projects.open(ref).revision)
        assertEquals(1, projects.open(ref).assets.size)
    }
}
