import XCTest
@testable import Ardaas

/// The Compose screen's benti logic: which layers a draft produces, and how
/// they change as the user translates, edits or rewrites.
///
/// Everything here is pure — no model, no ONNX Runtime, no network — which is
/// the point: the translation *call* cannot run in CI, but every rule around it
/// can. `applyTranslation` stands in for "the engine returned this string".
final class BentiDraftTests: XCTestCase {

    // MARK: - English only (the pre-#44 behaviour)

    /// Saving without translating writes the English layer and nothing else,
    /// exactly as before this screen could translate.
    func testTypedEnglishWithoutTranslationIsEnglishOnly() {
        let draft = BentiDraft(typed: "Please bless my family.")
        XCTAssertEqual(
            draft.layers,
            BentiLayers(gurmukhi: "", transliteration: "", english: "Please bless my family."))
        XCTAssertEqual(draft.transliteration, "")
        XCTAssertFalse(draft.hasTranslationDraft)
        XCTAssertTrue(draft.isTranslatable)
    }

    /// A `SavedArdaas` built the way `ComposeView.save()` builds it round-trips
    /// back to the same layers.
    func testLayersSurviveASavedArdaasRoundTrip() {
        var draft = BentiDraft(typed: "  Please bless my family.  ")
        draft.applyTranslation("ਸਿੰਘ ਅਰਦਾਸ", of: draft.translationSource)
        let layers = draft.layers
        let saved = SavedArdaas(
            label: "Family",
            bentiText: layers.english,
            bentiGurmukhi: layers.gurmukhi,
            bentiTransliteration: layers.transliteration
        )
        XCTAssertEqual(saved.bentiLayers, layers)
        XCTAssertEqual(saved.bentiText, "Please bless my family.")
        XCTAssertEqual(saved.bentiGurmukhi, "ਸਿੰਘ ਅਰਦਾਸ")
        XCTAssertEqual(saved.bentiTransliteration, "Singh Ardaas")
    }

    func testWhitespaceOnlyDraftIsEmpty() {
        XCTAssertTrue(BentiDraft(typed: "   \n ").isEmpty)
        XCTAssertTrue(BentiDraft().isEmpty)
        XCTAssertFalse(BentiDraft(typed: "a").isEmpty)
        // A translation with the English deleted is still worth saving.
        XCTAssertFalse(BentiDraft(typed: "", gurmukhi: "ਅਰਦਾਸ").isEmpty)
    }

    // MARK: - Translated English

    func testTranslationFillsAllThreeLayers() {
        var draft = BentiDraft(typed: "Bless the sangat.")
        draft.applyTranslation("ਸਿੰਘ ਅਰਦਾਸ", of: draft.translationSource)
        XCTAssertEqual(
            draft.layers,
            BentiLayers(
                gurmukhi: "ਸਿੰਘ ਅਰਦਾਸ",
                transliteration: "Singh Ardaas",
                english: "Bless the sangat."))
        XCTAssertTrue(draft.hasTranslationDraft)
    }

    /// The transliteration is derived, never stored, so editing the Gurmukhi
    /// regenerates it — the whole point of letting the user correct the
    /// machine's output before saving.
    func testEditingTheGurmukhiRegeneratesTheTransliteration() {
        var draft = BentiDraft(typed: "Bless the sangat.")
        draft.applyTranslation("ਸਿੰਘ", of: draft.translationSource)
        XCTAssertEqual(draft.transliteration, "Singh")

        draft.gurmukhi = "ਅਰਦਾਸ"
        XCTAssertEqual(draft.transliteration, "Ardaas")
        XCTAssertEqual(draft.layers.transliteration, "Ardaas")

        // Emptying it drops both layers rather than leaving a stale romanization.
        draft.gurmukhi = "   "
        XCTAssertEqual(draft.transliteration, "")
        XCTAssertEqual(
            draft.layers,
            BentiLayers(english: "Bless the sangat."))
        XCTAssertFalse(draft.hasTranslationDraft)
    }

    func testClearingTheTranslationReturnsToEnglishOnly() {
        var draft = BentiDraft(typed: "Bless the sangat.")
        draft.applyTranslation("ਸਿੰਘ", of: draft.translationSource)
        draft.clearTranslation()
        XCTAssertEqual(draft.layers, BentiLayers(english: "Bless the sangat."))
        XCTAssertNil(draft.translatedFrom)
        XCTAssertFalse(draft.isStale)
    }

