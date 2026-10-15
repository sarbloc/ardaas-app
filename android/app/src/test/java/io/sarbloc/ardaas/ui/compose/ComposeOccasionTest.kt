package io.sarbloc.ardaas.ui.compose

import androidx.compose.runtime.saveable.SaverScope
import io.sarbloc.ardaas.content.BundledAssets
import io.sarbloc.ardaas.occasion.OccasionDraft
import io.sarbloc.ardaas.occasion.OccasionSelection
import org.junit.Assert.assertEquals
import org.junit.Test

/** The occasion half of Compose: the row's summary, and the draft surviving saved state. */
class ComposeOccasionTest {

    private val catalog = BundledAssets.occasionCatalog()

    // region Summary

    @Test
    fun nothingChosenReadsNone() {
        assertEquals(OccasionSummary.None, OccasionSummary.of(OccasionDraft(), catalog))
    }

    @Test
    fun aCatalogEntryReadsAsItsGurmukhiAndEnglish() {
        val entry = catalog.occasions.first()
        val summary = OccasionSummary.of(OccasionDraft(selection = OccasionSelection.Catalog(entry.id)), catalog)
        assertEquals(OccasionSummary.Entry(entry.gurmukhi, entry.english), summary)
    }

    /** A retired id reads as None — which is also what the text renders. */
    @Test
    fun anUnknownCatalogIdReadsNone() {
        val summary = OccasionSummary.of(OccasionDraft(selection = OccasionSelection.Catalog("retired")), catalog)
        assertEquals(OccasionSummary.None, summary)
    }

    @Test
    fun otherReadsOther() {
        val summary = OccasionSummary.of(OccasionDraft(selection = OccasionSelection.Custom, customText = "x"), catalog)
        assertEquals(OccasionSummary.Other, summary)
    }

    // endregion

    // region Saver

    private fun roundTrip(draft: OccasionDraft): OccasionDraft {
        val saved = with(OccasionDraftSaver) { SaverScope { true }.save(draft) }
        return OccasionDraftSaver.restore(requireNotNull(saved))!!
    }

    @Test
    fun everySelectionSurvivesSavedState() {
        val drafts = listOf(
            OccasionDraft(),
            OccasionDraft(selection = OccasionSelection.Catalog("japji-sahib")),
            OccasionDraft(selection = OccasionSelection.Custom, customText = "ਜਨਮ ਦਿਨ"),
            // "Other…" with nothing typed yet is a real UI state, kept as-is.
            OccasionDraft(selection = OccasionSelection.Custom),
        )
        for (draft in drafts) assertEquals(draft, roundTrip(draft))
    }

    // endregion
}
