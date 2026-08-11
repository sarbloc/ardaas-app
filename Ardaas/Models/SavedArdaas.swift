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

    init(
        label: String,
        bentiText: String,
        createdAt: Date = .now,
        variantId: String = ArdaasLibrary.defaultVariantId,
        bentiGurmukhi: String = "",
        bentiTransliteration: String = ""
    ) {
        self.label = label
        self.bentiText = bentiText
        self.createdAt = createdAt
        self.variantId = variantId
        self.bentiGurmukhi = bentiGurmukhi
        self.bentiTransliteration = bentiTransliteration
    }
}

extension SavedArdaas {
    /// Clarity alias for `bentiText`: the user's own words, which serve as
    /// the benti's English/translation layer.
    var bentiEnglish: String {
        get { bentiText }
        set { bentiText = newValue }
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
