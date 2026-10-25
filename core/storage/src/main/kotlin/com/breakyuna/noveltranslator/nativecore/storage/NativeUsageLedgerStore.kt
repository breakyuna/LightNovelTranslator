package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.room.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.concurrent.Callable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** No source, response, endpoint or credential body is stored here. */
@Entity(tableName = "native_usage_receipts", indices = [
    Index(value = ["instanceId", "generation", "projectId", "chatId", "runId", "commandId"]),
    Index(value = ["configurationId", "configurationRevision"]),
])
internal data class NativeUsageReceiptRow(
    @PrimaryKey val id: String, val channel: String, val attemptId: String,
    val instanceId: String?, val generation: Long?, val projectId: String,
    val chatId: String?, val runId: String, val commandId: String,
    val configurationId: String, val configurationRevision: Long,
    val model: String?, val state: String, val usage: String?, val stop: String?, val failure: String?,
) {
    override fun toString() = "NativeUsageReceiptRow(metadata)"
}

internal data class NativeUsageReceiptListRow(val position: Long, @Embedded val receipt: NativeUsageReceiptRow)

@Dao
internal interface NativeUsageReceiptDao {
    @Insert fun insert(row: NativeUsageReceiptRow)
    @Query("SELECT * FROM native_usage_receipts WHERE id=:id") fun find(id: String): NativeUsageReceiptRow?
    @Query("UPDATE native_usage_receipts SET state='DISPATCHED' WHERE id=:id AND state='PREPARED'")
    fun dispatch(id: String): Int
    @Query("UPDATE native_usage_receipts SET state='RECEIVED',usage=:usage,stop=:stop,failure=:failure WHERE id=:id AND state='DISPATCHED'")
    fun received(id: String, usage: String, stop: String?, failure: String?): Int
    @Query("SELECT * FROM native_usage_receipts WHERE id>:after AND (:instance IS NULL OR (instanceId=:instance AND generation=:generation AND projectId=:project)) AND (:chat IS NULL OR chatId=:chat) AND (:run IS NULL OR (runId=:run AND commandId=:command AND ((:runChat IS NULL AND chatId IS NULL) OR chatId=:runChat))) AND (:configuration IS NULL OR (configurationId=:configuration AND configurationRevision=:revision)) ORDER BY id LIMIT 100")
    fun page(after: String, instance: String?, generation: Long?, project: String?, chat: String?,
        run: String?, command: String?, runChat: String?, configuration: String?, revision: Long?): List<NativeUsageReceiptRow>
    @Query("SELECT rowid AS position,* FROM native_usage_receipts WHERE (:before IS NULL OR rowid<:before) AND (:instance IS NULL OR (instanceId=:instance AND generation=:generation AND projectId=:project)) AND (:chat IS NULL OR chatId=:chat) AND (:run IS NULL OR (runId=:run AND commandId=:command AND ((:runChat IS NULL AND chatId IS NULL) OR chatId=:runChat))) AND (:configuration IS NULL OR (configurationId=:configuration AND configurationRevision=:revision)) ORDER BY rowid DESC LIMIT 51")
    fun details(before: Long?, instance: String?, generation: Long?, project: String?, chat: String?,
        run: String?, command: String?, runChat: String?, configuration: String?, revision: Long?): List<NativeUsageReceiptListRow>

}

