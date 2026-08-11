import Foundation

/// The three display layers of a personal benti, mirroring the layers a
/// canonical `ArdaasSegment` carries so the Reader can render both with the
/// same toggles.
///
/// Unlike a segment — where a missing layer is `nil` and coverage is
/// all-or-none across the variant — a benti is authored incrementally: the
/// user types one layer (today, English) and the others are filled in later
/// by translation/transliteration. So each layer is a plain `String` and
/// **empty means "not written yet"**, matching how the layers are persisted
/// on `SavedArdaas` (non-optional, defaulted to `""` for migration).
///
/// Values are normalised on init: every layer is trimmed of surrounding
/// whitespace, so a whitespace-only layer is indistinguishable from an
/// absent one and two bentis differing only in padding compare equal.
struct BentiLayers: Equatable {
    let gurmukhi: String
    let transliteration: String
    let english: String

    init(gurmukhi: String = "", transliteration: String = "", english: String = "") {
        self.gurmukhi = Self.normalise(gurmukhi)
        self.transliteration = Self.normalise(transliteration)
        self.english = Self.normalise(english)
    }

    private static func normalise(_ layer: String) -> String {
        layer.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// No layer carries any text — there is nothing to recite, so the
    /// composer omits the benti entirely (see `ArdaasComposer.compose`).
    var isEmpty: Bool {
        gurmukhi.isEmpty && transliteration.isEmpty && english.isEmpty
    }
}
