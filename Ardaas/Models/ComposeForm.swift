import Foundation

/// Everything the Compose screen writes to a `SavedArdaas`, as one value.
///
/// Creating and editing both go through it — `ComposeForm.fields` builds it,
/// `SavedArdaas.make(from:)` and `SavedArdaas.apply(_:)` write it — so a new
/// record and an edited one cannot be saved by two rules that drift apart.
/// `createdAt` is deliberately absent: it belongs to the record, and editing
/// keeps it.
struct SavedArdaasFields: Equatable {
    var label: String
    var variantId: String
    var benti: BentiLayers
    var occasion: OccasionChoice
}

/// The Compose screen's whole form as pure, testable data: the label, the
/// benti draft, the chosen variant and the occasion picker's state.
///
/// Composing starts from `ComposeForm()`. Editing (#154) starts from
/// `ComposeForm(fields:)` on the saved record, which is the exact inverse of
/// `fields` — a record reopened and saved unchanged is written back as it
/// was.
struct ComposeForm: Equatable {
    var label = ""
    var benti = BentiDraft()
    var variantId = ArdaasLibrary.defaultVariantId
    var occasion = OccasionDraft()

    /// What Save writes. The label is trimmed; the benti and occasion go
    /// through their own drafts' rules (`BentiDraft.layers`,
    /// `OccasionDraft.choice`).
    var fields: SavedArdaasFields {
        SavedArdaasFields(
            label: label.trimmingCharacters(in: .whitespacesAndNewlines),
            variantId: variantId,
            benti: benti.layers,
            occasion: occasion.choice
        )
    }

    /// Saving now would write something different from saving `original`.
    ///
    /// Compared by what would be *saved*, not by raw editor state, so edits
    /// that change nothing — padding, or changing the English and changing
    /// it back — leave nothing to discard.
    func hasChanges(from original: ComposeForm) -> Bool {
        fields != original.fields
    }
}

extension ComposeForm {
    /// The form that reopens a saved record — see `BentiDraft(saved:)` for
    /// the benti and `OccasionDraft(choice:catalog:)` for the occasion.
    ///
    /// Declared in an extension so the memberwise initialiser survives.
    init(fields: SavedArdaasFields) {
        self.init(
            label: fields.label,
            benti: BentiDraft(saved: fields.benti),
            variantId: fields.variantId,
            occasion: OccasionDraft(choice: fields.occasion)
        )
    }

    /// Opens a catalog occasion that a later bundle has retired as "None".
    ///
    /// The picker has no row for a retired id, so it would tick nothing while
    /// the Reader renders the canonical dots; "None" is what is actually
    /// rendered. The same rule as the Reader's occasion sheet
    /// (`OccasionDraft(choice:catalog:)`), applied once the catalog has
    /// loaded. Anything else is left exactly as it is.
    mutating func dropRetiredOccasion(in catalog: OccasionCatalog) {
        guard case .catalog(let id) = occasion.selection, catalog.occasion(id: id) == nil else {
            return
        }
        occasion = OccasionDraft()
    }
}
