package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.AssetImportApplier
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportApplyFailure
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportArtifactId
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportProposal
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportProposalState
import com.breakyuna.noveltranslator.nativecore.domain.AssetMutationReceipt
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactUnavailable

/**
 * App-layer bridge between a frozen asset-import proposal and the real project asset writer.
 *
 * The project revision and the artifact body are re-read at apply time. A stale revision, a
 * changed SHA-256/size, or an unreadable artifact fails closed before the writer is called.
 * Target-path conflicts stay in `ProjectFormatService`/`ProjectAssetWriteStore`, whose receipt
 * is returned unchanged.
 */
internal class NativeAssetImportApplier(
    private val projectRevision: suspend (LocalProjectRef) -> Long,
    private val readArtifactBytes: suspend (LocalProjectRef, RunId, AssetImportArtifactId,
        String, Long) -> ByteArray,
    private val importAsset: suspend (CommandId, LocalProjectRef, Long, RelativePath, ByteArray,
        Boolean) -> AssetMutationReceipt,
) : AssetImportApplier {
    override suspend fun apply(proposal: AssetImportProposal, command: CommandId): AssetMutationReceipt {
        require(command == proposal.command) { "Asset import command changed" }
        if (projectRevision(proposal.ref) != proposal.revision) {
            throw AssetImportApplyFailure(AssetImportProposalState.STALE)
        }
        val bytes = try {
            readArtifactBytes(proposal.ref, proposal.artifactRun, proposal.artifactId,
                proposal.artifactSha256, proposal.artifactSizeBytes)
        } catch (unavailable: WorkspaceArtifactUnavailable) {
            throw AssetImportApplyFailure(AssetImportProposalState.STALE, unavailable)
        }
        if (bytes.size.toLong() != proposal.artifactSizeBytes ||
            TextProjectFingerprint.bytes(bytes) != proposal.artifactSha256) {
            throw AssetImportApplyFailure(AssetImportProposalState.STALE)
        }
        return importAsset(command, proposal.ref, proposal.revision, proposal.target, bytes, proposal.enabled)
    }
}
