package com.breakyuna.noveltranslator.nativecore.storage

import android.database.sqlite.SQLiteDatabase
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import java.security.MessageDigest

/** Untrusted input is validated read-only, before publication or local instance registration. */
internal object NativeImportValidator {
    fun validate(file: File, limits: TextProjectLimits, budget: NativeImportLimits,
                 checkActive: () -> Unit): ProjectId {
        if (file.length() > budget.maxFileBytes) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        // Check the name-snapshot budget before full schema/fingerprint validation. A tampered
        // oversized snapshot otherwise fails the earlier integrity pass as CORRUPT_PROJECT and
        // hides the real resource-limit classification.
        if (nameSnapshotOverBudget(file, 32 * 4096)) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        NativeProjectDatabase.open(file).use { db ->
            NativeProjectDatabase.validateSchema(db)
            val version = db.version
            val allItems = if (version >= 4) "(SELECT * FROM items UNION ALL SELECT * FROM archived_items)" else "items"
            if (version >= 4) {
                val badTypes = listOf("id", "asset_id", "row_index", "slot", "skip_internal_filter")
                    .joinToString(" OR ") { "typeof($it) != 'integer'" }
                db.rawQuery("SELECT 1 FROM archived_items WHERE $badTypes LIMIT 1", null).use { if (it.moveToFirst()) corrupt() }
                db.rawQuery("SELECT 1 FROM archived_items a WHERE a.asset_id <= 0 OR EXISTS (SELECT 1 FROM assets s WHERE s.id=a.asset_id) " +
                    "OR NOT EXISTS (SELECT 1 FROM asset_revisions r WHERE r.kind='REMOVE' AND r.asset_id=a.asset_id) LIMIT 1",
                    null).use { if (it.moveToFirst()) corrupt() }
                for ((column, max) in listOf("source" to limits.maxItemChars, "translation" to limits.maxItemChars,
                    "fingerprint" to 64, "status" to 32, "tag" to 4096, "extra_field" to 65_536,
                    "name_source" to 32 * 4096, "name_translation" to 32 * 4096))
                    requireTextBudget(db, "archived_items", column, max)
            }
            if (version >= 5) {
                db.rawQuery("SELECT 1 FROM item_revisions WHERE " +
                    "(name_source IS NOT NULL AND typeof(name_source) != 'text') OR " +
                    "(name_translation IS NOT NULL AND typeof(name_translation) != 'text') LIMIT 1", null).use {
                    if (it.moveToFirst()) corrupt()
                }
                requireTextBudget(db, "item_revisions", "name_source", 32 * 4096)
                requireTextBudget(db, "item_revisions", "name_translation", 32 * 4096)
            }
            for ((table, columns) in listOf(
                "project" to listOf("singleton", "revision", "fingerprint_version"),
                "assets" to listOf("id", "original_size"),
                "asset_chunks" to listOf("asset_id", "part"),
                "items" to listOf("id", "asset_id", "row_index"),
                "item_revisions" to listOf("revision", "item_id", "generation", "committed_at"),
                "write_receipts" to listOf("generation", "item_id", "revision", "committed_at"),
            )) {
                val predicate = columns.joinToString(" OR ") { "typeof($it) != 'integer'" }
                db.rawQuery("SELECT 1 FROM $table WHERE $predicate LIMIT 1", null).use { if (it.moveToFirst()) corrupt() }
            }
            db.rawQuery("PRAGMA quick_check", null).use {
                if (!it.moveToFirst() || it.getString(0) != "ok" || it.moveToNext()) corrupt()
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { if (it.moveToFirst()) corrupt() }
            if (count(db, "project") != 1L) corrupt()
            if (version < 3 && count(db, "assets") != 1L) corrupt()
            if (version >= 3 && count(db, "assets") !in 1..1_000L) corrupt()
            requireTextBudget(db, "project", "name", 200)
            requireTextBudget(db, "project", "project_id", 2048)
            for ((table, columns) in listOf(
                "assets" to listOf("path" to 4096, "file_type" to 32, "codec" to 16, "sha256" to 64),
                "items" to listOf("fingerprint" to 64, "status" to 32),
                "item_revisions" to listOf("previous_fingerprint" to 64, "fingerprint" to 64, "status" to 32,
                    "instance_id" to 2048, "command_id" to 2048),
                "write_receipts" to listOf("instance_id" to 2048, "command_id" to 2048, "digest" to 64, "outcome" to 32),
            )) for ((column, max) in columns) requireTextBudget(db, table, column, max)
            val project = db.rawQuery("SELECT project_id, name, revision, fingerprint_version FROM project WHERE singleton = 1", null).use {
                if (!it.moveToFirst() || it.getLong(2) < 0 || it.getInt(3) != 1) corrupt()
                val name = it.getString(1)
                if (name.isBlank() || name.length > 200 || name.any(Char::isISOControl)) corrupt()
                TextProjectFingerprint.utf8(name)
                val id = it.getString(0)
                if (id.length > 2048) corrupt()
                ProjectId(id) to it.getLong(2)
            }
            val assetIds = validateAssets(db, version, limits, checkActive)
            if (count(db, "items") + (if (version >= 4) count(db, "archived_items") else 0L) > limits.maxItems) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            if (count(db, "item_revisions") > budget.maxHistoryRows || count(db, "write_receipts") > budget.maxHistoryRows) {
                fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            }
            requireTextBudget(db, "items", "source", limits.maxItemChars)
            requireTextBudget(db, "items", "translation", limits.maxItemChars)
            requireTextBudget(db, "item_revisions", "translation", limits.maxItemChars)
            if (version >= 3) {
                requireTextBudget(db, "items", "tag", 4096)
                requireTextBudget(db, "items", "extra_field", 65_536)
                requireTextBudget(db, "items", "name_source", 32 * 4096)
                requireTextBudget(db, "items", "name_translation", 32 * 4096)
            }
            var validationChars = 0L
            val chains = HashMap<Long, ObjectFingerprint>()
            val currentFingerprints = HashMap<Long, ObjectFingerprint>()
            val revised = HashSet<Long>()
            fun charge(chars: Long) {
                validationChars += chars
                if (validationChars > budget.maxValidationChars) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            }
            val itemColumns = if (version >= 3) NativeProjectDatabase.ITEM_COLUMNS
                else NativeProjectDatabase.ITEM_COLUMNS_V2 + ", asset_id"
            db.rawQuery(if (version >= 4) "SELECT $itemColumns, 0 AS archived FROM items UNION ALL " +
                "SELECT $itemColumns, 1 AS archived FROM archived_items ORDER BY id"
                else "SELECT $itemColumns FROM items ORDER BY id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    checkActive()
                    if (cursor.getLong(0) <= 0 || cursor.getLong(1) !in 0 until limits.maxItems.toLong()) corrupt()
                    val assetId = if (version >= 3) cursor.getLong(6) else cursor.getLong(6)
                    if ((version < 4 || cursor.getInt(cursor.columnCount - 1) == 0) && assetId !in assetIds) corrupt()
                    charge(cursor.getString(2).length.toLong() * 2 + cursor.getString(3).length)
                    val item = NativeProjectDatabase.item(cursor, version)
                    if (item.source.length > limits.maxItemChars || item.translation.length > limits.maxItemChars) {
                        fail(TextProjectException.Reason.LIMIT_EXCEEDED)
                    }
                    if (chains.put(item.id.value, item.fingerprint) != null) corrupt()
                    currentFingerprints[item.id.value] = item.fingerprint
                }
            }
            val maxItemId = NativeProjectDatabase.scalar(db, "SELECT COALESCE(MAX(id), 0) FROM $allItems")
            val sequenceNames = if (version >= 3) setOf("items", "assets") else setOf("items")
            db.rawQuery("SELECT name, seq FROM sqlite_sequence", null).use {
                val seen = mutableSetOf<String>()
                while (it.moveToNext()) {
                    val name = it.getString(0)
                    if (name !in sequenceNames || !seen.add(name)) {
                        // sqlite_sequence also holds AUTOINCREMENT tables with no rows; tolerate.
                        if (name == "sqlite_sequence") continue
                    }
                    if (name == "items" && it.getLong(1) < maxItemId) corrupt()
                    if (version >= 4 && name == "assets") {
                        val maxAsset = NativeProjectDatabase.scalar(db, "SELECT COALESCE(MAX(value),0) FROM " +
                            "(SELECT MAX(id) AS value FROM assets UNION ALL SELECT MAX(asset_id) FROM archived_items UNION ALL SELECT MAX(asset_id) FROM asset_revisions)")
                        if (it.getLong(1) < maxAsset) corrupt()
                    }
                }
                if (maxItemId > 0 && "items" !in seen) corrupt()
                if (version >= 4 && "assets" !in seen) corrupt()
            }
            val policyCount = if (version >= 2) count(db, "policy_revisions") else 0
            val assetCount = if (version >= 3) count(db, "asset_revisions") else 0
            if (count(db, "item_revisions") + policyCount + assetCount != project.second) corrupt()
            if (project.second > budget.maxHistoryRows) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            if (policyCount > budget.maxHistoryRows ||
                (version >= 2 && count(db, "policy_receipts") > budget.maxHistoryRows) ||
                (version >= 3 && (count(db, "asset_revisions") > budget.maxHistoryRows ||
                    count(db, "asset_receipts") > budget.maxHistoryRows))) {
                fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            }
            if (version >= 2) {
                charge(validatePolicy(db, project.first, project.second, budget.maxValidationChars, checkActive))
            }
            if (version >= 3) validateAssetsMetadata(db, project.second, budget.maxValidationChars, checkActive)
            val revisionQuery = when {
                version >= 3 -> "SELECT revision FROM item_revisions UNION ALL SELECT revision FROM policy_revisions " +
                    "UNION ALL SELECT revision FROM asset_revisions ORDER BY revision"
                version == 2 -> "SELECT revision FROM item_revisions UNION ALL SELECT revision FROM policy_revisions ORDER BY revision"
                else -> "SELECT revision FROM item_revisions ORDER BY revision"
            }
            db.rawQuery(revisionQuery, null).use {
                var global = 0L
                while (it.moveToNext()) { checkActive(); if (it.getLong(0) != ++global) corrupt() }
                if (global != project.second) corrupt()
            }
            db.rawQuery("SELECT 1 FROM item_revisions r WHERE NOT EXISTS " +
                "(SELECT 1 FROM $allItems i WHERE i.id=r.item_id) LIMIT 1", null).use { if (it.moveToFirst()) corrupt() }
            val approvedEvidence = if (version >= 6) ApprovedItemReceiptAudit.validate(db, project, budget, ::charge, checkActive)
                else emptyMap()
            if (version >= 7) ApprovedPdfPageReceiptAudit.validate(db, project, budget, ::charge, checkActive)
            validateItemRevisions(db, version, limits, project, chains, revised, approvedEvidence, ::charge, checkActive)
            if (currentFingerprints.any { (id, fingerprint) -> chains[id] != fingerprint }) corrupt()
            db.rawQuery("SELECT instance_id, generation, command_id, digest, item_id, outcome, revision, committed_at FROM write_receipts", null).use { cursor ->
                while (cursor.moveToNext()) {
                    checkActive()
                    boundedIdentity(cursor.getString(0)); boundedIdentity(cursor.getString(2))
                    ObjectFingerprint(cursor.getString(3))
                    ManualEditStatus.valueOf(cursor.getString(5))
                    if (cursor.getLong(1) <= 0 || cursor.getLong(4) <= 0 || cursor.getLong(6) !in 0..project.second || cursor.getLong(7) < 0) corrupt()
                }
            }
            val groupException = if (version >= 6) " AND NOT EXISTS (SELECT 1 FROM approved_item_receipts a WHERE " +
                "a.instance_id=r.instance_id AND a.generation=r.generation AND a.command_id=r.command_id)" else ""
            db.rawQuery("""SELECT 1 FROM item_revisions r LEFT JOIN write_receipts w ON
                w.instance_id = r.instance_id AND w.generation = r.generation AND w.command_id = r.command_id
                WHERE (w.command_id IS NULL OR w.outcome != 'COMMITTED' OR w.item_id != r.item_id OR w.revision != r.revision)
                $groupException LIMIT 1""", null).use {
                if (it.moveToFirst()) corrupt()
            }
            db.rawQuery("""SELECT 1 FROM write_receipts w LEFT JOIN item_revisions r ON
                w.instance_id = r.instance_id AND w.generation = r.generation AND w.command_id = r.command_id
                WHERE w.outcome = 'COMMITTED' AND (r.revision IS NULL OR r.revision != w.revision OR r.item_id != w.item_id
                    OR r.committed_at != w.committed_at) LIMIT 1""", null).use { if (it.moveToFirst()) corrupt() }
            return project.first
        }
    }

