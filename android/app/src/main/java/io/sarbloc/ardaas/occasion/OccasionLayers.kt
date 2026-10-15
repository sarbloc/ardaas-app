package io.sarbloc.ardaas.occasion

import io.sarbloc.ardaas.content.LayerKind
import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines

// Port of the OccasionLayers struct in Ardaas/Models/OccasionChoice.swift.
// Only the plain data type lives here for now: the free-text initialiser
// (which needs the transliterator) and OccasionChoice/OccasionCatalog land
// with #85.

/**
 * The three display layers of a chosen occasion, ready to be spliced into
 * the "….." slot. Mirrors `BentiLayers`: each layer is a plain `String` and
 * **empty means "nothing for this layer"**, which the composer reads as
 * "leave that layer's placeholder alone" rather than "blank the slot".
 *
 * Values are trimmed on construction, so padding can never travel into the
 * middle of a canonical sentence. A plain class rather than a data class so
 * no `copy()` can bypass that normalisation.
 */
class OccasionLayers(
    gurmukhi: String = "",
    transliteration: String = "",
    english: String = "",
) {
    val gurmukhi: String = gurmukhi.trimmingWhitespaceAndNewlines()
    val transliteration: String = transliteration.trimmingWhitespaceAndNewlines()
    val english: String = english.trimmingWhitespaceAndNewlines()

    operator fun get(layer: LayerKind): String = when (layer) {
        LayerKind.GURMUKHI -> gurmukhi
        LayerKind.TRANSLITERATION -> transliteration
        LayerKind.ENGLISH -> english
    }

    /**
     * No layer carries text, so there is nothing to substitute anywhere —
     * the composer treats this exactly like "nothing chosen".
     */
    val isEmpty: Boolean get() = gurmukhi.isEmpty() && transliteration.isEmpty() && english.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is OccasionLayers &&
            gurmukhi == other.gurmukhi &&
            transliteration == other.transliteration &&
            english == other.english

    override fun hashCode(): Int = (gurmukhi.hashCode() * 31 + transliteration.hashCode()) * 31 + english.hashCode()

    override fun toString(): String =
        "OccasionLayers(gurmukhi=$gurmukhi, transliteration=$transliteration, english=$english)"
}
