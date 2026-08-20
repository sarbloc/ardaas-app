import XCTest
@testable import Ardaas

/// The Compose screen's occasion state: what the picker's selection means for
/// storage, and what the in-context preview shows. The view itself is not
/// unit-testable here, so everything it decides lives in `OccasionDraft` and
/// is asserted directly.
final class OccasionDraftTests: XCTestCase {
    private func bundledCatalog() throws -> OccasionCatalog {
        try OccasionCatalog.loadBundled()
    }

    private func content(id: String) throws -> ArdaasContent {
        try XCTUnwrap(ArdaasLibrary.loadBundled().variant(id: id)).content
    }

    // MARK: - Selection → storage

    func testTheDefaultSelectionIsNoChoice() {
        let draft = OccasionDraft()
        XCTAssertEqual(draft.selection, .unset)
        XCTAssertEqual(draft.choice, .unset)
        XCTAssertFalse(draft.isCustom)
    }

    func testACatalogSelectionBecomesACatalogChoice() {
        var draft = OccasionDraft()
        draft.selection = .catalog(id: "japji-sahib")
        XCTAssertEqual(draft.choice, .catalog(id: "japji-sahib"))
        XCTAssertEqual(draft.choice.storedId, "japji-sahib")
        XCTAssertEqual(draft.choice.storedCustomText, "")
    }

    func testFreeTextBecomesACustomChoice() {
        var draft = OccasionDraft()
        draft.selection = .custom
        draft.customText = "my daughter's first birthday"
        XCTAssertTrue(draft.isCustom)
        XCTAssertEqual(draft.choice, .custom(text: "my daughter's first birthday"))
        XCTAssertEqual(draft.choice.storedId, OccasionChoice.customId)
    }

    func testFreeTextIsTrimmed() {
        var draft = OccasionDraft()
        draft.selection = .custom
        draft.customText = "  ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ  \n"
        XCTAssertEqual(draft.choice, .custom(text: "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ"))
    }

    /// "Other…" with nothing typed is not a choice: an abandoned draft must
    /// leave the canonical dots exactly as authored.
    func testBlankFreeTextIsNoChoice() {
        var draft = OccasionDraft()
        draft.selection = .custom
        XCTAssertEqual(draft.choice, .unset)
        draft.customText = "  \n\t "
        XCTAssertEqual(draft.choice, .unset)
    }

    /// Moving off "Other…" drops the words typed under it, so they can never
    /// be saved beside a catalog choice or resurface later.
    func testSwitchingAwayFromOtherClearsTheFreeText() {
        var draft = OccasionDraft()
        draft.selection = .custom
        draft.customText = "a new job"

        draft.selection = .catalog(id: "birthday")
        XCTAssertEqual(draft.customText, "")
        XCTAssertEqual(draft.choice, .catalog(id: "birthday"))

        draft.selection = .custom
        XCTAssertEqual(draft.customText, "")

        draft.customText = "a new job"
        draft.selection = .unset
        XCTAssertEqual(draft.customText, "")
        XCTAssertEqual(draft.choice, .unset)
    }

    /// Re-selecting the row already showing must not wipe what is being
    /// typed into it.
    func testReselectingOtherKeepsTheFreeText() {
        var draft = OccasionDraft()
        draft.selection = .custom
        draft.customText = "a new job"
        draft.selection = .custom
        XCTAssertEqual(draft.customText, "a new job")
    }

    // MARK: - Storage → selection (the Reader's picker opens on a saved choice)

    func testADraftSeededFromNothingChosenIsTheDefault() {
        XCTAssertEqual(OccasionDraft(choice: .unset), OccasionDraft())
    }

    func testADraftSeededFromACatalogChoiceSelectsThatRow() {
        let draft = OccasionDraft(choice: .catalog(id: "birthday"))
        XCTAssertEqual(draft.selection, .catalog(id: "birthday"))
        XCTAssertEqual(draft.customText, "")
        XCTAssertFalse(draft.isCustom)
    }

    /// Seeding must survive `selection`'s `didSet`, which clears the free
    /// text for every row but "Other…" — initialisation does not trigger it,
    /// which is exactly why the seeding initialiser is written the way it is.
    func testADraftSeededFromFreeTextKeepsTheWords() {
        let draft = OccasionDraft(choice: .custom(text: "a new home"))
        XCTAssertEqual(draft.selection, .custom)
        XCTAssertTrue(draft.isCustom)
        XCTAssertEqual(draft.customText, "a new home")
    }

    /// Round-tripping a saved choice through the picker and back changes
    /// nothing, so opening the Reader's picker cannot by itself rewrite a
    /// record.
    func testSeedingRoundTripsEveryStorableChoice() {
        let choices: [OccasionChoice] = [
            .unset, .catalog(id: "japji-sahib"), .custom(text: "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ"),
        ]
        for choice in choices {
            XCTAssertEqual(OccasionDraft(choice: choice).choice, choice)
        }
    }

    // MARK: - The in-context preview

    /// Nothing chosen shows the sentence exactly as authored — dots included.
    /// That is the teaching case: it is where the reader sees what the "….."
    /// actually is.
    func testNoChoicePreviewsTheAuthoredSentence() throws {
        let sgpc = try content(id: "sgpc")
        let preview = try XCTUnwrap(OccasionDraft().preview(in: sgpc, catalog: bundledCatalog()))
        XCTAssertFalse(preview.isFilled)
        XCTAssertEqual(preview.layer, .gurmukhi)
        XCTAssertTrue(preview.text.contains("\u{2026}.."), preview.text)
    }

