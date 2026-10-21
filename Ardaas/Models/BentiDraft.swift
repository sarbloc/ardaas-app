import Foundation

/// The Compose screen's benti as pure, testable data: what the user typed, the
/// Gurmukhi draft (if any), and the rules that turn the two into the three
/// layers persisted on `SavedArdaas`.
///
/// The view owns one of these and binds its editors straight to `typed` and
/// `gurmukhi`. Everything else — the detected script, the transliteration, the
/// layers, whether the draft has gone stale — is *derived*, never stored, so
/// there is no second copy to keep in sync. In particular the transliteration
/// is recomputed from the current Gurmukhi on every read, which is what makes
/// an edit to the Gurmukhi regenerate it for free (the transliterator is
/// deterministic, offline and needs no model).
struct BentiDraft: Equatable {
    /// Exactly what the user typed in the benti editor — English, or Gurmukhi
    /// if they used the Punjabi keyboard.
    var typed: String = ""

    /// The machine-translated Gurmukhi, after any edits the user made to it.
    /// Empty when nothing has been translated. Ignored when `typed` is itself
    /// Gurmukhi (there is nothing to translate then, so the typed text is the
    /// Gurmukhi layer).
    var gurmukhi: String = ""

    /// The trimmed English that `gurmukhi` was translated from, or nil when
    /// there is no translation. Used only to notice that the English has been
    /// edited since — see `isStale`.
    var translatedFrom: String?

    /// The Gurmukhi draft section is open on screen. Set when a translation
    /// lands and cleared only by `clearTranslation()` ("Remove Gurmukhi") —
    /// deliberately *not* derived from `gurmukhi` being non-empty, so emptying
    /// the editor mid-edit leaves the editor where it is instead of removing
    /// it from under the user (#131). Editor state only: it never reaches
    /// `layers`, so what gets saved is unchanged.
    var isDraftOpen: Bool

    /// `isDraftOpen` defaults to whether `gurmukhi` holds anything, so a draft
    /// built with Gurmukhi already in it opens its section.
    init(
        typed: String = "",
        gurmukhi: String = "",
        translatedFrom: String? = nil,
        isDraftOpen: Bool? = nil
    ) {
        self.typed = typed
        self.gurmukhi = gurmukhi
        self.translatedFrom = translatedFrom
        self.isDraftOpen = isDraftOpen ?? !gurmukhi.trimmed.isEmpty
    }

    // MARK: - Derived

    var script: BentiScript { BentiScriptDetector.detect(typed) }

    /// True when the typed text is already Gurmukhi, so translation is skipped
    /// and only the transliteration is generated.
    var isTypedInGurmukhi: Bool { script == .gurmukhi }

    /// The Gurmukhi that will be saved: the typed text when it is already
    /// Gurmukhi, otherwise the translation draft.
    var effectiveGurmukhi: String {
        isTypedInGurmukhi ? typed.trimmed : gurmukhi.trimmed
    }

    /// Regenerated from `effectiveGurmukhi` on every read — deterministic and
    /// instant, so an edit to the Gurmukhi is reflected immediately.
    var transliteration: String {
        let source = effectiveGurmukhi
        guard !source.isEmpty else { return "" }
        return GurmukhiTransliterator.transliterate(source)
    }

    /// The three layers to persist.
    ///
    /// * Typed in Gurmukhi → Gurmukhi + transliteration; the English layer is
    ///   left empty, because nothing here translates Gurmukhi back to English
    ///   and putting the Gurmukhi in the English slot would mislabel it.
    /// * Typed in anything else → the typed text is the English layer, and the
    ///   Gurmukhi/transliteration layers are filled in only once a translation
    ///   exists. **No translation means English only**, exactly as before this
    ///   screen could translate at all.
    var layers: BentiLayers {
        BentiLayers(
            gurmukhi: effectiveGurmukhi,
            transliteration: transliteration,
            english: isTypedInGurmukhi ? "" : typed
        )
    }

    /// Nothing worth saving in any layer.
    var isEmpty: Bool { layers.isEmpty }

