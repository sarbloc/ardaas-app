package io.sarbloc.ardaas.translation

import io.sarbloc.ardaas.text.canonicallyEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of ArdaasTests/BentiPostprocessorTests.swift — postprocessing parity for
 * the pure text stages. Expected values were generated with indic_nlp_library
 * (`UnicodeIndicTransliterator`, `trivial_detokenize_indic`), the reference
 * implementations named in the pipeline spec, and are shared with iOS.
 */
class BentiPostprocessorTest {

    // Devanagari -> Gurmukhi (spec section 5.3)

    @Test
    fun basicWordTransliteration() {
        assertEquals("ਵਾਹਿਗੁਰੂ", BentiPostprocessor.transliterateDevanagariToGurmukhi("वाहिगुरू"))
    }

    @Test
    fun dandaIsPreserved() {
        assertEquals("ਸਤਿਗੁਰੂ ਜੀ ।", BentiPostprocessor.transliterateDevanagariToGurmukhi("सतिगुरू जी ।"))
    }

    /**
     * The shared expectation spells ਸ਼ decomposed (U+0A38 U+0A3C) while the
     * offset map yields precomposed U+0A36 from श. XCTAssertEqual compares
     * Swift Strings canonically, so this asserts the same way — and pins the
     * scalar actually produced.
     */
    @Test
    fun doubleDandaIsPreserved() {
        val actual = BentiPostprocessor.transliterateDevanagariToGurmukhi("मेरे परिवार को स्वास्थ्य और खुशी दो ॥")
        assertTrue(actual, "ਮੇਰੇ ਪਰਿਵਾਰ ਕੋ ਸ੍ਵਾਸ੍ਥ੍ਯ ਔਰ ਖੁਸ਼ੀ ਦੋ ॥".canonicallyEquals(actual))
        assertTrue(actual.contains("ਖੁਸ਼ੀ"))
    }

    @Test
    fun asciiAndPlaceholdersPassThrough() {
        assertEquals(
            "ID1 <ID1> abc 123 !",
            BentiPostprocessor.transliterateDevanagariToGurmukhi("ID1 <ID1> abc 123 !"),
        )
    }

    @Test
    fun devanagariDigitsMapIntoGurmukhiBlock() {
        assertEquals(
            "ਦੇਵਨਾਗਰੀ ਅਂਕ ੦ ੧ ੯",
            BentiPostprocessor.transliterateDevanagariToGurmukhi("देवनागरी अंक ० १ ९"),
        )
    }

    /** U+0950 (ॐ, offset 0x50) maps; U+0970 (॰, offset 0x70) is outside the coordinated range. */
    @Test
    fun blockBoundaries() {
        assertEquals("੐ ॰", BentiPostprocessor.transliterateDevanagariToGurmukhi("ॐ ॰"))
    }

    @Test
    fun nonDevanagariScriptsPassThrough() {
        assertEquals(
            "বাংলা mixed ਵਾਹ",
            BentiPostprocessor.transliterateDevanagariToGurmukhi("বাংলা mixed वाह"),
        )
    }

    // Indic detokenize (spec section 5.4)

    @Test
    fun dandaAttachesLeft() {
        assertEquals("ਸਤਿਗੁਰੂ ਜੀ।", BentiPostprocessor.indicDetokenize("ਸਤਿਗੁਰੂ ਜੀ ।"))
    }

    @Test
    fun punctuationAttachment() {
        assertEquals("(ਸਤ) ਅਤੇ [ਨਾਮ] ਜਪੋ!", BentiPostprocessor.indicDetokenize("( ਸਤ ) ਅਤੇ [ ਨਾਮ ] ਜਪੋ !"))
        assertEquals("ਏ-ਬੀ ਅਤੇ ਸੀ/ਡੀ", BentiPostprocessor.indicDetokenize("ਏ - ਬੀ ਅਤੇ ਸੀ / ਡੀ"))
    }

    @Test
    fun quotesAlternateAttachment() {
        assertEquals("'ਸਤ ਸ੍ਰੀ ਅਕਾਲ' ਆਖੋ।", BentiPostprocessor.indicDetokenize("' ਸਤ ਸ੍ਰੀ ਅਕਾਲ ' ਆਖੋ ।"))
        assertEquals(
            "\"ਧੰਨ ਗੁਰੂ\", ਉਸ ਨੇ ਕਿਹਾ।",
            BentiPostprocessor.indicDetokenize("\" ਧੰਨ ਗੁਰੂ \" , ਉਸ ਨੇ ਕਿਹਾ ।"),
        )
    }

    @Test
    fun numberSequencesCollapse() {
        assertEquals(
            "ਮਿਤੀ 15.8.2026 ਨੂੰ ਅਰਦਾਸ ਹੈ।",
            BentiPostprocessor.indicDetokenize("ਮਿਤੀ 15 . 8 . 2026 ਨੂੰ ਅਰਦਾਸ ਹੈ ।"),
        )
        assertEquals("ਸਮਾਂ 9:30 ਵਜੇ॥", BentiPostprocessor.indicDetokenize("ਸਮਾਂ 9 : 30 ਵਜੇ ॥"))
    }

