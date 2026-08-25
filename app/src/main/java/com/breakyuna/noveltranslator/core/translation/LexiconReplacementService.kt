package com.breakyuna.noveltranslator.core.translation

import androidx.room.withTransaction
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.RevisionType
import com.breakyuna.noveltranslator.data.model.SegmentRevisionEntity

data class LexiconReplacementCandidate(
    val editionSegmentId: Long,
    val before: String,
    val after: String,
    val matchCount: Int
)

/** Preview first, then create reversible revisions only for candidates explicitly confirmed by the user. */
class LexiconReplacementService(private val database: AppDatabase) {
    suspend fun preview(
        editionIds: List<Long>,
        sourceTerm: String,
        targetTerm: String,
        caseSensitive: Boolean,
        exactMatch: Boolean
    ): List<LexiconReplacementCandidate> {
        require(sourceTerm.isNotBlank())
        if (editionIds.isEmpty()) return emptyList()
        val segments = database.bookDao().getEditionSegmentsByEditions(editionIds.distinct())
        if (segments.isEmpty()) return emptyList()
        val revisions = database.bookDao().getActiveRevisions(segments.map { it.id })
            .groupBy { it.editionSegmentId }
            .mapValues { (_, rows) -> rows.maxWithOrNull(compareBy<SegmentRevisionEntity> { it.priority }.thenBy { it.createdAt }) }
        val regex = replacementRegex(sourceTerm, caseSensitive, exactMatch)
        return segments.mapNotNull { segment ->
            val before = revisions[segment.id]?.text ?: segment.baseText
            val matches = regex.findAll(before).count()
            if (matches == 0) null else LexiconReplacementCandidate(segment.id, before, regex.replace(before, targetTerm), matches)
        }
    }

    suspend fun confirm(candidates: List<LexiconReplacementCandidate>, note: String): List<Long> = database.withTransaction {
        candidates.map { candidate ->
            database.bookDao().insertRevision(
                SegmentRevisionEntity(
                    editionSegmentId = candidate.editionSegmentId,
                    revisionType = RevisionType.USER_CONFIRMED_REPLACEMENT.name,
                    text = candidate.after,
                    note = note
                )
            )
        }
    }

    suspend fun undo(revisionIds: List<Long>) = database.withTransaction {
        revisionIds.forEach { database.bookDao().deactivateRevision(it) }
    }

    private fun replacementRegex(term: String, caseSensitive: Boolean, exactMatch: Boolean): Regex {
        val escaped = Regex.escape(term)
        val body = if (exactMatch && term.all { it.code < 128 && (it.isLetterOrDigit() || it == '_') }) {
            "(?<![\\p{L}\\p{N}_])$escaped(?![\\p{L}\\p{N}_])"
        } else escaped
        return Regex(body, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
    }
}
