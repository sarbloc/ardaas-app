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

    init(typed: String = "", gurmukhi: String = "", translatedFrom: String? = nil) {
        self.typed = typed
        self.gurmukhi = gurmukhi
        self.translatedFrom = translatedFrom
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
    var isStale: Bool {
        guard let translatedFrom, hasTranslationDraft else { return false }
        return translatedFrom != typed.trimmed
    }

    /// A translation draft is on screen: only meaningful while the typed text
    /// is not itself Gurmukhi.
    var hasTranslationDraft: Bool {
        !isTypedInGurmukhi && !gurmukhi.trimmed.isEmpty
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
    }

    /// Drops the translation draft, returning to English-only.
    mutating func clearTranslation() {
        gurmukhi = ""
        translatedFrom = nil
    }
}

/// File-scoped so it cannot collide with another module-wide `trimmed`.
private extension String {
    /// Whitespace-trimmed, matching how `BentiLayers` normalises every layer.
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
