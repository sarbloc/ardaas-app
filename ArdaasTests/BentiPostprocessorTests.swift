import XCTest
@testable import Ardaas

/// Postprocessing parity for the pure text stages (#35) —
/// Devanagari -> Gurmukhi transliteration, indic detokenization, and the HF
/// decode cleanup. Expected values were generated with indic_nlp_library
/// (`UnicodeIndicTransliterator`, `trivial_detokenize_indic`), the reference
/// implementations named in the pipeline spec. No ONNX Runtime and no model
/// artifact: these run on every CI job.
final class BentiPostprocessorTests: XCTestCase {
    // MARK: - Devanagari -> Gurmukhi (spec section 5.3)

    func testBasicWordTransliteration() {
        XCTAssertEqual(
            BentiPostprocessor.transliterateDevanagariToGurmukhi("वाहिगुरू"),
            "ਵਾਹਿਗੁਰੂ"
        )
    }

    func testDandaIsPreserved() {
        XCTAssertEqual(
            BentiPostprocessor.transliterateDevanagariToGurmukhi("सतिगुरू जी ।"),
            "ਸਤਿਗੁਰੂ ਜੀ ।"
        )
    }

    func testDoubleDandaIsPreserved() {
        XCTAssertEqual(
            BentiPostprocessor.transliterateDevanagariToGurmukhi(
                "मेरे परिवार को स्वास्थ्य और खुशी दो ॥"),
            "ਮੇਰੇ ਪਰਿਵਾਰ ਕੋ ਸ੍ਵਾਸ੍ਥ੍ਯ ਔਰ ਖੁਸ਼ੀ ਦੋ ॥"
        )
    }

    func testAsciiAndPlaceholdersPassThrough() {
        XCTAssertEqual(
            BentiPostprocessor.transliterateDevanagariToGurmukhi("ID1 <ID1> abc 123 !"),
            "ID1 <ID1> abc 123 !"
        )
    }

    func testDevanagariDigitsMapIntoGurmukhiBlock() {
        XCTAssertEqual(
            BentiPostprocessor.transliterateDevanagariToGurmukhi("देवनागरी अंक ० १ ९"),
            "ਦੇਵਨਾਗਰੀ ਅਂਕ ੦ ੧ ੯"
        )
    }

    func testBlockBoundaries() {
        // U+0950 (ॐ, offset 0x50) maps; U+0970 (॰, offset 0x70) is outside
        // the coordinated range and passes through.
        XCTAssertEqual(
            BentiPostprocessor.transliterateDevanagariToGurmukhi("ॐ ॰"),
            "\u{0A50} ॰"
        )
    }

    func testNonDevanagariScriptsPassThrough() {
        XCTAssertEqual(
            BentiPostprocessor.transliterateDevanagariToGurmukhi("বাংলা mixed वाह"),
            "বাংলা mixed ਵਾਹ"
        )
    }

    // MARK: - Indic detokenize (spec section 5.4)

    func testDandaAttachesLeft() {
        XCTAssertEqual(
            BentiPostprocessor.indicDetokenize("ਸਤਿਗੁਰੂ ਜੀ ।"),
            "ਸਤਿਗੁਰੂ ਜੀ।"
        )
    }

    func testPunctuationAttachment() {
        XCTAssertEqual(
            BentiPostprocessor.indicDetokenize("( ਸਤ ) ਅਤੇ [ ਨਾਮ ] ਜਪੋ !"),
            "(ਸਤ) ਅਤੇ [ਨਾਮ] ਜਪੋ!"
        )
        XCTAssertEqual(
            BentiPostprocessor.indicDetokenize("ਏ - ਬੀ ਅਤੇ ਸੀ / ਡੀ"),
            "ਏ-ਬੀ ਅਤੇ ਸੀ/ਡੀ"
        )
    }

    func testQuotesAlternateAttachment() {
        XCTAssertEqual(
            BentiPostprocessor.indicDetokenize("' ਸਤ ਸ੍ਰੀ ਅਕਾਲ ' ਆਖੋ ।"),
            "'ਸਤ ਸ੍ਰੀ ਅਕਾਲ' ਆਖੋ।"
        )
        XCTAssertEqual(
            BentiPostprocessor.indicDetokenize("\" ਧੰਨ ਗੁਰੂ \" , ਉਸ ਨੇ ਕਿਹਾ ।"),
            "\"ਧੰਨ ਗੁਰੂ\", ਉਸ ਨੇ ਕਿਹਾ।"
        )
    }

