package io.sarbloc.ardaas.content

import java.io.ByteArrayInputStream
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of ArdaasTests/ArdaasContentTests.swift, case for case. Bundled-content
 * tests run against the real packaged JSON — CI-level protection of the
 * content invariants the app relies on.
 */
class ArdaasContentTest {

    private companion object {
        /** One ellipsis character followed by two full stops (U+2026 ".."), not five dots. */
        const val SLOT_MARKER = "….."
        const val ENGLISH_PLACEHOLDER = "$SLOT_MARKER (mention here the paath recited or the occasion)"
    }

    private val library by lazy { BundledAssets.library() }

    private fun content(variantId: String) = requireNotNull(library.variant(variantId)).content

    // region Bundled content

    @Test
    fun bundledLibraryLoadsAndValidates() {
        assertEquals(listOf("sgpc", "buddha-dal"), library.variants.map { it.id })
        assertEquals(ArdaasLibrary.DEFAULT_VARIANT_ID, library.defaultVariant.id)
    }

    @Test
    fun bundledBuddhaDalContent() {
        val content = content("buddha-dal")
        assertEquals(16, content.segments.size)
        assertTrue(content.hasTransliteration)
        assertTrue(content.hasEnglish)
        // hasTransliteration/hasEnglish read the first segment only; assert the full counts too.
        assertEquals(16, content.segments.count { it.transliteration != null })
        assertEquals(16, content.segments.count { it.english != null })
        assertEquals("he-deen-dayal", content.bentiSlot.afterSegmentId)
        val ids = content.segments.map { it.id }
        val slotIndex = ids.indexOf("he-deen-dayal")
        assertTrue(slotIndex >= 0)
        assertTrue(content.segments[slotIndex].gurmukhi.contains("ਆਪ ਜੀ ਦੇ ਹਜ਼ੂਰ…..ਦੀ ਅਰਦਾਸ"))
        assertEquals("sikh-parhde-sunde", ids[slotIndex + 1])
        assertEquals("jaikara", ids.last())
    }

    @Test
    fun bundledSgpcContent() {
        val content = content("sgpc")
        assertEquals(1, content.version)
        assertEquals(19, content.segments.size)
        assertFalse(content.sources.isEmpty())
        assertEquals("nimaniyan-de-maan", content.bentiSlot.afterSegmentId)
        assertTrue(content.hasTransliteration)
        assertTrue(content.hasEnglish)
    }

    @Test
    fun bundledSlotFollowsIntactSentence() {
        val content = content("sgpc")
        val ids = content.segments.map { it.id }
        val slotIndex = ids.indexOf(content.bentiSlot.afterSegmentId)
        assertTrue(slotIndex >= 0)
        assertTrue(content.segments[slotIndex].gurmukhi.endsWith("ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥"))
        assertEquals("bhul-chuk-maaf", ids[slotIndex + 1])
    }

    @Test
    fun resolvedVariantFallsBackToDefaultForUnknownId() {
        assertEquals("sgpc", library.resolvedVariant("no-such-variant").id)
    }

    // endregion

    // region validate()

    @Test
    fun validateRejectsDuplicateIds() {
        val content = fixtureContent(listOf(fixtureSegment("a"), fixtureSegment("a")), slotAfter = "a")
        assertValidationFails(ArdaasContentException.DuplicateSegmentIds, content)
    }

    @Test
    fun validateRejectsUnknownSlot() {
        val content = fixtureContent(listOf(fixtureSegment("a")), slotAfter = "missing")
        assertValidationFails(ArdaasContentException.InvalidSlot("missing"), content)
    }

    @Test
    fun validateRejectsEmptyLayer() {
        val content = fixtureContent(
            listOf(fixtureSegment("a"), ArdaasSegment("b", "ਬ", transliteration = "", english = "b")),
            slotAfter = "a",
        )
        assertValidationFails(ArdaasContentException.EmptyLayer("b"), content)
    }

