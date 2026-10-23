package com.breakyuna.noveltranslator.nativecore.domain

enum class TextSearchField { BOTH, SOURCE, TRANSLATION }

/** Literal, case-sensitive substring search. Neither SQL wildcards nor regular expressions. */
data class TextItemQuery(
    val status: TextItemStatus? = null,
    val text: String = "",
    val field: TextSearchField = TextSearchField.BOTH,
) {
    init {
        require(text.length <= MAX_TEXT_LENGTH && '\u0000' !in text)
        TextProjectFingerprint.utf8(text)
    }

    val active: Boolean get() = status != null || text.isNotEmpty()
    override fun toString(): String = "TextItemQuery(status=$status, field=$field)"

    companion object { const val MAX_TEXT_LENGTH = 256 }
}

/** Whole-project status counts, from the same read transaction as the displayed rows. */
data class TextItemStatistics(
    val unprocessed: Int, val processed: Int, val excluded: Int,
    /** NONE rows whose source satisfies the translation service's isNotBlank predicate. */
    val translatableUnprocessed: Int,
) {
    init {
        require(unprocessed >= 0 && processed >= 0 && excluded >= 0)
        require(translatableUnprocessed in 0..unprocessed)
    }
    val total: Int get() = Math.addExact(Math.addExact(unprocessed, processed), excluded)
}

/** Fixed, bounded page membership. Refreshing edits never silently drops/reorders a member. */
class TextItemWindow(ids: List<ItemId>) {
    val ids: List<ItemId> = java.util.Collections.unmodifiableList(ArrayList(ids))
    init {
        require(this.ids.size in 1..100)
        require(this.ids.zipWithNext().all { (a, b) -> a.value < b.value })
    }
}
