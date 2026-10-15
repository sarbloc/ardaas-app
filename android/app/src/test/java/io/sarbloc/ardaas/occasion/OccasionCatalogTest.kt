package io.sarbloc.ardaas.occasion

import io.sarbloc.ardaas.content.BundledAssets
import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines
import io.sarbloc.ardaas.transliteration.GurmukhiTransliterator
import java.io.ByteArrayInputStream
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slot marker as the bundled variants actually spell it: one ellipsis
 * character followed by two full stops (U+2026 ".."), not five dots.
 */
private const val SLOT_MARKER = "….."

/**
 * Port of ArdaasTests/OccasionCatalogTests.swift, case for case, plus the
 * Android-only loader cases at the end.
 *
 * Bundled-content tests for the "….." slot list, run against the real JSON
 * Android packages — protection of the content invariants the picker relies
 * on.
 */
class OccasionCatalogTest {

    private fun occasion(
        id: String = "japji-sahib",
        category: OccasionCategory = OccasionCategory.PAATH,
        gurmukhi: String = "ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ",
        transliteration: String = "Jap Ji Saahib De Paath",
        english: String = "the recitation of Japji Sahib",
    ) = Occasion(id, category, gurmukhi, transliteration, english)

    private fun catalog(occasions: List<Occasion>) = OccasionCatalog(1, listOf("test"), occasions)

    private fun bundled() = BundledAssets.occasionCatalog()

    // region The bundled catalog

    @Test
    fun bundledCatalogLoadsAndValidates() {
        val catalog = bundled()
        assertEquals(1, catalog.version)
        assertFalse(catalog.sources.isEmpty())
        // Pinned so that adding or removing an entry is a deliberate content
        // change, re-proof-read like any other scripture-adjacent text.
        assertEquals(
            listOf(
                "japji-sahib", "jaap-sahib", "rehras-sahib", "kirtan-sohila",
                "sukhmani-sahib", "chaupai-sahib", "anand-sahib",
                "sehaj-paath-bhog", "akhand-paath-bhog",
                "birthday", "new-home", "recovery", "studies", "bereavement",
                "thanksgiving",
            ),
            catalog.occasions.map { it.id },
        )
    }

    @Test
    fun bundledCatalogGroupsIntoPaathsAndOccasions() {
        val catalog = bundled()
        assertEquals(9, catalog.occasionsIn(OccasionCategory.PAATH).size)
        assertEquals(6, catalog.occasionsIn(OccasionCategory.OCCASION).size)
        // Categories are contiguous in bundled order, so the picker can render
        // them as two sections without re-sorting.
        assertEquals(
            List(9) { OccasionCategory.PAATH } + List(6) { OccasionCategory.OCCASION },
            catalog.occasions.map { it.category },
        )
    }

    @Test
    fun bundledIdsAreStableSlugs() {
        val allowed = "abcdefghijklmnopqrstuvwxyz0123456789-".toSet()
        for (occasion in bundled().occasions) {
            assertFalse(occasion.id.isEmpty())
            assertTrue("id is not a slug: ${occasion.id}", occasion.id.all { it in allowed })
        }
    }

    // endregion

    // region The genitive constraint

    /**
     * The whole point of the list: the slot sits **inside** a genitive
     * construction, so an entry has to read correctly followed by `ਦੀ ਅਰਦਾਸ`.
     *
     * This drops each entry into the real bundled sentence of **both**
     * variants and asserts the result actually contains `<entry> ਦੀ ਅਰਦਾਸ`,
     * which is what breaks if a later edit shortens an entry back to a name.
     */
    @Test
    fun everyEntryClosesTheSlotSentenceInBothVariants() {
        val catalog = bundled()
        val library = BundledAssets.library()
        assertFalse(library.variants.isEmpty())

        for (variant in library.variants) {
            val slotId = variant.content.bentiSlot.afterSegmentId
            val segment = requireNotNull(variant.content.segments.firstOrNull { it.id == slotId })
            assertTrue("${variant.id} slot segment has no ….. marker", segment.gurmukhi.contains(SLOT_MARKER))
            for (occasion in catalog.occasions) {
                // The marker is written tight against its neighbours
                // ("ਹਜ਼ੂਰ…..ਦੀ"), so the entry is spaced in as the reader will
                // render it.
                val filled = segment.gurmukhi.replace(SLOT_MARKER, " ${occasion.gurmukhi} ")
                assertTrue(
                    "${occasion.id} does not read into ${variant.id}'s sentence: $filled",
                    filled.contains("${occasion.gurmukhi} ਦੀ ਅਰਦਾਸ"),
                )
            }
        }
    }

    /**
     * An entry is a noun phrase for the slot, not a sentence: it must not
     * carry the words that already surround the slot, nor its own punctuation.
     */
    @Test
    fun entriesAreBareNounPhrases() {
        for (occasion in bundled().occasions) {
            for (forbidden in listOf("ਅਰਦਾਸ", "ਹਜ਼ੂਰ", SLOT_MARKER, "॥", "।")) {
                assertFalse(
                    "${occasion.id} contains $forbidden, which the sentence already supplies",
                    occasion.gurmukhi.contains(forbidden),
                )
            }
            assertEquals(occasion.gurmukhi.trimmingWhitespaceAndNewlines(), occasion.gurmukhi)
            assertFalse(occasion.gurmukhi.contains("\n"))
            assertFalse(occasion.english.isEmpty())
        }
    }

