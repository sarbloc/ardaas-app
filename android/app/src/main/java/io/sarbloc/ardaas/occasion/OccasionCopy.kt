package io.sarbloc.ardaas.occasion

// Port of `OccasionCopy` in Ardaas/Views/OccasionSection.swift, verbatim.

/**
 * The user-facing copy for the "….." slot, in one place so Compose, the
 * occasion picker and the Reader's own slot warning say the same thing in the
 * same words.
 */
object OccasionCopy {
    /** What the gap is and that leaving it is normal — the teaching text. */
    const val EXPLANATION =
        "Partway through the Ardaas there's a gap, written as “…..”. It's where you name what the prayer " +
            "is for — the paath (scripture reading) you've just finished, an occasion like a birthday, a new " +
            "home, or someone's recovery, or both. Leaving it unfilled is completely normal; the dots stay as they are."

    /** How free text is placed: one script, no translation. */
    const val FREE_TEXT =
        "Write it in Gurmukhi and it goes into the Gurmukhi text; write it in English and it goes into the " +
            "English. It isn't translated for you."

    /**
     * The #72 gap said plainly: this choice lands in no layer this Ardaas
     * carries. Reachable when choosing, and again when the Reader's variant
     * picker moves free text onto an Ardaas that cannot show it.
     */
    const val WILL_NOT_APPEAR =
        "This Ardaas has no English line, so an occasion written in English has nowhere to go — the “…..” " +
            "stays as it is. Write it in Gurmukhi instead, or pick one from the list."

    // The picker's own labels, verbatim from `OccasionSection` /
    // `OccasionEditorSheet`, so Compose and the Reader present one picker.

    /**
     * The picker's title — iOS's section header, sheet title and Reader
     * button label (#158): the slot takes a paath and an occasion together.
     */
    const val TITLE = "Paath & Occasion"

    /** The row that leaves the slot as authored. */
    const val NONE = "None"

    /** Header over the [OccasionCategory.PAATH] entries. */
    const val PAATH_HEADER = "After a paath"

    /** Header over the [OccasionCategory.OCCASION] entries. */
    const val OCCASIONS_HEADER = "Occasions"

    /** The free-text row. */
    const val OTHER = "Other…"

    /** The free-text field's placeholder. */
    const val CUSTOM_PLACEHOLDER = "e.g. my daughter's first birthday"

    /** The free-text field's name — iOS's accessibility label, shown as the field label here. */
    const val CUSTOM_LABEL = "Your own occasion"

    /** Over the preview once the choice has landed in the sentence. */
    const val PREVIEW_FILLED = "How it will read"

    /** Over the preview while the slot still reads as authored. */
    const val PREVIEW_UNFILLED = "Where it goes"
}
