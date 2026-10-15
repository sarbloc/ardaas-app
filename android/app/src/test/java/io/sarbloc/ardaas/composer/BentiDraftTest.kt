package io.sarbloc.ardaas.composer

import io.sarbloc.ardaas.persistence.SavedArdaas
import io.sarbloc.ardaas.persistence.bentiLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of ArdaasTests/BentiDraftTests.swift, minus the translation engine.
 *
 * iOS fills [BentiDraft.gurmukhi] with `applyTranslation`; Android has no
 * translator, so the user types it — the cases below set it directly. The
 * staleness and translatability cases test engine state that does not exist
 * here and are not ported; every layer rule is, with iOS's assertions.
 */
class BentiDraftTest {

    // region English only

    /** Saving with no Gurmukhi writes the English layer and nothing else. */
    @Test
    fun typedEnglishWithoutGurmukhiIsEnglishOnly() {
        val draft = BentiDraft(typed = "Please bless my family.")
        assertEquals(BentiLayers(gurmukhi = "", transliteration = "", english = "Please bless my family."), draft.layers)
        assertEquals("", draft.transliteration)
        assertFalse(draft.hasGurmukhiEntry)
    }

    /** A record built the way Compose builds it round-trips back to the same layers. */
    @Test
    fun layersSurviveASavedArdaasRoundTrip() {
        val draft = BentiDraft(typed = "  Please bless my family.  ", gurmukhi = "ਸਿੰਘ ਅਰਦਾਸ")
        val layers = draft.layers
        val saved = SavedArdaas(
            label = "Family",
            bentiText = layers.english,
            bentiGurmukhi = layers.gurmukhi,
            bentiTransliteration = layers.transliteration,
        )
        assertEquals(layers, saved.bentiLayers)
        assertEquals("Please bless my family.", saved.bentiText)
        assertEquals("ਸਿੰਘ ਅਰਦਾਸ", saved.bentiGurmukhi)
        assertEquals("Singh Ardaas", saved.bentiTransliteration)
    }

    @Test
    fun whitespaceOnlyDraftIsEmpty() {
        assertTrue(BentiDraft(typed = "   \n ").isEmpty)
        assertTrue(BentiDraft().isEmpty)
        assertFalse(BentiDraft(typed = "a").isEmpty)
        // Gurmukhi with no English is still worth saving.
        assertFalse(BentiDraft(typed = "", gurmukhi = "ਅਰਦਾਸ").isEmpty)
        // …but whitespace in both editors is not.
        assertTrue(BentiDraft(typed = " ", gurmukhi = "\n ").isEmpty)
    }

    // endregion

    // region English plus Gurmukhi (iOS: a translated draft)

    @Test
    fun gurmukhiEntryFillsAllThreeLayers() {
        val draft = BentiDraft(typed = "Bless the sangat.", gurmukhi = "ਸਿੰਘ ਅਰਦਾਸ")
        assertEquals(
            BentiLayers(gurmukhi = "ਸਿੰਘ ਅਰਦਾਸ", transliteration = "Singh Ardaas", english = "Bless the sangat."),
            draft.layers,
        )
        assertTrue(draft.hasGurmukhiEntry)
    }

    /** The transliteration is derived, never stored, so editing the Gurmukhi regenerates it. */
    @Test
    fun editingTheGurmukhiRegeneratesTheTransliteration() {
        var draft = BentiDraft(typed = "Bless the sangat.", gurmukhi = "ਸਿੰਘ")
        assertEquals("Singh", draft.transliteration)

        draft = draft.copy(gurmukhi = "ਅਰਦਾਸ")
        assertEquals("Ardaas", draft.transliteration)
        assertEquals("Ardaas", draft.layers.transliteration)

        // Emptying it drops both layers rather than leaving a stale romanization.
        draft = draft.copy(gurmukhi = "   ")
        assertEquals("", draft.transliteration)
        assertEquals(BentiLayers(english = "Bless the sangat."), draft.layers)
        assertFalse(draft.hasGurmukhiEntry)
    }

    /** Deleting the English leaves a Gurmukhi-only benti, which saves fine. */
    @Test
    fun deletingTheEnglishKeepsTheGurmukhi() {
        val draft = BentiDraft(typed = "  ", gurmukhi = "ਅਰਦਾਸ")
        assertEquals(BentiLayers(gurmukhi = "ਅਰਦਾਸ", transliteration = "Ardaas", english = ""), draft.layers)
    }

    // endregion

    // region Gurmukhi typed in the benti editor

    /**
     * Gurmukhi in the benti editor *is* the Gurmukhi layer, the
     * transliteration is generated from it, and the English layer stays
     * empty — putting Gurmukhi in the English slot would mislabel it.
     */
    @Test
    fun gurmukhiInputIsTransliterated() {
        val draft = BentiDraft(typed = "ਸਿੰਘ ਅਰਦਾਸ")
        assertTrue(draft.isTypedInGurmukhi)
        assertEquals(
            BentiLayers(gurmukhi = "ਸਿੰਘ ਅਰਦਾਸ", transliteration = "Singh Ardaas", english = ""),
            draft.layers,
        )
    }

    /**
     * Rewriting the benti in Gurmukhi sets the Gurmukhi editor aside without
     * deleting it: the typed text wins, and undoing the rewrite brings the
     * editor's text back.
     */
    @Test
    fun gurmukhiInputOverridesTheGurmukhiEditor() {
        var draft = BentiDraft(typed = "Bless the sangat.", gurmukhi = "ਅਰਦਾਸ")

        draft = draft.copy(typed = "ਸਿੰਘ")
        assertFalse(draft.hasGurmukhiEntry)
        assertEquals(BentiLayers(gurmukhi = "ਸਿੰਘ", transliteration = "Singh", english = ""), draft.layers)

        draft = draft.copy(typed = "Bless the sangat.")
        assertTrue(draft.hasGurmukhiEntry)
        assertEquals("ਅਰਦਾਸ", draft.layers.gurmukhi)
    }

    /** Latin passes through the transliterator untouched, so a mostly-Gurmukhi benti survives intact. */
    @Test
    fun mixedGurmukhiMajorityKeepsItsLatinText() {
        val draft = BentiDraft(typed = "ਸਿੰਘ ਅਰਦਾਸ ok")
        assertTrue(draft.isTypedInGurmukhi)
        assertEquals("ਸਿੰਘ ਅਰਦਾਸ ok", draft.layers.gurmukhi)
        assertEquals("Singh Ardaas ok", draft.layers.transliteration)
    }

    // endregion

    /** Digits and punctuation are not Gurmukhi, and still save as English. */
    @Test
    fun undeterminedInputSavesAsEnglish() {
        val draft = BentiDraft(typed = "108 !!!")
        assertFalse(draft.isTypedInGurmukhi)
        assertEquals(BentiLayers(english = "108 !!!"), draft.layers)
    }
}
