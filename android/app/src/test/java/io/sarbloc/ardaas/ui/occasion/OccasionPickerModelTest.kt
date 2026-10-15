package io.sarbloc.ardaas.ui.occasion

import io.sarbloc.ardaas.content.BundledAssets
import io.sarbloc.ardaas.occasion.Occasion
import io.sarbloc.ardaas.occasion.OccasionCatalog
import io.sarbloc.ardaas.occasion.OccasionCategory
import io.sarbloc.ardaas.occasion.OccasionChoice
import io.sarbloc.ardaas.occasion.OccasionCopy
import io.sarbloc.ardaas.occasion.OccasionDraft
import io.sarbloc.ardaas.occasion.OccasionSelection
import io.sarbloc.ardaas.persistence.SavedArdaas
import io.sarbloc.ardaas.persistence.occasionChoice
import io.sarbloc.ardaas.persistence.withOccasionChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The occasion picker's decisions (#90): the groups it renders, its
 * selection → stored choice round trip, how its draft survives recreation,
 * and what the Reader writes on Done. The catalog's own order is pinned in
 * `OccasionCatalogTest`; this pins how the picker groups it.
 */
class OccasionPickerModelTest {
    private val catalog = BundledAssets.occasionCatalog()

    private fun record(choice: OccasionChoice = OccasionChoice.Unset) =
        SavedArdaas(label = "Morning", bentiText = "my benti").withOccasionChoice(choice)

    private fun entry(id: String, category: OccasionCategory) =
        Occasion(id, category, "ਗ $id", "g $id", "e $id")

    // region Grouping

    @Test
    fun theBundledCatalogRendersAsPaathThenOccasions() {
        val groups = catalog.pickerGroups()
        assertEquals(listOf(OccasionCategory.PAATH, OccasionCategory.OCCASION), groups.map { it.category })
        assertEquals(listOf(OccasionCopy.PAATH_HEADER, OccasionCopy.OCCASIONS_HEADER), groups.map { it.title })
        assertEquals(listOf(9, 6), groups.map { it.entries.size })
    }

    @Test
    fun everyBundledEntryRendersOnceInCatalogOrder() {
        // Paath entries lead the bundle, so the grouped rows read in exactly
        // the catalog's order — all fifteen, none twice.
        val rendered = catalog.pickerGroups().flatMap { it.entries }
        assertEquals(15, rendered.size)
        assertEquals(catalog.occasions, rendered)
    }

    @Test
    fun eachGroupHoldsOnlyItsOwnCategory() {
        for (group in catalog.pickerGroups()) {
            assertTrue(group.entries.all { it.category == group.category })
        }
    }

    @Test
    fun theGroupsFollowTheDataNotAFixedList() {
        // Interleaved in the file: each group keeps catalog order within it.
        val interleaved = OccasionCatalog(
            version = 1,
            sources = emptyList(),
            occasions = listOf(
                entry("o1", OccasionCategory.OCCASION),
                entry("p1", OccasionCategory.PAATH),
                entry("o2", OccasionCategory.OCCASION),
                entry("p2", OccasionCategory.PAATH),
            ),
        )
        val groups = interleaved.pickerGroups()
        assertEquals(listOf("p1", "p2"), groups[0].entries.map { it.id })
        assertEquals(listOf("o1", "o2"), groups[1].entries.map { it.id })
    }

    @Test
    fun anEmptyCategoryGetsNoHeader() {
        val paathOnly = OccasionCatalog(1, emptyList(), listOf(entry("p1", OccasionCategory.PAATH)))
        assertEquals(listOf(OccasionCategory.PAATH), paathOnly.pickerGroups().map { it.category })
    }

    // endregion

    // region Selection → stored choice

    @Test
    fun everyCatalogRowRoundTripsThroughTheStoredChoice() {
        for (occasion in catalog.occasions) {
            val picked = OccasionDraft().withSelection(OccasionSelection.Catalog(occasion.id)).choice
            val saved = record().withOccasionChoice(picked)
            assertEquals(OccasionChoice.Catalog(occasion.id), saved.occasionChoice)
            // Reopening the picker lands on the same row.
            assertEquals(OccasionSelection.Catalog(occasion.id), OccasionDraft.from(saved.occasionChoice, catalog).selection)
        }
    }

