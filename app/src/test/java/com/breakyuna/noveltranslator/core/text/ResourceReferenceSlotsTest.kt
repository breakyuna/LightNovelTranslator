package com.breakyuna.noveltranslator.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceReferenceSlotsTest {

    @Test
    fun recognizes_base64_data_uri_uri_and_scheme_less_resource_path_by_priority() {
        val base64 = "data:image/png;base64,AAABBB=="
        val text = base64 + " " +
            "https://example.com/a.png?size=2#cover " +
            "bg\\scene.WEBP 资源/背景图.png cover.avif manual.DOCX archive.tar.gz"

        assertEquals(
            listOf(
                base64,
                "https://example.com/a.png?size=2#cover",
                "bg\\scene.WEBP",
                "资源/背景图.png",
                "cover.avif",
                "manual.DOCX",
                "archive.tar.gz"
            ),
            ResourceReferenceSlots.collect(text).map { it.value }
        )
    }

    @Test
    fun keeps_paired_brackets_inside_uri_and_strips_outer_punctuation() {
        val text = "请看（https://example.com/a_(1).png），然后继续。"

        assertEquals(
            listOf("https://example.com/a_(1).png"),
            ResourceReferenceSlots.collect(text).map { it.value }
        )
        assertEquals("请看（），然后继续。", ResourceReferenceSlots.remove(text))
    }

    @Test
    fun does_not_treat_bare_base64_bare_extension_or_plain_scheme_text_as_reference() {
        assertEquals(
            emptyList<ResourceReference>(),
            ResourceReferenceSlots.collect(
                "AAAAABBBBBCCCCCDDDD 支持 .json 文件 chapter:one demo.mp3backup demo.mp3后缀"
            )
        )
    }

    @Test
    fun projects_multiple_fields_with_flat_ordinals_and_restores_exactly() {
        val first = ResourceReferenceSlots.project("打开 https://example.com 和 image.png", 0)
        val second = ResourceReferenceSlots.project(
            "data:image/png;base64,AAAA",
            first.nextOrdinal
        )

        assertEquals("打开 [REF:0] 和 [REF:1]", first.text)
        assertEquals("[REF:2]", second.text)
        assertEquals(
            "打开 https://example.com 和 image.png\ndata:image/png;base64,AAAA",
            ResourceReferenceSlots.restore(
                "${first.text}\n${second.text}",
                first.mappings + second.mappings
            )
        )
    }

    @Test
    fun ordinal_skips_tokens_already_present_in_the_source() {
        val projection = ResourceReferenceSlots.project("literal [REF:0] image.png")

        assertEquals("literal [REF:0] [REF:1]", projection.text)
        assertEquals(
            "literal [REF:0] image.png",
            ResourceReferenceSlots.restore(projection.text, projection.mappings)
        )
    }

    @Test
    fun restore_does_not_match_a_short_token_against_a_longer_ordinal_prefix() {
        assertEquals(
            "[REF:10] one.png",
            ResourceReferenceSlots.restore(
                "[REF:10] [REF:1]",
                listOf(ResourceReferenceMapping("[REF:1]", "one.png"))
            )
        )
    }

    @Test
    fun token_shaped_text_inside_a_restored_value_is_not_replaced_again() {
        assertEquals(
            "literal-[REF:1].txt actual.png",
            ResourceReferenceSlots.restore(
                "[REF:0] [REF:1]",
                listOf(
                    ResourceReferenceMapping("[REF:0]", "literal-[REF:1].txt"),
                    ResourceReferenceMapping("[REF:1]", "actual.png")
                )
            )
        )
    }

    @Test
    fun local_transform_only_touches_text_between_temporary_tokens() {
        assertEquals(
            "BEFORE [REF:0] AFTER",
            ResourceReferenceSlots.transformProjected(
                "before [REF:0] after",
                listOf(ResourceReferenceMapping("[REF:0]", "https://example.com"))
            ) { it.uppercase() }
        )
    }

    @Test
    fun collect_projected_reports_only_allocated_token_positions() {
        val projection = ResourceReferenceSlots.project("看图 image.png 与 image.png")
        val projected = ResourceReferenceSlots.collectProjected(projection.text, projection.mappings)

        assertEquals(listOf("[REF:0]", "[REF:1]"), projected.map { it.value })
        // 坐标基于投影后的文本："看图 [REF:0] 与 [REF:1]"。
        assertEquals(listOf(3, 13), projected.map { it.start })
    }

    @Test
    fun split_around_keeps_resource_boundaries_and_includes_empty_edges() {
        val parts = ResourceReferenceSlots.splitAround("前 image.png 后")

        assertEquals(listOf("前 ", " 后"), parts)
    }

    @Test
    fun empty_text_and_empty_mappings_are_no_ops() {
        assertEquals(emptyList<ResourceReference>(), ResourceReferenceSlots.collect(""))
        assertEquals("任意文本", ResourceReferenceSlots.restore("任意文本", emptyList()))
        assertEquals("任意文本", ResourceReferenceSlots.transformProjected("任意文本", emptyList()) { it.uppercase() })
        assertEquals(emptyList<ResourceReference>(), ResourceReferenceSlots.collectProjected("任意文本", emptyList()))
    }

    @Test
    fun very_long_data_uri_is_kept_as_a_single_reference() {
        // 同时是性能守卫：data URI 的 Base64 载荷是一长串 scheme 字符集内的字符，
        // 若无 scheme 上限与路径游程 lookbehind，匹配会退化成 O(n^2)（20 万字符约 20 分钟）。
        val payload = "A".repeat(200_000)
        val text = "head data:image/png;base64,$payload tail"

        val references = ResourceReferenceSlots.collect(text)
        assertEquals(1, references.size)
        assertEquals("data:image/png;base64,$payload", references.single().value)

        val projection = ResourceReferenceSlots.project(text, 0)
        assertEquals("head [REF:0] tail", projection.text)
        assertEquals(
            text,
            ResourceReferenceSlots.restore(projection.text, projection.mappings)
        )
    }

    @Test
    fun a_long_run_of_path_characters_is_not_a_reference_and_stays_fast() {
        // 长游程既不是引用，也不能让扫描退化（同一性能守卫的另一面）。
        val text = "head " + "A".repeat(200_000) + " tail"

        assertEquals(emptyList<ResourceReference>(), ResourceReferenceSlots.collect(text))
        assertEquals(text, ResourceReferenceSlots.remove(text))
    }

    @Test
    fun projection_continues_the_ordinal_from_a_non_zero_start() {
        val projection = ResourceReferenceSlots.project("a.png b.png", 7)

        assertEquals("[REF:7] [REF:8]", projection.text)
        assertEquals(9, projection.nextOrdinal)
    }

    @Test
    fun unclosed_token_like_text_is_not_mistaken_for_a_reference() {
        // "[REF:" 没有闭合括号，不构成资源 token，也不构成引用。
        assertEquals(emptyList<ResourceReference>(), ResourceReferenceSlots.collect("价格 [REF: 未闭合"))
    }
}
