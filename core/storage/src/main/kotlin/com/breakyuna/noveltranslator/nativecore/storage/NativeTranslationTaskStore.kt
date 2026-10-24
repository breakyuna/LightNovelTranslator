package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
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
    @Query("SELECT COUNT(*) FROM native_translation_targets WHERE task=:task") fun count(task: String): Int
    @Query("SELECT COUNT(*) FROM native_translation_targets WHERE task=:task AND done=0") fun remaining(task: String): Int
}

/** Fixed membership and payload, with project receipts as the authority after a crash. */
class NativeTranslationTaskStore(context: Context, private val projects: TextProjectPort,
    private val runtime: RuntimePort,
    private val retryRecovery: TranslationRetryRecoveryPolicy? = null) : TranslationTaskPort {
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
                require(decode(old.specification).copy(revision = spec.revision) == spec)
                require(old.revision == spec.revision && (old.stage == "READY" || recoverable(old)))
                require(projects.open(spec.ref).revision == old.revision)
                return@withContext TranslationTaskProgress(old.revision, old.committed, old.requests, decode(old.specification).revision)
            }
            require(!resuming) { "Missing original task" }
            val enabled = (projects as? ProjectAssetPort)?.assets(spec.ref)?.filter { it.enabled }?.mapTo(HashSet()) { it.id }
            val targets = ArrayList<NativeTranslationTargetRow>()
            var cursor = spec.afterId
            do {
                currentCoroutineContext().ensureActive()
                val page = projects.items(spec.ref, spec.revision, cursor, if (spec.wholeProject) 100 else spec.pageSize)
                page.items.filter { spec.options.startMode.accepts(it.status) && it.source.isNotBlank() &&
                    (enabled == null || it.assetId in enabled) }.forEach {
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
            val row = row(lease); require(row.stage == "READY" && db.translationTasks().remaining(row.id) == 0)
            check(db.translationTasks().update(row.copy(stage = "COMPLETE")) == 1)
        }
    }
    override suspend fun resume(ref: LocalProjectRef, run: RunIdentity): TranslationTaskSpec = withContext(Dispatchers.IO) {
        val write = runtime.beginProjectWrite()
        try {
            val row = requireNotNull(db.translationTasks().find(key(run)))
            val spec = decode(row.specification); require(spec.ref == ref && spec.run == run)
            require(row.stage != "COMPLETE")
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
            transaction {
                check(db.translationTasks().done(row.id, items) == items.size)
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
