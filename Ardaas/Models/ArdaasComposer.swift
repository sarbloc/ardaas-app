import Foundation

/// One item in the rendered Ardaas: a canonical segment or the user's benti.
enum RenderItem: Equatable {
    case canonical(ArdaasSegment)
    case benti(String)
}

enum ArdaasComposer {
    /// Pure composition: canonical segments + optional benti → ordered
    /// render sequence, with the benti inserted immediately after the
    /// slot segment.
    ///
    /// - A nil or whitespace-only benti yields the canonical sequence
    ///   unchanged (reading the plain Ardaas is valid use).
    /// - The benti is trimmed of surrounding whitespace.
    /// - An unknown slot id also yields the canonical sequence; this is
    ///   unreachable for bundled content because
    ///   `ArdaasContent.validate()` rejects it at load.
    static func compose(content: ArdaasContent, benti: String?) -> [RenderItem] {
        let canonical = content.segments.map(RenderItem.canonical)
        let trimmed = benti?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmed.isEmpty,
              let index = content.segments.firstIndex(where: { $0.id == content.bentiSlot.afterSegmentId })
        else {
            return canonical
        }
        var result = canonical
        result.insert(.benti(trimmed), at: index + 1)
        return result
    }
}
