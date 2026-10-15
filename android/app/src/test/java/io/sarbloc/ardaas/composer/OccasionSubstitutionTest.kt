package io.sarbloc.ardaas.composer

import io.sarbloc.ardaas.content.ArdaasContent
import io.sarbloc.ardaas.content.ArdaasSegment
import io.sarbloc.ardaas.content.BundledAssets
import io.sarbloc.ardaas.content.LayerKind
import io.sarbloc.ardaas.content.OccasionPlaceholder
import io.sarbloc.ardaas.content.OccasionSlot
import io.sarbloc.ardaas.content.fixtureContent
import io.sarbloc.ardaas.content.fixtureSegment
import io.sarbloc.ardaas.content.fixtureSlot
import io.sarbloc.ardaas.occasion.OccasionChoice
import io.sarbloc.ardaas.occasion.OccasionLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The slot marker as the bundled variants spell it: one ellipsis character followed by two full stops. */
private const val MARKER = "….."

/**
 * Port of ArdaasTests/OccasionSubstitutionTests.swift, case for case.
 *
 * Composition-time substitution of a chosen occasion into the "….." slot.
 * Pure logic, so it is tested against both hand-built fixtures (for the
 * rules) and the real bundled variants (for the sentences readers see).
 */
class OccasionSubstitutionTest {
    private val japji = OccasionLayers(
        gurmukhi = "ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ",
        transliteration = "Jap Ji Saahib De Paath",
        english = "the recitation of Japji Sahib",
    )

    private val library by lazy { BundledAssets.library() }

    /** A three-layer variant whose slot segment is written tight against the marker, exactly like the bundled ones. */
    private fun content(
        gurmukhi: String = "ਆਪ ਦੇ ਹਜ਼ੂਰ${MARKER}ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥",
        transliteration: String? = "Aap De Hazur${MARKER}Di Ardas Hai Ji ||",
        english: String? = "we humbly make prayer in your presence$MARKER (mention here).",
        placeholder: OccasionPlaceholder = OccasionPlaceholder(
            gurmukhi = MARKER, transliteration = MARKER, english = "$MARKER (mention here)",
        ),
    ): ArdaasContent = fixtureContent(
        listOf(
            fixtureSegment("before"),
            ArdaasSegment("slot", gurmukhi, transliteration = transliteration, english = english),
            fixtureSegment("after"),
        ),
        slotAfter = "slot",
        occasionSlot = OccasionSlot("slot", placeholder),
    )

    /** Composes and returns the slot segment as rendered. */
    private fun slotSegment(content: ArdaasContent, occasion: OccasionLayers?, id: String = "slot"): ArdaasSegment =
        ArdaasComposer.compose(content, benti = null, occasion = occasion)
            .filterIsInstance<RenderItem.Canonical>()
            .map { it.segment }
            .firstOrNull { it.id == id }
            ?: throw AssertionError("no segment '$id' in the composed sequence")

    // region The substitution rule

    @Test
    fun eachLayerTakesItsOwnLayerOfTheOccasion() {
        val segment = slotSegment(content(), japji)
        assertEquals("ਆਪ ਦੇ ਹਜ਼ੂਰ ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥", segment.gurmukhi)
        assertEquals("Aap De Hazur Jap Ji Saahib De Paath Di Ardas Hai Ji ||", segment.transliteration)
        assertEquals("we humbly make prayer in your presence the recitation of Japji Sahib.", segment.english)
    }

    /**
     * The operator's decision for an unfilled slot: the dots stay exactly as
     * authored, so the reader still sees where to say their own words.
     */
    @Test
    fun nothingChosenLeavesThePlaceholderUntouched() {
        val original = content()
        val segment = slotSegment(original, null)
        assertEquals(original.segments[1], segment)
        assertTrue(segment.gurmukhi.contains(MARKER))
    }

    @Test
    fun anAllBlankOccasionLeavesThePlaceholderUntouched() {
        val original = content()
        val segment = slotSegment(original, OccasionLayers())
        assertEquals(original.segments[1], segment)
    }

