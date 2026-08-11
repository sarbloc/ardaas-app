import XCTest
@testable import Ardaas

private func toggles(
    gurmukhi: Bool = true,
    transliteration: Bool = true,
    english: Bool = true
) -> LayerToggles {
    LayerToggles(gurmukhi: gurmukhi, transliteration: transliteration, english: english)
}

/// The Reader's layer-visibility decisions, extracted from the view so they
/// can be exercised directly: which benti layers render under which toggles,
/// and which layers the controls may offer.
final class BentiVisibleLinesTests: XCTestCase {
    private let full = BentiLayers(
        gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ",
        transliteration: "merī bentī",
        english: "my benti"
    )

    private func kinds(_ layers: BentiLayers, _ toggles: LayerToggles) -> [LayerKind] {
        layers.visibleLines(under: toggles).map(\.kind)
    }

    // MARK: - Three populated layers

    func testAllLayersOnRendersAllThreeInLayerOrder() {
        XCTAssertEqual(full.visibleLines(under: toggles()), [
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
                kinds(full, toggles(
                    gurmukhi: gurmukhi, transliteration: transliteration, english: english
                )),
                expected,
                "toggles g:\(gurmukhi) t:\(transliteration) e:\(english)"
            )
        }
    }

    // MARK: - Never blank

    /// All toggles off would leave a visible-but-empty benti card, so the
    /// first populated layer renders anyway.
    func testAllTogglesOffFallsBackToFirstPopulatedLayer() {
        XCTAssertEqual(
            kinds(full, toggles(gurmukhi: false, transliteration: false, english: false)),
            [.gurmukhi]
        )
    }

    /// The fallback is the first *populated* layer in layer order, not
    /// simply Gurmukhi — an untranslated benti has no Gurmukhi to show.
    func testFallbackPicksFirstPopulatedLayerNotGurmukhi() {
        let awaitingGurmukhi = BentiLayers(transliteration: "merī bentī", english: "my benti")
        XCTAssertEqual(
            kinds(awaitingGurmukhi, toggles(gurmukhi: false, transliteration: false, english: false)),
            [.transliteration]
        )
    }

    // MARK: - Partially populated bentis

    /// A benti awaiting translation carries only the words the user typed.
    func testEnglishOnlyBentiRendersOnlyEnglish() {
        XCTAssertEqual(kinds(BentiLayers(english: "my benti"), toggles()), [.english])
    }

    /// ...and it stays visible when the English toggle is off, because
    /// hiding it would blank the card.
    func testEnglishOnlyBentiSurvivesItsToggleBeingOff() {
        XCTAssertEqual(
            kinds(BentiLayers(english: "my benti"), toggles(english: false)),
            [.english]
        )
    }

