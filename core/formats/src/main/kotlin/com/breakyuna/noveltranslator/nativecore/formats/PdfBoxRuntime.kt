package com.breakyuna.noveltranslator.nativecore.formats

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File

/**
 * Process-wide PDFBox-Android setup and optional Unicode font registration.
 *
 * PDFBox-Android stores its bundled CMaps, glyph lists and Standard-14 metric resources in Android
 * assets, so the application must initialize it once, before the first PDF import:
 *
 * ```kotlin
 * // Application.onCreate()
 * PdfBoxRuntime.initialize(applicationContext)
 * ```
 *
 * The binary adapter API deliberately has no `Context` parameter: it is called from storage and
 * format services, and a missing initialization must not be hidden inside a per-call lookup.
 *
 * Replacement pages need a Unicode-capable TrueType font. By default the adapter probes common
 * system fonts (`/system/fonts`), including the Noto CJK TrueType collection. A device that only
 * exposes fonts PDFBox cannot embed can register a bundled TrueType/OpenType single-font file (or
 * its bytes) once, e.g. from a downloaded or app-bundled asset:
 *
 * ```kotlin
 * PdfBoxRuntime.installFont(fontFile)
 * ```
 *
 * Without any usable font, pages with a `translate` decision fail with
 * [PdfFormatException.Reason.FONT]; `keep` and `omit` output never needs a font.
 */
object PdfBoxRuntime {
    private val lock = Any()
    private var customFontFile: File? = null
    private var customFontBytes: ByteArray? = null

    /**
     * Candidate single-font files, most capable first. `.ttc` collections are handled by the
     * adapter through FontBox; entries that cannot be parsed or do not cover the replacement text
     * are skipped. A Latin-only system font is rejected by the coverage check, so a CJK fallback
     * later in the list can still be selected.
     */
    private val systemFontFiles = listOf(
        "/system/fonts/NotoSansCJK-Regular.ttc",
        "/system/fonts/NotoSerifCJK-Regular.ttc",
        "/system/fonts/MiSansVF.ttf",
        "/system/fonts/NotoSansSC-Regular.otf",
        "/system/fonts/NotoSansTC-Regular.otf",
        "/system/fonts/NotoSansJP-Regular.otf",
        "/system/fonts/NotoSansKR-Regular.otf",
        "/system/fonts/DroidSansFallback.ttf",
        "/system/fonts/NotoSansCJK-Regular.ttf",
    )

    /** Initializes PDFBox-Android resources. Safe to call more than once. */
    fun initialize(context: Context) {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    fun isInitialized(): Boolean = PDFBoxResourceLoader.isReady()

    /** Registers a TrueType/OpenType single-font file used before any system font. */
    fun installFont(file: File) {
        val length = file.length()
        require(file.isFile && length >= 1L && length <= PdfFormatBounds.MAX_FONT_BYTES.toLong()) {
            "Font file is missing, empty or too large"
        }
        synchronized(lock) {
            customFontFile = file
            customFontBytes = null
        }
    }

    /** Registers TrueType/OpenType bytes used before any system font. The array is copied. */
    fun installFont(bytes: ByteArray) {
        require(bytes.size in 4..PdfFormatBounds.MAX_FONT_BYTES) { "Font data is empty or too large" }
        synchronized(lock) {
            customFontBytes = bytes.copyOf()
            customFontFile = null
        }
    }

    fun clearInstalledFont() {
        synchronized(lock) {
            customFontFile = null
            customFontBytes = null
        }
    }

    internal fun fontCandidates(): List<PdfFontCandidate> = synchronized(lock) {
        val result = ArrayList<PdfFontCandidate>(systemFontFiles.size + 1)
        customFontBytes?.let { result.add(PdfFontCandidate(null, it)) }
        customFontFile?.let { result.add(PdfFontCandidate(it, null)) }
        for (path in systemFontFiles) result.add(PdfFontCandidate(File(path), null))
        result
    }
}

/** One font candidate; exactly one of [file] and [bytes] is set. */
internal class PdfFontCandidate(val file: File?, val bytes: ByteArray?)