    @Test
    fun validateRejectsInconsistentLayerCoverage() {
        val content = fixtureContent(
            listOf(fixtureSegment("a"), ArdaasSegment("b", "ਬ", transliteration = null, english = "e")),
            slotAfter = "a",
        )
        assertValidationFails(ArdaasContentException.InconsistentLayer("transliteration"), content)
    }

    @Test
    fun gurmukhiOnlyVariantValidates() {
        val content = fixtureContent(
            listOf(ArdaasSegment("a", "ਗ"), ArdaasSegment("b", "ਬ")),
            slotAfter = "a",
        )
        content.validate()
        assertFalse(content.hasTransliteration)
        assertFalse(content.hasEnglish)
    }

    // endregion

    // region The occasion slot

    /** Only this stops a dropped or typo'd `occasionSlot` key shipping as "the picker does nothing". */
    @Test
    fun bundledVariantsDeclareTheOccasionSlot() {
        for (variant in library.variants) {
            val slot = requireNotNull(variant.content.occasionSlot) { "${variant.id} declares no occasion slot" }
            // The occasion sits inside the same sentence the benti follows.
            assertEquals(variant.content.bentiSlot.afterSegmentId, slot.inSegmentId)
            assertEquals(SLOT_MARKER, slot.placeholder.gurmukhi)
        }
    }

    /** SGPC's Roman layer has a bare `…`; both English layers carry the instruction. */
    @Test
    fun bundledPlaceholdersAreDeclaredPerLayer() {
        val sgpc = content("sgpc").occasionSlot
        assertEquals("…", sgpc?.placeholder?.transliteration)
        assertEquals(ENGLISH_PLACEHOLDER, sgpc?.placeholder?.english)
        val buddhaDal = content("buddha-dal").occasionSlot
        assertEquals(SLOT_MARKER, buddhaDal?.placeholder?.transliteration)
        assertEquals(ENGLISH_PLACEHOLDER, buddhaDal?.placeholder?.english)
    }

    @Test
    fun validateRejectsUnknownOccasionSlotSegment() {
        val content = fixtureContent(
            listOf(fixtureSegment("a")), slotAfter = "a",
            occasionSlot = OccasionSlot("missing", OccasionPlaceholder(SLOT_MARKER)),
        )
        assertValidationFails(ArdaasContentException.InvalidOccasionSlot("missing"), content)
    }

    /** A declaration that no longer matches the text fails instead of silently substituting nothing. */
    @Test
    fun validateRejectsAPlaceholderThatIsNotInTheText() {
        val content = fixtureContent(
            listOf(ArdaasSegment("a", "ਹਜ਼ੂਰ${SLOT_MARKER}ਦੀ", transliteration = "Hazur Di")),
            slotAfter = "a",
            occasionSlot = fixtureSlot("a", transliteration = SLOT_MARKER),
        )
        assertValidationFails(
            ArdaasContentException.OccasionPlaceholderNotFound("a", LayerKind.TRANSLITERATION),
            content,
        )
    }

    /** No text for it to be absent from — the same defect. */
    @Test
    fun validateRejectsAPlaceholderForAnAbsentLayer() {
        val content = fixtureContent(
            listOf(ArdaasSegment("a", "ਹਜ਼ੂਰ${SLOT_MARKER}ਦੀ")),
            slotAfter = "a",
            occasionSlot = fixtureSlot("a", english = SLOT_MARKER),
        )
        assertValidationFails(
            ArdaasContentException.OccasionPlaceholderNotFound("a", LayerKind.ENGLISH),
            content,
        )
    }