    // MARK: - Staleness

    func testDraftIsStaleOnlyAfterTheEnglishChanges() {
        var draft = BentiDraft(typed: "Bless the sangat.")
        draft.applyTranslation("ਸਿੰਘ", of: draft.translationSource)
        XCTAssertFalse(draft.isStale)

        // Whitespace-only edits are not a change: both sides are trimmed.
        draft.typed = "  Bless the sangat.  "
        XCTAssertFalse(draft.isStale)

        draft.typed = "Bless the whole sangat."
        XCTAssertTrue(draft.isStale)

        // Editing the Gurmukhi is not what staleness tracks — that is the user
        // correcting the machine, and it must not be flagged as out of date.
        draft.typed = "Bless the sangat."
        draft.gurmukhi = "ਅਰਦਾਸ"
        XCTAssertFalse(draft.isStale)
    }

    /// Deleting the English is not staleness: there is nothing left for the
    /// Gurmukhi to disagree with, and a Gurmukhi-only benti saves fine.
    func testDeletingTheEnglishIsNotStale() {
        var draft = BentiDraft(typed: "Bless the sangat.")
        draft.applyTranslation("ਅਰਦਾਸ", of: draft.translationSource)
        draft.typed = "  "
        XCTAssertFalse(draft.isStale)
        XCTAssertEqual(
            draft.layers,
            BentiLayers(gurmukhi: "ਅਰਦਾਸ", transliteration: "Ardaas", english: ""))
    }

    func testUntranslatedDraftIsNeverStale() {
        var draft = BentiDraft(typed: "Bless the sangat.")
        XCTAssertFalse(draft.isStale)
        draft.applyTranslation("ਸਿੰਘ", of: draft.translationSource)
        draft.gurmukhi = ""
        draft.typed = "Something else entirely."
        XCTAssertFalse(draft.isStale)
    }

    // MARK: - Gurmukhi typed directly

    /// Gurmukhi input skips translation entirely: the typed text *is* the
    /// Gurmukhi layer, the transliteration is generated from it, and the
    /// English layer stays empty — nothing here translates Gurmukhi back to
    /// English, and putting Gurmukhi in the English slot would mislabel it.
    func testGurmukhiInputIsTransliteratedNotTranslated() {
        let draft = BentiDraft(typed: "ਸਿੰਘ ਅਰਦਾਸ")
        XCTAssertTrue(draft.isTypedInGurmukhi)
        XCTAssertFalse(draft.isTranslatable)
        XCTAssertEqual(
            draft.layers,
            BentiLayers(
                gurmukhi: "ਸਿੰਘ ਅਰਦਾਸ",
                transliteration: "Singh Ardaas",
                english: ""))
    }

    /// Rewriting an English benti in Gurmukhi retires the old translation
    /// without deleting it: the typed text wins, so nothing stale can be saved,
    /// and undoing the rewrite brings the draft back.
    func testGurmukhiInputOverridesAnyExistingTranslationDraft() {
        var draft = BentiDraft(typed: "Bless the sangat.")
        draft.applyTranslation("ਅਰਦਾਸ", of: draft.translationSource)

        draft.typed = "ਸਿੰਘ"
        XCTAssertFalse(draft.hasTranslationDraft)
        XCTAssertEqual(
            draft.layers,
            BentiLayers(gurmukhi: "ਸਿੰਘ", transliteration: "Singh", english: ""))

        draft.typed = "Bless the sangat."
        XCTAssertTrue(draft.hasTranslationDraft)
        XCTAssertEqual(draft.layers.gurmukhi, "ਅਰਦਾਸ")
    }

    /// Latin passes through the transliterator untouched, so a mostly-Gurmukhi
    /// benti with an English word in it survives intact.
    func testMixedGurmukhiMajorityKeepsItsLatinText() {
        let draft = BentiDraft(typed: "ਸਿੰਘ ਅਰਦਾਸ ok")
        XCTAssertTrue(draft.isTypedInGurmukhi)
        XCTAssertEqual(draft.layers.gurmukhi, "ਸਿੰਘ ਅਰਦਾਸ ok")
        XCTAssertEqual(draft.layers.transliteration, "Singh Ardaas ok")
    }

    // MARK: - The draft section (#131)

