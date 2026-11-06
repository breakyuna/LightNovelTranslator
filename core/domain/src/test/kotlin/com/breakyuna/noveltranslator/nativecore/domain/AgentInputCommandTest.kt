package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentInputCommandTest {
    private val chat = ChatIdentity(ProjectId("project"), ChatId("chat"))

    @Test
    fun draftsCanBeQueuedEditedAndPausedButNotAcceptedDirectly() {
        val draft = command()
        assertEquals(InputCommandStatus.QUEUED, draft.transition(InputCommandStatus.QUEUED).status)
        assertEquals("edited", draft.edit("edited").text)
        expectTransition(InputCommandStatus.DRAFT, InputCommandStatus.ACCEPTED) {
            draft.transition(InputCommandStatus.ACCEPTED)
        }
        val queued = draft.transition(InputCommandStatus.QUEUED)
        val paused = queued.transition(InputCommandStatus.PAUSED)
        assertEquals(InputCommandStatus.QUEUED, paused.transition(InputCommandStatus.QUEUED).status)
    }

    @Test
    fun sentCommandsCannotBeEditedAndTerminalStatesAreFinal() {
        val sending = command().transition(InputCommandStatus.SENDING, RequestAttemptId("attempt-1"))
        expectTransition(InputCommandStatus.SENDING, InputCommandStatus.SENDING) { sending.edit("changed") }
        val accepted = sending.transition(InputCommandStatus.ACCEPTED)
        assertTrue(accepted.isTerminal)
        expectTransition(InputCommandStatus.ACCEPTED, InputCommandStatus.CANCELLED) {
            accepted.transition(InputCommandStatus.CANCELLED)
        }
        val cancelled = command().transition(InputCommandStatus.CANCELLED)
        assertTrue(cancelled.isTerminal)
    }

    @Test
    fun retryKeepsCommandIdentityButTakesANewAttemptIdentity() {
        val failed = command()
            .transition(InputCommandStatus.SENDING, RequestAttemptId("attempt-1"))
            .transition(InputCommandStatus.FAILED)
        val retried = failed.retry(RequestAttemptId("attempt-2"))
        assertEquals(failed.id, retried.id)
        assertEquals(RequestAttemptId("attempt-2"), retried.attemptId)
        expectTransition(InputCommandStatus.SENDING, InputCommandStatus.SENDING) {
            retried.transition(InputCommandStatus.SENDING, RequestAttemptId("attempt-1"))
        }
        expectTransition(InputCommandStatus.ACCEPTED, InputCommandStatus.SENDING) {
            retried.transition(InputCommandStatus.ACCEPTED).retry(RequestAttemptId("attempt-2"))
        }
    }

    @Test
    fun attemptIdentityOnlyExistsForSendingFailedOrAcceptedCommands() {
        expectIllegalArgument { command(status = InputCommandStatus.DRAFT, attempt = RequestAttemptId("a")) }
        expectIllegalArgument { command(status = InputCommandStatus.SENDING, attempt = null) }
        val sending = command(status = InputCommandStatus.SENDING, attempt = RequestAttemptId("a"))
        assertEquals(RequestAttemptId("a"), sending.attemptId)
        assertNull(command().attemptId)
    }

    @Test
    fun transportRetryOfTheSameCommandIsIdempotentButContentConflictsAreRejected() {
        val registry = InputCommandRegistry()
        val first = registry.open(command())
        assertEquals(first, registry.open(command()))
        assertThrows(InputCommandConflict::class.java) { registry.open(command(text = "other")) }
        assertThrows(InputCommandConflict::class.java) {
            registry.open(
                InputCommand(CommandId("command"), ChatIdentity(ProjectId("project"), ChatId("other")), "synthetic"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.replace(command(id = "unknown").copyStatus(InputCommandStatus.QUEUED))
        }
    }

    @Test
    fun registryTracksActiveCommandsUntilTheyReachATerminalState() {
        val registry = InputCommandRegistry()
        val draft = registry.open(command())
        registry.replace(draft.transition(InputCommandStatus.CANCELLED))
        assertEquals(0, registry.active().size)
        val second = registry.open(command(id = "command-2"))
        registry.replace(second.transition(InputCommandStatus.QUEUED))
        assertEquals(listOf(CommandId("command-2")), registry.active().map { it.id })
    }

    private fun command(
        id: String = "command",
        text: String = "synthetic input",
        status: InputCommandStatus = InputCommandStatus.DRAFT,
        attempt: RequestAttemptId? = null,
    ) = InputCommand(CommandId(id), chat, text, status, attempt)

    private fun InputCommand.copyStatus(status: InputCommandStatus) =
        InputCommand(id, chat, text, status, attemptId)

    private fun expectTransition(
        from: InputCommandStatus,
        to: InputCommandStatus,
        body: () -> Unit,
    ) {
        val failure = assertThrows(InvalidInputTransition::class.java) { body() }
        assertEquals(from, failure.from)
        assertEquals(to, failure.to)
    }

    private fun expectIllegalArgument(body: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { body() }
    }
}
