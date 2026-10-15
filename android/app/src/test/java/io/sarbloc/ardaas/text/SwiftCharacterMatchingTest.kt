package io.sarbloc.ardaas.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android-only: pins Swift's Character-level string matching on Kotlin's UTF-16 strings. */
class SwiftCharacterMatchingTest {

    @Test
    fun charactersAreGraphemeClusters() {
        assertEquals(listOf("ਗੁ", "ਰੂ"), "ਗੁਰੂ".swiftCharacters())
        assertEquals(listOf("é", "x"), "éx".swiftCharacters())
        assertEquals(listOf("🙏"), "🙏".swiftCharacters())
        assertEquals(emptyList<String>(), "".swiftCharacters())
    }

    @Test
    fun equalityIsCanonical() {
        assertTrue("é".canonicallyEquals("é"))
        assertTrue("`".canonicallyEquals("`"))
        assertFalse("é".canonicallyEquals("e"))
    }

    @Test
    fun replacesWholeCharactersOnly() {
        assertEquals("a-b", "a b".swiftReplacingOccurrences(" ", "-"))
        // "e" is only part of the cluster "e + acute", so it is not a match.
        assertEquals("café", "café".swiftReplacingOccurrences("e", "x"))
        // But a precomposed é matches a decomposed target, and vice versa.
        assertEquals("cafx", "café".swiftReplacingOccurrences("é", "x"))
    }

    @Test
    fun replacementIsLeftToRightAndNonOverlapping() {
        assertEquals("ba", "aaa".swiftReplacingOccurrences("aa", "b"))
        assertEquals("x", "x".swiftReplacingOccurrences("", "y"))
        assertEquals("", "aa".swiftReplacingOccurrences("a", ""))
    }
}
