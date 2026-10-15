package io.sarbloc.ardaas.translation

import io.sarbloc.ardaas.text.canonicallyEquals
import io.sarbloc.ardaas.text.swiftCharacters
import io.sarbloc.ardaas.text.swiftReplacingOccurrences
import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines

// Port of Ardaas/Translation/BentiPostprocessor.swift. Keep the two in step:
// the same model output must become the same Gurmukhi benti on both platforms.
//
// String semantics: where Swift walks Unicode scalars this port walks code
// points; where Swift uses NSRegularExpression (ICU, UTF-16) this uses
// kotlin.text.Regex; and where Swift compares or replaces Strings — whole
// grapheme clusters under canonical equivalence — this goes through
// swiftReplacingOccurrences / canonicallyEquals rather than Kotlin's
// code-unit `replace` / `==`.

/**
 * The pure text stages that turn model output into a Gurmukhi benti
 * (pipeline spec section 5): id-drop + piece-join detokenization, HF cleanup,
 * placeholder restore, Devanagari → Gurmukhi transliteration, indic
 * detokenization, and the script purity gate.
 *
 * Kept free of ONNX Runtime and the model artifact so every stage is a plain
 * JVM unit test.
 */
object BentiPostprocessor {

    // Ids -> raw Devanagari string (spec section 5.1)

    /** Ids below this are the vocabulary's specials (pad, bos, eos, unk), never text. */
    private const val FIRST_TEXT_ID = 4L

    /** SentencePiece's word-boundary marker, LOWER ONE EIGHTH BLOCK. */
    private const val WORD_BOUNDARY = "▁"

    /** Drops specials, joins the pieces, turns word boundaries into spaces, trims, then HF-cleans. */
    fun decodeToText(ids: List<Long>, idToPiece: Map<Long, String>): String {
        val joined = ids
            .filter { it >= FIRST_TEXT_ID }
            .mapNotNull { idToPiece[it] }
            .joinToString(separator = "")
            .swiftReplacingOccurrences(WORD_BOUNDARY, " ")
            .trimmingWhitespaceAndNewlines()
        return cleanUpTokenizationSpaces(joined)
    }

    /** HF `clean_up_tokenization_spaces` replacements; order matters, it is the reference's. */
    private val tokenizationSpaceRules = listOf(
        " ." to ".", " ?" to "?", " !" to "!", " ," to ",",
        " ' " to "'", " n't" to "n't", " 'm" to "'m", " 's" to "'s",
        " 've" to "'ve", " 're" to "'re",
    )

    /** HF `clean_up_tokenization_spaces`, applied in reference order. */
    fun cleanUpTokenizationSpaces(text: String): String =
        tokenizationSpaceRules.fold(text) { acc, (from, to) -> acc.swiftReplacingOccurrences(from, to) }

    // Placeholder restore (spec section 5.2)

    /** Puts back every text the preprocessor hid, whichever registered spelling the model echoed. */
    fun restorePlaceholders(text: String, placeholders: List<BentiPlaceholder>): String =
        placeholders.fold(text) { acc, placeholder ->
            acc.swiftReplacingOccurrences(placeholder.alias, placeholder.original)
        }

    // Devanagari -> Gurmukhi (spec section 5.3)

    private const val DEVANAGARI_BASE = 0x0900
    private const val GURMUKHI_BASE = 0x0A00

    /** Offsets the two ISCII-derived blocks share; past 0x6F they stop lining up. */
    private val COORDINATED_OFFSETS = 0x00..0x6F

    private const val DANDA = 0x0964
    private const val DOUBLE_DANDA = 0x0965

    /**
     * Shifts each Devanagari scalar into the Gurmukhi block at the same offset
     * (`UnicodeIndicTransliterator`). The dandas are shared by both scripts
     * and live only in the Devanagari block, so they stay put; everything
     * outside the coordinated range passes through untouched.
     */
    fun transliterateDevanagariToGurmukhi(text: String): String {
        val out = StringBuilder(text.length)
        text.codePoints().forEach { scalar ->
            val offset = scalar - DEVANAGARI_BASE
            if (offset in COORDINATED_OFFSETS && scalar != DANDA && scalar != DOUBLE_DANDA) {
                out.appendCodePoint(GURMUKHI_BASE + offset)
            } else {
                out.appendCodePoint(scalar)
            }
        }
        return out.toString()
    }

