package com.breakyuna.noveltranslator.core.glossary

import com.breakyuna.noveltranslator.data.model.LexiconEntryEntity
import com.breakyuna.noveltranslator.data.model.LexiconKind
import com.breakyuna.noveltranslator.data.model.LexiconSource
import com.breakyuna.noveltranslator.data.model.ReviewStatus
import com.breakyuna.noveltranslator.data.model.TermCategory
import java.util.Locale

/**
 * File-transfer shape for one glossary entry.
 *
 * Deliberately independent of Room ids and of the owning translation project so a file can move
 * between books/projects; the DB write-back is a separate integration step (see [fromTransfer]).
 *
 * Field defaults mirror `LexiconEntryEntity` defaults so a hand-written file only needs
 * `sourceTerm` (and usually `targetTerm`).
 */
data class GlossaryTransferEntry(
    val sourceTerm: String,
    val targetTerm: String = "",
    val kind: String = LexiconKind.PROPER_NOUN.name,
    val category: String = TermCategory.CUSTOM.name,
    val aliases: List<String> = emptyList(),
    val notes: String = "",
    val caseSensitive: Boolean = false,
    val exactMatch: Boolean = true,
    val priority: Int = 0,
)

/** `LexiconEntryEntity.aliases` is a single delimited string; this is its canonical delimiter set. */
private val ALIAS_DELIMITERS = charArrayOf(',', ';', '|')

/**
 * Projects a persisted lexicon row into the transfer shape.
 * Aliases are split with the same delimiters the engine uses when matching
 * (`TranslationProtocol` scans `,` `;` `|`), so an export/import round trip is stable.
 */
fun LexiconEntryEntity.toTransfer(): GlossaryTransferEntry = GlossaryTransferEntry(
    sourceTerm = sourceTerm,
    targetTerm = targetTerm,
    kind = kind,
    category = category,
    aliases = aliases.split(*ALIAS_DELIMITERS)
        .map { it.trim() }
        .filter { it.isNotBlank() },
    notes = notes,
    caseSensitive = caseSensitive,
    exactMatch = exactMatch,
    priority = priority,
)

/**
 * Projects a transfer entry back into an entity for persistence.
 *
 * [source]/[reviewStatus] default to `IMPORTED`/`CONFIRMED` because file-imported entries are
 * user-supplied facts, not unvetted AI candidates. Enum-ish fields are upper-cased and fall back to
 * their defaults when unrecognised, matching `BookPlatformRepository.normalizeLexiconEntry`;
 * length/control-character limits stay enforced there at save time.
 *
 * Note for the integration agent: `BookPlatformRepository` requires a non-blank `targetTerm` and
 * upserts by exact `sourceTerm`, so `existing-target-empty`/normalized-key collisions must be
 * resolved in the UI layer before calling save.
 */
fun GlossaryTransferEntry.fromTransfer(
    translationProjectId: Long,
    id: Long = 0,
    source: String = LexiconSource.IMPORTED.name,
    reviewStatus: String = ReviewStatus.CONFIRMED.name,
): LexiconEntryEntity = LexiconEntryEntity(
    id = id,
    translationProjectId = translationProjectId,
    sourceTerm = sourceTerm.trim(),
    targetTerm = targetTerm.trim(),
    kind = normalizeEnum(kind, LexiconKind.values().map { it.name }, LexiconKind.PROPER_NOUN.name),
    category = normalizeEnum(category, TermCategory.values().map { it.name }, TermCategory.CUSTOM.name),
    aliases = aliases.map { it.trim() }.filter { it.isNotBlank() }.joinToString(","),
    notes = notes,
    caseSensitive = caseSensitive,
    exactMatch = exactMatch,
    priority = priority,
    source = normalizeEnum(source, LexiconSource.values().map { it.name }, LexiconSource.IMPORTED.name),
    reviewStatus = normalizeEnum(reviewStatus, ReviewStatus.values().map { it.name }, ReviewStatus.CONFIRMED.name),
)

private fun normalizeEnum(value: String, allowed: List<String>, default: String): String {
    val normalized = value.trim().uppercase(Locale.ROOT)
    return if (normalized in allowed) normalized else default
}