    private fun validateItemRevisions(db: SQLiteDatabase, version: Int, limits: TextProjectLimits,
        project: Pair<ProjectId, Long>, chains: HashMap<Long, ObjectFingerprint>, revised: MutableSet<Long>,
        approvedEvidence: Map<Long, ApprovedItemRevisionEvidence>,
        charge: (Long) -> Unit, checkActive: () -> Unit) {
        val items = if (version >= 4) "(SELECT * FROM items UNION ALL SELECT * FROM archived_items)" else "items"
        val nameColumns = if (version >= 5) ", r.name_source, r.name_translation" else ""
        val sql = if (version >= 3) {
            """SELECT r.revision, r.item_id, r.previous_fingerprint, r.fingerprint,
                r.translation, r.status, r.instance_id, r.generation, r.command_id, r.committed_at,
                i.row_index, i.source, i.asset_id, i.slot, i.tag, i.name_source, i.name_translation,
                i.extra_field, i.skip_internal_filter,
                (SELECT w.digest FROM write_receipts w WHERE w.instance_id = r.instance_id AND w.generation = r.generation
                    AND w.command_id = r.command_id)$nameColumns FROM item_revisions r JOIN $items i ON i.id = r.item_id
                ORDER BY r.revision"""
        } else {
            """SELECT r.revision, r.item_id, r.previous_fingerprint, r.fingerprint,
                r.translation, r.status, r.instance_id, r.generation, r.command_id, r.committed_at,
                i.row_index, i.source, i.asset_id, 0, '', NULL, NULL, '{}', 0,
                (SELECT w.digest FROM write_receipts w WHERE w.instance_id = r.instance_id AND w.generation = r.generation
                    AND w.command_id = r.command_id) FROM item_revisions r JOIN $items i ON i.id = r.item_id ORDER BY r.revision"""
        }
        db.rawQuery(sql, null).use { cursor ->
            var revision = 0L
            val seenApproved = HashSet<Long>()
            while (cursor.moveToNext()) {
                checkActive()
                val next = cursor.getLong(0)
                if ((version == 1 && next != revision + 1) || next <= revision || next > project.second ||
                    cursor.getLong(7) <= 0 || cursor.getLong(9) < 0) corrupt()
                revision = next
                if (cursor.getString(4).length > limits.maxItemChars) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
                charge(cursor.getString(11).length.toLong() + cursor.getString(4).length)
                ObjectFingerprint(cursor.getString(2))
                val itemId = cursor.getLong(1)
                val previous = chains[itemId] ?: corrupt()
                if (itemId in revised && previous.sha256 != cursor.getString(2)) corrupt()
                val status = try { TextItemStatus.valueOf(cursor.getString(5)) }
                catch (_: IllegalArgumentException) { corrupt() }
                // A row written by v5 always stores the names effective after it. Legacy rows
                // (v3/v4, or a v5 row missing the optional snapshot) fall back to the item's
                // current metadata, matching the pre-v5 reconstruction rule.
                val snapshotSourceText = if (version >= 5) cursor.getString(20) else null
                val snapshotTranslationText = if (version >= 5) cursor.getString(21) else null
                if (snapshotSourceText != null) charge(snapshotSourceText.length.toLong())
                if (snapshotTranslationText != null) charge(snapshotTranslationText.length.toLong())
                val snapshotSource = snapshotSourceText?.let { NativeProjectDatabase.decodeNameSnapshot(it) }
                val snapshotTranslation = snapshotTranslationText?.let { NativeProjectDatabase.decodeNameSnapshot(it) }
                val expected = if (version >= 3) {
                    val metadata = try {
                        TextItemMetadata(cursor.getString(14),
                            if (snapshotSourceText != null) snapshotSource
                            else NativeProjectDatabase.decodeNames(cursor.getString(15)),
                            if (snapshotTranslationText != null) snapshotTranslation
                            else NativeProjectDatabase.decodeNames(cursor.getString(16)),
                            cursor.getString(17), cursor.getInt(18) != 0)
                    } catch (_: IllegalArgumentException) { corrupt() }
                    TextProjectFingerprint.item(ItemId(cursor.getLong(1)), cursor.getInt(10),
                        cursor.getString(11), cursor.getString(4), status, metadata, cursor.getLong(12), cursor.getInt(13))
                } else {
                    TextProjectFingerprint.item(ItemId(cursor.getLong(1)), cursor.getInt(10),
                        cursor.getString(11), cursor.getString(4), status)
                }
                if (expected.sha256 != cursor.getString(3)) corrupt()
                chains[itemId] = expected
                revised.add(itemId)
                boundedIdentity(cursor.getString(6)); boundedIdentity(cursor.getString(8))
                val approved = approvedEvidence[revision]
                if (approved != null) {
                    if (!cursor.isNull(19) || snapshotSourceText == null || snapshotTranslationText == null ||
                        approved.instance != cursor.getString(6) || approved.generation != cursor.getLong(7) ||
                        approved.command != cursor.getString(8) || approved.item != itemId || approved.row != cursor.getInt(10) ||
                        approved.committedAt != cursor.getLong(9) ||
                        approved.asset != cursor.getLong(12) || approved.base.sha256 != cursor.getString(2) ||
                        approved.after != expected) corrupt()
                    seenApproved.add(revision)
                    continue
                }
                if (cursor.isNull(19)) corrupt()
                val ref = LocalProjectRef(project.first, ProjectInstanceId(cursor.getString(6)),
                    RecoveryGeneration(cursor.getLong(7)))
                val intent = ManualTextEdit(CommandId(cursor.getString(8)), ref,
                    ItemId(cursor.getLong(1)), revision - 1, ObjectFingerprint(cursor.getString(2)),
                    cursor.getString(4), status)
                if (TextProjectFingerprint.command(intent) != cursor.getString(19) &&
                    !(version >= 8 && snapshotTranslationText != null && snapshotTranslation != null &&
                        ItemTextReplacement.commandDigest(intent, snapshotTranslation) == cursor.getString(19)) &&
                    !matchesNameIntent(cursor.getString(19), cursor.getString(8), ref, cursor.getLong(1), revision - 1,
                        ObjectFingerprint(cursor.getString(2)), snapshotTranslation, snapshotSource, version)) corrupt()
            }
            if (seenApproved.size != approvedEvidence.size) corrupt()
        }
    }

