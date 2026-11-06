package com.breakyuna.noveltranslator.nativecore.domain

/**
 * Explicit execution phase for steer admission and injection. Only an active phase may accept a
 * steer, and only [TOOL_LOOP_BOUNDARY] may inject one into the running tool loop.
 */
enum class AgentRunStage {
    MODEL_REQUEST,
    TOOL_EXECUTION,
    TOOL_LOOP_BOUNDARY,
    STOPPING,
    STOPPED,
    FINISHED,
    FAILED;

    val active: Boolean get() = this == MODEL_REQUEST || this == TOOL_EXECUTION || this == TOOL_LOOP_BOUNDARY
    val terminal: Boolean get() = this == STOPPED || this == FINISHED || this == FAILED
}

enum class AgentSteerStatus { ACCEPTED, INJECTED, REJECTED, CANCELLED }

enum class AgentSteerFailure {
    WRONG_CHAT,
    WRONG_RUN,
    RUN_STOPPING,
    RUN_STOPPED,
    RUN_FINISHED,
    RUN_FAILED,
    NOT_AT_TOOL_BOUNDARY,
    DUPLICATE_COMMAND_CONFLICT,
    STEER_BUDGET_EXCEEDED,
    INVALID_STAGE_TRANSITION,
}

/**
 * An in-execution steering instruction. It carries its own Run/Chat identity and never enters the
 * next-turn input queue as an ordinary user message. [text] is bounded by the same 8000-character
 * limit as one send, but the value is only ever consumed by a tool-loop injection.
 */
class AgentSteerCommand(
    val id: CommandId,
    val run: RunIdentity,
    val chat: ChatIdentity,
    val text: String,
) {
    init {
        require(run.projectId == chat.projectId && run.chatId == chat.chatId) {
            "Steer Run must belong to the same Project/Chat"
        }
        require(text.isNotBlank()) { "Empty steer input" }
        require(text.length <= MAX_TEXT_CHARS) { "Steer input exceeds the text budget" }
        require(text.none { it.isISOControl() && it != '\n' && it != '\t' }) {
            "Control characters in steer input"
        }
    }

    override fun toString(): String = "AgentSteerCommand(id=${id.value}, chars=${text.length})"

    companion object {
        const val MAX_TEXT_CHARS = 8_000
    }
}

/**
 * One steer attempt. ACCEPTED is not yet visible to the model; INJECTED means it crossed exactly
 * one explicit [AgentRunStage.TOOL_LOOP_BOUNDARY]. CANCELLED keeps the command text for explicit
 * recovery, while REJECTED is returned to the caller and is never silently replayed.
 */
class AgentSteerAttempt internal constructor(
    val command: AgentSteerCommand,
    val run: RunIdentity,
    val chat: ChatIdentity,
    val submittedDuring: AgentRunStage,
    val status: AgentSteerStatus,
    val reason: AgentSteerFailure? = null,
    val injectedDuring: AgentRunStage? = null,
) {
    init {
        require(run == command.run && chat == command.chat) { "Steer attempt identity mismatch" }
        when (status) {
            AgentSteerStatus.ACCEPTED -> require(
                reason == null && injectedDuring == null && submittedDuring.active,
            ) { "Accepted steer must be active and not yet injected" }

            AgentSteerStatus.INJECTED -> require(
                reason == null && injectedDuring == AgentRunStage.TOOL_LOOP_BOUNDARY && submittedDuring.active,
            ) { "Injected steer must record the tool-loop boundary" }

            AgentSteerStatus.REJECTED -> require(reason != null && injectedDuring == null) {
                "Rejected steer requires a reason and was never injected"
            }

            AgentSteerStatus.CANCELLED -> require(
                reason != null && injectedDuring == null && submittedDuring.active,
            ) { "Cancelled steer requires a reason and was never injected" }
        }
    }

    val id: CommandId get() = command.id
    val text: String get() = command.text

    internal fun injected(): AgentSteerAttempt {
        require(status == AgentSteerStatus.ACCEPTED) { "Only an accepted steer can be injected" }
        return AgentSteerAttempt(
            command,
            run,
            chat,
            submittedDuring,
            AgentSteerStatus.INJECTED,
            injectedDuring = AgentRunStage.TOOL_LOOP_BOUNDARY,
        )
    }

    internal fun cancelled(reason: AgentSteerFailure): AgentSteerAttempt {
        require(status == AgentSteerStatus.ACCEPTED) { "Only an accepted steer can be cancelled" }
        return AgentSteerAttempt(command, run, chat, submittedDuring, AgentSteerStatus.CANCELLED, reason)
    }

    override fun toString(): String =
        "AgentSteerAttempt(id=${id.value}, status=$status, stage=$submittedDuring, chars=${text.length})"

    companion object {
        /**
         * Explicit recovery factory for persisted facts. Phase/status/reason combinations are
         * validated by the constructor and by [AgentSteerState]; no injection is performed here.
         */
        fun restore(
            command: AgentSteerCommand,
            run: RunIdentity,
            chat: ChatIdentity,
            submittedDuring: AgentRunStage,
            status: AgentSteerStatus,
            reason: AgentSteerFailure? = null,
            injectedDuring: AgentRunStage? = null,
        ): AgentSteerAttempt = AgentSteerAttempt(
            command,
            run,
            chat,
            submittedDuring,
            status,
            reason,
            injectedDuring,
        )
    }
}

