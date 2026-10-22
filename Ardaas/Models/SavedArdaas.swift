import Foundation
import SwiftData

/// A user-saved Ardaas: a labelled personal benti composed into the
/// canonical text at render time. Only the benti is persisted — the
/// canonical segments always come from the bundled content.
///
/// The benti carries the same three layers as a canonical segment
/// (Gurmukhi / transliteration / English). All three are stored even when
/// the chosen variant renders only some of them, so switching variants
/// never discards work.
@Model
final class SavedArdaas {
    var label: String
    /// The English (translation) layer — the text the user typed.
    ///
    /// Named `bentiText` for history: it predates the three-layer benti and
    /// renaming the stored property would break SwiftData's lightweight
    /// migration. Read it as `bentiEnglish` (the alias below).
    var bentiText: String
    var createdAt: Date
    /// Which Ardaas variant this benti composes into. Defaults to the
    /// standard SGPC text; pre-variant records migrate to it implicitly.
    var variantId: String = ArdaasLibrary.defaultVariantId
    /// The Gurmukhi layer of the benti — empty until it is translated.
    /// Defaulted so pre-three-layer records migrate implicitly.
    var bentiGurmukhi: String = ""
    /// The Roman transliteration of `bentiGurmukhi` — empty until it is
    /// generated. Defaulted so pre-three-layer records migrate implicitly.
    var bentiTransliteration: String = ""
    /// Which entry fills the Ardaas's "….." slot: a bundled occasion's id,
    /// `OccasionChoice.customId` when the user typed their own, or **empty
    /// for "nothing chosen"** — which is also what every pre-occasion record
    /// migrates to, so those keep rendering the canonical dots.
    ///
    /// Defaulted, like `variantId` and the benti layers, so SwiftData's
    /// lightweight migration covers existing stores with no migration plan.
    var occasionId: String = ""
    /// The user's own words for the slot, used only when `occasionId` is
    /// `OccasionChoice.customId`. Free text has no id and cannot come from
    /// the bundle, so it is stored verbatim and in its own field — see
    /// `OccasionChoice` for why it is not squeezed into `occasionId`.
    ///
    /// The pair is written only through `OccasionChoice` (here and in
    /// `occasionChoice`'s setter), so it can never hold an impossible
    /// combination like an id with a stale free-text draft beside it.
    var occasionCustom: String = ""
    /// The paath recited, which fills the slot beside the occasion (#158):
    /// a bundled entry's id whose category is `.paath`, or **empty for "no
    /// paath"** — which is what every earlier record migrates to.
    ///
    /// Defaulted, like `occasionId`, so SwiftData's lightweight migration
    /// adds it to existing stores with no migration plan. Records saved
    /// before #158 may still carry their paath in `occasionId`; that is
    /// resolved at read time (`SlotChoice.normalised(in:)`), never by
    /// rewriting the store, and the next save writes it here.
    var paathId: String = ""