    /**
     * A name edit leaves translation and status unchanged, so its receipt can only be identified
     * by the name command digest. The original command may have preserved or replaced source
     * names; either explicit or preserving encoding is accepted when it reproduces the receipt.
     */
    private fun matchesNameIntent(digest: String, commandId: String, ref: LocalProjectRef, itemId: Long,
        revision: Long, previous: ObjectFingerprint, translation: List<String>?, source: List<String>?,
        version: Int): Boolean {
        if (version < 5 || translation == null) return false
        if (matchesNameCommand(digest, commandId, ref, itemId, revision, previous, translation, source)) return true
        return source != null &&
            matchesNameCommand(digest, commandId, ref, itemId, revision, previous, translation, null)
    }

    private fun matchesNameCommand(digest: String, commandId: String, ref: LocalProjectRef, itemId: Long,
        revision: Long, previous: ObjectFingerprint, translation: List<String>, source: List<String>?): Boolean =
        try {
            TextProjectFingerprint.nameCommand(ManualNameEdit(CommandId(commandId), ref, ItemId(itemId),
                revision, previous, translation, source)) == digest
        } catch (_: IllegalArgumentException) { false } catch (_: TextProjectException) { false }

    private fun validateAssets(db: SQLiteDatabase, version: Int, limits: TextProjectLimits,
                               checkActive: () -> Unit): Set<Long> {
        val ids = mutableSetOf<Long>()
        val expectedOrder = if (version >= 3) 0 else 0
        var order = expectedOrder
        val sql = if (version >= 3) {
            "SELECT id,path,file_type,codec,original_size,sha256,ordinal,enabled,metadata FROM assets ORDER BY ordinal,id"
        } else {
            "SELECT id,path,file_type,codec,original_size,sha256,ordinal,enabled,metadata FROM " +
                "(SELECT id,path,file_type,codec,original_size,sha256,0 AS ordinal,1 AS enabled,'{}' AS metadata FROM assets)"
        }
        db.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                checkActive()
                val id = cursor.getLong(0)
                if (id <= 0 || !ids.add(id)) corrupt()
                val path = try { RelativePath(cursor.getString(1)) } catch (_: IllegalArgumentException) { corrupt() }
                if (path.value.length > 4096) corrupt()
                TextProjectFingerprint.utf8(path.value)
                val format = try { ProjectAssetFormat.valueOf(cursor.getString(2)) } catch (_: IllegalArgumentException) { corrupt() }
                if (version < 3 && (format != ProjectAssetFormat.TXT ||
                        !path.value.endsWith(".txt", ignoreCase = true))) corrupt()
                if (cursor.getString(3) != "raw-v1") corrupt()
                val ordinal = cursor.getInt(6)
                if (version >= 3 && ordinal != order) corrupt()
                order++
                if (version >= 3) {
                    val enabled = cursor.getInt(7)
                    if (enabled !in 0..1) corrupt()
                    val metadata = cursor.getString(8)
                    if (metadata.length > 65_536) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
                    JsonObjectValue.parse(metadata, JsonInputLimits(65_536, 32))
                }
                val size = cursor.getLong(4)
                if (size !in 0..MAX_ASSET_BYTES) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
                val sha = ObjectFingerprint(cursor.getString(5)).sha256
                validateChunks(db, id, size, sha, checkActive)
            }
        }
        if (ids.isEmpty()) corrupt()
        return ids
    }

    private fun validateChunks(db: SQLiteDatabase, assetId: Long, size: Long, sha: String, checkActive: () -> Unit) {
        db.rawQuery("SELECT 1 FROM asset_chunks WHERE asset_id=? AND (typeof(data) != 'blob' OR length(data) > ? " +
            "OR length(data) = 0) LIMIT 1", arrayOf(assetId.toString(), NativeProjectDatabase.CHUNK_BYTES.toString())).use {
            if (it.moveToFirst()) corrupt()
        }
        val expectedChunks = if (size == 0L) 0L else (size + NativeProjectDatabase.CHUNK_BYTES - 1) / NativeProjectDatabase.CHUNK_BYTES
        val actualChunks = db.rawQuery("SELECT COUNT(*) FROM asset_chunks WHERE asset_id=?",
            arrayOf(assetId.toString())).use { if (!it.moveToFirst()) corrupt(); it.getLong(0) }
        if (actualChunks != expectedChunks) corrupt()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var part = 0L
        db.rawQuery("SELECT part,data FROM asset_chunks WHERE asset_id=? ORDER BY part",
            arrayOf(assetId.toString())).use {
            while (it.moveToNext()) {
                checkActive()
                if (it.getLong(0) != part++) corrupt()
                val bytes = it.getBlob(1)
                val expectedLength = minOf(NativeProjectDatabase.CHUNK_BYTES.toLong(), size - total).toInt()
                if (bytes.size != expectedLength) corrupt()
                total += bytes.size
                if (total > size) corrupt()
                digest.update(bytes)
            }
        }
        val hash = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        if (total != size || hash != sha) corrupt()
    }

    private fun validateAssetsMetadata(db: SQLiteDatabase, revision: Long, maxChars: Long, checkActive: () -> Unit) {
        requireTextBudget(db, "project_settings", "payload", ProjectSettingsCodec.MAX_CHARS)
        requireTextBudget(db, "project_settings", "fingerprint", 64)
        if (count(db, "project_settings") != 1L) corrupt()
        db.rawQuery("SELECT payload,fingerprint FROM project_settings WHERE singleton=1", null).use {
            if (!it.moveToFirst()) corrupt()
            val settings = ProjectSettingsCodec.decode(it.getString(0))
            if (settings.fingerprint().sha256 != it.getString(1)) corrupt()
            ProjectPageTranslationPolicy(settings, NativeProjectDatabase.assetSummaries(db))
        }
        for ((table, columns) in listOf(
            "asset_receipts" to listOf("generation", "revision", "committed_at"),
            "asset_revisions" to listOf("revision", "generation", "committed_at"),
        )) {
            db.rawQuery("SELECT 1 FROM $table WHERE " + columns.joinToString(" OR ") { "typeof($it) != 'integer'" } + " LIMIT 1", null).use {
                if (it.moveToFirst()) corrupt()
            }
        }
        for (table in listOf("asset_receipts", "asset_revisions")) {
            for (column in listOf("instance_id", "command_id")) requireTextBudget(db, table, column, 2048)
        }
        requireTextBudget(db, "asset_receipts", "digest", 64)
        requireTextBudget(db, "asset_receipts", "outcome", 32)
        requireTextBudget(db, "asset_receipts", "kind", 32)
        requireTextBudget(db, "asset_revisions", "payload", ProjectSettingsCodec.MAX_CHARS)
        var chars = 0L
        db.rawQuery("SELECT instance_id,generation,command_id,digest,outcome,revision,asset_id,kind,committed_at FROM asset_receipts", null).use {
            while (it.moveToNext()) {
                checkActive(); boundedIdentity(it.getString(0)); boundedIdentity(it.getString(2))
                ObjectFingerprint(it.getString(3))
                AssetMutationStatus.valueOf(it.getString(4))
                AssetMutationKind.valueOf(it.getString(7))
                if (it.getLong(1) <= 0 || it.getLong(5) !in 0..revision || it.getLong(8) < 0) corrupt()
            }
        }
        db.rawQuery("SELECT revision,kind,asset_id,payload,instance_id,generation,command_id,committed_at FROM asset_revisions", null).use {
            var previous = 0L
            while (it.moveToNext()) {
                checkActive()
                val value = it.getLong(0)
                if (value <= previous || value > revision) corrupt()
                previous = value
                AssetMutationKind.valueOf(it.getString(1))
                boundedIdentity(it.getString(4)); boundedIdentity(it.getString(6))
                if (it.getLong(5) <= 0 || it.getLong(7) < 0) corrupt()
                val payload = it.getString(3)
                chars += payload.length
                if (chars > maxChars) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
                JsonObjectValue.parse(payload, JsonInputLimits(ProjectSettingsCodec.MAX_CHARS, 64))
                if (it.getString(1) == AssetMutationKind.SETTINGS.name) ProjectSettingsCodec.decode(payload)
            }
        }
    }

    private fun validatePolicy(db: SQLiteDatabase, project: ProjectId, revision: Long, maxChars: Long, checkActive: () -> Unit): Long {
        if (count(db, "text_policy") != 1L) corrupt()
        for (table in listOf("text_policy", "policy_revisions")) {
            requireTextBudget(db, table, "payload", ProjectTextPolicyCodec.MAX_CHARS)
            requireTextBudget(db, table, "fingerprint", 64)
        }
        for ((table, columns) in listOf("text_policy" to listOf("singleton"),
            "policy_revisions" to listOf("revision", "generation", "committed_at"),
            "policy_receipts" to listOf("generation", "revision", "committed_at"))) {
            db.rawQuery("SELECT 1 FROM $table WHERE " + columns.joinToString(" OR ") { "typeof($it)!='integer'" } + " LIMIT 1", null).use {
                if (it.moveToFirst()) corrupt()
            }
        }
        for ((table, columns) in listOf("text_policy" to listOf("payload", "fingerprint"),
            "policy_revisions" to listOf("payload", "previous_fingerprint", "fingerprint", "instance_id", "command_id"),
            "policy_receipts" to listOf("instance_id", "command_id", "digest", "outcome", "fingerprint"))) {
            db.rawQuery("SELECT 1 FROM $table WHERE " + columns.joinToString(" OR ") { "typeof($it)!='text'" } + " LIMIT 1", null).use {
                if (it.moveToFirst()) corrupt()
            }
        }
        for (table in listOf("policy_revisions", "policy_receipts")) {
            for (column in listOf("instance_id", "command_id")) requireTextBudget(db, table, column, 2048)
        }
        for (column in listOf("fingerprint", "digest")) requireTextBudget(db, "policy_receipts", column, 64)
        requireTextBudget(db, "policy_revisions", "previous_fingerprint", 64)
        requireTextBudget(db, "policy_receipts", "outcome", 32)
        var chars = 0L
        fun decode(payload: String): ProjectTextPolicy {
            chars += payload.length
            if (chars > maxChars) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            return ProjectTextPolicyCodec.decode(payload).also { TextRuleValidation.validate(it, checkActive) }
        }
        val current = db.rawQuery("SELECT payload,fingerprint FROM text_policy WHERE singleton=1", null).use {
            if (!it.moveToFirst()) corrupt()
            val policy = decode(it.getString(0))
            if (policy.fingerprint().sha256 != it.getString(1)) corrupt()
            policy.fingerprint()
        }
        var previous = ProjectTextPolicy().fingerprint()
        db.rawQuery("SELECT revision,previous_fingerprint,fingerprint,payload,instance_id,generation,command_id,committed_at FROM policy_revisions ORDER BY revision", null).use {
            while (it.moveToNext()) {
                checkActive()
                if (it.getLong(0) !in 1..revision || it.getLong(5) <= 0 || it.getLong(7) < 0 || it.getString(1) != previous.sha256) corrupt()
                boundedIdentity(it.getString(4)); boundedIdentity(it.getString(6))
                val policy = decode(it.getString(3))
                val next = policy.fingerprint()
                if (next.sha256 != it.getString(2)) corrupt()
                val ref = LocalProjectRef(project, ProjectInstanceId(it.getString(4)), RecoveryGeneration(it.getLong(5)))
                val command = ProjectPolicyEdit(CommandId(it.getString(6)), ref, it.getLong(0) - 1, previous, policy)
                db.rawQuery("SELECT digest,outcome,revision,fingerprint,committed_at FROM policy_receipts WHERE instance_id=? AND generation=? AND command_id=?",
                    arrayOf(ref.instanceId.value, ref.generation.value.toString(), command.commandId.value)).use { receipt ->
                    if (!receipt.moveToFirst() || receipt.getString(0) != command.digest() || receipt.getString(1) != "COMMITTED" ||
                        receipt.getLong(2) != it.getLong(0) || receipt.getString(3) != next.sha256 || receipt.getLong(4) != it.getLong(7)) corrupt()
                }
                previous = next
            }
        }
        if (previous != current) corrupt()
        db.rawQuery("SELECT instance_id,generation,command_id,digest,outcome,revision,fingerprint,committed_at FROM policy_receipts", null).use {
            while (it.moveToNext()) {
                checkActive(); boundedIdentity(it.getString(0)); boundedIdentity(it.getString(2))
                ObjectFingerprint(it.getString(3)); ObjectFingerprint(it.getString(6))
                if (it.getLong(1) <= 0 || it.getLong(5) !in 0..revision || it.getLong(7) < 0) corrupt()
                PolicyEditStatus.valueOf(it.getString(4))
            }
        }
        db.rawQuery("""SELECT 1 FROM policy_receipts w LEFT JOIN policy_revisions r ON
            w.instance_id=r.instance_id AND w.generation=r.generation AND w.command_id=r.command_id
            WHERE w.outcome='COMMITTED' AND (r.revision IS NULL OR r.revision!=w.revision OR r.fingerprint!=w.fingerprint OR r.committed_at!=w.committed_at) LIMIT 1""", null).use { if (it.moveToFirst()) corrupt() }
        db.rawQuery("""SELECT 1 FROM policy_receipts p JOIN write_receipts w ON
            p.instance_id=w.instance_id AND p.generation=w.generation AND p.command_id=w.command_id LIMIT 1""", null).use { if (it.moveToFirst()) corrupt() }
        return chars
    }

    private fun nameSnapshotOverBudget(file: File, limit: Int): Boolean = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val columns = HashSet<String>()
            db.rawQuery("PRAGMA table_info(item_revisions)", null).use { cursor ->
                while (cursor.moveToNext()) columns.add(cursor.getString(1))
            }
            (columns.contains("name_source") || columns.contains("name_translation")) &&
                db.rawQuery(
                    "SELECT 1 FROM item_revisions WHERE " +
                        (if (columns.contains("name_source")) "length(name_source) > CAST(? AS INTEGER) " else "0 ") + " OR " +
                        (if (columns.contains("name_translation")) "length(name_translation) > CAST(? AS INTEGER) " else "0 ") + "LIMIT 1",
                    arrayOf(limit.toString(), limit.toString()),
                ).use { it.moveToFirst() }
        }
    } catch (_: Exception) {
        false
    }

    private fun requireTextBudget(db: SQLiteDatabase, table: String, column: String, limit: Int) {
        db.rawQuery("SELECT 1 FROM $table WHERE length($column) > CAST(? AS INTEGER) OR " +
            "length(CAST($column AS BLOB)) > CAST(? AS INTEGER) LIMIT 1",
            arrayOf(limit.toString(), (limit * 4).toString())).use {
            if (it.moveToFirst()) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        }
    }
    private fun boundedIdentity(value: String) {
        if (value.isBlank() || value.length > 2048 || value.any(Char::isISOControl)) corrupt()
    }
    private fun count(db: SQLiteDatabase, table: String): Long = NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM $table")
    private fun corrupt(): Nothing = fail(TextProjectException.Reason.CORRUPT_PROJECT)
    private fun fail(reason: TextProjectException.Reason): Nothing = throw TextProjectException(reason)
    private const val MAX_ASSET_BYTES = 64L * 1024 * 1024
}
