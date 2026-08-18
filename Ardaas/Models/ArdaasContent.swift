import Foundation

/// One canonical segment of the Ardaas. Gurmukhi is always present;
/// transliteration and translation are optional per variant (some
/// variants have no attested aligned layers).
struct ArdaasSegment: Codable, Equatable, Identifiable {
    let id: String
    let gurmukhi: String
    let transliteration: String?
    let english: String?
}

/// The single benti insertion point: the benti renders immediately after
/// the segment with this id.
struct BentiSlot: Codable, Equatable {
    let afterSegmentId: String
}

/// The exact text the "….." occasion placeholder is spelled with, per layer.
///
/// Mirrors `ArdaasSegment`: Gurmukhi is always present, the other two only
/// when the variant carries that layer. Declared per layer rather than once
/// per variant because the bundled texts genuinely differ layer by layer —
/// SGPC's human-authored Roman layer writes a single `…` where its Gurmukhi
/// writes `…..`, and its English carries a parenthetical instruction
/// ("(mention here the paath recited or the occasion)") that has to disappear
/// along with the dots once an occasion is chosen. A single variant-wide
/// string could describe none of that.
struct OccasionPlaceholder: Codable, Equatable {
    let gurmukhi: String
    let transliteration: String?
    let english: String?

    /// The placeholder for one layer, or nil when this variant declares none.
    subscript(layer: LayerKind) -> String? {
        switch layer {
        case .gurmukhi: return gurmukhi
        case .transliteration: return transliteration
        case .english: return english
        }
    }
}

/// Where a chosen occasion is spliced in.
///
/// Unlike `BentiSlot` — the benti renders *after* a segment, as its own
/// passage — the occasion sits *inside* a segment's own sentence, in the
/// genitive: `ਆਪ ਦੇ ਹਜ਼ੂਰ…..ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥`. The placeholder is **declared**
/// here and never discovered by string-matching dots, so a stray ellipsis
/// elsewhere in the text can never be mistaken for the slot, and
/// `ArdaasContent.validate()` fails the build when a declaration and the text
/// it describes drift apart.
struct OccasionSlot: Codable, Equatable {
    let inSegmentId: String
    let placeholder: OccasionPlaceholder
}

/// A variant's full text (`Resources/ardaas-<variant>.json`).
struct ArdaasContent: Codable, Equatable {
    let version: Int
    let sources: [String]
    let segments: [ArdaasSegment]
    let bentiSlot: BentiSlot
    /// Optional: a variant may carry no occasion slot at all (and then
    /// nothing is ever substituted into it). Both bundled variants declare
    /// one, pinned by `ArdaasContentTests`, so an omission or a typo'd key
    /// fails CI rather than silently disabling the feature.
    let occasionSlot: OccasionSlot?
}

enum ArdaasContentError: Error, Equatable {
    case duplicateSegmentIds
    case invalidSlot(String)
    case emptyLayer(segmentId: String)
    case inconsistentLayer(String)
    case invalidOccasionSlot(String)
    case emptyOccasionPlaceholder(layer: LayerKind)
    /// The declared placeholder does not occur in that layer of the named
    /// segment — including the case where the variant has no such layer.
    case occasionPlaceholderNotFound(segmentId: String, layer: LayerKind)
    /// It occurs more than once, so "the slot" would be ambiguous.
    case occasionPlaceholderNotUnique(segmentId: String, layer: LayerKind)
}

extension ArdaasSegment {
    /// This segment's text for one layer, nil when the variant omits it.
    func text(for layer: LayerKind) -> String? {
        switch layer {
        case .gurmukhi: return gurmukhi
        case .transliteration: return transliteration
        case .english: return english
        }
    }
}

extension ArdaasContent {
    /// Whether this variant carries the layer for every segment.
    /// `validate()` guarantees all-or-none, so checking the first
    /// segment is sufficient.
    var hasTransliteration: Bool { segments.first?.transliteration != nil }
    var hasEnglish: Bool { segments.first?.english != nil }

    /// Structural invariants the app relies on. Mirrors the checks CI's
    /// unit tests run against the real bundled JSON.
    func validate() throws {
        let ids = segments.map(\.id)
        guard Set(ids).count == ids.count else {
            throw ArdaasContentError.duplicateSegmentIds
        }
        guard ids.contains(bentiSlot.afterSegmentId) else {
            throw ArdaasContentError.invalidSlot(bentiSlot.afterSegmentId)
        }
        if let empty = segments.first(where: {
            $0.gurmukhi.isEmpty
                || $0.transliteration?.isEmpty == true
                || $0.english?.isEmpty == true
        }) {
            throw ArdaasContentError.emptyLayer(segmentId: empty.id)
        }
        // Optional layers are all-or-none within a variant: a text either
        // has a transliteration/translation or it doesn't. Mixed coverage
        // would render as unexplained gaps mid-recitation.
        let withTransliteration = segments.filter { $0.transliteration != nil }.count
        guard withTransliteration == 0 || withTransliteration == segments.count else {
            throw ArdaasContentError.inconsistentLayer("transliteration")
        }
        let withEnglish = segments.filter { $0.english != nil }.count
        guard withEnglish == 0 || withEnglish == segments.count else {
            throw ArdaasContentError.inconsistentLayer("english")
        }
        try validateOccasionSlot()
    }

    /// The declared occasion placeholder must actually be in the text it
    /// claims to describe — exactly once, in every layer it is declared for.
    /// Substitution is a silent no-op when it isn't, so without this a typo
    /// would ship as "the picker does nothing" rather than a failed build.
    ///
    /// What this cannot decide is whether a declaration that *is* present
    /// covers everything that should disappear: a shortened SGPC English
    /// declaration would still match, and would leave the parenthetical
    /// instruction stranded after the chosen occasion. That is circular here
    /// — the declaration is the only statement of what the slot is — so it is
    /// pinned instead by `ArdaasContentTests`, which asserts the bundled
    /// declarations character for character.
    private func validateOccasionSlot() throws {
        guard let slot = occasionSlot else { return }
        guard let segment = segments.first(where: { $0.id == slot.inSegmentId }) else {
            throw ArdaasContentError.invalidOccasionSlot(slot.inSegmentId)
        }
        for layer in LayerKind.allCases {
            guard let placeholder = slot.placeholder[layer] else { continue }
            guard !placeholder.isEmpty else {
                throw ArdaasContentError.emptyOccasionPlaceholder(layer: layer)
            }
            // A missing layer counts as "not found": declaring a placeholder
            // for a layer the variant does not carry is the same defect.
            let occurrences = segment.text(for: layer)
                .map { $0.components(separatedBy: placeholder).count - 1 } ?? 0
            guard occurrences > 0 else {
                throw ArdaasContentError.occasionPlaceholderNotFound(
                    segmentId: segment.id, layer: layer
                )
            }
            guard occurrences == 1 else {
                throw ArdaasContentError.occasionPlaceholderNotUnique(
                    segmentId: segment.id, layer: layer
                )
            }
        }
    }
}
