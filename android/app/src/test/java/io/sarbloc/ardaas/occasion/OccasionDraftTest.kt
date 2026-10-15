package io.sarbloc.ardaas.occasion

import io.sarbloc.ardaas.content.ArdaasContent
import io.sarbloc.ardaas.content.ArdaasSegment
import io.sarbloc.ardaas.content.BundledAssets
import io.sarbloc.ardaas.content.LayerKind
import io.sarbloc.ardaas.content.fixtureContent
import io.sarbloc.ardaas.content.fixtureSegment
import io.sarbloc.ardaas.content.fixtureSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The slot marker as the bundled variants spell it. */
private const val MARKER = "….."

/**
 * Port of ArdaasTests/OccasionDraftTests.swift, case for case.
 *
 * The occasion picker's state: what the selection means for storage, and
 * what the in-context preview shows. The view itself is not unit-testable,
 * so everything it decides lives in [OccasionDraft] and is asserted directly.
 *
 * iOS mutates its value-type draft in place (`draft.selection = …`); the
 * Kotlin draft is immutable, so each step reassigns (`draft =
 * draft.withSelection(…)`). The assertions are iOS's.
 */
class OccasionDraftTest {
    private fun bundledCatalog() = BundledAssets.occasionCatalog()

    private fun content(id: String): ArdaasContent = BundledAssets.content(id)

    // region Selection → storage

    @Test
    fun theDefaultSelectionIsNoChoice() {
        val draft = OccasionDraft()
        assertEquals(OccasionSelection.Unset, draft.selection)
        assertEquals(OccasionChoice.Unset, draft.choice)
        assertFalse(draft.isCustom)
    }

    @Test
    fun aCatalogSelectionBecomesACatalogChoice() {
        val draft = OccasionDraft().withSelection(OccasionSelection.Catalog("japji-sahib"))
        assertEquals(OccasionChoice.Catalog("japji-sahib"), draft.choice)
        assertEquals("japji-sahib", draft.choice.storedId)
        assertEquals("", draft.choice.storedCustomText)
    }

    @Test
    fun freeTextBecomesACustomChoice() {
        val draft = OccasionDraft().withSelection(OccasionSelection.Custom)
            .copy(customText = "my daughter's first birthday")
        assertTrue(draft.isCustom)
        assertEquals(OccasionChoice.Custom("my daughter's first birthday"), draft.choice)
        assertEquals(OccasionChoice.CUSTOM_ID, draft.choice.storedId)
    }

    @Test
    fun freeTextIsTrimmed() {
        val draft = OccasionDraft().withSelection(OccasionSelection.Custom).copy(customText = "  ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ  \n")
        assertEquals(OccasionChoice.Custom("ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ"), draft.choice)
    }

    /**
     * "Other…" with nothing typed is not a choice: an abandoned draft must
     * leave the canonical dots exactly as authored.
     */
    @Test
    fun blankFreeTextIsNoChoice() {
        var draft = OccasionDraft().withSelection(OccasionSelection.Custom)
        assertEquals(OccasionChoice.Unset, draft.choice)
        draft = draft.copy(customText = "  \n\t ")
        assertEquals(OccasionChoice.Unset, draft.choice)
    }

    /**
     * Moving off "Other…" drops the words typed under it, so they can never
     * be saved beside a catalog choice or resurface later.
     */
    @Test
    fun switchingAwayFromOtherClearsTheFreeText() {
        var draft = OccasionDraft().withSelection(OccasionSelection.Custom).copy(customText = "a new job")

        draft = draft.withSelection(OccasionSelection.Catalog("birthday"))
        assertEquals("", draft.customText)
        assertEquals(OccasionChoice.Catalog("birthday"), draft.choice)

        draft = draft.withSelection(OccasionSelection.Custom)
        assertEquals("", draft.customText)

        draft = draft.copy(customText = "a new job")
        draft = draft.withSelection(OccasionSelection.Unset)
        assertEquals("", draft.customText)
        assertEquals(OccasionChoice.Unset, draft.choice)
    }

    /** Re-selecting the row already showing must not wipe what is being typed into it. */
    @Test
    fun reselectingOtherKeepsTheFreeText() {
        var draft = OccasionDraft().withSelection(OccasionSelection.Custom).copy(customText = "a new job")
        draft = draft.withSelection(OccasionSelection.Custom)
        assertEquals("a new job", draft.customText)
    }

    // endregion

