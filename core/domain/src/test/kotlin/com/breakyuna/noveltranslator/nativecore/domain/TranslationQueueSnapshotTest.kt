package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TranslationQueueSnapshotTest {
    private val ref = LocalProjectRef(
        projectId = ProjectId("project"),
        instanceId = ProjectInstanceId("instance"),
        generation = RecoveryGeneration(1),
    )
    private val configuration = ModelConfigurationRef("model", 2)

    @Test
    fun restoreRebuildsAContiguousQueueWithItsCommandsAndSpecs() {
        val spec = spec("run-1", 0)
        val identity = spec.queueIdentity()
        val batch = TranslationTaskFailureBatch(
            TranslationTaskFailureBatchId("batch-1"),
            listOf(member(1)),
        )
        val facts = TranslationQueuedTaskFacts(
            spec = spec,
            sequence = 0,
            state = TranslationTaskQueueState.FAILED,
            batches = listOf(batch),
            commands = listOf(
                TranslationTaskCommandFacts(
                    commandId = CommandId("keep-1"),
                    action = TranslationTaskCommandAction.KEEP_ERROR,
                    batchId = batch.id,
                    memberIds = listOf(ItemId(1)),
                    evidence = batch.members,
                ),
            ),
        )
        val snapshot = TranslationQueueSnapshot.restore(
            ref = ref,
            revision = 3,
            updatedAtMillis = 20,
            limits = TranslationTaskQueueLimits.DEFAULT,
            tasks = listOf(facts),
            metas = listOf(TranslationQueueTaskMeta(10, 20)),
        )

        assertEquals(3L, snapshot.revision)
        assertEquals(spec, snapshot.spec(identity))
        assertEquals(TranslationTaskQueueState.FAILED, snapshot.task(identity)!!.state)
        assertEquals(TranslationTaskCommandAction.KEEP_ERROR, snapshot.task(identity)!!.commands.single().action)
        assertEquals(listOf(ItemId(1)), snapshot.task(identity)!!.decisions.single().memberIds)
    }

    @Test
    fun restoreRejectsLostForeignOrReorderedTasks() {
        val facts = TranslationQueuedTaskFacts(
            spec = spec("run-1", 0),
            sequence = 0,
            state = TranslationTaskQueueState.QUEUED,
            batches = emptyList(),
            commands = emptyList(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TranslationQueueSnapshot.restore(
                ref = ref,
                revision = 1,
                updatedAtMillis = 10,
                limits = TranslationTaskQueueLimits.DEFAULT,
                tasks = listOf(facts.copySequence(1)),
                metas = listOf(TranslationQueueTaskMeta(1, 1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranslationQueueSnapshot.restore(
                ref = ref,
                revision = 1,
                updatedAtMillis = 10,
                limits = TranslationTaskQueueLimits.DEFAULT,
                tasks = listOf(facts),
                metas = emptyList(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranslationQueueSnapshot.restore(
                ref = ref,
                revision = 1,
                updatedAtMillis = 10,
                limits = TranslationTaskQueueLimits.DEFAULT,
                tasks = listOf(
                    TranslationQueuedTaskFacts(
                        spec = spec("run-1", 0, otherProject = true),
                        sequence = 0,
                        state = TranslationTaskQueueState.QUEUED,
                        batches = emptyList(),
                        commands = emptyList(),
                    ),
                ),
                metas = listOf(TranslationQueueTaskMeta(1, 1)),
            )
        }
    }

    @Test
    fun updatedAdvancesOneRevisionAndRequiresTheSpecOfAnAppendedTask() {
        val first = spec("run-1", 0)
        val snapshot = TranslationQueueSnapshot.restore(
            ref = ref,
            revision = 1,
            updatedAtMillis = 10,
            limits = TranslationTaskQueueLimits.DEFAULT,
            tasks = listOf(
                TranslationQueuedTaskFacts(
                    spec = first,
                    sequence = 0,
                    state = TranslationTaskQueueState.QUEUED,
                    batches = emptyList(),
                    commands = emptyList(),
                ),
            ),
            metas = listOf(TranslationQueueTaskMeta(5, 10)),
        )
        val second = spec("run-2", 1)
        val admitted = snapshot.queue.enqueue(second)
        val queue = (admitted as TranslationQueueResult.Applied).queue
        val next = snapshot.updated(queue, second.queueIdentity(), 30, newSpec = second)

        assertEquals(2L, next.revision)
        assertEquals(first, next.spec(first.queueIdentity()))
        assertEquals(second, next.spec(second.queueIdentity()))
        assertEquals(TranslationQueueTaskMeta(30, 30), next.meta(second.queueIdentity()))

        assertThrows(IllegalArgumentException::class.java) {
            snapshot.updated(queue, second.queueIdentity(), 30)
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.updated(snapshot.queue, first.queueIdentity(), 5)
        }
    }

    private fun TranslationQueuedTaskFacts.copySequence(sequence: Long): TranslationQueuedTaskFacts =
        TranslationQueuedTaskFacts(spec, sequence, state, batches, commands)

    private fun spec(runId: String, revision: Long, otherProject: Boolean = false): TranslationTaskSpec {
        val owner = if (otherProject) {
            LocalProjectRef(ProjectId("other"), ProjectInstanceId("instance"), RecoveryGeneration(1))
        } else {
            ref
        }
        return TranslationTaskSpec(
            ref = owner,
            run = RunIdentity(
                projectId = owner.projectId,
                chatId = null,
                runId = RunId(runId),
                commandId = CommandId("command-$runId"),
            ),
            configuration = configuration,
            revision = revision,
            afterId = 0,
            pageSize = 20,
            wholeProject = false,
            options = RemoteTranslationOptions(targetLanguage = "zh"),
        )
    }

    private fun member(id: Long): TranslationFailureMember = TranslationFailureMember(
        itemId = ItemId(id),
        fingerprint = ObjectFingerprint("c".repeat(64)),
        failure = ModelFailureKind.NETWORK,
        receipt = TranslationFailureReceipt.RECEIVED,
    )
}