/** Read model over the canonical request transactions. No second paid-request or receipt-writing path. */
class NativeUsageLedgerStore(context: Context) : UsageLedgerReadPort {
    private val db = NativeAppDatabase.open(context)
    override suspend fun summary(query: UsageLedgerQuery): DurableUsageSummary = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        db.runInTransaction(Callable {
            var prepared = 0L; var dispatched = 0L; var unresolved = 0L
            var totals = RequestUsageTotals(); var after = ""
            while (true) {
                context.ensureActive()
                val rows = db.usage().page(after, query.ref?.instanceId?.value, query.ref?.generation?.value,
                    query.ref?.projectId?.value, query.chat?.value, query.run?.runId?.value,
                    query.run?.commandId?.value, query.run?.chatId?.value,
                    query.configuration?.id, query.configuration?.revision)
                if (rows.isEmpty()) break
                for (row in rows) when (row.state) {
                    "PREPARED" -> prepared = Math.addExact(prepared, 1)
                    "DISPATCHED" -> {
                        dispatched = Math.addExact(dispatched, 1); unresolved = Math.addExact(unresolved, 1)
                    }
                    "RECEIVED" -> {
                        dispatched = Math.addExact(dispatched, 1)
                        totals = totals.add(TranslationUsageCodec.decode(requireNotNull(row.usage)))
                    }
                    else -> error("Unknown usage state")
                }
                after = rows.last().id
            }
            DurableUsageSummary(prepared, dispatched, unresolved, totals)
        })
    }
    override suspend fun entries(query: UsageLedgerQuery, cursor: UsageLedgerCursor?): UsageLedgerPage = withContext(Dispatchers.IO) {
        require(cursor == null || cursor.query == query)
        val context = currentCoroutineContext()
        db.runInTransaction(Callable {
            context.ensureActive()
            val rows = db.usage().details(cursor?.before, query.ref?.instanceId?.value, query.ref?.generation?.value,
                query.ref?.projectId?.value, query.chat?.value, query.run?.runId?.value, query.run?.commandId?.value,
                query.run?.chatId?.value, query.configuration?.id, query.configuration?.revision)
            val entries = rows.take(50).map { value ->
                context.ensureActive()
                entry(value.receipt)
            }
            UsageLedgerPage(entries, rows.takeIf { it.size > 50 }?.get(49)?.let { UsageLedgerCursor(query, it.position) })
        })
    }
    private fun entry(row: NativeUsageReceiptRow): UsageLedgerEntry {
        require((row.instanceId == null) == (row.generation == null))
        val ref = row.instanceId?.let { LocalProjectRef(ProjectId(row.projectId), ProjectInstanceId(it),
            RecoveryGeneration(requireNotNull(row.generation))) }
        return UsageLedgerEntry(row.id, UsageReceiptChannel.valueOf(row.channel.uppercase(java.util.Locale.ROOT)),
            RequestAttemptId(row.attemptId), ref,
            RunIdentity(ProjectId(row.projectId), row.chatId?.let(::ChatId), RunId(row.runId), CommandId(row.commandId)),
            ModelConfigurationRef(row.configurationId, row.configurationRevision), row.model, UsageReceiptState.valueOf(row.state),
            row.usage?.let(TranslationUsageCodec::decode), row.stop?.let(ModelStopReason::valueOf), row.failure?.let(ModelFailureKind::valueOf))
    }
    override suspend fun export(query: UsageLedgerQuery, destination: () -> java.io.OutputStream): Long = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        context.ensureActive()
        destination().use { stream -> stream.bufferedWriter(Charsets.UTF_8).use { output ->
            db.runInTransaction(Callable {
                context.ensureActive()
                output.write(buildJsonObject {
                    put("type", "header"); put("format", "lnt-usage-ledger"); put("version", 1)
                    put("scope", buildJsonObject {
                        put("project", query.ref?.projectId?.value?.let(::JsonPrimitive) ?: JsonNull)
                        put("instance", query.ref?.instanceId?.value?.let(::JsonPrimitive) ?: JsonNull)
                        put("generation", query.ref?.generation?.value?.let(::JsonPrimitive) ?: JsonNull)
                        put("chat", query.chat?.value?.let(::JsonPrimitive) ?: JsonNull)
                        put("run", query.run?.runId?.value?.let(::JsonPrimitive) ?: JsonNull)
                        put("command", query.run?.commandId?.value?.let(::JsonPrimitive) ?: JsonNull)
                        put("run_chat", query.run?.chatId?.value?.let(::JsonPrimitive) ?: JsonNull)
                        put("configuration", query.configuration?.id?.let(::JsonPrimitive) ?: JsonNull)
                        put("configuration_revision", query.configuration?.revision?.let(::JsonPrimitive) ?: JsonNull)
                    })
                }.toString()); output.newLine()
                var before: Long? = null; var count = 0L
                while (true) {
                    context.ensureActive()
                    val rows = db.usage().details(before, query.ref?.instanceId?.value, query.ref?.generation?.value,
                        query.ref?.projectId?.value, query.chat?.value, query.run?.runId?.value, query.run?.commandId?.value,
                        query.run?.chatId?.value, query.configuration?.id, query.configuration?.revision)
                    for (value in rows.take(50)) {
                        context.ensureActive()
                        output.write(usageLedgerEntryJson(entry(value.receipt)).toString()); output.newLine()
                        count = Math.addExact(count, 1)
                    }
                    if (rows.size <= 50) break
                    before = rows[49].position
                }
                context.ensureActive()
                output.write(buildJsonObject { put("type", "complete"); put("entries", count) }.toString())
                output.newLine(); output.flush()
                count
            })
        } }
    }
    suspend fun close(): Unit = withContext(Dispatchers.IO) { db.close() }

    internal companion object {
        private fun id(channel: String, attempt: String) = "$channel:$attempt"
        fun reserveAgent(db: NativeAppDatabase, request: AgentModelRequest) {
            val chat = request.run.chatId?.let { db.agent().chat(it.value) }
            val instance = chat?.let { db.instances().find(it.instanceId) }
            if (instance != null) require(instance.projectId == request.run.projectId.value && instance.generation == requireNotNull(chat).generation)
            db.usage().insert(NativeUsageReceiptRow(id("agent", request.attemptId.value), "agent", request.attemptId.value,
                instance?.instanceId, instance?.generation, request.run.projectId.value, request.run.chatId?.value,
                request.run.runId.value, request.run.commandId.value, request.configuration.id, request.configuration.revision,
                model(db, request.configuration), "PREPARED", null, null, null))
        }
        fun reserveTranslation(db: NativeAppDatabase, request: TranslationModelRequest) {
            db.usage().insert(NativeUsageReceiptRow(id("translation", request.attempt.value), "translation", request.attempt.value,
                request.ref.instanceId.value, request.ref.generation.value, request.ref.projectId.value, null,
                request.run.runId.value, request.run.commandId.value, request.configuration.id, request.configuration.revision,
                model(db, request.configuration), "DISPATCHED", null, null, null))
        }
        private fun model(db: NativeAppDatabase, configuration: ModelConfigurationRef): String? =
            db.models().find(configuration.revision)?.takeIf { it.profileId == configuration.id }?.model

        fun dispatchedAgent(db: NativeAppDatabase, attempt: String) {
            // A pre-v6 prepared request may have no ledger metadata. Do not invent scope or config.
            val key = id("agent", attempt)
            if (db.usage().find(key) != null) check(db.usage().dispatch(key) == 1)
        }
        fun received(db: NativeAppDatabase, channel: String, attempt: String, usage: RequestUsage,
            stop: ModelStopReason? = null, failure: ModelFailureKind? = null) {
            val key = id(channel, attempt)
            val row = db.usage().find(key) ?: return // Older canonical receipts have no reconstructible ledger metadata.
            val encoded = TranslationUsageCodec.encode(usage)
            if (row.state == "RECEIVED") require(row.usage == encoded && row.stop == stop?.name && row.failure == failure?.name)
            else check(db.usage().received(key, encoded, stop?.name, failure?.name) == 1)
        }
    }
}
