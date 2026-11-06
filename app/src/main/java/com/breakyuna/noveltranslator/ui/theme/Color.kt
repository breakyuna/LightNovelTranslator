package com.breakyuna.noveltranslator.ui.theme

import androidx.compose.ui.graphics.Color

/* ============================================================================
 * Novel Translator — Design System: Color Tokens
 *
 * One tonal palette drives every surface. Light uses warm paper neutrals so a
 * full bookshelf reads like printed covers; dark uses layered "night" neutrals
 * with a cool tint instead of pure black. Brand hues are deliberately calm:
 * an ink blue for primary, a deep teal for secondary, a warm amber for accents.
 * Screens must reference these tokens (or MaterialTheme.colorScheme) instead of
 * hard-coding Color(0xFF...) values.
 * ========================================================================== */

// ---------------------------------------------------------------------------
// Brand — Ink Blue (primary)
// ---------------------------------------------------------------------------
val InkBlue10 = Color(0xFF001544)
val InkBlue20 = Color(0xFF002A85)
val InkBlue30 = Color(0xFF0B3FB8)
val InkBlue40 = Color(0xFF2A55D6)
val InkBlue50 = Color(0xFF3B6BE0)
val InkBlue60 = Color(0xFF6B93EC)
val InkBlue70 = Color(0xFF9BB8F4)
val InkBlue80 = Color(0xFFC4D6FA)
val InkBlue90 = Color(0xFFE2EBFF)
val InkBlue95 = Color(0xFFF0F5FF)

// ---------------------------------------------------------------------------
// Brand — Deep Teal (secondary)
// ---------------------------------------------------------------------------
val Teal10 = Color(0xFF00251F)
val Teal20 = Color(0xFF00382F)
val Teal30 = Color(0xFF005246)
val Teal40 = Color(0xFF00715F)
val Teal50 = Color(0xFF0E9384)
val Teal60 = Color(0xFF46B8A8)
val Teal70 = Color(0xFF77D6C7)
val Teal80 = Color(0xFFB5EDE1)
val Teal90 = Color(0xFFDCF5EE)

// ---------------------------------------------------------------------------
// Brand — Warm Amber (tertiary / accents)
// ---------------------------------------------------------------------------
val Amber10 = Color(0xFF2A1A00)
val Amber20 = Color(0xFF452B00)
val Amber30 = Color(0xFF663F00)
val Amber40 = Color(0xFF8A5A00)
val Amber50 = Color(0xFFB57A10)
val Amber60 = Color(0xFFE0A030)
val Amber70 = Color(0xFFF2BE5C)
val Amber80 = Color(0xFFFBD694)
val Amber90 = Color(0xFFFFEFD4)

// ---------------------------------------------------------------------------
// Semantic — success / warning / error (light)
// ---------------------------------------------------------------------------
val Green10 = Color(0xFF04220F)
val Green20 = Color(0xFF083D1B)
val Green30 = Color(0xFF0F5B29)
val Green40 = Color(0xFF17843C)
val Green50 = Color(0xFF22A355)
val Green60 = Color(0xFF5CC480)
val Green70 = Color(0xFF93DBA9)
val Green80 = Color(0xFFC2EBD0)
val Green90 = Color(0xFFE4F6E9)

val Red10 = Color(0xFF410E0B)
val Red20 = Color(0xFF601410)
val Red30 = Color(0xFF8C1D18)
val Red40 = Color(0xFFB3261E)
val Red50 = Color(0xFFDC362E)
val Red60 = Color(0xFFE96961)
val Red70 = Color(0xFFF2A19A)
val Red80 = Color(0xFFF9CFC9)
val Red90 = Color(0xFFFCEDEB)

val Orange10 = Color(0xFF301E00)
val Orange20 = Color(0xFF4A2E00)
val Orange30 = Color(0xFF5C3A00)
val Orange40 = Color(0xFF8A5A00)
val Orange50 = Color(0xFFD98A04)
val Orange60 = Color(0xFFF2A93B)
val Orange70 = Color(0xFFFBC86E)
val Orange80 = Color(0xFFFDE3AE)
val Orange90 = Color(0xFFFFF3DC)

// ---------------------------------------------------------------------------
// Neutrals — light (warm paper) & dark (layered night)
// ---------------------------------------------------------------------------
val PaperBackground = Color(0xFFF7F7F5)
val PaperSurface = Color(0xFFFFFFFF)
val PaperSurfaceLowest = Color(0xFFFFFFFF)
val PaperSurfaceLow = Color(0xFFF2F1ED)
val PaperSurfaceContainer = Color(0xFFEDECE7)
val PaperSurfaceHigh = Color(0xFFE7E6E0)
val PaperSurfaceHighest = Color(0xFFE1E0D9)
val PaperOnSurface = Color(0xFF1A1C1E)
val PaperOnSurfaceVariant = Color(0xFF5A5F66)
val PaperOutline = Color(0xFFD8D9D2)
val PaperOutlineVariant = Color(0xFFE7E7E0)

