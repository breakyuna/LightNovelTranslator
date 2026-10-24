package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ProjectPromptTemplateServiceTest {
    @Test fun renderingPreservesRulesAndEnabledSettingAndDoesNotRescanValues() {
        val original = ProjectTextPolicy(listOf(ProjectTextRule("term", TextRuleKind.TERM, "Alice", "艾莉丝")), "old", false)
        val template = PromptTemplate("template", "Template", body = "Translate {{language}}: {{style}}")
        val result = ProjectPromptTemplateService.render(original, template, mapOf("language" to "ja", "style" to "{{literal}}"))
        assertEquals("Translate ja: {{literal}}", result.prompt)
        assertFalse(result.promptEnabled)
        assertEquals(original.rules, result.rules)
        assertEquals("old", original.prompt)
    }
    @Test fun renderedPolicyMustAlsoFitTheCombinedRuleAndPromptEnvelope() {
        val rules = (1..8).map { ProjectTextRule("rule-$it", TextRuleKind.TERM, "source-$it" + "x".repeat(3_900), "y".repeat(3_900)) }
        val original = ProjectTextPolicy(rules, "keep")
        ProjectTextPolicyCodec.encode(original)
        val template = PromptTemplate("template", "Template", body = "{{value}}".repeat(4))
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPromptTemplateService.render(original, template, mapOf("value" to "z".repeat(4_000)))
        }
        assertEquals("keep", original.prompt)
    }

    @Test fun projectPromptLimitRejectsExpandedTextInsteadOfTruncatingIt() {
        val original = ProjectTextPolicy(prompt = "keep", promptEnabled = true)
        val template = PromptTemplate("template", "Template", body = "{{value}}".repeat(5))
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPromptTemplateService.render(original, template, mapOf("value" to "x".repeat(4_000)))
        }
        assertEquals("keep", original.prompt)
        assertThrows(PromptTemplateException::class.java) { ProjectPromptTemplateService.render(original, template, emptyMap()) }
        assertThrows(CancellationException::class.java) {
            ProjectPromptTemplateService.render(original, template, emptyMap()) { throw CancellationException() }
        }
    }
}
