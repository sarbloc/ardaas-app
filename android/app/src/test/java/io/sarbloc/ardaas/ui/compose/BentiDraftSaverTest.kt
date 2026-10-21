package io.sarbloc.ardaas.ui.compose

import androidx.compose.runtime.saveable.SaverScope
import io.sarbloc.ardaas.composer.BentiDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The draft — staleness included — survives a configuration change and process death. */
class BentiDraftSaverTest {
    private val scope = SaverScope { true }

    private fun roundTrip(draft: BentiDraft): BentiDraft {
        val saved = requireNotNull(with(BentiDraftSaver) { scope.save(draft) })
        return requireNotNull(BentiDraftSaver.restore(saved))
    }

    @Test
    fun anEmptyDraftRoundTrips() {
        assertEquals(BentiDraft(), roundTrip(BentiDraft()))
    }

    @Test
    fun aTranslatedDraftKeepsItsSource() {
        val draft = BentiDraft(typed = "Bless us").applyTranslation("ਸਾਨੂੰ ਬਖ਼ਸ਼ੋ", "Bless us")
        assertEquals(draft, roundTrip(draft))
    }

    @Test
    fun aStaleDraftIsStillStaleAfterRestore() {
        val stale = BentiDraft(typed = "Bless us").applyTranslation("ਸਾਨੂੰ ਬਖ਼ਸ਼ੋ", "Bless us").copy(typed = "Bless us all")
        assertTrue(stale.isStale)
        assertTrue(roundTrip(stale).isStale)
    }

    @Test
    fun aHandTypedDraftHasNoSourceAfterRestore() {
        val restored = roundTrip(BentiDraft(typed = "ਅਰਦਾਸ"))
        assertEquals(null, restored.translatedFrom)
        assertFalse(restored.isStale)
    }

    /** #131: an emptied Gurmukhi editor is still on screen after a rotation or process death. */
    @Test
    fun anEmptiedDraftStaysOpenAfterRestore() {
        val emptied = BentiDraft(typed = "Bless us").applyTranslation("ਸਾਨੂੰ ਬਖ਼ਸ਼ੋ", "Bless us").copy(gurmukhi = "")
        val restored = roundTrip(emptied)
        assertEquals(emptied, restored)
        assertTrue(restored.showsTranslationDraft)
    }

    @Test
    fun aRemovedDraftStaysClosedAfterRestore() {
        val removed = BentiDraft(typed = "Bless us").applyTranslation("ਸਾਨੂੰ ਬਖ਼ਸ਼ੋ", "Bless us").clearTranslation()
        assertFalse(roundTrip(removed).showsTranslationDraft)
    }

    /** State saved before the flag existed restores it from the Gurmukhi, as the old derivation did. */
    @Test
    fun aListWithoutTheFlagDerivesItFromTheGurmukhi() {
        assertTrue(requireNotNull(BentiDraftSaver.restore(listOf("Bless us", "ਅਰਦਾਸ", "Bless us"))).isDraftOpen)
        assertFalse(requireNotNull(BentiDraftSaver.restore(listOf("Bless us", "", null))).isDraftOpen)
    }
}
