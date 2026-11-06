package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat

/**
 * Product scope after the 2026-10-26 decision: EPUB and TXT are the active formats. Other
 * adapters remain in the build so existing projects can still be opened/exported, but new
 * imports are frozen rather than expanded or re-verified.
 */
internal val NativeActiveFormats: Set<ProjectAssetFormat> = setOf(
    ProjectAssetFormat.TXT,
    ProjectAssetFormat.EPUB,
)

internal fun isNativeFormatActive(format: ProjectAssetFormat): Boolean = format in NativeActiveFormats

internal fun isNativeFormatActive(path: String): Boolean =
    isNativeFormatActive(ProjectAssetFormat.detect(path))
