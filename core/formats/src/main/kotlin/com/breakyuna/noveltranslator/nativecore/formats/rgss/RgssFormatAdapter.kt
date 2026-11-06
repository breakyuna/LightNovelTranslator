package com.breakyuna.noveltranslator.nativecore.formats.rgss

import com.breakyuna.noveltranslator.nativecore.domain.BinaryFormatAdapter
import com.breakyuna.noveltranslator.nativecore.domain.FormatDocument
import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWarning
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Adapter-level failure for an RGSS asset that is neither a supported archive nor Scripts.rxdata. */
class RgssFormatException(val reason: Reason, detail: String = "") :
    IllegalArgumentException(
        "RGSS format: $reason" + (if (detail.isEmpty()) "" else " ($detail)"),
    ) {
    enum class Reason { HEADER, SCRIPT, DATA, BINDING, LIMIT, PATH, IO, ENCODING }
}

private const val MAX_UNITS = 100_000
private const val MAX_UNIT_SOURCE = 65_536
private val SCRIPT_FILE_NAMES = setOf("scripts.rxdata", "scripts.rvdata", "scripts.rvdata2")

private fun safeWarningPath(value: String): String =
    if (value.length <= 4_096 && value.none(Char::isISOControl)) value else ""

/**
 * RGSS adapter for the shared format contract.
 *
 * Read accepts either an RGSSAD v1 / RGSSAD v3 archive, a bare Scripts.rxdata byte string, or a bare
 * non-script data file (`Data/<name>.rxdata`, `Data/<name>.rvdata`, `Data/<name>.rvdata2`). Script entries are
 * decoded and ordinary Ruby string literals become `RULE_SKIPPED` units (the rule layer decides
 * whether they are dialogue). Non-script data files are scanned with [RgssDataCodec], which applies
 * an explicit `RPG::` text-slot/event-command whitelist; both sources stay distinguishable through
 * the unit tag (member path) and the `kind` field (`ruby` or `marshal`).
 *
 * Constructs the Ruby scanner cannot prove safe (heredoc, interpolation, percent/regex literals,
 * unknown escapes) reject that script with a warning. Unsupported or ambiguous Marshal data files
 * are skipped with a warning as well. Neither path ever produces a partial rewrite: write failures
 * reject the whole asset.
 */
