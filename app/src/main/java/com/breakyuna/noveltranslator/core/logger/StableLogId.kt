package com.breakyuna.noveltranslator.core.logger

import java.util.UUID

/** Stable identifiers shared by persisted system logs and in-memory live logs. */
object StableLogId {
    fun create(): String = UUID.randomUUID().toString()
}
