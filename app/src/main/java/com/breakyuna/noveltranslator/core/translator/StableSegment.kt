package com.breakyuna.noveltranslator.core.translator

import com.breakyuna.noveltranslator.data.model.ChapterSegmentEntity
import java.security.MessageDigest

enum class SegmentType { TEXT, IMAGE }

data class StableSegment(
    val segmentId: String,
    val ordinal: Int,
    val sourceText: String,
    val type: SegmentType,
    val translatedText: String = ""
)

data class SegmentRelation(
    val sourceSegmentId: String,
    val translatedSegmentId: String,
    val relation: String // ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE
)

data class AlignedSegment(
    val segmentId: String,
    val sourceText: String,
    val translatedText: String,
    val sourceOrdinal: Int?,
    val translatedOrdinal: Int?,
    val relation: String
)

/**
 * Stable paragraph model used by quality checks and future editor persistence.
 * The ordinal is included in the hash so repeated paragraphs never collide.
 */
object StableSegmentParser {
    private val imagePattern = Regex("^\\[IMG:([^]]+)]$")

    fun parse(chapterId: Long, text: String): List<StableSegment> {
        if (text.isBlank()) return emptyList()
        val lines = text.replace("\r\n", "\n").split('\n')
        val result = mutableListOf<StableSegment>()
        var ordinal = 0
        lines.forEach { raw ->
            val value = raw.trim()
            if (value.isBlank()) return@forEach
            val type = if (imagePattern.matches(value)) SegmentType.IMAGE else SegmentType.TEXT
            result += StableSegment(
                segmentId = stableId(chapterId, ordinal, value),
                ordinal = ordinal,
                sourceText = value,
                type = type
            )
            ordinal++
        }
        return result
    }

    /** Keeps source/translation pairing explicit when either side has missing or extra segments. */
    fun align(chapterId: Long, sourceText: String, translatedText: String): List<AlignedSegment> {
        val source = parse(chapterId, sourceText)
        val translated = parse(chapterId, translatedText)
        return collapsePairs(chapterId, buildPairs(source, translated))
    }

