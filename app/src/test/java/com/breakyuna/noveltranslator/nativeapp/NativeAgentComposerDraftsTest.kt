package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeAgentComposerDraftsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))

    @Test fun draftsSurviveRecreationAndStayIsolatedByChatAndGeneration() { runBlocking<Unit> {
        val root = temporary.newFolder()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = NativeAgentComposerDrafts(root, firstScope) { _, _ -> fail("Draft write failed") }
        try {
            first.save(ref, ChatId("a"), "first")
            first.save(ref, ChatId("b"), "second")
            first.save(ref, ChatId("a"), "你好🙂 latest")
            assertEquals("你好🙂 latest", first.load(ref, ChatId("a")))
            assertEquals("second", first.load(ref, ChatId("b")))
        } finally { firstScope.cancel() }
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val second = NativeAgentComposerDrafts(root, secondScope) { _, _ -> fail("Draft write failed") }
            assertEquals("你好🙂 latest", second.load(ref, ChatId("a")))
            val restored = ref.copy(generation = RecoveryGeneration(2))
            assertEquals("", second.load(restored, ChatId("a")))
            second.save(ref, ChatId("a"), "")
            assertEquals("", second.load(ref, ChatId("a")))
            assertEquals("second", second.load(ref, ChatId("b")))
        } finally { secondScope.cancel() }
    } }
}
