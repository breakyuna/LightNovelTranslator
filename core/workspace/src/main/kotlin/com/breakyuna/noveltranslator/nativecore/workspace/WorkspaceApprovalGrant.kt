package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId

/**
 * Trusted application boundary that turns a persisted user decision into the opaque grant consumed
 * by [WorkspaceApplyPort]. Core deliberately keeps [ApprovedWorkspaceChangeSet]'s constructor
 * internal; this factory accepts only an approved decision whose request/digest still match the
 * frozen batch. Scripts and model tools cannot call it.
 */
object WorkspaceApprovalGrants {
    fun issue(
        changeSet: WorkspaceChangeSet,
        commandId: CommandId,
        approvalRequestId: ApprovalRequestId,
        approvedAtMillis: Long,
        decision: WorkspaceApprovalDecision.Approved,
        baseRevision: Long = 0,
        resumed: Boolean = false,
    ): ApprovedWorkspaceChangeSet {
        require(decision.requestId == approvalRequestId) { "Approval request changed" }
        require(decision.digest == changeSet.digest) { "Approval digest does not bind this change set" }
        require(decision.approvedAtMillis == approvedAtMillis && approvedAtMillis >= 0) {
            "Approval time changed"
        }
        return ApprovedWorkspaceChangeSet(commandId, changeSet, approvalRequestId, approvedAtMillis,
            baseRevision, resumed)
    }
}
