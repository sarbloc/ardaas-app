import XCTest
@testable import Ardaas

/// Hand-curated fixtures for the transliteration scheme documented on
/// `GurmukhiTransliterator`.
///
/// These are **not** fitted to the bundled SGPC transliteration: that layer is
/// human-authored and internally inconsistent (`ਸਭ ਥਾਂਈ` appears as both "Sabh
/// Thaai" and "Sabh Thai"), so it cannot be reproduced by rules. Where this
/// engine legitimately differs from it, the expectation below is *this
/// engine's* output and the divergence is called out in a comment.
final class GurmukhiTransliteratorTests: XCTestCase {

    private func expect(
        _ cases: [(String, String)],
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        for (input, expected) in cases {
            XCTAssertEqual(
                GurmukhiTransliterator.transliterate(input), expected,
                "input: \(input)", file: file, line: line
            )
        }
    }

    // MARK: - Consonants

    /// Every consonant alone. A one-syllable word keeps its inherent vowel —
    /// schwa deletion would leave it with no vowel at all.
    func testConsonantsInIsolation() {
        expect([
            ("ਕ", "Ka"), ("ਖ", "Kha"), ("ਗ", "Ga"), ("ਘ", "Gha"), ("ਙ", "Nga"),
            ("ਚ", "Cha"), ("ਛ", "Chha"), ("ਜ", "Ja"), ("ਝ", "Jha"), ("ਞ", "Nja"),
            ("ਟ", "Ta"), ("ਠ", "Tha"), ("ਡ", "Da"), ("ਢ", "Dha"), ("ਣ", "Na"),
            ("ਤ", "Ta"), ("ਥ", "Tha"), ("ਦ", "Da"), ("ਧ", "Dha"), ("ਨ", "Na"),
            ("ਪ", "Pa"), ("ਫ", "Pha"), ("ਬ", "Ba"), ("ਭ", "Bha"), ("ਮ", "Ma"),
            ("ਯ", "Ya"), ("ਰ", "Ra"), ("ਲ", "La"), ("ਵ", "Va"), ("ੜ", "Rha"),
            ("ਸ", "Sa"), ("ਹ", "Ha"),
        ])
    }

    /// The same consonants in a simple open syllable (C + kanna).
    func testConsonantsInSimpleSyllable() {
        expect([
            ("ਕਾ", "Kaa"), ("ਖਾ", "Khaa"), ("ਗਾ", "Gaa"), ("ਘਾ", "Ghaa"),
            ("ਙਾ", "Ngaa"), ("ਚਾ", "Chaa"), ("ਛਾ", "Chhaa"), ("ਜਾ", "Jaa"),
            ("ਝਾ", "Jhaa"), ("ਞਾ", "Njaa"), ("ਟਾ", "Taa"), ("ਠਾ", "Thaa"),
            ("ਡਾ", "Daa"), ("ਢਾ", "Dhaa"), ("ਣਾ", "Naa"), ("ਤਾ", "Taa"),
            ("ਥਾ", "Thaa"), ("ਦਾ", "Daa"), ("ਧਾ", "Dhaa"), ("ਨਾ", "Naa"),
            ("ਪਾ", "Paa"), ("ਫਾ", "Phaa"), ("ਬਾ", "Baa"), ("ਭਾ", "Bhaa"),
            ("ਮਾ", "Maa"), ("ਯਾ", "Yaa"), ("ਰਾ", "Raa"), ("ਲਾ", "Laa"),
            ("ਵਾ", "Vaa"), ("ੜਾ", "Rhaa"), ("ਸਾ", "Saa"), ("ਹਾ", "Haa"),
        ])
    }

    /// Pairin-bindi letters, precomposed (U+0A36, U+0A59…U+0A5E).
    func testPairinBindiConsonantsPrecomposed() {
        expect([
            ("\u{0A36}", "Sha"), ("\u{0A59}", "Kha"), ("\u{0A5A}", "Gha"),
            ("\u{0A5B}", "Za"), ("\u{0A5E}", "Fa"), ("\u{0A33}", "La"),
            ("\u{0A36}\u{0A3E}", "Shaa"), ("\u{0A5B}\u{0A3E}", "Zaa"),
            ("\u{0A5E}\u{0A3E}", "Faa"),
        ])
    }