    /// A benti typed straight in Gurmukhi shows Gurmukhi + the generated
    /// transliteration, and nothing where the translation will go.
    func testGurmukhiBentiRendersGurmukhiAndTransliteration() {
        let benti = BentiLayers(gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ", transliteration: "merī bentī")
        XCTAssertEqual(kinds(benti, toggles()), [.gurmukhi, .transliteration])
        XCTAssertEqual(kinds(benti, toggles(transliteration: false)), [.gurmukhi])
    }

    func testGurmukhiOnlyBentiRendersOnlyGurmukhi() {
        let benti = BentiLayers(gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ")
        XCTAssertEqual(kinds(benti, toggles()), [.gurmukhi])
        XCTAssertEqual(kinds(benti, toggles(gurmukhi: false)), [.gurmukhi])
    }

    /// A layer that is only whitespace was never written (`BentiLayers`
    /// normalises), so it is not a candidate and cannot be the fallback.
    func testWhitespaceOnlyLayerIsTreatedAsUnwritten() {
        let benti = BentiLayers(gurmukhi: "   \n", english: "my benti")
        XCTAssertEqual(kinds(benti, toggles()), [.english])
        XCTAssertEqual(
            kinds(benti, toggles(gurmukhi: false, transliteration: false, english: false)),
            [.english]
        )
    }

    /// Defensive: the composer drops an all-blank benti, so the Reader never
    /// asks — but the function is total and yields nothing to draw.
    func testEmptyBentiYieldsNoLines() {
        XCTAssertEqual(BentiLayers().visibleLines(under: toggles()), [])
    }

    func testLineTextIsTheLayerText() {
        let lines = full.visibleLines(
            under: toggles(gurmukhi: false, transliteration: false)
        )
        XCTAssertEqual(lines.map(\.text), ["my benti"])
    }
}

/// `hidesLayer` is what stops the Reader offering a control that cannot take
/// effect: with the never-blank fallback in play, switching a layer off does
/// not always remove it.
final class BentiHidesLayerTests: XCTestCase {
    private let full = BentiLayers(
        gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ",
        transliteration: "merī bentī",
        english: "my benti"
    )

    func testSwitchingOffOneOfSeveralLayersHidesIt() {
        XCTAssertTrue(full.hidesLayer(.english, under: toggles()))
        XCTAssertTrue(full.hidesLayer(.transliteration, under: toggles()))
    }

    /// The pending-translation case: English is all the benti has, so its
    /// toggle cannot remove it and the pill must be disabled, not dead.
    func testUntranslatedBentiCannotHideItsOnlyLayer() {
        XCTAssertFalse(BentiLayers(english: "my benti").hidesLayer(.english, under: toggles()))
    }

    func testGurmukhiOnlyBentiCannotHideGurmukhi() {
        XCTAssertFalse(BentiLayers(gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ").hidesLayer(.gurmukhi, under: toggles()))
    }

    /// The last layer standing is unhideable whichever layer it is: with the
    /// others already off, switching this one off just triggers the fallback.
    func testLastLayerStandingIsUnhideable() {
        XCTAssertFalse(
            full.hidesLayer(.gurmukhi, under: toggles(transliteration: false, english: false))
        )
    }

    /// ...but a layer that is not first in render order does hide, because
    /// the fallback picks the first populated layer instead.
    func testLayerHidesWhenTheFallbackWouldPickADifferentOne() {
        XCTAssertTrue(
            full.hidesLayer(.english, under: toggles(gurmukhi: false, transliteration: false))
        )
    }

    /// A layer that is not rendered at all cannot be hidden further.
    func testAlreadyHiddenLayerReportsNoChange() {
        XCTAssertFalse(full.hidesLayer(.english, under: toggles(english: false)))
        XCTAssertFalse(BentiLayers(english: "my benti").hidesLayer(.gurmukhi, under: toggles()))
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

    /// Gurmukhi is never optional — every variant carries it.
    func testGurmukhiIsAlwaysCarried() {
        XCTAssertTrue(LayerAvailability.canonical(gurmukhiOnlyVariant).carries(.gurmukhi))
        XCTAssertFalse(LayerAvailability.canonical(gurmukhiOnlyVariant).carries(.english))
    }

    /// The never-blank guard's input: Gurmukhi off with only layers this
    /// variant lacks toggled on leaves the segments with nothing.
    func testCanonicalCountIsZeroWhenOnlyUnavailableLayersAreOn() {
        XCTAssertEqual(
            LayerAvailability.canonical(gurmukhiOnlyVariant)
                .visibleLayerCount(under: toggles(gurmukhi: false)),
            0
        )
    }

    // MARK: - What a segment renders

    func testSegmentRendersTheToggledLayersTheVariantCarries() {
        XCTAssertEqual(
            LayerAvailability.canonical(fullVariant).renderedLayers(under: toggles()),
            [.gurmukhi, .transliteration, .english]
        )
        XCTAssertEqual(
            LayerAvailability.canonical(noEnglishVariant).renderedLayers(under: toggles()),
            [.gurmukhi, .transliteration]
        )
        XCTAssertEqual(
            LayerAvailability.canonical(fullVariant)
                .renderedLayers(under: toggles(transliteration: false)),
            [.gurmukhi, .english]
        )
    }

    /// The segments' never-blank guard: Gurmukhi renders anyway rather than
    /// leaving an empty segment.
    func testSegmentFallsBackToGurmukhiWhenNothingElseWouldRender() {
        XCTAssertEqual(
            LayerAvailability.canonical(gurmukhiOnlyVariant)
                .renderedLayers(under: toggles(gurmukhi: false)),
            [.gurmukhi]
        )
        XCTAssertEqual(
            LayerAvailability.canonical(fullVariant).renderedLayers(
                under: toggles(gurmukhi: false, transliteration: false, english: false)
            ),
            [.gurmukhi]
        )
    }

    // MARK: - Whether a toggle can take effect

    func testCanonicalLayerHidesWhenTheVariantCarriesSomethingElse() {
        let availability = LayerAvailability.canonical(fullVariant)
        XCTAssertTrue(availability.hidesLayer(.gurmukhi, under: toggles()))
        XCTAssertTrue(availability.hidesLayer(.english, under: toggles()))
    }

    /// On a Gurmukhi-only variant the guard restores Gurmukhi immediately,
    /// so its toggle cannot take effect and the Reader pins it — even when a
    /// benti-only layer is keeping the screen-wide count above one.
    func testGurmukhiCannotHideOnAGurmukhiOnlyVariant() {
        XCTAssertFalse(
            LayerAvailability.canonical(gurmukhiOnlyVariant)
                .hidesLayer(.gurmukhi, under: toggles())
        )
    }

    /// A layer the variant does not carry was never rendered canonically, so
    /// switching it off changes nothing there (the benti decides separately).
    func testUncarriedLayerHidesNothingCanonically() {
        XCTAssertFalse(
            LayerAvailability.canonical(noEnglishVariant).hidesLayer(.english, under: toggles())
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
        XCTAssertFalse(
            LayerAvailability.screen(
                content: noEnglishVariant,
                benti: BentiLayers(gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ")
            ).english
        )
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
            availability.visibleLayerCount(under: toggles(transliteration: false)),
            2
        )
        // English alone: it is the last visible layer, so its pill is
        // disabled rather than letting the reader empty the screen.
        XCTAssertEqual(
            availability.visibleLayerCount(under: toggles(gurmukhi: false, transliteration: false)),
            1
        )
    }

    func testCountIgnoresLayersThatAreOnButUnavailable() {
        XCTAssertEqual(
            LayerAvailability.screen(content: gurmukhiOnlyVariant, benti: BentiLayers())
                .visibleLayerCount(under: toggles()),
            1
        )
    }

    func testCountIsZeroWhenEverythingIsOff() {
        XCTAssertEqual(
            LayerAvailability.screen(content: fullVariant, benti: benti).visibleLayerCount(
                under: toggles(gurmukhi: false, transliteration: false, english: false)
            ),
            0
        )
    }
}

final class LayerTogglesTests: XCTestCase {
    func testSubscriptReadsTheMatchingToggle() {
        let state = toggles(transliteration: false)
        XCTAssertTrue(state[.gurmukhi])
        XCTAssertFalse(state[.transliteration])
        XCTAssertTrue(state[.english])
    }

    func testSettingChangesOnlyTheNamedLayer() {
        XCTAssertEqual(
            toggles().setting(.english, to: false),
            toggles(english: false)
        )
    }
}
