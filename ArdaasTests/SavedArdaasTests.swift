import SwiftData
import XCTest
@testable import Ardaas

@MainActor
final class SavedArdaasTests: XCTestCase {
    private func makeContainer() throws -> ModelContainer {
        try ModelContainer(
            for: SavedArdaas.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
    }

    /// The two new layers default to empty, so a record written by the
    /// pre-three-layer app (and any call site that still passes only
    /// `bentiText`) is well-formed without a migration plan.
    func testNewLayersDefaultToEmpty() {
        let saved = SavedArdaas(label: "Morning", bentiText: "my benti")
        XCTAssertEqual(saved.bentiGurmukhi, "")
        XCTAssertEqual(saved.bentiTransliteration, "")
        XCTAssertEqual(saved.bentiText, "my benti")
    }

    func testBentiEnglishAliasesTheStoredText() {
        let saved = SavedArdaas(label: "Morning", bentiText: "my benti")
        XCTAssertEqual(saved.bentiEnglish, "my benti")
        saved.bentiEnglish = "edited benti"
        XCTAssertEqual(saved.bentiText, "edited benti")
    }

    func testBentiLayersExposesAllThreeLayers() {
        let saved = SavedArdaas(
            label: "Morning",
            bentiText: "my benti",
            bentiGurmukhi: "ਮੇਰੀ ਬੇਨਤੀ",
            bentiTransliteration: "merī bentī"
        )
        XCTAssertEqual(
            saved.bentiLayers,
            BentiLayers(gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ", transliteration: "merī bentī", english: "my benti")
        )
    }

    /// An untranslated record still yields a composable benti — only the
    /// English layer is populated, and it is not empty.
    func testUntranslatedRecordYieldsEnglishOnlyLayers() {
        let saved = SavedArdaas(label: "Morning", bentiText: "  my benti  ")
        XCTAssertEqual(saved.bentiLayers, BentiLayers(english: "my benti"))
        XCTAssertFalse(saved.bentiLayers.isEmpty)
    }

    func testBlankRecordYieldsEmptyLayers() {
        let saved = SavedArdaas(label: "Morning", bentiText: " \n ")
        XCTAssertTrue(saved.bentiLayers.isEmpty)
    }

    /// All three layers survive a store round-trip, so the layers a variant
    /// does not display are still kept.
    func testAllThreeLayersPersist() throws {
        let container = try makeContainer()
        let context = container.mainContext
        context.insert(
            SavedArdaas(
                label: "Morning",
                bentiText: "my benti",
                variantId: "buddha-dal",
                bentiGurmukhi: "ਮੇਰੀ ਬੇਨਤੀ",
                bentiTransliteration: "merī bentī"
            )
        )
        try context.save()

        let fetched = try XCTUnwrap(
            try context.fetch(FetchDescriptor<SavedArdaas>()).first
        )
        XCTAssertEqual(fetched.label, "Morning")
        XCTAssertEqual(fetched.variantId, "buddha-dal")
        XCTAssertEqual(fetched.bentiText, "my benti")
        XCTAssertEqual(fetched.bentiGurmukhi, "ਮੇਰੀ ਬੇਨਤੀ")
        XCTAssertEqual(fetched.bentiTransliteration, "merī bentī")
    }
}
