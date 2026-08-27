package com.breakyuna.noveltranslator.data.model

data class TermExtractionCandidate(
    val term: GlossaryEntity
) {
    /**
     * Every scan window persists legacy evidence before publishing it to review, so Compose can
     * use the real stable database row id just like the V2 aggregate review screen.
     */
    val id: Long get() = term.id
}

sealed class TermExtractionUiState {
    object Idle : TermExtractionUiState()

    data class Scanning(
        val projectId: Long,
        val currentChapterIndex: Int,
        val currentChapterTitle: String,
        val currentWindowIndex: Int,
        val totalWindows: Int,
        val discoveredTerms: List<TermExtractionCandidate>,
        val promptTokens: Long,
        val completionTokens: Long,
        val estimatedCost: Double,
        val currency: String,
        val isPaused: Boolean = false
    ) : TermExtractionUiState()

    data class Review(
        val projectId: Long,
        val candidates: List<TermExtractionCandidate>,
        val promptTokens: Long,
        val completionTokens: Long,
        val estimatedCost: Double,
        val currency: String
    ) : TermExtractionUiState()

    data class Finished(
        val savedCount: Int,
        val totalExtracted: Int,
        val cost: Double,
        val currency: String
    ) : TermExtractionUiState()

    data class Error(
        val message: String
    ) : TermExtractionUiState()
}
