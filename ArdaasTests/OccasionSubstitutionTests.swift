import XCTest
@testable import Ardaas

/// The slot marker as the bundled variants spell it: one ellipsis character
/// followed by two full stops (U+2026 ".."), not five dots.
private let marker = "\u{2026}.."

/// Composition-time substitution of a chosen occasion into the "….." slot.
/// Pure logic, so it is tested against both hand-built fixtures (for the
/// rules) and the real bundled variants (for the sentences readers see).
final class OccasionSubstitutionTests: XCTestCase {
    private let japji = OccasionLayers(
        gurmukhi: "ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ",
        transliteration: "Jap Ji Saahib De Paath",
        english: "the recitation of Japji Sahib"
    )

    /// A three-layer variant whose slot segment is written tight against the
    /// marker, exactly like the bundled ones.
    private func content(
        gurmukhi: String = "ਆਪ ਦੇ ਹਜ਼ੂਰ\(marker)ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥",
        transliteration: String? = "Aap De Hazur\(marker)Di Ardas Hai Ji ||",
        english: String? = "we humbly make prayer in your presence\(marker) (mention here).",
        placeholder: OccasionPlaceholder = OccasionPlaceholder(
            gurmukhi: marker, transliteration: marker, english: "\(marker) (mention here)"
        )
    ) -> ArdaasContent {
        ArdaasContent.fixture(
            segments: [
                .fixture(id: "before"),
                ArdaasSegment(
                    id: "slot", gurmukhi: gurmukhi,
                    transliteration: transliteration, english: english
                ),
                .fixture(id: "after"),
            ],
            slotAfter: "slot",
            occasionSlot: OccasionSlot(inSegmentId: "slot", placeholder: placeholder)
        )
    }