    /// The same letters spelled base + U+0A3C NUKTA, which is how the bundled
    /// JSON stores them. Both spellings must transliterate identically.
    func testPairinBindiConsonantsDecomposed() {
        expect([
            ("\u{0A38}\u{0A3C}", "Sha"), ("\u{0A16}\u{0A3C}", "Kha"),
            ("\u{0A17}\u{0A3C}", "Gha"), ("\u{0A1C}\u{0A3C}", "Za"),
            ("\u{0A2B}\u{0A3C}", "Fa"), ("\u{0A32}\u{0A3C}", "La"),
        ])
        XCTAssertEqual(
            GurmukhiTransliterator.transliterate("\u{0A38}\u{0A3C}\u{0A2C}\u{0A26}"),
            GurmukhiTransliterator.transliterate("\u{0A36}\u{0A2C}\u{0A26}")
        )
    }

    // MARK: - Vowels

    func testIndependentVowels() {
        expect([
            ("ਅ", "A"), ("ਆ", "Aa"), ("ਇ", "I"), ("ਈ", "I"), ("ਉ", "U"),
            ("ਊ", "U"), ("ਏ", "E"), ("ਐ", "Ai"), ("ਓ", "O"), ("ਔ", "Au"),
        ])
    }

    /// Every dependent vowel sign on a single consonant. Vowel length is
    /// written for a/aa only: ਿ and ੀ both give `i`, ੁ and ੂ both give `u`,
    /// which is what the house style does (Ji, Ki, Sri, Guru).
    func testDependentVowelSigns() {
        expect([
            ("ਕ", "Ka"), ("ਕਾ", "Kaa"), ("ਕਿ", "Ki"), ("ਕੀ", "Ki"),
            ("ਕੁ", "Ku"), ("ਕੂ", "Ku"), ("ਕੇ", "Ke"), ("ਕੈ", "Kai"),
            ("ਕੋ", "Ko"), ("ਕੌ", "Kau"),
        ])
    }

    /// The bearers ੳ ਅ ੲ take whichever matra follows them.
    func testVowelBearersTakeTheirMatra() {
        expect([
            ("\u{0A73}\u{0A42}", "U"),   // ੳ + dulainkar
            ("\u{0A72}\u{0A40}", "I"),   // ੲ + bihari
            ("\u{0A05}\u{0A3E}", "Aa"),  // ਅ + kanna
        ])
    }

    /// A vowel-initial syllable after an i/u takes the matching glide.
    func testGlideBeforeVowelInitialSyllable() {
        expect([
            ("ਧਿਆਨ", "Dhiyaan"),
            ("ਪਿਆਰੇ", "Piyaare"),
            ("ਸੁਆਸ", "Suwaas"),
            ("ਜੀਓ", "Jiyo"),
            ("ਹੋਇ", "Hoi"),    // no glide after o
            ("ਭਾਈ", "Bhaai"),  // no glide after aa
        ])
    }

    // MARK: - Nasalization, gemination, conjuncts

    func testNasalization() {
        expect([
            ("ਪੰਥ", "Panth"),       // tippi → n
            ("ਅੰਗ", "Ang"),
            ("ਸਿੰਘ", "Singh"),
            ("ਗੋਬਿੰਦ", "Gobind"),
            ("ਮਾਂ", "Maan"),        // bindi → n
            ("ਨਹੀਂ", "Nahin"),
            ("ਦਸਾਂ", "Dasaan"),
            ("ਅੰਮ੍ਰਿਤ", "Amrit"),   // homorganic m + ਮ written once
            ("ਮੰਨ", "Man"),         // homorganic n + ਨ written once
        ])
    }

    func testAddakGemination() {
        expect([
            ("ਦਿੱਤੇ", "Ditte"),
            ("ਸੱਚੇ", "Sacche"),
            ("ਸਰਬੱਤ", "Sarbat"),  // gemination lands word-final → written single
            ("ਅਟੱਲ", "Atal"),      // same; the SGPC layer has "Attal"
            ("ਸਿੱਖ", "Sikh"),
            ("ਸਿੱਖੀ", "Sikhi"),    // aspirates are not doubled ("Sikkhi")
            ("ਸੁੱਖ", "Sukh"),
        ])
    }

    func testSubscriptConjuncts() {
        expect([
            ("ਪ੍ਰਿਥਮ", "Pritham"),      // pairin rara
            ("ਕ੍ਰਿਪਾ", "Kripaa"),
            ("ਪ੍ਰਭੂ", "Prabhu"),
            ("ਤਿਨ੍ਹਾਂ", "Tinhaan"),     // pairin haha
            ("ਖੁਲ੍ਹੇ", "Khulhe"),
            ("ਸ੍ਵਾਸ", "Svaas"),         // pairin wawa
            ("ਚੜ੍ਹਦੀ", "Charhdi"),      // ੜ is "rh"; the pairin haha is absorbed
        ])
    }

    // MARK: - Schwa deletion

