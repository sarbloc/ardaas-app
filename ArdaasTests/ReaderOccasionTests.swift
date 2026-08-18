import SwiftData
import XCTest
@testable import Ardaas

/// The slot marker as the bundled variants spell it: one ellipsis character
/// followed by two full stops (U+2026 ".."), not five dots.
private let marker = "\u{2026}.."

/// What the Reader renders for a saved record, and what its occasion picker
/// writes back.
///
/// The screen itself is not unit-testable, so everything it decides lives
/// behind two pure entry points it is the only caller of —
/// `SavedArdaas.renderItems(in:catalog:)` for the composition and
/// `OccasionChoice.willNotAppear(in:catalog:)` for the #72 warning — and both
/// are asserted here against the real bundled text and catalog.
@MainActor
final class ReaderOccasionTests: XCTestCase {
    private func bundledCatalog() throws -> OccasionCatalog {
        try OccasionCatalog.loadBundled()
    }

    private func content(id: String) throws -> ArdaasContent {
        try XCTUnwrap(ArdaasLibrary.loadBundled().variant(id: id)).content
    }

    private func makeContainer() throws -> ModelContainer {
        try ModelContainer(
            for: SavedArdaas.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
    }

    private func record(occasion: OccasionChoice = .unset) -> SavedArdaas {
        SavedArdaas(
            label: "Morning",
            bentiText: "bless the family",
            occasion: occasion
        )
    }

    /// Where the benti card sits in a composed sequence, if at all.
    private func bentiIndices(in items: [RenderItem]) -> [Int] {
        items.indices.filter { index -> Bool in
            guard case .benti = items[index] else { return false }
            return true
        }
    }

    /// The slot segment as the Reader would draw it, out of the full
    /// composed sequence.
    private func slotSegment(
        of saved: SavedArdaas,
        in content: ArdaasContent,
        catalog: OccasionCatalog?,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws -> ArdaasSegment {
        let slotId = try XCTUnwrap(content.occasionSlot, file: file, line: line).inSegmentId
        let match = saved.renderItems(in: content, catalog: catalog)
            .compactMap { item -> ArdaasSegment? in
                guard case let .canonical(segment) = item, segment.id == slotId else { return nil }
                return segment
            }
        return try XCTUnwrap(match.first, file: file, line: line)
    }

    // MARK: - The Reader composes with the occasion (#67's first job)

    /// The gap this issue closes: before it, a choice saved in Compose was
    /// persisted and then never rendered.
    func testACatalogChoiceIsComposedIntoTheSlot() throws {
        let sgpc = try content(id: "sgpc")
        let segment = try slotSegment(
            of: record(occasion: .catalog(id: "japji-sahib")),
            in: sgpc,
            catalog: try bundledCatalog()
        )
        XCTAssertTrue(segment.gurmukhi.contains("ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ"), segment.gurmukhi)
        XCTAssertFalse(segment.gurmukhi.contains(marker), segment.gurmukhi)
        // Every layer the variant carries, not just the Gurmukhi.
        XCTAssertEqual(segment.english?.contains(marker), false)
        XCTAssertEqual(segment.transliteration?.contains("\u{2026}"), false)
    }

    func testFreeTextIsComposedIntoTheSlot() throws {
        let segment = try slotSegment(
            of: record(occasion: .custom(text: "my daughter's first birthday")),
            in: try content(id: "sgpc"),
            catalog: try bundledCatalog()
        )
        let english = try XCTUnwrap(segment.english)
        XCTAssertTrue(english.hasSuffix("my daughter's first birthday."), english)
        // The authored parenthetical goes with the dots it explains.
        XCTAssertFalse(english.contains("mention here"), english)
        // One script only: the Gurmukhi layer keeps its dots rather than
        // taking Roman words (see `OccasionLayers.init(freeText:)`).
        XCTAssertTrue(segment.gurmukhi.contains(marker), segment.gurmukhi)
    }

    /// Nothing chosen renders the sentence exactly as authored — the
    /// operator's decision for #65, and the state every pre-occasion record
    /// migrates to.
    func testNothingChosenLeavesTheDotsExactlyAsAuthored() throws {
        let sgpc = try content(id: "sgpc")
        let authored = try XCTUnwrap(
            sgpc.segments.first { $0.id == sgpc.occasionSlot?.inSegmentId }
        )
        let segment = try slotSegment(of: record(), in: sgpc, catalog: try bundledCatalog())
        XCTAssertEqual(segment, authored)
    }

    /// An entry retired from a later bundle leaves the canonical dots rather
    /// than a hole in the sentence.
    func testAnUnknownCatalogIdLeavesTheDots() throws {
        let segment = try slotSegment(
            of: record(occasion: .catalog(id: "no-such-occasion")),
            in: try content(id: "sgpc"),
            catalog: try bundledCatalog()
        )
        XCTAssertTrue(segment.gurmukhi.contains(marker), segment.gurmukhi)
    }

    /// A catalog that failed to load costs the reader the substitution, never
    /// the Ardaas: the text still renders, with its dots.
    func testAMissingCatalogLeavesTheDots() throws {
        let segment = try slotSegment(
            of: record(occasion: .catalog(id: "japji-sahib")),
            in: try content(id: "sgpc"),
            catalog: nil
        )
        XCTAssertTrue(segment.gurmukhi.contains(marker), segment.gurmukhi)
    }

    /// The occasion does not disturb the benti: it is still inserted, once,
    /// immediately after its own slot segment.
    func testTheBentiIsStillComposedInAlongsideTheOccasion() throws {
        let sgpc = try content(id: "sgpc")
        let saved = record(occasion: .catalog(id: "japji-sahib"))
        let items = saved.renderItems(in: sgpc, catalog: try bundledCatalog())

        let indices = bentiIndices(in: items)
        XCTAssertEqual(indices.count, 1)
        let index = try XCTUnwrap(indices.first)
        XCTAssertGreaterThan(index, 0, "the benti is never the first item")
        guard index > 0, case let .canonical(previous) = items[index - 1] else {
            return XCTFail("the benti should follow a canonical segment")
        }
        XCTAssertEqual(previous.id, sgpc.bentiSlot.afterSegmentId)
        XCTAssertEqual(items[index], .benti(saved.bentiLayers))
    }

    /// Reading the plain Ardaas with an occasion but no benti is valid use:
    /// no benti card, and the slot is still filled.
    func testAnOccasionRendersWithoutABenti() throws {
        let sgpc = try content(id: "sgpc")
        let catalog = try bundledCatalog()
        let saved = SavedArdaas(
            label: "Sangat",
            bentiText: "  ",
            occasion: .catalog(id: "sukhmani-sahib")
        )
        XCTAssertTrue(bentiIndices(in: saved.renderItems(in: sgpc, catalog: catalog)).isEmpty)
        let segment = try slotSegment(of: saved, in: sgpc, catalog: catalog)
        XCTAssertFalse(segment.gurmukhi.contains(marker), segment.gurmukhi)
    }

    // MARK: - The picker persists to the record

    /// The Reader's picker writes straight back, like the variant picker:
    /// the draft's choice through `occasionChoice`, which keeps the two
    /// stored fields consistent.
    func testChangingTheOccasionPersistsToTheRecord() throws {
        let container = try makeContainer()
        let saved = record()
        container.mainContext.insert(saved)

        var draft = OccasionDraft(choice: saved.occasionChoice)
        draft.selection = .catalog(id: "birthday")
        saved.occasionChoice = draft.choice
        try container.mainContext.save()

        let stored = try XCTUnwrap(
            try container.mainContext.fetch(FetchDescriptor<SavedArdaas>()).first
        )
        XCTAssertEqual(stored.occasionId, "birthday")
        XCTAssertEqual(stored.occasionCustom, "")
        XCTAssertEqual(stored.occasionChoice, .catalog(id: "birthday"))
        // And the Reader now renders it.
        let segment = try slotSegment(of: stored, in: try content(id: "sgpc"), catalog: try bundledCatalog())
        XCTAssertFalse(segment.gurmukhi.contains(marker), segment.gurmukhi)
    }

    /// Moving from a catalog entry to the user's own words leaves no trace of
    /// the id, and back again leaves no trace of the words.
    func testMovingBetweenChoicesNeverLeavesAStalePair() throws {
        let saved = record(occasion: .catalog(id: "birthday"))

        var draft = OccasionDraft(choice: saved.occasionChoice)
        draft.selection = .custom
        draft.customText = "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ"
        saved.occasionChoice = draft.choice
        XCTAssertEqual(saved.occasionId, OccasionChoice.customId)
        XCTAssertEqual(saved.occasionCustom, "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ")

        draft.selection = .catalog(id: "japji-sahib")
        saved.occasionChoice = draft.choice
        XCTAssertEqual(saved.occasionId, "japji-sahib")
        XCTAssertEqual(saved.occasionCustom, "")

        draft.selection = .unset
        saved.occasionChoice = draft.choice
        XCTAssertEqual(saved.occasionChoice, .unset)
        XCTAssertEqual(saved.occasionId, "")
        XCTAssertEqual(saved.occasionCustom, "")
    }

    /// Clearing the free-text field is "nothing chosen" while it is empty —
    /// the same rule Compose applies at Save, applied live here — and the
    /// dots come back with it.
    func testEmptyingTheFreeTextClearsTheChoice() throws {
        let saved = record(occasion: .custom(text: "a new home"))
        var draft = OccasionDraft(choice: saved.occasionChoice)
        draft.customText = "   "
        saved.occasionChoice = draft.choice

        XCTAssertEqual(saved.occasionChoice, .unset)
        let segment = try slotSegment(of: saved, in: try content(id: "sgpc"), catalog: try bundledCatalog())
        XCTAssertEqual(segment.english?.contains(marker), true)
    }

    // MARK: - Stranding free text by switching variant (#72)

    /// The state only the Reader can create: free text written for a variant
    /// that carries English, then the variant picker moves it to one that
    /// does not. The choice is kept — the words are the user's — but the
    /// reader sees the dots, so the Reader says so at the slot.
    func testSwitchingToAVariantWithoutEnglishStrandsLatinFreeText() throws {
        let catalog = try bundledCatalog()
        let saved = record(occasion: .custom(text: "my daughter's first birthday"))

        XCTAssertFalse(saved.occasionChoice.willNotAppear(in: try content(id: "sgpc"), catalog: catalog))

        saved.variantId = "buddha-dal"
        let buddhaDal = try content(id: "buddha-dal")
        XCTAssertTrue(saved.occasionChoice.willNotAppear(in: buddhaDal, catalog: catalog))
        // And the warning is honest: the slot really does still read as
        // authored.
        let segment = try slotSegment(of: saved, in: buddhaDal, catalog: catalog)
        XCTAssertTrue(segment.gurmukhi.contains(marker), segment.gurmukhi)
        XCTAssertEqual(saved.occasionChoice, .custom(text: "my daughter's first birthday"))
    }

    /// Catalog entries and Gurmukhi free text reach Buddha Dal's Gurmukhi
    /// layer, so switching variants strands nothing and no warning shows.
    func testSwitchingVariantsDoesNotStrandGurmukhiOrCatalogChoices() throws {
        let catalog = try bundledCatalog()
        let buddhaDal = try content(id: "buddha-dal")

        for choice in [OccasionChoice.catalog(id: "japji-sahib"), .custom(text: "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ")] {
            let saved = record(occasion: choice)
            XCTAssertFalse(saved.occasionChoice.willNotAppear(in: buddhaDal, catalog: catalog), "\(choice)")
            let segment = try slotSegment(of: saved, in: buddhaDal, catalog: catalog)
            XCTAssertFalse(segment.gurmukhi.contains(marker), segment.gurmukhi)
        }
    }

    /// Nothing chosen is never a warning on either variant: leaving the slot
    /// alone is a normal way to read the Ardaas.
    func testNothingChosenIsNeverFlagged() throws {
        let catalog = try bundledCatalog()
        for id in ["sgpc", "buddha-dal"] {
            XCTAssertFalse(
                record().occasionChoice.willNotAppear(in: try content(id: id), catalog: catalog)
            )
        }
    }
}
