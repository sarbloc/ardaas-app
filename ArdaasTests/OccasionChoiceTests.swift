import XCTest
@testable import Ardaas

/// The occasion *choice*: how it is stored on a record, and how it resolves
/// to the layers the composer splices in.
final class OccasionChoiceTests: XCTestCase {
    private func bundledCatalog() throws -> OccasionCatalog {
        try OccasionCatalog.loadBundled()
    }

    // MARK: - Storage

    func testEmptyIdIsNoChoice() {
        XCTAssertEqual(OccasionChoice(occasionId: "", occasionCustom: ""), .unset)
        XCTAssertEqual(OccasionChoice.unset.storedId, "")
        XCTAssertEqual(OccasionChoice.unset.storedCustomText, "")
    }

    func testACatalogChoiceStoresOnlyItsId() {
        let choice = OccasionChoice.catalog(id: "japji-sahib")
        XCTAssertEqual(choice.storedId, "japji-sahib")
        XCTAssertEqual(choice.storedCustomText, "")
        XCTAssertEqual(
            OccasionChoice(occasionId: choice.storedId, occasionCustom: choice.storedCustomText),
            choice
        )
    }

    /// Free text rides the sentinel id, so a stored id is always either empty
    /// or a real catalog id — a user typing "birthday" is never mistaken for
    /// the catalog's `birthday` entry.
    func testFreeTextRoundTripsUnderTheSentinelId() {
        let choice = OccasionChoice.custom(text: "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ")
        XCTAssertEqual(choice.storedId, OccasionChoice.customId)
        XCTAssertEqual(choice.storedCustomText, "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ")
        XCTAssertEqual(
            OccasionChoice(occasionId: choice.storedId, occasionCustom: choice.storedCustomText),
            choice
        )
        XCTAssertNotEqual(OccasionChoice.custom(text: "birthday"), .catalog(id: "birthday"))
    }

    func testStoredValuesAreTrimmed() {
        XCTAssertEqual(OccasionChoice.custom(text: "  a birth  ").storedCustomText, "a birth")
        XCTAssertEqual(OccasionChoice.catalog(id: " japji-sahib ").storedId, "japji-sahib")
        XCTAssertEqual(
            OccasionChoice(occasionId: "  ", occasionCustom: "orphaned"), .unset
        )
    }

    /// The sentinel with nothing typed is not a choice — an abandoned
    /// "Other…" draft must not blank or fill the slot.
    func testTheSentinelWithBlankTextIsNoChoice() throws {
        XCTAssertEqual(
            OccasionChoice(occasionId: OccasionChoice.customId, occasionCustom: " \n "), .unset
        )
        let catalog = try bundledCatalog()
        XCTAssertNil(OccasionChoice.custom(text: "   ").layers(in: catalog))
    }

    // MARK: - Resolution

    func testACatalogChoiceResolvesToAllThreeBundledLayers() throws {
        let catalog = try bundledCatalog()
        let layers = try XCTUnwrap(OccasionChoice.catalog(id: "japji-sahib").layers(in: catalog))
        let occasion = try XCTUnwrap(catalog.occasion(id: "japji-sahib"))
        XCTAssertEqual(layers.gurmukhi, occasion.gurmukhi)
        XCTAssertEqual(layers.transliteration, occasion.transliteration)
        XCTAssertEqual(layers.english, occasion.english)
    }

    /// An entry retired from a later bundle leaves the canonical dots rather
    /// than crashing or blanking the sentence.
    func testAnUnknownIdResolvesToNothing() throws {
        let catalog = try bundledCatalog()
        XCTAssertNil(OccasionChoice.catalog(id: "no-such-occasion").layers(in: catalog))
        XCTAssertNil(OccasionChoice.unset.layers(in: catalog))
    }

    // MARK: - Free text

    /// Gurmukhi free text *is* the Gurmukhi layer, and its Roman layer is
    /// derived with the same engine that generated the bundled catalog's —
    /// so a typed occasion and a picked one romanize in one voice.
    func testGurmukhiFreeTextFillsGurmukhiAndTransliteration() throws {
        let layers = try XCTUnwrap(OccasionLayers(freeText: " ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ ਦੇ ਸਮਾਗਮ "))
        XCTAssertEqual(layers.gurmukhi, "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ ਦੇ ਸਮਾਗਮ")
        XCTAssertEqual(
            layers.transliteration,
            GurmukhiTransliterator.transliterate("ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ ਦੇ ਸਮਾਗਮ")
        )
        XCTAssertFalse(layers.transliteration.isEmpty)
        XCTAssertFalse(
            layers.transliteration.unicodeScalars.contains { (0x0A00...0x0A7F).contains($0.value) }
        )
        // Not translated: the English layer keeps its dots rather than
        // showing Gurmukhi the reader asked not to see.
        XCTAssertEqual(layers.english, "")
    }

    /// Anything not in Gurmukhi script goes to the English layer verbatim —
    /// including Roman-script Punjabi, since the detector counts scripts, not
    /// languages, and this app never guesses Gurmukhi back out of Roman.
    func testNonGurmukhiFreeTextFillsOnlyEnglish() throws {
        for text in ["my daughter's first birthday", "Amrit Sanchaar de samaagam", "108"] {
            let layers = try XCTUnwrap(OccasionLayers(freeText: text))
            XCTAssertEqual(layers.english, text)
            XCTAssertEqual(layers.gurmukhi, "")
            XCTAssertEqual(layers.transliteration, "")
        }
    }

    func testBlankFreeTextIsNothing() {
        XCTAssertNil(OccasionLayers(freeText: ""))
        XCTAssertNil(OccasionLayers(freeText: " \n\t "))
    }

    func testFreeTextResolvesThroughTheChoice() throws {
        let catalog = try bundledCatalog()
        let layers = try XCTUnwrap(OccasionChoice.custom(text: "a new home").layers(in: catalog))
        XCTAssertEqual(layers.english, "a new home")
    }

    // MARK: - Layers

    func testLayersAreTrimmedAndSubscriptable() {
        let layers = OccasionLayers(gurmukhi: "  ਪਾਠ\n", transliteration: " Paath ", english: " a paath ")
        XCTAssertEqual(layers[.gurmukhi], "ਪਾਠ")
        XCTAssertEqual(layers[.transliteration], "Paath")
        XCTAssertEqual(layers[.english], "a paath")
        XCTAssertFalse(layers.isEmpty)
    }

    func testEmptyLayersAreEmpty() {
        XCTAssertTrue(OccasionLayers().isEmpty)
        XCTAssertTrue(OccasionLayers(gurmukhi: " ", transliteration: "\n", english: "\t").isEmpty)
        XCTAssertFalse(OccasionLayers(english: "x").isEmpty)
    }
}
