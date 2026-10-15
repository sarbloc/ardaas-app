package io.sarbloc.ardaas.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android-only: pins the trim set to iOS's `.whitespacesAndNewlines`, which is
 * what BentiLayers and OccasionLayers trim with there. Kotlin's own `trim()`
 * differs on exactly the characters asserted below.
 */
class UnicodeWhitespaceTest {

    @Test
    fun trimsWhatIosTrims() {
        val iosWhitespace = listOf(
            '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u0085', '\u00A0',
            '\u1680', '\u2000', '\u2007', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
        )
        for (c in iosWhitespace) {
            assertTrue("U+%04X".format(c.code), c.isSwiftWhitespace())
            assertEquals("U+%04X".format(c.code), "ਪਾਠ", "${c}ਪਾਠ$c".trimmingWhitespaceAndNewlines())
        }
    }

    /**
     * The whole BMP, against the Unicode White_Space property spelled out by
     * code point — the set Swift's `Character.isWhitespace` uses, which the
     * transliterator's seam check relies on too. Every White_Space scalar is
     * in the BMP, so this is exhaustive.
     */
    @Test
    fun matchesUnicodeWhiteSpaceAcrossTheBmp() {
        val whiteSpace = (0x0009..0x000D) + listOf(0x0020, 0x0085, 0x00A0, 0x1680) +
            (0x2000..0x200A) + listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)
        val expected = whiteSpace.toSet()
        for (code in Char.MIN_VALUE.code..Char.MAX_VALUE.code) {
            assertEquals("U+%04X".format(code), code in expected, code.toChar().isSwiftWhitespace())
        }
    }

    @Test
    fun keepsWhatIosKeeps() {
        // U+001C–U+001F: Kotlin's trim() would strip these; iOS does not.
        // U+200B, U+FEFF, U+180E: format characters, whitespace on neither.
        for (c in listOf('\u001C', '\u001D', '\u001E', '\u001F', '\u200B', '\uFEFF', '\u180E')) {
            assertFalse("U+%04X".format(c.code), c.isSwiftWhitespace())
            assertEquals("U+%04X".format(c.code), "${c}x$c", "${c}x$c".trimmingWhitespaceAndNewlines())
        }
    }

    /** The two places Kotlin's built-in trim() disagrees with iOS — the reason this helper exists. */
    @Test
    fun differsFromKotlinTrimWhereIosDoes() {
        assertEquals("\u0085x", "\u0085x".trim())
        assertEquals("x", "\u0085x".trimmingWhitespaceAndNewlines())
        assertEquals("x", "\u001Cx".trim())
        assertEquals("\u001Cx", "\u001Cx".trimmingWhitespaceAndNewlines())
    }

    @Test
    fun interiorWhitespaceIsKept() {
        assertEquals("ਜਪੁ ਜੀ ਸਾਹਿਬ", " ਜਪੁ ਜੀ ਸਾਹਿਬ ".trimmingWhitespaceAndNewlines())
    }
}