/** A boundary effect for the current tool loop, not an [AgentMessage.User] and never history. */
class AgentSteerInjection internal constructor(
    val command: AgentSteerCommand,
    val run: RunIdentity,
    val chat: ChatIdentity,
    val boundary: AgentRunStage = AgentRunStage.TOOL_LOOP_BOUNDARY,
) {
    init {
        require(boundary == AgentRunStage.TOOL_LOOP_BOUNDARY) { "Steer only injects at the tool-loop boundary" }
        require(run == command.run && chat == command.chat) { "Steer injection identity mismatch" }
    }

    val text: String get() = command.text

    override fun toString(): String = "AgentSteerInjection(id=${command.id.value}, chars=${text.length})"
}

sealed interface AgentSteerSubmitResult {
    data class Accepted(val state: AgentSteerState, val attempt: AgentSteerAttempt) : AgentSteerSubmitResult

    /** Retrying the same command identity with identical content changes nothing. */
    data class Duplicate(val state: AgentSteerState, val attempt: AgentSteerAttempt) : AgentSteerSubmitResult

    data class Rejected(
        val state: AgentSteerState,
        val attempt: AgentSteerAttempt,
        val reason: AgentSteerFailure,
    ) : AgentSteerSubmitResult
}

sealed interface AgentSteerStageResult {
    data class Applied(val state: AgentSteerState) : AgentSteerStageResult
    data class Rejected(val state: AgentSteerState, val reason: AgentSteerFailure) : AgentSteerStageResult
}

sealed interface AgentSteerInjectionResult {
    class Injected(
        val state: AgentSteerState,
        injections: List<AgentSteerInjection>,
    ) : AgentSteerInjectionResult {
        val injections: List<AgentSteerInjection> = frozenList(injections)
    }

    data class NothingToInject(val state: AgentSteerState) : AgentSteerInjectionResult
    data class Rejected(val state: AgentSteerState, val reason: AgentSteerFailure) : AgentSteerInjectionResult
}

/**
 * Process-local steer ledger for exactly one Run/Chat. Submit is allowed during active phases and
 * binds the submission phase; injection only happens at an explicit tool-loop boundary. Stop,
 * finish and failure cancel accepted-but-not-injected inputs and retain their text, but they never
 * turn them into next-turn messages. A stopped/finished/failed Run rejects all later submissions.
 */
