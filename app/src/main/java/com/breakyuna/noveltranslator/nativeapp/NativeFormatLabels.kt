package com.breakyuna.noveltranslator.nativeapp

import androidx.annotation.StringRes
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat

/** Five-language display names; technical format identifiers are never shown raw in the UI. */
@StringRes
internal fun nativeFormatLabelRes(format: ProjectAssetFormat): Int = when (format) {
    ProjectAssetFormat.TXT -> R.string.native_format_txt
    ProjectAssetFormat.MD_V2 -> R.string.native_format_markdown
    ProjectAssetFormat.SRT -> R.string.native_format_srt
    ProjectAssetFormat.VTT -> R.string.native_format_vtt
    ProjectAssetFormat.LRC -> R.string.native_format_lrc
    ProjectAssetFormat.ASS -> R.string.native_format_ass
    ProjectAssetFormat.SSA -> R.string.native_format_ssa
    ProjectAssetFormat.EPUB -> R.string.native_format_epub
    ProjectAssetFormat.XLSX -> R.string.native_format_xlsx
    ProjectAssetFormat.WOLFXLSX -> R.string.native_format_wolf_xlsx
    ProjectAssetFormat.RENPY -> R.string.native_format_renpy
    ProjectAssetFormat.TRANS -> R.string.native_format_trans
    ProjectAssetFormat.RPGMAKER_MV_MZ -> R.string.native_format_rpgmaker_mv_mz
    ProjectAssetFormat.RPGMAKER_RGSS -> R.string.native_format_rpgmaker_rgss
    ProjectAssetFormat.KVJSON -> R.string.native_format_kvjson
    ProjectAssetFormat.MESSAGEJSON -> R.string.native_format_messagejson
    ProjectAssetFormat.PDF -> R.string.native_format_pdf
    ProjectAssetFormat.BINARY -> R.string.native_format_binary
}
