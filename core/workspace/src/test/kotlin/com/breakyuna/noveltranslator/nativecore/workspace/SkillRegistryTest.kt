package com.breakyuna.noveltranslator.nativecore.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRegistryTest {
    @Test
    fun registrationSortsByOrderThenNameAndFiltersByLanguageAndTag() {
        val registry = registered(
            skill("skill-c", "Gamma", order = 30, languages = setOf("ja")),
            skill("skill-a", "Alpha", order = 20, languages = setOf("en")),
            skill("skill-b", "Beta", order = 10, tags = setOf("analysis")),
        )

        assertEquals(listOf("Beta", "Alpha", "Gamma"), registry.skills.map { it.name })
        assertEquals(listOf("Beta", "Gamma"), registry.list(language = "JA").map { it.name })
        assertEquals(listOf("Beta", "Alpha"), registry.list(language = "en").map { it.name })
        assertEquals(listOf("Beta"), registry.list(tag = "ANALYSIS").map { it.name })
        assertEquals(listOf("Beta", "Alpha", "Gamma"), registry.list(enabledOnly = true).map { it.name })
    }

    @Test
    fun enableDisableAndReorderProduceNewRegistries() {
        val registry = registered(
            skill("skill-a", "Alpha", order = 1),
            skill("skill-b", "Beta", order = 2),
        )

        val disabled = (registry.setEnabled(WorkspaceSkillId("skill-a"), false) as WorkspaceSkillMutation.Updated)
            .registry
        assertFalse(disabled.find(WorkspaceSkillId("skill-a"))!!.enabled)
        assertTrue(registry.find(WorkspaceSkillId("skill-a"))!!.enabled)
        assertEquals(listOf("Beta"), disabled.list(enabledOnly = true).map { it.name })

        val reordered = (disabled.setOrder(WorkspaceSkillId("skill-a"), 0) as WorkspaceSkillMutation.Updated)
            .registry
        assertEquals(listOf("Alpha", "Beta"), reordered.skills.map { it.name })

        val notFound = registry.setOrder(WorkspaceSkillId("missing"), 1)
        assertTrue(notFound is WorkspaceSkillMutation.NotFound)
    }

    @Test
    fun duplicateIdAndDuplicateNameAreConflicts() {
        val registry = registered(skill("skill-a", "Alpha", order = 1))

        val duplicateId = registry.register(skill("skill-a", "Other", order = 2))
        assertTrue(duplicateId is WorkspaceSkillRegistration.Conflicted)
        assertEquals(
            listOf(WorkspaceSkillConflictReason.DUPLICATE_ID),
            (duplicateId as WorkspaceSkillRegistration.Conflicted).conflicts.map { it.reason },
        )

        val duplicateName = registry.register(skill("skill-b", "alpha", order = 2))
        assertTrue(duplicateName is WorkspaceSkillRegistration.Conflicted)
        assertEquals(
            listOf(WorkspaceSkillConflictReason.DUPLICATE_NAME),
            (duplicateName as WorkspaceSkillRegistration.Conflicted).conflicts.map { it.reason },
        )

        val duplicateInBatch = SkillRegistry.of(
            listOf(skill("skill-a", "Alpha", order = 1), skill("skill-b", "Alpha", order = 2)),
        )
        assertTrue(duplicateInBatch is WorkspaceSkillRegistration.Conflicted)
    }

    @Test
    fun skillMetadataIsBoundedAndContentIsReadOnlyOnExplicitReference() {
        assertThrows(IllegalArgumentException::class.java) {
            skill("skill-a", "Alpha", order = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            skill("skill-a", "Alpha", order = 1, description = "x".repeat(1_025))
        }

        val summary = skill(
            "skill-a",
            "Alpha",
            order = 1,
            contentPath = "skills/alpha/SKILL.md",
            contentSha256 = sha256Of("body"),
        )
        val registry = registered(summary)
        val reader = CountingSkillReader("body")
        assertEquals(0, reader.reads)
        assertEquals(summary.id, registry.skills.single().id)
        assertNull(reader.cachedBody)
        assertEquals("body", runWorkspaceSuspend { reader.read(summary.id) })
        assertEquals(1, reader.reads)
        assertEquals("body", runWorkspaceSuspend { reader.read(summary.id) })
        assertEquals(2, reader.reads)
    }

    private fun registered(vararg skills: WorkspaceSkillSummary): SkillRegistry {
        val registration = SkillRegistry.of(skills.toList())
        assertTrue(registration is WorkspaceSkillRegistration.Registered)
        return (registration as WorkspaceSkillRegistration.Registered).registry
    }

    private fun skill(
        id: String,
        name: String,
        order: Int,
        enabled: Boolean = true,
        source: WorkspaceSkillSource = WorkspaceSkillSource.BUILT_IN,
        languages: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        contentPath: String? = null,
        contentSha256: String? = null,
        description: String = "description",
    ): WorkspaceSkillSummary = WorkspaceSkillSummary(
        id = WorkspaceSkillId(id),
        name = name,
        description = description,
        enabled = enabled,
        order = order,
        source = source,
        languages = languages,
        tags = tags,
        contentPath = contentPath?.let { WorkspacePath(it) },
        contentSha256 = contentSha256,
    )

    private class CountingSkillReader(private val body: String) : WorkspaceSkillDocumentPort {
        var reads: Int = 0
            private set
        var cachedBody: String? = null
            private set

        override suspend fun read(id: WorkspaceSkillId, maxChars: Int): String {
            reads += 1
            cachedBody = body
            return body
        }
    }
}
