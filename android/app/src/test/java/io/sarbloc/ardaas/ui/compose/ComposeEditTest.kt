package io.sarbloc.ardaas.ui.compose

import io.sarbloc.ardaas.composer.BentiDraft
import io.sarbloc.ardaas.content.ArdaasLibrary
import io.sarbloc.ardaas.occasion.OccasionChoice
import io.sarbloc.ardaas.occasion.OccasionDraft
import io.sarbloc.ardaas.occasion.OccasionSelection
import io.sarbloc.ardaas.persistence.SavedArdaas
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #154: reopening a saved record in Compose. The record → draft mapping for
 * every benti shape Compose can save, the round trip back, staleness on
 * open, and the in-place update.
 */
class ComposeEditTest {

    private val createdAt = Instant.parse("2026-10-15T09:26:53.589Z")

    /** A record exactly as Compose saves [benti], with an id as if it had been inserted. */
    private fun saved(benti: BentiDraft, label: String = "Family", id: Long = 7): SavedArdaas =
        requireNotNull(ComposeDraft(label = label, benti = benti, variantId = "buddha-dal").toSavedArdaas(createdAt))
            .copy(id = id)

    private fun reopened(record: SavedArdaas): BentiDraft = ComposeDraft.from(record, catalog = null).benti

    // region Every benti shape

    private val englishOnly = BentiDraft(typed = "Bless us.")
    private val translated = BentiDraft(typed = "Bless the sangat.").applyTranslation("ਸਿੰਘ ਅਰਦਾਸ", "Bless the sangat.")
    private val typedInGurmukhi = BentiDraft(typed = "ਸਿੰਘ ਅਰਦਾਸ")
    private val gurmukhiRemoved = translated.clearTranslation()
    private val gurmukhiEmptiedButOpen = translated.copy(gurmukhi = "")
    private val englishDeletedAfterTranslating = translated.copy(typed = "")
    private val draftEditedOutOfGurmukhi = translated.copy(typed = "", gurmukhi = "Waheguru")

    private val everyShape = listOf(
        englishOnly,
        translated,
        typedInGurmukhi,
        gurmukhiRemoved,
        gurmukhiEmptiedButOpen,
        englishDeletedAfterTranslating,
        draftEditedOutOfGurmukhi,
    )

    @Test
    fun englishOnlyReopensAsTypedTextWithNoDraft() {
        assertEquals(BentiDraft(typed = "Bless us.", isDraftOpen = false), reopened(saved(englishOnly)))
    }

    /** The saved pair counts as one translation: English typed, Gurmukhi in the open draft. */
    @Test
    fun englishAndGurmukhiReopenAsATranslation() {
        assertEquals(
            BentiDraft(
                typed = "Bless the sangat.",
                gurmukhi = "ਸਿੰਘ ਅਰਦਾਸ",
                translatedFrom = "Bless the sangat.",
                isDraftOpen = true,
            ),
            reopened(saved(translated)),
        )
    }

    /** Gurmukhi typed with the Punjabi keyboard goes back into the benti editor, where it was typed. */
    @Test
    fun gurmukhiTypedDirectlyReopensInTheBentiEditor() {
        val record = saved(typedInGurmukhi)
        assertEquals("", record.bentiText)
        assertEquals(BentiDraft(typed = "ਸਿੰਘ ਅਰਦਾਸ", isDraftOpen = false), reopened(record))
    }

    /** Remove Gurmukhi saved English only, so it reopens as English only — section closed. */
    @Test
    fun removedGurmukhiReopensAsEnglishOnly() {
        assertEquals(BentiDraft(typed = "Bless the sangat.", isDraftOpen = false), reopened(saved(gurmukhiRemoved)))
        assertEquals(BentiDraft(typed = "Bless the sangat.", isDraftOpen = false), reopened(saved(gurmukhiEmptiedButOpen)))
    }

    /** A Gurmukhi-only benti from a translation reopens in the benti editor; it saves the same. */
    @Test
    fun aTranslationWithItsEnglishDeletedReopensInTheBentiEditor() {
        assertEquals(BentiDraft(typed = "ਸਿੰਘ ਅਰਦਾਸ"), reopened(saved(englishDeletedAfterTranslating)))
    }

    /**
     * Gurmukhi-layer text the detector reads as English cannot go in the
     * benti editor (it would save as English), so it stays in the draft.
     */
    @Test
    fun aGurmukhiLayerInAnotherScriptReopensInTheDraft() {
        val record = saved(draftEditedOutOfGurmukhi)
        assertEquals("", record.bentiText)
        assertEquals("Waheguru", record.bentiGurmukhi)
        assertEquals(BentiDraft(gurmukhi = "Waheguru", isDraftOpen = true), reopened(record))
        assertTrue(reopened(record).showsTranslationDraft)
    }

    /** isDraftOpen follows whether there is saved Gurmukhi (outside the benti editor). */
    @Test
    fun theDraftSectionIsOpenOnlyForSavedTranslatedGurmukhi() {
        assertTrue(reopened(saved(translated)).showsTranslationDraft)
        assertFalse(reopened(saved(englishOnly)).showsTranslationDraft)
        assertFalse(reopened(saved(gurmukhiRemoved)).showsTranslationDraft)
        assertFalse(reopened(saved(typedInGurmukhi)).showsTranslationDraft)
    }

    // endregion

    // region Round trip

    /** record → draft → record reproduces every field, for every shape. */
    @Test
    fun everyShapeRoundTrips() {
        for (shape in everyShape) {
            val record = saved(shape)
            val mode = ComposeMode.Edit(record, catalog = null)
            assertEquals(shape.toString(), record, mode.record(mode.initialDraft))
        }
    }

