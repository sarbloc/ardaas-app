import XCTest
@testable import Ardaas

/// The Reader's layer-visibility decisions, extracted from the view so they
/// can be exercised directly: which benti layers render under which toggles,
/// and which layers the controls may offer.
final class BentiVisibleLinesTests: XCTestCase {
    private let full = BentiLayers(
        gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ",
        transliteration: "merī bentī",
        english: "my benti"
    )

    private func kinds(
        _ layers: BentiLayers,
        _ showGurmukhi: Bool,
        _ showTransliteration: Bool,
        _ showEnglish: Bool
    ) -> [LayerKind] {
        layers.visibleLines(
            showGurmukhi: showGurmukhi,
            showTransliteration: showTransliteration,
            showEnglish: showEnglish
        ).map(\.kind)
    }

    // MARK: - Three populated layers

    func testAllLayersOnRendersAllThreeInLayerOrder() {
        let lines = full.visibleLines(
            showGurmukhi: true, showTransliteration: true, showEnglish: true
        )
        XCTAssertEqual(lines, [
            BentiLine(kind: .gurmukhi, text: "ਮੇਰੀ ਬੇਨਤੀ"),
            BentiLine(kind: .transliteration, text: "merī bentī"),
            BentiLine(kind: .english, text: "my benti"),
        ])
    }

    /// Every toggle combination for a fully populated benti. Seven of the
    /// eight are the toggles taken literally; all-off falls back (below).
    func testEveryToggleCombinationSelectsExactlyTheToggledLayers() {
        let cases: [(Bool, Bool, Bool, [LayerKind])] = [
            (true, true, true, [.gurmukhi, .transliteration, .english]),
            (true, true, false, [.gurmukhi, .transliteration]),
            (true, false, true, [.gurmukhi, .english]),
            (false, true, true, [.transliteration, .english]),
            (true, false, false, [.gurmukhi]),
            (false, true, false, [.transliteration]),
            (false, false, true, [.english]),
        ]
        for (gurmukhi, transliteration, english, expected) in cases {
            XCTAssertEqual(
                kinds(full, gurmukhi, transliteration, english),
                expected,
                "toggles g:\(gurmukhi) t:\(transliteration) e:\(english)"
            )
        }
    }

    // MARK: - Never blank

    /// All toggles off would leave a visible-but-empty benti card, so the
    /// first populated layer renders anyway.
    func testAllTogglesOffFallsBackToFirstPopulatedLayer() {
        XCTAssertEqual(kinds(full, false, false, false), [.gurmukhi])
    }

    /// The fallback is the first *populated* layer in layer order, not
    /// simply Gurmukhi — an untranslated benti has no Gurmukhi to show.
    func testFallbackPicksFirstPopulatedLayerNotGurmukhi() {
        let awaitingGurmukhi = BentiLayers(transliteration: "merī bentī", english: "my benti")
        XCTAssertEqual(kinds(awaitingGurmukhi, false, false, false), [.transliteration])
    }

    // MARK: - Partially populated bentis

    /// A benti awaiting translation carries only the words the user typed.
    func testEnglishOnlyBentiRendersOnlyEnglish() {
        let benti = BentiLayers(english: "my benti")
        XCTAssertEqual(kinds(benti, true, true, true), [.english])
    }

    /// ...and it stays visible when the English toggle is off, because
    /// hiding it would blank the card.
    func testEnglishOnlyBentiSurvivesItsToggleBeingOff() {
        let benti = BentiLayers(english: "my benti")
        XCTAssertEqual(kinds(benti, true, true, false), [.english])
    }

