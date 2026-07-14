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

/// A variant's full text (`Resources/ardaas-<variant>.json`).
struct ArdaasContent: Codable, Equatable {
    let version: Int
    let sources: [String]
    let segments: [ArdaasSegment]
    let bentiSlot: BentiSlot
}

enum ArdaasContentError: Error, Equatable {
    case duplicateSegmentIds
    case invalidSlot(String)
    case emptyLayer(segmentId: String)
    case inconsistentLayer(String)
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
    }
}
