package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueIdentity
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState
import com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueControlIntentKind
import com.breakyuna.noveltranslator.nativecore.storage.NativeTranslationQueueControlIntent
import com.breakyuna.noveltranslator.nativecore.storage.NativeTranslationQueueControlKind
import com.breakyuna.noveltranslator.nativecore.storage.NativeTranslationQueueControlStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Durable control-intent mapping and wake policy; no real JobScheduler/paid request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeTranslationQueueWakeTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val identity = TranslationTaskQueueIdentity(
        ref = ref,
        run = RunIdentity(ref.projectId, ChatId("chat"), RunId("run"), CommandId("command")),
        configuration = ModelConfigurationRef("model", 1),
        revision = 7,
    )

    @Test fun controlIntentsRoundTripAndNewerCancelSurvivesPauseClear() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "native_translation_queue_control").deleteRecursively()
        val adapter = NativeTranslationQueueControlIntentAdapter(
            NativeTranslationQueueControlStore(context),
        )
        val pause = com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueControlIntent(
            identity, CommandId("pause-1"), TranslationQueueControlIntentKind.PENDING_PAUSE,
        )
        val cancel = com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueControlIntent(
            identity, CommandId("cancel-1"), TranslationQueueControlIntentKind.PENDING_CANCEL,
        )
        adapter.save(pause)
        assertEquals(listOf(pause), adapter.pending())
        adapter.save(cancel)
        adapter.clear(pause)
        assertEquals(listOf(cancel), adapter.pending())
        adapter.clear(cancel)
        assertTrue(adapter.pending().isEmpty())
    }

    @Test fun storedIntentKeepsOnlyRedactedIdentityCommandAndKind() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "native_translation_queue_control").deleteRecursively()
        val store = NativeTranslationQueueControlStore(context)
        store.save(NativeTranslationQueueControlIntent(
            identity, CommandId("pause-2"), NativeTranslationQueueControlKind.PENDING_PAUSE,
        ))
        val restored = store.load().single()
        assertEquals(identity, restored.identity)
        assertEquals(CommandId("pause-2"), restored.command)
        assertEquals(NativeTranslationQueueControlKind.PENDING_PAUSE, restored.kind)
        assertFalse(restored.toString().contains("source"))
    }

    @Test fun pauseAndReviewAreNeverClaimableButQueuedIs() {
        assertTrue(NativeTranslationQueueWakePolicy.hasQueuedStates(listOf(TranslationTaskQueueState.QUEUED)))
        assertFalse(NativeTranslationQueueWakePolicy.hasQueuedStates(listOf(
            TranslationTaskQueueState.PAUSED,
            TranslationTaskQueueState.NEEDS_REVIEW,
            TranslationTaskQueueState.FAILED,
            TranslationTaskQueueState.CANCELLED,
        )))
        assertTrue(NativeTranslationQueueWakePolicy.shouldReschedule(true, false))
        assertFalse(NativeTranslationQueueWakePolicy.shouldReschedule(true, true))
        assertFalse(NativeTranslationQueueWakePolicy.shouldReschedule(false, false))
    }

    @Test fun heldProcessLeaseIsSkippedUntilItsOwnerReleasesIt() {
        val file = File.createTempFile("native-translation-queue-", ".lock")
        try {
            val first = TranslationQueueProcessLease(file)
            val second = TranslationQueueProcessLease(file)
            val token = first.tryAcquire()
            assertTrue(token != null)
            assertNull(second.tryAcquire())
            first.release(requireNotNull(token))
            val next = second.tryAcquire()
            assertTrue(next != null)
            second.release(requireNotNull(next))
        } finally {
            file.delete()
        }
    }
}
