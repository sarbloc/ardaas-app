package io.sarbloc.ardaas.ui.compose

import androidx.compose.runtime.saveable.SaverScope
import io.sarbloc.ardaas.content.BundledAssets
import io.sarbloc.ardaas.occasion.OccasionDraft
import io.sarbloc.ardaas.occasion.OccasionSelection
import org.junit.Assert.assertEquals
import org.junit.Test

/** The slot half of Compose: the rows' summaries, and the draft surviving saved state. */
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

    @Test
    fun thePaathRowReadsNoneOrThePaath() {
        assertEquals(OccasionSummary.None, OccasionSummary.paath(OccasionDraft(), catalog))
        val sukhmani = requireNotNull(catalog.occasion("sukhmani-sahib"))
        assertEquals(
            OccasionSummary.Entry(sukhmani.gurmukhi, sukhmani.english),
            OccasionSummary.paath(OccasionDraft(paathId = "sukhmani-sahib"), catalog),
        )
    }

    /** Not a paath, or retired: None — which is also what the text renders (#158). */
    @Test
    fun aPaathRowWithAnIdThatIsNoPaathReadsNone() {
        for (id in listOf("birthday", "retired")) {
            assertEquals(OccasionSummary.None, OccasionSummary.paath(OccasionDraft(paathId = id), catalog))
        }
    }

    @Test
    fun theTwoRowsReadIndependently() {
        val draft = OccasionDraft(selection = OccasionSelection.Catalog("birthday"), paathId = "sukhmani-sahib")
        assertEquals("ਜਨਮ ਦਿਹਾੜੇ", (OccasionSummary.of(draft, catalog) as OccasionSummary.Entry).gurmukhi)
        assertEquals("ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ", (OccasionSummary.paath(draft, catalog) as OccasionSummary.Entry).gurmukhi)
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
            // #158: the paath beside each occasion shape.
            OccasionDraft(paathId = "sukhmani-sahib"),
            OccasionDraft(selection = OccasionSelection.Catalog("birthday"), paathId = "sukhmani-sahib"),
            OccasionDraft(selection = OccasionSelection.Custom, customText = "x", paathId = "japji-sahib"),
        )
        for (draft in drafts) assertEquals(draft, roundTrip(draft))
    }

    @Test
    fun aSaveFromBeforeThePaathRestoresWithNone() {
        val restored = OccasionDraftSaver.restore(listOf("catalog", "birthday", ""))
        assertEquals(OccasionDraft(selection = OccasionSelection.Catalog("birthday")), restored)
    }

    // endregion
}
