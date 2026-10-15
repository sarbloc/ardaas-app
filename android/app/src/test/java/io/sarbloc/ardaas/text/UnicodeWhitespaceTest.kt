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
            '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', ' ', '\u0085', ' ',
            ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　',
        )
        for (c in iosWhitespace) {
            assertTrue("U+%04X".format(c.code), c.isSwiftWhitespace())
            assertEquals("U+%04X".format(c.code), "ਪਾਠ", "${c}ਪਾਠ$c".trimmingWhitespaceAndNewlines())
        }
    }

    @Test
    fun keepsWhatIosKeeps() {
        // U+001C–U+001F: Kotlin's trim() would strip these; iOS does not.
        // U+200B, U+FEFF, U+180E: format characters, whitespace on neither.
        for (c in listOf('\u001C', '\u001D', '\u001E', '\u001F', '​', '﻿', '᠎')) {
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
