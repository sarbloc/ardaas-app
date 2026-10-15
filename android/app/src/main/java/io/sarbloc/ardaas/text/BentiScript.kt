package io.sarbloc.ardaas.text

// Port of Ardaas/Models/BentiScript.swift. Android has no translator in v1,
// but the free-text occasion rule (OccasionLayers.fromFreeText) needs the
// same script decision iOS makes.

/**
 * Which script a benti was typed in, as far as the Compose screen needs to
 * care: Gurmukhi text is already in the target script and only needs
 * transliterating, anything else is a candidate for machine translation.
 */
enum class BentiScript {
    /**
     * Gurmukhi. Translation is skipped entirely — the text *is* the Gurmukhi
     * layer, and only the (deterministic, offline) transliteration is
     * generated from it.
     */
    GURMUKHI,

    /** Letters outside the Gurmukhi block — English in the case the feature is built for. */
    LATIN,

    /**
     * No letters in either script: empty, or only whitespace, digits,
     * punctuation or emoji. Nothing to translate and nothing to transliterate.
     */
    UNDETERMINED,
}

/**
 * Decides which script a benti is written in by counting scalars.
 *
 * ## The rule
 *
 * Two counters, over the text's Unicode code points:
 *
 * * **Gurmukhi evidence** — code points in the Gurmukhi block `U+0A00…U+0A7F`,
 *   excluding the Gurmukhi digits `੦…੯` (`U+0A66…U+0A6F`). Consonants,
 *   independent vowels, matras, tippi/bindi, addak, halant, nukta, the bearers
 *   and `ੴ` all count.
 * * **Other evidence** — any code point outside that block that is a letter.
 *   ASCII digits, spaces, punctuation and emoji count for neither side, so
 *   `"108 :)"` is [BentiScript.UNDETERMINED] rather than English.
 *
 * Whichever side has more wins; **a tie goes to Gurmukhi**, and text with no
 * evidence at all is [BentiScript.UNDETERMINED].
 *
 * "A letter" is iOS's `Character.isLetter` on a single scalar: the Unicode
 * `Alphabetic` property ([Character.isAlphabetic]), not [Char.isLetter]'s
 * general category L — the same definition [io.sarbloc.ardaas.composer
 * .ArdaasComposer]'s seam rule uses. The two differ only on combining vowel
 * signs of other scripts and letter-like numbers, which count here as on iOS.
 *
 * ## Why it leans Gurmukhi on mixed input
 *
 * The counting is deliberately not normalised per grapheme, so one Gurmukhi
 * word contributes more code points than one Latin word of the same length
 * (its matras and signs are separate scalars). That bias is the safe
 * direction: misreading Gurmukhi as English would feed already-Gurmukhi text
 * to an English→Gurmukhi model, while misreading English as Gurmukhi costs
 * nothing — the transliterator passes Latin through untouched.
 */
object BentiScriptDetector {
    /** The Gurmukhi Unicode block. */
    private val gurmukhiBlock = 0x0A00..0x0A7F

    /** `੦…੯`. Excluded for the same reason ASCII digits are: a number identifies no language. */
    private val gurmukhiDigits = 0x0A66..0x0A6F

    fun detect(text: String): BentiScript {
        var gurmukhi = 0
        var other = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint in gurmukhiBlock) {
                if (codePoint !in gurmukhiDigits) gurmukhi++
            } else if (Character.isAlphabetic(codePoint)) {
                other++
            }
            index += Character.charCount(codePoint)
        }
        if (gurmukhi == 0 && other == 0) return BentiScript.UNDETERMINED
        return if (gurmukhi >= other) BentiScript.GURMUKHI else BentiScript.LATIN
    }
}