    /**
     * An occasion with no words for a layer leaves *that* layer's dots
     * alone. Blanking the slot would delete part of a canonical sentence.
     */
    @Test
    fun aLayerTheOccasionLacksKeepsItsDots() {
        val gurmukhiOnly = OccasionLayers(gurmukhi = "ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ")
        val segment = slotSegment(content(), gurmukhiOnly)
        assertFalse(segment.gurmukhi.contains(MARKER))
        assertEquals("Aap De Hazur${MARKER}Di Ardas Hai Ji ||", segment.transliteration)
        assertTrue(requireNotNull(segment.english).contains(MARKER))
    }

    /**
     * A layer the *variant* lacks stays absent — never an empty string,
     * which `ArdaasContent.validate()`'s all-or-none rule would reject and
     * the Reader would render as a blank line.
     */
    @Test
    fun aLayerTheVariantLacksStaysNil() {
        val gurmukhiOnly = content(
            transliteration = null,
            english = null,
            placeholder = OccasionPlaceholder(gurmukhi = MARKER, transliteration = null, english = null),
        )
        val segment = slotSegment(gurmukhiOnly, japji)
        assertFalse(segment.gurmukhi.contains(MARKER))
        assertNull(segment.transliteration)
        assertNull(segment.english)
    }

    /**
     * An undeclared placeholder for a layer is not guessed at: no marker is
     * searched for, so that layer is left exactly as authored.
     */
    @Test
    fun anUndeclaredLayerPlaceholderIsNotSubstituted() {
        val partial = content(
            placeholder = OccasionPlaceholder(gurmukhi = MARKER, transliteration = null, english = null),
        )
        val segment = slotSegment(partial, japji)
        assertFalse(segment.gurmukhi.contains(MARKER))
        assertEquals("Aap De Hazur${MARKER}Di Ardas Hai Ji ||", segment.transliteration)
    }

    @Test
    fun otherSegmentsAreUntouched() {
        val items = ArdaasComposer.compose(content(), benti = null, occasion = japji)
        assertEquals(3, items.size)
        assertEquals(RenderItem.Canonical(fixtureSegment("before")), items.first())
        assertEquals(RenderItem.Canonical(fixtureSegment("after")), items.last())
    }

    /**
     * Composition is pure: the content a caller holds must come back
     * canonical, or a second read would render an occasion nobody chose.
     */
    @Test
    fun compositionDoesNotMutateTheContent() {
        val original = content()
        val before = original.copy(segments = original.segments.toList())
        ArdaasComposer.compose(original, benti = null, occasion = japji)
        assertEquals(before, original)
        assertTrue(original.segments[1].gurmukhi.contains(MARKER))
    }

    /** A variant with no declared slot never substitutes, and does not crash. */
    @Test
    fun aVariantWithoutASlotIsUnchanged() {
        val noSlot = fixtureContent(listOf(fixtureSegment("a")), slotAfter = "a")
        assertEquals(
            listOf(RenderItem.Canonical(fixtureSegment("a"))),
            ArdaasComposer.compose(noSlot, benti = null, occasion = japji),
        )
    }

    /**
     * An occasion slot naming a segment that isn't there is a no-op too —
     * unreachable for bundled content, which `validate()` rejects at load.
     */
    @Test
    fun anUnknownSlotSegmentIsANoOp() {
        val broken = fixtureContent(
            listOf(fixtureSegment("a")), slotAfter = "a",
            occasionSlot = fixtureSlot("missing"),
        )
        assertEquals(
            listOf(RenderItem.Canonical(fixtureSegment("a"))),
            ArdaasComposer.compose(broken, benti = null, occasion = japji),
        )
    }