class AgentSteerState private constructor(
    val run: RunIdentity,
    val chat: ChatIdentity,
    val stage: AgentRunStage,
    attempts: List<AgentSteerAttempt>,
) {
    val attempts: List<AgentSteerAttempt> = frozenList(attempts)
    val accepted: List<AgentSteerAttempt> =
        frozenList(this.attempts.filter { it.status == AgentSteerStatus.ACCEPTED })
    val injected: List<AgentSteerAttempt> =
        frozenList(this.attempts.filter { it.status == AgentSteerStatus.INJECTED })
    val cancelled: List<AgentSteerAttempt> =
        frozenList(this.attempts.filter { it.status == AgentSteerStatus.CANCELLED })

    init {
        require(run.projectId == chat.projectId && run.chatId == chat.chatId) {
            "Steer state Run must belong to the same Project/Chat"
        }
        require(this.attempts.map { it.id }.toSet().size == this.attempts.size) {
            "Duplicate steer command identity"
        }
        require(this.attempts.all { it.run == run && it.chat == chat }) { "Steer attempt identity mismatch" }
        require(this.attempts.none { it.status == AgentSteerStatus.REJECTED }) {
            "Rejected steer attempts are returned to the caller, not retained"
        }
        require(stage.active || this.accepted.isEmpty()) { "A terminal Run cannot retain accepted steers" }
    }

    val hasAccepted: Boolean get() = accepted.isNotEmpty()

    /**
     * The host mirrors the real execution phase. Moving between active phases is explicit; leaving
     * the Run uses [stop], [finish] or [fail], which own accepted-input cancellation.
     */
    fun moveTo(next: AgentRunStage): AgentSteerStageResult {
        if (!next.active || !stage.active) {
            return AgentSteerStageResult.Rejected(this, AgentSteerFailure.INVALID_STAGE_TRANSITION)
        }
        if (next == stage) return AgentSteerStageResult.Applied(this)
        return AgentSteerStageResult.Applied(copy(stage = next))
    }

    fun submit(command: AgentSteerCommand): AgentSteerSubmitResult {
        if (command.chat != chat) {
            return AgentSteerSubmitResult.Rejected(
                this,
                rejectedAttempt(command, AgentSteerFailure.WRONG_CHAT),
                AgentSteerFailure.WRONG_CHAT,
            )
        }
        if (command.run != run) {
            return AgentSteerSubmitResult.Rejected(
                this,
                rejectedAttempt(command, AgentSteerFailure.WRONG_RUN),
                AgentSteerFailure.WRONG_RUN,
            )
        }
        val existing = attempts.firstOrNull { it.id == command.id }
        if (existing != null) {
            return if (existing.command.text == command.text) {
                AgentSteerSubmitResult.Duplicate(this, existing)
            } else {
                AgentSteerSubmitResult.Rejected(
                    this,
                    rejectedAttempt(command, AgentSteerFailure.DUPLICATE_COMMAND_CONFLICT),
                    AgentSteerFailure.DUPLICATE_COMMAND_CONFLICT,
                )
            }
        }
        stage.submissionFailure()?.let { reason ->
            return AgentSteerSubmitResult.Rejected(this, rejectedAttempt(command, reason), reason)
        }
        if (attempts.size >= MAX_ATTEMPTS) {
            return AgentSteerSubmitResult.Rejected(
                this,
                rejectedAttempt(command, AgentSteerFailure.STEER_BUDGET_EXCEEDED),
                AgentSteerFailure.STEER_BUDGET_EXCEEDED,
            )
        }
        val attempt = AgentSteerAttempt(command, run, chat, stage, AgentSteerStatus.ACCEPTED)
        return AgentSteerSubmitResult.Accepted(copy(attempts = attempts + attempt), attempt)
    }

    /** Injects every accepted steer in submission order at exactly one tool-loop boundary. */
    fun injectAtToolBoundary(): AgentSteerInjectionResult {
        stage.submissionFailure()?.let { reason ->
            return AgentSteerInjectionResult.Rejected(this, reason)
        }
        if (stage != AgentRunStage.TOOL_LOOP_BOUNDARY) {
            return AgentSteerInjectionResult.Rejected(this, AgentSteerFailure.NOT_AT_TOOL_BOUNDARY)
        }
        if (accepted.isEmpty()) return AgentSteerInjectionResult.NothingToInject(this)
        val injectedIds = accepted.map { it.id }.toSet()
        val nextAttempts = attempts.map { if (it.id in injectedIds) it.injected() else it }
        val injections = nextAttempts
            .filter { it.status == AgentSteerStatus.INJECTED && it.id in injectedIds }
            .map { AgentSteerInjection(it.command, run, chat) }
        return AgentSteerInjectionResult.Injected(copy(attempts = nextAttempts), injections)
    }

    /** Stop invalidates accepted steers before any further model/tool request; injected ones stay facts. */
    fun stop(): AgentSteerStageResult = when (stage) {
        AgentRunStage.STOPPING, AgentRunStage.STOPPED -> AgentSteerStageResult.Applied(this)
        AgentRunStage.FINISHED, AgentRunStage.FAILED ->
            AgentSteerStageResult.Rejected(this, AgentSteerFailure.INVALID_STAGE_TRANSITION)

        else -> AgentSteerStageResult.Applied(
            copy(stage = AgentRunStage.STOPPING, attempts = cancelAccepted(AgentSteerFailure.RUN_STOPPING)),
        )
    }

    fun markStopped(): AgentSteerStageResult = when (stage) {
        AgentRunStage.STOPPED -> AgentSteerStageResult.Applied(this)
        AgentRunStage.STOPPING -> AgentSteerStageResult.Applied(copy(stage = AgentRunStage.STOPPED))
        else -> AgentSteerStageResult.Rejected(this, AgentSteerFailure.INVALID_STAGE_TRANSITION)
    }

    fun finish(): AgentSteerStageResult = when (stage) {
        AgentRunStage.FINISHED -> AgentSteerStageResult.Applied(this)
        AgentRunStage.STOPPING, AgentRunStage.STOPPED, AgentRunStage.FAILED ->
            AgentSteerStageResult.Rejected(this, AgentSteerFailure.INVALID_STAGE_TRANSITION)

        else -> AgentSteerStageResult.Applied(
            copy(stage = AgentRunStage.FINISHED, attempts = cancelAccepted(AgentSteerFailure.RUN_FINISHED)),
        )
    }

    fun fail(): AgentSteerStageResult = when (stage) {
        AgentRunStage.FAILED -> AgentSteerStageResult.Applied(this)
        AgentRunStage.STOPPING, AgentRunStage.STOPPED, AgentRunStage.FINISHED ->
            AgentSteerStageResult.Rejected(this, AgentSteerFailure.INVALID_STAGE_TRANSITION)

        else -> AgentSteerStageResult.Applied(
            copy(stage = AgentRunStage.FAILED, attempts = cancelAccepted(AgentSteerFailure.RUN_FAILED)),
        )
    }

    private fun cancelAccepted(reason: AgentSteerFailure): List<AgentSteerAttempt> =
        attempts.map { if (it.status == AgentSteerStatus.ACCEPTED) it.cancelled(reason) else it }

    private fun rejectedAttempt(command: AgentSteerCommand, reason: AgentSteerFailure): AgentSteerAttempt =
        AgentSteerAttempt(command, command.run, command.chat, stage, AgentSteerStatus.REJECTED, reason)

    private fun copy(
        stage: AgentRunStage = this.stage,
        attempts: List<AgentSteerAttempt> = this.attempts,
    ): AgentSteerState = AgentSteerState(run, chat, stage, attempts)

    override fun toString(): String =
        "AgentSteerState(run=${run.runId.value}, stage=$stage, accepted=${accepted.size}, " +
            "injected=${injected.size}, cancelled=${cancelled.size})"

    companion object {
        /** Hard per-Run ledger budget; old attempts are never silently evicted. */
        const val MAX_ATTEMPTS = 100

        fun start(
            run: RunIdentity,
            chat: ChatIdentity,
            stage: AgentRunStage = AgentRunStage.MODEL_REQUEST,
        ): AgentSteerState {
            require(run.projectId == chat.projectId && run.chatId == chat.chatId) {
                "Steer Run must belong to the same Project/Chat"
            }
            return AgentSteerState(run, chat, stage, emptyList())
        }

        /**
         * Explicit recovery factory for a persisted ledger. Existing invariants still apply and
         * the restored state is inert: it performs no submission, cancellation or injection.
         */
        fun restore(
            run: RunIdentity,
            chat: ChatIdentity,
            stage: AgentRunStage,
            attempts: List<AgentSteerAttempt>,
        ): AgentSteerState = AgentSteerState(run, chat, stage, attempts)
    }
}

private fun AgentRunStage.submissionFailure(): AgentSteerFailure? = when (this) {
    AgentRunStage.STOPPING -> AgentSteerFailure.RUN_STOPPING
    AgentRunStage.STOPPED -> AgentSteerFailure.RUN_STOPPED
    AgentRunStage.FINISHED -> AgentSteerFailure.RUN_FINISHED
    AgentRunStage.FAILED -> AgentSteerFailure.RUN_FAILED
    else -> null
}

/** Production tool-loop hook; it does not authorize model requests or project writes. */
interface AgentSteerExecutionPort {
    suspend fun phase(lease: RunLease, stage: AgentRunStage)
    suspend fun boundary(lease: RunLease): List<AgentSteerInjection>
}
