package io.sarbloc.ardaas.ui.reader

import androidx.compose.ui.unit.sp
import io.sarbloc.ardaas.content.LayerKind
import io.sarbloc.ardaas.ui.ReaderTextStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text-size rule from `ReaderView.adjustFontScale(by:)` and its range. */
class FontScaleTest {
    @Test
    fun stepsByATenth() {
        assertEquals(1.1, FontScale.adjusted(1.0, FontScale.STEP), 0.0)
        assertEquals(0.9, FontScale.adjusted(1.0, -FontScale.STEP), 0.0)
    }

    /** Repeated steps land on exact tenths — no floating-point drift accumulates. */
    @Test
    fun repeatedStepsDoNotDrift() {
        var scale = FontScale.MIN
        repeat(9) { scale = FontScale.adjusted(scale, FontScale.STEP) }
        assertEquals(FontScale.MAX, scale, 0.0)
        repeat(9) { scale = FontScale.adjusted(scale, -FontScale.STEP) }
        assertEquals(FontScale.MIN, scale, 0.0)
    }

    @Test
    fun clampsAtBothEndsOfTheRange() {
        assertEquals(FontScale.MAX, FontScale.adjusted(FontScale.MAX, FontScale.STEP), 0.0)
        assertEquals(FontScale.MIN, FontScale.adjusted(FontScale.MIN, -FontScale.STEP), 0.0)
    }

    @Test
    fun controlsAreOfferedOnlyInsideTheRange() {
        assertTrue(FontScale.canIncrease(1.5))
        assertFalse(FontScale.canIncrease(FontScale.MAX))
        assertTrue(FontScale.canDecrease(0.8))
        assertFalse(FontScale.canDecrease(FontScale.MIN))
    }

    @Test
    fun normalizesStoredValues() {
        // The float round-trip: 0.7f widens to 0.699999988…
        assertEquals(0.7, FontScale.normalized(0.7f.toDouble()), 0.0)
        assertEquals(1.6, FontScale.normalized(1.6f.toDouble()), 0.0)
        assertEquals(FontScale.MAX, FontScale.normalized(9.0), 0.0)
        assertEquals(FontScale.MIN, FontScale.normalized(0.0), 0.0)
        assertEquals(FontScale.DEFAULT, FontScale.normalized(Double.NaN), 0.0)
        assertEquals(FontScale.DEFAULT, FontScale.normalized(Double.POSITIVE_INFINITY), 0.0)
    }

    /** Scaling keeps the generous leading: line height grows with the type, never left behind. */
    @Test
    fun stylesScaleSizeAndLineHeightTogether() {
        val styles = ScaledReaderStyles.at(1.5)
        assertEquals(33.sp, styles.gurmukhi.fontSize)
        assertEquals(54.sp, styles.gurmukhi.lineHeight)
        assertEquals(36.sp, styles.forLayer(LayerKind.TRANSLITERATION).lineHeight)
        assertEquals(ReaderTextStyles.Translation.color, styles.forLayer(LayerKind.ENGLISH).color)
        assertEquals(ReaderTextStyles.Gurmukhi, ScaledReaderStyles.at(FontScale.DEFAULT).gurmukhi)
    }
}