    fun stableId(chapterId: Long, ordinal: Int, sourceText: String): String {
        val input = "$chapterId|$ordinal|${sourceText.trim().replace(Regex("\\s+"), " ")}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return "seg_" + digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    /** Converts the current source/translation alignment into durable Room rows. */
    fun toPersistedRelations(
        chapterId: Long,
        sourceText: String,
        translatedText: String,
        now: Long = System.currentTimeMillis()
    ): List<ChapterSegmentEntity> {
        val source = parse(chapterId, sourceText)
        val translated = parse(chapterId, translatedText)
        val sourceHash = sha256(sourceText)
        return buildPairs(source, translated).mapIndexed { ordinal, pair ->
            val sourceSegment = pair.first
            val translatedSegment = pair.second
            val sourceId = sourceSegment?.segmentId ?: "missing_source_${chapterId}_$ordinal"
            val translatedId = translatedSegment?.let {
                "translated_${chapterId}_${it.ordinal}_${shortHash(it.sourceText)}"
            } ?: "missing_translation_${chapterId}_$ordinal"
            val relation = relationshipFor(pair, source, translated)
            ChapterSegmentEntity(
                stableKey = "$chapterId|$sourceId|$translatedId",
                chapterId = chapterId,
                sourceSegmentId = sourceId,
                translatedSegmentId = translatedId,
                sourceOrdinal = sourceSegment?.ordinal,
                translatedOrdinal = translatedSegment?.ordinal,
                sourceText = sourceSegment?.sourceText.orEmpty(),
                translatedText = translatedSegment?.sourceText.orEmpty(),
                segmentType = sourceSegment?.type?.name ?: translatedSegment?.type?.name ?: SegmentType.TEXT.name,
                relation = relation,
                sourceHash = sourceHash,
                updatedAt = now
            )
        }
    }

    /** Collapses persisted pair rows into editor-safe groups without duplicating translated text. */
    fun alignPersisted(rows: List<ChapterSegmentEntity>): List<AlignedSegment> {
        if (rows.isEmpty()) return emptyList()
        val groups = mutableListOf<List<ChapterSegmentEntity>>()
        rows.sortedWith(compareBy({ it.sourceOrdinal ?: Int.MAX_VALUE }, { it.translatedOrdinal ?: Int.MAX_VALUE }))
            .forEach { row ->
                val key = when (row.relation) {
                    "MANY_TO_ONE" -> "T:${row.translatedSegmentId}"
                    "ONE_TO_MANY" -> "S:${row.sourceSegmentId}"
                    else -> "K:${row.stableKey}"
                }
                val previous = groups.lastOrNull()
                val previousKey = previous?.firstOrNull()?.let {
                    when (it.relation) {
                        "MANY_TO_ONE" -> "T:${it.translatedSegmentId}"
                        "ONE_TO_MANY" -> "S:${it.sourceSegmentId}"
                        else -> "K:${it.stableKey}"
                    }
                }
                if (previous != null && previousKey == key) {
                    groups[groups.lastIndex] = previous + row
                } else {
                    groups += listOf(row)
                }
            }
        return groups.map { group ->
            val first = group.first()
            AlignedSegment(
                segmentId = group.firstOrNull { it.sourceText.isNotBlank() }?.sourceSegmentId ?: first.sourceSegmentId,
                sourceText = group.map { it.sourceText }.filter { it.isNotBlank() }.distinct().joinToString("\n\n"),
                translatedText = group.map { it.translatedText }.filter { it.isNotBlank() }.distinct().joinToString("\n\n"),
                sourceOrdinal = group.mapNotNull { it.sourceOrdinal }.minOrNull(),
                translatedOrdinal = group.mapNotNull { it.translatedOrdinal }.minOrNull(),
                relation = first.relation
            )
        }
    }

    private fun buildPairs(
        source: List<StableSegment>,
        translated: List<StableSegment>
    ): List<Pair<StableSegment?, StableSegment?>> = when {
        source.isEmpty() -> translated.map { null to it }
        translated.isEmpty() -> source.map { it to null }
        source.size >= translated.size -> source.mapIndexed { index, item ->
            item to translated[balancedIndex(index, source.size, translated.size)]
        }
        else -> translated.mapIndexed { index, item ->
            source[balancedIndex(index, translated.size, source.size)] to item
        }
    }

    private fun relationshipFor(
        pair: Pair<StableSegment?, StableSegment?>,
        source: List<StableSegment>,
        translated: List<StableSegment>
    ): String {
        val sourceSegment = pair.first ?: return "ONE_TO_MANY"
        val translatedSegment = pair.second ?: return "MANY_TO_ONE"
        val sourceMatches = source.indices.count {
            balancedIndex(it, source.size, translated.size.coerceAtLeast(1)) == translatedSegment.ordinal
        }
        val translatedMatches = translated.indices.count {
            balancedIndex(it, translated.size, source.size.coerceAtLeast(1)) == sourceSegment.ordinal
        }
        return when {
            source.size > translated.size && sourceMatches > 1 -> "MANY_TO_ONE"
            translated.size > source.size && translatedMatches > 1 -> "ONE_TO_MANY"
            else -> "ONE_TO_ONE"
        }
    }

    private fun collapsePairs(
        chapterId: Long,
        pairs: List<Pair<StableSegment?, StableSegment?>>
    ): List<AlignedSegment> {
        if (pairs.isEmpty()) return emptyList()
        val sourceCount = pairs.mapNotNull { it.first?.segmentId }.distinct().size
        val translatedCount = pairs.mapNotNull { it.second?.segmentId }.distinct().size
        val grouped = when {
            sourceCount > translatedCount -> pairs.groupBy { it.second?.segmentId ?: "missing_translation_${it.first?.ordinal}" }.values
            translatedCount > sourceCount -> pairs.groupBy { it.first?.segmentId ?: "missing_source_${it.second?.ordinal}" }.values
            else -> pairs.map { listOf(it) }
        }
        return grouped.mapIndexed { index, group ->
            val sources = group.mapNotNull { it.first }.distinctBy { it.segmentId }
            val translations = group.mapNotNull { it.second }.distinctBy { it.segmentId }
            val relation = when {
                sources.size > 1 -> "MANY_TO_ONE"
                translations.size > 1 -> "ONE_TO_MANY"
                else -> "ONE_TO_ONE"
            }
            AlignedSegment(
                segmentId = sources.firstOrNull()?.segmentId
                    ?: stableId(chapterId, index, translations.joinToString { it.sourceText }),
                sourceText = sources.joinToString("\n\n") { it.sourceText },
                translatedText = translations.joinToString("\n\n") { it.sourceText },
                sourceOrdinal = sources.minOfOrNull { it.ordinal },
                translatedOrdinal = translations.minOfOrNull { it.ordinal },
                relation = relation
            )
        }
    }

    private fun balancedIndex(index: Int, fromSize: Int, toSize: Int): Int {
        if (toSize <= 1) return 0
        return ((((index * 2L) + 1L) * toSize) / (fromSize * 2L)).toInt().coerceIn(0, toSize - 1)
    }

    private fun shortHash(value: String): String = sha256(value).take(12)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
