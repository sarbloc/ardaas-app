import XCTest
@testable import Ardaas

/// Bundled-content tests for the "….." slot list, run against the real JSON —
/// CI-level protection of the content invariants the picker (#65–#67) will
/// rely on.
final class OccasionCatalogTests: XCTestCase {

    /// The slot marker as the bundled variants actually spell it: one ellipsis
    /// character followed by two full stops (U+2026 ".."), not five dots.
    private static let slotMarker = "\u{2026}.."

    private func occasion(
        id: String = "japji-sahib",
        category: OccasionCategory = .paath,
        gurmukhi: String = "ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ",
        transliteration: String = "Jap Ji Saahib De Paath",
        english: String = "the recitation of Japji Sahib"
    ) -> Occasion {
        Occasion(
            id: id, category: category, gurmukhi: gurmukhi,
            transliteration: transliteration, english: english
        )
    }

    private func catalog(_ occasions: [Occasion]) -> OccasionCatalog {
        OccasionCatalog(version: 1, sources: ["test"], occasions: occasions)
    }

    // MARK: - The bundled catalog

    func testBundledCatalogLoadsAndValidates() throws {
        let catalog = try OccasionCatalog.loadBundled()
        XCTAssertEqual(catalog.version, 1)
        XCTAssertFalse(catalog.sources.isEmpty)
        // Pinned so that adding or removing an entry is a deliberate content
        // change, re-proof-read like any other scripture-adjacent text.
        XCTAssertEqual(catalog.occasions.map(\.id), [
            "japji-sahib", "jaap-sahib", "rehras-sahib", "kirtan-sohila",
            "sukhmani-sahib", "chaupai-sahib", "anand-sahib",
            "sehaj-paath-bhog", "akhand-paath-bhog",
            "birthday", "new-home", "recovery", "studies", "bereavement",
            "thanksgiving",
        ])
    }

    func testBundledCatalogGroupsIntoPaathsAndOccasions() throws {
        let catalog = try OccasionCatalog.loadBundled()
        XCTAssertEqual(catalog.occasions(in: .paath).count, 9)
        XCTAssertEqual(catalog.occasions(in: .occasion).count, 6)
        // Categories are contiguous in bundled order, so the picker can render
        // them as two sections without re-sorting.
        XCTAssertEqual(
            catalog.occasions.map(\.category),
            Array(repeating: OccasionCategory.paath, count: 9)
                + Array(repeating: OccasionCategory.occasion, count: 6)
        )
    }

    func testBundledIdsAreStableSlugs() throws {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789-")
        for occasion in try OccasionCatalog.loadBundled().occasions {
            XCTAssertFalse(occasion.id.isEmpty)
            XCTAssertTrue(
                occasion.id.unicodeScalars.allSatisfy { allowed.contains($0) },
                "id is not a slug: \(occasion.id)"
            )
        }
    }

    // MARK: - The genitive constraint

    /// The whole point of the list: the slot sits **inside** a genitive
    /// construction, so an entry has to read correctly followed by `ਦੀ ਅਰਦਾਸ`.
    /// A bare name (`ਜਪੁ ਜੀ ਸਾਹਿਬ`) does not; the genitive form
    /// (`ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ`) does.
    ///
    /// This drops each entry into the real bundled sentence of **both**
    /// variants and asserts the result actually contains `<entry> ਦੀ ਅਰਦਾਸ`,
    /// which is what breaks if a later edit shortens an entry back to a name.
    func testEveryEntryClosesTheSlotSentenceInBothVariants() throws {
        let catalog = try OccasionCatalog.loadBundled()
        let library = try ArdaasLibrary.loadBundled()
        XCTAssertFalse(library.variants.isEmpty)

        for variant in library.variants {
            let slotId = variant.content.bentiSlot.afterSegmentId
            let segment = try XCTUnwrap(
                variant.content.segments.first { $0.id == slotId }
            )
            XCTAssertTrue(
                segment.gurmukhi.contains(Self.slotMarker),
                "\(variant.id) slot segment has no ….. marker"
            )
            for occasion in catalog.occasions {
                // The marker is written tight against its neighbours
                // ("ਹਜ਼ੂਰ…..ਦੀ"), so the entry is spaced in as the reader will
                // render it.
                let filled = segment.gurmukhi.replacingOccurrences(
                    of: Self.slotMarker, with: " \(occasion.gurmukhi) "
                )
                XCTAssertTrue(
                    filled.contains("\(occasion.gurmukhi) ਦੀ ਅਰਦਾਸ"),
                    "\(occasion.id) does not read into \(variant.id)'s sentence: \(filled)"
                )
            }
        }
    }

