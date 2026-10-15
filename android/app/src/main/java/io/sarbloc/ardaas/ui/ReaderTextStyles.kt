package io.sarbloc.ardaas.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.sp

/**
 * The three reading layers, each paired with its layer colour exactly as the
 * iOS reader pairs them. Everything else uses the stock Material 3 type scale.
 *
 * Line height is deliberately generous — direct user feedback on iOS — and set
 * explicitly so Material defaults cannot tighten it. iOS uses 22pt Gurmukhi
 * with 8pt extra line spacing and 15pt Latin layers with 5pt extra; on top of
 * San Francisco's natural leading that is roughly 34pt and 23pt per line. The
 * values here meet or exceed that, rounding Gurmukhi up because its vowel
 * signs reach well above and below the headline.
 *
 * Gurmukhi uses the default family: Android falls back to the system's Noto
 * Sans Gurmukhi, so no font file ships with the app.
 */
object ReaderTextStyles {
    val Gurmukhi = TextStyle(
        color = ArdaasColors.Parchment,
        fontFamily = FontFamily.Default,
        fontSize = 22.sp,
        lineHeight = 36.sp,
    )

    val Transliteration = TextStyle(
        color = ArdaasColors.Sand,
        fontFamily = FontFamily.Default,
        fontStyle = FontStyle.Italic,
        fontSize = 15.sp,
        lineHeight = 24.sp,
    )

    val Translation = TextStyle(
        color = ArdaasColors.Mist,
        fontFamily = FontFamily.Serif,
        fontSize = 15.sp,
        lineHeight = 24.sp,
    )
}
