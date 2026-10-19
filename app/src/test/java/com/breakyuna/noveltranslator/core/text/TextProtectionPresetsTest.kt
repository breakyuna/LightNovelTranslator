package com.breakyuna.noveltranslator.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextProtectionPresetsTest {

    @Test
    fun base_preset_covers_html_tags_whitespace_and_resource_tokens() {
        val entries = TextProtectionPresets.base()

        assertEquals(3, entries.size)
        assertTrue(entries.any { it.pattern == "<br>" })
        assertTrue(entries.any { it.pattern == """\s""" })
        // 资源 token 规则与投影端 token 格式同源，否则模型回显的占位符不会被保护。
        assertTrue(entries.any { it.pattern == """\[REF:[0-9]+\]""" })
    }

    @Test
    fun base_rule_protects_every_projected_token() {
        val rule = TextProtectionPresets.baseRule()
        val projection = ResourceReferenceSlots.project(
            "\u770B\u56FE https://example.com/a.png \u7ED3\u675F",
            0
        )

        assertEquals(
            listOf(" ", "[REF:0]", " "),
            rule.collect(projection.text)
        )
    }

    @Test
    fun resource_token_pattern_escapes_the_prefix_and_only_matches_ascii_digits() {
        assertEquals("""\[REF:[0-9]+\]""", TextProtectionPresets.resourceTokenPattern("[REF:"))

        val rule = TextProtectionRule(
            listOf(TextPreserveEntry(pattern = TextProtectionPresets.resourceTokenPattern("[REF:")))
        )
        assertEquals(listOf("[REF:7]"), rule.collect("\u524d[REF:7]\u540e"))
        // 全角数字不是投影端生成的编号，不应被当成资源 token。
        assertEquals(emptyList<String>(), rule.collect("[REF:\uFF17]"))
    }

    @Test
    fun a_custom_token_prefix_stays_in_sync_with_the_preset() {
        // token 后缀也参与预设生成，因此这里沿用默认的 `]`。
        val rule = TextProtectionPresets.baseRule(tokenPrefix = "<@")
        val projection = ResourceReferenceSlots.project(
            "\u770B\u56FE image.png \u7ED3\u675F",
            0,
            tokenPrefix = "<@"
        )

        assertEquals("\u770B\u56FE <@0] \u7ED3\u675F", projection.text)
        assertTrue(rule.collect(projection.text).contains("<@0]"))
    }

    @Test
    fun base_rule_with_extra_entries_keeps_the_base_layer_first() {
        val rule = TextProtectionPresets.baseRule(
            extraEntries = listOf(TextPreserveEntry(pattern = """\d+"""))
        )

        // 基础层仍在最前，同起点裁决时基础层优先。
        assertEquals(listOf("<br>", " ", "123"), rule.collect("<br> 123"))
    }
}