    func testNumberSequencesCollapse() {
        XCTAssertEqual(
            BentiPostprocessor.indicDetokenize("ਮਿਤੀ 15 . 8 . 2026 ਨੂੰ ਅਰਦਾਸ ਹੈ ।"),
            "ਮਿਤੀ 15.8.2026 ਨੂੰ ਅਰਦਾਸ ਹੈ।"
        )
        XCTAssertEqual(
            BentiPostprocessor.indicDetokenize("ਸਮਾਂ 9 : 30 ਵਜੇ ॥"),
            "ਸਮਾਂ 9:30 ਵਜੇ॥"
        )
    }

    /// The reference implementation skips a number sequence that starts at
    /// position 0 (its `start > prev` guard) — ported bug-for-bug, so "10 ,
    /// 000" at the start only gets the left-attach comma treatment.
    func testLeadingNumberSequenceQuirk() {
        XCTAssertEqual(
            BentiPostprocessor.indicDetokenize("10 , 000 ਰੁਪਏ"),
            "10, 000 ਰੁਪਏ"
        )
    }

    // MARK: - HF decode cleanup (spec section 5.1)

    func testCleanupTokenizationSpaces() {
        XCTAssertEqual(
            BentiPostprocessor.cleanUpTokenizationSpaces("ਇਹ ਹੈ ਜੀ ."),
            "ਇਹ ਹੈ ਜੀ."
        )
        XCTAssertEqual(
            BentiPostprocessor.cleanUpTokenizationSpaces("ਕੀ ਹਾਲ ਹੈ ?"),
            "ਕੀ ਹਾਲ ਹੈ?"
        )
        XCTAssertEqual(
            BentiPostprocessor.cleanUpTokenizationSpaces("ਏ , ਬੀ"),
            "ਏ, ਬੀ"
        )
        // The quote rule: " x ' y " -> " x' y" per the reference replace order.
        XCTAssertEqual(
            BentiPostprocessor.cleanUpTokenizationSpaces("ਓ ' ਸ"),
            "ਓ'ਸ"
        )
    }

    // MARK: - Placeholder wrap/restore round trip (spec sections 2.3 + 5.2)

    func testPlaceholderRoundTrip() {
        let wrapped = BentiPreprocessor.wrapWithPlaceholders(
            "Grant success starting on 2026-08-15, and keep pride away.")
        XCTAssertTrue(wrapped.text.contains("<ID1>"))
        XCTAssertFalse(wrapped.text.contains("2026-08-15"))

        // The model may echo the placeholder in any registered spelling.
        for spelling in ["<ID1>", "< ID1 >", "[ID1]", "<id1>"] {
            XCTAssertEqual(
                BentiPostprocessor.restorePlaceholders("ਸਫਲਤਾ \(spelling) ਨੂੰ", placeholders: wrapped.placeholders),
                "ਸਫਲਤਾ 2026-08-15 ਨੂੰ",
                "failed to restore spelling \(spelling)"
            )
        }
    }

    func testShortNumeralsAreNotWrapped() {
        // "9:30" minus " .:" is 3 chars — under the reference's threshold.
        let wrapped = BentiPreprocessor.wrapWithPlaceholders("Meet at 9:30 today.")
        XCTAssertFalse(wrapped.text.contains("<ID"))
        XCTAssertTrue(wrapped.placeholders.isEmpty)
    }

    // MARK: - Script purity gate (spec section 5.5)

    func testGurmukhiPurityGate() {
        XCTAssertTrue(BentiPostprocessor.isGurmukhiPure("ਵਾਹਿਗੁਰੂ ਜੀ ।"))
        XCTAssertTrue(BentiPostprocessor.isGurmukhiPure("\"ਧੰਨ\", ਹੈ ਜੀ।"))
        XCTAssertFalse(BentiPostprocessor.isGurmukhiPure("ਵਾਹਿਗੁਰੂ जी"))  // Devanagari leak
        XCTAssertFalse(BentiPostprocessor.isGurmukhiPure("ਜੀ latin"))
        XCTAssertFalse(BentiPostprocessor.isGurmukhiPure("ਜੀ 123"))       // ASCII digits are N
    }
}