    /**
     * The benti still goes in after its own slot segment, with the occasion
     * spliced into that segment's text — the two slots are independent.
     */
    @Test
    fun theBentiStillComposesAlongsideAnOccasion() {
        val benti = BentiLayers(english = "my benti")
        val items = ArdaasComposer.compose(content(), benti = benti, occasion = japji)
        assertEquals(4, items.size)
        assertEquals(RenderItem.Benti(benti), items[2])
        val segment = (items[1] as? RenderItem.Canonical)?.segment
            ?: throw AssertionError("expected the slot segment before the benti")
        assertTrue(segment.gurmukhi.contains("ਹਜ਼ੂਰ ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਦੀ"))
    }

    // endregion

    // region Seams

    /**
     * The dots stand in for the spaces around the words as well as for the
     * words, so the occasion is spaced into a tight sentence…
     */
    @Test
    fun tightSeamsGainASingleSpace() {
        val segment = slotSegment(content(), japji)
        assertTrue(segment.gurmukhi.contains("ਹਜ਼ੂਰ ਜਪੁ"))
        assertTrue(segment.gurmukhi.contains("ਪਾਠ ਦੀ ਅਰਦਾਸ"))
    }

    /**
     * …and whitespace already at a seam is absorbed rather than doubled —
     * SGPC's Roman layer writes a bare `…` followed by a space.
     */
    @Test
    fun existingWhitespaceAtASeamIsNotDoubled() {
        val spaced = content(
            transliteration = "Aap De Hazur… Di Ardas ||",
            english = null,
            placeholder = OccasionPlaceholder(gurmukhi = MARKER, transliteration = "…", english = null),
        )
        val segment = slotSegment(spaced, japji)
        assertEquals("Aap De Hazur Jap Ji Saahib De Paath Di Ardas ||", segment.transliteration)
    }

    /** No space is added before punctuation: SGPC's English placeholder is followed by the sentence's full stop. */
    @Test
    fun punctuationAfterTheSlotIsNotPushedAway() {
        val segment = slotSegment(content(), japji)
        assertTrue(requireNotNull(segment.english).endsWith("Japji Sahib."))
    }

    /** A placeholder that opens the text gains no leading space. */
    @Test
    fun aPlaceholderAtTheStartGainsNoLeadingSpace() {
        val leading = content(
            gurmukhi = "${MARKER}ਦੀ ਅਰਦਾਸ",
            transliteration = null,
            english = null,
            placeholder = OccasionPlaceholder(gurmukhi = MARKER, transliteration = null, english = null),
        )
        val segment = slotSegment(leading, japji)
        assertEquals("ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਦੀ ਅਰਦਾਸ", segment.gurmukhi)
    }

    // endregion

    // region The bundled variants

    @Test
    fun bundledSgpcSentenceReadsCorrectlyWithAnOccasion() {
        val content = requireNotNull(library.variant("sgpc")).content
        val segment = slotSegment(content, japji, id = "nimaniyan-de-maan")
        assertTrue(segment.gurmukhi, segment.gurmukhi.endsWith("ਆਪ ਦੇ ਹਜ਼ੂਰ ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥"))
        assertTrue(
            requireNotNull(segment.transliteration).contains("Hazur Jap Ji Saahib De Paath Di Ardaas Hai Ji"),
        )
        // The English placeholder swallows the parenthetical instruction:
        // once an occasion is named, "(mention here …)" is stale.
        val english = requireNotNull(segment.english)
        assertTrue(english, english.endsWith("in your presence the recitation of Japji Sahib."))
        assertFalse(english.contains("mention here"))
    }

    @Test
    fun bundledBuddhaDalSentenceReadsCorrectlyWithAnOccasion() {
        val content = requireNotNull(library.variant("buddha-dal")).content
        val segment = slotSegment(content, japji, id = "he-deen-dayal")
        assertTrue(segment.gurmukhi, segment.gurmukhi.contains("ਆਪ ਜੀ ਦੇ ਹਜ਼ੂਰ ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਦੀ ਅਰਦਾਸ"))
        assertTrue(requireNotNull(segment.transliteration).contains("Hazur Jap Ji Saahib De Paath Di Ardaas"))
        // Since #60 there is an English layer, and its placeholder swallows
        // the same parenthetical instruction SGPC's does.
        val english = requireNotNull(segment.english)
        assertTrue(
            english,
            english.endsWith("the prayer of the recitation of Japji Sahib is laid at your lotus feet,"),
        )
        assertFalse(english.contains("mention here"))
    }