    func testACatalogChoicePreviewsTheGurmukhiSentence() throws {
        var draft = OccasionDraft()
        draft.selection = .catalog(id: "japji-sahib")
        let preview = try XCTUnwrap(
            draft.preview(in: try content(id: "sgpc"), catalog: bundledCatalog())
        )
        XCTAssertTrue(preview.isFilled)
        XCTAssertEqual(preview.layer, .gurmukhi)
        XCTAssertTrue(preview.text.hasSuffix("ਆਪ ਦੇ ਹਜ਼ੂਰ ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥"), preview.text)
        XCTAssertFalse(preview.text.contains("\u{2026}.."))
    }

    /// English free text fills only the English layer, so previewing the
    /// Gurmukhi would show the untouched dots. The preview follows the
    /// substitution to the layer it actually landed in.
    func testEnglishFreeTextPreviewsTheEnglishSentence() throws {
        var draft = OccasionDraft()
        draft.selection = .custom
        draft.customText = "my daughter's first birthday"
        let preview = try XCTUnwrap(
            draft.preview(in: try content(id: "sgpc"), catalog: bundledCatalog())
        )
        XCTAssertTrue(preview.isFilled)
        XCTAssertEqual(preview.layer, .english)
        XCTAssertTrue(preview.text.hasSuffix("my daughter's first birthday."), preview.text)
        XCTAssertFalse(preview.text.contains("mention here"))
    }

    func testGurmukhiFreeTextPreviewsTheGurmukhiSentence() throws {
        var draft = OccasionDraft()
        draft.selection = .custom
        draft.customText = "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ ਦੇ ਸਮਾਗਮ"
        let preview = try XCTUnwrap(
            draft.preview(in: try content(id: "buddha-dal"), catalog: bundledCatalog())
        )
        XCTAssertTrue(preview.isFilled)
        XCTAssertEqual(preview.layer, .gurmukhi)
        XCTAssertTrue(preview.text.contains("ਹਜ਼ੂਰ ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ ਦੇ ਸਮਾਗਮ ਦੀ ਅਰਦਾਸ"), preview.text)
    }

    /// A variant with no occasion slot has nothing to preview — and does not
    /// crash. Unreachable for the bundled variants, both of which declare one.
    func testAVariantWithoutASlotHasNoPreview() throws {
        let noSlot = ArdaasContent.fixture(segments: [.fixture(id: "a")], slotAfter: "a")
        XCTAssertNil(OccasionDraft().preview(in: noSlot, catalog: try bundledCatalog()))
    }

    // MARK: - The Buddha Dal + Latin gap (#72)

    /// Buddha Dal carries no English layer, and free text that isn't in
    /// Gurmukhi script becomes the English layer and nothing else — so it has
    /// nowhere to appear and the reader would still see the dots.
    func testLatinFreeTextOnAVariantWithoutEnglishIsFlagged() throws {
        let buddhaDal = try content(id: "buddha-dal")
        let catalog = try bundledCatalog()
        var draft = OccasionDraft()
        draft.selection = .custom
        draft.customText = "my daughter's first birthday"

        XCTAssertTrue(draft.willNotAppear(in: buddhaDal, catalog: catalog))
        XCTAssertEqual(draft.preview(in: buddhaDal, catalog: catalog)?.isFilled, false)
        // The choice is still recorded — the words are the user's, and #72 is
        // about where they can be shown, not about discarding them.
        XCTAssertEqual(draft.choice, .custom(text: "my daughter's first birthday"))
    }

    /// The same text on a variant that does carry English is fine.
    func testLatinFreeTextOnAVariantWithEnglishIsNotFlagged() throws {
        var draft = OccasionDraft()
        draft.selection = .custom
        draft.customText = "my daughter's first birthday"
        XCTAssertFalse(
            draft.willNotAppear(in: try content(id: "sgpc"), catalog: try bundledCatalog())
        )
    }

    /// Gurmukhi free text and every bundled entry reach Buddha Dal's Gurmukhi
    /// layer, so nothing there is flagged.
    func testGurmukhiAndCatalogChoicesAreNeverFlagged() throws {
        let buddhaDal = try content(id: "buddha-dal")
        let catalog = try bundledCatalog()

        var typed = OccasionDraft()
        typed.selection = .custom
        typed.customText = "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ ਦੇ ਸਮਾਗਮ"
        XCTAssertFalse(typed.willNotAppear(in: buddhaDal, catalog: catalog))

        for entry in catalog.occasions {
            var picked = OccasionDraft()
            picked.selection = .catalog(id: entry.id)
            XCTAssertFalse(
                picked.willNotAppear(in: buddhaDal, catalog: catalog),
                "\(entry.id) does not reach any Buddha Dal layer"
            )
        }
    }

    /// Nothing chosen is not a warning: leaving the slot alone is a normal,
    /// supported way to read the Ardaas.
    func testNoChoiceIsNeverFlagged() throws {
        let catalog = try bundledCatalog()
        for id in ["sgpc", "buddha-dal"] {
            XCTAssertFalse(OccasionDraft().willNotAppear(in: try content(id: id), catalog: catalog))
        }
    }

    /// An abandoned "Other…" draft is not a warning either — it is not a
    /// choice at all.
    func testBlankFreeTextIsNeverFlagged() throws {
        var draft = OccasionDraft()
        draft.selection = .custom
        draft.customText = "   "
        XCTAssertFalse(
            draft.willNotAppear(in: try content(id: "buddha-dal"), catalog: try bundledCatalog())
        )
    }
}
