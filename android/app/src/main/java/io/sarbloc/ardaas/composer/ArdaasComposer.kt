package io.sarbloc.ardaas.composer

import io.sarbloc.ardaas.content.ArdaasContent
import io.sarbloc.ardaas.content.ArdaasSegment
import io.sarbloc.ardaas.occasion.OccasionLayers
import io.sarbloc.ardaas.text.isSwiftWhitespace

// Port of Ardaas/Models/ArdaasComposer.swift. Keep the two in step: the
// splice rules decide how a canonical sentence reads, and a reader switching
// phones must see the same words.

/** One item in the rendered Ardaas: a canonical segment or the user's benti. */
sealed interface RenderItem {
    data class Canonical(val segment: ArdaasSegment) : RenderItem
    data class Benti(val layers: BentiLayers) : RenderItem
}

object ArdaasComposer {
    /**
     * Pure composition: canonical segments + optional benti + optional
     * occasion → ordered render sequence, with the benti inserted
     * immediately after the slot segment and the chosen occasion spliced
     * into the "….." placeholder.
     *
     * - A null benti, or one whose every layer is blank, yields the
     *   canonical sequence unchanged (reading the plain Ardaas is valid
     *   use). A benti with *any* populated layer is inserted, even if the
     *   others are still empty — a benti awaiting translation is real.
     * - Each layer is trimmed of surrounding whitespace (by [BentiLayers]),
     *   so a whitespace-only layer counts as blank.
     * - An unknown slot id also yields the canonical sequence; this is
     *   unreachable for bundled content because [ArdaasContent.validate]
     *   rejects it at load.
     * - Occasion substitution is per layer and never destructive: see
     *   [substituting]. Nothing chosen leaves the dots exactly as authored,
     *   which is the operator's decision for #65.
     *
     * [content] is never mutated — substitution returns modified copies, so
     * the bundled text a caller holds stays canonical.
     */
    fun compose(
        content: ArdaasContent,
        benti: BentiLayers?,
        occasion: OccasionLayers? = null,
    ): List<RenderItem> {
        val filled = substituting(occasion, content)
        val canonical = filled.segments.map { RenderItem.Canonical(it) }
        if (benti == null || benti.isEmpty) return canonical
        val index = filled.segments.indexOfFirst { it.id == filled.bentiSlot.afterSegmentId }
        if (index < 0) return canonical
        return canonical.toMutableList<RenderItem>().apply { add(index + 1, RenderItem.Benti(benti)) }
    }

    /**
     * The slot segment alone, as it reads with [occasion] spliced into it —
     * null when the variant declares no occasion slot, or names a segment
     * that isn't there.
     *
     * Exists so the Compose picker can quote the sentence back to the user
     * through the *same* substitution [compose] performs, rather than
     * re-implementing the splice and drifting from what the Reader renders.
     */
    fun occasionSlotSegment(content: ArdaasContent, occasion: OccasionLayers?): ArdaasSegment? {
        val filled = substituting(occasion, content)
        val slot = filled.occasionSlot ?: return null
        return filled.segments.firstOrNull { it.id == slot.inSegmentId }
    }

    /**
     * A copy of [content] with the chosen occasion spliced into the slot
     * segment, layer by layer.
     *
     * Every step is a no-op that returns the text untouched rather than a
     * blank: no occasion chosen, no occasion slot declared, an unknown slot
     * segment, a layer the variant does not carry, or a layer this occasion
     * has no words for (free text is one script only). The placeholder
     * surviving is always preferable to a hole in a canonical sentence.
     */
    private fun substituting(occasion: OccasionLayers?, content: ArdaasContent): ArdaasContent {
        if (occasion == null || occasion.isEmpty) return content
        val slot = content.occasionSlot ?: return content
        val index = content.segments.indexOfFirst { it.id == slot.inSegmentId }
        if (index < 0) return content

        val segment = content.segments[index]
        val filled = ArdaasSegment(
            id = segment.id,
            gurmukhi = splice(occasion.gurmukhi, segment.gurmukhi, slot.placeholder.gurmukhi),
            // `?.let` keeps a layer the variant does not carry as null — a
            // missing layer must stay missing, never become an empty string.
            transliteration = segment.transliteration?.let {
                splice(occasion.transliteration, it, slot.placeholder.transliteration)
            },
            english = segment.english?.let { splice(occasion.english, it, slot.placeholder.english) },
        )
        val segments = content.segments.toMutableList().apply { set(index, filled) }
        return content.copy(segments = segments)
    }

    /**
     * Replaces the first occurrence of [placeholder] in [text] with
     * [replacement], repairing the seams. Returns [text] unchanged when any
     * part is missing or the placeholder isn't there.
     *
     * ## Why the seams need repairing
     *
     * The dots stand in for the words *and* for the spaces around them: the
     * canonical text is written tight, `ਆਪ ਦੇ ਹਜ਼ੂਰ…..ਦੀ ਅਰਦਾਸ`, so a plain
     * replacement would glue the occasion to its neighbours
     * (`ਹਜ਼ੂਰਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠਦੀ`). So:
     *
     * * exactly one space before the occasion (none if it starts the text);
     * * exactly one space after it **only when the text continues with a
     *   word** — SGPC's English placeholder is followed by the sentence's
     *   full stop, which must not be pushed away from it.
     *
     * Whitespace already at the seams is absorbed, so SGPC's Roman layer
     * (`Hazur… Di`, one ellipsis then a space) comes back single-spaced.
     * [ArdaasContent.validate] guarantees the placeholder is unique in the
     * layer, so "the first occurrence" is unambiguous for bundled content.
     *
     * ## Matching iOS
     *
     * Whitespace is iOS's set, not Kotlin's (see [isSwiftWhitespace]), and
     * "a word" is iOS's `Character.isLetter || isNumber`: the Unicode
     * `Alphabetic` property — which, unlike [Char.isLetter], includes
     * Gurmukhi vowel signs — or any number category. iOS walks grapheme
     * clusters where this walks UTF-16 units; the two differ only for a
     * cluster that starts with whitespace and carries a combining mark
     * (a vowel sign on a bare space), which no proof-read text contains.
     * The search is literal, like [ArdaasContent.validate]'s count.
     */
    private fun splice(replacement: String, text: String, placeholder: String?): String {
        if (replacement.isEmpty() || placeholder.isNullOrEmpty()) return text
        val start = text.indexOf(placeholder)
        if (start < 0) return text

        val prefix = text.substring(0, start).trimEnd { it.isSwiftWhitespace() }
        val suffix = text.substring(start + placeholder.length).trimStart { it.isSwiftWhitespace() }

        val leading = if (prefix.isEmpty()) "" else " "
        val trailing = if (suffix.startsWithAWord()) " " else ""
        return prefix + leading + replacement + trailing + suffix
    }

    private fun String.startsWithAWord(): Boolean {
        if (isEmpty()) return false
        val first = codePointAt(0)
        if (Character.isAlphabetic(first)) return true
        return when (Character.getType(first).toByte()) {
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }
    }
}