    /**
     * The reference skips a number sequence that starts at position 0 (its
     * `start > prev` guard) — ported bug-for-bug, so "10 , 000" at the start
     * only gets the left-attach comma treatment.
     */
    @Test
    fun leadingNumberSequenceQuirk() {
        assertEquals("10, 000 ਰੁਪਏ", BentiPostprocessor.indicDetokenize("10 , 000 ਰੁਪਏ"))
    }

    // HF decode cleanup (spec section 5.1)

    @Test
    fun cleanupTokenizationSpaces() {
        assertEquals("ਇਹ ਹੈ ਜੀ.", BentiPostprocessor.cleanUpTokenizationSpaces("ਇਹ ਹੈ ਜੀ ."))
        assertEquals("ਕੀ ਹਾਲ ਹੈ?", BentiPostprocessor.cleanUpTokenizationSpaces("ਕੀ ਹਾਲ ਹੈ ?"))
        assertEquals("ਏ, ਬੀ", BentiPostprocessor.cleanUpTokenizationSpaces("ਏ , ਬੀ"))
        // The quote rule: " x ' y " -> " x' y" per the reference replace order.
        assertEquals("ਓ'ਸ", BentiPostprocessor.cleanUpTokenizationSpaces("ਓ ' ਸ"))
    }

    // Placeholder wrap/restore round trip (spec sections 2.3 + 5.2)

    @Test
    fun placeholderRoundTrip() {
        val wrapped = BentiPreprocessor.wrapWithPlaceholders(
            "Grant success starting on 2026-08-15, and keep pride away.",
        )
        assertTrue(wrapped.text.contains("<ID1>"))
        assertFalse(wrapped.text.contains("2026-08-15"))

        // The model may echo the placeholder in any registered spelling.
        for (spelling in listOf("<ID1>", "< ID1 >", "[ID1]", "<id1>")) {
            assertEquals(
                "failed to restore spelling $spelling",
                "ਸਫਲਤਾ 2026-08-15 ਨੂੰ",
                BentiPostprocessor.restorePlaceholders("ਸਫਲਤਾ $spelling ਨੂੰ", wrapped.placeholders),
            )
        }
    }

    @Test
    fun shortNumeralsAreNotWrapped() {
        // "9:30" minus " .:" is 3 chars — under the reference's threshold.
        val wrapped = BentiPreprocessor.wrapWithPlaceholders("Meet at 9:30 today.")
        assertFalse(wrapped.text.contains("<ID"))
        assertTrue(wrapped.placeholders.isEmpty())
    }

    // Script purity gate (spec section 5.5)

    @Test
    fun gurmukhiPurityGate() {
        assertTrue(BentiPostprocessor.isGurmukhiPure("ਵਾਹਿਗੁਰੂ ਜੀ ।"))
        assertTrue(BentiPostprocessor.isGurmukhiPure("\"ਧੰਨ\", ਹੈ ਜੀ।"))
        assertFalse(BentiPostprocessor.isGurmukhiPure("ਵਾਹਿਗੁਰੂ जी")) // Devanagari leak
        assertFalse(BentiPostprocessor.isGurmukhiPure("ਜੀ latin"))
        assertFalse(BentiPostprocessor.isGurmukhiPure("ਜੀ 123")) // ASCII digits are N
    }

    // Android-only: no iOS counterpart.

    /** Specials (< 4) and ids missing from the vocabulary vanish; U+2581 becomes a space, then trims. */
    @Test
    fun decodeDropsSpecialsAndJoinsPieces() {
        val vocabulary = mapOf(
            2L to "</s>",
            10L to "▁सति",
            11L to "गुरू",
            12L to "▁जी",
            13L to "▁.",
        )
        assertEquals(
            "सतिगुरू जी.",
            BentiPostprocessor.decodeToText(listOf(0L, 10L, 11L, 99L, 12L, 13L, 2L), vocabulary),
        )
    }

    /** A quote or full stop carrying a combining mark is a different Swift `Character`, so it is left alone. */
    @Test
    fun punctuationWithCombiningMarkIsNotMatched() {
        assertEquals("ਜੀ .́", BentiPostprocessor.cleanUpTokenizationSpaces("ਜੀ .́"))
        assertEquals("'́ ਸਤ", BentiPostprocessor.indicDetokenize("'́ ਸਤ"))
    }

    /** U+1FEF GREEK VARIA is canonically a backtick, so Swift treats it as one. */
    @Test
    fun canonicallyEquivalentQuoteIsAQuote() {
        assertEquals("`ਸਤ`", BentiPostprocessor.indicDetokenize("` ਸਤ `"))
    }

    /** A pass-through Devanagari danda never trips the gate; neither do spaces or punctuation. */
    @Test
    fun purityGateIgnoresPunctuationAndEmpty() {
        assertTrue(BentiPostprocessor.isGurmukhiPure(""))
        assertTrue(BentiPostprocessor.isGurmukhiPure("ਜੀ॥ ! ? -"))
    }
}
