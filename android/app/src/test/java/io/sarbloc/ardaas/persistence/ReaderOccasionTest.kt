package io.sarbloc.ardaas.persistence

import androidx.room.Room
import io.sarbloc.ardaas.composer.RenderItem
import io.sarbloc.ardaas.content.ArdaasContent
import io.sarbloc.ardaas.content.ArdaasSegment
import io.sarbloc.ardaas.content.BundledAssets
import io.sarbloc.ardaas.content.fixtureContent
import io.sarbloc.ardaas.content.fixtureSlot
import io.sarbloc.ardaas.occasion.OccasionCatalog
import io.sarbloc.ardaas.occasion.OccasionChoice
import io.sarbloc.ardaas.occasion.OccasionDraft
import io.sarbloc.ardaas.occasion.OccasionSelection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The slot marker as the bundled variants spell it: one ellipsis character followed by two full stops. */
private const val MARKER = "….."

/**
 * Port of ArdaasTests/ReaderOccasionTests.swift, case for case.
 *
 * What the Reader renders for a saved record, and what its occasion picker
 * writes back. Everything the screen decides lives behind two pure entry
 * points — [renderItems] for the composition and
 * [OccasionChoice.willNotAppear] for the #72 warning — and both are asserted
 * here against the real bundled text and catalog. This is also where the
 * catalog-load-failure path (a null catalog) is pinned.
 *
 * iOS mutates its record in place; the Kotlin entity is immutable, so each
 * write is a reassignment through [withOccasionChoice] or `copy`.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderOccasionTest {
    private fun bundledCatalog(): OccasionCatalog = BundledAssets.occasionCatalog()

    private fun content(id: String): ArdaasContent = BundledAssets.content(id)

    private fun record(occasion: OccasionChoice = OccasionChoice.Unset) =
        SavedArdaas(label = "Morning", bentiText = "bless the family", occasion = occasion)

    /** Where the benti card sits in a composed sequence, if at all. */
    private fun bentiIndices(items: List<RenderItem>): List<Int> = items.indices.filter { items[it] is RenderItem.Benti }

    /** The slot segment as the Reader would draw it, out of the full composed sequence. */
    private fun slotSegment(saved: SavedArdaas, content: ArdaasContent, catalog: OccasionCatalog?): ArdaasSegment {
        val slotId = requireNotNull(content.occasionSlot) { "no occasion slot" }.inSegmentId
        return saved.renderItems(content, catalog)
            .filterIsInstance<RenderItem.Canonical>()
            .map { it.segment }
            .firstOrNull { it.id == slotId }
            ?: throw AssertionError("no slot segment '$slotId' in the composed sequence")
    }

    // region The Reader composes with the occasion

    @Test
    fun aCatalogChoiceIsComposedIntoTheSlot() {
        val sgpc = content("sgpc")
        val segment = slotSegment(record(OccasionChoice.Catalog("japji-sahib")), sgpc, bundledCatalog())
        assertTrue(segment.gurmukhi, segment.gurmukhi.contains("ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ"))
        assertFalse(segment.gurmukhi, segment.gurmukhi.contains(MARKER))
        // Every layer the variant carries, not just the Gurmukhi.
        assertEquals(false, segment.english?.contains(MARKER))
        assertEquals(false, segment.transliteration?.contains("…"))
    }

    @Test
    fun freeTextIsComposedIntoTheSlot() {
        val segment = slotSegment(
            record(OccasionChoice.Custom("my daughter's first birthday")),
            content("sgpc"),
            bundledCatalog(),
        )
        val english = requireNotNull(segment.english)
        assertTrue(english, english.endsWith("my daughter's first birthday."))
        // The authored parenthetical goes with the dots it explains.
        assertFalse(english, english.contains("mention here"))
        // One script only: the Gurmukhi layer keeps its dots rather than
        // taking Roman words (see OccasionLayers.fromFreeText).
        assertTrue(segment.gurmukhi, segment.gurmukhi.contains(MARKER))
    }

    /**
     * Nothing chosen renders the sentence exactly as authored — the
     * operator's decision for #65, and the state every pre-occasion record
     * reads as.
     */
    @Test
    fun nothingChosenLeavesTheDotsExactlyAsAuthored() {
        val sgpc = content("sgpc")
        val authored = requireNotNull(sgpc.segments.firstOrNull { it.id == sgpc.occasionSlot?.inSegmentId })
        val segment = slotSegment(record(), sgpc, bundledCatalog())
        assertEquals(authored, segment)
    }

    /** An entry retired from a later bundle leaves the canonical dots rather than a hole in the sentence. */
    @Test
    fun anUnknownCatalogIdLeavesTheDots() {
        val segment = slotSegment(record(OccasionChoice.Catalog("no-such-occasion")), content("sgpc"), bundledCatalog())
        assertTrue(segment.gurmukhi, segment.gurmukhi.contains(MARKER))
    }

    /**
     * A catalog that failed to load costs the reader the substitution, never
     * the Ardaas: the text still renders, with its dots.
     */
    @Test
    fun aMissingCatalogLeavesTheDots() {
        val segment = slotSegment(record(OccasionChoice.Catalog("japji-sahib")), content("sgpc"), catalog = null)
        assertTrue(segment.gurmukhi, segment.gurmukhi.contains(MARKER))
    }

    /** The occasion does not disturb the benti: it is still inserted, once, immediately after its own slot segment. */
    @Test
    fun theBentiIsStillComposedInAlongsideTheOccasion() {
        val sgpc = content("sgpc")
        val saved = record(OccasionChoice.Catalog("japji-sahib"))
        val items = saved.renderItems(sgpc, bundledCatalog())

        val indices = bentiIndices(items)
        assertEquals(1, indices.size)
        val index = indices.single()
        assertTrue("the benti is never the first item", index > 0)
        val previous = items[index - 1] as? RenderItem.Canonical
            ?: throw AssertionError("the benti should follow a canonical segment")
        assertEquals(sgpc.bentiSlot.afterSegmentId, previous.segment.id)
        assertEquals(RenderItem.Benti(saved.bentiLayers), items[index])
    }

    /** Reading the plain Ardaas with an occasion but no benti is valid use: no benti card, and the slot is still filled. */
    @Test
    fun anOccasionRendersWithoutABenti() {
        val sgpc = content("sgpc")
        val catalog = bundledCatalog()
        val saved = SavedArdaas(label = "Sangat", bentiText = "  ", occasion = OccasionChoice.Catalog("sukhmani-sahib"))
        assertTrue(bentiIndices(saved.renderItems(sgpc, catalog)).isEmpty())
        val segment = slotSegment(saved, sgpc, catalog)
        assertFalse(segment.gurmukhi, segment.gurmukhi.contains(MARKER))
    }

    // endregion

    // region The picker persists to the record

    /**
     * The Reader's picker writes straight back: the draft's choice through
     * [withOccasionChoice], which keeps the two stored fields consistent.
     */
    @Test
    fun changingTheOccasionPersistsToTheRecord() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ArdaasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.savedArdaasDao()
            val saved = requireNotNull(dao.getById(dao.insert(record())))

            val draft = OccasionDraft.from(saved.occasionChoice).withSelection(OccasionSelection.Catalog("birthday"))
            dao.update(saved.withOccasionChoice(draft.choice))

            val stored = requireNotNull(dao.getById(saved.id))
            assertEquals("birthday", stored.occasionId)
            assertEquals("", stored.occasionCustom)
            assertEquals(OccasionChoice.Catalog("birthday"), stored.occasionChoice)
            // And the Reader now renders it.
            val segment = slotSegment(stored, content("sgpc"), bundledCatalog())
            assertFalse(segment.gurmukhi, segment.gurmukhi.contains(MARKER))
        } finally {
            db.close()
        }
    }

    /** Moving from a catalog entry to the user's own words leaves no trace of the id, and back again leaves no trace of the words. */
    @Test
    fun movingBetweenChoicesNeverLeavesAStalePair() {
        var saved = record(OccasionChoice.Catalog("birthday"))

        var draft = OccasionDraft.from(saved.occasionChoice)
            .withSelection(OccasionSelection.Custom)
            .copy(customText = "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ")
        saved = saved.withOccasionChoice(draft.choice)
        assertEquals(OccasionChoice.CUSTOM_ID, saved.occasionId)
        assertEquals("ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ", saved.occasionCustom)

        draft = draft.withSelection(OccasionSelection.Catalog("japji-sahib"))
        saved = saved.withOccasionChoice(draft.choice)
        assertEquals("japji-sahib", saved.occasionId)
        assertEquals("", saved.occasionCustom)

        draft = draft.withSelection(OccasionSelection.Unset)
        saved = saved.withOccasionChoice(draft.choice)
        assertEquals(OccasionChoice.Unset, saved.occasionChoice)
        assertEquals("", saved.occasionId)
        assertEquals("", saved.occasionCustom)
    }

    /**
     * Clearing the free-text field is "nothing chosen" while it is empty — the
     * same rule Compose applies at Save, applied live here — and the dots come
     * back with it.
     */
    @Test
    fun emptyingTheFreeTextClearsTheChoice() {
        var saved = record(OccasionChoice.Custom("a new home"))
        val draft = OccasionDraft.from(saved.occasionChoice).copy(customText = "   ")
        saved = saved.withOccasionChoice(draft.choice)

        assertEquals(OccasionChoice.Unset, saved.occasionChoice)
        val segment = slotSegment(saved, content("sgpc"), bundledCatalog())
        assertEquals(true, segment.english?.contains(MARKER))
    }

    // endregion

    // region Stranding free text by switching variant (#72)

    /**
     * The state only the Reader can create: free text written for a variant
     * that carries English, then moved to one that does not. The choice is
     * kept — the words are the user's — but the reader sees the dots, so the
     * Reader says so at the slot. Since #60 no shipped pair of variants can
     * strand free text, so the destination is a fixture.
     */
    @Test
    fun switchingToAVariantWithoutEnglishStrandsLatinFreeText() {
        val catalog = bundledCatalog()
        val saved = record(OccasionChoice.Custom("my daughter's first birthday"))

        assertFalse(saved.occasionChoice.willNotAppear(content("sgpc"), catalog))

        val noEnglish = fixtureContent(
            listOf(
                ArdaasSegment(
                    id = "slot",
                    gurmukhi = "ਆਪ ਜੀ ਦੇ ਹਜ਼ੂਰ${MARKER}ਦੀ ਅਰਦਾਸ",
                    transliteration = "Aap Ji De Hazur${MARKER}Di Ardaas",
                    english = null,
                ),
            ),
            slotAfter = "slot",
            occasionSlot = fixtureSlot("slot", transliteration = MARKER),
        )
        assertTrue(saved.occasionChoice.willNotAppear(noEnglish, catalog))
        // And the warning is honest: the slot really does still read as authored.
        val segment = slotSegment(saved, noEnglish, catalog)
        assertTrue(segment.gurmukhi, segment.gurmukhi.contains(MARKER))
        assertEquals(OccasionChoice.Custom("my daughter's first birthday"), saved.occasionChoice)
    }

    /** The gap #72 describes is closed for everything that ships: Latin free text lands in Buddha Dal's English layer. */
    @Test
    fun latinFreeTextReachesEveryBundledVariant() {
        val catalog = bundledCatalog()
        var saved = record(OccasionChoice.Custom("my daughter's first birthday"))
        for (id in listOf("sgpc", "buddha-dal")) {
            val variant = content(id)
            saved = saved.copy(variantId = id)
            assertFalse(id, saved.occasionChoice.willNotAppear(variant, catalog))
            val segment = slotSegment(saved, variant, catalog)
            val english = requireNotNull(segment.english) { id }
            assertTrue(english, english.contains("my daughter's first birthday"))
            assertFalse(english, english.contains(MARKER))
        }
    }

    /** Catalog entries and Gurmukhi free text reach Buddha Dal's Gurmukhi layer, so switching variants strands nothing. */
    @Test
    fun switchingVariantsDoesNotStrandGurmukhiOrCatalogChoices() {
        val catalog = bundledCatalog()
        val buddhaDal = content("buddha-dal")

        for (choice in listOf(OccasionChoice.Catalog("japji-sahib"), OccasionChoice.Custom("ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ"))) {
            val saved = record(choice)
            assertFalse("$choice", saved.occasionChoice.willNotAppear(buddhaDal, catalog))
            val segment = slotSegment(saved, buddhaDal, catalog)
            assertFalse(segment.gurmukhi, segment.gurmukhi.contains(MARKER))
        }
    }

    /** Nothing chosen is never a warning on either variant: leaving the slot alone is a normal way to read the Ardaas. */
    @Test
    fun nothingChosenIsNeverFlagged() {
        val catalog = bundledCatalog()
        for (id in listOf("sgpc", "buddha-dal")) {
            assertFalse(record().occasionChoice.willNotAppear(content(id), catalog))
        }
    }

    // endregion

    // region Android-only: free text survives a missing catalog

    /**
     * The other half of the catalog-load-failure rule, asserted directly on
     * iOS only through the code: a free-text occasion is stored whole on the
     * record, so it still renders when the catalog is null.
     */
    @Test
    fun freeTextSurvivesAMissingCatalog() {
        val segment = slotSegment(
            record(OccasionChoice.Custom("my daughter's first birthday")),
            content("sgpc"),
            catalog = null,
        )
        val english = requireNotNull(segment.english)
        assertTrue(english, english.endsWith("my daughter's first birthday."))
    }

    // endregion
}
