package com.breakyuna.noveltranslator.core.agent

import com.breakyuna.noveltranslator.data.model.TermCategory

/** A validated, review-only terminology observation returned by the extraction agent. */
data class ExtractedTermCandidate(
    val originalTerm: String,
    val translatedTerm: String,
    val category: TermCategory,
    val notes: String = ""
)
