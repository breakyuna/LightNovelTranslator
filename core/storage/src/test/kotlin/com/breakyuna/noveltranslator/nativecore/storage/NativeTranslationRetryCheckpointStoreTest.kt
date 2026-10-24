package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeTranslationRetryCheckpointStoreTest {
    private lateinit var context: Context
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private val configuration = ModelConfigurationRef("model", 1)
    private val run = RunIdentity(ref.projectId, null, RunId("run"), CommandId("command"))
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "native_translation_retries").deleteRecursively()
    }
    private fun item(id: Long): TranslationItemSnapshot {
        val key = ItemId(id)
        return TranslationItemSnapshot(key, 1, id.toInt(), "private source", TextItemStatus.NONE,
            TextProjectFingerprint.item(key, id.toInt(), "private source", "", TextItemStatus.NONE))
    }
    private fun begin(runtime: RuntimeCoordinator) = runtime.beginRunSelectingChat(run, RuntimeOwner.TRANSLATION,
        ChatIdentity(ref.projectId, ChatId("workspace")))
    @Test fun terminalFailureCannotBeClearedChangedOrReplacedByAnEmptySuccessfulQueue() = runBlocking {
        val runtime = RuntimeCoordinator(); val lease = begin(runtime)
        val store = NativeTranslationRetryCheckpointStore(context, runtime)
        val session = TranslationRetrySession.start(listOf(item(1)), 0)
        session.begin(RequestAttemptId("failed")); session.failed(ModelFailureKind.PARSING, 0)
        val failed = session.checkpoint
        store.write(lease, ref, configuration, failed)
        // Exact replay is idempotent; all rewrites that erase/change the failure are rejected.
        store.write(lease, ref, configuration, failed)
        val rewrites = listOf(
            TranslationRetryCheckpoint(failed.targets, 0, 0, emptyList()),
            TranslationRetryCheckpoint(failed.targets, 0, 0, emptyList(), failed.active, failed.attempt, ModelFailureKind.NETWORK),
            TranslationRetryCheckpoint(failed.targets, 0, 0, listOf(requireNotNull(failed.active))))
        for (rewrite in rewrites) {
            try { store.write(lease, ref, configuration, rewrite); fail("Erased terminal failure") }
            catch (_: IllegalArgumentException) { }
        }
        val saved = requireNotNull(store.read(lease, ref, configuration))
        assertEquals(ModelFailureKind.PARSING, saved.terminalFailure)
        assertEquals(RequestAttemptId("failed"), saved.attempt)
        runtime.finishRun(lease); runtime.close()
    }

    @Test fun reopensOriginalBudgetQueueAndActiveAttemptWithoutStoringBodies() = runBlocking {
        val runtime = RuntimeCoordinator(); var lease = begin(runtime)
        val store = NativeTranslationRetryCheckpointStore(context, runtime)
        assertNull(store.read(lease, ref, configuration))
        val session = TranslationRetrySession.start(listOf(item(1), item(2)), 3)
        session.begin(RequestAttemptId("first")); session.failed(ModelFailureKind.PARSING, 100)
        session.begin(RequestAttemptId("child"))
        store.write(lease, ref, configuration, session.checkpoint)
        runtime.finishRun(lease); lease = begin(runtime)
        val reopened = NativeTranslationRetryCheckpointStore(context, runtime)
        val state = requireNotNull(reopened.read(lease, ref, configuration))
        assertEquals(1, state.remainingAdditional)
        assertEquals(RequestAttemptId("child"), state.attempt)
        assertEquals(listOf(ItemId(2)), state.pending.single().ids)
        val file = File(context.filesDir, "native_translation_retries").listFiles()!!.single()
        assertTrue(file.name.matches(Regex("[a-f0-9]{64}\\.json")))
        assertFalse(file.readText().contains("private source"))
        assertNull(reopened.read(lease, ref.copy(generation = RecoveryGeneration(2)), configuration))
        runtime.finishRun(lease); runtime.close()
    }
    @Test fun refusesBudgetReplenishmentAndReplacingAnUnfinishedOriginalUnit() = runBlocking {
        val runtime = RuntimeCoordinator(); val lease = begin(runtime)
        val store = NativeTranslationRetryCheckpointStore(context, runtime)
        val session = TranslationRetrySession.start(listOf(item(1)), 2)
        session.begin(RequestAttemptId("first")); session.failed(ModelFailureKind.NETWORK, 100)
        store.write(lease, ref, configuration, session.checkpoint)
        try { store.write(lease, ref, configuration, TranslationRetrySession.start(listOf(item(1)), 2).checkpoint); fail() }
        catch (_: IllegalArgumentException) { }
        try { store.write(lease, ref, configuration, TranslationRetrySession.start(listOf(item(2)), 2).checkpoint); fail() }
        catch (_: IllegalArgumentException) { }
        session.begin(RequestAttemptId("retry")); session.succeeded()
        store.write(lease, ref, configuration, session.checkpoint)
        store.write(lease, ref, configuration, TranslationRetrySession.start(listOf(item(2)), 2).checkpoint)
        runtime.finishRun(lease); runtime.close()
    }
    @Test fun corruptedForeignOrUnleasedStateNeverFallsBackToAFreshBudget() = runBlocking {
        val runtime = RuntimeCoordinator(); val lease = begin(runtime)
        val store = NativeTranslationRetryCheckpointStore(context, runtime)
        store.write(lease, ref, configuration, TranslationRetrySession.start(listOf(item(1)), 1).checkpoint)
        try { store.read(lease, ref, ModelConfigurationRef("other", 1)); fail() } catch (_: IllegalArgumentException) { }
        val file = File(context.filesDir, "native_translation_retries").listFiles()!!.single()
        file.writeText(file.readText().replace("\"initial\":1", "\"initial\":1.5"))
        try { store.read(lease, ref, configuration); fail() } catch (_: IllegalArgumentException) { }
        file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        try { store.read(lease, ref, configuration); fail() } catch (_: java.nio.charset.CharacterCodingException) { }
        runtime.finishRun(lease)
        try { store.read(lease, ref, configuration); fail() } catch (_: IllegalArgumentException) { }
        runtime.close()
    }
}
