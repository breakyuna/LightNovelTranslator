package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetImportProposalTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val chat = ChatId("chat")
    private val run = RunId("run")
    private val command = CommandId("command")
    private val artifactSha = "a".repeat(64)

    private fun proposal(
        id: String = "proposal",
        revision: Long = 4,
        artifactRun: RunId = run,
        artifactId: String = "artifact",
        artifactPath: String = "reports/report.md",
        sha256: String = artifactSha,
        size: Long = 21,
        target: String = "imports/report.md",
        enabled: Boolean = true,
        createdAt: Long = 1_000,
    ) = AssetImportProposal(
        ApprovalRequestId(id), ref, chat, run, command, revision, artifactRun,
        AssetImportArtifactId(artifactId), RelativePath(artifactPath), sha256, size,
        RelativePath(target), enabled, createdAt,
    )

    @Test fun digestBindsArtifactTargetRevisionCommandAndEnabled() {
        val base = proposal()
        assertEquals(base.digest, proposal(createdAt = 2_000).digest)
        assertNotEquals(base.digest, proposal(revision = 5).digest)
        assertNotEquals(base.digest, proposal(sha256 = "b".repeat(64)).digest)
        assertNotEquals(base.digest, proposal(target = "imports/other.md").digest)
        assertEquals(run, base.runIdentity.runId)
        assertEquals(command, base.runIdentity.commandId)
        assertEquals(chat, base.runIdentity.chatId)
    }

    @Test fun bindingCoversDigestCommandAndSelectedObject() {
        val base = proposal()
        val binding = base.binding()
        assertEquals(base.digest, binding.digest)
        assertEquals(command, binding.command)
        assertTrue(binding.selected)
        assertNotEquals(binding.fingerprint, AssetImportBinding(base.id,
            ObjectFingerprint("c".repeat(64)), command).fingerprint)
        assertThrows(IllegalArgumentException::class.java) {
            AssetImportBinding(base.id, base.digest, command, selected = false)
        }
    }

    @Test fun onlyCommittedReceiptMapsToApplied() {
        assertEquals(AssetImportProposalState.APPLIED,
            AssetMutationStatus.COMMITTED.toAssetImportProposalState())
        assertEquals(AssetImportProposalState.STALE,
            AssetMutationStatus.STALE_REVISION.toAssetImportProposalState())
        assertEquals(AssetImportProposalState.CONFLICT,
            AssetMutationStatus.CONFLICT.toAssetImportProposalState())
        assertEquals(AssetImportProposalState.CONFLICT,
            AssetMutationStatus.UNKNOWN_ASSET.toAssetImportProposalState())
        assertTrue(AssetImportProposalState.APPLIED.terminal)
        assertTrue(!AssetImportProposalState.PENDING.terminal)
        val receipt = AssetImportReceipt(command, ref, AssetMutationStatus.COMMITTED, 5, 9, 2_000,
            artifactSha, 21)
        assertEquals(AssetImportProposalState.APPLIED, receipt.state)
    }

    @Test fun proposalRejectsForeignArtifactRunAndInvalidMetadata() {
        assertThrows(IllegalArgumentException::class.java) { proposal(artifactRun = RunId("other-run")) }
    }

    @Test fun summaryCarriesTheFrozenIdentityWithoutArtifactBody() {
        val base = proposal()
        val summary = base.summary(AssetImportProposalState.PENDING)
        assertEquals(base.id, summary.id)
        assertEquals(base.ref, summary.ref)
        assertEquals(base.digest, summary.digest)
        assertEquals(base.artifactSha256, summary.artifactSha256)
        assertEquals(base.artifactSizeBytes, summary.artifactSizeBytes)
        assertEquals(base.target, summary.target)
        assertEquals(AssetImportProposalState.PENDING, summary.state)
        assertTrue(base.toString().contains("redacted"))
    }
}
