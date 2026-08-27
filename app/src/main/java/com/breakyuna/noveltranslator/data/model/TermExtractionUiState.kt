package com.breakyuna.noveltranslator.data.model

import java.util.UUID

data class TermExtractionCandidate(
    val id: String = UUID.randomUUID().toString(),
    val term: GlossaryEntity
)

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
