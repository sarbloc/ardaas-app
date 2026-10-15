package io.sarbloc.ardaas.translation

/**
 * The ICU meanings of `\s`, `\w`, `\d` and `\b`, spelled out as explicit
 * Unicode classes so they mean the same thing on every engine that runs this
 * code.
 *
 * Why not just write `\s`: the iOS port uses NSRegularExpression (ICU), where
 * those shorthands are Unicode-aware, as they are in the Python reference.
 * `java.util.regex` on the JVM — where the unit tests run — makes them ASCII
 * unless `UNICODE_CHARACTER_CLASS` is set, and Android's ICU-backed `Pattern`
 * rejects that flag. A benti with a NO-BREAK SPACE (one parity fixture has
 * two) would then tokenize differently on the test host and on device. These
 * classes use only `\p{…}` general categories and code-point ranges, which
 * both engines read identically. `IcuRegexClassesTest` pins each one against
 * the JVM's Unicode-mode shorthand, code point for code point.
 *
 * Each constant is the *body* of a character class, so it can be combined:
 * `"[$SPACE]+"`, `"[^$WORD/.]"`.
 */
internal object IcuRegexClasses {

    /** ICU `\s`: the Unicode `White_Space` property, enumerated. */
    const val SPACE =
        """\t\n\x{0B}\f\r\x{20}\x{85}\x{A0}\x{1680}\x{2000}-\x{200A}\x{2028}\x{2029}\x{202F}\x{205F}\x{3000}"""

    /** ICU `\d`: decimal digits in any script. */
    const val DIGIT = """\p{Nd}"""

    /**
     * ICU `\w`: `[\p{Alphabetic}\p{M}\p{Nd}\p{Pc}\x{200C}\x{200D}]`.
     * `Alphabetic` is letters, letter numbers and (via Other_Alphabetic) some
     * marks — all covered by `\p{L}\p{Nl}\p{M}` — plus the circled and squared
     * Latin letters (So), listed by range because no category reaches them.
     */
    const val WORD =
        """\p{L}\p{Nl}\p{M}\p{Nd}\p{Pc}\x{200C}\x{200D}""" +
            """\x{24B6}-\x{24E9}\x{1F130}-\x{1F149}\x{1F150}-\x{1F169}\x{1F170}-\x{1F189}"""

    /**
     * `\b` as a transition between [WORD] and non-[WORD]. ICU additionally
     * skips combining marks and format characters when looking back across a
     * boundary; that only matters for a mark directly after punctuation,
     * which English benti input does not produce.
     */
    const val WORD_BOUNDARY = """(?:(?<=[$WORD])(?![$WORD])|(?<![$WORD])(?=[$WORD]))"""
}
