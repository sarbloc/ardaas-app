package io.sarbloc.ardaas.ui.reader

import io.sarbloc.ardaas.composer.BentiLayers
import io.sarbloc.ardaas.composer.toggles
import io.sarbloc.ardaas.content.ArdaasSegment
import io.sarbloc.ardaas.content.LayerKind.ENGLISH
import io.sarbloc.ardaas.content.LayerKind.GURMUKHI
import io.sarbloc.ardaas.content.LayerKind.TRANSLITERATION
import io.sarbloc.ardaas.content.fixtureContent
import io.sarbloc.ardaas.content.fixtureSegment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which layer controls the Reader disables — iOS's `ReaderView.isPinnedLayer`. */
class ReaderControlsTest {
    private val fullVariant = fixtureContent(listOf(fixtureSegment("a")), slotAfter = "a")
    private val noEnglishVariant =
        fixtureContent(listOf(ArdaasSegment("a", "ਗ", transliteration = "g", english = null)), slotAfter = "a")
    private val gurmukhiOnlyVariant =
        fixtureContent(listOf(ArdaasSegment("a", "ਗ", transliteration = null, english = null)), slotAfter = "a")

    private val fullBenti = BentiLayers(gurmukhi = "ਮੇਰੀ ਬੇਨਤੀ", transliteration = "merī bentī", english = "my benti")
    private val untranslatedBenti = BentiLayers(english = "my benti")

    @Test
    fun nothingIsPinnedWhenEveryLayerCanBeHidden() {
        for (kind in listOf(GURMUKHI, TRANSLITERATION, ENGLISH)) {
            assertFalse("$kind", isPinnedLayer(kind, toggles(), fullVariant, fullBenti))
        }
    }

    /** A layer that is already off is never disabled — it can always be switched back on. */
    @Test
    fun aLayerThatIsOffIsNeverPinned() {
        val state = toggles(transliteration = false, english = false)
        assertFalse(isPinnedLayer(ENGLISH, state, gurmukhiOnlyVariant, BentiLayers()))
        assertFalse(isPinnedLayer(TRANSLITERATION, state, fullVariant, fullBenti))
    }

    /** The last layer visible anywhere on screen cannot be switched off. */
    @Test
    fun theLastVisibleLayerIsPinned() {
        assertTrue(isPinnedLayer(GURMUKHI, toggles(transliteration = false, english = false), fullVariant, fullBenti))
        assertTrue(isPinnedLayer(ENGLISH, toggles(gurmukhi = false, transliteration = false), fullVariant, fullBenti))
    }

    /**
     * On a Gurmukhi-only variant the segments' guard puts Gurmukhi straight
     * back, so its toggle is a no-op — even while the benti's English keeps the
     * screen-wide count above one.
     */
    @Test
    fun gurmukhiIsPinnedOnAGurmukhiOnlyVariant() {
        assertTrue(isPinnedLayer(GURMUKHI, toggles(), gurmukhiOnlyVariant, untranslatedBenti))
    }

    /**
     * The pending-translation case: on a variant with no English the only
     * English on screen is the benti's single written layer, which its
     * fallback restores — so the Translation pill is disabled, not dead.
     */
    @Test
    fun translationIsPinnedForAnUntranslatedBentiOnAVariantWithoutEnglish() {
        assertTrue(isPinnedLayer(ENGLISH, toggles(), noEnglishVariant, untranslatedBenti))
    }

    /** …but where the scripture carries English, switching it off does hide something. */
    @Test
    fun translationIsFreeWhenTheVariantCarriesEnglish() {
        assertFalse(isPinnedLayer(ENGLISH, toggles(), fullVariant, untranslatedBenti))
    }

    /** The pre-load frame assumes coverage, so no control is disabled before the text is known. */
    @Test
    fun nothingIsPinnedBeforeContentIsKnown() {
        for (kind in listOf(GURMUKHI, TRANSLITERATION, ENGLISH)) {
            assertFalse("$kind", isPinnedLayer(kind, toggles(), null, BentiLayers()))
        }
    }
}
