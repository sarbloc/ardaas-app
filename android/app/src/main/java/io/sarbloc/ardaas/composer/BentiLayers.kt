package io.sarbloc.ardaas.composer

import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines

// Port of Ardaas/Models/BentiLayers.swift.

/**
 * The three display layers of a personal benti, mirroring the layers a
 * canonical `ArdaasSegment` carries so the Reader can render both with the
 * same toggles.
 *
 * Unlike a segment — where a missing layer is null and coverage is
 * all-or-none across the variant — a benti is authored incrementally: the
 * user types one layer (today, English) and the others are filled in later
 * by translation/transliteration. So each layer is a plain `String` and
 * **empty means "not written yet"**, matching how the layers are persisted
 * on the saved Ardaas (non-optional, defaulted to `""`).
 *
 * Values are normalised on construction: every layer is trimmed of
 * surrounding whitespace (by iOS's definition — see
 * [trimmingWhitespaceAndNewlines]), so a whitespace-only layer is
 * indistinguishable from an absent one and two bentis differing only in
 * padding compare equal. A plain class rather than a data class so no
 * `copy()` can bypass that normalisation.
 */
class BentiLayers(
    gurmukhi: String = "",
    transliteration: String = "",
    english: String = "",
) {
    val gurmukhi: String = gurmukhi.trimmingWhitespaceAndNewlines()
    val transliteration: String = transliteration.trimmingWhitespaceAndNewlines()
    val english: String = english.trimmingWhitespaceAndNewlines()

    /**
     * No layer carries any text — there is nothing to recite, so the
     * composer omits the benti entirely (see [ArdaasComposer.compose]).
     */
    val isEmpty: Boolean get() = gurmukhi.isEmpty() && transliteration.isEmpty() && english.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is BentiLayers &&
            gurmukhi == other.gurmukhi &&
            transliteration == other.transliteration &&
            english == other.english

    override fun hashCode(): Int = (gurmukhi.hashCode() * 31 + transliteration.hashCode()) * 31 + english.hashCode()

    override fun toString(): String =
        "BentiLayers(gurmukhi=$gurmukhi, transliteration=$transliteration, english=$english)"
}