    @Test
    fun noneRoundTripsAsUnset() {
        val saved = record(OccasionChoice.Catalog("japji-sahib"))
            .withOccasionChoice(OccasionDraft().withSelection(OccasionSelection.Unset).choice)
        assertEquals(OccasionChoice.Unset, saved.occasionChoice)
        assertEquals(OccasionSelection.Unset, OccasionDraft.from(saved.occasionChoice, catalog).selection)
    }

    @Test
    fun otherIsStoredTrimmedAndReopensWithItsWords() {
        val picked = OccasionDraft().withSelection(OccasionSelection.Custom)
            .copy(customText = "  my daughter's first birthday \n").choice
        val saved = record().withOccasionChoice(picked)
        assertEquals(OccasionChoice.Custom("my daughter's first birthday"), saved.occasionChoice)
        assertEquals(
            OccasionDraft(OccasionSelection.Custom, "my daughter's first birthday"),
            OccasionDraft.from(saved.occasionChoice, catalog),
        )
    }

    @Test
    fun otherWithNothingTypedIsNoChoice() {
        for (typed in listOf("", "   ", "\n\t")) {
            val picked = OccasionDraft().withSelection(OccasionSelection.Custom).copy(customText = typed).choice
            assertEquals(OccasionChoice.Unset, picked)
        }
    }

    // endregion

    // region Surviving recreation

    @Test
    fun everyDraftShapeSurvivesTheBundle() {
        val drafts = listOf(
            OccasionDraft(),
            OccasionDraft(OccasionSelection.Catalog("japji-sahib")),
            // Half typed: the one state no OccasionChoice can carry.
            OccasionDraft(OccasionSelection.Custom, "my daugh"),
            OccasionDraft(OccasionSelection.Custom, ""),
        )
        for (draft in drafts) {
            assertEquals(draft, OccasionDraftBundle.decode(OccasionDraftBundle.encode(draft)))
        }
    }

    @Test
    fun aMalformedBundleRestoresNothing() {
        assertNull(OccasionDraftBundle.decode(emptyList()))
        assertNull(OccasionDraftBundle.decode(listOf("mystery", "", "")))
    }

    // endregion

    // region What the Reader writes on Done

    @Test
    fun doneWithNoChangeWritesNothing() {
        assertNull(record().withPickedOccasion(OccasionChoice.Unset, catalog))
        assertNull(record(OccasionChoice.Catalog("japji-sahib")).withPickedOccasion(OccasionChoice.Catalog("japji-sahib"), catalog))
        assertNull(record(OccasionChoice.Custom("words")).withPickedOccasion(OccasionChoice.Custom("words"), catalog))
    }

    @Test
    fun doneWithAChangeWritesBothStoredFields() {
        val updated = written(
            record(OccasionChoice.Custom("words")).withPickedOccasion(OccasionChoice.Catalog("japji-sahib"), catalog),
        )
        assertEquals("japji-sahib", updated.occasionId)
        assertEquals("", updated.occasionCustom)
    }

    @Test
    fun clearingFreeTextWritesUnset() {
        // "Other…" emptied comes back from the picker as Unset.
        val updated = written(record(OccasionChoice.Custom("words")).withPickedOccasion(OccasionChoice.Unset, catalog))
        assertEquals(OccasionChoice.Unset, updated.occasionChoice)
    }

    @Test
    fun anUntouchedRetiredIdIsLeftAlone() {
        // The picker opens a retired id as "None"; Done without a change
        // must not rewrite the stored id.
        val retired = record(OccasionChoice.Catalog("retired-entry"))
        assertNull(retired.withPickedOccasion(OccasionChoice.Unset, catalog))
        val replaced = written(retired.withPickedOccasion(OccasionChoice.Catalog("japji-sahib"), catalog))
        assertEquals(OccasionChoice.Catalog("japji-sahib"), replaced.occasionChoice)
    }

    // endregion

    /** The record Done wrote, failing the test if it wrote nothing. */
    private fun written(record: SavedArdaas?): SavedArdaas {
        assertNotNull("expected a write", record)
        return record!!
    }
}