    /** Two markers make "the slot" ambiguous; substitution would fill only the first. */
    @Test
    fun validateRejectsADuplicatedPlaceholder() {
        val content = fixtureContent(
            listOf(ArdaasSegment("a", "ਹਜ਼ੂਰ${SLOT_MARKER}ਦੀ${SLOT_MARKER}ਅਰਦਾਸ")),
            slotAfter = "a",
            occasionSlot = fixtureSlot("a"),
        )
        assertValidationFails(
            ArdaasContentException.OccasionPlaceholderNotUnique("a", LayerKind.GURMUKHI),
            content,
        )
    }

    @Test
    fun validateRejectsAnEmptyPlaceholder() {
        val content = fixtureContent(
            listOf(fixtureSegment("a")), slotAfter = "a",
            occasionSlot = fixtureSlot("a", gurmukhi = ""),
        )
        assertValidationFails(ArdaasContentException.EmptyOccasionPlaceholder(LayerKind.GURMUKHI), content)
    }

    @Test
    fun variantWithoutAnOccasionSlotValidates() {
        val content = fixtureContent(listOf(fixtureSegment("a")), slotAfter = "a")
        assertNull(content.occasionSlot)
        content.validate()
    }

    // endregion

    // region Library

    @Test
    fun libraryValidateRejectsDuplicateVariantIds() {
        val variant = ArdaasVariant("sgpc", "A", fixtureContent(listOf(fixtureSegment("a")), slotAfter = "a"))
        val error = assertThrows(ArdaasLibraryException::class.java) {
            ArdaasLibrary(listOf(variant, variant)).validate()
        }
        assertEquals(ArdaasLibraryException.DuplicateVariantIds, error)
    }

    @Test
    fun libraryValidateRequiresDefaultVariant() {
        val library = ArdaasLibrary(
            listOf(ArdaasVariant("taksali", "T", fixtureContent(listOf(fixtureSegment("a")), slotAfter = "a"))),
        )
        val error = assertThrows(ArdaasLibraryException::class.java) { library.validate() }
        assertEquals(ArdaasLibraryException.MissingDefaultVariant, error)
    }

    // endregion

    // region Android-only: the loader (iOS covers these through Bundle lookup)

    @Test
    fun loadFailsWhenTheManifestIsMissing() {
        val error = assertThrows(ArdaasLibraryException::class.java) { ArdaasLibrary.load { null } }
        assertEquals(ArdaasLibraryException.ManifestMissing, error)
    }

    @Test
    fun loadFailsWhenAVariantResourceIsMissing() {
        val error = assertThrows(ArdaasLibraryException::class.java) {
            ArdaasLibrary.load { name -> if (name == "variants.json") BundledAssets.open(name) else null }
        }
        assertEquals(ArdaasLibraryException.VariantResourceMissing("sgpc", "ardaas-sgpc"), error)
    }

    /** Stricter than iOS on purpose: a typo'd key fails the load instead of decoding as "absent". */
    @Test
    fun loadRejectsAnUnknownKey() {
        val sgpc = requireNotNull(BundledAssets.open("ardaas-sgpc.json")).use { it.readBytes().decodeToString() }
        val typo = sgpc.replaceFirst("\"occasionSlot\"", "\"occassionSlot\"")
        check(typo != sgpc)
        assertThrows(SerializationException::class.java) {
            ArdaasLibrary.load { name ->
                if (name == "ardaas-sgpc.json") ByteArrayInputStream(typo.toByteArray()) else BundledAssets.open(name)
            }
        }
    }

    @Test
    fun countOccurrencesIsNonOverlapping() {
        // Matches iOS's components(separatedBy:).count - 1.
        assertEquals(1, "a….....b".countOccurrences("….."))
        assertEquals(2, "x…..y…..z".countOccurrences("….."))
        assertEquals(0, "abc".countOccurrences("….."))
        assertEquals(2, "aaaa".countOccurrences("aa"))
    }

    // endregion

    private fun assertValidationFails(expected: ArdaasContentException, content: ArdaasContent) {
        val error = assertThrows(ArdaasContentException::class.java) { content.validate() }
        assertEquals(expected, error)
    }
}
