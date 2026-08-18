import Foundation

/// The three display layers of a chosen occasion, ready to be spliced into
/// the "….." slot. Mirrors `BentiLayers`: each layer is a plain `String` and
/// **empty means "nothing for this layer"**, which the composer reads as
/// "leave that layer's placeholder alone" rather than "blank the slot".
///
/// Values are trimmed on init, so padding can never travel into the middle of
/// a canonical sentence.
struct OccasionLayers: Equatable {
    let gurmukhi: String
    let transliteration: String
    let english: String

    init(gurmukhi: String = "", transliteration: String = "", english: String = "") {
        self.gurmukhi = gurmukhi.trimmingCharacters(in: .whitespacesAndNewlines)
        self.transliteration = transliteration.trimmingCharacters(in: .whitespacesAndNewlines)
        self.english = english.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    subscript(layer: LayerKind) -> String {
        switch layer {
        case .gurmukhi: return gurmukhi
        case .transliteration: return transliteration
        case .english: return english
        }
    }

    /// No layer carries text, so there is nothing to substitute anywhere —
    /// the composer treats this exactly like "nothing chosen".
    var isEmpty: Bool {
        gurmukhi.isEmpty && transliteration.isEmpty && english.isEmpty
    }
}

extension OccasionLayers {
    /// A free-text occasion, typed by the user rather than picked from the
    /// bundled list.
    ///
    /// ## The one-script rule
    ///
    /// Free text arrives in exactly one script, and we will not machine
    /// translate it here (the benti translator is opt-in, on-device and
    /// asynchronous — a slot phrase is not worth that machinery). So:
    ///
    /// * **Gurmukhi in** (`BentiScriptDetector.detect` == `.gurmukhi`) → it
    ///   *is* the Gurmukhi layer, and the Roman layer is derived from it with
    ///   `GurmukhiTransliterator` — the same deterministic, offline path that
    ///   generated the bundled catalog's Roman layer, so a typed occasion and
    ///   a picked one romanize in one voice. English is left empty.
    /// * **Anything else** (`.latin`, or `.undetermined` — digits and
    ///   punctuation only) → the English layer, verbatim. Roman-script
    ///   Punjabi lands here too: the detector counts scripts, not languages,
    ///   and guessing Gurmukhi back out of Roman is not a thing this app
    ///   does.
    ///
    /// The layers left empty keep their dots (see `ArdaasComposer`), so a
    /// typed occasion never blanks the slot in the layers it cannot speak to.
    /// The corner of that: on a variant with no English layer at all (Buddha
    /// Dal), Latin free text has nowhere to appear and the reader still sees
    /// the dots. Putting Roman words into a Gurmukhi scripture line would be
    /// worse, so the text behaves as written and the *picker* is where the
    /// user should be told — see #72.
    ///
    /// Returns nil for text that is empty or whitespace-only — there is
    /// nothing to say, which is "nothing chosen".
    init?(freeText text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        switch BentiScriptDetector.detect(trimmed) {
        case .gurmukhi:
            self.init(
                gurmukhi: trimmed,
                transliteration: GurmukhiTransliterator.transliterate(trimmed)
            )
        case .latin, .undetermined:
            self.init(english: trimmed)
        }
    }
}

/// What fills the "….." slot for a saved Ardaas: nothing, an entry from the
/// bundled catalog, or the user's own words.
///
/// ## Why two stored fields rather than one
///
/// `SavedArdaas` persists this as `occasionId` + `occasionCustom`:
///
/// * A catalog choice stores only its **id**. The three layers stay in the
///   bundle, so a corrected transliteration or a re-proof-read Gurmukhi in a
///   later release reaches every existing save for free — and the picker in
///   #66/#67 can show the entry as selected by comparing ids.
/// * Free text has no id and cannot come from the bundle, so it is stored
///   **verbatim**. It is kept in its own field so an id is always either
///   empty or a real catalog id: storing free text in `occasionId` would make
///   a user who types "birthday" indistinguishable from the catalog's
///   `birthday` entry, and would break the picker's selected state.
///
/// The two are joined by the `custom` sentinel id, which `OccasionCatalog`
/// forbids any bundled entry from using.
enum OccasionChoice: Equatable {
    case unset
    case catalog(id: String)
    case custom(text: String)

    /// The reserved `occasionId` meaning "the text in `occasionCustom`".
    /// Reserved rather than free-form so the stored id stays a closed set.
    static let customId = "custom"

    /// Rebuilds a choice from the two persisted fields. Total by
    /// construction — every stored pair, including ones written by a future
    /// build or corrupted on disk, maps to some choice, and an id that no
    /// longer exists in the catalog simply resolves to no substitution.
    init(occasionId: String, occasionCustom: String) {
        let id = occasionId.trimmingCharacters(in: .whitespacesAndNewlines)
        let custom = occasionCustom.trimmingCharacters(in: .whitespacesAndNewlines)
        switch id {
        case "":
            self = .unset
        case Self.customId:
            self = custom.isEmpty ? .unset : .custom(text: custom)
        default:
            self = .catalog(id: id)
        }
    }

    /// The value for `SavedArdaas.occasionId`.
    var storedId: String {
        switch self {
        case .unset: return ""
        case .catalog(let id): return id.trimmingCharacters(in: .whitespacesAndNewlines)
        case .custom: return Self.customId
        }
    }

    /// The value for `SavedArdaas.occasionCustom`. Cleared for every choice
    /// that is not free text, so a stale draft can't resurface later.
    var storedCustomText: String {
        switch self {
        case .custom(let text): return text.trimmingCharacters(in: .whitespacesAndNewlines)
        case .unset, .catalog: return ""
        }
    }

    /// The layers to splice into the slot, or nil when nothing should be
    /// substituted and the placeholder stays exactly as authored.
    ///
    /// An **unknown catalog id** resolves to nil: an entry retired from a
    /// later bundle leaves the reader with the canonical dots rather than a
    /// crash or a hole in the sentence.
    func layers(in catalog: OccasionCatalog) -> OccasionLayers? {
        switch self {
        case .unset:
            return nil
        case .catalog(let id):
            guard let occasion = catalog.occasion(id: id) else { return nil }
            return OccasionLayers(
                gurmukhi: occasion.gurmukhi,
                transliteration: occasion.transliteration,
                english: occasion.english
            )
        case .custom(let text):
            return OccasionLayers(freeText: text)
        }
    }
}
