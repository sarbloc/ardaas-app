package io.sarbloc.ardaas.content

import kotlinx.serialization.Serializable

// Port of Ardaas/Models/ArdaasContent.swift. Keep the two in step: the
// validation rules are the contract that turns a content typo into a failed
// build on BOTH platforms rather than a silently broken picker on one.

/**
 * One canonical segment of the Ardaas. Gurmukhi is always present;
 * transliteration and translation are optional per variant (some variants
 * have no attested aligned layers).
 */
@Serializable
data class ArdaasSegment(
    val id: String,
    val gurmukhi: String,
    val transliteration: String? = null,
    val english: String? = null,
) {
    /** This segment's text for one layer, null when the variant omits it. */
    fun text(layer: LayerKind): String? = when (layer) {
        LayerKind.GURMUKHI -> gurmukhi
        LayerKind.TRANSLITERATION -> transliteration
        LayerKind.ENGLISH -> english
    }
}

/** The single benti insertion point: the benti renders immediately after this segment. */
@Serializable
data class BentiSlot(val afterSegmentId: String)

/**
 * The exact text the "….." occasion placeholder is spelled with, per layer.
 *
 * Declared per layer rather than once per variant because the bundled texts
 * genuinely differ layer by layer — SGPC's human-authored Roman layer writes a
 * single `…` where its Gurmukhi writes `…..`, and its English carries a
 * parenthetical instruction ("(mention here the paath recited or the
 * occasion)") that has to disappear along with the dots once an occasion is
 * chosen. A single variant-wide string could describe none of that.
 */
@Serializable
data class OccasionPlaceholder(
    val gurmukhi: String,
    val transliteration: String? = null,
    val english: String? = null,
) {
    /** The placeholder for one layer, or null when this variant declares none. */
    operator fun get(layer: LayerKind): String? = when (layer) {
        LayerKind.GURMUKHI -> gurmukhi
        LayerKind.TRANSLITERATION -> transliteration
        LayerKind.ENGLISH -> english
    }
}

/**
 * Where a chosen occasion is spliced in — *inside* a segment's own sentence,
 * unlike the benti, which follows a segment as its own passage. The
 * placeholder is declared here and never discovered by string-matching dots,
 * so a stray ellipsis elsewhere can never be mistaken for the slot.
 */
@Serializable
data class OccasionSlot(
    val inSegmentId: String,
    val placeholder: OccasionPlaceholder,
)

/** A variant's full text (`ardaas-<variant>.json`). */
@Serializable
data class ArdaasContent(
    val version: Int,
    val sources: List<String>,
    val segments: List<ArdaasSegment>,
    val bentiSlot: BentiSlot,
    /**
     * Optional: a variant may carry no occasion slot at all. Both bundled
     * variants declare one, pinned by tests, so an omission fails CI rather
     * than silently disabling the feature.
     */
    val occasionSlot: OccasionSlot? = null,
) {
    /**
     * Whether this variant carries the layer for every segment. [validate]
     * guarantees all-or-none, so checking the first segment is sufficient.
     */
    val hasTransliteration: Boolean get() = segments.firstOrNull()?.transliteration != null
    val hasEnglish: Boolean get() = segments.firstOrNull()?.english != null

    /** Structural invariants the app relies on. Throws [ArdaasContentException]. */
    fun validate() {
        val ids = segments.map { it.id }
        if (ids.toSet().size != ids.size) throw ArdaasContentException.DuplicateSegmentIds
        if (bentiSlot.afterSegmentId !in ids) {
            throw ArdaasContentException.InvalidSlot(bentiSlot.afterSegmentId)
        }
        segments.firstOrNull {
            it.gurmukhi.isEmpty() || it.transliteration?.isEmpty() == true || it.english?.isEmpty() == true
        }?.let { throw ArdaasContentException.EmptyLayer(it.id) }

        // Optional layers are all-or-none within a variant. Mixed coverage
        // would render as unexplained gaps mid-recitation.
        val withTransliteration = segments.count { it.transliteration != null }
        if (withTransliteration != 0 && withTransliteration != segments.size) {
            throw ArdaasContentException.InconsistentLayer("transliteration")
        }
        val withEnglish = segments.count { it.english != null }
        if (withEnglish != 0 && withEnglish != segments.size) {
            throw ArdaasContentException.InconsistentLayer("english")
        }
        validateOccasionSlot()
    }

    /**
     * The declared occasion placeholder must actually be in the text it claims
     * to describe — exactly once, in every layer it is declared for.
     * Substitution is a silent no-op when it isn't, so without this a typo
     * would ship as "the picker does nothing" rather than a failed build.
     */
    private fun validateOccasionSlot() {
        val slot = occasionSlot ?: return
        val segment = segments.firstOrNull { it.id == slot.inSegmentId }
            ?: throw ArdaasContentException.InvalidOccasionSlot(slot.inSegmentId)
        for (layer in LayerKind.entries) {
            val placeholder = slot.placeholder[layer] ?: continue
            if (placeholder.isEmpty()) throw ArdaasContentException.EmptyOccasionPlaceholder(layer)
            // A missing layer counts as "not found": declaring a placeholder
            // for a layer the variant does not carry is the same defect.
            val occurrences = segment.text(layer)?.countOccurrences(placeholder) ?: 0
            if (occurrences == 0) {
                throw ArdaasContentException.OccasionPlaceholderNotFound(segment.id, layer)
            }
            if (occurrences > 1) {
                throw ArdaasContentException.OccasionPlaceholderNotUnique(segment.id, layer)
            }
        }
    }
}

/**
 * Non-overlapping occurrences, scanning left to right — the same count iOS
 * gets from `components(separatedBy:).count - 1`.
 */
internal fun String.countOccurrences(needle: String): Int {
    require(needle.isNotEmpty())
    var count = 0
    var from = indexOf(needle)
    while (from >= 0) {
        count++
        from = indexOf(needle, from + needle.length)
    }
    return count
}

/** Why a variant's content is unusable. Data types, so tests can compare them. */
sealed class ArdaasContentException(message: String) : Exception(message) {
    data object DuplicateSegmentIds : ArdaasContentException("duplicate segment ids")
    data class InvalidSlot(val segmentId: String) :
        ArdaasContentException("benti slot names unknown segment '$segmentId'")
    data class EmptyLayer(val segmentId: String) :
        ArdaasContentException("segment '$segmentId' has an empty layer")
    data class InconsistentLayer(val layer: String) :
        ArdaasContentException("layer '$layer' is present on some segments but not all")
    data class InvalidOccasionSlot(val segmentId: String) :
        ArdaasContentException("occasion slot names unknown segment '$segmentId'")
    data class EmptyOccasionPlaceholder(val layer: LayerKind) :
        ArdaasContentException("occasion placeholder for $layer is empty")
    /** Not in that layer of the segment — including when the variant has no such layer. */
    data class OccasionPlaceholderNotFound(val segmentId: String, val layer: LayerKind) :
        ArdaasContentException("occasion placeholder for $layer not found in segment '$segmentId'")
    /** More than once, so "the slot" would be ambiguous. */
    data class OccasionPlaceholderNotUnique(val segmentId: String, val layer: LayerKind) :
        ArdaasContentException("occasion placeholder for $layer occurs more than once in segment '$segmentId'")
}
