package io.sarbloc.ardaas.composer

import io.sarbloc.ardaas.content.ArdaasSegment
import io.sarbloc.ardaas.content.LayerKind
import io.sarbloc.ardaas.content.LayerKind.ENGLISH
import io.sarbloc.ardaas.content.LayerKind.GURMUKHI
import io.sarbloc.ardaas.content.LayerKind.TRANSLITERATION
import io.sarbloc.ardaas.content.fixtureContent
import io.sarbloc.ardaas.content.fixtureSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Port of ArdaasTests/LayerVisibilityTests.swift, case for case.

internal fun toggles(gurmukhi: Boolean = true, transliteration: Boolean = true, english: Boolean = true) =
    LayerToggles(gurmukhi = gurmukhi, transliteration = transliteration, english = english)

private val fullBenti = BentiLayers(gurmukhi = "ਮੇਰੀ ਬੇਨਤੀ", transliteration = "merī bentī", english = "my benti")

class BentiVisibleLinesTest {
    private fun kinds(layers: BentiLayers, toggles: LayerToggles): List<LayerKind> =
        layers.visibleLines(toggles).map { it.kind }

    @Test
    fun allLayersOnRendersAllThreeInLayerOrder() {
        assertEquals(
            listOf(
                BentiLine(GURMUKHI, "ਮੇਰੀ ਬੇਨਤੀ"),
                BentiLine(TRANSLITERATION, "merī bentī"),
                BentiLine(ENGLISH, "my benti"),
            ),
            fullBenti.visibleLines(toggles()),
        )
    }

    /** Seven of the eight combinations are the toggles taken literally; all-off falls back (below). */
    @Test
    fun everyToggleCombinationSelectsExactlyTheToggledLayers() {
        val cases = listOf(
            Triple(true, true, true) to listOf(GURMUKHI, TRANSLITERATION, ENGLISH),
            Triple(true, true, false) to listOf(GURMUKHI, TRANSLITERATION),
            Triple(true, false, true) to listOf(GURMUKHI, ENGLISH),
            Triple(false, true, true) to listOf(TRANSLITERATION, ENGLISH),
            Triple(true, false, false) to listOf(GURMUKHI),
            Triple(false, true, false) to listOf(TRANSLITERATION),
            Triple(false, false, true) to listOf(ENGLISH),
        )
        for ((state, expected) in cases) {
            val (g, t, e) = state
            assertEquals("toggles g:$g t:$t e:$e", expected, kinds(fullBenti, toggles(g, t, e)))
        }
    }

    @Test
    fun allTogglesOffFallsBackToFirstPopulatedLayer() {
        assertEquals(listOf(GURMUKHI), kinds(fullBenti, toggles(false, false, false)))
    }

    /** The fallback is the first *populated* layer, not simply Gurmukhi. */
    @Test
    fun fallbackPicksFirstPopulatedLayerNotGurmukhi() {
        val awaitingGurmukhi = BentiLayers(transliteration = "merī bentī", english = "my benti")
        assertEquals(listOf(TRANSLITERATION), kinds(awaitingGurmukhi, toggles(false, false, false)))
    }

    @Test
    fun englishOnlyBentiRendersOnlyEnglish() {
        assertEquals(listOf(ENGLISH), kinds(BentiLayers(english = "my benti"), toggles()))
    }

    @Test
    fun englishOnlyBentiSurvivesItsToggleBeingOff() {
        assertEquals(listOf(ENGLISH), kinds(BentiLayers(english = "my benti"), toggles(english = false)))
    }

    @Test
    fun gurmukhiBentiRendersGurmukhiAndTransliteration() {
        val benti = BentiLayers(gurmukhi = "ਮੇਰੀ ਬੇਨਤੀ", transliteration = "merī bentī")
        assertEquals(listOf(GURMUKHI, TRANSLITERATION), kinds(benti, toggles()))
        assertEquals(listOf(GURMUKHI), kinds(benti, toggles(transliteration = false)))
    }

    @Test
    fun gurmukhiOnlyBentiRendersOnlyGurmukhi() {
        val benti = BentiLayers(gurmukhi = "ਮੇਰੀ ਬੇਨਤੀ")
        assertEquals(listOf(GURMUKHI), kinds(benti, toggles()))
        assertEquals(listOf(GURMUKHI), kinds(benti, toggles(gurmukhi = false)))
    }

