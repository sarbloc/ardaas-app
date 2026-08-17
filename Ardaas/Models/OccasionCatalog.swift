import Foundation

/// What a slot entry names: a paath that was recited, or the occasion the
/// Ardaas marks. The UI groups the picker by this.
enum OccasionCategory: String, Codable, Equatable, CaseIterable {
    case paath
    case occasion
}

/// One choice for the "….." slot.
///
/// Every layer is authored in **genitive form**, because the slot sits inside
/// a genitive construction: SGPC reads `ਆਪ ਦੇ ਹਜ਼ੂਰ…..ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥` and
/// Buddha Dal `ਆਪ ਜੀ ਦੇ ਹਜ਼ੂਰ…..ਦੀ ਅਰਦਾਸ…`. A bare name does not close that
/// sentence — `ਜਪੁ ਜੀ ਸਾਹਿਬ` is wrong where `ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ` is right —
/// so each entry is a noun phrase that reads correctly followed by `ਦੀ ਅਰਦਾਸ`.
/// `OccasionCatalogTests` asserts that against the real bundled sentences.
///
/// The same list serves both variants (operator decision on #64).
struct Occasion: Codable, Equatable, Identifiable {
    let id: String
    let category: OccasionCategory
    let gurmukhi: String
    let transliteration: String
    let english: String
}

/// The bundled slot list (`Resources/occasions.json`).
///
/// The Roman layer is **generated** by `GurmukhiTransliterator`, like the
/// Buddha Dal one and unlike the human-authored SGPC layer, so that a chosen
/// occasion and a user's benti romanize in one voice. It is checked in rather
/// than computed at launch so it can be proof-read and diffed like any other
/// scripture-adjacent text, and pinned to the engine by
/// `testBundledTransliterationMatchesTheEngine`.
struct OccasionCatalog: Codable, Equatable {
    let version: Int
    let sources: [String]
    let occasions: [Occasion]
}

enum OccasionCatalogError: Error, Equatable {
    case resourceMissing
    case noOccasions
    case duplicateOccasionIds
    case emptyLayer(occasionId: String)
}

extension OccasionCatalog {
    /// Entries of one category, in bundled order (which is display order).
    func occasions(in category: OccasionCategory) -> [Occasion] {
        occasions.filter { $0.category == category }
    }

    /// Loads and validates the bundled catalog. Throws — a broken bundle is a
    /// build defect, not a recoverable runtime state.
    static func loadBundled(from bundle: Bundle = .occasionModule) throws -> OccasionCatalog {
        guard let url = bundle.url(forResource: "occasions", withExtension: "json") else {
            throw OccasionCatalogError.resourceMissing
        }
        let catalog = try JSONDecoder().decode(Self.self, from: Data(contentsOf: url))
        try catalog.validate()
        return catalog
    }

    /// Structural invariants the app relies on. Mirrors the checks CI's unit
    /// tests run against the real bundled JSON.
    func validate() throws {
        guard !occasions.isEmpty else {
            throw OccasionCatalogError.noOccasions
        }
        let ids = occasions.map(\.id)
        guard Set(ids).count == ids.count else {
            throw OccasionCatalogError.duplicateOccasionIds
        }
        // Unlike a variant's optional layers, all three are required here:
        // the picker shows whichever the reader has turned on, so a missing
        // one would render as a blank row.
        if let empty = occasions.first(where: {
            $0.gurmukhi.isEmpty || $0.transliteration.isEmpty || $0.english.isEmpty
        }) {
            throw OccasionCatalogError.emptyLayer(occasionId: empty.id)
        }
    }
}

private final class OccasionBundleToken {}

private extension Bundle {
    /// The bundle this code is compiled into (the app target) — resource
    /// lookup works identically in the app and in hosted tests.
    static var occasionModule: Bundle { Bundle(for: OccasionBundleToken.self) }
}
