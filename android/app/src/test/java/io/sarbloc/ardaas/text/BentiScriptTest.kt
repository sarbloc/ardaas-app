package io.sarbloc.ardaas.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Port of ArdaasTests/BentiScriptTests.swift, case for case.
 *
 * The rule that decides whether a benti gets translated or only
 * transliterated — and, on Android today, which layer a free-text occasion
 * fills. Documented in full on [BentiScriptDetector]:
 *
 * * Gurmukhi evidence = code points in U+0A00…U+0A7F, minus the Gurmukhi digits.
 * * Other evidence = letters outside that block.
 * * Majority wins, ties go to Gurmukhi, no evidence at all is UNDETERMINED.
 */
class BentiScriptTest {

    private fun expect(vararg cases: Pair<String, BentiScript>) {
        for ((input, expected) in cases) {
            assertEquals("input: $input", expected, BentiScriptDetector.detect(input))
        }
    }

    // region Single script

    @Test
    fun englishIsLatin() {
        expect(
            "Please bless my family with health and happiness." to BentiScript.LATIN,
            "a" to BentiScript.LATIN,
            "Waheguru Ji Ki Fateh" to BentiScript.LATIN,
        )
    }

    @Test
    fun gurmukhiIsGurmukhi() {
        expect(
            "ਸਿੰਘ" to BentiScript.GURMUKHI,
            "ਮੇਰੇ ਪਰਿਵਾਰ ਉੱਤੇ ਕਿਰਪਾ ਕਰੋ" to BentiScript.GURMUKHI,
            "ੴ" to BentiScript.GURMUKHI, // standalone token, still Gurmukhi evidence
            "ਜੀ॥" to BentiScript.GURMUKHI, // danda is outside the block and no letter
        )
    }

    // endregion

    // region No evidence either way

    /** Nothing to translate and nothing to transliterate. */
    @Test
    fun noLettersIsUndetermined() {
        expect(
            "" to BentiScript.UNDETERMINED,
            "   \n\t " to BentiScript.UNDETERMINED,
            "108" to BentiScript.UNDETERMINED,
            "!?…,.;:-—()\"'" to BentiScript.UNDETERMINED,
            "2026-08-11 12:30" to BentiScript.UNDETERMINED,
            "🙏🏽🌸" to BentiScript.UNDETERMINED,
        )
    }

    /**
     * Gurmukhi digits are excluded for exactly the reason ASCII digits are —
     * a number identifies no language — so they neither make text Gurmukhi
     * nor stop it being English.
     */
    @Test
    fun gurmukhiDigitsAreNotEvidence() {
        expect(
            "੧੨੩" to BentiScript.UNDETERMINED,
            "੦੧੨੩੪੫੬੭੮੯" to BentiScript.UNDETERMINED,
            "abc ੧੨੩੪੫" to BentiScript.LATIN,
        )
    }

    // endregion

    // region Mixed input

    @Test
    fun mixedInputGoesWithTheMajority() {
        expect(
            // Mostly English with one Gurmukhi word.
            "hello ਕ" to BentiScript.LATIN,
            "Bless the sangat ਜੀ" to BentiScript.LATIN,
            // Mostly Gurmukhi with an English word: never machine-translate
            // this. The transliterator passes Latin straight through.
            "ਮੇਰੀ ਬੇਨਤੀ hi" to BentiScript.GURMUKHI,
            "ਸਿੰਘ ਅਰਦਾਸ 2026" to BentiScript.GURMUKHI,
        )
    }

    /** A genuine tie goes to Gurmukhi. `ab` is two Latin letters; `ਕਖ` is two Gurmukhi scalars. */
    @Test
    fun tieGoesToGurmukhi() {
        expect(
            "ab ਕਖ" to BentiScript.GURMUKHI,
            "ਕ a" to BentiScript.GURMUKHI,
        )
    }

    /** Matras, tippi, addak and halant are separate scalars and all count. */
    @Test
    fun combiningMarksCountAsGurmukhi() {
        expect(
            "ਸਿੰਘ abc" to BentiScript.GURMUKHI, // 4 Gurmukhi scalars vs 3 Latin letters
        )
    }

    /** Punctuation and digits never tip the balance in either direction. */
    @Test
    fun punctuationAndDigitsAreNeutral() {
        assertEquals(BentiScript.LATIN, BentiScriptDetector.detect("hello!!! 12345 ???"))
        assertEquals(BentiScript.GURMUKHI, BentiScriptDetector.detect("ਜੀ!!! 12345 ???"))
    }

    /** Non-Latin, non-Gurmukhi letters count as "other". */
    @Test
    fun otherScriptsCountAsLatinSide() {
        expect(
            "मेरी बेनती" to BentiScript.LATIN,
            // 9 Gurmukhi scalars against the Devanagari word. (The iOS comment
            // says its matras don't count; under Swift's `isLetter` — the
            // Alphabetic property — they do, making it 4. Gurmukhi wins either way.)
            "ਮੇਰੀ ਬੇਨਤੀ मेरी" to BentiScript.GURMUKHI,
        )
    }

    // endregion
}
