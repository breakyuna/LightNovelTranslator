package com.breakyuna.noveltranslator.core.logger

import java.nio.charset.StandardCharsets
import java.util.UUID

/** Stable identifiers shared by persisted system logs and in-memory live logs. */
object StableLogId {
    fun create(): String = UUID.randomUUID().toString()

    fun fromLegacyRecord(canonicalRecord: String): String =
        "legacy-${UUID.nameUUIDFromBytes(canonicalRecord.toByteArray(StandardCharsets.UTF_8))}"
}
