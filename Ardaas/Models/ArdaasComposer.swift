import Foundation

/// One item in the rendered Ardaas: a canonical segment or the user's benti.
enum RenderItem: Equatable {
    case canonical(ArdaasSegment)
    case benti(BentiLayers)
}

enum ArdaasComposer {
    /// Pure composition: canonical segments + optional benti → ordered
    /// render sequence, with the benti inserted immediately after the
    /// slot segment.
    ///
    /// - A nil benti, or one whose every layer is blank, yields the
    ///   canonical sequence unchanged (reading the plain Ardaas is valid
    ///   use). A benti with *any* populated layer is inserted, even if the
    ///   others are still empty — a benti awaiting translation is real.
    /// - Each layer is trimmed of surrounding whitespace (by
    ///   `BentiLayers`), so a whitespace-only layer counts as blank.
    /// - An unknown slot id also yields the canonical sequence; this is
    ///   unreachable for bundled content because
    ///   `ArdaasContent.validate()` rejects it at load.
    static func compose(content: ArdaasContent, benti: BentiLayers?) -> [RenderItem] {
        let canonical = content.segments.map(RenderItem.canonical)
        guard let benti, !benti.isEmpty,
              let index = content.segments.firstIndex(where: { $0.id == content.bentiSlot.afterSegmentId })
        else {
            return canonical
        }
        var result = canonical
        result.insert(.benti(benti), at: index + 1)
        return result
    }
}