    init(
        label: String,
        bentiText: String,
        createdAt: Date = .now,
        variantId: String = ArdaasLibrary.defaultVariantId,
        bentiGurmukhi: String = "",
        bentiTransliteration: String = "",
        occasion: OccasionChoice = .unset,
        paathId: String = ""
    ) {
        self.label = label
        self.bentiText = bentiText
        self.createdAt = createdAt
        self.variantId = variantId
        self.bentiGurmukhi = bentiGurmukhi
        self.bentiTransliteration = bentiTransliteration
        self.occasionId = occasion.storedId
        self.occasionCustom = occasion.storedCustomText
        self.paathId = paathId.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

extension SavedArdaas {
    /// Clarity alias for `bentiText`: the user's own words, which serve as
    /// the benti's English/translation layer.
    var bentiEnglish: String {
        get { bentiText }
        set { bentiText = newValue }
    }

    /// The two persisted occasion fields as the one value the pickers
    /// (#66/#67) and the composer reason about. Setting it keeps the pair
    /// consistent — in particular it clears `occasionCustom` for any choice
    /// that is not free text.
    var occasionChoice: OccasionChoice {
        get { OccasionChoice(occasionId: occasionId, occasionCustom: occasionCustom) }
        set {
            occasionId = newValue.storedId
            occasionCustom = newValue.storedCustomText
        }
    }

    /// All three persisted slot fields as one value, *as stored* — normalise
    /// with `SlotChoice.normalised(in:)` before showing it in a picker.
    /// Setting it writes all three, so the occasion pair stays consistent and
    /// a normalised slot clears a legacy paath out of `occasionId`.
    var slotChoice: SlotChoice {
        get { SlotChoice(paathId: paathId, occasion: occasionChoice) }
        set {
            paathId = newValue.paathId.trimmingCharacters(in: .whitespacesAndNewlines)
            occasionChoice = newValue.occasion
        }
    }

    /// The persisted layers as the value type the composer and Reader use.
    var bentiLayers: BentiLayers {
        BentiLayers(
            gurmukhi: bentiGurmukhi,
            transliteration: bentiTransliteration,
            english: bentiText
        )
    }

    /// Everything Compose edits on this record, as the value it saves — how
    /// an edit reopens the record (`ComposeForm(fields:)`).
    var fields: SavedArdaasFields {
        SavedArdaasFields(
            label: label,
            variantId: variantId,
            benti: bentiLayers,
            occasion: occasionChoice,
            paathId: paathId
        )
    }

    /// Writes Compose's fields onto this record in place: the label, the
    /// variant, all three benti layers, the occasion and the paath. `createdAt` is not
    /// touched, so an edited Ardaas keeps its place in the list.
    ///
    /// The only writer of these fields from Compose, for a new record
    /// (`make(from:)`) as much as an edited one.
    func apply(_ fields: SavedArdaasFields) {
        label = fields.label
        variantId = fields.variantId
        bentiText = fields.benti.english
        bentiGurmukhi = fields.benti.gurmukhi
        bentiTransliteration = fields.benti.transliteration
        slotChoice = fields.slot
    }

    /// A new record holding `fields`, written through `apply(_:)` so creating
    /// and editing cannot disagree about what a field means.
    static func make(from fields: SavedArdaasFields, createdAt: Date = .now) -> SavedArdaas {
        let record = SavedArdaas(label: fields.label, bentiText: fields.benti.english, createdAt: createdAt)
        record.apply(fields)
        return record
    }

    /// The slot's words — paath and occasion, joined (#158) — resolved
    /// through the bundled catalog.
    ///
    /// Nil — the canonical dots, untouched — for nothing chosen and for ids
    /// a later bundle has retired (`SlotChoice.layers(in:)`, which also reads
    /// a legacy paath out of `occasionId`). A broken bundle must cost the
    /// reader the substitution, never the Ardaas.
    ///
    /// A **free-text** occasion is stored whole on this record and needs no
    /// catalog, so it survives a catalog that failed to load (the paath, which
    /// is only an id, cannot). Dropping the user's own words because an
    /// unrelated bundled file is missing would be the wrong trade.
    func occasionLayers(in catalog: OccasionCatalog?) -> OccasionLayers? {
        guard let catalog else {
            if case .custom(let text) = occasionChoice {
                return OccasionLayers(freeText: text)
            }
            return nil
        }
        return slotChoice.layers(in: catalog)
    }

    /// Exactly what the Reader draws: this record's benti composed into
    /// `content`, with its chosen paath and occasion spliced into the "….."
    /// slot.
    ///
    /// The Reader calls nothing else, so this one function is the whole
    /// composition path and the tests that cover it cannot drift from the
    /// screen.
    func renderItems(in content: ArdaasContent, catalog: OccasionCatalog?) -> [RenderItem] {
        ArdaasComposer.compose(
            content: content,
            benti: bentiLayers,
            occasion: occasionLayers(in: catalog)
        )
    }
}
