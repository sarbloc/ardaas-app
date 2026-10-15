package io.sarbloc.ardaas.occasion

import io.sarbloc.ardaas.composer.ArdaasComposer
import io.sarbloc.ardaas.content.ArdaasContent
import io.sarbloc.ardaas.content.LayerKind
import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines

// Port of Ardaas/Models/OccasionDraft.swift (OccasionChoice.willNotAppear is
// in OccasionChoice.kt).

/**
 * Which row the occasion picker is on.
 *
 * Catalog entries are identified by id; the two synthetic rows are [Unset]
 * (the default, shown as "None" — the slot keeps its authored dots) and
 * [Custom] ("Other…", whose words live beside it in
 * [OccasionDraft.customText]).
 *
 * Deliberately *not* [OccasionChoice]: a picker can sit on "Other…" with
 * nothing typed yet, which is a real UI state but not a choice worth storing.
 * [OccasionDraft.choice] is where the two meet.
 */
sealed interface OccasionSelection {
    data object Unset : OccasionSelection

    data class Catalog(val id: String) : OccasionSelection

    data object Custom : OccasionSelection
}

/**
 * The occasion slot being edited, as pure, testable data: which row is
 * selected, the words typed under "Other…", and the rules that turn the two
 * into the [OccasionChoice] that `SavedArdaas` persists.
 *
 * Immutable — the Kotlin form of iOS's value-type struct, so it can sit in
 * Compose state and every edit is a new value. Change the row with
 * [withSelection], which applies the clearing rule iOS runs in `didSet`;
 * change the words with `copy(customText = …)`. The primary constructor sets
 * both fields as given, like iOS's memberwise initialiser.
 */
data class OccasionDraft(
    /** The selected row. */
    val selection: OccasionSelection = OccasionSelection.Unset,
    /**
     * The user's own words for the slot. Only meaningful while [selection] is
     * [OccasionSelection.Custom]; cleared by [withSelection] otherwise.
     */
    val customText: String = "",
) {
    /** True when the free-text field should be on screen. */
    val isCustom: Boolean get() = selection == OccasionSelection.Custom

    /**
     * What Save persists.
     *
     * "Other…" with nothing but whitespace typed is [OccasionChoice.Unset],
     * not an empty custom choice: an abandoned draft must leave the canonical
     * dots exactly as authored rather than blanking the sentence.
     */
    val choice: OccasionChoice
        get() = when (selection) {
            OccasionSelection.Unset -> OccasionChoice.Unset
            is OccasionSelection.Catalog -> OccasionChoice.Catalog(selection.id)
            OccasionSelection.Custom -> {
                val trimmed = customText.trimmingWhitespaceAndNewlines()
                if (trimmed.isEmpty()) OccasionChoice.Unset else OccasionChoice.Custom(trimmed)
            }
        }

    /**
     * This draft on another row.
     *
     * Moving off "Other…" clears [customText], so an abandoned draft can never
     * be saved beside a catalog choice, and can never reappear if the user
     * comes back to "Other…" later. This mirrors
     * [OccasionChoice.storedCustomText], which blanks the same field on the
     * way to storage — doing it here as well means what the user sees and
     * what would be saved never disagree. Re-selecting "Other…" keeps the
     * words being typed.
     */
    fun withSelection(selection: OccasionSelection): OccasionDraft = OccasionDraft(
        selection = selection,
        customText = if (selection == OccasionSelection.Custom) customText else "",
    )

    /**
     * The slot sentence as it will read with this choice in it, for the
     * variant the user has selected. Null when the variant declares no
     * occasion slot (nothing would ever be substituted, so there is nothing to
     * show).
     */
    fun preview(content: ArdaasContent, catalog: OccasionCatalog): OccasionPreview? =
        OccasionPreview.of(content, choice.layers(catalog))

    /** The #72 warning for the row currently selected — see [OccasionChoice.willNotAppear]. */
    fun willNotAppear(content: ArdaasContent, catalog: OccasionCatalog): Boolean =
        choice.willNotAppear(content, catalog)

    companion object {
        /**
         * The picker's state for a choice already saved — how a picker opens
         * on a record's current occasion.
         *
         * The inverse of [choice], except for the one state [choice]
         * collapses: "Other…" with nothing typed stores as
         * [OccasionChoice.Unset] and so reopens as "None". Nothing is lost —
         * an abandoned draft was never saved.
         *
         * @param catalog when given, a stored id the catalog no longer carries
         *   normalises to "None". A record can outlive the entry it points at
         *   if a later bundle retires one; the picker has no row for a retired
         *   id, so opening with it selected would tick nothing while the text
         *   renders the canonical dots. Omitting the catalog keeps the stored
         *   id untouched, for callers that cannot resolve it and must not
         *   silently discard it.
         */
        fun from(choice: OccasionChoice, catalog: OccasionCatalog? = null): OccasionDraft = when (choice) {
            OccasionChoice.Unset -> OccasionDraft()
            is OccasionChoice.Catalog ->
                if (catalog != null && catalog.occasion(choice.id) == null) {
                    OccasionDraft()
                } else {
                    OccasionDraft(selection = OccasionSelection.Catalog(choice.id))
                }
            is OccasionChoice.Custom -> OccasionDraft(selection = OccasionSelection.Custom, customText = choice.text)
        }
    }
}

/**
 * The Ardaas's slot sentence, quoted back to the user while they choose — the
 * point at which the "….." stops being a typo and becomes a sentence they can
 * read.
 */
data class OccasionPreview(
    /** Which layer is being quoted, so the caller can style it like the Reader does. */
    val layer: LayerKind,
    /** The slot segment's text for that layer, with the choice spliced in. */
    val text: String,
    /**
     * Whether the choice actually landed: false means [text] is still the
     * sentence exactly as authored, dots and all — either because nothing is
     * chosen, or because the choice has no words for any layer this variant
     * carries (see [OccasionChoice.willNotAppear]).
     */
    val isFilled: Boolean,
) {
    companion object {
        /**
         * Builds the preview by running the *same* substitution the Reader
         * gets ([ArdaasComposer]), so what Compose promises and what the
         * Reader renders cannot drift apart. Null when the variant has no
         * usable occasion slot.
         *
         * The layer shown is the first one — Gurmukhi, then transliteration,
         * then English — that the substitution actually changed. Preferring a
         * changed layer is what makes the preview useful for free text, which
         * fills only one layer. When nothing changed anywhere, the authored
         * Gurmukhi is shown, which is the whole point for "None" — it is where
         * the reader sees what the dots are.
         */
        fun of(content: ArdaasContent, occasion: OccasionLayers?): OccasionPreview? {
            val authored = ArdaasComposer.occasionSlotSegment(content, occasion = null) ?: return null
            val filled = ArdaasComposer.occasionSlotSegment(content, occasion) ?: authored
            for (layer in LayerKind.entries) {
                val text = filled.text(layer) ?: continue
                if (text != authored.text(layer)) return OccasionPreview(layer, text, isFilled = true)
            }
            return OccasionPreview(LayerKind.GURMUKHI, authored.gurmukhi, isFilled = false)
        }
    }
}