    /**
     * Every bundled entry has to read into every bundled variant, in every
     * layer that variant declares a placeholder for: the genitive still closes
     * with `ਦੀ ਅਰਦਾਸ`, and no dots are left behind.
     */
    @Test
    fun everyBundledOccasionSubstitutesIntoEveryVariant() {
        val catalog = BundledAssets.occasionCatalog()
        for (variant in library.variants) {
            val slot = requireNotNull(variant.content.occasionSlot) { "${variant.id} declares no occasion slot" }
            for (occasion in catalog.occasions) {
                val layers = OccasionChoice.Catalog(occasion.id).layers(catalog)
                val segment = slotSegment(variant.content, layers, id = slot.inSegmentId)
                assertTrue(
                    "${occasion.id} does not read into ${variant.id}: ${segment.gurmukhi}",
                    segment.gurmukhi.contains("${occasion.gurmukhi} ਦੀ ਅਰਦਾਸ"),
                )
                for (layer in LayerKind.entries) {
                    if (slot.placeholder[layer] == null) continue
                    val text = segment.text(layer) ?: continue
                    assertFalse(
                        "${occasion.id} left dots in ${variant.id}'s $layer layer: $text",
                        text.contains("…"),
                    )
                }
            }
        }
    }

    // endregion

    // region Android-only: occasionSlotSegment (iOS covers it through OccasionDraft, #85)

    @Test
    fun occasionSlotSegmentQuotesTheSentenceAsComposed() {
        val content = content()
        assertEquals(slotSegment(content, japji), ArdaasComposer.occasionSlotSegment(content, japji))
        assertEquals(content.segments[1], ArdaasComposer.occasionSlotSegment(content, null))
    }

    @Test
    fun occasionSlotSegmentIsNullWithoutAUsableSlot() {
        val noSlot = fixtureContent(listOf(fixtureSegment("a")), slotAfter = "a")
        assertNull(ArdaasComposer.occasionSlotSegment(noSlot, japji))
        val unknown = fixtureContent(listOf(fixtureSegment("a")), slotAfter = "a", occasionSlot = fixtureSlot("missing"))
        assertNull(ArdaasComposer.occasionSlotSegment(unknown, japji))
    }

    // endregion

    // region Android-only: seams by iOS's character classes

    /** A number continues the sentence as a word does (iOS: `isLetter || isNumber`). */
    @Test
    fun aNumberAfterTheSlotGainsASpace() {
        val segment = slotSegment(gurmukhiOnly("ਹਜ਼ੂਰ${MARKER}੧੦ ਦਿਨ"), japji)
        assertEquals("ਹਜ਼ੂਰ ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ੧੦ ਦਿਨ", segment.gurmukhi)
    }

    /** NBSP and NEXT LINE are whitespace on iOS, so a seam absorbs them too. */
    @Test
    fun iosWhitespaceAtASeamIsAbsorbed() {
        val segment = slotSegment(gurmukhiOnly("ਹਜ਼ੂਰ ${MARKER}\u0085ਦੀ"), japji)
        assertEquals("ਹਜ਼ੂਰ ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਦੀ", segment.gurmukhi)
    }

    /** A placeholder that closes the text gains no trailing space. */
    @Test
    fun aPlaceholderAtTheEndGainsNoTrailingSpace() {
        val segment = slotSegment(gurmukhiOnly("ਹਜ਼ੂਰ $MARKER "), japji)
        assertEquals("ਹਜ਼ੂਰ ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ", segment.gurmukhi)
    }

    private fun gurmukhiOnly(gurmukhi: String) = content(
        gurmukhi = gurmukhi,
        transliteration = null,
        english = null,
        placeholder = OccasionPlaceholder(gurmukhi = MARKER, transliteration = null, english = null),
    )

    // endregion
}
