package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.database.Cursor
import androidx.room.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.concurrent.Callable
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Entity(tableName = "native_translation_tasks", indices = [Index(value = ["instanceId", "generation", "projectId"])])
internal data class NativeTranslationTaskRow(
    @PrimaryKey val id: String, val instanceId: String, val generation: Long, val projectId: String,
    val runId: String, val commandId: String, val configurationId: String, val configurationRevision: Long,
    val targetLanguage: String, val specification: String,
    val revision: Long, val committed: Int, val requests: Int, val stage: String,
    val pendingAttempt: String?, val pendingCommands: String, val pendingItems: String, val responseRemaining: String,
) {
    override fun toString() = "NativeTranslationTaskRow(redacted)"
}
@Entity(tableName = "native_translation_targets", primaryKeys = ["task", "itemId"])
internal data class NativeTranslationTargetRow(val task: String, val itemId: Long, val fingerprint: String, val done: Boolean)
internal data class NativeTranslationTaskListRow(val position: Long, val id: String, val projectId: String,
    val runId: String, val commandId: String, val configurationId: String, val configurationRevision: Long,
    val targetLanguage: String, val revision: Long, val committed: Int, val requests: Int, val stage: String)
@Dao
internal interface NativeTranslationTaskDao {
    @Insert fun insert(row: NativeTranslationTaskRow)
    @Insert fun targets(rows: List<NativeTranslationTargetRow>)
    @Update fun update(row: NativeTranslationTaskRow): Int
    @Query("SELECT * FROM native_translation_tasks WHERE id=:id") fun find(id: String): NativeTranslationTaskRow?
    @Query("SELECT rowid AS position,id,projectId,runId,commandId,configurationId,configurationRevision,targetLanguage,revision,committed,requests,stage FROM native_translation_tasks WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND (:before IS NULL OR rowid < :before) ORDER BY rowid DESC LIMIT 21")
    fun page(instance: String, generation: Long, project: String, before: Long?): List<NativeTranslationTaskListRow>
    @Query("SELECT * FROM native_translation_targets WHERE task=:task AND itemId IN (:ids)")
    fun selected(task: String, ids: List<Long>): List<NativeTranslationTargetRow>
    @Query("UPDATE native_translation_targets SET done=1 WHERE task=:task AND itemId IN (:ids)")
    fun done(task: String, ids: List<Long>): Int
    @Query("UPDATE native_translation_targets SET fingerprint=:fingerprint WHERE task=:task AND itemId=:itemId")
    fun setFingerprint(task: String, itemId: Long, fingerprint: String)
    @Query("SELECT * FROM native_translation_targets WHERE task=:task AND itemId>:after ORDER BY itemId LIMIT 21")
    fun targetPage(task: String, after: Long): List<NativeTranslationTargetRow>
    @Query("SELECT COUNT(*) FROM native_translation_targets WHERE task=:task") fun count(task: String): Int
    @Query("SELECT COUNT(*) FROM native_translation_targets WHERE task=:task AND done=0") fun remaining(task: String): Int
}

