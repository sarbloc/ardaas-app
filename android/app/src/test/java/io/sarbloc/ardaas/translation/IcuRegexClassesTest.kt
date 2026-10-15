package io.sarbloc.ardaas.translation

import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spelled-out classes in [IcuRegexClasses] must mean exactly what the
 * Unicode-aware shorthands mean, for every code point — that is the whole
 * reason they exist (see its KDoc). The JVM's `UNICODE_CHARACTER_CLASS` mode
 * is the oracle: it implements the same Unicode definitions ICU documents.
 */
class IcuRegexClassesTest {

    private fun assertSameMembership(name: String, explicitBody: String, shorthand: String) {
        val explicit = Pattern.compile("[$explicitBody]")
        val oracle = Pattern.compile(shorthand, Pattern.UNICODE_CHARACTER_CLASS)
        val mismatches = (0..Character.MAX_CODE_POINT)
            .filter { it !in SURROGATES }
            .map { String(Character.toChars(it)) }
            .filter { explicit.matcher(it).matches() != oracle.matcher(it).matches() }
            .take(10)
            .map { "U+%04X".format(it.codePointAt(0)) }
        assertEquals("$name differs at", emptyList<String>(), mismatches)
    }

    private companion object {
        /** Never a whole character on its own; Java strings can't hold one as a code point. */
        val SURROGATES = 0xD800..0xDFFF
    }

    @Test
    fun spaceIsUnicodeWhitespace() = assertSameMembership("SPACE", IcuRegexClasses.SPACE, """\s""")

    @Test
    fun digitIsUnicodeDecimal() = assertSameMembership("DIGIT", IcuRegexClasses.DIGIT, """\d""")

    @Test
    fun wordIsUnicodeWord() = assertSameMembership("WORD", IcuRegexClasses.WORD, """\w""")

    /** The case the JVM's ASCII default gets wrong: a NO-BREAK SPACE, as in the "12 cm" parity case. */
    @Test
    fun noBreakSpaceIsWhitespace() {
        assertTrue(Regex("[${IcuRegexClasses.SPACE}]").matches(" "))
    }

    @Test
    fun wordBoundarySeesNonAsciiLetters() {
        val boundaries = Regex(IcuRegexClasses.WORD_BOUNDARY).findAll("café ok").map { it.range.first }.toList()
        assertEquals(listOf(0, 4, 5, 7), boundaries)
    }
}