    // Indic detokenize (spec section 5.4; indic_nlp_library
    // trivial_detokenize_indic, ported bug-for-bug)

    private val numberSequence = Regex("([0-9]+ [,.:/] )+[0-9]+")
    private val bothAttach = Regex("[ ]([-/\\\\])[ ]")
    private val leftAttach = Regex("[ ]([!%)\\]},.:;>?।॥])")
    private val rightAttach = Regex("([#$(\\[{<@])[ ]")

    /**
     * Private Use Area markers for odd/even quote occurrences: never produced
     * by the model and absent from real bentis, so a literal benti string can
     * never be mistaken for one.
     */
    private const val OPEN_MARKER = ""
    private const val CLOSE_MARKER = ""

    private val quotes = listOf("'", "\"", "`")

    /** Removes the spaces the model's tokenizer put around punctuation, numbers and quotes. */
    fun indicDetokenize(text: String): String {
        var s = collapseNumberSequences(text)
        s = bothAttach.replace(s, "$1")
        s = leftAttach.replace(s, "$1")
        s = rightAttach.replace(s, "$1")
        return quotes.fold(s, ::attachQuotes)
    }

    /**
     * "15 . 8 . 2026" -> "15.8.2026". The reference skips a match that starts
     * exactly at the previous boundary (`start > prev`), which leaves a match
     * at position 0 untouched — kept for parity.
     */
    private fun collapseNumberSequences(text: String): String {
        val out = StringBuilder(text.length)
        var prev = 0
        for (match in numberSequence.findAll(text)) {
            if (match.range.first > prev) {
                out.append(text, prev, match.range.first)
                out.append(match.value.replace(" ", ""))
                prev = match.range.last + 1
            }
        }
        out.append(text, prev, text.length)
        return out.toString()
    }

    /**
     * Quotes alternate: odd occurrences attach to the text after them, even
     * ones to the text before. A quote is a whole Swift `Character`, so one
     * carrying a combining mark is not counted.
     */
    private fun attachQuotes(text: String, quote: String): String {
        var count = 0
        val marked = StringBuilder(text.length)
        for (character in text.swiftCharacters()) {
            if (character.canonicallyEquals(quote)) {
                marked.append(if (count % 2 == 0) OPEN_MARKER else CLOSE_MARKER)
                count += 1
            } else {
                marked.append(character)
            }
        }
        return marked.toString()
            .swiftReplacingOccurrences("$OPEN_MARKER ", quote)
            .swiftReplacingOccurrences(" $CLOSE_MARKER", quote)
            .swiftReplacingOccurrences(OPEN_MARKER, quote)
            .swiftReplacingOccurrences(CLOSE_MARKER, quote)
    }

    // Script purity gate (spec section 5.5)

    private val GURMUKHI_BLOCK = 0x0A00..0x0A7F

    /** General categories L*, M* and N*: anything that is script, not punctuation or space. */
    private val SCRIPT_CATEGORIES: Set<Int> = setOf(
        Character.LOWERCASE_LETTER,
        Character.UPPERCASE_LETTER,
        Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER,
        Character.OTHER_LETTER,
        Character.NON_SPACING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.ENCLOSING_MARK,
        Character.DECIMAL_DIGIT_NUMBER,
        Character.LETTER_NUMBER,
        Character.OTHER_NUMBER,
    ).map { it.toInt() }.toSet()

    /**
     * No letters, marks or numbers outside U+0A00–U+0A7F.
     *
     * Advisory, not a hard gate: a restored placeholder (a date, a number)
     * legitimately trips it, so the UI shows it as a caution on the draft
     * rather than refusing the translation.
     */
    fun isGurmukhiPure(text: String): Boolean =
        text.codePoints().noneMatch { scalar ->
            scalar !in GURMUKHI_BLOCK && Character.getType(scalar) in SCRIPT_CATEGORIES
        }
}
