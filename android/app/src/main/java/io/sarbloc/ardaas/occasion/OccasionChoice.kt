package io.sarbloc.ardaas.occasion

import io.sarbloc.ardaas.content.ArdaasContent
import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines

// Port of the OccasionChoice enum in Ardaas/Models/OccasionChoice.swift, plus
// its willNotAppear extension from OccasionDraft.swift. OccasionLayers lives
// in OccasionLayers.kt.

/**
 * What fills the "….." slot for a saved Ardaas: nothing, an entry from the
 * bundled catalog, or the user's own words.
 *
 * ## Why two stored fields rather than one
 *
 * `SavedArdaas` persists this as `occasionId` + `occasionCustom`:
 *
 * * A catalog choice stores only its **id**. The three layers stay in the
 *   bundle, so a corrected transliteration or a re-proof-read Gurmukhi in a
 *   later release reaches every existing save for free — and the picker can
 *   show the entry as selected by comparing ids.
 * * Free text has no id and cannot come from the bundle, so it is stored
 *   **verbatim**. It is kept in its own field so an id is always either empty
 *   or a real catalog id: storing free text in `occasionId` would make a user
 *   who types "birthday" indistinguishable from the catalog's `birthday`
 *   entry, and would break the picker's selected state.
 *
 * The two are joined by the [CUSTOM_ID] sentinel, which [OccasionCatalog]
 * forbids any bundled entry from using.
 *
 * Equality is on the raw associated value, as on iOS: `Catalog(" a ")` and
 * `Catalog("a")` differ, and only [storedId] / [storedCustomText] trim.
 */
sealed interface OccasionChoice {
    data object Unset : OccasionChoice

    data class Catalog(val id: String) : OccasionChoice

    data class Custom(val text: String) : OccasionChoice

    /** The value for `SavedArdaas.occasionId`. */
    val storedId: String
        get() = when (this) {
            Unset -> ""
            is Catalog -> id.trimmingWhitespaceAndNewlines()
            is Custom -> CUSTOM_ID
        }

    /**
     * The value for `SavedArdaas.occasionCustom`. Cleared for every choice
     * that is not free text, so a stale draft can't resurface later.
     */
    val storedCustomText: String
        get() = when (this) {
            is Custom -> text.trimmingWhitespaceAndNewlines()
            Unset, is Catalog -> ""
        }

    /**
     * The layers to splice into the slot, or null when nothing should be
     * substituted and the placeholder stays exactly as authored.
     *
     * An **unknown catalog id** resolves to null: an entry retired from a
     * later bundle leaves the reader with the canonical dots rather than a
     * crash or a hole in the sentence.
     */
    fun layers(catalog: OccasionCatalog): OccasionLayers? = when (this) {
        Unset -> null
        is Catalog -> catalog.occasion(id)?.let {
            OccasionLayers(gurmukhi = it.gurmukhi, transliteration = it.transliteration, english = it.english)
        }
        is Custom -> OccasionLayers.fromFreeText(text)
    }

    /**
     * A choice has been made but it lands in no layer this variant carries,
     * so the reader would still see the dots.
     *
     * It takes a variant with no English layer: free text that is not in
     * Gurmukhi script becomes the English layer and nothing else (see
     * [OccasionLayers.fromFreeText]), so it has nowhere to appear. That is the
     * gap #72 describes; the pickers say so inline, and the Reader repeats it
     * at the slot itself — where switching variants can strand free text that
     * was fine on the variant it was written for. Since #60 no bundled
     * combination reaches it; kept because it is a rule about what a variant
     * carries.
     *
     * Phrased as "did the substitution change anything" rather than as a
     * hard-coded variant/script pair, so it stays true if either the bundled
     * texts or the free-text rules change.
     */
    fun willNotAppear(content: ArdaasContent, catalog: OccasionCatalog): Boolean {
        // A retired catalog id also resolves to no layers, but that is a
        // different fault with a different remedy — the record simply falls
        // back to the dots, and telling the reader "this Ardaas has no English
        // line" would misdiagnose it. Only a choice that genuinely resolves to
        // words can fail to land.
        if (this == Unset) return false
        val layers = layers(catalog) ?: return false
        val line = OccasionPreview.of(content, layers) ?: return false
        return !line.isFilled
    }

    companion object {
        /**
         * The reserved `occasionId` meaning "the text in `occasionCustom`".
         * Reserved rather than free-form so the stored id stays a closed set.
         */
        const val CUSTOM_ID = "custom"

        /**
         * Rebuilds a choice from the two persisted fields. Total by
         * construction — every stored pair, including ones written by a future
         * build or corrupted on disk, maps to some choice, and an id that no
         * longer exists in the catalog simply resolves to no substitution.
         */
        fun fromStored(occasionId: String, occasionCustom: String): OccasionChoice {
            val id = occasionId.trimmingWhitespaceAndNewlines()
            val custom = occasionCustom.trimmingWhitespaceAndNewlines()
            return when (id) {
                "" -> Unset
                CUSTOM_ID -> if (custom.isEmpty()) Unset else Custom(custom)
                else -> Catalog(id)
            }
        }
    }
}