    /// A benti typed straight in Gurmukhi shows Gurmukhi + the generated
    /// transliteration, and nothing where the translation will go.
    func testGurmukhiBentiRendersGurmukhiAndTransliteration() {
        let benti = BentiLayers(gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ", transliteration: "merī bentī")
        XCTAssertEqual(kinds(benti, true, true, true), [.gurmukhi, .transliteration])
        XCTAssertEqual(kinds(benti, true, false, true), [.gurmukhi])
    }

    func testGurmukhiOnlyBentiRendersOnlyGurmukhi() {
        let benti = BentiLayers(gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ")
        XCTAssertEqual(kinds(benti, true, true, true), [.gurmukhi])
        XCTAssertEqual(kinds(benti, false, true, true), [.gurmukhi])
    }

    /// A layer that is only whitespace was never written (`BentiLayers`
    /// normalises), so it is not a candidate and cannot be the fallback.
    func testWhitespaceOnlyLayerIsTreatedAsUnwritten() {
        let benti = BentiLayers(gurmukhi: "   \n", english: "my benti")
        XCTAssertEqual(kinds(benti, true, true, true), [.english])
        XCTAssertEqual(kinds(benti, false, false, false), [.english])
    }

    /// Defensive: the composer drops an all-blank benti, so the Reader never
    /// asks — but the function is total and yields nothing to draw.
    func testEmptyBentiYieldsNoLines() {
        XCTAssertEqual(BentiLayers().visibleLines(
            showGurmukhi: true, showTransliteration: true, showEnglish: true
        ), [])
    }

    func testLineTextIsTheLayerText() {
        let lines = full.visibleLines(
            showGurmukhi: false, showTransliteration: false, showEnglish: true
        )
        XCTAssertEqual(lines.map(\.text), ["my benti"])
    }
}

final class LayerAvailabilityTests: XCTestCase {
    private let benti = BentiLayers(
        gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ",
        transliteration: "merī bentī",
        english: "my benti"
    )

    /// A variant like Buddha Dal: Gurmukhi + transliteration, no attested
    /// English.
    private let noEnglishVariant = ArdaasContent.fixture(
        segments: [ArdaasSegment(id: "a", gurmukhi: "ਗ", transliteration: "g", english: nil)],
        slotAfter: "a"
    )

    private let gurmukhiOnlyVariant = ArdaasContent.fixture(
        segments: [ArdaasSegment(id: "a", gurmukhi: "ਗ", transliteration: nil, english: nil)],
        slotAfter: "a"
    )

    private let fullVariant = ArdaasContent.fixture(
        segments: [.fixture(id: "a")],
        slotAfter: "a"
    )

    // MARK: - Canonical scope

    func testCanonicalAvailabilityIsTheVariantsOwnCoverage() {
        XCTAssertEqual(
            LayerAvailability.canonical(fullVariant),
            LayerAvailability(transliteration: true, english: true)
        )
        XCTAssertEqual(
            LayerAvailability.canonical(noEnglishVariant),
            LayerAvailability(transliteration: true, english: false)
        )
        XCTAssertEqual(
            LayerAvailability.canonical(gurmukhiOnlyVariant),
            LayerAvailability(transliteration: false, english: false)
        )
    }

    /// The pre-load frame: assume coverage rather than disabling toggles on
    /// content we haven't read yet.
    func testCanonicalAvailabilityAssumesCoverageBeforeContentLoads() {
        XCTAssertEqual(
            LayerAvailability.canonical(nil),
            LayerAvailability(transliteration: true, english: true)
        )
    }

    /// The never-blank guard's input: Gurmukhi off with only layers this
    /// variant lacks toggled on leaves the segments with nothing.
    func testCanonicalCountIsZeroWhenOnlyUnavailableLayersAreOn() {
        XCTAssertEqual(
            LayerAvailability.canonical(gurmukhiOnlyVariant).visibleLayerCount(
                showGurmukhi: false, showTransliteration: true, showEnglish: true
            ),
            0
        )
    }

    // MARK: - Screen scope (the #45 rule)

    /// The operator decision: the benti's English is the user's own words,
    /// so it renders on a variant with no attested English — which means the
    /// English control must be offered there too, or it could not be hidden.
    func testBentiEnglishMakesEnglishAvailableOnAVariantWithoutIt() {
        let availability = LayerAvailability.screen(content: noEnglishVariant, benti: benti)
        XCTAssertTrue(availability.english)
        XCTAssertFalse(LayerAvailability.canonical(noEnglishVariant).english)
    }

    /// The mirror: with nothing to show, the layer stays unavailable, so no
    /// pill appears for a layer neither the variant nor the benti carries.
    func testEnglishStaysUnavailableWhenNeitherVariantNorBentiHasIt() {
        let availability = LayerAvailability.screen(
            content: noEnglishVariant,
            benti: BentiLayers(gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ")
        )
        XCTAssertFalse(availability.english)
    }

    func testBentiTransliterationMakesTransliterationAvailable() {
        let availability = LayerAvailability.screen(
            content: gurmukhiOnlyVariant,
            benti: BentiLayers(gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ", transliteration: "merī bentī")
        )
        XCTAssertTrue(availability.transliteration)
        XCTAssertFalse(availability.english)
    }

    /// The union never shrinks the variant's own coverage.
    func testScreenAvailabilityIncludesTheVariantsCoverageWithABlankBenti() {
        XCTAssertEqual(
            LayerAvailability.screen(content: fullVariant, benti: BentiLayers()),
            LayerAvailability(transliteration: true, english: true)
        )
    }

    // MARK: - Counting

    func testScreenCountKeepsABentiOnlyLayerSwitchableOffTheLastLayer() {
        let availability = LayerAvailability.screen(content: noEnglishVariant, benti: benti)
        // Gurmukhi + the benti's English are both on: two layers visible, so
        // the English pill is not the last one and stays enabled.
        XCTAssertEqual(
            availability.visibleLayerCount(
                showGurmukhi: true, showTransliteration: false, showEnglish: true
            ),
            2
        )
        // English alone: it is the last visible layer, so its pill is
        // disabled rather than letting the reader empty the screen.
        XCTAssertEqual(
            availability.visibleLayerCount(
                showGurmukhi: false, showTransliteration: false, showEnglish: true
            ),
            1
        )
    }

    func testCountIgnoresLayersThatAreOnButUnavailable() {
        XCTAssertEqual(
            LayerAvailability.screen(content: gurmukhiOnlyVariant, benti: BentiLayers())
                .visibleLayerCount(
                    showGurmukhi: true, showTransliteration: true, showEnglish: true
                ),
            1
        )
    }

    func testCountIsZeroWhenEverythingIsOff() {
        XCTAssertEqual(
            LayerAvailability.screen(content: fullVariant, benti: benti).visibleLayerCount(
                showGurmukhi: false, showTransliteration: false, showEnglish: false
            ),
            0
        )
    }
}
