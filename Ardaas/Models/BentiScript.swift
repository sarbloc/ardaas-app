import Foundation

/// Which script a benti was typed in, as far as the Compose screen needs to
/// care: Gurmukhi text is already in the target script and only needs
/// transliterating, anything else is a candidate for machine translation.
enum BentiScript: Equatable {
    /// Gurmukhi. Translation is skipped entirely — the text *is* the Gurmukhi
    /// layer, and only the (deterministic, offline) transliteration is
    /// generated from it.
    case gurmukhi
    /// Letters outside the Gurmukhi block — English in the case the feature is
    /// built for. Eligible for on-device translation.
    case latin
    /// No letters in either script: empty, or only whitespace, digits,
    /// punctuation or emoji. Nothing to translate and nothing to transliterate.
    case undetermined
}

/// Decides which script a benti is written in by counting scalars.
///
/// ## The rule
///
/// Two counters, over the text's Unicode scalars:
///
/// * **Gurmukhi evidence** — scalars in the Gurmukhi block `U+0A00…U+0A7F`,
///   excluding the Gurmukhi digits `੦…੯` (`U+0A66…U+0A6F`). Consonants,
///   independent vowels, matras, tippi/bindi, addak, halant, nukta, the bearers
///   and `ੴ` all count.
/// * **Other evidence** — any scalar outside that block that is a letter.
///   ASCII digits, spaces, punctuation and emoji count for neither side, so
///   `"108 :)"` is `.undetermined` rather than English.
///
/// Whichever side has more scalars wins; **a tie goes to Gurmukhi**, and text
/// with no evidence at all is `.undetermined`.
///
/// ## Why it leans Gurmukhi on mixed input
///
/// The counting is deliberately not normalised per grapheme, so one Gurmukhi
/// word contributes more scalars than one Latin word of the same length (its
/// matras and signs are separate scalars). That bias is the safe direction:
///
/// * Misreading Gurmukhi as English feeds already-Gurmukhi text to an
///   English→Gurmukhi model, which produces nonsense.
/// * Misreading English as Gurmukhi costs nothing — the transliterator passes
///   Latin through untouched, so a mostly-Gurmukhi benti with an English word
///   in it comes out intact, and the user can still see and edit every layer
///   before saving.
///
/// So mixed input is classified by majority and, when it is genuinely balanced,
/// treated as Gurmukhi.
enum BentiScriptDetector {
    /// The Gurmukhi Unicode block.
    private static let gurmukhiBlock: ClosedRange<UInt32> = 0x0A00...0x0A7F
    /// `੦…੯`. Excluded from the count for the same reason ASCII digits are:
    /// a number identifies no language.
    private static let gurmukhiDigits: ClosedRange<UInt32> = 0x0A66...0x0A6F

    static func detect(_ text: String) -> BentiScript {
        var gurmukhi = 0
        var other = 0
        for scalar in text.unicodeScalars {
            if gurmukhiBlock.contains(scalar.value) {
                if !gurmukhiDigits.contains(scalar.value) { gurmukhi += 1 }
            } else if Character(scalar).isLetter {
                other += 1
            }
        }
        if gurmukhi == 0 && other == 0 { return .undetermined }
        return gurmukhi >= other ? .gurmukhi : .latin
    }
}
