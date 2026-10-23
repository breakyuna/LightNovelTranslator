package com.breakyuna.noveltranslator.nativecore.domain

data class TranslationAuditHandle(val attempt: RequestAttemptId, val ref: LocalProjectRef,
    val run: RunIdentity, val digest: String)

/** Metadata and usage only. No response text, source, prompt or credential is persisted here. */
interface TranslationRequestAuditPort {
    /** Duplicate attempts are rejected, even when their payloads match. This is never a replay API. */
    suspend fun dispatched(lease: RunLease, request: TranslationModelRequest, digest: String): TranslationAuditHandle
    /** A complete HTTP response is a fact even if content parsing, Stop or a later write fails. */
    suspend fun received(handle: TranslationAuditHandle, usage: RequestUsage, failure: ModelFailureKind?)
}
