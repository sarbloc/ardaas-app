import Foundation

/// One canonical segment of the Ardaas, in all three layers.
struct ArdaasSegment: Codable, Equatable, Identifiable {
    let id: String
    let gurmukhi: String
    let transliteration: String
    let english: String
}

/// The single benti insertion point: the benti renders immediately after
/// the segment with this id.
struct BentiSlot: Codable, Equatable {
    let afterSegmentId: String
}

/// The bundled canonical Ardaas (`Ardaas/Resources/ardaas.json`).
struct ArdaasContent: Codable, Equatable {
    let version: Int
    let sources: [String]
    let segments: [ArdaasSegment]
    let bentiSlot: BentiSlot
}

enum ArdaasContentError: Error, Equatable {
    case resourceMissing
    case duplicateSegmentIds
    case invalidSlot(String)
    case emptyLayer(segmentId: String)
}

/// Anchors bundle resolution to whatever binary this file is compiled
/// into (the app target), independent of the host process — so resource
/// lookup works identically in the app and in hosted or unhosted tests.
private final class BundleToken {}

extension ArdaasContent {
    /// Loads and validates the bundled canonical text. Throws — a broken
    /// bundle is a build defect, not a recoverable runtime state.
    static func loadBundled(from bundle: Bundle = Bundle(for: BundleToken.self)) throws -> ArdaasContent {
        guard let url = bundle.url(forResource: "ardaas", withExtension: "json") else {
            throw ArdaasContentError.resourceMissing
        }
        let content = try JSONDecoder().decode(ArdaasContent.self, from: Data(contentsOf: url))
        try content.validate()
        return content
    }

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
            $0.gurmukhi.isEmpty || $0.transliteration.isEmpty || $0.english.isEmpty
        }) {
            throw ArdaasContentError.emptyLayer(segmentId: empty.id)
        }
    }
}