    @Test
    fun whitespaceOnlyLayerIsTreatedAsUnwritten() {
        val benti = BentiLayers(gurmukhi = "   \n", english = "my benti")
        assertEquals(listOf(ENGLISH), kinds(benti, toggles()))
        assertEquals(listOf(ENGLISH), kinds(benti, toggles(false, false, false)))
    }

    @Test
    fun emptyBentiYieldsNoLines() {
        assertEquals(emptyList<BentiLine>(), BentiLayers().visibleLines(toggles()))
    }

    @Test
    fun lineTextIsTheLayerText() {
        assertEquals(
            listOf("my benti"),
            fullBenti.visibleLines(toggles(gurmukhi = false, transliteration = false)).map { it.text },
        )
    }
}

class BentiHidesLayerTest {
    @Test
    fun switchingOffOneOfSeveralLayersHidesIt() {
        assertTrue(fullBenti.hidesLayer(ENGLISH, toggles()))
        assertTrue(fullBenti.hidesLayer(TRANSLITERATION, toggles()))
    }

    /** The pending-translation case: English is all the benti has, so its pill must be disabled. */
    @Test
    fun untranslatedBentiCannotHideItsOnlyLayer() {
        assertFalse(BentiLayers(english = "my benti").hidesLayer(ENGLISH, toggles()))
    }

    @Test
    fun gurmukhiOnlyBentiCannotHideGurmukhi() {
        assertFalse(BentiLayers(gurmukhi = "ਮੇਰੀ ਬੇਨਤੀ").hidesLayer(GURMUKHI, toggles()))
    }

    @Test
    fun lastLayerStandingIsUnhideable() {
        assertFalse(fullBenti.hidesLayer(GURMUKHI, toggles(transliteration = false, english = false)))
    }

    @Test
    fun layerHidesWhenTheFallbackWouldPickADifferentOne() {
        assertTrue(fullBenti.hidesLayer(ENGLISH, toggles(gurmukhi = false, transliteration = false)))
    }

    @Test
    fun alreadyHiddenLayerReportsNoChange() {
        assertFalse(fullBenti.hidesLayer(ENGLISH, toggles(english = false)))
        assertFalse(BentiLayers(english = "my benti").hidesLayer(GURMUKHI, toggles()))
    }
}

class LayerAvailabilityTest {
    /** Gurmukhi + transliteration, no English — no bundled variant since #60, but the rule is the model's. */
    private val noEnglishVariant =
        fixtureContent(listOf(ArdaasSegment("a", "ਗ", transliteration = "g", english = null)), slotAfter = "a")

    private val gurmukhiOnlyVariant =
        fixtureContent(listOf(ArdaasSegment("a", "ਗ", transliteration = null, english = null)), slotAfter = "a")

    private val fullVariant = fixtureContent(listOf(fixtureSegment("a")), slotAfter = "a")

    @Test
    fun canonicalAvailabilityIsTheVariantsOwnCoverage() {
        assertEquals(LayerAvailability(true, true), LayerAvailability.canonical(fullVariant))
        assertEquals(LayerAvailability(true, false), LayerAvailability.canonical(noEnglishVariant))
        assertEquals(LayerAvailability(false, false), LayerAvailability.canonical(gurmukhiOnlyVariant))
    }

    @Test
    fun canonicalAvailabilityAssumesCoverageBeforeContentLoads() {
        assertEquals(LayerAvailability(true, true), LayerAvailability.canonical(null))
    }

    @Test
    fun gurmukhiIsAlwaysCarried() {
        assertTrue(LayerAvailability.canonical(gurmukhiOnlyVariant).carries(GURMUKHI))
        assertFalse(LayerAvailability.canonical(gurmukhiOnlyVariant).carries(ENGLISH))
    }

    @Test
    fun canonicalCountIsZeroWhenOnlyUnavailableLayersAreOn() {
        assertEquals(0, LayerAvailability.canonical(gurmukhiOnlyVariant).visibleLayerCount(toggles(gurmukhi = false)))
    }

