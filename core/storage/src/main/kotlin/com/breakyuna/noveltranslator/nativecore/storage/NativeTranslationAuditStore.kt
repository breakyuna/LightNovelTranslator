package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.room.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.concurrent.Callable
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Entity(tableName = "native_translation_requests", indices = [Index(value = ["instanceId", "generation", "runId", "commandId"])])
internal data class NativeTranslationRequestRow(
    @PrimaryKey val attemptId: String, val instanceId: String, val generation: Long, val projectId: String,
    val runId: String, val commandId: String, val configurationId: String, val configurationRevision: Long,
    val digest: String, val state: String, val usage: String?, val failure: String?, val createdAt: Long,
) {
    override fun toString(): String = "NativeTranslationRequestRow(redacted)"
}

@Dao
internal interface NativeTranslationRequestDao {
    @Insert fun insert(row: NativeTranslationRequestRow)
    @Query("SELECT * FROM native_translation_requests WHERE attemptId=:attempt") fun find(attempt: String): NativeTranslationRequestRow?
    @Query("UPDATE native_translation_requests SET state='RECEIVED',usage=:usage,failure=:failure WHERE attemptId=:attempt AND digest=:digest AND state='DISPATCHED'")
    fun received(attempt: String, digest: String, usage: String, failure: String?): Int
    @Query("SELECT * FROM native_translation_requests WHERE instanceId=:instance AND generation=:generation AND projectId=:project AND runId=:run AND commandId=:command AND state='RECEIVED' AND attemptId>:after ORDER BY attemptId LIMIT 100")
    fun page(instance: String, generation: Long, project: String, run: String, command: String, after: String): List<NativeTranslationRequestRow>
}

/** Local audit facts. This table has no executable request bodies and cannot authorize replay. */
class NativeTranslationAuditStore(context: Context, private val runtime: RuntimePort,
    private val projects: TextProjectPort) : TranslationRequestAuditPort {
    private val db = NativeAppDatabase.open(context)

    override suspend fun dispatched(lease: RunLease, request: TranslationModelRequest, digest: String): TranslationAuditHandle =
        withContext(Dispatchers.IO) {
            require(lease.identity == request.run && request.options != null && digest.matches(Regex("[0-9a-f]{64}")))
            val active = runtime.snapshot().activeRun
            require(active != null && active.identity == request.run && active.owner == RuntimeOwner.TRANSLATION)
            val operation = runtime.beginOperation(lease, OperationKind.HOST_REQUEST)
            try {
                projects.open(request.ref)
                currentCoroutineContext().ensureActive()
                // INSERT aborts for every duplicate attempt, including same-body duplicates.
                db.translationRequests().insert(NativeTranslationRequestRow(request.attempt.value,
                    request.ref.instanceId.value, request.ref.generation.value, request.ref.projectId.value,
                    request.run.runId.value, request.run.commandId.value, request.configuration.id, request.configuration.revision,
                    digest, "DISPATCHED", null, null, System.currentTimeMillis().coerceAtLeast(0)))
                TranslationAuditHandle(request.attempt, request.ref, request.run, digest)
            } finally { runtime.endOperation(operation) }
        }

    override suspend fun received(handle: TranslationAuditHandle, usage: RequestUsage, failure: ModelFailureKind?): Unit =
        withContext(Dispatchers.IO) {
            val encoded = TranslationUsageCodec.encode(usage)
            db.runInTransaction(Callable {
                val row = requireNotNull(db.translationRequests().find(handle.attempt.value))
                require(matches(row, handle.ref, handle.run) && row.digest == handle.digest)
                if (row.state == "RECEIVED") require(row.usage == encoded && row.failure == failure?.name)
                else check(db.translationRequests().received(row.attemptId, handle.digest, encoded, failure?.name) == 1)
            })
            Unit
        }

    suspend fun close(): Unit = withContext(Dispatchers.IO) { db.close() }

    internal companion object {
        fun matches(row: NativeTranslationRequestRow, ref: LocalProjectRef, run: RunIdentity): Boolean =
            run.chatId == null && row.instanceId == ref.instanceId.value && row.generation == ref.generation.value &&
                row.projectId == ref.projectId.value && row.projectId == run.projectId.value &&
                row.runId == run.runId.value && row.commandId == run.commandId.value

        fun totals(db: NativeAppDatabase, ref: LocalProjectRef, run: RunIdentity, checkActive: () -> Unit): RequestUsageTotals =
            db.runInTransaction(Callable {
                var result = RequestUsageTotals()
                var after = ""
                while (true) {
                    checkActive()
                    val rows = db.translationRequests().page(ref.instanceId.value, ref.generation.value,
                        ref.projectId.value, run.runId.value, run.commandId.value, after)
                    if (rows.isEmpty()) break
                    for (row in rows) {
                        require(matches(row, ref, run))
                        result = result.add(TranslationUsageCodec.decode(requireNotNull(row.usage)))
                    }
                    after = rows.last().attemptId
                }
                result
            })
    }
}

internal object TranslationUsageCodec {
    fun encode(usage: RequestUsage): String = buildJsonObject {
        listOf("input" to usage.input, "cacheRead" to usage.cacheRead, "cacheWrite" to usage.cacheWrite,
            "thinking" to usage.thinking, "output" to usage.output).forEach { (key, metric) ->
            put(key, buildJsonObject {
                put("tokens", metric.tokens?.let(::JsonPrimitive) ?: JsonNull); put("source", metric.source.name)
            })
        }
    }.toString()

    fun decode(value: String): RequestUsage {
        val root = JsonObjectValue.parse(value, JsonInputLimits(2048, 8)).json
        fun metric(key: String): TokenMeasurement {
            val item = root.getValue(key).jsonObject
            val token = item.getValue("tokens")
            val count = if (token == JsonNull) null else {
                val number = token.jsonPrimitive
                require(!number.isString)
                ToolArguments.long(ToolScalar.NumberToken(number.content), "usage", 0, Long.MAX_VALUE)
            }
            return TokenMeasurement(count, UsageSource.valueOf(item.getValue("source").jsonPrimitive.content))
        }
        return RequestUsage(metric("input"), metric("cacheRead"), metric("cacheWrite"), metric("thinking"), metric("output"))
    }
}
