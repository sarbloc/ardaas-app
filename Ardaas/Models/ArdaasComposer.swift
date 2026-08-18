import Foundation

/// One item in the rendered Ardaas: a canonical segment or the user's benti.
enum RenderItem: Equatable {
    case canonical(ArdaasSegment)
    case benti(BentiLayers)
}

enum ArdaasComposer {
    /// Pure composition: canonical segments + optional benti + optional
    /// occasion → ordered render sequence, with the benti inserted
    /// immediately after the slot segment and the chosen occasion spliced
    /// into the "….." placeholder.
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
    /// - Occasion substitution is per layer and never destructive: see
    ///   `substituting(_:into:)`. Nothing chosen leaves the dots exactly as
    ///   authored, which is the operator's decision for #65.
    ///
    /// `content` is never mutated — substitution returns modified copies, so
    /// the bundled text a caller holds stays canonical.
    static func compose(
        content: ArdaasContent,
        benti: BentiLayers?,
        occasion: OccasionLayers? = nil
    ) -> [RenderItem] {
        let filled = substituting(occasion, into: content)
        let canonical = filled.segments.map(RenderItem.canonical)
        guard let benti, !benti.isEmpty,
              let index = filled.segments.firstIndex(where: { $0.id == filled.bentiSlot.afterSegmentId })
        else {
            return canonical
        }
        var result = canonical
        result.insert(.benti(benti), at: index + 1)
        return result
    }

    /// The slot segment alone, as it reads with `occasion` spliced into it —
    /// nil when the variant declares no occasion slot, or names a segment
    /// that isn't there.
    ///
    /// Exists so the Compose picker (#66) can quote the sentence back to the
    /// user through the *same* substitution `compose` performs, rather than
    /// re-implementing the splice and drifting from what the Reader renders.
    static func occasionSlotSegment(
        of content: ArdaasContent,
        occasion: OccasionLayers?
    ) -> ArdaasSegment? {
        let filled = substituting(occasion, into: content)
        guard let slot = filled.occasionSlot else { return nil }
        return filled.segments.first { $0.id == slot.inSegmentId }
    }

    /// A copy of `content` with the chosen occasion spliced into the slot
    /// segment, layer by layer.
    ///
    /// Every step is a no-op that returns the text untouched rather than a
    /// blank: no occasion chosen, no occasion slot declared, an unknown slot
    /// segment, a layer the variant does not carry, or a layer this occasion
    /// has no words for (free text is one script only — see
    /// `OccasionLayers.init(freeText:)`). The placeholder surviving is always
    /// preferable to a hole in a canonical sentence.
    private static func substituting(
        _ occasion: OccasionLayers?,
        into content: ArdaasContent
    ) -> ArdaasContent {
        guard let occasion, !occasion.isEmpty,
              let slot = content.occasionSlot,
              let index = content.segments.firstIndex(where: { $0.id == slot.inSegmentId })
        else {
            return content
        }
        let segment = content.segments[index]
        let filled = ArdaasSegment(
            id: segment.id,
            gurmukhi: splice(occasion.gurmukhi, into: segment.gurmukhi,
                             at: slot.placeholder.gurmukhi),
            // `map` keeps a layer the variant does not carry as nil — a
            // missing layer must stay missing, never become an empty string.
            transliteration: segment.transliteration.map {
                splice(occasion.transliteration, into: $0, at: slot.placeholder.transliteration)
            },
            english: segment.english.map {
                splice(occasion.english, into: $0, at: slot.placeholder.english)
            }
        )
        var segments = content.segments
        segments[index] = filled
        return ArdaasContent(
            version: content.version,
            sources: content.sources,
            segments: segments,
            bentiSlot: content.bentiSlot,
            occasionSlot: content.occasionSlot
        )
    }

    /// Replaces the first occurrence of `placeholder` in `text` with
    /// `replacement`, repairing the seams. Returns `text` unchanged when any
    /// part is missing or the placeholder isn't there.
    ///
    /// ## Why the seams need repairing
    ///
    /// The dots stand in for the words *and* for the spaces around them: the
    /// canonical text is written tight, `ਆਪ ਦੇ ਹਜ਼ੂਰ…..ਦੀ ਅਰਦਾਸ`, so a plain
    /// replacement would glue the occasion to its neighbours
    /// (`ਹਜ਼ੂਰਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠਦੀ`). So:
    ///
    /// * exactly one space before the occasion (none if it starts the text);
    /// * exactly one space after it **only when the text continues with a
    ///   word** — SGPC's English placeholder is followed by the sentence's
    ///   full stop, which must not be pushed away from it.
    ///
    /// Whitespace already at the seams is absorbed, so SGPC's Roman layer
    /// (`Hazur… Di`, one ellipsis then a space) comes back single-spaced.
    /// `ArdaasContent.validate()` guarantees the placeholder is unique in the
    /// layer, so "the first occurrence" is unambiguous for bundled content.
    private static func splice(
        _ replacement: String,
        into text: String,
        at placeholder: String?
    ) -> String {
        guard !replacement.isEmpty,
              let placeholder, !placeholder.isEmpty,
              let range = text.range(of: placeholder)
        else {
            return text
        }
        var prefix = text[..<range.lowerBound]
        while let last = prefix.last, last.isWhitespace { prefix.removeLast() }
        var suffix = text[range.upperBound...]
        while let first = suffix.first, first.isWhitespace { suffix.removeFirst() }

        let leading = prefix.isEmpty ? "" : " "
        let continuesWithAWord = suffix.first.map { $0.isLetter || $0.isNumber } ?? false
        let trailing = continuesWithAWord ? " " : ""
        return String(prefix) + leading + replacement + trailing + String(suffix)
    }
}
