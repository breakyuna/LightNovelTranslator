package com.breakyuna.noveltranslator.nativecore.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Asset metadata/settings transaction owner. The source bytes and item rows are written in the
 * same transaction as the global revision and terminal receipt; a later failure leaves neither a
 * half-imported asset nor an accepted command without a receipt.
 */
internal object ProjectAssetWriteStore {
    fun importAsset(db: SQLiteDatabase, plan: AssetImportPlan, maximumItems: Int, beforeCommit: () -> Unit): AssetMutationReceipt =
        NativeProjectDatabase.transaction(db) {
            importAssetInTransaction(db, plan, maximumItems, beforeCommit)
        }

    /**
     * Transaction-internal asset import. [transactionStartRevision] is the mixed parent's
     * pre-write revision so earlier members do not turn an approved import into a false stale.
     */
    fun importAssetInTransaction(db: SQLiteDatabase, plan: AssetImportPlan, maximumItems: Int,
                                 beforeCommit: () -> Unit, transactionStartRevision: Long? = null): AssetMutationReceipt {
        val summary = NativeProjectDatabase.summary(db, plan.ref)
        val prior = receipt(db, plan.ref, plan.commandId)
        if (prior != null) {
            if (prior.first != plan.digest()) throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
            return prior.second
        }
        val batchRevision = transactionStartRevision ?: summary.revision
        if (plan.expectedRevision != batchRevision) {
            return reject(db, plan.commandId, plan.ref, AssetMutationKind.IMPORT,
                AssetMutationStatus.STALE_REVISION, summary.revision, null, plan.digest())
        }
        if (pathExists(db, plan.path.value)) {
            return reject(db, plan.commandId, plan.ref, AssetMutationKind.IMPORT,
                AssetMutationStatus.CONFLICT, summary.revision, null, plan.digest())
        }
        val retained = NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM items") +
            if (db.version >= 4) NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM archived_items") else 0L
        if (retained + plan.units.size > maximumItems) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        val ordinal = NativeProjectDatabase.scalar(db, "SELECT COALESCE(MAX(ordinal), -1) + 1 FROM assets").toInt()
        val assetId = db.insertOrThrow("assets", null, ContentValues().apply {
            put("path", plan.path.value); put("file_type", plan.format.name); put("codec", "raw-v1")
            put("original_size", plan.bytes.size); put("sha256", TextProjectFingerprint.bytes(plan.bytes))
            put("ordinal", ordinal); put("enabled", if (plan.enabled) 1 else 0); put("metadata", plan.metadata)
        })
        NativeProjectDatabase.insertChunks(db, assetId, plan.bytes, beforeCommit)
        var nextId = Math.addExact(NativeProjectDatabase.scalar(db,
            "SELECT MAX(value) FROM (SELECT COALESCE(MAX(id),0) AS value FROM items " +
                "UNION ALL SELECT COALESCE(MAX(seq),0) FROM sqlite_sequence WHERE name='items')"), 1L)
        for (unit in plan.units) {
            val id = ItemId(nextId++)
            val fingerprint = TextProjectFingerprint.item(id, unit.row, unit.source, unit.translation,
                unit.status, TextItemMetadata(unit.tag, unit.nameSource, unit.nameTranslation,
                    unit.extraField, unit.skipInternalFilter), assetId, unit.slot)
            db.insertOrThrow("items", null, ContentValues().apply {
                put("id", id.value); put("asset_id", assetId); put("row_index", unit.row)
                put("slot", unit.slot); put("source", unit.source); put("translation", unit.translation)
                put("status", unit.status.name); put("fingerprint", fingerprint.sha256)
                put("tag", unit.tag); put("name_source", NativeProjectDatabase.encodeNames(unit.nameSource))
                put("name_translation", NativeProjectDatabase.encodeNames(unit.nameTranslation))
                put("extra_field", unit.extraField); put("skip_internal_filter", if (unit.skipInternalFilter) 1 else 0)
            })
        }
        val revision = Math.addExact(summary.revision, 1)
        db.execSQL("UPDATE project SET revision=? WHERE singleton=1", arrayOf(revision))
        db.insertOrThrow("asset_revisions", null, ContentValues().apply {
            put("revision", revision); put("kind", AssetMutationKind.IMPORT.name); put("asset_id", assetId)
            put("payload", buildJsonObject {
                put("path", plan.path.value); put("format", plan.format.name)
                put("sha256", TextProjectFingerprint.bytes(plan.bytes)); put("units", plan.units.size)
            }.toString())
            put("instance_id", plan.ref.instanceId.value); put("generation", plan.ref.generation.value)
            put("command_id", plan.commandId.value); put("committed_at", now())
        })
        val at = now()
        db.insertOrThrow("asset_receipts", null, ContentValues().apply {
            put("instance_id", plan.ref.instanceId.value); put("generation", plan.ref.generation.value)
            put("command_id", plan.commandId.value); put("digest", plan.digest())
            put("outcome", AssetMutationStatus.COMMITTED.name); put("revision", revision)
            put("asset_id", assetId); put("kind", AssetMutationKind.IMPORT.name); put("committed_at", at)
        })
        beforeCommit()
        return AssetMutationReceipt(plan.commandId, plan.ref, AssetMutationKind.IMPORT,
            AssetMutationStatus.COMMITTED, revision, assetId, at)
    }

