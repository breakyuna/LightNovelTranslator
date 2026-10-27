package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillDocumentPort
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillDraft
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillPolicy
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** App-level selection and prompt marking for the automatic skill context. */
class NativeSkillContextTest {
    @get:Rule val temporary = TemporaryFolder()

    private suspend fun skill(
        store: WorkspaceSkillStore,
        id: String,
        name: String,
        order: Int,
        body: String = "body-$id",
        languages: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        policy: WorkspaceSkillPolicy = WorkspaceSkillPolicy.DEFAULT,
    ) = store.create(WorkspaceSkillDraft(
        id = WorkspaceSkillId(id),
        name = name,
        content = body,
        order = order,
        languages = languages,
        tags = tags,
        policy = policy,
    ))

    @Test fun explicitReferenceOutranksOrderAndSelectionStaysInsideFourAnd8192() = runBlocking {
        val root = temporary.newFolder("context-budget")
        val store = WorkspaceSkillStore(root)
        try {
            for (index in 0 until 6) {
                skill(store, "skill-$index", "Skill $index", index, body = "x".repeat(3_000))
            }
            val diagnostics = mutableListOf<String>()
            val block = requireNotNull(nativeSkillContextBlock(
                catalog = store,
                documents = store,
                hints = NativeSkillContextHints(task = "Please use @skill-5 for this chapter"),
            ) { diagnostics += it })

            assertTrue(block.contains("UNTRUSTED SKILL REFERENCE DATA"))
            assertTrue(block.contains("BEGIN UNTRUSTED SKILL skill-5"))
            assertTrue(block.contains("BEGIN UNTRUSTED SKILL skill-0"))
            assertTrue(block.contains("BEGIN UNTRUSTED SKILL skill-1"))
            assertTrue(block.contains("BEGIN UNTRUSTED SKILL skill-2"))
            assertFalse(block.contains("BEGIN UNTRUSTED SKILL skill-3"))
            assertFalse(block.contains("BEGIN UNTRUSTED SKILL skill-4"))
            assertEquals(4, Regex("--- BEGIN UNTRUSTED SKILL ").findAll(block).count())
            assertTrue(diagnostics.any {
                it.contains("truncated=true") && it.contains("chars=8192")
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun languageAndTagFiltersExcludeMismatchedSkills() = runBlocking {
        val root = temporary.newFolder("context-filter")
        val store = WorkspaceSkillStore(root)
        try {
            skill(store, "ja-style", "Japanese Style", 0,
                languages = setOf("ja"), tags = setOf("style"))
            skill(store, "en-style", "English Style", 1,
                languages = setOf("en"), tags = setOf("style"))
            skill(store, "ja-other", "Japanese Other", 2,
                languages = setOf("ja"), tags = setOf("analysis"))
            val block = requireNotNull(nativeSkillContextBlock(
                catalog = store,
                documents = store,
                hints = NativeSkillContextHints(language = "JA", tag = "style"),
            ))
            assertTrue(block.contains("BEGIN UNTRUSTED SKILL ja-style"))
            assertFalse(block.contains("BEGIN UNTRUSTED SKILL en-style"))
            assertFalse(block.contains("BEGIN UNTRUSTED SKILL ja-other"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun readFailuresAreReportedAndTheirTextIsNeverInjected() = runBlocking {
        val root = temporary.newFolder("context-failure")
        val store = WorkspaceSkillStore(root)
        try {
            skill(store, "broken", "Broken", 0)
            for (index in 0 until 4) skill(store, "ok-$index", "Okay $index", index + 1)
            val documents = object : WorkspaceSkillDocumentPort {
                override suspend fun read(id: WorkspaceSkillId, maxChars: Int): String? {
                    if (id.value == "broken") throw IllegalStateException("unreadable")
                    return "body-${id.value}"
                }
            }
            val diagnostics = mutableListOf<String>()
            val block = requireNotNull(nativeSkillContextBlock(
                catalog = store,
                documents = documents,
                hints = NativeSkillContextHints(task = "use @broken"),
            ) { diagnostics += it })

            assertFalse(block.contains("body-broken"))
            assertEquals(4, Regex("--- BEGIN UNTRUSTED SKILL ").findAll(block).count())
            assertTrue(diagnostics.any {
                it.contains("broken:READ_FAILED:IllegalStateException")
            })
        } finally {
            root.deleteRecursively()
        }
    }
}
