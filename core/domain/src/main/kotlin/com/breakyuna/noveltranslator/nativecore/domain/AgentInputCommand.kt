package com.breakyuna.noveltranslator.nativecore.domain

enum class InputCommandStatus { DRAFT, QUEUED, SENDING, ACCEPTED, PAUSED, FAILED, CANCELLED }

class InvalidInputTransition(val from: InputCommandStatus, val to: InputCommandStatus) :
    IllegalStateException("Invalid input transition: $from -> $to")

class InputCommandConflict(val id: CommandId) :
    IllegalStateException("Input command identity reused with different content")

private val ALLOWED_TRANSITIONS: Map<InputCommandStatus, Set<InputCommandStatus>> = mapOf(
    InputCommandStatus.DRAFT to setOf(InputCommandStatus.QUEUED, InputCommandStatus.SENDING, InputCommandStatus.CANCELLED),
    InputCommandStatus.QUEUED to setOf(InputCommandStatus.SENDING, InputCommandStatus.PAUSED, InputCommandStatus.CANCELLED),
    InputCommandStatus.PAUSED to setOf(InputCommandStatus.QUEUED, InputCommandStatus.CANCELLED),
    InputCommandStatus.SENDING to setOf(InputCommandStatus.ACCEPTED, InputCommandStatus.FAILED, InputCommandStatus.CANCELLED),
    InputCommandStatus.FAILED to setOf(InputCommandStatus.SENDING, InputCommandStatus.CANCELLED),
    InputCommandStatus.ACCEPTED to emptySet(),
    InputCommandStatus.CANCELLED to emptySet(),
)

private val ATTEMPT_STATUSES = setOf(
    InputCommandStatus.SENDING, InputCommandStatus.FAILED, InputCommandStatus.ACCEPTED,
)

/**
 * One user-visible input attempt. The command identity is stable across transport retries;
 * a user re-send is a new command, and a fresh model attempt gets a new [RequestAttemptId].
 * A draft is consumed only after it really enters history, never when the UI clears the box.
 */
class InputCommand(
    val id: CommandId,
    val chat: ChatIdentity,
    val text: String,
    val status: InputCommandStatus = InputCommandStatus.DRAFT,
    val attemptId: RequestAttemptId? = null,
) {
    init {
        require(text.isNotBlank()) { "Empty input command" }
        require(text.none { it.isISOControl() && it != '\n' && it != '\t' }) { "Control characters in input" }
        require((attemptId != null) == (status in ATTEMPT_STATUSES)) {
            "Attempt identity exists exactly for sending/failed/accepted commands"
        }
    }

    val isTerminal: Boolean get() = status == InputCommandStatus.ACCEPTED || status == InputCommandStatus.CANCELLED

    fun transition(
        next: InputCommandStatus,
        attempt: RequestAttemptId? = attemptId,
    ): InputCommand {
        if (next !in ALLOWED_TRANSITIONS.getValue(status)) throw InvalidInputTransition(status, next)
        return InputCommand(id, chat, text, next, attempt)
    }

    /** Editing is only possible while the input is still a draft or a paused/queued item. */
    fun edit(text: String): InputCommand {
        if (status !in setOf(InputCommandStatus.DRAFT, InputCommandStatus.QUEUED, InputCommandStatus.PAUSED)) {
            throw InvalidInputTransition(status, status)
        }
        return InputCommand(id, chat, text, status, attemptId)
    }

    /** Retry after a failed attempt keeps the command identity but takes a new attempt identity. */
    fun retry(attempt: RequestAttemptId): InputCommand {
        if (status != InputCommandStatus.FAILED) throw InvalidInputTransition(status, InputCommandStatus.SENDING)
        return InputCommand(id, chat, text, InputCommandStatus.SENDING, attempt)
    }
}

/**
 * Process-local command ledger used to prove deduplication rules before persistence exists.
 * A production store must enforce the same identity rules transactionally; this class is not
 * a recovery source.
 */
class InputCommandRegistry {
    private val commands = LinkedHashMap<CommandId, InputCommand>()

    /** Transport retry may repeat the same command; different content under one ID is a conflict. */
    fun open(command: InputCommand): InputCommand {
        val existing = commands[command.id]
        if (existing != null) {
            if (existing.chat != command.chat || existing.text != command.text) {
                throw InputCommandConflict(command.id)
            }
            return existing
        }
        commands[command.id] = command
        return command
    }

    fun replace(command: InputCommand): InputCommand {
        require(commands.containsKey(command.id)) { "Unknown input command" }
        commands[command.id] = command
        return command
    }

    fun get(id: CommandId): InputCommand? = commands[id]

    fun active(): List<InputCommand> = commands.values.filter { !it.isTerminal }
}
