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
    var occasionCustom: String = ""

    init(
        label: String,
        bentiText: String,
        createdAt: Date = .now,
        variantId: String = ArdaasLibrary.defaultVariantId,
        bentiGurmukhi: String = "",
        bentiTransliteration: String = "",
        occasionId: String = "",
        occasionCustom: String = ""
    ) {
        self.label = label
        self.bentiText = bentiText
        self.createdAt = createdAt
        self.variantId = variantId
        self.bentiGurmukhi = bentiGurmukhi
        self.bentiTransliteration = bentiTransliteration
        self.occasionId = occasionId
        self.occasionCustom = occasionCustom
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

    /// The persisted layers as the value type the composer and Reader use.
    var bentiLayers: BentiLayers {
        BentiLayers(
            gurmukhi: bentiGurmukhi,
            transliteration: bentiTransliteration,
            english: bentiText
        )
    }
}
