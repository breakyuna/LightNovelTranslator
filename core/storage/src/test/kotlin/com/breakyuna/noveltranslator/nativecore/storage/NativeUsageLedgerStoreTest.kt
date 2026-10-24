package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.engine.unknownRequestUsage
import java.io.File
import java.math.BigInteger
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeUsageLedgerStoreTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var agent: NativeAgentStore
    private lateinit var audit: NativeTranslationAuditStore
    private lateinit var ledger: NativeUsageLedgerStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator(); projects = NativeTextProjectStore(context, runtime)
        agent = NativeAgentStore(context, projects, runtime); audit = NativeTranslationAuditStore(context, runtime, projects)
        ledger = NativeUsageLedgerStore(context)
    }
    @After fun cleanup() = runBlocking { agent.close(); audit.close(); ledger.close(); projects.close(); runtime.close() }
    private suspend fun project() = projects.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"), "source".toByteArray())
    private fun request(ref: LocalProjectRef, chat: ChatId, attempt: String = "attempt") = AgentModelRequest(
        RunIdentity(ref.projectId, chat, RunId("run"), CommandId("command")), BranchId("main"), RequestAttemptId(attempt),
        ModelConfigurationRef("fixture", 1), listOf(AgentMessage.User(MessageId("message"), listOf(UserPart.Text("private body")))), emptyList())
    private fun usage(tokens: Long = 10) = unknownRequestUsage().copy(input = TokenMeasurement(tokens, UsageSource.ACTUAL),
        cacheRead = TokenMeasurement(4, UsageSource.ESTIMATED))
    private fun response(usage: RequestUsage = usage()) = AgentModelResponse(
        AgentMessage.Assistant(MessageId("response"), listOf(AssistantPart.Text("private response"))), usage, ModelStopReason.COMPLETE)

    @Test fun exportPreservesScopeUnknownUsageAndCompletionWithoutPrivateBodies() = runBlocking {
        val ref = project().ref; val chat = agent.currentChat(ref)
        val received = request(ref, chat, "export-received")
        agent.reserveRequest(received); agent.markDispatched(received.key()); agent.completeRequest(received.key(), response())
        agent.reserveRequest(request(ref, chat, "export-prepared"))
        val uncertain = request(ref, chat, "export-uncertain")
        agent.reserveRequest(uncertain); agent.markDispatched(uncertain.key())
        val output = java.io.ByteArrayOutputStream()
        assertEquals(3L, ledger.export(UsageLedgerQuery(ref, chat)) { output })
        val text = output.toString("UTF-8")
        val lines = text.lineSequence().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.toList()
        assertEquals(5, lines.size)
        assertEquals("lnt-usage-ledger", lines.first().getValue("format").jsonPrimitive.content)
        assertEquals(chat.value, lines.first().getValue("scope").jsonObject.getValue("chat").jsonPrimitive.content)
        assertEquals("complete", lines.last().getValue("type").jsonPrimitive.content)
        assertEquals(3L, lines.last().getValue("entries").jsonPrimitive.long)
        val requests = lines.drop(1).dropLast(1)
        assertEquals(setOf("PREPARED", "DISPATCHED", "RECEIVED"), requests.map { it.getValue("state").jsonPrimitive.content }.toSet())
        val usage = requests.single { it.getValue("state").jsonPrimitive.content == "RECEIVED" }.getValue("usage").jsonObject
        assertEquals(10L, usage.getValue("input").jsonObject.getValue("tokens").jsonPrimitive.long)
        assertEquals(JsonNull, usage.getValue("output").jsonObject.getValue("tokens"))
        assertTrue(requests.filter { it.getValue("state").jsonPrimitive.content != "RECEIVED" }.all { it["usage"] == JsonNull })
        assertFalse(text.contains("private body")); assertFalse(text.contains("private response")); assertFalse(text.contains("Authorization"))
        val empty = java.io.ByteArrayOutputStream()
        assertEquals(0L, ledger.export(UsageLedgerQuery(ref, ChatId("other"))) { empty })
        assertEquals(2, empty.toString("UTF-8").lineSequence().filter { it.isNotBlank() }.count())
    }

    @Test fun exportWriteFailureClosesDestinationAndDoesNotChangeReceiptFacts() = runBlocking {
        val ref = project().ref; val chat = agent.currentChat(ref)
        agent.reserveRequest(request(ref, chat, "failed-export"))
        var closed = false
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { throw java.io.IOException("Destination unavailable") }
            override fun close() { closed = true }
        }
        try { ledger.export(UsageLedgerQuery(ref)) { output }; fail("Reported failed export as complete") }
        catch (_: java.io.IOException) { }
        assertTrue(closed)
        assertEquals(1L, ledger.summary(UsageLedgerQuery(ref)).prepared)
        assertEquals(UsageReceiptState.PREPARED, ledger.entries(UsageLedgerQuery(ref)).entries.single().state)
    }

    @Test fun detailPagesPreserveReceiptFactsAndScopeAcrossNewInsertions() = runBlocking {
        val ref = project().ref
        val query = UsageLedgerQuery(ref)
        val db = NativeAppDatabase.open(context)
        fun row(index: Int, generation: Long = ref.generation.value) = NativeUsageReceiptRow("translation:detail-$index", "translation", "detail-$index",
            ref.instanceId.value, generation, ref.projectId.value, null, "run", "command", "fixture", 1, null,
            if (index == 105) "RECEIVED" else "DISPATCHED", if (index == 105) TranslationUsageCodec.encode(usage()) else null,
            null, if (index == 105) ModelFailureKind.PARSING.name else null)
        try {
            withContext(Dispatchers.IO) {
                db.runInTransaction { for (index in 1..105) db.usage().insert(row(index)) }
            }
            val first = ledger.entries(query)
            assertEquals(50, first.entries.size)
            val received = first.entries.first()
            assertEquals(RequestAttemptId("detail-105"), received.attempt)
            assertEquals(UsageReceiptState.RECEIVED, received.state)
            assertEquals(ModelFailureKind.PARSING, received.failure)
            assertEquals(10L, received.usage!!.input.tokens)
            assertEquals(UsageSource.ESTIMATED, received.usage!!.cacheRead.source)
            assertEquals(UsageSource.UNKNOWN, received.usage!!.output.source)
            withContext(Dispatchers.IO) { db.usage().insert(row(106)); db.usage().insert(row(107, 2)) }
            val second = ledger.entries(query, first.next)
            val third = ledger.entries(query, second.next)
            assertEquals(50, second.entries.size); assertEquals(5, third.entries.size); assertNull(third.next)
            val all = first.entries + second.entries + third.entries
            assertEquals(105, all.map { it.id }.distinct().size)
            assertFalse(all.any { it.attempt.value in setOf("detail-106", "detail-107") })
            assertTrue(all.drop(1).all { it.state == UsageReceiptState.DISPATCHED && it.usage == null })
            assertEquals(RequestAttemptId("detail-106"), ledger.entries(query).entries.first().attempt)
            assertEquals(1, ledger.entries(UsageLedgerQuery(ref.copy(generation = RecoveryGeneration(2)))).entries.size)
            assertTrue(ledger.entries(UsageLedgerQuery(ref, ChatId("other"))).entries.isEmpty())
            assertTrue(ledger.entries(UsageLedgerQuery(configuration = ModelConfigurationRef("different", 1))).entries.isEmpty())
            try { ledger.entries(UsageLedgerQuery(), first.next); fail("Reused cursor for another scope") }
            catch (_: IllegalArgumentException) { }
        } finally { db.close() }
    }

    @Test fun canonicalReceiptsAreAtomicIdempotentScopedAndSurviveReopen() = runBlocking {
        val ref = project().ref; val chat = agent.currentChat(ref); val request = request(ref, chat)
        agent.reserveRequest(request)
        assertEquals(1L, ledger.summary(UsageLedgerQuery(ref)).prepared)
        agent.markDispatched(request.key())
        val uncertain = ledger.summary(UsageLedgerQuery(ref, chat))
        assertEquals(1L, uncertain.dispatched); assertEquals(1L, uncertain.unresolved); assertEquals(0L, uncertain.totals.responses)
        agent.completeRequest(request.key(), response()); agent.completeRequest(request.key(), response())
        ledger.close(); ledger = NativeUsageLedgerStore(context)
        val saved = ledger.summary(UsageLedgerQuery(ref, chat, request.run))
        assertEquals(1L, saved.dispatched); assertEquals(0L, saved.unresolved); assertEquals(1L, saved.totals.responses)
        assertEquals(BigInteger.TEN, saved.totals.input.actual)
        assertEquals(BigInteger.valueOf(4), saved.totals.cacheRead.estimated)
        assertEquals(1L, saved.totals.output.unknownRequests)
        assertEquals(0L, ledger.summary(UsageLedgerQuery(ref.copy(generation = RecoveryGeneration(2)))).dispatched)
        assertEquals(0L, ledger.summary(UsageLedgerQuery(ref, ChatId("other"))).dispatched)
        assertEquals(0L, ledger.summary(UsageLedgerQuery(configuration = ModelConfigurationRef("other", 1))).dispatched)
        val copied = projects.copyProject(CommandId("copy"), ref)
        assertEquals(0L, ledger.summary(UsageLedgerQuery(copied.ref)).dispatched)
    }

    @Test fun agentAndTranslationAttemptsDoNotCollideAndParsingFailureRetainsUsage() = runBlocking {
        val ref = project().ref; val chat = agent.currentChat(ref); val request = request(ref, chat)
        agent.reserveRequest(request); agent.markDispatched(request.key()); agent.completeRequest(request.key(), response())
        runtime.selectChat(null)
        val lease = runtime.beginRunSelectingChat(
            RunIdentity(ref.projectId, null, RunId("translation"), CommandId("translation")), RuntimeOwner.TRANSLATION,
            ChatIdentity(ref.projectId, ChatId("translation-workspace")))
        try {
            val input = projects.item(ref, ItemId(1))!!.item
            val translation = TranslationModelRequest(ref, lease.identity, request.attemptId, ModelConfigurationRef("fixture", 1),
                listOf(TranslationInput(input.id, input.source, input.fingerprint)), RemoteTranslationOptions("en"))
            val handle = audit.dispatched(lease, translation, "a".repeat(64))
            audit.received(handle, usage(), ModelFailureKind.PARSING); audit.received(handle, usage(), ModelFailureKind.PARSING)
            assertEquals(2L, ledger.summary(UsageLedgerQuery(ref)).totals.responses)
            assertEquals(1L, ledger.summary(UsageLedgerQuery(ref, chat)).totals.responses)
            assertEquals(1L, ledger.summary(UsageLedgerQuery(ref, run = lease.identity)).totals.responses)
        } finally { runtime.finishRun(lease) }
    }

    @Test fun aggregationReadsMultiplePagesAndNeverOverflowsLongTokenTotals() = runBlocking {
        val ref = project().ref; val chat = agent.currentChat(ref)
        repeat(205) { index ->
            val request = request(ref, chat, "a-$index")
            agent.reserveRequest(request); agent.markDispatched(request.key())
            agent.completeRequest(request.key(), response(usage(Long.MAX_VALUE)))
        }
        val snapshot = ledger.summary(UsageLedgerQuery(ref))
        assertEquals(205L, snapshot.totals.responses)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(205)), snapshot.totals.input.actual)
    }

    @Test fun migrationFromV5PreservesCanonicalFactsWithoutInventingPastLedgerEntries() = runBlocking {
        val ref = project().ref; val chat = agent.currentChat(ref); val request = request(ref, chat)
        agent.reserveRequest(request); agent.markDispatched(request.key()); agent.completeRequest(request.key(), response())
        agent.close(); audit.close(); ledger.close(); projects.close()
        withContext(Dispatchers.IO) {
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("lnt_native_app.db").path,
                null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE native_translation_staged")
                db.execSQL("DROP TABLE native_translation_responses")
                db.execSQL("DROP TABLE native_translation_request_inputs")
                db.execSQL("DROP TABLE native_translation_targets")
                db.execSQL("DROP TABLE native_translation_tasks")
                db.execSQL("DROP TABLE native_usage_receipts"); db.execSQL("DELETE FROM room_master_table"); db.version = 5
            }
        }
        projects = NativeTextProjectStore(context, runtime); agent = NativeAgentStore(context, projects, runtime)
        audit = NativeTranslationAuditStore(context, runtime, projects); ledger = NativeUsageLedgerStore(context)
        assertEquals(ref, projects.registeredProjects().single().ref)
        assertTrue(agent.reserveRequest(request) is RequestReservation.Completed)
        assertEquals(0L, ledger.summary().dispatched)
        agent.completeRequest(request.key(), response()) // Old replay does not fabricate a new dispatch.
        assertEquals(0L, ledger.summary().dispatched)
        val next = request(ref, chat, "after-upgrade")
        agent.reserveRequest(next); agent.markDispatched(next.key()); agent.completeRequest(next.key(), response())
        assertEquals(1L, ledger.summary().dispatched)
    }

    @Test fun ledgerConflictRollsBackCanonicalResponseRatherThanSplittingTheReceipt() = runBlocking {
        val ref = project().ref; val chat = agent.currentChat(ref); val request = request(ref, chat)
        agent.reserveRequest(request); agent.markDispatched(request.key())
        withContext(Dispatchers.IO) {
            val db = NativeAppDatabase.open(context)
            try { check(db.usage().received("agent:attempt", TranslationUsageCodec.encode(usage(99)), null, null) == 1) }
            finally { db.close() }
        }
        try { agent.completeRequest(request.key(), response()); fail("Accepted mismatched usage") }
        catch (_: IllegalArgumentException) { }
        assertEquals(RequestReservation.Uncertain, agent.reserveRequest(request))
    }
}
