package com.breakyuna.noveltranslator.nativecore.workspace

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSkillSelectionTest {
    @Test fun explicitReferenceOutranksLanguageAndOrder() {
        val manual = skillOf("manual", "Manual", order = 9, languages = setOf("ja"))
        val language = skillOf("language", "Language", order = 8, languages = setOf("ja"))
        val generic = skillOf("generic", "Generic", order = 1)
        val excluded = skillOf("english", "English", order = 0, languages = setOf("en"))
        val documents = fakeDocuments(manual, language, generic, excluded)

        val result = runWorkspaceSuspend {
            WorkspaceSkillSelection.select(
                registry = registryOf(manual, language, generic, excluded),
                documents = documents,
                task = "translate this chapter",
                language = "JA",
                explicitSkillIds = setOf(manual.id),
                maxSkills = 3,
            )
        }

        assertEquals(listOf("manual", "language", "generic"), result.selectedIds.map { it.value })
        assertTrue(WorkspaceSkillSelectionReason.EXPLICIT_REFERENCE in result.selected[0].reasons)
        assertTrue(WorkspaceSkillSelectionReason.LANGUAGE_MATCH in result.selected[0].reasons)
        assertTrue(WorkspaceSkillSelectionReason.LANGUAGE_MATCH in result.selected[1].reasons)
        assertEquals(listOf(WorkspaceSkillSelectionReason.ORDER_FALLBACK), result.selected[2].reasons)
        assertEquals(3, result.candidateCount)
        assertEquals(0, result.autoSelectExcludedCount)
        assertFalse(result.truncated)
        assertEquals("body-manual", result.selected[0].text)
    }

    @Test fun atMentionIsAnExplicitReferenceWithoutACallerSet() {
        val glossary = skillOf("glossary", "Glossary", order = 50)
        val style = skillOf("style", "Style", order = 0)

        val result = runWorkspaceSuspend {
            WorkspaceSkillSelection.select(
                registry = registryOf(glossary, style),
                documents = fakeDocuments(glossary, style),
                task = "Use @glossary for this chapter",
                maxSkills = 2,
            )
        }

        assertEquals(listOf("glossary", "style"), result.selectedIds.map { it.value })
        assertTrue(WorkspaceSkillSelectionReason.EXPLICIT_REFERENCE in result.selected[0].reasons)
        assertTrue(WorkspaceSkillSelectionReason.NAME_MATCH in result.selected[0].reasons)
    }

    @Test fun languageTagAndDisabledFiltersRunBeforeScoring() {
        val jaStyle = skillOf(
            "ja-style", "Japanese Style", order = 10, languages = setOf("ja"), tags = setOf("style"),
        )
        val enStyle = skillOf(
            "en-style", "English Style", order = 0, languages = setOf("en"), tags = setOf("style"),
        )
        val jaOther = skillOf(
            "ja-other", "Japanese Other", order = 0, languages = setOf("ja"), tags = setOf("analysis"),
        )
        val generic = skillOf("generic", "Generic", order = 1, tags = setOf("style"))
        val disabled = skillOf(
            "disabled", "Disabled", order = 0, enabled = false, tags = setOf("style"),
        )

        val result = runWorkspaceSuspend {
            WorkspaceSkillSelection.select(
                registry = registryOf(jaStyle, enStyle, jaOther, generic, disabled),
                documents = fakeDocuments(jaStyle, enStyle, jaOther, generic, disabled),
                task = "",
                language = "ja",
                tag = "style",
                maxSkills = 4,
            )
        }

        assertEquals(listOf("ja-style", "generic"), result.selectedIds.map { it.value })
        assertEquals(2, result.candidateCount)
    }

    @Test fun nameAndDescriptionMatchesBeatDisplayOrder() {
        val glossary = skillOf(
            "glossary", "Glossary", order = 99, description = "terms and consistency",
        )
        val style = skillOf("style", "Style", order = 0, description = "tone and voice")

        val result = runWorkspaceSuspend {
            WorkspaceSkillSelection.select(
                registry = registryOf(glossary, style),
                documents = fakeDocuments(glossary, style),
                task = "improve glossary consistency for this chapter",
                maxSkills = 2,
            )
        }

        assertEquals(listOf("glossary", "style"), result.selectedIds.map { it.value })
        assertTrue(WorkspaceSkillSelectionReason.NAME_MATCH in result.selected[0].reasons)
        assertTrue(WorkspaceSkillSelectionReason.DESCRIPTION_MATCH in result.selected[0].reasons)
        assertEquals(listOf(WorkspaceSkillSelectionReason.ORDER_FALLBACK), result.selected[1].reasons)
    }

    @Test fun equalSignalsFallBackToStableDisplayOrder() {
        val second = skillOf("second", "Second", order = 2)
        val first = skillOf("first", "First", order = 1)

        val result = runWorkspaceSuspend {
            WorkspaceSkillSelection.select(
                registry = registryOf(second, first),
                documents = fakeDocuments(second, first),
                task = "nothing relevant",
                maxSkills = 2,
            )
        }

        assertEquals(listOf("first", "second"), result.selectedIds.map { it.value })
        assertEquals(listOf(WorkspaceSkillSelectionReason.ORDER_FALLBACK), result.selected[0].reasons)
    }

    @Test fun totalAndPerSkillBudgetsTruncateContentAndStopLowerRanks() {
        val a = skillOf("a", "Alpha", order = 0)
        val b = skillOf("b", "Beta", order = 1)
        val c = skillOf("c", "Gamma", order = 2)
        val documents = fakeDocuments(a, b, c, bodyLength = 100)

        val result = runWorkspaceSuspend {
            WorkspaceSkillSelection.select(
                registry = registryOf(a, b, c),
                documents = documents,
                task = "",
                maxSkills = 4,
                maxTotalChars = 150,
                maxSkillChars = 80,
            )
        }

        assertEquals(listOf("a", "b"), result.selectedIds.map { it.value })
        assertEquals(80, result.selected[0].textChars)
        assertEquals(70, result.selected[1].textChars)
        assertTrue(result.selected[0].truncated)
        assertTrue(result.selected[1].truncated)
        assertTrue(result.truncated)
        assertEquals(150, result.injectedChars)

        val perSkill = runWorkspaceSuspend {
            WorkspaceSkillSelection.select(
                registry = registryOf(a, b),
                documents = documents,
                task = "",
                maxSkills = 4,
                maxTotalChars = 1_000,
                maxSkillChars = 50,
            )
        }
        assertEquals(listOf(50, 50), perSkill.selected.map { it.textChars })
        assertTrue(perSkill.truncated)

        val capped = runWorkspaceSuspend {
            WorkspaceSkillSelection.select(
                registry = registryOf(a, b),
                documents = documents,
                task = "",
                maxSkills = 1,
                maxTotalChars = 1_000,
                maxSkillChars = 50,
            )
        }
        assertEquals(listOf("a"), capped.selectedIds.map { it.value })
        assertTrue(capped.truncated)
    }

    @Test fun readFailuresAreVisibleAndDoNotHideSuccessfulSkills() {
        val broken = skillOf("broken", "Broken", order = 0)
        val missing = skillOf("missing", "Missing", order = 1)
        val good = skillOf("good", "Good", order = 2)
        val documents = FakeDocuments(
            bodies = mapOf("good" to "usable text"),
            failures = setOf("broken"),
        )

        val result = runWorkspaceSuspend {
            WorkspaceSkillSelection.select(
                registry = registryOf(broken, missing, good),
                documents = documents,
                task = "",
                maxSkills = 4,
            )
        }

        assertEquals(listOf("good"), result.selectedIds.map { it.value })
        assertEquals(listOf("broken", "missing"), result.failures.map { it.id.value })
        assertEquals(
            WorkspaceSkillSelectionFailure.Reason.READ_FAILED,
            result.failures[0].reason,
        )
        assertEquals("IllegalStateException", result.failures[0].errorType)
        assertEquals(
            WorkspaceSkillSelectionFailure.Reason.NOT_FOUND,
            result.failures[1].reason,
        )
        assertFalse(result.truncated)
        assertFalse(result.toString().contains("secret"))
        assertFalse(result.failures[0].toString().contains("secret"))
    }

    @Test fun autoSelectOptOutIsBypassedOnlyByAnExplicitReference() {
        val optOut = skillOf(
            "opt-out", "Opt Out", order = 0,
            policy = WorkspaceSkillPolicy(allowAutoSelect = false),
        )
        val automatic = skillOf("automatic", "Automatic", order = 1)
        val disabled = skillOf("disabled", "Disabled", order = 0, enabled = false)
        val registry = registryOf(optOut, automatic, disabled)
        val documents = fakeDocuments(optOut, automatic, disabled)

        val automaticResult = runWorkspaceSuspend {
            WorkspaceSkillSelection.select(registry, documents, task = "", maxSkills = 4)
        }
        assertEquals(listOf("automatic"), automaticResult.selectedIds.map { it.value })
        assertEquals(2, automaticResult.candidateCount)
        assertEquals(1, automaticResult.autoSelectExcludedCount)
        assertFalse(automaticResult.truncated)

        val explicitResult = runWorkspaceSuspend {
            WorkspaceSkillSelection.select(
                registry = registry,
                documents = documents,
                task = "",
                explicitSkillIds = setOf(optOut.id),
                maxSkills = 4,
            )
        }
        assertEquals(listOf("opt-out", "automatic"), explicitResult.selectedIds.map { it.value })
        assertTrue(WorkspaceSkillSelectionReason.EXPLICIT_REFERENCE in explicitResult.selected[0].reasons)
    }

    @Test fun cancellationFromTheDocumentPortPropagates() {
        val skill = skillOf("cancelled", "Cancelled", order = 0)
        val documents = object : WorkspaceSkillDocumentPort {
            override suspend fun read(id: WorkspaceSkillId, maxChars: Int): String? {
                throw CancellationException("stop")
            }
        }
        assertThrows(CancellationException::class.java) {
            runWorkspaceSuspend {
                WorkspaceSkillSelection.select(registryOf(skill), documents, task = "")
            }
        }
    }

    private fun skillOf(
        id: String,
        name: String,
        order: Int,
        enabled: Boolean = true,
        languages: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        description: String = "",
        policy: WorkspaceSkillPolicy = WorkspaceSkillPolicy.DEFAULT,
    ): WorkspaceSkillSummary = WorkspaceSkillSummary(
        id = WorkspaceSkillId(id),
        name = name,
        description = description,
        enabled = enabled,
        order = order,
        source = WorkspaceSkillSource.BUILT_IN,
        languages = languages,
        tags = tags,
        policy = policy,
    )

    private fun registryOf(vararg skills: WorkspaceSkillSummary): SkillRegistry {
        val registration = SkillRegistry.of(skills.toList())
        assertTrue(registration is WorkspaceSkillRegistration.Registered)
        return (registration as WorkspaceSkillRegistration.Registered).registry
    }

    private fun fakeDocuments(
        vararg skills: WorkspaceSkillSummary,
        bodyLength: Int = 0,
    ): FakeDocuments = FakeDocuments(
        bodies = skills.associate { it.id.value to bodyFor(it.id.value, bodyLength) },
    )

    private fun bodyFor(id: String, length: Int): String {
        if (length == 0) return "body-$id"
        val prefix = "body-$id"
        return prefix + "x".repeat(maxOf(0, length - prefix.length))
    }

    private class FakeDocuments(
        private val bodies: Map<String, String>,
        private val failures: Set<String> = emptySet(),
    ) : WorkspaceSkillDocumentPort {
        override suspend fun read(id: WorkspaceSkillId, maxChars: Int): String? {
            if (id.value in failures) throw IllegalStateException("secret /private/path")
            val body = bodies[id.value] ?: return null
            if (body.length <= maxChars) return body
            var end = maxChars
            if (end > 0 && body[end - 1].isHighSurrogate()) end -= 1
            return body.substring(0, end)
        }
    }
}