    /// An entry is a noun phrase for the slot, not a sentence: it must not
    /// carry the words that already surround the slot, nor its own punctuation.
    func testEntriesAreBareNounPhrases() throws {
        for occasion in try OccasionCatalog.loadBundled().occasions {
            for forbidden in ["ਅਰਦਾਸ", "ਹਜ਼ੂਰ", Self.slotMarker, "॥", "।"] {
                XCTAssertFalse(
                    occasion.gurmukhi.contains(forbidden),
                    "\(occasion.id) contains \(forbidden), which the sentence already supplies"
                )
            }
            XCTAssertEqual(
                occasion.gurmukhi, occasion.gurmukhi.trimmingCharacters(in: .whitespacesAndNewlines)
            )
            XCTAssertFalse(occasion.gurmukhi.contains("\n"))
            XCTAssertFalse(occasion.english.isEmpty)
        }
    }

    // MARK: - The generated Roman layer

    /// The Roman layer is **generated** by `GurmukhiTransliterator`, not
    /// hand-authored — that is what makes a chosen occasion and a user's benti
    /// look like one voice. Pinning it here is what stops the bundled JSON
    /// silently going stale: any rule change that would alter these strings
    /// fails CI and forces the layer to be regenerated (and re-proof-read).
    ///
    /// Same contract as `testBundledBuddhaDalLayerMatchesTheEngine`.
    func testBundledTransliterationMatchesTheEngine() throws {
        let catalog = try OccasionCatalog.loadBundled()
        XCTAssertFalse(catalog.occasions.isEmpty)
        for occasion in catalog.occasions {
            XCTAssertEqual(
                occasion.transliteration,
                GurmukhiTransliterator.transliterate(occasion.gurmukhi),
                "bundled transliteration has drifted from the engine "
                    + "for \(occasion.id) — regenerate the layer"
            )
            XCTAssertFalse(
                occasion.transliteration.unicodeScalars
                    .contains { (0x0A00...0x0A7F).contains($0.value) },
                "untransliterated Gurmukhi left in \(occasion.id)"
            )
        }
    }

    // MARK: - Validation

    func testValidateRejectsAnEmptyCatalog() {
        XCTAssertThrowsError(try catalog([]).validate()) { error in
            XCTAssertEqual(error as? OccasionCatalogError, .noOccasions)
        }
    }

    func testValidateRejectsDuplicateIds() {
        let duplicated = catalog([occasion(), occasion(category: .occasion)])
        XCTAssertThrowsError(try duplicated.validate()) { error in
            XCTAssertEqual(error as? OccasionCatalogError, .duplicateOccasionIds)
        }
    }

    /// All three layers are required, unlike a variant's optional ones: the
    /// picker shows whichever the reader has turned on, so a missing layer
    /// would render as a blank row.
    func testValidateRejectsAMissingLayer() {
        for broken in [
            occasion(id: "no-gurmukhi", gurmukhi: ""),
            occasion(id: "no-transliteration", transliteration: ""),
            occasion(id: "no-english", english: ""),
        ] {
            XCTAssertThrowsError(try catalog([broken]).validate()) { error in
                XCTAssertEqual(
                    error as? OccasionCatalogError, .emptyLayer(occasionId: broken.id)
                )
            }
        }
    }

    func testValidateAcceptsAWellFormedCatalog() throws {
        try catalog([occasion(), occasion(id: "birthday", category: .occasion)]).validate()
    }

    func testDecodingRejectsAnUnknownCategory() throws {
        let json = Data("""
        {"version": 1, "sources": [], "occasions": [
          {"id": "x", "category": "prayer", "gurmukhi": "ਪਾਠ",
           "transliteration": "Paath", "english": "a paath"}
        ]}
        """.utf8)
        XCTAssertThrowsError(try JSONDecoder().decode(OccasionCatalog.self, from: json))
    }
}
