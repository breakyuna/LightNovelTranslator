package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Terminal outcome of one frozen workspace-artifact import proposal. */
enum class AssetImportProposalState {
    PENDING,
    APPLIED,
    REJECTED,
    STALE,
    CONFLICT,
    CANCELLED;

    val terminal: Boolean get() = this != PENDING
}

/** Opaque workspace artifact identity; it is never interpreted as a filesystem path. */
@JvmInline
value class AssetImportArtifactId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 128) { "Invalid artifact identity" }
        require(value.none { it.isISOControl() }) { "Invalid artifact identity" }
    }
}

/** Metadata of one persisted workspace artifact, frozen when an import is prepared. */
class AssetImportArtifact(
    val run: RunId,
    val id: AssetImportArtifactId,
    val path: RelativePath,
    val sha256: String,
    val sizeBytes: Long,
) {
    init {
        require(path.value.length <= 512) { "Artifact path is too long" }
        requireSha256Hex(sha256)
        require(sizeBytes in 0..AssetImportProposal.MAX_ARTIFACT_BYTES) { "Invalid artifact size" }
    }

    override fun toString(): String =
        "AssetImportArtifact(id=${id.value}, size=$sizeBytes)"
}

/**
 * One immutable proposal that imports full artifact bytes as a new project asset.
 *
 * The approval is bound to the project revision, run identity, command, artifact metadata,
 * target path and enabled flag. The single object is always selected; there is no partial
 * apply. Actual path conflicts and revision checks stay in the project asset writer.
 */
class AssetImportProposal(
    val id: ApprovalRequestId,
    val ref: LocalProjectRef,
    val chat: ChatId,
    val run: RunId,
    val command: CommandId,
    val revision: Long,
    val artifactRun: RunId,
    val artifactId: AssetImportArtifactId,
    val artifactPath: RelativePath,
    val artifactSha256: String,
    val artifactSizeBytes: Long,
    val target: RelativePath,
    val enabled: Boolean,
    val createdAt: Long,
) {
    init {
        require(revision >= 0) { "Invalid project revision" }
        require(artifactRun == run) { "Artifact belongs to another run" }
        require(artifactPath.value.length <= 512 && target.value.length <= 512) { "Path is too long" }
        requireSha256Hex(artifactSha256)
        require(artifactSizeBytes in 0..MAX_ARTIFACT_BYTES) { "Invalid artifact size" }
        require(createdAt >= 0) { "Invalid creation time" }
    }

    val runIdentity: RunIdentity get() = RunIdentity(ref.projectId, chat, run, command)

    /** Whole-object approval digest; no artifact body is stored in the proposal or digest. */
    val digest: ObjectFingerprint = ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(
        buildJsonObject {
            put("version", 1)
            put("kind", "ASSET_IMPORT")
            put("id", id.value)
            put("project", ref.projectId.value)
            put("instance", ref.instanceId.value)
            put("generation", ref.generation.value)
            put("chat", chat.value)
            put("run", run.value)
            put("command", command.value)
            put("revision", revision)
            put("artifact_run", artifactRun.value)
            put("artifact_id", artifactId.value)
            put("artifact_path", artifactPath.value)
            put("artifact_sha256", artifactSha256)
            put("artifact_size", artifactSizeBytes)
            put("target", target.value)
            put("enabled", enabled)
            put("objects", 1)
            put("selected", true)
        }.toString(),
    )))

    /** Fixed approval binding: one object, one command, one digest. */
    fun binding(): AssetImportBinding = AssetImportBinding(id, digest, command, selected = true)

    fun summary(state: AssetImportProposalState): AssetImportProposalSummary = AssetImportProposalSummary(
        id, ref, chat, run, command, revision, digest, artifactRun, artifactId, artifactPath,
        artifactSha256, artifactSizeBytes, target, enabled, state, createdAt,
    )

    override fun toString(): String = "AssetImportProposal(id=${id.value}, body=redacted)"

    companion object {
        const val MAX_ARTIFACT_BYTES: Long = 64L * 1024 * 1024
    }
}