    /// Emptying the Gurmukhi editor keeps its section open — the user is in
    /// the middle of editing it — while saving exactly what it did before:
    /// English only, and nothing stale to warn about.
    func testEmptyingTheGurmukhiKeepsTheDraftSectionOpen() {
        var draft = BentiDraft(typed: "Bless the sangat.")
        draft.applyTranslation("ਸਿੰਘ", of: draft.translationSource)
        draft.gurmukhi = "  "
        XCTAssertTrue(draft.isDraftOpen)
        XCTAssertTrue(draft.showsTranslationDraft)
        XCTAssertFalse(draft.hasTranslationDraft)
        XCTAssertEqual(draft.layers, BentiLayers(english: "Bless the sangat."))

        draft.typed = "Bless the whole sangat."
        XCTAssertFalse(draft.isStale)
    }

    /// Typing back into the emptied editor carries on as an ordinary draft.
    func testRetypingIntoAnEmptiedDraftRestoresItsLayers() {
        var draft = BentiDraft(typed: "Bless the sangat.")
        draft.applyTranslation("ਸਿੰਘ", of: draft.translationSource)
        draft.gurmukhi = ""
        draft.gurmukhi = "ਅਰਦਾਸ"
        XCTAssertTrue(draft.showsTranslationDraft)
        XCTAssertEqual(
            draft.layers,
            BentiLayers(gurmukhi: "ਅਰਦਾਸ", transliteration: "Ardaas", english: "Bless the sangat."))
    }

    func testRemoveGurmukhiClosesTheSection() {
        var draft = BentiDraft(typed: "Bless the sangat.")
        draft.applyTranslation("ਸਿੰਘ", of: draft.translationSource)
        draft.gurmukhi = ""
        draft.clearTranslation()
        XCTAssertFalse(draft.isDraftOpen)
        XCTAssertFalse(draft.showsTranslationDraft)
    }

    func testTranslatingAgainRefillsAnEmptiedDraft() {
        var draft = BentiDraft(typed: "Bless the sangat.")
        draft.applyTranslation("ਸਿੰਘ", of: draft.translationSource)
        draft.gurmukhi = ""
        draft.typed = "Bless us all."
        draft.applyTranslation("ਅਰਦਾਸ", of: draft.translationSource)
        XCTAssertTrue(draft.showsTranslationDraft)
        XCTAssertEqual(draft.gurmukhi, "ਅਰਦਾਸ")
        XCTAssertEqual(draft.translatedFrom, "Bless us all.")
        XCTAssertFalse(draft.isStale)
    }

    /// No translation, no section — the editor only appears once one lands.
    func testUntranslatedDraftHasNoSection() {
        XCTAssertFalse(BentiDraft().showsTranslationDraft)
        XCTAssertFalse(BentiDraft(typed: "Bless the sangat.").showsTranslationDraft)
    }

    /// Rewriting the benti in Gurmukhi hides even an emptied draft; English
    /// brings it back.
    func testGurmukhiInputHidesAnOpenDraftUntilTheEnglishReturns() {
        var draft = BentiDraft(typed: "Bless the sangat.")
        draft.applyTranslation("ਸਿੰਘ", of: draft.translationSource)
        draft.gurmukhi = ""
        draft.typed = "ਸਿੰਘ"
        XCTAssertFalse(draft.showsTranslationDraft)
        XCTAssertTrue(draft.isDraftOpen)
        draft.typed = "Bless the sangat."
        XCTAssertTrue(draft.showsTranslationDraft)
    }

    /// A draft built with Gurmukhi already in it opens its section; blank
    /// Gurmukhi does not.
    func testTheSectionDefaultsFromTheGurmukhi() {
        XCTAssertTrue(BentiDraft(typed: "Bless us.", gurmukhi: "ਅਰਦਾਸ").showsTranslationDraft)
        XCTAssertFalse(BentiDraft(typed: "Bless us.", gurmukhi: " \n").isDraftOpen)
    }

    // MARK: - Nothing to translate

    /// Digits and punctuation are `.undetermined`: not translatable, and there
    /// is no Gurmukhi to transliterate either. They still save as English.
    func testUndeterminedInputIsNotTranslatable() {
        let draft = BentiDraft(typed: "108 !!!")
        XCTAssertFalse(draft.isTranslatable)
        XCTAssertFalse(draft.isTypedInGurmukhi)
        XCTAssertEqual(draft.layers, BentiLayers(english: "108 !!!"))
    }

    func testTranslationSourceIsTheTrimmedTypedText() {
        XCTAssertEqual(
            BentiDraft(typed: "  Bless the sangat.\n").translationSource,
            "Bless the sangat.")
    }
}
