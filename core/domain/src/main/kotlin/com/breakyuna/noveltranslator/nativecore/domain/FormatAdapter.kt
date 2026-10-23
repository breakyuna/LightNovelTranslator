package com.breakyuna.noveltranslator.nativecore.domain

import java.util.Collections

/** Frozen output policy for one asset export. Never read from mutable application settings. */
class FormatWriteOptions(
    val bilingual: Boolean = false,
    val deduplicateSame: Boolean = true,
    val writeTranslatedNames: Boolean = false,
    val targetLanguage: String = "",
) {
    init {
        require(targetLanguage.length <= 80 && targetLanguage.none(Char::isISOControl))
        TextProjectFingerprint.utf8(targetLanguage)
    }
    override fun toString(): String = "FormatWriteOptions(redacted)"
}

class FormatWarning(
    val code: String,
    val path: String,
    val line: Int? = null,
    val message: String,
) {
    init {
        require(code.length <= 64 && path.length <= 4_096 && message.length <= 1_024)
        require(path.none(Char::isISOControl) && message.none(Char::isISOControl))
    }
    override fun toString(): String = "FormatWarning(code=$code,line=$line)"
}

/**
 * One format unit as produced by an adapter. [tag] is the format-private location (internal
 * archive path, engine file key or script path); [extraField] carries layout data needed to
 * rebuild the untouched structure. Bodies deliberately have no generated toString().
 */
class FormatUnit(
    val row: Int,
    val source: String,
    val translation: String = "",
    val status: TextItemStatus = TextItemStatus.NONE,
    val tag: String = "",
    nameSource: List<String>? = null,
    nameTranslation: List<String>? = null,
    val extraField: String = "{}",
    val skipInternalFilter: Boolean = false,
) {
    val nameSource: List<String>? = nameSource?.let { Collections.unmodifiableList(ArrayList(it)) }
    val nameTranslation: List<String>? = nameTranslation?.let { Collections.unmodifiableList(ArrayList(it)) }
    init {
        require(row >= 0 && source.length <= 65_536 && translation.length <= 65_536)
        require(tag.length <= 4_096 && tag.none(Char::isISOControl))
        require(extraField.length <= 65_536)
        require('\u0000' !in source && '\u0000' !in translation && '\u0000' !in extraField)
        TextProjectFingerprint.utf8(source)
        TextProjectFingerprint.utf8(translation)
        TextProjectFingerprint.utf8(extraField)
        JsonObjectValue.parse(extraField, JsonInputLimits(65_536, 32))
        for (name in (this.nameSource ?: emptyList()) + (this.nameTranslation ?: emptyList())) {
            require(name.length <= 4_096 && '\u0000' !in name)
            TextProjectFingerprint.utf8(name)
        }
    }
    override fun toString(): String = "FormatUnit(redacted)"
}

/** Immutable adapter result. Units preserve the adapter's source order. */
class FormatDocument(
    val format: ProjectAssetFormat,
    units: List<FormatUnit>,
    warnings: List<FormatWarning> = emptyList(),
    val gameEngine: String = "",
    val metadata: String = "{}",
) {
    val units: List<FormatUnit> = Collections.unmodifiableList(ArrayList(units))
    val warnings: List<FormatWarning> = Collections.unmodifiableList(ArrayList(warnings))
    init {
        require(this.units.size <= 100_000)
        require(gameEngine.length <= 64 && gameEngine.none(Char::isISOControl))
        require(metadata.length <= 65_536)
        TextProjectFingerprint.utf8(metadata)
        JsonObjectValue.parse(metadata, JsonInputLimits(65_536, 32))
    }
    override fun toString(): String = "FormatDocument(format=$format,units=${units.size})"
}

/** Pure text adapter; the caller owns decoding/encoding and project persistence. */
interface TextFormatAdapter {
    val format: ProjectAssetFormat
    fun read(text: String, path: RelativePath): FormatDocument
    fun write(original: String, units: List<FormatUnit>, options: FormatWriteOptions): String
}

/** Container adapter (ZIP/PDF/RGSS/directory image); all bytes stay inside the adapter call. */
interface BinaryFormatAdapter {
    val format: ProjectAssetFormat
    fun read(bytes: ByteArray, path: RelativePath): FormatDocument
    fun write(original: ByteArray, units: List<FormatUnit>, options: FormatWriteOptions): ByteArray
}

/** Adapter lookup is explicit so an unavailable format can never be advertised as writable. */
object FormatAdapterRegistry {
    private val text = LinkedHashMap<ProjectAssetFormat, TextFormatAdapter>()
    private val binary = LinkedHashMap<ProjectAssetFormat, BinaryFormatAdapter>()

    @Synchronized
    fun register(adapter: TextFormatAdapter) { text[adapter.format] = adapter }

    @Synchronized
    fun register(adapter: BinaryFormatAdapter) { binary[adapter.format] = adapter }

    @Synchronized
    fun text(format: ProjectAssetFormat): TextFormatAdapter? = text[format]

    @Synchronized
    fun binary(format: ProjectAssetFormat): BinaryFormatAdapter? = binary[format]

    @Synchronized
    fun supported(): Set<ProjectAssetFormat> = text.keys + binary.keys
}
