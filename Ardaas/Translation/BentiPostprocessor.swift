import Foundation

/// The pure text stages that turn model output into a Gurmukhi benti
/// (pipeline spec section 5): id-drop + piece-join detokenization, HF cleanup,
/// placeholder restore, Devanagari → Gurmukhi transliteration, indic
/// detokenization, and the script purity gate.
///
/// Split out of the translator so it compiles and is unit-tested in every
/// configuration — none of it needs ONNX Runtime or the 359 MB artifact.
enum BentiPostprocessor {
    // MARK: - Ids -> raw Devanagari string (spec section 5.1)

    static func decodeToText(ids: [Int64], idToPiece: [Int64: String]) -> String {
        // Drop specials (< 4), join pieces, U+2581 -> space, trim.
        let joined = ids
            .filter { $0 >= 4 }
            .compactMap { idToPiece[$0] }
            .joined()
            .replacingOccurrences(of: "\u{2581}", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return cleanUpTokenizationSpaces(joined)
    }

    /// HF `clean_up_tokenization_spaces` replacements, in reference order.
    static func cleanUpTokenizationSpaces(_ text: String) -> String {
        [
            (" .", "."), (" ?", "?"), (" !", "!"), (" ,", ","),
            (" ' ", "'"), (" n't", "n't"), (" 'm", "'m"), (" 's", "'s"),
            (" 've", "'ve"), (" 're", "'re"),
        ].reduce(text) { $0.replacingOccurrences(of: $1.0, with: $1.1) }
    }

    // MARK: - Placeholder restore (spec section 5.2)

    static func restorePlaceholders(
        _ text: String,
        placeholders: [(alias: String, original: String)]
    ) -> String {
        placeholders.reduce(text) { $0.replacingOccurrences(of: $1.alias, with: $1.original) }
    }

    // MARK: - Devanagari -> Gurmukhi (spec section 5.3)

    static func transliterateDevanagariToGurmukhi(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        for scalar in text.unicodeScalars {
            let offset = Int(scalar.value) - 0x0900
            if (0x00...0x6F).contains(offset),
               scalar.value != 0x0964, scalar.value != 0x0965,  // danda, double danda
               let mapped = Unicode.Scalar(0x0A00 + offset) {
                out.append(mapped)
            } else {
                out.append(scalar)
            }
        }
        return String(out)
    }

    // MARK: - Indic detokenize (spec section 5.4; indic_nlp_library
    // trivial_detokenize_indic, ported bug-for-bug)

    // Patterns are compile-time constants; an invalid one is a programmer
    // error caught by the first unit test run, never by a user.
    // swiftlint:disable force_try
    private static let numberSequence = try! NSRegularExpression(
        pattern: "([0-9]+ [,.:/] )+[0-9]+")
    private static let bothAttach = try! NSRegularExpression(pattern: "[ ]([-/\\\\])[ ]")
    private static let leftAttach = try! NSRegularExpression(
        pattern: "[ ]([!%)\\]},.:;>?\u{0964}\u{0965}])")
    private static let rightAttach = try! NSRegularExpression(pattern: "([#$(\\[{<@])[ ]")
    // swiftlint:enable force_try

    static func indicDetokenize(_ text: String) -> String {
        var s = text

        // Number sequences: collapse inner spaces. The reference skips a
        // match that starts exactly at the previous boundary (`start > prev`),
        // which leaves a leading match untouched — kept for parity.
        var parts: [String] = []
        var prev = s.startIndex
        numberSequence.enumerateMatches(in: s, range: NSRange(s.startIndex..., in: s)) { match, _, _ in
            guard let match, let r = Range(match.range, in: s) else { return }
            if r.lowerBound > prev {
                parts.append(String(s[prev..<r.lowerBound]))
                parts.append(String(s[r]).replacingOccurrences(of: " ", with: ""))
                prev = r.upperBound
            }
        }
        parts.append(String(s[prev...]))
        s = parts.joined()

        let full = { NSRange(s.startIndex..., in: s) }
        s = bothAttach.stringByReplacingMatches(in: s, range: full(), withTemplate: "$1")
        s = leftAttach.stringByReplacingMatches(in: s, range: full(), withTemplate: "$1")
        s = rightAttach.stringByReplacingMatches(in: s, range: full(), withTemplate: "$1")

        // Quotes alternate: odd occurrences attach right, even attach left.
        for quote in ["'", "\"", "`"] {
            var count = 0
            var marked = ""
            for char in s {
                if String(char) == quote {
                    marked += count % 2 == 0 ? "@RA" : "@LA"
                    count += 1
                } else {
                    marked.append(char)
                }
            }
            s = marked
                .replacingOccurrences(of: "@RA ", with: quote)
                .replacingOccurrences(of: " @LA", with: quote)
                .replacingOccurrences(of: "@RA", with: quote)
                .replacingOccurrences(of: "@LA", with: quote)
        }
        return s
    }

    // MARK: - Script purity gate (spec section 5.5)

    /// No letters, marks or numbers outside U+0A00–U+0A7F.
    ///
    /// Advisory, not a hard gate: a restored placeholder (a date, a number)
    /// legitimately trips it, so `#44` shows it as a caution on the draft
    /// rather than refusing the translation.
    static func isGurmukhiPure(_ text: String) -> Bool {
        for scalar in text.unicodeScalars {
            if (0x0A00...0x0A7F).contains(scalar.value) { continue }
            switch scalar.properties.generalCategory {
            case .lowercaseLetter, .uppercaseLetter, .titlecaseLetter,
                 .modifierLetter, .otherLetter,
                 .nonspacingMark, .spacingMark, .enclosingMark,
                 .decimalNumber, .letterNumber, .otherNumber:
                return false
            default:
                continue
            }
        }
        return true
    }
}