    // region Storage → selection (a picker opens on a saved choice)

    @Test
    fun aDraftSeededFromNothingChosenIsTheDefault() {
        assertEquals(OccasionDraft(), OccasionDraft.from(OccasionChoice.Unset))
    }

    /**
     * A record can outlive the catalog entry it points at. The picker has no
     * row for a retired id, so the draft normalises to None — which is what
     * the text renders — rather than opening with nothing ticked.
     */
    @Test
    fun retiredCatalogIdNormalisesToNoneWhenCatalogGiven() {
        val catalog = bundledCatalog()
        val retired = OccasionChoice.Catalog("no-such-occasion-any-more")
        assertEquals(OccasionDraft(), OccasionDraft.from(retired, catalog))
        // Without a catalog the id is kept: a caller that cannot resolve it
        // must not silently discard the user's choice.
        assertEquals(retired, OccasionDraft.from(retired).choice)
    }

    /**
     * A retired id resolves to no layers, but that is not the #72 no-English
     * gap and must not raise its warning on a variant that has English.
     */
    @Test
    fun retiredCatalogIdDoesNotRaiseTheNoEnglishWarning() {
        val catalog = bundledCatalog()
        val sgpc = content("sgpc")
        val retired = OccasionChoice.Catalog("no-such-occasion-any-more")
        assertFalse(retired.willNotAppear(sgpc, catalog))
    }

    @Test
    fun aDraftSeededFromACatalogChoiceSelectsThatRow() {
        val draft = OccasionDraft.from(OccasionChoice.Catalog("birthday"))
        assertEquals(OccasionSelection.Catalog("birthday"), draft.selection)
        assertEquals("", draft.customText)
        assertFalse(draft.isCustom)
    }

    /**
     * Seeding must keep the words alongside "Other…" — on iOS that means
     * bypassing `selection`'s `didSet`; here, not going through
     * [OccasionDraft.withSelection].
     */
    @Test
    fun aDraftSeededFromFreeTextKeepsTheWords() {
        val draft = OccasionDraft.from(OccasionChoice.Custom("a new home"))
        assertEquals(OccasionSelection.Custom, draft.selection)
        assertTrue(draft.isCustom)
        assertEquals("a new home", draft.customText)
    }

    /**
     * Round-tripping a saved choice through the picker and back changes
     * nothing, so opening a picker cannot by itself rewrite a record.
     */
    @Test
    fun seedingRoundTripsEveryStorableChoice() {
        val choices = listOf(
            OccasionChoice.Unset,
            OccasionChoice.Catalog("japji-sahib"),
            OccasionChoice.Custom("ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ"),
        )
        for (choice in choices) {
            assertEquals(choice, OccasionDraft.from(choice).choice)
        }
    }

    // endregion

    // region The in-context preview

    /**
     * Nothing chosen shows the sentence exactly as authored — dots included.
     * That is the teaching case: it is where the reader sees what the "….."
     * actually is.
     */
    @Test
    fun noChoicePreviewsTheAuthoredSentence() {
        val preview = requireNotNull(OccasionDraft().preview(content("sgpc"), bundledCatalog()))
        assertFalse(preview.isFilled)
        assertEquals(LayerKind.GURMUKHI, preview.layer)
        assertTrue(preview.text, preview.text.contains(MARKER))
    }

    @Test
    fun aCatalogChoicePreviewsTheGurmukhiSentence() {
        val draft = OccasionDraft().withSelection(OccasionSelection.Catalog("japji-sahib"))
        val preview = requireNotNull(draft.preview(content("sgpc"), bundledCatalog()))
        assertTrue(preview.isFilled)
        assertEquals(LayerKind.GURMUKHI, preview.layer)
        assertTrue(preview.text, preview.text.endsWith("ਆਪ ਦੇ ਹਜ਼ੂਰ ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥"))
        assertFalse(preview.text.contains(MARKER))
    }

    /**
     * English free text fills only the English layer, so previewing the
     * Gurmukhi would show the untouched dots. The preview follows the
     * substitution to the layer it actually landed in.
     */
    @Test
    fun englishFreeTextPreviewsTheEnglishSentence() {
        val draft = OccasionDraft().withSelection(OccasionSelection.Custom)
            .copy(customText = "my daughter's first birthday")
        val preview = requireNotNull(draft.preview(content("sgpc"), bundledCatalog()))
        assertTrue(preview.isFilled)
        assertEquals(LayerKind.ENGLISH, preview.layer)
        assertTrue(preview.text, preview.text.endsWith("my daughter's first birthday."))
        assertFalse(preview.text.contains("mention here"))
    }