    func testWordFinalSchwaDeletion() {
        expect([
            ("ਨਾਨਕ", "Naanak"),
            ("ਭਗਤ", "Bhagat"),
            ("ਧਰਮ", "Dharam"),
            ("ਅਕਾਲ", "Akaal"),
            ("ਸ਼ਬਦ", "Shabad"),
        ])
    }

    func testMedialSchwaDeletion() {
        expect([
            ("ਅਰਦਾਸ", "Ardaas"),          // a·ra·daa·s → ardaas
            ("ਪਾਤਸ਼ਾਹੀ", "Paatshaahi"),   // SGPC layer: "Paatshaahee"
            ("ਹਰਗੋਬਿੰਦ", "Hargobind"),
            ("ਜ਼ੋਰਾਵਰ", "Zoraavar"),
            ("ਪੰਜਾਬੀ", "Panjaabi"),
        ])
    }

    /// A word-final sihari/aunkar is silent — the standard Gurbani reading
    /// convention, and what the SGPC layer does ("Simar Kai", not "Simari").
    func testWordFinalShortVowelElision() {
        expect([
            ("ਸਿਮਰਿ", "Simar"),
            ("ਸਤਿ", "Sat"),
            ("ਹਰਿ", "Har"),
            ("ਨਿਧਿ", "Nidh"),
            ("ਨਾਮੁ", "Naam"),
            ("ਅਮਰਦਾਸੁ", "Amardaas"),
            ("ਕਿ", "Ki"),  // single syllable: the vowel is all it has
        ])
    }

    // MARK: - Exception lexicon

    func testExceptionLexicon() {
        expect([
            ("ਵਾਹਿਗੁਰੂ", "Waheguru"),  // rules alone: "Vaahiguru"
            ("ਵਾਹਗੁਰੂ", "Waheguru"),
            ("ਸਤਿਗੁਰੂ", "Satguru"),
            ("ਸਤਿਗੁਰ", "Satgur"),
            ("ਗੁਰੂ", "Guru"),
            ("ਜੀ", "Ji"),
            ("ਸ੍ਰੀ", "Sri"),
            ("ਸਿੰਘ", "Singh"),
            ("ਖਾਲਸਾ", "Khalsa"),       // rules alone: "Khaalsaa"
            ("ਅਰਦਾਸ", "Ardaas"),
            ("ਨੂੰ", "Noon"),
            ("ਫਤਿਹ", "Fateh"),
            ("ਫਤਹਿ", "Fateh"),
        ])
        // The nukta spelling of ਫ਼ਤਹਿ resolves to the same entry.
        expect([("\u{0A2B}\u{0A3C}\u{0A24}\u{0A39}\u{0A3F}", "Fateh")])
        // Lexicon entries only cover the listed forms; inflections fall back
        // to the rules, which is a deliberate limit on the table's size.
        expect([("ਖਾਲਸੇ", "Khaalse")])
    }

    // MARK: - Symbols, digits, punctuation

    func testIkOnkar() {
        expect([
            ("ੴ", "Ik-Onkar"),
            ("ੴ ਸਤਿ ਨਾਮੁ", "Ik-Onkar Sat Naam"),
        ])
    }

    func testDandas() {
        expect([
            ("ਬੋਲੇ ਸੋ ਨਿਹਾਲ ॥ ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ ॥", "Bole So Nihaal || Sat Sri Akaal ||"),
            ("ਖਾਲਸਾ ਜੀ ।", "Khalsa Ji |"),
            ("ਜੀ॥", "Ji ||"),   // a danda is always set off by one space
            ("ਜੀ ॥", "Ji ||"),  // …and never doubles an existing one
        ])
    }

    func testGurmukhiDigits() {
        expect([
            ("੦੧੨੩੪੫੬੭੮੯", "0123456789"),
            ("ਪਾਤਸ਼ਾਹੀ ੧੦॥", "Paatshaahi 10 ||"),
        ])
    }

    // MARK: - Passthrough

    func testLatinAndMixedScriptPassthrough() {
        expect([
            ("ਗੁਰੂ Nanak ji", "Guru Nanak ji"),  // Latin is not re-cased
            ("ਵਾਹਿਗੁਰੂ (Waheguru) 2026", "Waheguru (Waheguru) 2026"),
            ("Ardaas", "Ardaas"),
            ("", ""),
        ])
    }

    func testWhitespaceAndLineBreaksArePreserved() {
        expect([
            ("   ", "   "),
            ("ਜੀ\nਜੀ", "Ji\nJi"),
            ("ਸਤਿ  ਸ੍ਰੀ\tਅਕਾਲ", "Sat  Sri\tAkaal"),
            ("\n\nਜੀ\n", "\n\nJi\n"),
        ])
    }