    @Test
    fun theOtherFieldsRoundTrip() {
        val record = saved(translated, label = "Akhand Paath").copy(
            occasionId = OccasionChoice.CUSTOM_ID,
            occasionCustom = "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ",
        )
        val draft = ComposeDraft.from(record, catalog = null)
        assertEquals("Akhand Paath", draft.label)
        assertEquals("buddha-dal", draft.variantId)
        assertEquals(OccasionDraft(selection = OccasionSelection.Custom, customText = "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ"), draft.occasion)
        assertEquals(record, draft.updating(record))

        val catalogOccasion = record.copy(occasionId = "japji-sahib", occasionCustom = "")
        assertEquals(catalogOccasion, ComposeDraft.from(catalogOccasion, catalog = null).updating(catalogOccasion))
    }

    /** A variant a later bundle retired is kept as stored; the Reader heals it, not Compose. */
    @Test
    fun aRetiredVariantIsKept() {
        val record = saved(englishOnly).copy(variantId = "retired")
        assertEquals("retired", ComposeDraft.from(record, catalog = null).variantId)
    }

    // endregion

    // region Staleness

    @Test
    fun aReopenedTranslationIsNotStale() {
        for (shape in everyShape) {
            assertFalse(shape.toString(), reopened(saved(shape)).isStale)
        }
    }

    @Test
    fun changingTheEnglishMakesItStale() {
        val benti = reopened(saved(translated))
        assertTrue(benti.copy(typed = "Bless the whole sangat.").isStale)
        // Back to what was translated: not stale again. Padding is not a change.
        assertFalse(benti.copy(typed = "Bless the sangat. ").isStale)
    }

    @Test
    fun editingTheGurmukhiAloneIsNotStale() {
        assertFalse(reopened(saved(translated)).copy(gurmukhi = "ਸਿੰਘ").isStale)
    }

    // endregion

    // region Update

    @Test
    fun anUpdateKeepsTheIdAndCreatedAt() {
        val record = saved(translated, id = 42)
        val mode = ComposeMode.Edit(record, catalog = null)
        val edited = mode.initialDraft.copy(
            label = " Morning ",
            benti = mode.initialDraft.benti.copy(typed = "Bless us all.").clearTranslation(),
            variantId = ArdaasLibrary.DEFAULT_VARIANT_ID,
            occasion = OccasionDraft(selection = OccasionSelection.Catalog("japji-sahib")),
        )
        assertEquals(
            SavedArdaas(
                label = "Morning",
                bentiText = "Bless us all.",
                createdAt = createdAt,
                variantId = ArdaasLibrary.DEFAULT_VARIANT_ID,
                occasionId = "japji-sahib",
                id = 42,
            ),
            mode.record(edited),
        )
    }

    /** The same validation as create: nothing worth saving is no record at all. */
    @Test
    fun anUpdateThatCannotSaveIsNoRecord() {
        val record = saved(englishOnly)
        assertNull(ComposeDraft.from(record, catalog = null).copy(label = " ").updating(record))
        assertNull(ComposeDraft.from(record, catalog = null).copy(benti = BentiDraft()).updating(record))
    }

    @Test
    fun createStillInsertsANewRecord() {
        val draft = ComposeDraft(label = "Family", benti = englishOnly)
        assertEquals(0L, ComposeMode.Create.record(draft)?.id)
        assertEquals(ComposeDraft(), ComposeMode.Create.initialDraft)
    }

    /**
     * The transliteration is regenerated, never read back — an edit saves
     * what the transliterator writes now.
     */
    @Test
    fun anUpdateRegeneratesTheTransliteration() {
        val record = saved(translated).copy(bentiTransliteration = "an older spelling")
        assertEquals("Singh Ardaas", ComposeDraft.from(record, catalog = null).updating(record)?.bentiTransliteration)
    }

    // endregion

    // region Unsaved changes

    @Test
    fun openingAnEditChangesNothing() {
        for (shape in everyShape) {
            val mode = ComposeMode.Edit(saved(shape), catalog = null)
            assertFalse(shape.toString(), mode.hasUnsavedChanges(mode.initialDraft))
        }
        // Not even a transliteration the transliterator would now write differently.
        val outdated = ComposeMode.Edit(saved(translated).copy(bentiTransliteration = "older"), catalog = null)
        assertFalse(outdated.hasUnsavedChanges(outdated.initialDraft))
    }

    @Test
    fun anyEditIsAnUnsavedChange() {
        val mode = ComposeMode.Edit(saved(translated), catalog = null)
        val initial = mode.initialDraft
        assertTrue(mode.hasUnsavedChanges(initial.copy(label = "Other")))
        assertTrue(mode.hasUnsavedChanges(initial.copy(variantId = ArdaasLibrary.DEFAULT_VARIANT_ID)))
        assertTrue(mode.hasUnsavedChanges(initial.copy(benti = initial.benti.copy(typed = "Bless us."))))
        assertTrue(mode.hasUnsavedChanges(initial.copy(benti = initial.benti.clearTranslation())))
        assertTrue(mode.hasUnsavedChanges(initial.copy(occasion = OccasionDraft(selection = OccasionSelection.Catalog("japji-sahib")))))
        // Changed and changed back is no change.
        assertFalse(mode.hasUnsavedChanges(initial.copy(label = initial.label)))
    }

    @Test
    fun creatingNeverAsks() {
        assertFalse(ComposeMode.Create.hasUnsavedChanges(ComposeDraft(label = "Family", benti = englishOnly)))
    }

    // endregion
}
