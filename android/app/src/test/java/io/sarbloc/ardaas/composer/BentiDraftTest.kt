package io.sarbloc.ardaas.composer

import io.sarbloc.ardaas.persistence.SavedArdaas
import io.sarbloc.ardaas.persistence.bentiLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of ArdaasTests/BentiDraftTests.swift: which layers a draft produces,
 * and how they change as the user translates, edits or rewrites.
 *
 * Everything here is pure — no model, no ONNX Runtime, no network — which is
 * the point: the translation *call* cannot run in CI, but every rule around it
 * can. [BentiDraft.applyTranslation] stands in for "the engine returned this
 * string".
 */
class BentiDraftTest {

    // region English only (the pre-translation behaviour)

    /** Saving without translating writes the English layer and nothing else. */
    @Test
    fun typedEnglishWithoutTranslationIsEnglishOnly() {
        val draft = BentiDraft(typed = "Please bless my family.")
        assertEquals(BentiLayers(gurmukhi = "", transliteration = "", english = "Please bless my family."), draft.layers)
        assertEquals("", draft.transliteration)
        assertFalse(draft.hasTranslationDraft)
        assertTrue(draft.isTranslatable)
    }

    /** A record built the way Compose builds it round-trips back to the same layers. */
    @Test
    fun layersSurviveASavedArdaasRoundTrip() {
        val typed = BentiDraft(typed = "  Please bless my family.  ")
        val draft = typed.applyTranslation("ਸਿੰਘ ਅਰਦਾਸ", typed.translationSource)
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
        // A translation with the English deleted is still worth saving.
        assertFalse(BentiDraft(typed = "", gurmukhi = "ਅਰਦਾਸ").isEmpty)
        // …but whitespace in both editors is not.
        assertTrue(BentiDraft(typed = " ", gurmukhi = "\n ").isEmpty)
    }

    // endregion

    // region Translated English

    @Test
    fun translationFillsAllThreeLayers() {
        val draft = translated("Bless the sangat.", "ਸਿੰਘ ਅਰਦਾਸ")
        assertEquals(
            BentiLayers(gurmukhi = "ਸਿੰਘ ਅਰਦਾਸ", transliteration = "Singh Ardaas", english = "Bless the sangat."),
            draft.layers,
        )
        assertTrue(draft.hasTranslationDraft)
    }

    /**
     * The transliteration is derived, never stored, so editing the Gurmukhi
     * regenerates it — the whole point of letting the user correct the
     * machine's output before saving.
     */
    @Test
    fun editingTheGurmukhiRegeneratesTheTransliteration() {
        var draft = translated("Bless the sangat.", "ਸਿੰਘ")
        assertEquals("Singh", draft.transliteration)

        draft = draft.copy(gurmukhi = "ਅਰਦਾਸ")
        assertEquals("Ardaas", draft.transliteration)
        assertEquals("Ardaas", draft.layers.transliteration)

        // Emptying it drops both layers rather than leaving a stale romanization.
        draft = draft.copy(gurmukhi = "   ")
        assertEquals("", draft.transliteration)
        assertEquals(BentiLayers(english = "Bless the sangat."), draft.layers)
        assertFalse(draft.hasTranslationDraft)
    }

    @Test
    fun clearingTheTranslationReturnsToEnglishOnly() {
        val draft = translated("Bless the sangat.", "ਸਿੰਘ").clearTranslation()
        assertEquals(BentiLayers(english = "Bless the sangat."), draft.layers)
        assertNull(draft.translatedFrom)
        assertFalse(draft.isStale)
    }

    /** Both sides are recorded trimmed, as iOS's `applyTranslation` does. */
    @Test
    fun applyTranslationTrimsTheResultAndTheSource() {
        val draft = BentiDraft(typed = "Bless us.").applyTranslation("  ਅਰਦਾਸ \n", "  Bless us. ")
        assertEquals("ਅਰਦਾਸ", draft.gurmukhi)
        assertEquals("Bless us.", draft.translatedFrom)
        assertEquals("Bless us.", draft.typed)
    }

    // endregion

    // region Staleness

