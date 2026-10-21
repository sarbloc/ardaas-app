package io.sarbloc.ardaas.ui.compose

import io.sarbloc.ardaas.occasion.OccasionCatalog
import io.sarbloc.ardaas.persistence.SavedArdaas

/**
 * What the Compose screen is for: a new record, or editing a saved one
 * (#154). Pure, so which draft the screen opens on, which record Save writes
 * and whether leaving needs a confirmation are all unit-tested.
 */
sealed interface ComposeMode {
    /** The draft the screen opens on. */
    val initialDraft: ComposeDraft

    /** The record Save writes for [draft]; null when it cannot be saved. */
    fun record(draft: ComposeDraft): SavedArdaas?

    /**
     * Leaving with [draft] on screen would lose something the user did here,
     * so Cancel and Back ask first.
     */
    fun hasUnsavedChanges(draft: ComposeDraft): Boolean

    /** A new record, inserted on Save. Leaving never asks, as on iOS. */
    data object Create : ComposeMode {
        override val initialDraft: ComposeDraft = ComposeDraft()

        override fun record(draft: ComposeDraft): SavedArdaas? = draft.toSavedArdaas()

        override fun hasUnsavedChanges(draft: ComposeDraft): Boolean = false
    }

    /**
     * [original] reopened for editing: the screen opens on it, and Save
     * rewrites that row — same id, same `createdAt`.
     *
     * @param catalog the occasion catalog the draft resolves the stored
     *   occasion against — see [ComposeDraft.from].
     */
    data class Edit(val original: SavedArdaas, val catalog: OccasionCatalog?) : ComposeMode {
        override val initialDraft: ComposeDraft = ComposeDraft.from(original, catalog)

        override fun record(draft: ComposeDraft): SavedArdaas? = draft.updating(original)

        /**
         * Anything differs from the draft the screen opened on. Compared as
         * drafts rather than as the records they save, so a record whose
         * stored transliteration the transliterator would now write
         * differently does not count as changed just by being opened.
         */
        override fun hasUnsavedChanges(draft: ComposeDraft): Boolean = draft != initialDraft
    }
}
