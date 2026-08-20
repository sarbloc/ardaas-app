import Foundation

/// Which row the Compose screen's occasion picker is on.
///
/// Catalog entries are identified by id; the two synthetic rows are `unset`
/// (the default, shown as "None" — the slot keeps its authored dots) and
/// `custom` ("Other…", whose words live beside it in
/// `OccasionDraft.customText`).
///
/// Deliberately *not* `OccasionChoice`: a picker can sit on "Other…" with
/// nothing typed yet, which is a real UI state but not a choice worth
/// storing. `OccasionDraft.choice` is where the two meet.
enum OccasionSelection: Hashable {
    case unset
    case catalog(id: String)
    case custom
}

/// The Compose screen's occasion slot as pure, testable data: which row is
/// selected, the words typed under "Other…", and the rules that turn the two
/// into the `OccasionChoice` that `SavedArdaas` persists.
///
/// The view owns one of these and binds the picker to `selection` and the
/// free-text field to `customText`. Everything else is derived.
struct OccasionDraft: Equatable {
    /// The selected row.
    ///
    /// Moving off "Other…" clears `customText`, so an abandoned draft can
    /// never be saved beside a catalog choice, and can never reappear if the
    /// user comes back to "Other…" later. This mirrors
    /// `OccasionChoice.storedCustomText`, which blanks the same field on the
    /// way to storage — doing it here as well means what the user sees and
    /// what would be saved never disagree.
    var selection: OccasionSelection = .unset {
        didSet {
            if selection != .custom { customText = "" }
        }
    }

    /// The user's own words for the slot. Only meaningful while `selection`
    /// is `.custom`; cleared otherwise (see above).
    var customText: String = ""

    /// True when the free-text field should be on screen.
    var isCustom: Bool { selection == .custom }

    /// What Save persists.
    ///
    /// "Other…" with nothing but whitespace typed is `.unset`, not an empty
    /// custom choice: an abandoned draft must leave the canonical dots
    /// exactly as authored rather than blanking the sentence.
    var choice: OccasionChoice {
        switch selection {
        case .unset:
            return .unset
        case .catalog(let id):
            return .catalog(id: id)
        case .custom:
            let trimmed = customText.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? .unset : .custom(text: trimmed)
        }
    }

    /// The slot sentence as it will read with this choice in it, for the
    /// variant the user has selected. Nil when the variant declares no
    /// occasion slot (nothing would ever be substituted, so there is nothing
    /// to show).
    func preview(in content: ArdaasContent, catalog: OccasionCatalog) -> OccasionPreview? {
        OccasionPreview(content: content, occasion: choice.layers(in: catalog))
    }

    /// The #72 warning for the row currently selected — see
    /// `OccasionChoice.willNotAppear(in:catalog:)`, which both pickers and the
    /// Reader's slot share.
    func willNotAppear(in content: ArdaasContent, catalog: OccasionCatalog) -> Bool {
        choice.willNotAppear(in: content, catalog: catalog)
    }
}

extension OccasionDraft {
    /// The picker's state for a choice already saved — how the Reader's
    /// picker (#67) opens on the record's current occasion.
    ///
    /// The inverse of `choice`, except for the one state `choice` collapses:
    /// "Other…" with nothing typed stores as `.unset` and so reopens as
    /// "None". Nothing is lost — an abandoned draft was never saved — and the
    /// row shown and the record stay in agreement.
    ///
    /// Declared in an extension so the memberwise initialiser survives. It
    /// also bypasses `selection`'s `didSet` (initialisation never triggers
    /// one), which is what lets the free text be set alongside `.custom`.
    init(choice: OccasionChoice) {
        switch choice {
        case .unset:
            self.init()
        case .catalog(let id):
            self.init(selection: .catalog(id: id))
        case .custom(let text):
            self.init(selection: .custom, customText: text)
        }
    }
}

extension OccasionChoice {
    /// A choice has been made but it lands in no layer this variant carries,
    /// so the reader would still see the dots.
    ///
    /// Today this is reachable in exactly one combination: Buddha Dal has no
    /// English layer, and free text that is not in Gurmukhi script becomes the
    /// English layer and nothing else (see `OccasionLayers.init(freeText:)`),
    /// so it has nowhere to appear. That is the known gap #72; the pickers say
    /// so inline rather than accepting the choice silently, and the Reader
    /// repeats it at the slot itself — where switching variants can strand
    /// free text that was fine on the variant it was written for.
    ///
    /// Phrased as "did the substitution change anything" rather than as a
    /// hard-coded variant/script pair, so it stays true if either the bundled
    /// texts or the free-text rules change.
    func willNotAppear(in content: ArdaasContent, catalog: OccasionCatalog) -> Bool {
        guard self != .unset,
              let line = OccasionPreview(content: content, occasion: layers(in: catalog))
        else {
            return false
        }
        return !line.isFilled
    }
}

/// The Ardaas's slot sentence, quoted back to the user while they choose —
/// the point at which the "….." stops being a typo and becomes a sentence
/// they can read.
struct OccasionPreview: Equatable {
    /// Which layer is being quoted, so the caller can style it like the
    /// Reader does.
    let layer: LayerKind
    /// The slot segment's text for that layer, with the choice spliced in.
    let text: String
    /// Whether the choice actually landed: false means `text` is still the
    /// sentence exactly as authored, dots and all — either because nothing is
    /// chosen, or because the choice has no words for any layer this variant
    /// carries (see `OccasionDraft.willNotAppear`).
    let isFilled: Bool
}

extension OccasionPreview {
    /// Builds the preview by running the *same* substitution the Reader gets
    /// (`ArdaasComposer`), so what Compose promises and what the Reader
    /// renders cannot drift apart.
    ///
    /// The layer shown is the first one — Gurmukhi, then transliteration,
    /// then English — that the substitution actually changed. Preferring a
    /// changed layer is what makes the preview useful for free text, which
    /// fills only one layer: showing the untouched Gurmukhi for an
    /// English-only occasion would just be the dots again. When nothing
    /// changed anywhere, the authored Gurmukhi is shown, which is the whole
    /// point for "None" — it is where the reader sees what the dots are.
    init?(content: ArdaasContent, occasion: OccasionLayers?) {
        guard let authored = ArdaasComposer.occasionSlotSegment(of: content, occasion: nil) else {
            return nil
        }
        let filled = ArdaasComposer.occasionSlotSegment(of: content, occasion: occasion) ?? authored
        let changed = LayerKind.allCases.first { layer in
            guard let text = filled.text(for: layer) else { return false }
            return text != authored.text(for: layer)
        }
        if let changed, let text = filled.text(for: changed) {
            self.init(layer: changed, text: text, isFilled: true)
        } else {
            self.init(layer: .gurmukhi, text: authored.gurmukhi, isFilled: false)
        }
    }
}
