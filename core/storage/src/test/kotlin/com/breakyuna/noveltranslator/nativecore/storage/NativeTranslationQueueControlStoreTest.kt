package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeTranslationQueueControlStoreTest {
    private val ref = LocalProjectRef(ProjectId("project-a"), ProjectInstanceId("instance-a"), RecoveryGeneration(2))
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val store = NativeTranslationQueueControlStore(context)
        requireNotNull(store.fileFor(identity("run-a")).parentFile).deleteRecursively()
    }

    @Test
    fun pauseAndCancelIntentRoundTripAcrossReopenAndSupersedeAtomically() = runBlocking {
        val store = NativeTranslationQueueControlStore(context)
        val task = identity("run-intent")
        val pause = NativeTranslationQueueControlIntent(
            task, CommandId("pause-command"), NativeTranslationQueueControlKind.PENDING_PAUSE)
        store.save(pause)
        assertEquals(listOf(pause), store.load())
        assertEquals(listOf(pause), NativeTranslationQueueControlStore(context).load())

        val cancel = NativeTranslationQueueControlIntent(
            task, CommandId("cancel-command"), NativeTranslationQueueControlKind.PENDING_CANCEL)
        store.save(cancel)
        assertEquals(listOf(cancel), store.load())

        // A late pause completion must not delete a newer cancel intent.
        store.clear(pause)
        assertEquals(listOf(cancel), store.load())
        assertTrue(store.fileFor(task).exists())

        store.clear(cancel)
        assertTrue(store.load().isEmpty())
        assertFalse(store.fileFor(task).exists())
    }

    @Test
    fun corruptForeignOversizedAndUnfinishedIntentFilesFailClosed() = runBlocking {
        val store = NativeTranslationQueueControlStore(context)
        val task = identity("run-corrupt")
        val intent = NativeTranslationQueueControlIntent(
            task, CommandId("pause-corrupt"), NativeTranslationQueueControlKind.PENDING_PAUSE)
        store.save(intent)
        val file = store.fileFor(task)
        val valid = file.readText()
        file.writeText(valid.dropLast(12))
        expectCorrupt { NativeTranslationQueueControlStore(context).load() }

        file.writeText(valid)
        val foreign = File(file.parentFile, "0".repeat(64) + ".json")
        file.copyTo(foreign)
        expectCorrupt { NativeTranslationQueueControlStore(context).load() }
        foreign.delete()

        file.writeBytes(ByteArray(NativeTranslationQueueControlStore.MAX_BYTES + 1) { ' '.code.toByte() })
        expectCorrupt { NativeTranslationQueueControlStore(context).load() }

        file.delete()
        File(file.path + ".new").writeText("{unfinished")
        expectCorrupt { NativeTranslationQueueControlStore(context).load() }
    }

    @Test
    fun storedIntentCarriesOnlyIdentityCommandAndKind() = runBlocking {
        val store = NativeTranslationQueueControlStore(context)
        val task = identity("run-redacted")
        store.save(NativeTranslationQueueControlIntent(
            task, CommandId("cancel-redacted"), NativeTranslationQueueControlKind.PENDING_CANCEL))
        val text = store.fileFor(task).readText()
        val forbidden = setOf(
            "source", "translation", "text", "body", "content",
            "prompt", "api_key", "authorization", "bearer", "header",
        )
        assertTrue(forbidden.none { text.contains(it, ignoreCase = true) })
        assertFalse(text.contains("sk-live-secret"))
        assertTrue(text.contains("CANCEL"))
    }

    private suspend fun expectCorrupt(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected a corrupt translation queue control store")
        } catch (_: TranslationQueueCorrupt) {
        }
    }

    private fun identity(run: String): TranslationTaskQueueIdentity =
        TranslationTaskQueueIdentity(
            ref = ref,
            run = RunIdentity(ref.projectId, null, RunId(run), CommandId("command-$run")),
            configuration = ModelConfigurationRef("model", 3),
            revision = 7,
        )
}
