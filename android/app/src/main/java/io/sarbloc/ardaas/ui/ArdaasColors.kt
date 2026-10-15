package io.sarbloc.ardaas.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Brand palette, ported value-for-value from iOS `Theme.swift`: a deep navy
 * field with a kesri (saffron) accent, taken from the app icon. The values are
 * pinned by `ArdaasColorsTest` so the two apps cannot drift apart.
 */
object ArdaasColors {
    // Surfaces: the icon's gradient, top to bottom.
    val NavyTop = Color(0xFF18264A)
    val NavyBottom = Color(0xFF0C1328)

    /** Kesri accent — the iOS `AccentColor`. */
    val Kesri = Color(0xFFFF9933)

    // Reading layers. Load-bearing, not decoration: the hue is how a reader
    // tells the layers apart at a glance while reciting.
    /** Gurmukhi — warm parchment white. */
    val Parchment = Color(0xFFF2EADB)

    /** Transliteration — muted sand gold. */
    val Sand = Color(0xFFD9A868)

    /** Translation — cool mist blue-grey. */
    val Mist = Color(0xFF93A0BC)

    /** Row/card fill that reads as "raised" on the navy gradient. */
    val RaisedFill = Color.White.copy(alpha = 0.06f)

    /** The screen background: [NavyTop] fading vertically into [NavyBottom]. */
    val BackgroundGradient: Brush = Brush.verticalGradient(listOf(NavyTop, NavyBottom))
}
