package io.sarbloc.ardaas.ui.compose

import io.sarbloc.ardaas.composer.BentiDraft
import io.sarbloc.ardaas.content.ArdaasLibrary
import io.sarbloc.ardaas.occasion.OccasionDraft
import io.sarbloc.ardaas.persistence.SavedArdaas
import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Everything the Compose screen is building, as pure data: the label, the
 * benti, the variant and the occasion. Holds iOS `ComposeView`'s Save rule
 * ([canSave]) and its `save()` mapping ([toSavedArdaas]) so both are
 * unit-tested rather than living in the view.
 */
data class ComposeDraft(
    val label: String = "",
    val benti: BentiDraft = BentiDraft(),
    /** The pinned default until the user picks another, as on iOS. */
    val variantId: String = ArdaasLibrary.DEFAULT_VARIANT_ID,
    val occasion: OccasionDraft = OccasionDraft(),
) {
    /** The label as it is saved: iOS trims whitespace and newlines from both ends. */
    val trimmedLabel: String get() = label.trimmingWhitespaceAndNewlines()

    /**
     * Something worth saving: a label and a benti with text in at least one
     * layer. iOS's `canSave` minus the clause that waits on a translation,
     * which lives in [TranslationStatus.canSave] with the rest of the
     * translation state. The occasion never gates Save: leaving the slot
     * unfilled is normal.
     */
    val canSave: Boolean get() = trimmedLabel.isNotEmpty() && !benti.isEmpty

    /**
     * The record Save inserts, built exactly as iOS's `save()` builds it —
     * every layer from [BentiDraft.layers], the occasion through
     * [OccasionDraft.choice] (so "Other…" with nothing typed saves as
     * nothing chosen). Null when [canSave] is false.
     */
    fun toSavedArdaas(createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)): SavedArdaas? {
        if (!canSave) return null
        val layers = benti.layers
        return SavedArdaas(
            label = trimmedLabel,
            bentiText = layers.english,
            occasion = occasion.choice,
            createdAt = createdAt,
            variantId = variantId,
            bentiGurmukhi = layers.gurmukhi,
            bentiTransliteration = layers.transliteration,
        )
    }
}
