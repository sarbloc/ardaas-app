package io.sarbloc.ardaas.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the palette to iOS `Theme.swift`, value for value. A change here is a
 * change to the brand on one platform only — update both or neither.
 */
class ArdaasColorsTest {

    private fun assertArgb(name: String, expected: Long, actual: Color) {
        assertEquals(name, expected.toInt(), actual.toArgb())
    }

    @Test
    fun paletteMatchesIos() {
        assertArgb("navyTop", 0xFF18264A, ArdaasColors.NavyTop)
        assertArgb("navyBottom", 0xFF0C1328, ArdaasColors.NavyBottom)
        assertArgb("kesri", 0xFFFF9933, ArdaasColors.Kesri)
        assertArgb("parchment", 0xFFF2EADB, ArdaasColors.Parchment)
        assertArgb("sand", 0xFFD9A868, ArdaasColors.Sand)
        assertArgb("mist", 0xFF93A0BC, ArdaasColors.Mist)
    }

    @Test
    fun raisedFillIsWhiteAtSixPercent() {
        val fill = ArdaasColors.RaisedFill
        assertEquals(Color.White.copy(alpha = 1f), fill.copy(alpha = 1f))
        assertEquals(0.06f, fill.alpha, 0.002f)
    }

    @Test
    fun readerLayersKeepTheirColours() {
        assertEquals(ArdaasColors.Parchment, ReaderTextStyles.Gurmukhi.color)
        assertEquals(ArdaasColors.Sand, ReaderTextStyles.Transliteration.color)
        assertEquals(ArdaasColors.Mist, ReaderTextStyles.Translation.color)
    }

    /** Generous leading is user feedback from iOS; Material must not tighten it. */
    @Test
    fun readerLineHeightStaysGenerous() {
        for (style in listOf(ReaderTextStyles.Gurmukhi, ReaderTextStyles.Transliteration, ReaderTextStyles.Translation)) {
            assertTrue(style.lineHeight.isSp && style.fontSize.isSp)
            assertTrue(
                "line height ${style.lineHeight} for ${style.fontSize}",
                style.lineHeight.value / style.fontSize.value >= 1.5f,
            )
        }
    }

    @Test
    fun schemeUsesBrandRoles() {
        val scheme = ArdaasColorScheme
        assertEquals(ArdaasColors.Kesri, scheme.primary)
        assertEquals(ArdaasColors.NavyBottom, scheme.background)
        assertEquals(ArdaasColors.NavyBottom, scheme.surface)
        assertEquals(ArdaasColors.Parchment, scheme.onBackground)
        assertEquals(ArdaasColors.Parchment, scheme.onSurface)
    }
}
