import SwiftData
import XCTest
@testable import Ardaas

/// Editing a saved Ardaas (#154): how a record reopens in Compose, and how
/// Save writes it back.
///
/// The screen itself is not unit-testable, so everything it decides lives in
/// pure values it is the only caller of — `ComposeForm(fields:)` and
/// `BentiDraft(saved:)` to reopen, `ComposeForm.fields` and
/// `SavedArdaas.apply(_:)` to save, `ComposeForm.hasChanges(from:)` for
/// Cancel — and those are asserted here.
@MainActor
final class EditSavedArdaasTests: XCTestCase {
    private func makeContainer() throws -> ModelContainer {
        try ModelContainer(
            for: SavedArdaas.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
    }

    /// The layers exactly as Compose saves them: the transliteration is
    /// always the transliterator's output for the Gurmukhi.
    private func layers(english: String = "", gurmukhi: String = "") -> BentiLayers {
        BentiLayers(
            gurmukhi: gurmukhi,
            transliteration: gurmukhi.isEmpty ? "" : GurmukhiTransliterator.transliterate(gurmukhi),
            english: english
        )
    }

    /// Every benti shape Compose can save.
    private var everyShape: [BentiLayers] {
        [
            // English only — never translated, or saved after Remove Gurmukhi.
            layers(english: "Please bless my family."),
            // English with its translation.
            layers(english: "Bless the sangat.", gurmukhi: "ਸਿੰਘ ਅਰਦਾਸ"),
            // English with Gurmukhi that no longer matches it ("Save both
            // anyway" on a stale draft).
            layers(english: "Bless the whole sangat.", gurmukhi: "ਅਰਦਾਸ"),
            // Typed in Gurmukhi, or the English deleted after translating.
            layers(gurmukhi: "ਸਿੰਘ ਅਰਦਾਸ"),
            // The translation draft rewritten in Latin, then the English
            // deleted.
            layers(gurmukhi: "Singh"),
            // Digits only: not translatable, saved as English.
            layers(english: "108"),
        ]
    }

    // MARK: - Record → draft, shape by shape

    func testEnglishOnlyReopensAsTypedEnglishWithNoDraft() {
        let draft = BentiDraft(saved: layers(english: "Please bless my family."))
        XCTAssertEqual(draft.typed, "Please bless my family.")
        XCTAssertEqual(draft.gurmukhi, "")
        XCTAssertNil(draft.translatedFrom)
        XCTAssertFalse(draft.isDraftOpen)
        XCTAssertFalse(draft.showsTranslationDraft)
        XCTAssertFalse(draft.isStale)
    }

    /// The saved pair counts as one translation, with its section open.
    func testEnglishWithGurmukhiReopensAsOneTranslation() {
        let draft = BentiDraft(saved: layers(english: "Bless the sangat.", gurmukhi: "ਸਿੰਘ ਅਰਦਾਸ"))
        XCTAssertEqual(draft.typed, "Bless the sangat.")
        XCTAssertEqual(draft.gurmukhi, "ਸਿੰਘ ਅਰਦਾਸ")
        XCTAssertEqual(draft.translatedFrom, "Bless the sangat.")
        XCTAssertTrue(draft.isDraftOpen)
        XCTAssertTrue(draft.showsTranslationDraft)
        XCTAssertEqual(draft.transliteration, "Singh Ardaas")
    }

    /// Gurmukhi with no English goes back in the benti editor, where it was
    /// typed; the draft section stays shut.
    func testGurmukhiOnlyReopensAsTypedGurmukhi() {
        let draft = BentiDraft(saved: layers(gurmukhi: "ਸਿੰਘ ਅਰਦਾਸ"))
        XCTAssertEqual(draft.typed, "ਸਿੰਘ ਅਰਦਾਸ")
        XCTAssertEqual(draft.gurmukhi, "")
        XCTAssertNil(draft.translatedFrom)
        XCTAssertTrue(draft.isTypedInGurmukhi)
        XCTAssertFalse(draft.isDraftOpen)
        XCTAssertFalse(draft.showsTranslationDraft)
        XCTAssertEqual(draft.transliteration, "Singh Ardaas")
    }

    /// Latin text in the Gurmukhi layer would save as English if it went in
    /// the benti editor, so it reopens as an open draft instead.
    func testNonGurmukhiTextInTheGurmukhiLayerReopensAsADraft() {
        let draft = BentiDraft(saved: layers(gurmukhi: "Singh"))
        XCTAssertEqual(draft.typed, "")
        XCTAssertEqual(draft.gurmukhi, "Singh")
        XCTAssertNil(draft.translatedFrom)
        XCTAssertTrue(draft.showsTranslationDraft)
        XCTAssertEqual(draft.layers, layers(gurmukhi: "Singh"))
    }

    func testEmptyBentiReopensEmpty() {
        XCTAssertEqual(BentiDraft(saved: BentiLayers()), BentiDraft())
    }

    /// The transliteration is never read back — it is regenerated from the
    /// Gurmukhi, so a stored one from older rules is refreshed on save.
    func testTheTransliterationIsRegeneratedNotReadBack() {
        let saved = BentiLayers(gurmukhi: "ਸਿੰਘ", transliteration: "singh (old rules)", english: "Bless us.")
        XCTAssertEqual(BentiDraft(saved: saved).layers.transliteration, "Singh")
    }

    // MARK: - Staleness

    func testReopeningATranslatedBentiIsNotStale() {
        for shape in everyShape {
            XCTAssertFalse(BentiDraft(saved: shape).isStale, "\(shape)")
        }
    }

    func testChangingTheEnglishAfterReopeningIsStale() {
        var draft = BentiDraft(saved: layers(english: "Bless the sangat.", gurmukhi: "ਸਿੰਘ ਅਰਦਾਸ"))

        // Padding is not a change.
        draft.typed = "  Bless the sangat.  "
        XCTAssertFalse(draft.isStale)

        draft.typed = "Bless the whole sangat."
        XCTAssertTrue(draft.isStale)

        // Translating again settles it.
        draft.applyTranslation("ਅਰਦਾਸ", of: draft.translationSource)
        XCTAssertFalse(draft.isStale)
    }

    /// Without saved Gurmukhi there is nothing to go stale.
    func testChangingAnUntranslatedEnglishIsNotStale() {
        var draft = BentiDraft(saved: layers(english: "Bless the sangat."))
        draft.typed = "Bless the whole sangat."
        XCTAssertFalse(draft.isStale)
    }

    // MARK: - Round trip

    /// Record → draft → saved layers reproduces every shape exactly.
    func testEveryBentiShapeRoundTrips() {
        for shape in everyShape {
            XCTAssertEqual(BentiDraft(saved: shape).layers, shape, "\(shape)")
        }
    }

    /// The same property from the Compose side: whatever a draft saves, it
    /// reopens to a draft that saves the same.
    func testEveryComposeDraftReopensToTheSameLayers() {
        var translated = BentiDraft(typed: "Bless the sangat.")
        translated.applyTranslation("ਸਿੰਘ ਅਰਦਾਸ", of: translated.translationSource)

        var edited = translated
        edited.gurmukhi = "ਅਰਦਾਸ"

        var removed = translated
        removed.clearTranslation()

        var emptied = translated
        emptied.gurmukhi = "  "

        var englishDeleted = translated
        englishDeleted.typed = ""

        var stale = translated
        stale.typed = "Bless the whole sangat."

        let drafts = [
            BentiDraft(typed: "  Please bless my family.\n"),
            translated, edited, removed, emptied, englishDeleted, stale,
            BentiDraft(typed: "ਸਿੰਘ ਅਰਦਾਸ"),
            BentiDraft(typed: "ਸਿੰਘ ਅਰਦਾਸ ok"),
            BentiDraft(typed: "108 !!!"),
        ]
        for draft in drafts {
            XCTAssertEqual(BentiDraft(saved: draft.layers).layers, draft.layers, "\(draft)")
        }
    }

    /// The whole form — label, variant, occasion and benti — round-trips.
    func testEveryFieldRoundTrips() {
        let occasions: [OccasionChoice] = [
            .unset,
            .catalog(id: "japji-sahib"),
            .custom(text: "my niece's birth"),
            .custom(text: "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ"),
        ]
        for shape in everyShape {
            for occasion in occasions {
                let fields = SavedArdaasFields(
                    label: "Family", variantId: "buddha-dal", benti: shape, occasion: occasion
                )
                XCTAssertEqual(ComposeForm(fields: fields).fields, fields)
            }
        }
    }

    func testTheFormReopensOnTheSavedValues() {
        let record = SavedArdaas.make(from: SavedArdaasFields(
            label: "Family",
            variantId: "buddha-dal",
            benti: layers(english: "Bless the sangat.", gurmukhi: "ਸਿੰਘ ਅਰਦਾਸ"),
            occasion: .custom(text: "my niece's birth")
        ))
        let form = ComposeForm(fields: record.fields)
        XCTAssertEqual(form.label, "Family")
        XCTAssertEqual(form.variantId, "buddha-dal")
        XCTAssertEqual(form.occasion.selection, .custom)
        XCTAssertEqual(form.occasion.customText, "my niece's birth")
        XCTAssertEqual(form.benti.typed, "Bless the sangat.")
        XCTAssertEqual(form.benti.gurmukhi, "ਸਿੰਘ ਅਰਦਾਸ")
    }

    // MARK: - Unsaved changes (Cancel)

    func testAnUntouchedFormHasNoChanges() {
        for shape in everyShape {
            let form = ComposeForm(fields: SavedArdaasFields(
                label: "Family", variantId: "sgpc", benti: shape, occasion: .catalog(id: "japji-sahib")
            ))
            XCTAssertFalse(form.hasChanges(from: form), "\(shape)")
        }
    }

    func testEveryEditableFieldCountsAsAChange() {
        let original = ComposeForm(fields: SavedArdaasFields(
            label: "Family",
            variantId: "sgpc",
            benti: layers(english: "Bless the sangat.", gurmukhi: "ਸਿੰਘ ਅਰਦਾਸ"),
            occasion: .unset
        ))

        var label = original
        label.label = "Morning"
        XCTAssertTrue(label.hasChanges(from: original))

        var variant = original
        variant.variantId = "buddha-dal"
        XCTAssertTrue(variant.hasChanges(from: original))

        var occasion = original
        occasion.occasion.selection = .catalog(id: "japji-sahib")
        XCTAssertTrue(occasion.hasChanges(from: original))

        var english = original
        english.benti.typed = "Bless the whole sangat."
        XCTAssertTrue(english.hasChanges(from: original))

        var gurmukhi = original
        gurmukhi.benti.gurmukhi = "ਅਰਦਾਸ"
        XCTAssertTrue(gurmukhi.hasChanges(from: original))

        var removed = original
        removed.benti.clearTranslation()
        XCTAssertTrue(removed.hasChanges(from: original))
    }

    /// Edits that would save exactly what is already saved leave nothing to
    /// discard.
    func testEditsThatSaveTheSameAreNotChanges() {
        let original = ComposeForm(fields: SavedArdaasFields(
            label: "Family", variantId: "sgpc", benti: layers(english: "Bless the sangat."), occasion: .unset
        ))
        var form = original
        form.label = "  Family "
        form.benti.typed = "Bless the whole sangat."
        form.benti.typed = "Bless the sangat.\n"
        form.occasion.selection = .custom
        XCTAssertFalse(form.hasChanges(from: original))
    }

    // MARK: - Retired occasions

    func testARetiredOccasionReopensAsNone() throws {
        let catalog = try OccasionCatalog.loadBundled()
        var form = ComposeForm(fields: SavedArdaasFields(
            label: "Family", variantId: "sgpc", benti: layers(english: "b"), occasion: .catalog(id: "retired-entry")
        ))
        form.dropRetiredOccasion(in: catalog)
        XCTAssertEqual(form.occasion, OccasionDraft())
    }

    func testOtherOccasionsAreLeftAlone() throws {
        let catalog = try OccasionCatalog.loadBundled()
        let choices: [OccasionChoice] = [.unset, .catalog(id: "japji-sahib"), .custom(text: "a new job")]
        for choice in choices {
            let opened = OccasionDraft(choice: choice)
            var form = ComposeForm(occasion: opened)
            form.dropRetiredOccasion(in: catalog)
            XCTAssertEqual(form.occasion, opened, "\(choice)")
        }
    }

    // MARK: - Writing

    /// A new record and an edited one are written by the same rule.
    func testMakeWritesEveryField() {
        let fields = SavedArdaasFields(
            label: "Family",
            variantId: "buddha-dal",
            benti: layers(english: "Bless the sangat.", gurmukhi: "ਸਿੰਘ ਅਰਦਾਸ"),
            occasion: .custom(text: "my niece's birth")
        )
        let createdAt = Date(timeIntervalSince1970: 1_700_000_000)
        let record = SavedArdaas.make(from: fields, createdAt: createdAt)
        XCTAssertEqual(record.fields, fields)
        XCTAssertEqual(record.createdAt, createdAt)
        XCTAssertEqual(record.bentiText, "Bless the sangat.")
        XCTAssertEqual(record.occasionId, OccasionChoice.customId)
    }

    /// Saving an edit updates the same record in place: no second record,
    /// the same identity, the same `createdAt`, and every field rewritten —
    /// including clearing the occasion's free text when it moves to a
    /// catalog entry.
    func testSavingAnEditUpdatesTheRecordInPlace() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let createdAt = Date(timeIntervalSince1970: 1_700_000_000)
        let record = SavedArdaas.make(
            from: SavedArdaasFields(
                label: "Family",
                variantId: "sgpc",
                benti: layers(english: "Bless the sangat.", gurmukhi: "ਸਿੰਘ ਅਰਦਾਸ"),
                occasion: .custom(text: "my niece's birth")
            ),
            createdAt: createdAt
        )
        context.insert(record)
        try context.save()
        let identity = record.persistentModelID

        var form = ComposeForm(fields: record.fields)
        form.label = " Morning "
        form.variantId = "buddha-dal"
        form.occasion.selection = .catalog(id: "japji-sahib")
        form.benti.typed = "Bless us all."
        form.benti.applyTranslation("ਅਰਦਾਸ", of: form.benti.translationSource)
        record.apply(form.fields)
        try context.save()

        let all = try context.fetch(FetchDescriptor<SavedArdaas>())
        XCTAssertEqual(all.count, 1)
        let fetched = try XCTUnwrap(all.first)
        XCTAssertEqual(fetched.persistentModelID, identity)
        XCTAssertEqual(fetched.createdAt, createdAt)
        XCTAssertEqual(fetched.label, "Morning")
        XCTAssertEqual(fetched.variantId, "buddha-dal")
        XCTAssertEqual(fetched.bentiText, "Bless us all.")
        XCTAssertEqual(fetched.bentiGurmukhi, "ਅਰਦਾਸ")
        XCTAssertEqual(fetched.bentiTransliteration, "Ardaas")
        XCTAssertEqual(fetched.occasionId, "japji-sahib")
        XCTAssertEqual(fetched.occasionCustom, "")
    }

    /// Reopened and saved untouched, a record is written back as it was.
    func testSavingAnUntouchedEditChangesNothing() throws {
        let container = try makeContainer()
        let context = container.mainContext
        for shape in everyShape {
            let fields = SavedArdaasFields(
                label: "Family", variantId: "buddha-dal", benti: shape, occasion: .catalog(id: "japji-sahib")
            )
            let record = SavedArdaas.make(from: fields)
            context.insert(record)
            record.apply(ComposeForm(fields: record.fields).fields)
            XCTAssertEqual(record.fields, fields, "\(shape)")
        }
        try context.save()
    }
}