/** Fixed membership and payload, with project receipts as the authority after a crash. */
class NativeTranslationTaskStore(context: Context, private val projects: TextProjectPort,
    private val runtime: RuntimePort,
    private val retryRecovery: TranslationRetryRecoveryPolicy? = null,
    private val ledger: UsageLedgerReadPort? = null,
) : TranslationTaskPort, TranslationTaskReviewPort, TranslationTaskRetryPort, TranslationTaskOutcomePort,
    TranslationTaskFailureEvidencePort, TranslationTaskFailureAdjudicationPort,
    TranslationTaskRetrySelectionPort, TranslationTaskSkipSelectionPort {
    private val db = NativeAppDatabase.open(context)
    private val retryCheckpoints = NativeTranslationRetryCheckpointStore(context, runtime)
    private fun key(run: RunIdentity) = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(
        JsonArray(listOf(run.projectId.value, run.runId.value, run.commandId.value).map(::JsonPrimitive)).toString()))
    private fun <T> transaction(block: () -> T) = db.runInTransaction(Callable { block() })
    private fun row(lease: RunLease): NativeTranslationTaskRow {
        val active = runtime.snapshot().activeRun
        require(active != null && active.identity == lease.identity && active.owner == RuntimeOwner.TRANSLATION)
        val operation = runtime.beginOperation(lease, OperationKind.HOST_REQUEST)
        runtime.endOperation(operation)
        return requireNotNull(db.translationTasks().find(key(lease.identity)))
    }
    override suspend fun open(lease: RunLease, spec: TranslationTaskSpec, resuming: Boolean): TranslationTaskProgress = withContext(Dispatchers.IO) {
        require(lease.identity == spec.run)
        val operation = runtime.beginOperation(lease, OperationKind.HOST_REQUEST)
        try {
            val id = key(spec.run)
            val old = db.translationTasks().find(id)
            if (old != null) {
                require(resuming) { "Existing task requires explicit continuation" }
                require(old.stage !in setOf("COMPLETE", "CANCELLED")) { "Task is terminal" }
                require(decode(old.specification).copy(revision = spec.revision) == spec)
                require(old.revision == spec.revision && (old.stage == "READY" || recoverable(old)))
                require(projects.open(spec.ref).revision == old.revision)
                return@withContext TranslationTaskProgress(old.revision, old.committed, old.requests, decode(old.specification).revision)
            }
            require(!resuming) { "Missing original task" }
            val assetPort = projects as? ProjectAssetPort
            val assets = assetPort?.assets(spec.ref)
            val enabled = assets?.filter { it.enabled }?.mapTo(HashSet()) { it.id }
            val pagePolicy = assetPort?.let { ProjectPageTranslationPolicy(it.projectSettings(spec.ref), requireNotNull(assets)) }
            val targets = ArrayList<NativeTranslationTargetRow>()
            var cursor = spec.afterId
            do {
                currentCoroutineContext().ensureActive()
                val page = projects.items(spec.ref, spec.revision, cursor, if (spec.wholeProject) 100 else spec.pageSize)
                page.items.filter { spec.options.startMode.accepts(it.status) && it.source.isNotBlank() &&
                    (enabled == null || it.assetId in enabled) && pagePolicy?.accepts(it) != false }.forEach {
                    targets.add(NativeTranslationTargetRow(id, it.id.value, it.fingerprint.sha256, false))
                }
                require(targets.size <= 100_000)
                if (!spec.wholeProject || page.items.isEmpty()) break
                cursor = page.items.last().id.value
            } while (true)
            currentCoroutineContext().ensureActive()
            transaction {
                db.translationTasks().insert(NativeTranslationTaskRow(id, spec.ref.instanceId.value, spec.ref.generation.value,
                    spec.ref.projectId.value, spec.run.runId.value, spec.run.commandId.value, spec.configuration.id,
                    spec.configuration.revision, spec.options.targetLanguage, encode(spec),
                    spec.revision, 0, 0, "READY", null, "[]", "[]", "[]"))
                targets.chunked(500).forEach { db.translationTasks().targets(it) }
            }
            TranslationTaskProgress(spec.revision, 0, 0)
        } finally { runtime.endOperation(operation) }
    }
    override suspend fun remaining(lease: RunLease, ref: LocalProjectRef, items: List<TextProjectItem>): Set<ItemId> = withContext(Dispatchers.IO) {
        val row = row(lease); require(decode(row.specification).ref == ref)
        val targets = db.translationTasks().selected(row.id, items.map { it.id.value }).filter { !it.done }.associateBy { it.itemId }
        items.filter { it.id.value in targets }.forEach { require(it.fingerprint.sha256 == targets.getValue(it.id.value).fingerprint) }
        targets.keys.mapTo(HashSet(), ::ItemId)
    }
    override suspend fun requesting(lease: RunLease, revision: Long, attempt: RequestAttemptId, targets: List<ItemId>, requests: Int): Unit = withContext(Dispatchers.IO) {
        transaction {
            val row = row(lease); require(row.revision == revision && row.stage in setOf("READY", "REQUESTING", "RESPONSE_RECORDED"))
            require(targets.isNotEmpty() && targets.size <= 100 && targets.distinct().size == targets.size && requests >= row.requests)
            val selected = db.translationTasks().selected(row.id, targets.map { it.value })
            require(selected.size == targets.size && selected.none { it.done })
            // Retries inside one explicitly budgeted run are recorded by its audit. Recovery never retries them.
            check(db.translationTasks().update(row.copy(stage = "REQUESTING", pendingAttempt = attempt.value,
                pendingCommands = "[]", pendingItems = ids(targets.map { it.value }), requests = requests,
                responseRemaining = ids((numbers(row.responseRemaining) + targets.map { it.value }).distinct()))) == 1)
        }
    }
    override suspend fun committing(lease: RunLease, revision: Long, edits: List<ManualTextEdit>, failures: Boolean): Unit = withContext(Dispatchers.IO) {
        transaction {
            val row = row(lease); require(row.revision == revision && edits.size in 1..20)
            val spec = decode(row.specification)
            val selected = db.translationTasks().selected(row.id, edits.map { it.itemId.value }).associateBy { it.itemId }
            require(selected.size == edits.size && edits.map { it.commandId }.distinct().size == edits.size)
            val staged = db.translationResponses().staged(row.id).associateBy { it.itemId }
            edits.forEachIndexed { index, edit ->
                staged[edit.itemId.value]?.let { cached ->
                    require(!failures && cached.commandId == edit.commandId.value && cached.text == edit.translation &&
                        cached.fingerprint == edit.baseFingerprint.sha256)
                }
                val target = selected.getValue(edit.itemId.value)
                require(!target.done && edit.ref == spec.ref && edit.expectedRevision == Math.addExact(revision, index.toLong()) &&
                    edit.baseFingerprint.sha256 == target.fingerprint &&
                    edit.status == (if (failures) TextItemStatus.ERROR else TextItemStatus.PROCESSED))
            }
            check(db.translationTasks().update(row.copy(stage = if (failures) "MARKING_FAILURE" else "COMMITTING",
                pendingCommands = JsonArray(edits.map { JsonPrimitive(it.commandId.value) }).toString(),
                pendingItems = ids(edits.map { it.itemId.value }))) == 1)
        }
    }
    override suspend fun committed(lease: RunLease, revision: Long, committed: Int): Unit = withContext(Dispatchers.IO) {
        val row = row(lease)
        settle(row, decode(row.specification).ref)
        val settled = requireNotNull(db.translationTasks().find(row.id))
        require(settled.revision == revision && settled.committed == committed)
    }
    override suspend fun stage(lease: RunLease, outputs: List<TranslationTaskOutput>): Unit = withContext(Dispatchers.IO) {
        require(outputs.size in 1..100 && outputs.map { it.id }.distinct().size == outputs.size)
        require(outputs.sumOf { require(it.text.length <= 65_536); TextProjectFingerprint.utf8(it.text).size.toLong() } <= 32L * 1024 * 1024)
        transaction {
            val row = row(lease)
            val selected = db.translationTasks().selected(row.id, outputs.map { it.id.value }).associateBy { it.itemId }
            require(selected.size == outputs.size && outputs.all { !selected.getValue(it.id.value).done &&
                selected.getValue(it.id.value).fingerprint == it.fingerprint.sha256 })
            val expected = numbers(row.responseRemaining)
            require(expected.isEmpty() || outputs.all { it.id.value in expected })
            require(db.translationResponses().stagedIds(row.id).isEmpty())
            db.translationResponses().stage(outputs.map {
                NativeTranslationStagedRow(row.id, it.id.value, it.fingerprint.sha256, UUID.randomUUID().toString(), it.text)
            })
            check(db.translationTasks().update(row.copy(stage = "RESPONSE_RECORDED",
                responseRemaining = if (expected.isEmpty()) ids(outputs.map { it.id.value }) else row.responseRemaining)) == 1)
        }
    }
    override suspend fun staged(lease: RunLease): List<ManualTextEdit> = withContext(Dispatchers.IO) {
        val row = row(lease); val spec = decode(row.specification)
        val values = db.translationResponses().staged(row.id)
        val selected = db.translationTasks().selected(row.id, values.map { it.itemId }).associateBy { it.itemId }
        values.mapIndexed { offset, value ->
            require(value.text.length <= 65_536 && value.itemId in numbers(row.responseRemaining) &&
                selected.getValue(value.itemId).fingerprint == value.fingerprint && !selected.getValue(value.itemId).done)
            TextProjectFingerprint.utf8(value.text)
            ManualTextEdit(CommandId(value.commandId), spec.ref, ItemId(value.itemId), Math.addExact(row.revision, offset.toLong()),
                ObjectFingerprint(value.fingerprint), value.text)
        }
    }
    override suspend fun response(lease: RunLease): TranslationTaskResponse? = withContext(Dispatchers.IO) {
        val row = row(lease)
        if (row.stage == "READY" || db.translationResponses().stagedIds(row.id).isNotEmpty()) return@withContext null
        val attempt = row.pendingAttempt ?: throw TranslationTaskNeedsReview()
        val receipt = db.translationRequests().find(attempt) ?: throw TranslationTaskNeedsReview()
        val spec = decode(row.specification)
        require(NativeTranslationAuditStore.matches(receipt, spec.ref, spec.run) && receipt.state == "RECEIVED" && receipt.failure == null &&
            receipt.configurationId == spec.configuration.id && receipt.configurationRevision == spec.configuration.revision)
        val values = db.translationResponses().responses(attempt)
        val inputs = db.translationResponses().inputs(attempt)
        require(values.size in 1..20 && values.map { it.itemId } == inputs.map { it.itemId })
        val targets = db.translationTasks().selected(row.id, values.map { it.itemId }).associateBy { it.itemId }
        values.forEachIndexed { index, value ->
            require(value.fingerprint == inputs[index].fingerprint && targets.getValue(value.itemId).fingerprint == value.fingerprint)
        }
        val saved = retryCheckpoints.readCoordinated(spec.ref, spec.run, spec.configuration)
        val partition = saved?.takeIf { it.pending.isNotEmpty() }?.let { retryPartition(row, spec, it) }
        if (saved?.pending?.isNotEmpty() == true && partition == null) throw TranslationTaskNeedsReview()
        TranslationTaskResponse(values.map { TranslationTaskOutput(ItemId(it.itemId), ObjectFingerprint(it.fingerprint), it.text) },
            (partition?.first ?: numbers(row.responseRemaining)).map(::ItemId))
    }
    private suspend fun recoverable(row: NativeTranslationTaskRow): Boolean {
        val ids = numbers(row.responseRemaining)
        if (ids.isEmpty()) return false
        val staged = db.translationResponses().stagedIds(row.id)
        if (staged.isNotEmpty()) {
            if (staged.toSet() == ids.toSet()) return true
            val spec = decode(row.specification)
            val saved = retryCheckpoints.readCoordinated(spec.ref, spec.run, spec.configuration) ?: return false
            val partition = retryPartition(row, spec, saved) ?: return false
            if (staged.toSet() != partition.first.toSet()) return false
        }
        val attempt = row.pendingAttempt ?: return false
        val receipt = db.translationRequests().find(attempt) ?: return false
        return receipt.state == "RECEIVED" && receipt.failure == null && receipt.instanceId == row.instanceId &&
            receipt.generation == row.generation && receipt.projectId == row.projectId && receipt.runId == row.runId &&
            receipt.commandId == row.commandId && receipt.configurationId == row.configurationId &&
            receipt.configurationRevision == row.configurationRevision && db.translationResponses().responseCount(attempt) in 1..20
    }
    override suspend fun failed(lease: RunLease): Unit = withContext(Dispatchers.IO) {
        transaction {
            val row = row(lease)
            require(row.stage in setOf("READY", "REQUESTING", "RESPONSE_RECORDED", "FAILED", "NEEDS_REVIEW"))
            val spec = decode(row.specification)
            val unresolved = db.translationRequests().unresolved(spec.ref.instanceId.value, spec.ref.generation.value,
                spec.ref.projectId.value, spec.run.runId.value, spec.run.commandId.value)
            check(db.translationTasks().update(row.copy(stage = if (unresolved > 0) "NEEDS_REVIEW" else "FAILED")) == 1)
        }
    }
    override suspend fun finished(lease: RunLease): Unit = withContext(Dispatchers.IO) {
        transaction {
            val row = row(lease); require(row.stage == "READY")
            // A manual/partial retry may have no eligible target left while explicitly skipped
            // ERROR rows remain unfinished. Publish COMPLETE only when every target row is done;
            // otherwise keep READY for the queue scheduler's committed/skipped/receipt proof.
            val remaining = db.translationTasks().remaining(row.id)
            check(db.translationTasks().update(row.copy(stage = if (remaining == 0) "COMPLETE" else "READY")) == 1)
        }
    }
    override suspend fun resume(ref: LocalProjectRef, run: RunIdentity): TranslationTaskSpec = withContext(Dispatchers.IO) {
        val write = runtime.beginProjectWrite()
        try {
            val row = requireNotNull(db.translationTasks().find(key(run)))
            val spec = decode(row.specification); require(spec.ref == ref && spec.run == run)
            require(row.stage !in setOf("COMPLETE", "CANCELLED"))
            if (row.stage in setOf("FAILED", "NEEDS_REVIEW")) throw TranslationTaskNeedsReview()
            reconcileRecordedFailure(row, spec)
            reconcileUnsentRetry(requireNotNull(db.translationTasks().find(row.id)), spec)
            reconcilePendingRetry(requireNotNull(db.translationTasks().find(row.id)), spec)
            settle(requireNotNull(db.translationTasks().find(row.id)), ref)
            reconcileCommittedRetry(requireNotNull(db.translationTasks().find(row.id)), spec)
            reconcilePendingRetry(requireNotNull(db.translationTasks().find(row.id)), spec)
            val settled = requireNotNull(db.translationTasks().find(row.id))
            val retry = retryCheckpoints.readReserved(spec.ref, spec.run, spec.configuration)
            if (retry?.terminalFailure != null) {
                transaction { check(db.translationTasks().update(settled.copy(stage = "FAILED")) == 1) }
                throw TranslationTaskNeedsReview()
            }
            if ((settled.stage != "READY" && !recoverable(settled)) || db.translationRequests().unresolved(ref.instanceId.value, ref.generation.value,
                ref.projectId.value, run.runId.value, run.commandId.value) > 0) throw TranslationTaskNeedsReview()
            if (projects.open(ref).revision != settled.revision) throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
            spec.copy(revision = settled.revision)
        } finally { runtime.endProjectWrite(write) }
    }
    override suspend fun review(ref: LocalProjectRef, run: RunIdentity): TranslationTaskReview = withContext(Dispatchers.IO) {
        val write = runtime.beginProjectWrite()
        try {
            projects.open(ref)
            transaction { reviewRow(ref, run) }
        } finally { runtime.endProjectWrite(write) }
    }

    /**
     * Completion evidence is read from persisted targets, the task's proven committed count, the
     * queue's explicit skip set and the usage ledger. No in-memory progress or synthetic ACTUAL is
     * used. A skip must name an actual unfinished target of this task, otherwise the caller stops
     * for review instead of inflating completion.
     */
    override suspend fun outcome(ref: LocalProjectRef, run: RunIdentity,
        skippedItemIds: Set<ItemId>): TranslationTaskOutcomeEvidence = withContext(Dispatchers.IO) {
        val facts = transaction {
            val row = requireNotNull(db.translationTasks().find(key(run)))
            val spec = decode(row.specification)
            require(spec.ref == ref && spec.run == run)
            val target = db.translationTasks().count(row.id)
            val skipped = if (skippedItemIds.isEmpty()) 0 else {
                val selected = db.translationTasks().selected(row.id, skippedItemIds.map { it.value })
                require(selected.size == skippedItemIds.size && selected.none { it.done })
                selected.size
            }
            val unresolved = db.translationRequests().unresolved(ref.instanceId.value, ref.generation.value,
                ref.projectId.value, run.runId.value, run.commandId.value)
            OutcomeFacts(row.stage, target, row.committed, skipped, unresolved)
        }
        val usage = ledger?.summary(UsageLedgerQuery(ref = ref, run = run))
        val unresolvedDispatches = Math.addExact(facts.unresolved.toLong(), usage?.unresolved ?: 0L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val usageSource = if (usage == null) UsageSource.UNKNOWN
        else usage.totals.usageSource(usage.unresolved)
        TranslationTaskOutcomeEvidence(
            stage = facts.stage,
            targetItems = facts.target,
            committedItems = facts.committed,
            skippedItems = facts.skipped,
            receiptedItems = facts.committed,
            unresolvedDispatches = unresolvedDispatches,
            usage = usageSource,
        )
    }

    private data class OutcomeFacts(
        val stage: String,
        val target: Int,
        val committed: Int,
        val skipped: Int,
        val unresolved: Int,
    )

    private data class MemberReceipt(
        val state: String,
        val failure: String?,
        val usage: String?,
    )

    /**
     * Bounded durable failure facts for one fixed task. Only persisted target rows, the current
     * project item fingerprint/status and request audit receipts are read; no source or
     * translation body is loaded into the result.
     */
    override suspend fun failures(
        ref: LocalProjectRef,
        run: RunIdentity,
        after: Long,
    ): TranslationTaskFailureEvidence = withContext(Dispatchers.IO) {
        require(after >= 0)
        val write = runtime.beginProjectWrite()
        try {
            val projectRevision = projects.open(ref).revision
            val id = key(run)
            val row = requireNotNull(db.translationTasks().find(id))
            val spec = decode(row.specification)
            require(spec.ref == ref && spec.run == run) { "Failure evidence belongs to another task" }
            val page = db.translationTasks().targetPage(id, after)
            val examined = page.take(TranslationTaskFailureBatch.MAX_MEMBERS)
            val items = examined.associate { target ->
                val item = projects.item(ref, ItemId(target.itemId))?.item ?: throw TranslationTaskNeedsReview()
                require(item.fingerprint.sha256 == target.fingerprint) { "Target fingerprint changed" }
                target.itemId to item
            }
            val receipts = latestReceipts(ref, run, examined.map { it.itemId })
            val members = examined.mapNotNull { target ->
                failureMember(target, items.getValue(target.itemId), receipts[target.itemId])
            }
            val next = if (page.size > TranslationTaskFailureBatch.MAX_MEMBERS) examined.last().itemId else null
            TranslationTaskFailureEvidence(reviewRow(ref, run), projectRevision, members, next)
        } finally { runtime.endProjectWrite(write) }
    }

    override suspend fun failureMembers(
        ref: LocalProjectRef,
        run: RunIdentity,
        itemIds: List<ItemId>,
    ): List<TranslationFailureMember> = withContext(Dispatchers.IO) {
        require(itemIds.isNotEmpty() && itemIds.size <= TranslationTaskFailureBatch.MAX_MEMBERS &&
            itemIds.distinct().size == itemIds.size)
        val write = runtime.beginProjectWrite()
        try {
            projects.open(ref)
            val id = key(run)
            val row = requireNotNull(db.translationTasks().find(id))
            val spec = decode(row.specification)
            require(spec.ref == ref && spec.run == run) { "Failure evidence belongs to another task" }
            val targets = db.translationTasks().selected(id, itemIds.map { it.value })
            require(targets.size == itemIds.size) { "Selected member is not a task target" }
            val items = targets.associate { target ->
                val item = projects.item(ref, ItemId(target.itemId))?.item ?: throw TranslationTaskNeedsReview()
                require(item.fingerprint.sha256 == target.fingerprint) { "Target fingerprint changed" }
                target.itemId to item
            }
            val receipts = latestReceipts(ref, run, targets.map { it.itemId })
            targets.mapNotNull { target ->
                failureMember(target, items.getValue(target.itemId), receipts[target.itemId])
            }.sortedBy { it.itemId.value }
        } finally { runtime.endProjectWrite(write) }
    }

    /**
     * Records one explicit user decision on the SAME fixed task. Selection evidence is rebuilt
     * from the target rows, current project fingerprints and request audit, then compared with the
     * queue batch copy. No new Run/task and no retry-budget replenishment is performed.
     */
    override suspend fun recordFailureDecision(
        evidence: TranslationTaskFailureEvidence,
        batchId: TranslationTaskFailureBatchId,
        action: TranslationTaskCommandAction,
        members: List<TranslationFailureMember>,
        command: CommandId,
    ): TranslationTaskFailureDecisionReceipt = withContext(Dispatchers.IO) {
        require(action.adjudication) { "Not a member adjudication action" }
        require(members.size in 1..TranslationTaskFailureBatch.MAX_MEMBERS &&
            members.map { it.itemId }.distinct().size == members.size)
        val review = evidence.review
        val ordered = members.sortedBy { it.itemId.value }
        val write = runtime.beginProjectWrite()
        try {
            val project = projects.open(review.ref)
            val id = key(review.run)
            val row = requireNotNull(db.translationTasks().find(id))
            val spec = decode(row.specification)
            require(spec.ref == review.ref && spec.run == review.run) { "Failure decision belongs to another task" }
            val taskAction = "${action.name}:${batchId.value}"
            val digest = failureDecisionDigest(batchId, action, ordered)
            val replay = db.translationTaskDecisions().find(command.value)
            if (replay != null) {
                require(replay.task == id && replay.action == taskAction && replay.fingerprint == digest) {
                    "Failure decision command conflict"
                }
                return@withContext TranslationTaskFailureDecisionReceipt(
                    command, action, batchId, ordered.map { it.itemId }, 0)
            }
            require(project.revision == evidence.projectRevision) { "Project revision changed" }
            val current = reviewRow(review.ref, review.run)
            require(current == review) { "Task review changed" }
            require(current.stage in FAILURE_DECISION_STAGES) { "Task is not awaiting a failure decision" }
            require(action == TranslationTaskCommandAction.KEEP_ERROR || current.unresolvedRequests == 0) {
                "An unresolved dispatch cannot be retried or skipped"
            }
            val targets = db.translationTasks().selected(id, ordered.map { it.itemId.value }).associateBy { it.itemId }
            require(targets.size == ordered.size) { "Selected member is not a task target" }
            val prior = db.translationTaskDecisions().selected(id, ordered.map { it.itemId.value })
                .associateBy { it.itemId }
            val items = ordered.associate { member ->
                val target = targets.getValue(member.itemId.value)
                require(!target.done && target.fingerprint == member.fingerprint.sha256) {
                    "Target or fingerprint changed"
                }
                val item = projects.item(review.ref, member.itemId)?.item ?: throw TranslationTaskNeedsReview()
                require(item.fingerprint == member.fingerprint) { "Project item fingerprint changed" }
                member.itemId to item
            }
            val receipts = latestReceipts(review.ref, review.run, ordered.map { it.itemId.value })
            val fresh = ordered.mapNotNull { member ->
                failureMember(targets.getValue(member.itemId.value), items.getValue(member.itemId),
                    receipts[member.itemId.value])
            }.sortedBy { it.itemId.value }
            require(fresh == ordered) { "Failure evidence changed" }
            for (member in ordered) {
                val previous = prior[member.itemId.value]
                when (action) {
                    TranslationTaskCommandAction.RETRY_MEMBERS -> {
                        require(!member.committed && !member.terminal) { "Member is not retryable" }
                        require(member.receipt != TranslationFailureReceipt.DISPATCHED_NO_RECEIPT) {
                            "An unreceipted dispatch cannot be retried"
                        }
                        require(previous == null || previous.action == RETRY_MEMBER_ACTION) {
                            "Member already has a final decision"
                        }
                    }
                    TranslationTaskCommandAction.SKIP_MEMBERS -> {
                        require(!member.committed) { "Committed member cannot be skipped" }
                        require(member.receipt != TranslationFailureReceipt.DISPATCHED_NO_RECEIPT) {
                            "An unreceipted dispatch cannot be skipped"
                        }
                        require(previous == null || previous.action == RETRY_MEMBER_ACTION) {
                            "Member already has a final decision"
                        }
                    }
                    TranslationTaskCommandAction.KEEP_ERROR -> {
                        require(!member.committed) { "Committed member cannot be kept as an error" }
                        require(previous == null || previous.action == RETRY_MEMBER_ACTION) {
                            "Member already has a final decision"
                        }
                    }
                    else -> throw IllegalArgumentException("Not a member adjudication action")
                }
            }
            transaction {
                require(reviewRow(review.ref, review.run) == review) { "Task review changed" }
                val currentTargets = db.translationTasks().selected(id, ordered.map { it.itemId.value })
                    .associateBy { it.itemId }
                require(currentTargets.size == ordered.size)
                val currentPrior = db.translationTaskDecisions().selected(id, ordered.map { it.itemId.value })
                    .associateBy { it.itemId }
                for (member in ordered) {
                    val target = currentTargets.getValue(member.itemId.value)
                    require(!target.done && target.fingerprint == member.fingerprint.sha256)
                    val previous = currentPrior[member.itemId.value]
                    require(previous == null || previous.action == RETRY_MEMBER_ACTION) {
                        "Member decision changed"
                    }
                }
                db.translationTaskDecisions().insert(NativeTranslationTaskDecisionRow(
                    command.value, id, taskAction, digest, review.stage, review.revision,
                    review.committed, review.remaining, review.requests, review.unresolvedRequests))
                for (member in ordered) {
                    val previous = currentPrior[member.itemId.value]
                    if (previous == null) {
                        db.translationTaskDecisions().members(listOf(NativeTranslationMemberDecisionRow(
                            id, member.itemId.value, command.value, action.name,
                            member.fingerprint.sha256, evidence.projectRevision)))
                    } else if (previous.action == RETRY_MEMBER_ACTION) {
                        db.openHelper.writableDatabase.execSQL(
                            "UPDATE native_translation_member_decisions SET command=?, action=?, revision=? " +
                                "WHERE task=? AND itemId=? AND action=?",
                            arrayOf<Any?>(command.value, action.name, evidence.projectRevision,
                                id, member.itemId.value, RETRY_MEMBER_ACTION),
                        )
                    }
                }
                val settled = requireNotNull(db.translationTasks().find(id))
                val remaining = db.translationTasks().remaining(id)
                val nextStage = when (action) {
                    TranslationTaskCommandAction.RETRY_MEMBERS -> "READY"
                    TranslationTaskCommandAction.KEEP_ERROR -> "FAILED"
                    TranslationTaskCommandAction.SKIP_MEMBERS ->
                        // Explicit skips stay as unfinished target rows. READY lets the queue
                        // scheduler continue the remaining targets (or finalize from exact
                        // committed+skipped evidence) without a second review round.
                        "READY"
                    else -> settled.stage
                }
                if (nextStage != settled.stage) {
                    check(db.translationTasks().update(settled.copy(stage = nextStage)) == 1)
                }
            }
            TranslationTaskFailureDecisionReceipt(
                command = command,
                action = action,
                batchId = batchId,
                memberIds = ordered.map { it.itemId },
                retryBudget = 0,
            )
        } finally { runtime.endProjectWrite(write) }
    }

    /**
     * Finalizes the SAME fixed task after the queue proved every target is either committed or in
     * the exact skip set. It refuses a stale/foreign skip, an unresolved dispatch or unknown usage;
     * it never invents an ACTUAL receipt for a request that has none.
     */
    override suspend fun completeWithErrors(
        ref: LocalProjectRef,
        run: RunIdentity,
        skippedItemIds: Set<ItemId>,
        command: CommandId,
    ): Unit = withContext(Dispatchers.IO) {
        val ordered = skippedItemIds.sortedBy { it.value }
        // Below SQLite's common 999 bind-parameter limit; larger skip sets fail closed instead of
        // issuing a query that could silently mix projects.
        require(ordered.size <= 900) { "Too many skipped members for one completion proof" }
        val write = runtime.beginProjectWrite()
        try {
            val project = projects.open(ref)
            val id = key(run)
            val row = requireNotNull(db.translationTasks().find(id))
            val spec = decode(row.specification)
            require(spec.ref == ref && spec.run == run) { "Completion belongs to another task" }
            require(project.revision >= row.revision) { "Project revision regressed" }
            val action = if (ordered.isEmpty()) COMPLETE_ACTION else COMPLETED_WITH_ERRORS_ACTION
            val digest = completionDigest(ref, run, row, ordered)
            val replay = db.translationTaskDecisions().find(command.value)
            if (replay != null) {
                require(replay.task == id && replay.action == action && replay.fingerprint == digest) {
                    "Completion command conflict"
                }
                return@withContext
            }
            require(row.stage !in setOf("COMPLETE", "CANCELLED")) { "Task is already terminal" }
            val targetCount = db.translationTasks().count(id)
            val remaining = db.translationTasks().remaining(id)
            val skippedTargets = if (ordered.isEmpty()) emptyList() else
                db.translationTasks().selected(id, ordered.map { it.value })
            require(skippedTargets.size == ordered.size) { "Skipped member is not a task target" }
            require(skippedTargets.none { it.done }) { "Committed target cannot be skipped" }
            val memberDecisions = if (ordered.isEmpty()) emptyMap() else db.translationTaskDecisions()
                .selected(id, ordered.map { it.value }).associateBy { it.itemId }
            require(ordered.all { itemId ->
                val target = skippedTargets.first { it.itemId == itemId.value }
                val decision = memberDecisions[itemId.value]
                decision?.action == SKIP_MEMBER_ACTION && decision.fingerprint == target.fingerprint
            }) { "Skipped member has no durable skip decision" }
            require(remaining == ordered.size) { "Unfinished targets remain unaccounted" }
            require(targetCount - remaining == row.committed) { "Committed target count changed" }
            val unresolved = db.translationRequests().unresolved(ref.instanceId.value, ref.generation.value,
                ref.projectId.value, run.runId.value, run.commandId.value)
            require(unresolved == 0) { "An unresolved dispatch forbids completion" }
            val usage = ledger?.summary(UsageLedgerQuery(ref = ref, run = run))
            require(usage != null && usage.totals.usageSource(usage.unresolved) != UsageSource.UNKNOWN) {
                "Unknown usage forbids completion"
            }
            transaction {
                val current = requireNotNull(db.translationTasks().find(id))
                require(current.stage !in setOf("COMPLETE", "CANCELLED"))
                require(db.translationTasks().remaining(id) == ordered.size)
                require(db.translationTasks().count(id) - ordered.size == current.committed)
                db.translationTaskDecisions().insert(NativeTranslationTaskDecisionRow(
                    command.value, id, action, digest, current.stage, current.revision,
                    current.committed, ordered.size, current.requests, unresolved))
                check(db.translationTasks().update(current.copy(stage = "COMPLETE")) == 1)
            }
        } finally { runtime.endProjectWrite(write) }
    }

    /**
     * Explicit same-task retry selection read by the execution service. Only durable RETRY member
     * decisions for still-unfinished ERROR targets are returned; a changed fingerprint refuses the
     * selection instead of silently translating a different object.
     */
    override suspend fun retrySelection(lease: RunLease): Set<ItemId> = withContext(Dispatchers.IO) {
        val active = runtime.snapshot().activeRun
        require(active != null && active.identity == lease.identity &&
            active.owner == RuntimeOwner.TRANSLATION) { "No matching translation lease" }
        val id = key(lease.identity)
        val row = requireNotNull(db.translationTasks().find(id))
        val spec = decode(row.specification)
        require(spec.run == lease.identity && spec.ref.projectId == lease.identity.projectId)
        val decisions = ArrayList<Pair<Long, String>>()
        db.openHelper.readableDatabase.query(
            "SELECT itemId,fingerprint FROM native_translation_member_decisions " +
                "WHERE task=? AND action=? ORDER BY itemId LIMIT ?",
            arrayOf<Any?>(id, RETRY_MEMBER_ACTION, TranslationTaskFailureBatch.MAX_MEMBERS),
        ).use { cursor ->
            while (cursor.moveToNext()) decisions.add(cursor.getLong(0) to cursor.getString(1))
        }
        if (decisions.isEmpty()) return@withContext emptySet()
        val targets = db.translationTasks().selected(id, decisions.map { it.first }).associateBy { it.itemId }
        require(targets.size == decisions.size) { "Retry member is not a task target" }
        val selected = LinkedHashSet<ItemId>()
        for ((itemId, fingerprint) in decisions) {
            val target = targets.getValue(itemId)
            if (target.done) continue
            require(target.fingerprint == fingerprint) { "Retry target fingerprint changed" }
            val item = projects.item(spec.ref, ItemId(itemId))?.item ?: throw TranslationTaskNeedsReview()
            require(item.fingerprint.sha256 == target.fingerprint) { "Project item fingerprint changed" }
            if (item.status == TextItemStatus.ERROR) selected.add(item.id)
        }
        selected
    }

    /**
     * Explicit skip decisions for the active lease. Skipped targets stay unfinished for completion
     * evidence but must not be requested again when a queue resume continues other members.
     */
    override suspend fun skipSelection(lease: RunLease): Set<ItemId> = withContext(Dispatchers.IO) {
        val active = runtime.snapshot().activeRun
        require(active != null && active.identity == lease.identity &&
            active.owner == RuntimeOwner.TRANSLATION) { "No matching translation lease" }
        val id = key(lease.identity)
        val row = requireNotNull(db.translationTasks().find(id))
        val spec = decode(row.specification)
        require(spec.run == lease.identity && spec.ref.projectId == lease.identity.projectId)
        val decisions = ArrayList<Pair<Long, String>>()
        db.openHelper.readableDatabase.query(
            "SELECT itemId,fingerprint FROM native_translation_member_decisions " +
                "WHERE task=? AND action=? ORDER BY itemId LIMIT ?",
            arrayOf<Any?>(id, SKIP_MEMBER_ACTION, TranslationTaskFailureBatch.MAX_MEMBERS),
        ).use { cursor ->
            while (cursor.moveToNext()) decisions.add(cursor.getLong(0) to cursor.getString(1))
        }
        if (decisions.isEmpty()) return@withContext emptySet()
        val targets = db.translationTasks().selected(id, decisions.map { it.first }).associateBy { it.itemId }
        require(targets.size == decisions.size) { "Skipped member is not a task target" }
        val selected = LinkedHashSet<ItemId>()
        for ((itemId, fingerprint) in decisions) {
            val target = targets.getValue(itemId)
            if (target.done) continue
            require(target.fingerprint == fingerprint) { "Skipped target fingerprint changed" }
            val item = projects.item(spec.ref, ItemId(itemId))?.item ?: throw TranslationTaskNeedsReview()
            require(item.fingerprint.sha256 == target.fingerprint) { "Project item fingerprint changed" }
            if (item.status == TextItemStatus.ERROR) selected.add(item.id)
        }
        selected
    }

    private fun latestReceipts(
        ref: LocalProjectRef,
        run: RunIdentity,
        itemIds: List<Long>,
    ): Map<Long, MemberReceipt> {
        if (itemIds.isEmpty()) return emptyMap()
        val placeholders = itemIds.joinToString(",") { "?" }
        val sql = "SELECT i.itemId,r.state,r.failure,r.usage " +
            "FROM native_translation_requests r " +
            "INNER JOIN native_translation_request_inputs i ON i.attemptId=r.attemptId " +
            "WHERE r.instanceId=? AND r.generation=? AND r.projectId=? AND r.runId=? AND r.commandId=? " +
            "AND i.itemId IN ($placeholders) ORDER BY i.itemId,r.rowid"
        val args = ArrayList<Any?>(5 + itemIds.size)
        args.add(ref.instanceId.value); args.add(ref.generation.value); args.add(ref.projectId.value)
        args.add(run.runId.value); args.add(run.commandId.value); args.addAll(itemIds)
        val result = LinkedHashMap<Long, MemberReceipt>()
        db.openHelper.readableDatabase.query(sql, args.toTypedArray()).use { cursor: Cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                result[id] = MemberReceipt(
                    state = cursor.getString(1),
                    failure = if (cursor.isNull(2)) null else cursor.getString(2),
                    usage = if (cursor.isNull(3)) null else cursor.getString(3),
                )
            }
        }
        return result
    }

    private fun failureMember(
        target: NativeTranslationTargetRow,
        item: TextProjectItem,
        receipt: MemberReceipt?,
    ): TranslationFailureMember? {
        val state = receipt?.state
        // Item failures are persisted as ERROR rows. An unresolved dispatch is also member-level
        // evidence even if the item row is still NONE. A received run-level failure without an
        // ERROR marker is not turned into a member decision.
        val failed = item.status == TextItemStatus.ERROR || state == "DISPATCHED"
        if (!failed) return null
        // A committed target without a received response cannot be reported as failure evidence;
        // it is either a normal success or an unproven/late state that must stay for review.
        if (target.done && state != "RECEIVED") return null
        val failure = receipt?.failure?.let { ModelFailureKind.valueOf(it) } ?: ModelFailureKind.UNKNOWN
        val received = state == "RECEIVED"
        val usage = if (received) receipt?.usage?.let {
            usageCertainty(TranslationUsageCodec.decode(it))
        } ?: UsageSource.UNKNOWN else UsageSource.UNKNOWN
        return TranslationFailureMember(
            itemId = item.id,
            fingerprint = item.fingerprint,
            failure = failure,
            receipt = when {
                received -> TranslationFailureReceipt.RECEIVED
                state == "DISPATCHED" -> TranslationFailureReceipt.DISPATCHED_NO_RECEIPT
                else -> TranslationFailureReceipt.NOT_DISPATCHED
            },
            usage = usage,
            committed = target.done,
            terminal = !target.done && received && failure in TERMINAL_ITEM_FAILURES,
        )
    }

    private fun usageCertainty(usage: RequestUsage): UsageSource {
        val metrics = listOf(usage.input, usage.cacheRead, usage.cacheWrite, usage.thinking, usage.output)
        return when {
            metrics.any { it.tokens == null || it.source == UsageSource.UNKNOWN } -> UsageSource.UNKNOWN
            metrics.any { it.source == UsageSource.ESTIMATED } -> UsageSource.ESTIMATED
            else -> UsageSource.ACTUAL
        }
    }

    private fun failureDecisionDigest(
        batchId: TranslationTaskFailureBatchId,
        action: TranslationTaskCommandAction,
        members: List<TranslationFailureMember>,
    ): String = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(JsonArray(
        listOf(
            JsonPrimitive(action.name), JsonPrimitive(batchId.value), JsonPrimitive(0),
        ) + members.flatMap { member ->
            listOf(
                JsonPrimitive(member.itemId.value), JsonPrimitive(member.fingerprint.sha256),
                JsonPrimitive(member.failure.name), JsonPrimitive(member.receipt.name),
                JsonPrimitive(member.usage.name), JsonPrimitive(member.committed),
                JsonPrimitive(member.terminal),
            )
        },
    ).toString()))

    private fun completionDigest(
        ref: LocalProjectRef,
        run: RunIdentity,
        row: NativeTranslationTaskRow,
        skipped: List<ItemId>,
    ): String = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(JsonArray(
        listOf(
            JsonPrimitive(ref.projectId.value), JsonPrimitive(ref.instanceId.value),
            JsonPrimitive(ref.generation.value), JsonPrimitive(run.runId.value),
            JsonPrimitive(run.commandId.value), JsonPrimitive(row.revision),
            JsonPrimitive(row.committed), JsonPrimitive(row.requests),
        ) + skipped.map { JsonPrimitive(it.value) },
    ).toString()))

    private fun skipDecisionCount(task: String): Int =
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM native_translation_member_decisions WHERE task=? AND action=?",
            arrayOf<Any?>(task, SKIP_MEMBER_ACTION),
        ).use { cursor ->
            require(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun reviewRow(ref: LocalProjectRef, run: RunIdentity): TranslationTaskReview {
        val row = requireNotNull(db.translationTasks().find(key(run)))
        val spec = decode(row.specification)
        require(spec.ref == ref && spec.run == run)
        val remaining = db.translationTasks().remaining(row.id)
        val unresolved = db.translationRequests().unresolved(ref.instanceId.value, ref.generation.value,
            ref.projectId.value, run.runId.value, run.commandId.value)
        // No body escapes to UI. Every mutable recovery field is part of the preview binding.
        val evidence = JsonArray(listOf(row.id, row.specification, row.revision.toString(), row.committed.toString(),
            row.requests.toString(), row.stage, row.pendingAttempt.orEmpty(), row.pendingCommands,
            row.pendingItems, row.responseRemaining, remaining.toString(), unresolved.toString()).map(::JsonPrimitive)).toString()
        return TranslationTaskReview(ref, run, row.stage, row.revision, row.committed, remaining,
            row.requests, unresolved, ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(evidence))))
    }

    override suspend fun terminate(review: TranslationTaskReview, command: CommandId): Unit = withContext(Dispatchers.IO) {
        val write = runtime.beginProjectWrite()
        try {
            projects.open(review.ref)
            transaction {
                val id = key(review.run)
                val existing = db.translationTaskDecisions().find(command.value)
                if (existing != null) {
                    require(existing.task == id && existing.action == "TERMINATE_TASK" &&
                        existing.fingerprint == review.fingerprint.sha256) { "Decision command conflict" }
                    return@transaction
                }
                val current = reviewRow(review.ref, review.run)
                require(current == review) { "Task review changed" }
                require(current.stage !in setOf("COMPLETE", "CANCELLED")) { "Task already terminal" }
                val row = requireNotNull(db.translationTasks().find(id))
                db.translationTaskDecisions().insert(NativeTranslationTaskDecisionRow(command.value, id,
                    "TERMINATE_TASK", review.fingerprint.sha256, review.stage, review.revision, review.committed,
                    review.remaining, review.requests, review.unresolvedRequests))
                // Do not erase pending attempts, staged responses, targets, or unknown usage.
                check(db.translationTasks().update(row.copy(stage = "CANCELLED")) == 1)
            }
        } finally { runtime.endProjectWrite(write) }
    }

    override suspend fun members(ref: LocalProjectRef, run: RunIdentity, after: Long): TranslationTaskMemberPage =
        withContext(Dispatchers.IO) {
            require(after >= 0)
            val write = runtime.beginProjectWrite()
            try {
                val revision = projects.open(ref).revision
                val row = requireNotNull(db.translationTasks().find(key(run)))
                val spec = decode(row.specification)
                require(spec.ref == ref && spec.run == run)
                val targets = db.translationTasks().targetPage(row.id, after)
                val decisions = db.translationTaskDecisions().selected(row.id, targets.take(20).map { it.itemId }).associateBy { it.itemId }
                val members = targets.take(20).map { target ->
                    val item = projects.item(ref, ItemId(target.itemId))?.item
                        ?: throw TranslationTaskNeedsReview()
                    TranslationTaskMemberReview(item.id, item.fingerprint, item.status, decisions[target.itemId]?.action)
                }
                TranslationTaskMemberPage(revision, members, targets.takeIf { it.size > 20 }?.get(19)?.itemId)
            } finally { runtime.endProjectWrite(write) }
        }

    override suspend fun retryErrors(review: TranslationTaskReview, revision: Long,
        members: List<TranslationTaskMemberReview>, command: CommandId): TranslationTaskSpec = withContext(Dispatchers.IO) {
        require(members.size in 1..20 && members.map { it.id }.distinct().size == members.size)
        require(members.all { it.status == TextItemStatus.ERROR && it.decision == null })
        val ordered = members.sortedBy { it.id.value }
        val digest = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(JsonArray(
            listOf(JsonPrimitive(review.fingerprint.sha256), JsonPrimitive(revision)) + ordered.map {
                JsonArray(listOf(JsonPrimitive(it.id.value), JsonPrimitive(it.fingerprint.sha256)))
            }).toString()))
        val write = runtime.beginProjectWrite()
        try {
            val project = projects.open(review.ref)
            val original = requireNotNull(db.translationTasks().find(key(review.run)))
            val oldSpec = decode(original.specification)
            require(oldSpec.ref == review.ref && oldSpec.run == review.run)
            val run = RunIdentity(review.ref.projectId, null, RunId("retry-$digest"), command)
            val id = key(run)
            val action = "RETRY_ERRORS:$id"
            db.translationTaskDecisions().find(command.value)?.let { existing ->
                require(existing.task == original.id && existing.action == action && existing.fingerprint == digest)
                return@withContext decode(requireNotNull(db.translationTasks().find(id)).specification)
            }
            require(project.revision == revision && reviewRow(review.ref, review.run) == review)
            require(review.stage in setOf("FAILED", "CANCELLED") && review.unresolvedRequests == 0)
            val selected = db.translationTasks().selected(original.id, ordered.map { it.id.value })
            require(selected.size == ordered.size)
            require(db.translationTaskDecisions().selected(original.id, ordered.map { it.id.value }).isEmpty()) {
                "Member already decided"
            }
            val assetPort = projects as? ProjectAssetPort
            val assets = assetPort?.assets(review.ref)
            val enabled = assets?.filter { it.enabled }?.mapTo(HashSet()) { it.id }
            val pagePolicy = assetPort?.let { ProjectPageTranslationPolicy(it.projectSettings(review.ref), requireNotNull(assets)) }
            ordered.forEach { evidence ->
                val current = projects.item(review.ref, evidence.id)?.item ?: throw TranslationTaskNeedsReview()
                require(current.status == TextItemStatus.ERROR && current.fingerprint == evidence.fingerprint &&
                    current.source.isNotBlank() && (enabled == null || current.assetId in enabled) && pagePolicy?.accepts(current) != false)
            }
            // A new explicit paid authorization gets its own immutable target set and retry budget.
            // The original checkpoint and all failure/usage facts stay untouched.
            val next = oldSpec.copy(run = run, revision = revision,
                options = oldSpec.options.copy(startMode = TranslationStartMode.RESET))
            transaction {
                require(reviewRow(review.ref, review.run) == review)
                db.translationTaskDecisions().insert(NativeTranslationTaskDecisionRow(command.value, original.id,
                    action, digest, review.stage, review.revision, review.committed, review.remaining,
                    review.requests, review.unresolvedRequests))
                db.translationTasks().insert(NativeTranslationTaskRow(id, next.ref.instanceId.value, next.ref.generation.value,
                    next.ref.projectId.value, run.runId.value, run.commandId.value, next.configuration.id,
                    next.configuration.revision, next.options.targetLanguage, encode(next), revision,
                    0, 0, "READY", null, "[]", "[]", "[]"))
                db.translationTasks().targets(ordered.map { NativeTranslationTargetRow(id, it.id.value, it.fingerprint.sha256, false) })
                db.translationTaskDecisions().members(ordered.map { NativeTranslationMemberDecisionRow(original.id,
                    it.id.value, command.value, "RETRY_ERRORS", it.fingerprint.sha256, revision) })
            }
            next
        } finally { runtime.endProjectWrite(write) }
    }

    override suspend fun keepErrors(review: TranslationTaskReview, revision: Long,
        members: List<TranslationTaskMemberReview>, command: CommandId): Unit = withContext(Dispatchers.IO) {
        require(members.size in 1..20 && members.map { it.id }.distinct().size == members.size)
        require(members.all { it.status == TextItemStatus.ERROR && it.decision == null })
        val ordered = members.sortedBy { it.id.value }
        val digest = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(JsonArray(
            listOf(JsonPrimitive("KEEP_ERROR"), JsonPrimitive(review.fingerprint.sha256), JsonPrimitive(revision)) +
                ordered.map { JsonArray(listOf(JsonPrimitive(it.id.value), JsonPrimitive(it.fingerprint.sha256))) }).toString()))
        val write = runtime.beginProjectWrite()
        try {
            val project = projects.open(review.ref)
            db.translationTaskDecisions().find(command.value)?.let { existing ->
                require(existing.task == key(review.run) && existing.action == "KEEP_ERROR" && existing.fingerprint == digest)
                return@withContext
            }
            // Load current item evidence under the same exclusive reservation before Room transaction.
            val currentMembers = ordered.map { evidence ->
                val item = projects.item(review.ref, evidence.id)?.item ?: throw TranslationTaskNeedsReview()
                TranslationTaskMemberReview(item.id, item.fingerprint, item.status)
            }
            transaction {
                val id = key(review.run)
                db.translationTaskDecisions().find(command.value)?.let { existing ->
                    require(existing.task == id && existing.action == "KEEP_ERROR" && existing.fingerprint == digest)
                    return@transaction
                }
                require(project.revision == revision && reviewRow(review.ref, review.run) == review)
                require(currentMembers == ordered)
                require(review.stage in setOf("FAILED", "NEEDS_REVIEW", "CANCELLED"))
                val selected = db.translationTasks().selected(id, ordered.map { it.id.value })
                require(selected.size == ordered.size)
                require(db.translationTaskDecisions().selected(id, ordered.map { it.id.value }).isEmpty())
                db.translationTaskDecisions().insert(NativeTranslationTaskDecisionRow(command.value, id, "KEEP_ERROR",
                    digest, review.stage, review.revision, review.committed, review.remaining, review.requests, review.unresolvedRequests))
                db.translationTaskDecisions().members(ordered.map { NativeTranslationMemberDecisionRow(id,
                    it.id.value, command.value, "KEEP_ERROR", it.fingerprint.sha256, revision) })
            }
        } finally { runtime.endProjectWrite(write) }
    }

    override suspend fun recent(ref: LocalProjectRef): List<TranslationTaskSummary> = page(ref).tasks
    override suspend fun page(ref: LocalProjectRef, before: Long?): TranslationTaskPage = withContext(Dispatchers.IO) {
        require(before == null || before > 0)
        transaction {
            val rows = db.translationTasks().page(ref.instanceId.value, ref.generation.value, ref.projectId.value, before)
            val summaries = rows.take(20).map { row ->
                val run = RunIdentity(ref.projectId, null, RunId(row.runId), CommandId(row.commandId))
                TranslationTaskSummary(ref, run, ModelConfigurationRef(row.configurationId, row.configurationRevision), row.targetLanguage,
                    TranslationTaskProgress(row.revision, row.committed, row.requests),
                    db.translationTasks().count(row.id), db.translationTasks().remaining(row.id), row.stage,
                    runtime.snapshot().activeRun?.identity == run)
            }
            TranslationTaskPage(summaries, rows.takeIf { it.size > 20 }?.get(19)?.position)
        }
    }
    /** Remaining active outputs and not-yet-dispatched siblings must be disjoint and fully explained. */
    private suspend fun retryPartition(row: NativeTranslationTaskRow, spec: TranslationTaskSpec,
        saved: TranslationRetryCheckpoint): Pair<List<Long>, List<Long>>? {
        val active = saved.active ?: return null
        if (saved.terminalFailure != null || saved.attempt?.value != row.pendingAttempt ||
            saved.initialAdditional != spec.options.additionalRequestsPerBatch) return null
        val roots = active.ids + saved.pending.flatMap { it.ids }
        val targets = db.translationTasks().selected(row.id, roots.map { it.value }).associateBy { it.itemId }
        if (targets.size != roots.size || roots.any { id ->
                saved.targets.singleOrNull { it.id == id }?.fingerprint?.sha256 != targets.getValue(id.value).fingerprint
            }) return null
        val primary = roots.associateWith { id -> projects.item(spec.ref, id)?.item ?: throw TranslationTaskNeedsReview() }
        val ids = numbers(row.responseRemaining)
        if (ids.size !in 1..100) return null
        val unfinished = db.translationTasks().selected(row.id, ids).associateBy { it.itemId }
        if (unfinished.size != ids.size || unfinished.values.any { it.done }) return null
        val activeIds = ArrayList<Long>(); val pendingIds = ArrayList<Long>()
        for (id in ids) {
            val item = projects.item(spec.ref, ItemId(id))?.item ?: throw TranslationTaskNeedsReview()
            if (item.fingerprint.sha256 != unfinished.getValue(id).fingerprint) return null
            val root = if (ItemId(id) in roots) ItemId(id) else {
                if (!spec.options.deduplicateSameFile || !item.metadata.isDefault) return null
                primary.entries.singleOrNull { (_, value) -> value.metadata.isDefault &&
                    value.assetId == item.assetId && value.source == item.source }?.key ?: return null
            }
            if (root in active.ids) activeIds.add(id) else pendingIds.add(id)
        }
        if (saved.pending.flatMap { it.ids }.any { it.value !in pendingIds }) return null
        return activeIds to pendingIds
    }
    /** Validate the frozen primary-to-derived mapping without persisting/loading request bodies. */
    private suspend fun matchesRetryTargets(row: NativeTranslationTaskRow, spec: TranslationTaskSpec,
        saved: TranslationRetryCheckpoint, primaryIds: List<Long>, actualIds: List<Long>): Boolean {
        if (!spec.options.deduplicateSameFile) return primaryIds.toSet() == actualIds.toSet()
        if (actualIds.size !in 1..100 || actualIds.distinct().size != actualIds.size ||
            !actualIds.containsAll(primaryIds)) return false
        val selected = db.translationTasks().selected(row.id, actualIds).associateBy { it.itemId }
        if (selected.size != actualIds.size || selected.values.any { it.done }) return false
        val items = actualIds.associateWith { id ->
            projects.item(spec.ref, ItemId(id))?.item ?: throw TranslationTaskNeedsReview()
        }
        if (items.any { (id, item) -> item.fingerprint.sha256 != selected.getValue(id).fingerprint }) return false
        val primaries = primaryIds.map { id ->
            val item = items.getValue(id)
            if (saved.targets.singleOrNull { it.id.value == id }?.fingerprint != item.fingerprint) return false
            item
        }
        return items.all { (id, item) -> id in primaryIds || item.metadata.isDefault &&
            primaries.count { it.metadata.isDefault && it.assetId == item.assetId && it.source == item.source } == 1 }
    }
    private suspend fun reconcileCommittedRetry(row: NativeTranslationTaskRow, spec: TranslationTaskSpec) {
        if (row.stage !in setOf("READY", "RESPONSE_RECORDED")) return
        val saved = retryCheckpoints.readReserved(spec.ref, spec.run, spec.configuration) ?: return
        val active = saved.active ?: return
        if (saved.terminalFailure != null) return
        require(saved.initialAdditional == spec.options.additionalRequestsPerBatch)
        val receipt = db.translationRequests().find(requireNotNull(saved.attempt).value) ?: return
        if (!NativeTranslationAuditStore.matches(receipt, spec.ref, spec.run) || receipt.state != "RECEIVED" ||
            receipt.failure != null || receipt.configurationId != spec.configuration.id ||
            receipt.configurationRevision != spec.configuration.revision) return
        val inputs = db.translationResponses().inputs(receipt.attemptId)
        if (inputs.map { it.itemId }.toSet() != active.ids.map { it.value }.toSet() || inputs.size != active.ids.size) return
        require(inputs.all { input -> saved.targets.single { it.id.value == input.itemId }.fingerprint.sha256 == input.fingerprint })
        val completed = db.translationTasks().selected(row.id, active.ids.map { it.value })
        if (completed.size != active.ids.size || completed.any { !it.done }) return
        if (spec.options.deduplicateSameFile) {
            val primaries = active.ids.map { projects.item(spec.ref, it)?.item ?: throw TranslationTaskNeedsReview() }
            for (id in numbers(row.responseRemaining)) {
                val item = projects.item(spec.ref, ItemId(id))?.item ?: throw TranslationTaskNeedsReview()
                if (item.metadata.isDefault && primaries.any { it.metadata.isDefault && it.assetId == item.assetId && it.source == item.source }) return
            }
        }
        retryCheckpoints.writeReserved(spec.ref, spec.run, spec.configuration,
            TranslationRetryCheckpoint(saved.targets, saved.initialAdditional, saved.remainingAdditional,
                saved.pending, notBeforeMillis = saved.notBeforeMillis))
    }
    private suspend fun reconcileRecordedFailure(row: NativeTranslationTaskRow, spec: TranslationTaskSpec) {
        val recovery = retryRecovery ?: return
        if (row.stage != "REQUESTING") return
        val saved = retryCheckpoints.readReserved(spec.ref, spec.run, spec.configuration) ?: return
        val active = saved.active ?: return
        if (saved.terminalFailure != null || saved.attempt?.value != row.pendingAttempt) return
        require(saved.initialAdditional == spec.options.additionalRequestsPerBatch)
        val receipt = db.translationRequests().find(requireNotNull(row.pendingAttempt)) ?: return
        if (receipt.state != "RECEIVED" || receipt.failure == null ||
            !NativeTranslationAuditStore.matches(receipt, spec.ref, spec.run) || receipt.configurationId != spec.configuration.id ||
            receipt.configurationRevision != spec.configuration.revision) return
        val inputs = db.translationResponses().inputs(receipt.attemptId)
        require(inputs.map { it.itemId }.toSet() == active.ids.map { it.value }.toSet() && inputs.size == active.ids.size)
        require(inputs.all { input -> saved.targets.single { it.id.value == input.itemId }.fingerprint.sha256 == input.fingerprint })
        val pending = active.ids + saved.pending.flatMap { it.ids }
        require(matchesRetryTargets(row, spec, saved, pending.map { it.value }, numbers(row.responseRemaining)))
        val targets = db.translationTasks().selected(row.id, pending.map { it.value })
        require(targets.size == pending.size && targets.none { it.done } && targets.all { target ->
            saved.targets.single { it.id.value == target.itemId }.fingerprint.sha256 == target.fingerprint
        })
        val recovered = recovery.afterFailure(saved, ModelFailureKind.valueOf(receipt.failure),
            System.currentTimeMillis().coerceAtLeast(0))
        retryCheckpoints.writeReserved(spec.ref, spec.run, spec.configuration, recovered)
    }
    private suspend fun reconcileUnsentRetry(row: NativeTranslationTaskRow, spec: TranslationTaskSpec) {
        if (row.stage !in setOf("READY", "REQUESTING")) return
        val saved = retryCheckpoints.readReserved(spec.ref, spec.run, spec.configuration) ?: return
        if (saved.terminalFailure != null) return
        require(saved.initialAdditional == spec.options.additionalRequestsPerBatch)
        val active = saved.active
        val attempt = saved.attempt?.value ?: row.pendingAttempt ?: return
        if (db.translationRequests().find(attempt) != null) return
        if (row.stage == "REQUESTING" && row.pendingAttempt != attempt) return
        val queue = active?.let { listOf(it) + saved.pending } ?: saved.pending
        val ids = queue.flatMap { it.ids }.map { it.value }
        if (ids.isEmpty()) return
        if (row.stage == "REQUESTING" && (!matchesRetryTargets(row, spec, saved, ids, numbers(row.responseRemaining)) ||
                !matchesRetryTargets(row, spec, saved, queue.first().ids.map { it.value }, numbers(row.pendingItems)))) return
        val targets = db.translationTasks().selected(row.id, ids)
        require(targets.size == ids.size && targets.none { it.done } && targets.all { target ->
            saved.targets.singleOrNull { it.id.value == target.itemId }?.fingerprint?.sha256 == target.fingerprint
        })
        if (active != null) retryCheckpoints.writeReserved(spec.ref, spec.run, spec.configuration,
            TranslationRetryCheckpoint(saved.targets, saved.initialAdditional, saved.remainingAdditional,
                queue, notBeforeMillis = saved.notBeforeMillis))
        if (row.stage == "REQUESTING") transaction {
            check(db.translationTasks().update(row.copy(stage = "READY", pendingAttempt = null,
                pendingCommands = "[]", pendingItems = "[]", responseRemaining = "[]")) == 1)
        }
    }
    private suspend fun reconcilePendingRetry(row: NativeTranslationTaskRow, spec: TranslationTaskSpec) {
        if (row.stage !in setOf("REQUESTING", "RESPONSE_RECORDED")) return
        val saved = retryCheckpoints.readReserved(spec.ref, spec.run, spec.configuration) ?: return
        if (saved.active != null || saved.terminalFailure != null || saved.pending.isEmpty()) return
        require(saved.initialAdditional == spec.options.additionalRequestsPerBatch)
        val pending = saved.pending.flatMap { it.ids }.map { it.value }
        if (!matchesRetryTargets(row, spec, saved, pending, numbers(row.responseRemaining))) return
        val targets = db.translationTasks().selected(row.id, pending)
        require(targets.size == pending.size && targets.none { it.done } && targets.all { target ->
            saved.targets.singleOrNull { it.id.value == target.itemId }?.fingerprint?.sha256 == target.fingerprint
        })
        val attempt = row.pendingAttempt ?: return
        val receipt = db.translationRequests().find(attempt) ?: return
        if (!NativeTranslationAuditStore.matches(receipt, spec.ref, spec.run) || receipt.state != "RECEIVED" ||
            receipt.configurationId != spec.configuration.id || receipt.configurationRevision != spec.configuration.revision) return
        if (receipt.failure == null) {
            val inputs = db.translationResponses().inputs(attempt)
            if (inputs.isEmpty()) return
            val completed = db.translationTasks().selected(row.id, inputs.map { it.itemId })
            if (completed.size != inputs.size || completed.any { !it.done }) return
        }
        transaction { check(db.translationTasks().update(row.copy(stage = "READY", pendingAttempt = null,
            pendingCommands = "[]", pendingItems = "[]", responseRemaining = "[]")) == 1) }
    }
    private suspend fun settle(row: NativeTranslationTaskRow, ref: LocalProjectRef) {
        if (row.stage == "REQUESTING") {
            // No audit means HTTP was never entered. A response without committed outputs cannot be re-requested.
            if (recoverable(row)) return
            if (row.pendingAttempt == null || db.translationRequests().find(row.pendingAttempt) != null) throw TranslationTaskNeedsReview()
            val run = decode(row.specification).run
            if (db.translationResponses().previousRequests(ref.instanceId.value, ref.generation.value, ref.projectId.value,
                    run.runId.value, run.commandId.value, numbers(row.responseRemaining)) > 0 ||
                db.translationResponses().legacyRequests(ref.instanceId.value, ref.generation.value, ref.projectId.value,
                    run.runId.value, run.commandId.value) > 0) throw TranslationTaskNeedsReview()
            transaction { check(db.translationTasks().update(row.copy(stage = "READY", pendingAttempt = null,
                pendingCommands = "[]", pendingItems = "[]", responseRemaining = "[]")) == 1) }
        } else if (row.stage in setOf("COMMITTING", "MARKING_FAILURE")) {
            val commands = Json.parseToJsonElement(row.pendingCommands).jsonArray.map { CommandId(it.jsonPrimitive.content) }
            val items = numbers(row.pendingItems)
            require(commands.size == items.size && commands.size in 1..20)
            val receipts = commands.map { projects.receipt(ref, it) }
            if (receipts.all { it == null } && recoverable(row)) {
                transaction { check(db.translationTasks().update(row.copy(stage = "RESPONSE_RECORDED",
                    pendingCommands = "[]", pendingItems = "[]")) == 1) }
                return
            }
            if (receipts.any { it == null || it.status != ManualEditStatus.COMMITTED }) throw TranslationTaskNeedsReview()
            receipts.filterNotNull().forEachIndexed { index, receipt -> require(receipt.itemId.value == items[index] && receipt.ref == ref) }
            val revision = receipts.filterNotNull().maxOf { it.revision }
            val remaining = numbers(row.responseRemaining).filterNot { it in items }
            // A committed edit changes the item's translation/status, so the frozen dispatch
            // fingerprint no longer matches. Refresh exactly the settled targets; failure evidence
            // and later adjudication compare against the durable post-commit item state.
            val settledItems = items.associateWith { id ->
                projects.item(ref, ItemId(id))?.item ?: throw TranslationTaskNeedsReview()
            }
            val markingFailure = row.stage == "MARKING_FAILURE"
            transaction {
                // An ERROR marker is a settlement, not a successful commit: the target stays
                // unfinished so it still needs an explicit skip/retry decision.
                if (!markingFailure) check(db.translationTasks().done(row.id, items) == items.size)
                settledItems.forEach { (id, item) ->
                    db.translationTasks().setFingerprint(row.id, id, item.fingerprint.sha256)
                }
                db.translationResponses().removeStaged(row.id, items)
                check(db.translationTasks().update(row.copy(stage = if (remaining.isEmpty()) "READY" else "RESPONSE_RECORDED", revision = revision,
                    committed = if (row.stage == "COMMITTING") Math.addExact(row.committed, commands.size) else row.committed,
                    pendingAttempt = row.pendingAttempt.takeIf { remaining.isNotEmpty() }, pendingCommands = "[]", pendingItems = "[]", responseRemaining = ids(remaining))) == 1)
            }
        }
    }
    private fun numbers(value: String) = Json.parseToJsonElement(value).jsonArray.map { it.jsonPrimitive.long }
    private fun ids(values: List<Long>) = JsonArray(values.map(::JsonPrimitive)).toString()
    private fun encode(spec: TranslationTaskSpec) = buildJsonObject {
        put("version", 1); put("project", spec.ref.projectId.value); put("instance", spec.ref.instanceId.value)
        put("generation", spec.ref.generation.value); put("run", spec.run.runId.value); put("command", spec.run.commandId.value)
        put("configuration", spec.configuration.id); put("configuration_revision", spec.configuration.revision)
        put("revision", spec.revision); put("after", spec.afterId); put("page_size", spec.pageSize); put("whole", spec.wholeProject)
        put("target_language", spec.options.targetLanguage); put("source_language", spec.options.sourceLanguage)
        put("instructions", spec.options.instructions); put("batch_size", spec.options.batchSize)
        put("batch_characters", spec.options.batchCharacters); put("mode", spec.options.startMode.name)
        put("preceding", spec.options.precedingLines); put("additional", spec.options.additionalRequestsPerBatch)
        put("deduplicate", spec.options.deduplicateSameFile)
    }.toString()
    private fun decode(text: String): TranslationTaskSpec {
        val json = JsonObjectValue.parse(text, JsonInputLimits(131072, 8)).json
        fun text(key: String) = json.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
        fun number(key: String) = json.getValue(key).jsonPrimitive.let { require(!it.isString); ToolArguments.long(ToolScalar.NumberToken(it.content), key, 0, Long.MAX_VALUE) }
        fun int(key: String) = number(key).also { require(it <= Int.MAX_VALUE) }.toInt()
        fun boolean(key: String) = json.getValue(key).jsonPrimitive.let { require(!it.isString); it.boolean }
        require(number("version") == 1L && json.keys == setOf("version", "project", "instance", "generation", "run", "command",
            "configuration", "configuration_revision", "revision", "after", "page_size", "whole", "target_language", "source_language",
            "instructions", "batch_size", "batch_characters", "mode", "preceding", "additional", "deduplicate"))
        val ref = LocalProjectRef(ProjectId(text("project")), ProjectInstanceId(text("instance")), RecoveryGeneration(number("generation")))
        return TranslationTaskSpec(ref, RunIdentity(ref.projectId, null, RunId(text("run")), CommandId(text("command"))),
            ModelConfigurationRef(text("configuration"), number("configuration_revision")), number("revision"), number("after"), int("page_size"),
            boolean("whole"), RemoteTranslationOptions(text("target_language"), text("source_language"), text("instructions"),
                int("batch_size"), int("batch_characters"), TranslationStartMode.valueOf(text("mode")), int("preceding"), int("additional"), boolean("deduplicate")))
    }
    suspend fun close(): Unit = withContext(Dispatchers.IO) { db.close() }
}

/** Fixed-task stages that may accept an explicit member decision. */
private val FAILURE_DECISION_STAGES = setOf("FAILED", "NEEDS_REVIEW", "READY")

/** Item failures that no automatic retry may replay; an explicit skip/keep/terminate remains. */
private val TERMINAL_ITEM_FAILURES = setOf(
    ModelFailureKind.PARSING,
    ModelFailureKind.TRUNCATED,
    ModelFailureKind.CONTENT,
    ModelFailureKind.CONTENT_FILTERED,
    ModelFailureKind.CONTEXT_OVERFLOW,
    ModelFailureKind.EMPTY_RESPONSE,
)

private const val RETRY_MEMBER_ACTION = "RETRY_MEMBERS"
private const val SKIP_MEMBER_ACTION = "SKIP_MEMBERS"
private const val COMPLETE_ACTION = "COMPLETE"
private const val COMPLETED_WITH_ERRORS_ACTION = "COMPLETED_WITH_ERRORS"
