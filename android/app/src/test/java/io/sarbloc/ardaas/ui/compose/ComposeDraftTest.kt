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

/** The Compose screen's Save rule and its draft → [SavedArdaas] mapping (iOS `canSave` / `save()`). */
class ComposeDraftTest {

    private val createdAt = Instant.parse("2026-10-15T09:26:53.589Z")

    // region Save enabled

    @Test
    fun anEmptyDraftCannotSave() {
        assertFalse(ComposeDraft().canSave)
        assertNull(ComposeDraft().toSavedArdaas())
    }

    @Test
    fun aLabelAloneCannotSave() {
        assertFalse(ComposeDraft(label = "Family").canSave)
    }

    @Test
    fun aBentiAloneCannotSave() {
        assertFalse(ComposeDraft(benti = BentiDraft(typed = "Bless us.")).canSave)
    }

    @Test
    fun aLabelAndABentiCanSave() {
        assertTrue(ComposeDraft(label = "Family", benti = BentiDraft(typed = "Bless us.")).canSave)
    }

    /** Whitespace is nothing, in the label and in every benti editor. */
    @Test
    fun whitespaceDoesNotCount() {
        val benti = BentiDraft(typed = "Bless us.")
        assertFalse(ComposeDraft(label = "  \n\t", benti = benti).canSave)
        // iOS's whitespace set: a no-break space is whitespace too.
        assertFalse(ComposeDraft(label = " ", benti = benti).canSave)
        assertFalse(ComposeDraft(label = "Family", benti = BentiDraft(typed = "  ", gurmukhi = "\n")).canSave)
    }

    /** A Gurmukhi-only benti, from either editor, is worth saving. */
    @Test
    fun gurmukhiAloneCanSave() {
        assertTrue(ComposeDraft(label = "Family", benti = BentiDraft(gurmukhi = "ਅਰਦਾਸ")).canSave)
        assertTrue(ComposeDraft(label = "Family", benti = BentiDraft(typed = "ਅਰਦਾਸ")).canSave)
    }

    /** Leaving the slot unfilled is normal, so the occasion never gates Save. */
    @Test
    fun theOccasionNeverGatesSave() {
        val base = ComposeDraft(label = "Family", benti = BentiDraft(typed = "Bless us."))
        assertTrue(base.canSave)
        assertTrue(base.copy(occasion = OccasionDraft(selection = OccasionSelection.Custom)).canSave)
    }

    // endregion

    // region Mapping

    @Test
    fun theLabelIsSavedTrimmed() {
        val saved = ComposeDraft(label = "  Family ardaas \n", benti = BentiDraft(typed = "Bless us.")).toSavedArdaas()
        assertEquals("Family ardaas", saved?.label)
    }

    /** Every field the screen collects lands in its own column. */
    @Test
    fun everyFieldIsMapped() {
        val draft = ComposeDraft(
            label = " Akhand Paath ",
            benti = BentiDraft(typed = " Bless the sangat. ", gurmukhi = " ਸਿੰਘ ਅਰਦਾਸ "),
            variantId = "buddha-dal",
            occasion = OccasionDraft(selection = OccasionSelection.Custom, customText = "  ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ "),
        )
        val saved = draft.toSavedArdaas(createdAt)
        assertEquals(
            SavedArdaas(
                label = "Akhand Paath",
                bentiText = "Bless the sangat.",
                createdAt = createdAt,
                variantId = "buddha-dal",
                bentiGurmukhi = "ਸਿੰਘ ਅਰਦਾਸ",
                bentiTransliteration = "Singh Ardaas",
                occasionId = OccasionChoice.CUSTOM_ID,
                occasionCustom = "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ",
            ),
            saved,
        )
    }

    @Test
    fun theDefaultVariantIsThePinnedOne() {
        val saved = ComposeDraft(label = "Family", benti = BentiDraft(typed = "Bless us.")).toSavedArdaas()
        assertEquals(ArdaasLibrary.DEFAULT_VARIANT_ID, saved?.variantId)
    }

    @Test
    fun aCatalogOccasionStoresItsIdOnly() {
        val saved = ComposeDraft(
            label = "Family",
            benti = BentiDraft(typed = "Bless us."),
            occasion = OccasionDraft(selection = OccasionSelection.Catalog("japji-sahib")),
        ).toSavedArdaas()
        assertEquals("japji-sahib", saved?.occasionId)
        assertEquals("", saved?.occasionCustom)
    }

    /** "Other…" with nothing typed is nothing chosen, so the dots stay as authored. */
    @Test
    fun anAbandonedOtherSavesAsNothingChosen() {
        val saved = ComposeDraft(
            label = "Family",
            benti = BentiDraft(typed = "Bless us."),
            occasion = OccasionDraft(selection = OccasionSelection.Custom, customText = "   "),
        ).toSavedArdaas()
        assertEquals("", saved?.occasionId)
        assertEquals("", saved?.occasionCustom)
    }

    /** Gurmukhi typed in the benti editor is the Gurmukhi layer; English stays empty. */
    @Test
    fun gurmukhiInTheBentiEditorSavesAsGurmukhi() {
        val saved = ComposeDraft(label = "Family", benti = BentiDraft(typed = "ਸਿੰਘ ਅਰਦਾਸ", gurmukhi = "ਹੋਰ")).toSavedArdaas()
        assertEquals("", saved?.bentiText)
        assertEquals("ਸਿੰਘ ਅਰਦਾਸ", saved?.bentiGurmukhi)
        assertEquals("Singh Ardaas", saved?.bentiTransliteration)
    }

    @Test
    fun aNewRecordHasNoIdYet() {
        assertEquals(0L, ComposeDraft(label = "Family", benti = BentiDraft(typed = "Bless us.")).toSavedArdaas()?.id)
    }

    // endregion
}