    /// There is a translation and the English has changed since it was made, so
    /// the Gurmukhi on screen no longer corresponds to the text above it. The
    /// draft is kept (it may hold the user's own edits) and flagged, never
    /// silently discarded or silently saved as if it still matched.
    ///
    /// Deleting the English entirely is not staleness: there is then nothing
    /// for the Gurmukhi to disagree with, and a Gurmukhi-only benti is a
    /// perfectly good thing to save.
    var isStale: Bool {
        guard let translatedFrom, hasTranslationDraft else { return false }
        let current = typed.trimmed
        return !current.isEmpty && current != translatedFrom
    }

    /// The translation draft holds Gurmukhi that counts: only meaningful
    /// while the typed text is not itself Gurmukhi. Drives staleness — an
    /// empty draft saves English only, so it has nothing to disagree with.
    var hasTranslationDraft: Bool {
        !isTypedInGurmukhi && !gurmukhi.trimmed.isEmpty
    }

    /// The Gurmukhi draft section is on screen: it is open, and the typed text
    /// is not itself Gurmukhi (a draft from earlier English stays hidden while
    /// it is ignored, and comes back if the English does). Stays true while
    /// the editor is empty — see `isDraftOpen`.
    var showsTranslationDraft: Bool {
        !isTypedInGurmukhi && isDraftOpen
    }

    /// Text the translator can be given. `.undetermined` (digits, punctuation,
    /// emoji) has nothing to translate, and Gurmukhi is already there.
    var isTranslatable: Bool { script == .latin }

    /// The exact string handed to the translator, and recorded as the source of
    /// the resulting draft.
    var translationSource: String { typed.trimmed }

    // MARK: - Mutation

    /// Records a fresh translation of `source`.
    mutating func applyTranslation(_ translated: String, of source: String) {
        gurmukhi = translated.trimmed
        translatedFrom = source.trimmed
        isDraftOpen = true
    }

    /// Drops the translation draft, returning to English-only, and closes its
    /// section.
    mutating func clearTranslation() {
        gurmukhi = ""
        translatedFrom = nil
        isDraftOpen = false
    }
}

extension BentiDraft {
    /// The draft that reproduces a saved benti — how Compose reopens a record
    /// for editing (#154). The exact inverse of `layers`: for every benti
    /// Compose can save, `BentiDraft(saved: draft.layers).layers ==
    /// draft.layers`.
    ///
    /// * **English saved** (with or without Gurmukhi) → the English is what
    ///   was typed and the Gurmukhi is the translation draft. A saved pair
    ///   counts as one translation (`translatedFrom` is the saved English), so
    ///   reopening is not stale; only changing the English makes it so. The
    ///   draft section is open exactly when there is saved Gurmukhi — so a
    ///   benti saved after Remove Gurmukhi reopens as plain English.
    /// * **Gurmukhi only, in Gurmukhi script** → it was typed in Gurmukhi (or
    ///   the English was deleted after translating, which saves the same), so
    ///   it goes back in the benti editor and the transliteration regenerates.
    /// * **Gurmukhi only, in any other script** — the user rewrote the
    ///   translation draft in Latin letters, then deleted the English. Put in
    ///   the benti editor it would save as English, so it reopens as an open
    ///   draft under an empty editor instead, which saves unchanged.
    ///
    /// The transliteration is never read back: it is derived from the
    /// Gurmukhi on every read, so it is regenerated on save like any other
    /// draft.
    ///
    /// One record has no exact inverse: an English layer that is itself
    /// Gurmukhi script, which only an app from before #44 could write. It
    /// reopens as typed Gurmukhi and, if saved, moves to the Gurmukhi layer —
    /// where Compose would have put it.
    init(saved layers: BentiLayers) {
        if !layers.english.isEmpty {
            self.init(
                typed: layers.english,
                gurmukhi: layers.gurmukhi,
                translatedFrom: layers.gurmukhi.isEmpty ? nil : layers.english
            )
        } else if BentiScriptDetector.detect(layers.gurmukhi) == .gurmukhi {
            self.init(typed: layers.gurmukhi)
        } else {
            // Also the empty benti: no Gurmukhi leaves the section closed.
            self.init(gurmukhi: layers.gurmukhi)
        }
    }
}

/// File-scoped so it cannot collide with another module-wide `trimmed`.
private extension String {
    /// Whitespace-trimmed, matching how `BentiLayers` normalises every layer.
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
