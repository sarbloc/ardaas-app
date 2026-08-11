import XCTest
@testable import Ardaas

/// The rule that decides whether a benti gets translated or only
/// transliterated. Documented in full on `BentiScriptDetector`:
///
/// * Gurmukhi evidence = scalars in U+0A00…U+0A7F, minus the Gurmukhi digits.
/// * Other evidence = letters outside that block.
/// * Majority wins, ties go to Gurmukhi, no evidence at all is `.undetermined`.
final class BentiScriptTests: XCTestCase {

    private func expect(
        _ cases: [(String, BentiScript)],
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        for (input, expected) in cases {
            XCTAssertEqual(
                BentiScriptDetector.detect(input), expected,
                "input: \(input)", file: file, line: line
            )
        }
    }

    // MARK: - Single script

    func testEnglishIsLatin() {
        expect([
            ("Please bless my family with health and happiness.", .latin),
            ("a", .latin),
            ("Waheguru Ji Ki Fateh", .latin),
        ])
    }

    func testGurmukhiIsGurmukhi() {
        expect([
            ("ਸਿੰਘ", .gurmukhi),
            ("ਮੇਰੇ ਪਰਿਵਾਰ ਉੱਤੇ ਕਿਰਪਾ ਕਰੋ", .gurmukhi),
            ("ੴ", .gurmukhi),      // standalone token, still Gurmukhi evidence
            ("ਜੀ॥", .gurmukhi),    // danda is outside the block and no letter
        ])
    }

    // MARK: - No evidence either way

    /// Nothing to translate and nothing to transliterate: the Translate action
    /// stays visible but disabled rather than sending punctuation to a model.
    func testNoLettersIsUndetermined() {
        expect([
            ("", .undetermined),
            ("   \n\t ", .undetermined),
            ("108", .undetermined),
            ("!?…,.;:-—()\"'", .undetermined),
            ("2026-08-11 12:30", .undetermined),
            ("🙏🏽🌸", .undetermined),
        ])
    }

    /// Gurmukhi digits are excluded for exactly the reason ASCII digits are —
    /// a number identifies no language — so they neither make text Gurmukhi
    /// nor stop it being English.
    func testGurmukhiDigitsAreNotEvidence() {
        expect([
            ("੧੨੩", .undetermined),
            ("੦੧੨੩੪੫੬੭੮੯", .undetermined),
            ("abc ੧੨੩੪੫", .latin),
        ])
    }

    // MARK: - Mixed input

    func testMixedInputGoesWithTheMajority() {
        expect([
            // Mostly English with one Gurmukhi word: translate it, and let the
            // review step deal with the word that was already Gurmukhi.
            ("hello ਕ", .latin),
            ("Bless the sangat ਜੀ", .latin),
            // Mostly Gurmukhi with an English word: never machine-translate
            // this. The transliterator passes Latin straight through, so the
            // English survives intact.
            ("ਮੇਰੀ ਬੇਨਤੀ hi", .gurmukhi),
            ("ਸਿੰਘ ਅਰਦਾਸ 2026", .gurmukhi),
        ])
    }

    /// A genuine tie goes to Gurmukhi. `ab` is two Latin letters; `ਕਖ` is two
    /// Gurmukhi scalars. Being wrong this way costs a pass-through
    /// transliteration; being wrong the other way feeds Gurmukhi to an
    /// English→Gurmukhi model.
    func testTieGoesToGurmukhi() {
        expect([
            ("ab ਕਖ", .gurmukhi),
            ("ਕ a", .gurmukhi),
        ])
    }

    /// Matras, tippi, addak and halant are separate scalars and all count, so
    /// one Gurmukhi word outweighs a Latin word of the same visual length.
    /// That bias is deliberate — see the tie test above.
    func testCombiningMarksCountAsGurmukhi() {
        expect([
            ("ਸਿੰਘ abc", .gurmukhi),   // 4 Gurmukhi scalars vs 3 Latin letters
        ])
    }

    /// Punctuation and digits never tip the balance in either direction.
    func testPunctuationAndDigitsAreNeutral() {
        XCTAssertEqual(BentiScriptDetector.detect("hello!!! 12345 ???"), .latin)
        XCTAssertEqual(BentiScriptDetector.detect("ਜੀ!!! 12345 ???"), .gurmukhi)
    }

    /// Non-Latin, non-Gurmukhi letters count as "other". The model's source
    /// language is English, so this is a bad translation waiting to happen —
    /// but it is the user's text, the draft is shown before it is saved, and
    /// silently treating Devanagari as Gurmukhi would be worse.
    func testOtherScriptsCountAsLatinSide() {
        expect([
            ("मेरी बेनती", .latin),
            // 9 Gurmukhi scalars against 2 Devanagari letters — Devanagari
            // matras are combining marks, not letters, so they don't count.
            ("ਮੇਰੀ ਬੇਨਤੀ मेरी", .gurmukhi),
        ])
    }
}