    @Test
    fun segmentRendersTheToggledLayersTheVariantCarries() {
        assertEquals(
            setOf(GURMUKHI, TRANSLITERATION, ENGLISH),
            LayerAvailability.canonical(fullVariant).renderedLayers(toggles()),
        )
        assertEquals(
            setOf(GURMUKHI, TRANSLITERATION),
            LayerAvailability.canonical(noEnglishVariant).renderedLayers(toggles()),
        )
        assertEquals(
            setOf(GURMUKHI, ENGLISH),
            LayerAvailability.canonical(fullVariant).renderedLayers(toggles(transliteration = false)),
        )
    }

    @Test
    fun segmentFallsBackToGurmukhiWhenNothingElseWouldRender() {
        assertEquals(
            setOf(GURMUKHI),
            LayerAvailability.canonical(gurmukhiOnlyVariant).renderedLayers(toggles(gurmukhi = false)),
        )
        assertEquals(
            setOf(GURMUKHI),
            LayerAvailability.canonical(fullVariant).renderedLayers(toggles(false, false, false)),
        )
    }

    @Test
    fun canonicalLayerHidesWhenTheVariantCarriesSomethingElse() {
        val availability = LayerAvailability.canonical(fullVariant)
        assertTrue(availability.hidesLayer(GURMUKHI, toggles()))
        assertTrue(availability.hidesLayer(ENGLISH, toggles()))
    }

    @Test
    fun gurmukhiCannotHideOnAGurmukhiOnlyVariant() {
        assertFalse(LayerAvailability.canonical(gurmukhiOnlyVariant).hidesLayer(GURMUKHI, toggles()))
    }

    @Test
    fun uncarriedLayerHidesNothingCanonically() {
        assertFalse(LayerAvailability.canonical(noEnglishVariant).hidesLayer(ENGLISH, toggles()))
    }

    /** #45: the benti's English renders on a variant without English, so its control must be offered. */
    @Test
    fun bentiEnglishMakesEnglishAvailableOnAVariantWithoutIt() {
        assertTrue(LayerAvailability.screen(noEnglishVariant, fullBenti).english)
        assertFalse(LayerAvailability.canonical(noEnglishVariant).english)
    }

    @Test
    fun englishStaysUnavailableWhenNeitherVariantNorBentiHasIt() {
        assertFalse(LayerAvailability.screen(noEnglishVariant, BentiLayers(gurmukhi = "ਮੇਰੀ ਬੇਨਤੀ")).english)
    }

    @Test
    fun bentiTransliterationMakesTransliterationAvailable() {
        val availability = LayerAvailability.screen(
            gurmukhiOnlyVariant,
            BentiLayers(gurmukhi = "ਮੇਰੀ ਬੇਨਤੀ", transliteration = "merī bentī"),
        )
        assertTrue(availability.transliteration)
        assertFalse(availability.english)
    }

    @Test
    fun screenAvailabilityIncludesTheVariantsCoverageWithABlankBenti() {
        assertEquals(LayerAvailability(true, true), LayerAvailability.screen(fullVariant, BentiLayers()))
    }

    @Test
    fun screenCountKeepsABentiOnlyLayerSwitchableOffTheLastLayer() {
        val availability = LayerAvailability.screen(noEnglishVariant, fullBenti)
        assertEquals(2, availability.visibleLayerCount(toggles(transliteration = false)))
        assertEquals(1, availability.visibleLayerCount(toggles(gurmukhi = false, transliteration = false)))
    }

    @Test
    fun countIgnoresLayersThatAreOnButUnavailable() {
        assertEquals(1, LayerAvailability.screen(gurmukhiOnlyVariant, BentiLayers()).visibleLayerCount(toggles()))
    }

    @Test
    fun countIsZeroWhenEverythingIsOff() {
        assertEquals(
            0,
            LayerAvailability.screen(fullVariant, fullBenti).visibleLayerCount(toggles(false, false, false)),
        )
    }
}

class LayerTogglesTest {
    @Test
    fun getReadsTheMatchingToggle() {
        val state = toggles(transliteration = false)
        assertTrue(state[GURMUKHI])
        assertFalse(state[TRANSLITERATION])
        assertTrue(state[ENGLISH])
    }

    @Test
    fun settingChangesOnlyTheNamedLayer() {
        assertEquals(toggles(english = false), toggles().setting(ENGLISH, false))
    }
}
