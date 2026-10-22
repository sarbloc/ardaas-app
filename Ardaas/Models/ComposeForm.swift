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
    /// The slot's paath (#158) — `SavedArdaas.paathId`. Last, and defaulted,
    /// so a record with no paath reads as it did before there was one.
    var paathId: String = ""

    /// The paath and occasion together, as the slot renders them.
    var slot: SlotChoice { SlotChoice(paathId: paathId, occasion: occasion) }
}

/// The Compose screen's whole form as pure, testable data: the label, the
/// benti draft, the chosen variant and the slot pickers' state (paath and
/// occasion, both in `occasion`).
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

    /// What Save writes. The label is trimmed; the benti and the slot go
    /// through their own drafts' rules (`BentiDraft.layers`,
    /// `OccasionDraft.slot`).
    var fields: SavedArdaasFields {
        let slot = occasion.slot
        return SavedArdaasFields(
            label: label.trimmingCharacters(in: .whitespacesAndNewlines),
            variantId: variantId,
            benti: benti.layers,
            occasion: slot.occasion,
            paathId: slot.paathId
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
    /// the benti and `OccasionDraft(slot:catalog:)` for the paath and
    /// occasion. The slot is taken verbatim here; `normaliseSlot(in:)`
    /// resolves it once the catalog has loaded.
    ///
    /// Declared in an extension so the memberwise initialiser survives.
    init(fields: SavedArdaasFields) {
        self.init(
            label: fields.label,
            benti: BentiDraft(saved: fields.benti),
            variantId: fields.variantId,
            occasion: OccasionDraft(slot: fields.slot)
        )
    }

    /// Resolves the slot against the catalog, once it has loaded — the same
    /// rules as the Reader's editor (`OccasionDraft(slot:catalog:)`):
    ///
    /// * a legacy paath stored as the occasion (before #158) opens on the
    ///   Paath picker, so the next Save writes it as `paathId`;
    /// * a paath id that is not a paath, and a catalog occasion a later
    ///   bundle has retired, open as "None" — the pickers have no row for
    ///   them, and "None" is what the Reader renders.
    ///
    /// "Other…" is left exactly as it is, words and all.
    mutating func normaliseSlot(in catalog: OccasionCatalog) {
        let resolved = OccasionDraft(slot: occasion.slot, catalog: catalog)
        if occasion.isCustom {
            occasion.paathId = resolved.paathId
        } else {
            occasion = resolved
        }
    }
}