class RgssFormatAdapter(
    /**
     * Periodic cancellation hook for data-file traversal; the adapter is synchronous, so callers
     * enforce their own deadline by throwing here. Throw a type other than [RgssDataException],
     * otherwise a read treats it as a per-file warning. Script scanning keeps its existing behavior.
     */
    private val checkActive: () -> Unit = {},
) : BinaryFormatAdapter {
    override val format: ProjectAssetFormat = ProjectAssetFormat.RPGMAKER_RGSS

    override fun read(bytes: ByteArray, path: RelativePath): FormatDocument {
        val warnings = ArrayList<FormatWarning>()
        val collector = UnitCollector(warnings)
        val isArchive = isRgssArchive(bytes)
        var archiveVersion = 0
        var entryCount = 0
        var scriptFiles = 0
        var dataFiles = 0
        var dataScanned = 0
        var dataSkipped = 0
        if (isArchive) {
            val archive = RgssArchiveCodec.read(bytes)
            archiveVersion = archive.version
            entryCount = archive.entries.size
            val scriptEntries = archive.entries.filter { isScriptPath(it.path) }.sortedBy { it.path }
            val dataEntries = archive.entries
                .filter { isDataFile(it.path) && !isScriptPath(it.path) }
                .sortedBy { it.path }
            dataFiles = dataEntries.size
            if (scriptEntries.isEmpty()) {
                warnings.add(FormatWarning("rgss.no_scripts", "", null, "Archive has no Scripts.rxdata"))
            }
            // Scripts keep their historical first position so script-only archives produce exactly
            // the same unit order as before data-file support was added.
            for (entry in scriptEntries) {
                if (collector.limited) break
                scriptFiles += 1
                addScripts(readScripts(entry.data), entry.path, collector)
            }
            for (entry in dataEntries) {
                if (collector.limited) break
                if (addData(entry.data, entry.path, collector)) dataScanned += 1 else dataSkipped += 1
            }
        } else if (isScriptPath(path.value) && isBareScripts(bytes)) {
            entryCount = 1
            scriptFiles = 1
            addScripts(readScripts(bytes), path.value, collector)
        } else if (isDataFile(path.value) && !isScriptPath(path.value) && isBareScripts(bytes)) {
            entryCount = 1
            dataFiles = 1
            if (addData(bytes, path.value, collector)) dataScanned = 1 else dataSkipped = 1
        } else if (isBareScripts(bytes)) {
            // Preserve the pre-existing fallback: a Marshal 4.8 byte string with an unrelated path
            // was (and still is) interpreted as a bare Scripts payload.
            entryCount = 1
            scriptFiles = 1
            addScripts(readScripts(bytes), path.value, collector)
        } else {
            throw RgssFormatException(RgssFormatException.Reason.HEADER)
        }
        if (collector.limited) {
            warnings.add(
                FormatWarning("rgss.unit_limit", "", null, "RGSS unit limit reached"),
            )
        }
        val engine = when {
            !isArchive -> "RGSS"
            archiveVersion == 3 -> "VX Ace"
            path.value.lowercase().endsWith(".rgss2a") -> "VX"
            else -> "XP"
        }
        val metadata = buildJsonObject {
            put("v", 1)
            put("archive", isArchive)
            put("archive_version", archiveVersion)
            put("entries", entryCount)
            put("script_files", scriptFiles)
            put("data_files", dataFiles)
            put("data_scanned", dataScanned)
            put("data_skipped", dataSkipped)
            put("units", collector.units.size)
        }.toString()
        return FormatDocument(format, collector.units, warnings, engine, metadata)
    }

    override fun write(
        original: ByteArray,
        units: List<FormatUnit>,
        options: FormatWriteOptions,
    ): ByteArray {
        if (units.none { effectiveTarget(it, options) != null }) return original
        if (isRgssArchive(original)) {
            val archive = RgssArchiveCodec.read(original)
            val groups = LinkedHashMap<String, MutableList<FormatUnit>>()
            for (unit in units) {
                if (effectiveTarget(unit, options) == null) continue
                if (!isScriptPath(unit.tag) && !isDataFile(unit.tag)) {
                    throw RgssFormatException(RgssFormatException.Reason.BINDING, "unit tag")
                }
                groups.getOrPut(unit.tag) { ArrayList() }.add(unit)
            }
            if (groups.isEmpty()) return original
            val matched = HashSet<String>()
            var changed = false
            val entries = ArrayList<RgssArchiveEntry>(archive.entries.size)
            for (entry in archive.entries) {
                val group = groups[entry.path]
                if (group == null) {
                    entries.add(entry)
                    continue
                }
                matched.add(entry.path)
                if (isScriptPath(entry.path)) {
                    val scripts = readScripts(entry.data)
                    val updated = applyEdits(scripts, group, options)
                    entries.add(entry.withData(RgssScriptsCodec.write(updated), entry.compressed))
                    changed = true
                } else {
                    val updated = applyDataEdits(entry.data, entry.path, group, options)
                    if (updated.contentEquals(entry.data)) {
                        entries.add(entry)
                    } else {
                        entries.add(entry.withData(updated, entry.compressed))
                        changed = true
                    }
                }
            }
            if (matched.size != groups.size) {
                throw RgssFormatException(RgssFormatException.Reason.BINDING, "unknown entry")
            }
            return if (changed) RgssArchiveCodec.write(RgssArchive(archive.version, entries)) else original
        }
        if (isBareScripts(original)) {
            // The binary adapter API has no path on write; the unit tag carries the original
            // member path emitted by read(), so data and scripts stay distinguishable.
            val dataPath = units.map { it.tag }
                .firstOrNull { isDataFile(it) && !isScriptPath(it) }
            if (dataPath != null) return applyDataEdits(original, dataPath, units, options)
            val scripts = readScripts(original)
            return RgssScriptsCodec.write(applyEdits(scripts, units, options))
        }
        throw RgssFormatException(RgssFormatException.Reason.HEADER)
    }

    private fun readScripts(bytes: ByteArray): RgssScriptsArchive = try {
        RgssScriptsCodec.read(bytes)
    } catch (failure: RgssScriptsException) {
        throw RgssFormatException(RgssFormatException.Reason.SCRIPT, failure.reason.name)
    }

    private fun addScripts(archive: RgssScriptsArchive, tag: String, collector: UnitCollector) {
        for ((scriptIndex, script) in archive.scripts.withIndex()) {
            if (collector.limited) return
            val result = try {
                RgssRubyStrings.scan(script.code)
            } catch (failure: RubySourceException) {
                collector.warnings.add(
                    FormatWarning(
                        "rgss.ruby_unsupported",
                        safeWarningPath(tag),
                        null,
                        "Ruby source rejected: ${failure.reason}",
                    ),
                )
                continue
            }
            for (literal in result.literals) {
                if (collector.limited) return
                if (literal.value.isEmpty()) continue
                collector.add(tag, literal.value, locator(script.id, scriptIndex, literal))
            }
        }
    }

    /** Returns true when the data file was scanned (even if the whitelist produced no units). */
    private fun addData(data: ByteArray, tag: String, collector: UnitCollector): Boolean {
        val scan = try {
            RgssDataCodec.scan(data, tag, checkActive = checkActive)
        } catch (failure: RgssDataException) {
            collector.warnings.add(
                FormatWarning(
                    dataWarningCode(failure.reason),
                    safeWarningPath(tag),
                    null,
                    "Marshal data file rejected: ${failure.reason}",
                ),
            )
            return false
        }
        if (scan.ambiguousEncoding) {
            collector.warnings.add(
                FormatWarning(
                    "rgss.data_encoding",
                    safeWarningPath(tag),
                    null,
                    "Marshal data encoding cannot be proven",
                ),
            )
            return false
        }
        for (string in scan.strings) {
            if (collector.limited) return true
            collector.add(tag, string.source, dataLocator(string))
        }
        return true
    }

    private fun dataWarningCode(reason: RgssDataException.Reason): String = when (reason) {
        RgssDataException.Reason.ENCODING -> "rgss.data_encoding"
        RgssDataException.Reason.LIMIT -> "rgss.data_limit"
        else -> "rgss.data_unsupported"
    }

    private fun applyEdits(
        archive: RgssScriptsArchive,
        units: List<FormatUnit>,
        options: FormatWriteOptions,
    ): RgssScriptsArchive {
        val edits = LinkedHashMap<Int, MutableList<RubyReplacement>>()
        for (unit in units) {
            val edit = parseEdit(unit, options) ?: continue
            edits.getOrPut(edit.first) { ArrayList() }.add(edit.second)
        }
        if (edits.isEmpty()) return archive
        for (id in edits.keys) {
            if (archive.script(id) == null) {
                throw RgssFormatException(RgssFormatException.Reason.BINDING, "script id")
            }
        }
        val scripts = archive.scripts.map { script ->
            val replacements = edits[script.id] ?: return@map script
            val code = try {
                RgssRubyStrings.rewrite(script.code, replacements)
            } catch (failure: RubySourceException) {
                throw RgssFormatException(RgssFormatException.Reason.BINDING, "script ${script.id}")
            }
            if (code == script.code) script else script.withCode(code)
        }
        return RgssScriptsArchive(scripts)
    }

    /**
     * Applies one data file's units with [RgssDataCodec]. Every edit is validated against a fresh
     * parse of [data]; any mismatch throws before the caller adds the entry, so no partial archive
     * (and no partially rewritten Marshal file) can be produced.
     */
    private fun applyDataEdits(
        data: ByteArray,
        tag: String,
        units: List<FormatUnit>,
        options: FormatWriteOptions,
    ): ByteArray {
        val edits = ArrayList<RgssDataEdit>()
        for (unit in units) {
            val target = effectiveTarget(unit, options) ?: continue
            if (unit.tag != tag) {
                throw RgssFormatException(RgssFormatException.Reason.BINDING, "unit tag")
            }
            val extra = readExtra(unit)
            if ((extra["kind"] as? JsonPrimitive)?.contentOrNull != "marshal") {
                throw RgssFormatException(RgssFormatException.Reason.BINDING, "kind")
            }
            val node = (extra["node"] as? JsonPrimitive)?.intOrNull
                ?: throw RgssFormatException(RgssFormatException.Reason.BINDING, "node")
            val parent = (extra["parent"] as? JsonPrimitive)?.contentOrNull
                ?: throw RgssFormatException(RgssFormatException.Reason.BINDING, "parent")
            if (parent.length > 4_096) {
                throw RgssFormatException(RgssFormatException.Reason.BINDING, "parent")
            }
            val encoding = (extra["encoding"] as? JsonPrimitive)?.contentOrNull
                ?: throw RgssFormatException(RgssFormatException.Reason.BINDING, "encoding")
            if (encoding != RGSS_DATA_ENCODING_UTF8 && encoding != RGSS_DATA_ENCODING_SHIFT_JIS) {
                throw RgssFormatException(RgssFormatException.Reason.BINDING, "encoding")
            }
            val digest = (extra["digest"] as? JsonPrimitive)?.contentOrNull
                ?: throw RgssFormatException(RgssFormatException.Reason.BINDING, "digest")
            if (digest.length != 64 || digest.any { it !in "0123456789abcdef" }) {
                throw RgssFormatException(RgssFormatException.Reason.BINDING, "digest")
            }
            val replacement = if (options.bilingual) unit.source + "\n" + target else target
            try {
                edits.add(
                    RgssDataEdit(
                        node,
                        parent,
                        unit.source,
                        digest,
                        encoding,
                        replacement,
                    ),
                )
            } catch (_: IllegalArgumentException) {
                throw RgssFormatException(RgssFormatException.Reason.BINDING, "data locator")
            }
        }
        if (edits.isEmpty()) return data
        return try {
            RgssDataCodec.rewrite(data, tag, edits, checkActive = checkActive)
        } catch (failure: RgssDataException) {
            throw RgssFormatException(failure.reason.toFormatReason(), failure.reason.name)
        }
    }

    private fun readExtra(unit: FormatUnit): JsonObject = try {
        Json.parseToJsonElement(unit.extraField) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: throw RgssFormatException(RgssFormatException.Reason.BINDING, "extra")

    private fun parseEdit(unit: FormatUnit, options: FormatWriteOptions): Pair<Int, RubyReplacement>? {
        val target = effectiveTarget(unit, options) ?: return null
        val extra = readExtra(unit)
        if ((extra["kind"] as? JsonPrimitive)?.contentOrNull != "ruby") {
            throw RgssFormatException(RgssFormatException.Reason.BINDING, "kind")
        }
        val scriptId = (extra["script_id"] as? JsonPrimitive)?.intOrNull
            ?: throw RgssFormatException(RgssFormatException.Reason.BINDING, "script id")
        val start = (extra["start"] as? JsonPrimitive)?.intOrNull
            ?: throw RgssFormatException(RgssFormatException.Reason.BINDING, "start")
        val end = (extra["end"] as? JsonPrimitive)?.intOrNull
            ?: throw RgssFormatException(RgssFormatException.Reason.BINDING, "end")
        val quoteText = (extra["quote"] as? JsonPrimitive)?.contentOrNull
            ?: throw RgssFormatException(RgssFormatException.Reason.BINDING, "quote")
        if (quoteText.length != 1) {
            throw RgssFormatException(RgssFormatException.Reason.BINDING, "quote")
        }
        val digest = (extra["digest"] as? JsonPrimitive)?.contentOrNull
            ?: throw RgssFormatException(RgssFormatException.Reason.BINDING, "digest")
        if (digest.length != 64) {
            throw RgssFormatException(RgssFormatException.Reason.BINDING, "digest")
        }
        val replacement = if (options.bilingual) unit.source + "\n" + target else target
        return try {
            scriptId to RubyReplacement(
                start,
                end,
                quoteText[0],
                unit.source,
                replacement,
                digest,
            )
        } catch (_: IllegalArgumentException) {
            throw RgssFormatException(RgssFormatException.Reason.BINDING, "locator")
        }
    }

    private fun effectiveTarget(unit: FormatUnit, options: FormatWriteOptions): String? {
        if (unit.status != TextItemStatus.PROCESSED) return null
        val target = unit.translation
        if (!options.bilingual) return if (target == unit.source) null else target
        if (target.isEmpty()) return null
        if (options.deduplicateSame && target == unit.source) return null
        return target
    }

    private fun locator(scriptId: Int, scriptIndex: Int, literal: RubyStringLiteral): String =
        buildJsonObject {
            put("v", 1)
            put("kind", "ruby")
            put("script_id", scriptId)
            put("script_index", scriptIndex)
            put("start", literal.start)
            put("end", literal.end)
            put("quote", literal.quote.toString())
            put("digest", RgssRubyStrings.sha256Hex(literal.raw))
        }.toString()

    private fun dataLocator(value: RgssDataString): String =
        buildJsonObject {
            put("v", 1)
            put("kind", "marshal")
            put("node", value.node)
            put("parent", value.path)
            put("encoding", value.encoding)
            put("digest", value.digest)
        }.toString()

    private fun isRgssArchive(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes[0].toInt() == 0x52 && bytes[1].toInt() == 0x47 &&
            bytes[2].toInt() == 0x53 && bytes[3].toInt() == 0x53 && bytes[4].toInt() == 0x41 &&
            bytes[5].toInt() == 0x44 && bytes[6].toInt() == 0x00

    private fun isBareScripts(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0].toInt() == 4 && bytes[1].toInt() == 8

    private fun isScriptPath(path: String): Boolean =
        path.substringAfterLast('/').lowercase() in SCRIPT_FILE_NAMES

    private fun isDataFile(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        return name.endsWith(".rxdata") || name.endsWith(".rvdata") || name.endsWith(".rvdata2")
    }

    private class UnitCollector(val warnings: MutableList<FormatWarning>) {
        val units = ArrayList<FormatUnit>()
        var limited = false
        private var warned = false

        fun add(tag: String, source: String, extra: String) {
            if (source.isEmpty()) return
            if (source.length > MAX_UNIT_SOURCE) {
                if (!warned) {
                    warned = true
                    warnings.add(
                        FormatWarning("rgss.unit_too_large", safeWarningPath(tag), null, "A string exceeds the unit bound"),
                    )
                }
                return
            }
            if (units.size >= MAX_UNITS) {
                limited = true
                return
            }
            units.add(
                FormatUnit(
                    units.size,
                    source,
                    "",
                    TextItemStatus.RULE_SKIPPED,
                    tag,
                    extraField = extra,
                ),
            )
        }
    }

}

private fun RgssDataException.Reason.toFormatReason(): RgssFormatException.Reason = when (this) {
    RgssDataException.Reason.MARSHAL, RgssDataException.Reason.STRUCTURE -> RgssFormatException.Reason.DATA
    RgssDataException.Reason.ENCODING -> RgssFormatException.Reason.ENCODING
    RgssDataException.Reason.LIMIT -> RgssFormatException.Reason.LIMIT
    RgssDataException.Reason.BINDING -> RgssFormatException.Reason.BINDING
}
