import Foundation

/// The label a duplicated Ardaas is saved with (#159): "X" copies to
/// "X copy", then "X copy 2", "X copy 3" — the smallest number from 2 that no
/// saved label already uses.
///
/// Duplicating a copy counts from the original rather than stacking
/// suffixes: a trailing " copy" or " copy N" is stripped to find the base, so
/// "X copy" copies to "X copy 2", never "X copy copy".
///
/// Labels are compared trimmed, case-insensitively and up to canonical
/// equivalence (Swift's `String` equality), as a reader would see them:
/// "family COPY " already takes "Family copy", and a precomposed ਖ਼ takes the
/// same label as ਖ + nukta. Compose enforces no maximum label length, so
/// nothing is truncated.
///
/// Pure, and asserted case for case by the Android port through the shared
/// `ArdaasTests/Fixtures/duplicate-label-parity.json`.
enum DuplicateLabel {
    /// The word the copy is marked with, as typed into the label: data, not
    /// UI text, so it is not localised (the rest of the label is the user's).
    static let marker = "copy"

    /// The label for a copy of a record labelled `label`, given every label
    /// already saved (`label`'s own included).
    static func label(forCopyOf label: String, existing: [String]) -> String {
        let base = base(of: label)
        let taken = Set(existing.map(key))
        let first = joined(base, marker)
        if !taken.contains(key(first)) { return first }
        var number = 2
        while taken.contains(key(joined(base, "\(marker) \(number)"))) {
            number += 1
        }
        return joined(base, "\(marker) \(number)")
    }

    /// `label` trimmed, without a trailing " copy" or " copy N" (ASCII
    /// digits, any number of them; matched case-insensitively). A label that
    /// is nothing but the suffix keeps it — "copy 2" is a base, not a copy of
    /// nothing.
    static func base(of label: String) -> String {
        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        var rest = Substring(trimmed)
        let digitCount = rest.reversed().prefix(while: isASCIIDigit).count
        if digitCount > 0 {
            rest = rest.dropLast(digitCount)
            guard rest.last == " " else { return trimmed }
            rest = rest.dropLast()
        }
        let suffix = " " + marker
        guard rest.count > suffix.count, rest.suffix(suffix.count).lowercased() == suffix else {
            return trimmed
        }
        let base = rest.dropLast(suffix.count).trimmingCharacters(in: .whitespacesAndNewlines)
        return base.isEmpty ? trimmed : base
    }

    /// How two labels are compared: trimmed, case-insensitively. Canonical
    /// equivalence comes from `String` equality itself; Android normalises
    /// its keys to match.
    static func key(_ label: String) -> String {
        label.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private static func joined(_ base: String, _ suffix: String) -> String {
        base.isEmpty ? suffix : "\(base) \(suffix)"
    }

    /// 0–9 only: a Gurmukhi or other numeral is part of the label, not a
    /// copy number.
    private static func isASCIIDigit(_ character: Character) -> Bool {
        guard let ascii = character.asciiValue else { return false }
        return (48...57).contains(ascii)
    }
}

extension SavedArdaas {
    /// A new, uninserted record holding every user field of this one as
    /// stored — variant, all three benti layers, the occasion pair and the
    /// paath — under `label`, created at `createdAt` so it sorts as new.
    ///
    /// The fields are copied raw, not through `init` or `apply(_:)`: those
    /// normalise (the paath is trimmed, the occasion pair rewritten), and a
    /// copy must read exactly as the original does.
    func duplicate(label: String, createdAt: Date = .now) -> SavedArdaas {
        let copy = SavedArdaas(label: label, bentiText: bentiText, createdAt: createdAt)
        copy.variantId = variantId
        copy.bentiGurmukhi = bentiGurmukhi
        copy.bentiTransliteration = bentiTransliteration
        copy.occasionId = occasionId
        copy.occasionCustom = occasionCustom
        copy.paathId = paathId
        return copy
    }

    /// `duplicate(label:createdAt:)`, labelled by `DuplicateLabel` against
    /// every saved label.
    func duplicate(existingLabels: [String], createdAt: Date = .now) -> SavedArdaas {
        duplicate(
            label: DuplicateLabel.label(forCopyOf: label, existing: existingLabels),
            createdAt: createdAt
        )
    }
}