    // MARK: - Real phrases

    /// Phrases from the bundled SGPC text where the house style is
    /// unambiguous, plus a few where this engine deliberately differs.
    func testRealPhrases() {
        expect([
            ("ੴ ਵਾਹਿਗੁਰੂ ਜੀ ਕੀ ਫ਼ਤਹਿ॥", "Ik-Onkar Waheguru Ji Ki Fateh ||"),
            ("ਵਾਹਿਗੁਰੂ ਜੀ ਕੀ ਫਤਿਹ॥", "Waheguru Ji Ki Fateh ||"),
            ("ਪ੍ਰਿਥਮ ਭਗੌਤੀ ਸਿਮਰਿ ਕੈ", "Pritham Bhagauti Simar Kai"),
            ("ਦੇਗ ਤੇਗ ਫ਼ਤਹ", "Deg Teg Fateh"),
            ("ਬੋਲੋ ਜੀ ਵਾਹਿਗੁਰੂ॥", "Bolo Ji Waheguru ||"),
            // SGPC layer: "Waheguru Ji Ka Khalsa" — ਕਾ is a kanna, so this
            // engine writes the long vowel: "Kaa".
            ("ਵਾਹਿਗੁਰੂ ਜੀ ਕਾ ਖਾਲਸਾ॥", "Waheguru Ji Kaa Khalsa ||"),
            // SGPC layer: "Naanak Naam Chardi Kala" — ੜ੍ਹ is written "rh" and
            // final kanna as "aa", both consistently applied here.
            (
                "ਨਾਨਕ ਨਾਮ ਚੜ੍ਹਦੀ ਕਲਾ, ਤੇਰੇ ਭਾਣੇ ਸਰਬੱਤ ਦਾ ਭਲਾ॥",
                "Naanak Naam Charhdi Kalaa, Tere Bhaane Sarbat Daa Bhalaa ||"
            ),
            // SGPC layer: "Sabh Thaai" in one segment and "Sabh Thai" in
            // another. Neither is derivable: the bindi on ਥਾਂ is written.
            ("ਸਭ ਥਾਂਈ", "Sabh Thaani"),
        ])
    }

    func testMultiLineSegmentKeepsItsShape() {
        let input = "ਸ੍ਰੀ ਭਗੌਤੀ ਜੀ ਸਹਾਇ॥\nਵਾਰ ਸ੍ਰੀ ਭਗੌਤੀ ਜੀ ਕੀ ਪਾਤਸ਼ਾਹੀ ੧੦॥"
        XCTAssertEqual(
            GurmukhiTransliterator.transliterate(input),
            "Sri Bhagauti Ji Sahaai ||\nVaar Sri Bhagauti Ji Ki Paatshaahi 10 ||"
        )
    }

    /// A benti is free text: Gurmukhi, Latin and punctuation mixed.
    func testBentiStyleFreeText() {
        expect([
            ("ਮੇਰਾ ਨਾਮ ਸੁੱਖ ਹੈ", "Meraa Naam Sukh Hai"),
            ("ਅਰਦਾਸ ਹੈ ਜੀ — please help", "Ardaas Hai Ji — please help"),
        ])
    }

    func testTransliterationIsDeterministic() {
        let input = "ੴ ਵਾਹਿਗੁਰੂ ਜੀ ਕੀ ਫ਼ਤਹਿ॥ ਸਭ ਥਾਂਈ ਹੋਇ ਸਹਾਇ॥"
        let first = GurmukhiTransliterator.transliterate(input)
        for _ in 0..<3 {
            XCTAssertEqual(GurmukhiTransliterator.transliterate(input), first)
        }
    }

    /// The engine must produce a non-empty layer for every bundled segment —
    /// its second job is generating the Buddha Dal variant's missing layer.
    func testEveryBundledSegmentTransliterates() throws {
        let library = try ArdaasLibrary.loadBundled()
        for variant in library.variants {
            for segment in variant.content.segments {
                let output = GurmukhiTransliterator.transliterate(segment.gurmukhi)
                XCTAssertFalse(
                    output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                    "empty transliteration for \(variant.id)/\(segment.id)"
                )
                XCTAssertFalse(
                    output.unicodeScalars.contains { (0x0A00...0x0A7F).contains($0.value) },
                    "untransliterated Gurmukhi left in \(variant.id)/\(segment.id): \(output)"
                )
                XCTAssertEqual(
                    output.filter { $0 == "\n" }.count,
                    segment.gurmukhi.filter { $0 == "\n" }.count,
                    "line count changed for \(variant.id)/\(segment.id)"
                )
            }
        }
    }
}