    @Test
    fun draftIsStaleOnlyAfterTheEnglishChanges() {
        var draft = translated("Bless the sangat.", "ਸਿੰਘ")
        assertFalse(draft.isStale)

        // Whitespace-only edits are not a change: both sides are trimmed.
        draft = draft.copy(typed = "  Bless the sangat.  ")
        assertFalse(draft.isStale)

        draft = draft.copy(typed = "Bless the whole sangat.")
        assertTrue(draft.isStale)

        // Editing the Gurmukhi is not what staleness tracks — that is the user
        // correcting the machine, and it must not be flagged as out of date.
        draft = draft.copy(typed = "Bless the sangat.", gurmukhi = "ਅਰਦਾਸ")
        assertFalse(draft.isStale)
    }

    /**
     * Deleting the English is not staleness: there is nothing left for the
     * Gurmukhi to disagree with, and a Gurmukhi-only benti saves fine.
     */
    @Test
    fun deletingTheEnglishIsNotStale() {
        val draft = translated("Bless the sangat.", "ਅਰਦਾਸ").copy(typed = "  ")
        assertFalse(draft.isStale)
        assertEquals(BentiLayers(gurmukhi = "ਅਰਦਾਸ", transliteration = "Ardaas", english = ""), draft.layers)
    }

    @Test
    fun untranslatedDraftIsNeverStale() {
        assertFalse(BentiDraft(typed = "Bless the sangat.").isStale)
        val draft = translated("Bless the sangat.", "ਸਿੰਘ").copy(gurmukhi = "", typed = "Something else entirely.")
        assertFalse(draft.isStale)
    }

    // endregion

    // region Gurmukhi typed directly

    /**
     * Gurmukhi input skips translation entirely: the typed text *is* the
     * Gurmukhi layer, the transliteration is generated from it, and the
     * English layer stays empty — nothing here translates Gurmukhi back to
     * English, and putting Gurmukhi in the English slot would mislabel it.
     */
    @Test
    fun gurmukhiInputIsTransliteratedNotTranslated() {
        val draft = BentiDraft(typed = "ਸਿੰਘ ਅਰਦਾਸ")
        assertTrue(draft.isTypedInGurmukhi)
        assertFalse(draft.isTranslatable)
        assertEquals(
            BentiLayers(gurmukhi = "ਸਿੰਘ ਅਰਦਾਸ", transliteration = "Singh Ardaas", english = ""),
            draft.layers,
        )
    }

    /**
     * Rewriting an English benti in Gurmukhi retires the old translation
     * without deleting it: the typed text wins, so nothing stale can be saved,
     * and undoing the rewrite brings the draft back.
     */
    @Test
    fun gurmukhiInputOverridesAnyExistingTranslationDraft() {
        var draft = translated("Bless the sangat.", "ਅਰਦਾਸ")

        draft = draft.copy(typed = "ਸਿੰਘ")
        assertFalse(draft.hasTranslationDraft)
        assertFalse(draft.isStale)
        assertEquals(BentiLayers(gurmukhi = "ਸਿੰਘ", transliteration = "Singh", english = ""), draft.layers)

        draft = draft.copy(typed = "Bless the sangat.")
        assertTrue(draft.hasTranslationDraft)
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

    // region Nothing to translate

    /**
     * Digits and punctuation are undetermined: not translatable, and there is
     * no Gurmukhi to transliterate either. They still save as English.
     */
    @Test
    fun undeterminedInputIsNotTranslatable() {
        val draft = BentiDraft(typed = "108 !!!")
        assertFalse(draft.isTranslatable)
        assertFalse(draft.isTypedInGurmukhi)
        assertEquals(BentiLayers(english = "108 !!!"), draft.layers)
    }

    @Test
    fun translationSourceIsTheTrimmedTypedText() {
        assertEquals("Bless the sangat.", BentiDraft(typed = "  Bless the sangat.\n").translationSource)
    }

    // endregion

    private fun translated(english: String, gurmukhi: String): BentiDraft {
        val draft = BentiDraft(typed = english)
        return draft.applyTranslation(gurmukhi, draft.translationSource)
    }
}
