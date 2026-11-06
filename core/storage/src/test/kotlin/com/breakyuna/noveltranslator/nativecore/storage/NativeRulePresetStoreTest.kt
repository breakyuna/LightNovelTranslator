package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeRulePresetStoreTest {
    private lateinit var directory: File
    private lateinit var store: NativeRulePresetStore
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.filesDir, "native_rule_presets")
        directory.deleteRecursively()
        store = NativeRulePresetStore(context)
    }
    @After fun cleanup() { directory.deleteRecursively() }
    @Test fun libraryUsesHashedPathsAndRejectsStaleOverwritesAndDeletes() = runBlocking {
        val first = RulePreset("../../outside", "Original", rules = listOf(ProjectTextRule("term", TextRuleKind.TERM, "Alice", "艾莉丝")))
        store.save(first)
        assertEquals(listOf(RulePresetSummary(first.id, first.name, first.description, 1)), store.list())
        assertTrue(directory.listFiles()!!.all { it.name.matches(Regex("[0-9a-f]{64}\\.json")) })
        val oldFingerprint = NativeRulePresetStore.fingerprint(first)
        val changed = RulePreset(first.id, "Updated", prompt = "New prompt", promptEnabled = true)
        try { store.save(changed, "0".repeat(64)); fail("Accepted stale overwrite") }
        catch (_: IllegalArgumentException) { }
        assertEquals(first.name, store.load(first.id).name)
        store.save(changed, oldFingerprint)
        assertEquals(changed.prompt, store.load(first.id).prompt)
        try { store.remove(first.id, oldFingerprint); fail("Accepted stale delete") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, store.list().size)
        store.remove(first.id, NativeRulePresetStore.fingerprint(changed))
        assertTrue(store.list().isEmpty())
    }
}