    /// Composes and returns the slot segment as rendered.
    private func slotSegment(
        of content: ArdaasContent,
        occasion: OccasionLayers?,
        id: String = "slot",
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws -> ArdaasSegment {
        let items = ArdaasComposer.compose(content: content, benti: nil, occasion: occasion)
        let match = items.compactMap { item -> ArdaasSegment? in
            guard case let .canonical(segment) = item, segment.id == id else { return nil }
            return segment
        }
        return try XCTUnwrap(match.first, file: file, line: line)
    }

    // MARK: - The substitution rule

    func testEachLayerTakesItsOwnLayerOfTheOccasion() throws {
        let segment = try slotSegment(of: content(), occasion: japji)
        XCTAssertEqual(segment.gurmukhi, "ਆਪ ਦੇ ਹਜ਼ੂਰ ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥")
        XCTAssertEqual(
            segment.transliteration,
            "Aap De Hazur Jap Ji Saahib De Paath Di Ardas Hai Ji ||"
        )
        XCTAssertEqual(
            segment.english,
            "we humbly make prayer in your presence the recitation of Japji Sahib."
        )
    }

    /// The operator's decision for an unfilled slot: the dots stay exactly as
    /// authored, so the reader still sees where to say their own words.
    func testNothingChosenLeavesThePlaceholderUntouched() throws {
        let original = content()
        let segment = try slotSegment(of: original, occasion: nil)
        XCTAssertEqual(segment, original.segments[1])
        XCTAssertTrue(segment.gurmukhi.contains(marker))
    }

    func testAnAllBlankOccasionLeavesThePlaceholderUntouched() throws {
        let original = content()
        let segment = try slotSegment(of: original, occasion: OccasionLayers())
        XCTAssertEqual(segment, original.segments[1])
    }

    /// An occasion with no words for a layer leaves *that* layer's dots
    /// alone. Blanking the slot would delete part of a canonical sentence.
    func testALayerTheOccasionLacksKeepsItsDots() throws {
        let gurmukhiOnly = OccasionLayers(gurmukhi: "ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ")
        let segment = try slotSegment(of: content(), occasion: gurmukhiOnly)
        XCTAssertFalse(segment.gurmukhi.contains(marker))
        XCTAssertEqual(segment.transliteration, "Aap De Hazur\(marker)Di Ardas Hai Ji ||")
        XCTAssertTrue(try XCTUnwrap(segment.english).contains(marker))
    }

    /// A layer the *variant* lacks stays absent — never an empty string,
    /// which `ArdaasContent.validate()`'s all-or-none rule would reject and
    /// the Reader would render as a blank line.
    func testALayerTheVariantLacksStaysNil() throws {
        let gurmukhiOnly = content(
            transliteration: nil,
            english: nil,
            placeholder: OccasionPlaceholder(
                gurmukhi: marker, transliteration: nil, english: nil
            )
        )
        let segment = try slotSegment(of: gurmukhiOnly, occasion: japji)
        XCTAssertFalse(segment.gurmukhi.contains(marker))
        XCTAssertNil(segment.transliteration)
        XCTAssertNil(segment.english)
    }

    /// An undeclared placeholder for a layer is not guessed at: no marker is
    /// searched for, so that layer is left exactly as authored.
    func testAnUndeclaredLayerPlaceholderIsNotSubstituted() throws {
        let partial = content(placeholder: OccasionPlaceholder(
            gurmukhi: marker, transliteration: nil, english: nil
        ))
        let segment = try slotSegment(of: partial, occasion: japji)
        XCTAssertFalse(segment.gurmukhi.contains(marker))
        XCTAssertEqual(segment.transliteration, "Aap De Hazur\(marker)Di Ardas Hai Ji ||")
    }

    func testOtherSegmentsAreUntouched() {
        let items = ArdaasComposer.compose(content: content(), benti: nil, occasion: japji)
        XCTAssertEqual(items.count, 3)
        XCTAssertEqual(items.first, .canonical(.fixture(id: "before")))
        XCTAssertEqual(items.last, .canonical(.fixture(id: "after")))
    }

    /// Composition is pure: the content a caller holds must come back
    /// canonical, or a second read would render an occasion nobody chose.
    func testCompositionDoesNotMutateTheContent() {
        let original = content()
        let before = original
        _ = ArdaasComposer.compose(content: original, benti: nil, occasion: japji)
        XCTAssertEqual(original, before)
        XCTAssertTrue(original.segments[1].gurmukhi.contains(marker))
    }

    /// A variant with no declared slot never substitutes, and does not crash.
    func testAVariantWithoutASlotIsUnchanged() {
        let noSlot = ArdaasContent.fixture(segments: [.fixture(id: "a")], slotAfter: "a")
        XCTAssertEqual(
            ArdaasComposer.compose(content: noSlot, benti: nil, occasion: japji),
            [.canonical(.fixture(id: "a"))]
        )
    }

    /// An occasion slot naming a segment that isn't there is a no-op too —
    /// unreachable for bundled content, which `validate()` rejects at load.
    func testAnUnknownSlotSegmentIsANoOp() {
        let broken = ArdaasContent.fixture(
            segments: [.fixture(id: "a")], slotAfter: "a",
            occasionSlot: .fixture(inSegmentId: "missing")
        )
        XCTAssertEqual(
            ArdaasComposer.compose(content: broken, benti: nil, occasion: japji),
            [.canonical(.fixture(id: "a"))]
        )
    }

    /// The benti still goes in after its own slot segment, with the occasion
    /// spliced into that segment's text — the two slots are independent.
    func testTheBentiStillComposesAlongsideAnOccasion() throws {
        let benti = BentiLayers(english: "my benti")
        let items = ArdaasComposer.compose(content: content(), benti: benti, occasion: japji)
        XCTAssertEqual(items.count, 4)
        XCTAssertEqual(items[2], .benti(benti))
        guard case let .canonical(segment) = items[1] else {
            return XCTFail("expected the slot segment before the benti")
        }
        XCTAssertTrue(segment.gurmukhi.contains("ਹਜ਼ੂਰ ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਦੀ"))
    }

    // MARK: - Seams

    /// The dots stand in for the spaces around the words as well as for the
    /// words, so the occasion is spaced into a tight sentence…
    func testTightSeamsGainASingleSpace() throws {
        let segment = try slotSegment(of: content(), occasion: japji)
        XCTAssertTrue(segment.gurmukhi.contains("ਹਜ਼ੂਰ ਜਪੁ"))
        XCTAssertTrue(segment.gurmukhi.contains("ਪਾਠ ਦੀ ਅਰਦਾਸ"))
    }

    /// …and whitespace already at a seam is absorbed rather than doubled —
    /// SGPC's Roman layer writes a bare `…` followed by a space.
    func testExistingWhitespaceAtASeamIsNotDoubled() throws {
        let spaced = content(
            transliteration: "Aap De Hazur\u{2026} Di Ardas ||",
            english: nil,
            placeholder: OccasionPlaceholder(
                gurmukhi: marker, transliteration: "\u{2026}", english: nil
            )
        )
        let segment = try slotSegment(of: spaced, occasion: japji)
        XCTAssertEqual(segment.transliteration, "Aap De Hazur Jap Ji Saahib De Paath Di Ardas ||")
    }

    /// No space is added before punctuation: SGPC's English placeholder is
    /// followed by the sentence's full stop.
    func testPunctuationAfterTheSlotIsNotPushedAway() throws {
        let segment = try slotSegment(of: content(), occasion: japji)
        XCTAssertTrue(try XCTUnwrap(segment.english).hasSuffix("Japji Sahib."))
    }

    /// A placeholder that opens the text gains no leading space.
    func testAPlaceholderAtTheStartGainsNoLeadingSpace() throws {
        let leading = content(
            gurmukhi: "\(marker)ਦੀ ਅਰਦਾਸ",
            transliteration: nil,
            english: nil,
            placeholder: OccasionPlaceholder(
                gurmukhi: marker, transliteration: nil, english: nil
            )
        )
        let segment = try slotSegment(of: leading, occasion: japji)
        XCTAssertEqual(segment.gurmukhi, "ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਦੀ ਅਰਦਾਸ")
    }

    // MARK: - The bundled variants

    func testBundledSgpcSentenceReadsCorrectlyWithAnOccasion() throws {
        let content = try XCTUnwrap(ArdaasLibrary.loadBundled().variant(id: "sgpc")).content
        let segment = try slotSegment(of: content, occasion: japji, id: "nimaniyan-de-maan")
        XCTAssertTrue(
            segment.gurmukhi.hasSuffix("ਆਪ ਦੇ ਹਜ਼ੂਰ ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥"),
            segment.gurmukhi
        )
        XCTAssertTrue(
            try XCTUnwrap(segment.transliteration)
                .contains("Hazur Jap Ji Saahib De Paath Di Ardas Hai Ji")
        )
        // The English placeholder swallows the parenthetical instruction:
        // once an occasion is named, "(mention here …)" is stale.
        let english = try XCTUnwrap(segment.english)
        XCTAssertTrue(english.hasSuffix("in your presence the recitation of Japji Sahib."), english)
        XCTAssertFalse(english.contains("mention here"))
    }

    func testBundledBuddhaDalSentenceReadsCorrectlyWithAnOccasion() throws {
        let content = try XCTUnwrap(ArdaasLibrary.loadBundled().variant(id: "buddha-dal")).content
        let segment = try slotSegment(of: content, occasion: japji, id: "he-deen-dayal")
        XCTAssertTrue(
            segment.gurmukhi.contains("ਆਪ ਜੀ ਦੇ ਹਜ਼ੂਰ ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਦੀ ਅਰਦਾਸ"),
            segment.gurmukhi
        )
        XCTAssertTrue(
            try XCTUnwrap(segment.transliteration)
                .contains("Hazur Jap Ji Saahib De Paath Di Ardaas")
        )
        // No attested English layer, and none is invented.
        XCTAssertNil(segment.english)
    }

    /// Every bundled entry has to read into every bundled variant, in every
    /// layer that variant declares a placeholder for: the genitive still
    /// closes with `ਦੀ ਅਰਦਾਸ`, and no dots are left behind.
    func testEveryBundledOccasionSubstitutesIntoEveryVariant() throws {
        let catalog = try OccasionCatalog.loadBundled()
        for variant in try ArdaasLibrary.loadBundled().variants {
            let slot = try XCTUnwrap(variant.content.occasionSlot)
            for occasion in catalog.occasions {
                let layers = OccasionChoice.catalog(id: occasion.id).layers(in: catalog)
                let segment = try slotSegment(
                    of: variant.content, occasion: layers, id: slot.inSegmentId
                )
                XCTAssertTrue(
                    segment.gurmukhi.contains("\(occasion.gurmukhi) ਦੀ ਅਰਦਾਸ"),
                    "\(occasion.id) does not read into \(variant.id): \(segment.gurmukhi)"
                )
                for layer in LayerKind.allCases {
                    guard slot.placeholder[layer] != nil,
                          let text = segment.text(for: layer) else { continue }
                    XCTAssertFalse(
                        text.contains("\u{2026}"),
                        "\(occasion.id) left dots in \(variant.id)'s \(layer) layer: \(text)"
                    )
                }
            }
        }
    }
}
