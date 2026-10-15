package io.sarbloc.ardaas.occasion

import io.sarbloc.ardaas.content.LayerKind
import io.sarbloc.ardaas.text.BentiScript
import io.sarbloc.ardaas.text.BentiScriptDetector
import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines
import io.sarbloc.ardaas.transliteration.GurmukhiTransliterator

// Port of the OccasionLayers struct and its free-text initialiser in
// Ardaas/Models/OccasionChoice.swift.

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

    companion object {
        /**
         * A free-text occasion, typed by the user rather than picked from the
         * bundled list.
         *
         * ## The one-script rule
         *
         * Free text arrives in exactly one script, and it is not machine
         * translated here. So:
         *
         * * **Gurmukhi in** ([BentiScriptDetector] says [BentiScript.GURMUKHI])
         *   → it *is* the Gurmukhi layer, and the Roman layer is derived from
         *   it with [GurmukhiTransliterator] — the same deterministic, offline
         *   path that generated the bundled catalog's Roman layer, so a typed
         *   occasion and a picked one romanize in one voice. English is left
         *   empty.
         * * **Anything else** ([BentiScript.LATIN], or
         *   [BentiScript.UNDETERMINED] — digits and punctuation only) → the
         *   English layer, verbatim. Roman-script Punjabi lands here too: the
         *   detector counts scripts, not languages.
         *
         * The layers left empty keep their dots (see `ArdaasComposer`), so a
         * typed occasion never blanks the slot in the layers it cannot speak
         * to. On a variant with no English layer at all, Latin free text has
         * nowhere to appear — see [OccasionChoice.willNotAppear] (#72).
         *
         * Returns null for text that is empty or whitespace-only — there is
         * nothing to say, which is "nothing chosen".
         */
        fun fromFreeText(text: String): OccasionLayers? {
            val trimmed = text.trimmingWhitespaceAndNewlines()
            if (trimmed.isEmpty()) return null
            return when (BentiScriptDetector.detect(trimmed)) {
                BentiScript.GURMUKHI -> OccasionLayers(
                    gurmukhi = trimmed,
                    transliteration = GurmukhiTransliterator.transliterate(trimmed),
                )
                BentiScript.LATIN, BentiScript.UNDETERMINED -> OccasionLayers(english = trimmed)
            }
        }
    }
}