    @Test
    fun gurmukhiFreeTextPreviewsTheGurmukhiSentence() {
        val draft = OccasionDraft().withSelection(OccasionSelection.Custom)
            .copy(customText = "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ ਦੇ ਸਮਾਗਮ")
        val preview = requireNotNull(draft.preview(content("buddha-dal"), bundledCatalog()))
        assertTrue(preview.isFilled)
        assertEquals(LayerKind.GURMUKHI, preview.layer)
        assertTrue(preview.text, preview.text.contains("ਹਜ਼ੂਰ ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ ਦੇ ਸਮਾਗਮ ਦੀ ਅਰਦਾਸ"))
    }

    /**
     * A variant with no occasion slot has nothing to preview — and does not
     * crash. Unreachable for the bundled variants, both of which declare one.
     */
    @Test
    fun aVariantWithoutASlotHasNoPreview() {
        val noSlot = fixtureContent(listOf(fixtureSegment("a")), slotAfter = "a")
        assertNull(OccasionDraft().preview(noSlot, bundledCatalog()))
    }

    // endregion

    // region The no-English gap (#72)

    /**
     * A variant with no English layer, spelled as a fixture: since #60 both
     * bundled variants carry English, so nothing shipped reaches this state —
     * but the rule is the model's, not Buddha Dal's.
     */
    private val noEnglishVariant = fixtureContent(
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

    /**
     * Free text that isn't in Gurmukhi script becomes the English layer and
     * nothing else, so on a variant carrying no English it has nowhere to
     * appear and the reader would still see the dots.
     */
    @Test
    fun latinFreeTextOnAVariantWithoutEnglishIsFlagged() {
        val catalog = bundledCatalog()
        val draft = OccasionDraft().withSelection(OccasionSelection.Custom)
            .copy(customText = "my daughter's first birthday")

        assertTrue(draft.willNotAppear(noEnglishVariant, catalog))
        assertEquals(false, draft.preview(noEnglishVariant, catalog)?.isFilled)
        // The choice is still recorded — the words are the user's, and #72 is
        // about where they can be shown, not about discarding them.
        assertEquals(OccasionChoice.Custom("my daughter's first birthday"), draft.choice)
    }

    /** The same text on a variant that does carry English is fine — which, since #60, is both bundled variants. */
    @Test
    fun latinFreeTextOnAVariantWithEnglishIsNotFlagged() {
        val draft = OccasionDraft().withSelection(OccasionSelection.Custom)
            .copy(customText = "my daughter's first birthday")
        for (id in listOf("sgpc", "buddha-dal")) {
            assertFalse(id, draft.willNotAppear(content(id), bundledCatalog()))
        }
    }

    /** Gurmukhi free text and every bundled entry reach Buddha Dal's Gurmukhi layer, so nothing there is flagged. */
    @Test
    fun gurmukhiAndCatalogChoicesAreNeverFlagged() {
        val buddhaDal = content("buddha-dal")
        val catalog = bundledCatalog()

        val typed = OccasionDraft().withSelection(OccasionSelection.Custom).copy(customText = "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ ਦੇ ਸਮਾਗਮ")
        assertFalse(typed.willNotAppear(buddhaDal, catalog))

        for (entry in catalog.occasions) {
            val picked = OccasionDraft().withSelection(OccasionSelection.Catalog(entry.id))
            assertFalse(
                "${entry.id} does not reach any Buddha Dal layer",
                picked.willNotAppear(buddhaDal, catalog),
            )
        }
    }

    /** Nothing chosen is not a warning: leaving the slot alone is a normal, supported way to read the Ardaas. */
    @Test
    fun noChoiceIsNeverFlagged() {
        val catalog = bundledCatalog()
        for (id in listOf("sgpc", "buddha-dal")) {
            assertFalse(OccasionDraft().willNotAppear(content(id), catalog))
        }
    }

    /** An abandoned "Other…" draft is not a warning either — it is not a choice at all. */
    @Test
    fun blankFreeTextIsNeverFlagged() {
        val draft = OccasionDraft().withSelection(OccasionSelection.Custom).copy(customText = "   ")
        assertFalse(draft.willNotAppear(noEnglishVariant, bundledCatalog()))
    }

    // endregion
}