/** The human approved this exact digest/command/object selection, once. */
class AssetImportBinding(
    val proposal: ApprovalRequestId,
    val digest: ObjectFingerprint,
    val command: CommandId,
    val selected: Boolean = true,
) {
    init { require(selected) { "Asset import is a single selected object" } }

    val fingerprint: ObjectFingerprint = ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(
        buildJsonObject {
            put("format", "asset-import-binding-v1")
            put("proposal", proposal.value)
            put("digest", digest.sha256)
            put("command", command.value)
            put("selected", selected)
            put("objects", 1)
        }.toString(),
    )))

    override fun toString(): String =
        "AssetImportBinding(proposal=${proposal.value}, fingerprint=${fingerprint.sha256})"
}

/** Conversation/list projection without the artifact body. */
class AssetImportProposalSummary(
    val id: ApprovalRequestId,
    val ref: LocalProjectRef,
    val chat: ChatId,
    val run: RunId,
    val command: CommandId,
    val revision: Long,
    val digest: ObjectFingerprint,
    val artifactRun: RunId,
    val artifactId: AssetImportArtifactId,
    val artifactPath: RelativePath,
    val artifactSha256: String,
    val artifactSizeBytes: Long,
    val target: RelativePath,
    val enabled: Boolean,
    val state: AssetImportProposalState,
    val createdAt: Long,
) {
    val runIdentity: RunIdentity get() = RunIdentity(ref.projectId, chat, run, command)

    override fun toString(): String =
        "AssetImportProposalSummary(id=${id.value}, state=$state, target=${target.value})"
}

/** Durable receipt of the asset writer, persisted next to the proposal state. */
data class AssetImportReceipt(
    val command: CommandId,
    val ref: LocalProjectRef,
    val status: AssetMutationStatus,
    val revision: Long,
    val assetId: Long?,
    val committedAtMillis: Long,
    val artifactSha256: String,
    val artifactSizeBytes: Long,
) {
    init {
        require(status in setOf(AssetMutationStatus.COMMITTED, AssetMutationStatus.STALE_REVISION,
            AssetMutationStatus.CONFLICT, AssetMutationStatus.UNKNOWN_ASSET)) { "Invalid import receipt" }
        require(revision >= 0 && committedAtMillis >= 0) { "Invalid import receipt" }
        require(assetId == null || assetId > 0) { "Invalid imported asset identity" }
        require(status != AssetMutationStatus.COMMITTED || assetId != null) { "Committed import lacks an asset" }
        requireSha256Hex(artifactSha256)
        require(artifactSizeBytes in 0..AssetImportProposal.MAX_ARTIFACT_BYTES) { "Invalid artifact size" }
    }

    val state: AssetImportProposalState get() = status.toAssetImportProposalState()

    override fun toString(): String =
        "AssetImportReceipt(status=$status, revision=$revision, asset=$assetId)"
}

/** Only committed project receipts map to APPLIED; anything else is explicit failure. */
fun AssetMutationStatus.toAssetImportProposalState(): AssetImportProposalState = when (this) {
    AssetMutationStatus.COMMITTED -> AssetImportProposalState.APPLIED
    AssetMutationStatus.STALE_REVISION -> AssetImportProposalState.STALE
    AssetMutationStatus.CONFLICT, AssetMutationStatus.UNKNOWN_ASSET -> AssetImportProposalState.CONFLICT
}

/** Typed fail-closed apply failure used when no durable asset receipt can be produced. */
class AssetImportApplyFailure(
    val state: AssetImportProposalState,
    cause: Throwable? = null,
) : IllegalStateException("Asset import was not committed", cause) {
    init {
        require(state == AssetImportProposalState.STALE || state == AssetImportProposalState.CONFLICT) {
            "Unsupported asset import failure state"
        }
    }
}

/** App-layer port; storage only persists the resulting state and receipt. */
fun interface AssetImportApplier {
    suspend fun apply(proposal: AssetImportProposal, command: CommandId): AssetMutationReceipt
}

private fun requireSha256Hex(value: String) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid SHA-256" }
}
