package io.sarbloc.ardaas.occasion

import io.sarbloc.ardaas.content.BundledAssets
import io.sarbloc.ardaas.transliteration.GurmukhiTransliterator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Port of ArdaasTests/OccasionChoiceTests.swift, case for case, except its
 * "Layers" section, which is in [OccasionLayersTest].
 *
 * The occasion *choice*: how it is stored on a record, and how it resolves to
 * the layers the composer splices in.
 */
class OccasionChoiceTest {
    private fun bundledCatalog() = BundledAssets.occasionCatalog()

    // region Storage

    @Test
    fun emptyIdIsNoChoice() {
        assertEquals(OccasionChoice.Unset, OccasionChoice.fromStored(occasionId = "", occasionCustom = ""))
        assertEquals("", OccasionChoice.Unset.storedId)
        assertEquals("", OccasionChoice.Unset.storedCustomText)
    }

    @Test
    fun aCatalogChoiceStoresOnlyItsId() {
        val choice = OccasionChoice.Catalog("japji-sahib")
        assertEquals("japji-sahib", choice.storedId)
        assertEquals("", choice.storedCustomText)
        assertEquals(choice, OccasionChoice.fromStored(choice.storedId, choice.storedCustomText))
    }

    /**
     * Free text rides the sentinel id, so a stored id is always either empty
     * or a real catalog id — a user typing "birthday" is never mistaken for
     * the catalog's `birthday` entry.
     */
    @Test
    fun freeTextRoundTripsUnderTheSentinelId() {
        val choice = OccasionChoice.Custom("ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ")
        assertEquals(OccasionChoice.CUSTOM_ID, choice.storedId)
        assertEquals("ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ", choice.storedCustomText)
        assertEquals(choice, OccasionChoice.fromStored(choice.storedId, choice.storedCustomText))
        assertNotEquals(OccasionChoice.Catalog("birthday"), OccasionChoice.Custom("birthday"))
    }

    @Test
    fun storedValuesAreTrimmed() {
        assertEquals("a birth", OccasionChoice.Custom("  a birth  ").storedCustomText)
        assertEquals("japji-sahib", OccasionChoice.Catalog(" japji-sahib ").storedId)
        assertEquals(OccasionChoice.Unset, OccasionChoice.fromStored(occasionId = "  ", occasionCustom = "orphaned"))
    }

    /**
     * The sentinel with nothing typed is not a choice — an abandoned "Other…"
     * draft must not blank or fill the slot.
     */
    @Test
    fun theSentinelWithBlankTextIsNoChoice() {
        assertEquals(
            OccasionChoice.Unset,
            OccasionChoice.fromStored(occasionId = OccasionChoice.CUSTOM_ID, occasionCustom = " \n "),
        )
        val catalog = bundledCatalog()
        assertNull(OccasionChoice.Custom("   ").layers(catalog))
    }

    // endregion

    // region Resolution

    @Test
    fun aCatalogChoiceResolvesToAllThreeBundledLayers() {
        val catalog = bundledCatalog()
        val layers = requireNotNull(OccasionChoice.Catalog("japji-sahib").layers(catalog))
        val occasion = requireNotNull(catalog.occasion("japji-sahib"))
        assertEquals(occasion.gurmukhi, layers.gurmukhi)
        assertEquals(occasion.transliteration, layers.transliteration)
        assertEquals(occasion.english, layers.english)
    }

    /**
     * An entry retired from a later bundle leaves the canonical dots rather
     * than crashing or blanking the sentence.
     */
    @Test
    fun anUnknownIdResolvesToNothing() {
        val catalog = bundledCatalog()
        assertNull(OccasionChoice.Catalog("no-such-occasion").layers(catalog))
        assertNull(OccasionChoice.Unset.layers(catalog))
    }

    // endregion

    // region Free text

    /**
     * Gurmukhi free text *is* the Gurmukhi layer, and its Roman layer is
     * derived with the same engine that generated the bundled catalog's — so
     * a typed occasion and a picked one romanize in one voice.
     */
    @Test
    fun gurmukhiFreeTextFillsGurmukhiAndTransliteration() {
        val layers = requireNotNull(OccasionLayers.fromFreeText(" ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ ਦੇ ਸਮਾਗਮ "))
        assertEquals("ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ ਦੇ ਸਮਾਗਮ", layers.gurmukhi)
        assertEquals(GurmukhiTransliterator.transliterate("ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ ਦੇ ਸਮਾਗਮ"), layers.transliteration)
        assertFalse(layers.transliteration.isEmpty())
        assertFalse(layers.transliteration.any { it.code in 0x0A00..0x0A7F })
        // Not translated: the English layer keeps its dots rather than
        // showing Gurmukhi the reader asked not to see.
        assertEquals("", layers.english)
    }

    /**
     * Anything not in Gurmukhi script goes to the English layer verbatim —
     * including Roman-script Punjabi, since the detector counts scripts, not
     * languages, and this app never guesses Gurmukhi back out of Roman.
     */
    @Test
    fun nonGurmukhiFreeTextFillsOnlyEnglish() {
        for (text in listOf("my daughter's first birthday", "Amrit Sanchaar de samaagam", "108")) {
            val layers = requireNotNull(OccasionLayers.fromFreeText(text))
            assertEquals(text, layers.english)
            assertEquals("", layers.gurmukhi)
            assertEquals("", layers.transliteration)
        }
    }

    @Test
    fun blankFreeTextIsNothing() {
        assertNull(OccasionLayers.fromFreeText(""))
        assertNull(OccasionLayers.fromFreeText(" \n\t "))
    }

    @Test
    fun freeTextResolvesThroughTheChoice() {
        val catalog = bundledCatalog()
        val layers = requireNotNull(OccasionChoice.Custom("a new home").layers(catalog))
        assertEquals("a new home", layers.english)
    }

    // endregion
}