val NightBackground = Color(0xFF0B0E13)
val NightSurface = Color(0xFF11151C)
val NightSurfaceLowest = Color(0xFF080B10)
val NightSurfaceLow = Color(0xFF161B24)
val NightSurfaceContainer = Color(0xFF1B212C)
val NightSurfaceHigh = Color(0xFF232A36)
val NightSurfaceHighest = Color(0xFF2C3441)
val NightOnSurface = Color(0xFFE6E9EE)
val NightOnSurfaceVariant = Color(0xFFA2ABB8)
val NightOutline = Color(0xFF39414E)
val NightOutlineVariant = Color(0xFF242B36)

// ---------------------------------------------------------------------------
// Reader palettes (immersive backgrounds, independent from app theme)
// ---------------------------------------------------------------------------
val ReaderPaperBackground = Color(0xFFFBF3E2)
val ReaderPaperOnBackground = Color(0xFF3A3125)
val ReaderPaperSurface = Color(0xFFF3E9D2)

val ReaderMintBackground = Color(0xFFE9F3EA)
val ReaderMintOnBackground = Color(0xFF1E3A26)
val ReaderMintSurface = Color(0xFFD9E9DC)

val ReaderNightBackground = Color(0xFF101318)
val ReaderNightOnBackground = Color(0xFFD8DCE3)
val ReaderNightSurface = Color(0xFF1A1F27)

val ReaderSepiaBackground = Color(0xFFF6ECD9)
val ReaderSepiaOnBackground = Color(0xFF3D3320)
val ReaderSepiaSurface = Color(0xFFEFE2C8)

val ReaderSlateBackground = Color(0xFF1C2330)
val ReaderSlateOnBackground = Color(0xFFDCE3EE)
val ReaderSlateSurface = Color(0xFF273042)

val ReaderAmoledBackground = Color(0xFF000000)
val ReaderAmoledOnBackground = Color(0xFFD8DCE3)
val ReaderAmoledSurface = Color(0xFF101318)

/** Terminal-style console colors for the run/log surfaces. */
val ConsoleBackground = Color(0xFF14161C)
val ConsoleBackgroundLight = Color(0xFF1B1F27)
val ConsoleSurface = Color(0xFF0E1014)
val ConsoleOnSurface = Color(0xFFE4E7EC)
val ConsoleOnSurfaceVariant = Color(0xFF9AA3B2)
val ConsoleSuccess = Color(0xFF69DF94)
val ConsoleInfo = Color(0xFF62B6FF)
val ConsoleWarning = Color(0xFFFFC53D)
val ConsoleError = Color(0xFFFF6B5E)
val ConsoleAccent = Color(0xFFD0A6FF)

// ===========================================================================
// Compatibility aliases — existing references across the app.
// ===========================================================================
val AccentBlue = InkBlue50
val AccentBlueDark = InkBlue70
val AccentBlueContainerLight = InkBlue90
val AccentBlueContainerDark = InkBlue30

val StatusSuccess = Green50
val StatusSuccessContainer = Green90
val StatusSuccessContainerDark = Green20

val StatusWarning = Orange50
val StatusWarningContainer = Orange90
val StatusWarningContainerDark = Orange20

val StatusError = Red50
val StatusErrorContainer = Red90
val StatusErrorContainerDark = Red20

val PrimaryIndigo = InkBlue50
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = InkBlue90
val OnPrimaryContainer = InkBlue20

val SecondaryCyan = Teal50
val OnSecondary = Color(0xFFFFFFFF)
val SecondaryContainer = Teal90
val OnSecondaryContainer = Teal20

val TertiaryAmber = Amber50
val OnTertiary = Color(0xFFFFFFFF)
val TertiaryContainer = Amber90
val OnTertiaryContainer = Amber20

val PrimaryIndigoDark = InkBlue70
val OnPrimaryDark = InkBlue10
val PrimaryContainerDark = InkBlue30
val OnPrimaryContainerDark = InkBlue80

val SecondaryCyanDark = Teal60
val OnSecondaryDark = Teal10
val SecondaryContainerDark = Teal30
val OnSecondaryContainerDark = Teal80

val TertiaryAmberDark = Amber60
val OnTertiaryDark = Amber10
val TertiaryContainerDark = Amber30
val OnTertiaryContainerDark = Amber80

val EmeraldAccent = StatusSuccess
val RoseAccent = StatusError
val AccentPurple = Color(0xFF7C5CD6)
val AccentTeal = Teal50
val AccentOrange = StatusWarning
val AccentGreen = StatusSuccess

// Legacy reader palette aliases.
val SepiaBackground = ReaderSepiaBackground
val SepiaOnBackground = ReaderSepiaOnBackground
val SepiaSurface = ReaderSepiaSurface

val SlateBackground = ReaderSlateBackground
val SlateOnBackground = ReaderSlateOnBackground
val SlateSurface = ReaderSlateSurface

val MintBackground = ReaderMintBackground
val MintOnBackground = ReaderMintOnBackground
val MintSurface = ReaderMintSurface

val AmoledBackground = ReaderAmoledBackground
val AmoledOnBackground = ReaderAmoledOnBackground
val AmoledSurface = ReaderAmoledSurface