    // endregion

    // region The generated Roman layer

    /**
     * The Roman layer is **generated** by [GurmukhiTransliterator], not
     * hand-authored. Pinning it here is what stops the bundled JSON silently
     * going stale: any rule change that would alter these strings fails CI
     * and forces the layer to be regenerated (and re-proof-read).
     */
    @Test
    fun bundledTransliterationMatchesTheEngine() {
        val catalog = bundled()
        assertFalse(catalog.occasions.isEmpty())
        for (occasion in catalog.occasions) {
            assertEquals(
                "bundled transliteration has drifted from the engine for ${occasion.id} — regenerate the layer",
                GurmukhiTransliterator.transliterate(occasion.gurmukhi),
                occasion.transliteration,
            )
            assertFalse(
                "untransliterated Gurmukhi left in ${occasion.id}",
                occasion.transliteration.any { it.code in 0x0A00..0x0A7F },
            )
        }
    }

    // endregion

    // region Validation

    @Test
    fun validateRejectsAnEmptyCatalog() {
        val error = assertThrows(OccasionCatalogException::class.java) { catalog(emptyList()).validate() }
        assertEquals(OccasionCatalogException.NoOccasions, error)
    }

    @Test
    fun validateRejectsDuplicateIds() {
        val duplicated = catalog(listOf(occasion(), occasion(category = OccasionCategory.OCCASION)))
        val error = assertThrows(OccasionCatalogException::class.java) { duplicated.validate() }
        assertEquals(OccasionCatalogException.DuplicateOccasionIds, error)
    }

    /**
     * All three layers are required, unlike a variant's optional ones: the
     * picker shows whichever the reader has turned on, so a missing layer
     * would render as a blank row.
     */
    @Test
    fun validateRejectsAMissingLayer() {
        for (broken in listOf(
            occasion(id = "no-gurmukhi", gurmukhi = ""),
            occasion(id = "no-transliteration", transliteration = ""),
            occasion(id = "no-english", english = ""),
        )) {
            val error = assertThrows(OccasionCatalogException::class.java) { catalog(listOf(broken)).validate() }
            assertEquals(OccasionCatalogException.EmptyLayer(broken.id), error)
        }
    }

    /**
     * `custom` is how a saved record says "the occasion is the text I typed"
     * (see [OccasionChoice]), so no bundled entry may answer to it.
     */
    @Test
    fun validateRejectsTheReservedCustomId() {
        val clashing = catalog(listOf(occasion(id = OccasionChoice.CUSTOM_ID)))
        val error = assertThrows(OccasionCatalogException::class.java) { clashing.validate() }
        assertEquals(OccasionCatalogException.ReservedOccasionId(OccasionChoice.CUSTOM_ID), error)
    }

    @Test
    fun lookupByIdFindsBundledEntriesAndNothingElse() {
        val catalog = bundled()
        assertEquals(OccasionCategory.PAATH, catalog.occasion("japji-sahib")?.category)
        assertEquals(OccasionCategory.OCCASION, catalog.occasion("birthday")?.category)
        assertNull(catalog.occasion("no-such-occasion"))
        assertNull(catalog.occasion(OccasionChoice.CUSTOM_ID))
    }

    @Test
    fun validateAcceptsAWellFormedCatalog() {
        catalog(listOf(occasion(), occasion(id = "birthday", category = OccasionCategory.OCCASION))).validate()
    }

    /** Through the real loader, so it is the app's own decoder that refuses. */
    @Test
    fun decodingRejectsAnUnknownCategory() {
        val json = """
            {"version": 1, "sources": [], "occasions": [
              {"id": "x", "category": "prayer", "gurmukhi": "ਪਾਠ",
               "transliteration": "Paath", "english": "a paath"}
            ]}
        """.trimIndent()
        assertThrows(SerializationException::class.java) {
            OccasionCatalog.load { ByteArrayInputStream(json.toByteArray()) }
        }
    }

    // endregion

    // region Android-only: the loader (iOS covers these through Bundle lookup)

    @Test
    fun loadFailsWhenTheResourceIsMissing() {
        val error = assertThrows(OccasionCatalogException::class.java) { OccasionCatalog.load { null } }
        assertEquals(OccasionCatalogException.ResourceMissing, error)
    }

    /** The loader validates, so a structurally broken file never reaches the picker. */
    @Test
    fun loadValidatesWhatItDecodes() {
        val json = """{"version": 1, "sources": [], "occasions": []}"""
        val error = assertThrows(OccasionCatalogException::class.java) {
            OccasionCatalog.load { ByteArrayInputStream(json.toByteArray()) }
        }
        assertEquals(OccasionCatalogException.NoOccasions, error)
    }

    /** Stricter than iOS on purpose, like the variant loader: a typo'd key fails the load. */
    @Test
    fun loadRejectsAnUnknownKey() {
        val bundledJson = requireNotNull(BundledAssets.open(OccasionCatalog.FILE_NAME)).use {
            it.readBytes().decodeToString()
        }
        // An extra key beside a well-formed entry, so the only defect is the unknown key.
        val typo = bundledJson.replaceFirst("\"english\"", "\"englsh\": \"x\", \"english\"")
        check(typo != bundledJson)
        assertThrows(SerializationException::class.java) {
            OccasionCatalog.load { ByteArrayInputStream(typo.toByteArray()) }
        }
    }

    // endregion
}