    fun remove(db: SQLiteDatabase, commandId: CommandId, ref: LocalProjectRef, revision: Long,
               assetId: Long, beforeCommit: () -> Unit): AssetMutationReceipt =
        NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            val digest = AssetCommandFingerprint.remove(ref, revision, assetId, commandId)
            val prior = receipt(db, ref, commandId)
            if (prior != null) {
                if (prior.first != digest) throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
                return@transaction prior.second
            }
            if (revision != summary.revision) return@transaction reject(db, commandId, ref, AssetMutationKind.REMOVE,
                AssetMutationStatus.STALE_REVISION, summary.revision, assetId, digest)
            if (assetId !in summary.assets.map { it.id }) return@transaction reject(db, commandId, ref,
                AssetMutationKind.REMOVE, AssetMutationStatus.UNKNOWN_ASSET, summary.revision, assetId, digest)
            if (summary.assets.size == 1) return@transaction reject(db, commandId, ref,
                AssetMutationKind.REMOVE, AssetMutationStatus.CONFLICT, summary.revision, assetId, digest)
            check(db.version >= 4) { "Asset removal requires archived item history" }
            db.execSQL("INSERT INTO archived_items SELECT * FROM items WHERE asset_id=?", arrayOf(assetId))
            db.delete("items", "asset_id=?", arrayOf(assetId.toString()))
            db.delete("asset_chunks", "asset_id=?", arrayOf(assetId.toString()))
            db.delete("assets", "id=?", arrayOf(assetId.toString()))
            normalizeOrder(db)
            val next = Math.addExact(summary.revision, 1)
            db.execSQL("UPDATE project SET revision=? WHERE singleton=1", arrayOf(next))
            val at = now()
            db.insertOrThrow("asset_revisions", null, ContentValues().apply {
                put("revision", next); put("kind", AssetMutationKind.REMOVE.name); put("asset_id", assetId)
                put("payload", "{}"); put("instance_id", ref.instanceId.value)
                put("generation", ref.generation.value); put("command_id", commandId.value); put("committed_at", at)
            })
            db.insertOrThrow("asset_receipts", null, ContentValues().apply {
                put("instance_id", ref.instanceId.value); put("generation", ref.generation.value)
                put("command_id", commandId.value); put("digest", digest)
                put("outcome", AssetMutationStatus.COMMITTED.name); put("revision", next)
                put("asset_id", assetId); put("kind", AssetMutationKind.REMOVE.name); put("committed_at", at)
            })
            beforeCommit()
            AssetMutationReceipt(commandId, ref, AssetMutationKind.REMOVE, AssetMutationStatus.COMMITTED,
                next, assetId, at)
        }

    fun reorder(db: SQLiteDatabase, commandId: CommandId, ref: LocalProjectRef, revision: Long,
                assetIds: List<Long>, beforeCommit: () -> Unit): AssetMutationReceipt =
        NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            val digest = AssetCommandFingerprint.reorder(ref, revision, assetIds, commandId)
            val prior = receipt(db, ref, commandId)
            if (prior != null) {
                if (prior.first != digest) throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
                return@transaction prior.second
            }
            if (revision != summary.revision) return@transaction reject(db, commandId, ref, AssetMutationKind.REORDER,
                AssetMutationStatus.STALE_REVISION, summary.revision, null, digest)
            if (assetIds.size != summary.assets.size || assetIds.toSet() != summary.assets.map { it.id }.toSet())
                return@transaction reject(db, commandId, ref, AssetMutationKind.REORDER,
                    AssetMutationStatus.UNKNOWN_ASSET, summary.revision, null, digest)
            assetIds.forEachIndexed { index, id ->
                db.execSQL("UPDATE assets SET ordinal=? WHERE id=?", arrayOf<Any>(index, id))
            }
            val next = Math.addExact(summary.revision, 1)
            db.execSQL("UPDATE project SET revision=? WHERE singleton=1", arrayOf(next))
            val at = now()
            db.insertOrThrow("asset_revisions", null, ContentValues().apply {
                put("revision", next); put("kind", AssetMutationKind.REORDER.name); putNull("asset_id")
                put("payload", buildJsonObject { put("ids", assetIds.joinToString(",")) }.toString())
                put("instance_id", ref.instanceId.value); put("generation", ref.generation.value)
                put("command_id", commandId.value); put("committed_at", at)
            })
            db.insertOrThrow("asset_receipts", null, ContentValues().apply {
                put("instance_id", ref.instanceId.value); put("generation", ref.generation.value)
                put("command_id", commandId.value); put("digest", digest)
                put("outcome", AssetMutationStatus.COMMITTED.name); put("revision", next)
                putNull("asset_id"); put("kind", AssetMutationKind.REORDER.name); put("committed_at", at)
            })
            beforeCommit()
            AssetMutationReceipt(commandId, ref, AssetMutationKind.REORDER, AssetMutationStatus.COMMITTED,
                next, null, at)
        }

    fun setEnabled(db: SQLiteDatabase, commandId: CommandId, ref: LocalProjectRef, revision: Long,
                   assetId: Long, enabled: Boolean, beforeCommit: () -> Unit): AssetMutationReceipt =
        NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            val digest = AssetCommandFingerprint.enabled(ref, revision, assetId, enabled, commandId)
            val previous = receipt(db, ref, commandId)
            if (previous != null) {
                if (previous.first != digest) fail(TextProjectException.Reason.COMMAND_CONFLICT)
                return@transaction previous.second
            }
            if (revision != summary.revision) return@transaction reject(db, commandId, ref, AssetMutationKind.ENABLED,
                AssetMutationStatus.STALE_REVISION, summary.revision, assetId, digest)
            if (summary.assets.none { it.id == assetId }) return@transaction reject(db, commandId, ref,
                AssetMutationKind.ENABLED, AssetMutationStatus.UNKNOWN_ASSET, summary.revision, assetId, digest)
            check(db.update("assets", ContentValues().apply { put("enabled", if (enabled) 1 else 0) },
                "id=?", arrayOf(assetId.toString())) == 1)
            val next = Math.addExact(summary.revision, 1)
            val at = now()
            db.execSQL("UPDATE project SET revision=? WHERE singleton=1", arrayOf(next))
            db.insertOrThrow("asset_revisions", null, ContentValues().apply {
                put("revision", next); put("kind", AssetMutationKind.ENABLED.name); put("asset_id", assetId)
                put("payload", buildJsonObject { put("enabled", enabled) }.toString())
                put("instance_id", ref.instanceId.value); put("generation", ref.generation.value)
                put("command_id", commandId.value); put("committed_at", at)
            })
            db.insertOrThrow("asset_receipts", null, ContentValues().apply {
                put("instance_id", ref.instanceId.value); put("generation", ref.generation.value)
                put("command_id", commandId.value); put("digest", digest)
                put("outcome", AssetMutationStatus.COMMITTED.name); put("revision", next); put("asset_id", assetId)
                put("kind", AssetMutationKind.ENABLED.name); put("committed_at", at)
            })
            beforeCommit()
            AssetMutationReceipt(commandId, ref, AssetMutationKind.ENABLED, AssetMutationStatus.COMMITTED, next, assetId, at)
        }

    fun reset(db: SQLiteDatabase, commandId: CommandId, ref: LocalProjectRef, revision: Long,
              assetId: Long, beforeCommit: () -> Unit): AssetMutationReceipt =
        NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            val digest = AssetCommandFingerprint.reset(ref, revision, assetId, commandId)
            val prior = receipt(db, ref, commandId)
            if (prior != null) {
                if (prior.first != digest) throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
                return@transaction prior.second
            }
            if (revision != summary.revision) return@transaction reject(db, commandId, ref, AssetMutationKind.RESET,
                AssetMutationStatus.STALE_REVISION, summary.revision, assetId, digest)
            if (assetId !in summary.assets.map { it.id }) return@transaction reject(db, commandId, ref,
                AssetMutationKind.RESET, AssetMutationStatus.UNKNOWN_ASSET, summary.revision, assetId, digest)
            val targets = db.rawQuery("SELECT ${NativeProjectDatabase.ITEM_COLUMNS} FROM items WHERE asset_id=? " +
                "AND (translation != '' OR status != 'NONE') ORDER BY slot,id", arrayOf(assetId.toString())).use { cursor ->
                buildList { while (cursor.moveToNext()) add(NativeProjectDatabase.item(cursor)) }
            }
            if (targets.size > 10_000) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            var next = summary.revision
            for (item in targets) {
                val itemReceipt = ProjectWriteStore.edit(db, ManualTextEdit(
                    CommandId(commandId.value + ":reset:" + item.id.value), ref, item.id, next,
                    item.fingerprint, "", TextItemStatus.NONE)) { beforeCommit() }
                if (itemReceipt.status != ManualEditStatus.COMMITTED) fail(TextProjectException.Reason.STALE_PAGE)
                next = itemReceipt.revision
            }
            if (targets.isEmpty()) {
                next = Math.addExact(next, 1)
                db.execSQL("UPDATE project SET revision=? WHERE singleton=1", arrayOf(next))
                db.insertOrThrow("asset_revisions", null, ContentValues().apply {
                    put("revision", next); put("kind", AssetMutationKind.RESET.name); put("asset_id", assetId)
                    put("payload", "{}"); put("instance_id", ref.instanceId.value)
                    put("generation", ref.generation.value); put("command_id", commandId.value)
                    put("committed_at", now())
                })
            }
            val at = now()
            db.insertOrThrow("asset_receipts", null, ContentValues().apply {
                put("instance_id", ref.instanceId.value); put("generation", ref.generation.value)
                put("command_id", commandId.value); put("digest", digest)
                put("outcome", AssetMutationStatus.COMMITTED.name); put("revision", next)
                put("asset_id", assetId); put("kind", AssetMutationKind.RESET.name); put("committed_at", at)
            })
            beforeCommit()
            AssetMutationReceipt(commandId, ref, AssetMutationKind.RESET, AssetMutationStatus.COMMITTED,
                next, assetId, at)
        }

    fun editSettings(db: SQLiteDatabase, commandId: CommandId, ref: LocalProjectRef, revision: Long,
                     settings: ProjectSettings, beforeCommit: () -> Unit): AssetMutationReceipt =
        NativeProjectDatabase.transaction(db) {
            val summary = NativeProjectDatabase.summary(db, ref)
            val digest = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(buildJsonObject {
                put("format", "project-settings-v1"); put("command", commandId.value)
                put("project", ref.projectId.value); put("instance", ref.instanceId.value)
                put("generation", ref.generation.value); put("revision", revision)
                put("settings", settings.fingerprint().sha256)
            }.toString()))
            val prior = receipt(db, ref, commandId)
            if (prior != null) {
                if (prior.first != digest) throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
                return@transaction prior.second
            }
            if (revision != summary.revision) return@transaction reject(db, commandId, ref, AssetMutationKind.SETTINGS,
                AssetMutationStatus.STALE_REVISION, summary.revision, null, digest)
            ProjectPageTranslationPolicy(settings, summary.assets)
            db.execSQL("UPDATE project_settings SET payload=?,fingerprint=? WHERE singleton=1",
                arrayOf(ProjectSettingsCodec.encode(settings), settings.fingerprint().sha256))
            val next = Math.addExact(summary.revision, 1)
            db.execSQL("UPDATE project SET revision=? WHERE singleton=1", arrayOf(next))
            val at = now()
            db.insertOrThrow("asset_revisions", null, ContentValues().apply {
                put("revision", next); put("kind", AssetMutationKind.SETTINGS.name); putNull("asset_id")
                put("payload", ProjectSettingsCodec.encode(settings)); put("instance_id", ref.instanceId.value)
                put("generation", ref.generation.value); put("command_id", commandId.value); put("committed_at", at)
            })
            db.insertOrThrow("asset_receipts", null, ContentValues().apply {
                put("instance_id", ref.instanceId.value); put("generation", ref.generation.value)
                put("command_id", commandId.value); put("digest", digest)
                put("outcome", AssetMutationStatus.COMMITTED.name); put("revision", next)
                putNull("asset_id"); put("kind", AssetMutationKind.SETTINGS.name); put("committed_at", at)
            })
            beforeCommit()
            AssetMutationReceipt(commandId, ref, AssetMutationKind.SETTINGS, AssetMutationStatus.COMMITTED,
                next, null, at)
        }

    fun receipt(db: SQLiteDatabase, ref: LocalProjectRef, commandId: CommandId): Pair<String, AssetMutationReceipt>? {
        ProjectWriteStore.rejectApprovedItemCommand(db, ref, commandId)
        ProjectWriteStore.rejectApprovedPdfPageCommand(db, ref, commandId)
        return db.rawQuery("""SELECT digest,outcome,revision,asset_id,kind,committed_at FROM asset_receipts
            WHERE instance_id=? AND generation=? AND command_id=?""",
            arrayOf(ref.instanceId.value, ref.generation.value.toString(), commandId.value)).use {
            if (!it.moveToFirst()) null else it.getString(0) to AssetMutationReceipt(commandId, ref,
                AssetMutationKind.valueOf(it.getString(4)), AssetMutationStatus.valueOf(it.getString(1)),
                it.getLong(2), if (it.isNull(3)) null else it.getLong(3), it.getLong(5))
        }
    }

    private fun reject(db: SQLiteDatabase, commandId: CommandId, ref: LocalProjectRef, kind: AssetMutationKind,
                       status: AssetMutationStatus, revision: Long, assetId: Long?, digest: String): AssetMutationReceipt {
        val at = now()
        db.insertOrThrow("asset_receipts", null, ContentValues().apply {
            put("instance_id", ref.instanceId.value); put("generation", ref.generation.value)
            put("command_id", commandId.value); put("digest", digest)
            put("outcome", status.name); put("revision", revision)
            if (assetId == null) putNull("asset_id") else put("asset_id", assetId)
            put("kind", kind.name); put("committed_at", at)
        })
        return AssetMutationReceipt(commandId, ref, kind, status, revision, assetId, at)
    }

    private fun pathExists(db: SQLiteDatabase, path: String): Boolean =
        db.rawQuery("SELECT 1 FROM assets WHERE path=?", arrayOf(path)).use { it.moveToFirst() }

    private fun normalizeOrder(db: SQLiteDatabase) {
        db.rawQuery("SELECT id FROM assets ORDER BY ordinal,id", null).use { cursor ->
            var ordinal = 0
            while (cursor.moveToNext()) {
                db.execSQL("UPDATE assets SET ordinal=? WHERE id=?", arrayOf<Any>(ordinal++, cursor.getLong(0)))
            }
        }
    }

    private fun now(): Long = System.currentTimeMillis().coerceAtLeast(0)
    private fun fail(reason: TextProjectException.Reason): Nothing = throw TextProjectException(reason)
}
