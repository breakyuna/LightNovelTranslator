package com.breakyuna.noveltranslator.nativecore.domain

/** Structural source availability, deliberately separate from export-time SHA-256 validation. */
enum class AssetSourceAvailability { PRESENT, INCOMPLETE }

data class AssetFileInventory(val assetId: Long, val updatedAtMillis: Long?, val source: AssetSourceAvailability) {
    init { require(assetId > 0 && (updatedAtMillis == null || updatedAtMillis >= 0)) }
}

class ProjectFileInventory(val ref: LocalProjectRef, val revision: Long, entries: List<AssetFileInventory>) {
    val entries: List<AssetFileInventory> = java.util.Collections.unmodifiableList(ArrayList(entries))
    init { require(revision >= 0 && this.entries.map { it.assetId }.distinct().size == this.entries.size) }
}

interface ProjectFileInventoryPort {
    suspend fun fileInventory(ref: LocalProjectRef, revision: Long): ProjectFileInventory
}
