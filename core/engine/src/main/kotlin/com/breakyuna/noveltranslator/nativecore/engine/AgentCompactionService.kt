package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One automatic-compaction attempt. It performs at most one summary request and one CAS save;
 * it never retries a paid request or edits visible history. A failure before [AgentAutoContextPort.saveAutoContext]
 * leaves the previous context untouched.
 */
class AgentCompactionService(
    private val conversation: AgentConversationPort,
    private val summaries: AgentSummaryPort,
    private val contexts: AgentAutoContextPort,
    private val gate: AgentCompactionGatePort,
    private val policy: AgentCompactionPolicy = AgentCompactionPolicy(),
) {
    suspend fun compact(ref: LocalProjectRef, chat: ChatId): AgentCompactionResult {
        currentCoroutineContext().ensureActive()
        val blocked = gateResult(ref, chat)
        if (blocked != null) return blocked

        val history = try {
            conversation.modelHistory(ref, chat)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AgentModelFailure) {
            return AgentCompactionResult.Failed(AgentCompactionFailure.HISTORY_UNAVAILABLE, failure.kind)
        } catch (_: Exception) {
            return AgentCompactionResult.Failed(AgentCompactionFailure.HISTORY_UNAVAILABLE)
        }
        currentCoroutineContext().ensureActive()

        val plan = when (val decision = AgentCompactionPlanner.plan(history, policy)) {
            AgentCompactionDecision.NotNeeded -> return AgentCompactionResult.Skipped(AgentCompactionSkip.BELOW_THRESHOLD)
            is AgentCompactionDecision.Refused -> return AgentCompactionResult.Skipped(decision.reason.toSkip())
            is AgentCompactionDecision.Ready -> decision.plan
        }

        val prepared = try {
            contexts.prepareAutoContext(ref, chat, policy.retainUserTurns)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AgentCompactionResult.Failed(AgentCompactionFailure.PREPARE_FAILED)
        }
        if (prepared.retainUserTurns != policy.retainUserTurns ||
            prepared.context.ref != ref || prepared.context.chat != chat ||
            prepared.context.archivedMessages != plan.cut.archivedMessages ||
            prepared.context.retainedMessages != plan.cut.retainedMessages ||
            prepared.boundary != plan.cut.boundary
        ) return AgentCompactionResult.Skipped(AgentCompactionSkip.STALE_CONTEXT)

        val archived = history.subList(0, plan.cut.index).filterNot { it is AgentMessage.System }
        if (archived.size != plan.cut.archivedMessages || archived.lastOrNull()?.id != plan.cut.boundary)
            return AgentCompactionResult.Skipped(AgentCompactionSkip.STALE_CONTEXT)

        // A prepared read is cheap; re-check the host gate before the billable summary request.
        val blockedNow = gateResult(ref, chat)
        if (blockedNow != null) return blockedNow
        currentCoroutineContext().ensureActive()
        val summary = try {
            summaries.summarize(AgentSummaryRequest(ref, chat, prepared.context.previousSummary,
                archived, policy.maxSummaryChars))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AgentModelFailure) {
            return AgentCompactionResult.Failed(AgentCompactionFailure.SUMMARY_FAILED, failure.kind)
        } catch (_: Exception) {
            return AgentCompactionResult.Failed(AgentCompactionFailure.SUMMARY_FAILED)
        }
        currentCoroutineContext().ensureActive()
        if (summary.isBlank()) return AgentCompactionResult.Failed(AgentCompactionFailure.INVALID_SUMMARY)
        if (summary.length > policy.maxSummaryChars)
            return AgentCompactionResult.Failed(AgentCompactionFailure.SUMMARY_TOO_LARGE)
        try {
            TextProjectFingerprint.utf8(summary)
        } catch (_: Exception) {
            return AgentCompactionResult.Failed(AgentCompactionFailure.INVALID_SUMMARY)
        }

        currentCoroutineContext().ensureActive()
        try {
            contexts.saveAutoContext(prepared, summary)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AgentCompactionResult.Failed(AgentCompactionFailure.PERSIST_FAILED)
        }
        return AgentCompactionResult.Compacted(plan, summary.length)
    }

    private suspend fun gateResult(ref: LocalProjectRef, chat: ChatId): AgentCompactionResult? = try {
        gate.blockedReason(ref, chat)?.let { AgentCompactionResult.Skipped(it.toSkip()) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AgentCompactionResult.Failed(AgentCompactionFailure.GATE_UNAVAILABLE)
    }

    private fun AgentCompactionBlock.toSkip(): AgentCompactionSkip = when (this) {
        AgentCompactionBlock.PENDING_APPROVAL -> AgentCompactionSkip.PENDING_APPROVAL
        AgentCompactionBlock.ACTIVE_TOOL_LOOP -> AgentCompactionSkip.ACTIVE_TOOL_LOOP
        AgentCompactionBlock.ACTIVE_RUN -> AgentCompactionSkip.ACTIVE_RUN
        AgentCompactionBlock.USER_DISABLED -> AgentCompactionSkip.USER_DISABLED
    }

    private fun AgentCompactionRefusal.toSkip(): AgentCompactionSkip = when (this) {
        AgentCompactionRefusal.INSUFFICIENT_USER_TURNS -> AgentCompactionSkip.INSUFFICIENT_USER_TURNS
        AgentCompactionRefusal.NO_SAFE_CUT -> AgentCompactionSkip.NO_SAFE_CUT
        AgentCompactionRefusal.PENDING_TOOL_CALL -> AgentCompactionSkip.PENDING_TOOL_CALL
        AgentCompactionRefusal.INVALID_HISTORY -> AgentCompactionSkip.INVALID_HISTORY
    }
}
