package io.sarbloc.ardaas.occasion

import androidx.room.Room
import io.sarbloc.ardaas.composer.ArdaasComposer
import io.sarbloc.ardaas.composer.BentiDraft
import io.sarbloc.ardaas.composer.RenderItem
import io.sarbloc.ardaas.content.ArdaasContent
import io.sarbloc.ardaas.content.ArdaasSegment
import io.sarbloc.ardaas.content.BundledAssets
import io.sarbloc.ardaas.content.LayerKind
import io.sarbloc.ardaas.content.fixtureContent
import io.sarbloc.ardaas.content.fixtureSlot
import io.sarbloc.ardaas.persistence.ArdaasDatabase
import io.sarbloc.ardaas.persistence.SavedArdaas
import io.sarbloc.ardaas.persistence.renderItems
import io.sarbloc.ardaas.persistence.slotChoice
import io.sarbloc.ardaas.persistence.withSlotChoice
import io.sarbloc.ardaas.transliteration.GurmukhiTransliterator
import io.sarbloc.ardaas.ui.compose.ComposeDraft
import io.sarbloc.ardaas.ui.compose.ComposeMode
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The slot marker as the bundled variants spell it: one ellipsis character followed by two full stops. */
private const val MARKER = "….."

/**
 * Port of ArdaasTests/SlotChoiceTests.swift, case for case.
 *
 * A paath and an occasion together in the "….." slot (#158): the joiner, the
 * legacy normaliser, every combination rendered into both bundled variants,
 * persistence and the edit round trip. Robolectric only for the Room cases.
 */
@RunWith(RobolectricTestRunner::class)
class SlotChoiceTest {
    private fun bundledCatalog(): OccasionCatalog = BundledAssets.occasionCatalog()

    private fun content(id: String): ArdaasContent = BundledAssets.content(id)

    private fun record(paathId: String = "", occasion: OccasionChoice = OccasionChoice.Unset) =
        SavedArdaas(label = "Morning", bentiText = "bless the family", occasion = occasion, paathId = paathId)

    /** The slot segment as the Reader would draw it for [saved]. */
    private fun slotSegment(saved: SavedArdaas, content: ArdaasContent, catalog: OccasionCatalog?): ArdaasSegment {
        val slotId = requireNotNull(content.occasionSlot) { "no occasion slot" }.inSegmentId
        return saved.renderItems(content, catalog)
            .filterIsInstance<RenderItem.Canonical>()
            .map { it.segment }
            .firstOrNull { it.id == slotId }
            ?: throw AssertionError("no slot segment '$slotId' in the composed sequence")
    }

    private val sukhmani = OccasionLayers(
        gurmukhi = "ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ",
        transliteration = "Sukhmani Saahib De Paath",
        english = "the recitation of Sukhmani Sahib",
    )
    private val birthday = OccasionLayers(gurmukhi = "ਜਨਮ ਦਿਹਾੜੇ", transliteration = "Janam Dihaarhe", english = "a birthday")
    private val joiner = SlotJoiner(gurmukhi = "ਅਤੇ", transliteration = "Ate", english = "and")

    // region The bundled joiner

    @Test
    fun theBundledJoinerIsTheApprovedWord() {
        assertEquals(joiner, bundledCatalog().joiner)
    }

    /**
     * Like every other Roman string in the catalog, the joiner's is what the
     * engine writes for its Gurmukhi — so a combined line romanizes in one voice.
     */
    @Test
    fun theBundledJoinerTransliterationMatchesTheEngine() {
        val bundled = requireNotNull(bundledCatalog().joiner)
        assertEquals(GurmukhiTransliterator.transliterate(bundled.gurmukhi), bundled.transliteration)
    }

    /** The strict decoder still accepts a catalog without the key: no joiner. */
    @Test
    fun aCatalogWithoutAJoinerDecodes() {
        val json = """
            {"version": 1, "sources": ["test"], "occasions": [
              {"id": "a", "category": "paath", "gurmukhi": "ਗ", "transliteration": "g", "english": "e"}
            ]}
        """.trimIndent()
        val catalog = OccasionCatalog.load { json.byteInputStream() }
        assertNull(catalog.joiner)
    }

    // endregion

    // region Joining

    @Test
    fun neitherChosenIsNothing() {
        assertNull(OccasionLayers.joining(paath = null, occasion = null, joiner = joiner))
        assertNull(OccasionLayers.joining(paath = OccasionLayers(), occasion = OccasionLayers(), joiner = joiner))
    }

    @Test
    fun oneChosenIsExactlyThatOne() {
        assertEquals(sukhmani, OccasionLayers.joining(paath = sukhmani, occasion = null, joiner = joiner))
        assertEquals(birthday, OccasionLayers.joining(paath = null, occasion = birthday, joiner = joiner))
    }

    @Test
    fun bothChosenAreJoinedInEveryLayer() {
        assertEquals(
            OccasionLayers(
                gurmukhi = "ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਅਤੇ ਜਨਮ ਦਿਹਾੜੇ",
                transliteration = "Sukhmani Saahib De Paath Ate Janam Dihaarhe",
                english = "the recitation of Sukhmani Sahib and a birthday",
            ),
            OccasionLayers.joining(paath = sukhmani, occasion = birthday, joiner = joiner),
        )
    }

    /** Free text fills one script, so the layers it cannot speak to read the paath alone. */
    @Test
    fun aOneScriptOccasionJoinsOnlyWhereItHasWords() {
        assertEquals(
            OccasionLayers(
                gurmukhi = sukhmani.gurmukhi,
                transliteration = sukhmani.transliteration,
                english = "the recitation of Sukhmani Sahib and a new job",
            ),
            OccasionLayers.joining(paath = sukhmani, occasion = OccasionLayers(english = "a new job"), joiner = joiner),
        )
    }

    @Test
    fun aMissingJoinerFallsBackToThePaathAlone() {
        assertEquals(sukhmani, OccasionLayers.joining(paath = sukhmani, occasion = birthday, joiner = null))
    }

    @Test
    fun aJoinerWithAnEmptyLayerFallsBackToThePaathInThatLayer() {
        val partial = SlotJoiner(gurmukhi = "ਅਤੇ", transliteration = " ", english = "and")
        val joined = OccasionLayers.joining(paath = sukhmani, occasion = birthday, joiner = partial)
        assertEquals("ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਅਤੇ ਜਨਮ ਦਿਹਾੜੇ", joined?.gurmukhi)
        assertEquals(sukhmani.transliteration, joined?.transliteration)
        assertEquals("the recitation of Sukhmani Sahib and a birthday", joined?.english)
    }

    /** A bundle without the joiner renders the paath alone, never a failure. */
    @Test
    fun aBundleWithoutAJoinerRendersThePaathAlone() {
        val catalog = bundledCatalog().copy(joiner = null)
        val slot = SlotChoice(paathId = "sukhmani-sahib", occasion = OccasionChoice.Catalog("birthday"))
        assertEquals(sukhmani, slot.layers(catalog))
    }

    // endregion

    // region The legacy normaliser

    @Test
    fun aLegacyPaathInTheOccasionFieldIsReadAsThePaath() {
        val catalog = bundledCatalog()
        for (paath in catalog.occasionsIn(OccasionCategory.PAATH)) {
            val legacy = SlotChoice(paathId = "", occasion = OccasionChoice.Catalog(paath.id))
            assertEquals(paath.id, SlotChoice(paathId = paath.id), legacy.normalised(catalog))
        }
    }

    @Test
    fun anOccasionInTheOccasionFieldStaysAnOccasion() {
        val catalog = bundledCatalog()
        for (occasion in catalog.occasionsIn(OccasionCategory.OCCASION)) {
            val slot = SlotChoice(paathId = "", occasion = OccasionChoice.Catalog(occasion.id))
            assertEquals(occasion.id, slot, slot.normalised(catalog))
        }
    }

    @Test
    fun aPaathIdThatIsNotAPaathIsIgnored() {
        val catalog = bundledCatalog()
        for (id in listOf("birthday", "retired-paath", OccasionChoice.CUSTOM_ID)) {
            val slot = SlotChoice(paathId = id, occasion = OccasionChoice.Custom("a new job"))
            assertEquals(id, SlotChoice(paathId = "", occasion = OccasionChoice.Custom("a new job")), slot.normalised(catalog))
        }
    }

    @Test
    fun aPaathInTheOccasionFieldBesideAPaathIsIgnored() {
        val slot = SlotChoice(paathId = "japji-sahib", occasion = OccasionChoice.Catalog("sukhmani-sahib"))
        assertEquals(SlotChoice(paathId = "japji-sahib"), slot.normalised(bundledCatalog()))
    }

    @Test
    fun aRetiredOccasionIsLeftForTheDraftToDrop() {
        val catalog = bundledCatalog()
        val slot = SlotChoice(paathId = "sukhmani-sahib", occasion = OccasionChoice.Catalog("retired-occasion"))
        assertEquals(slot, slot.normalised(catalog))
        assertEquals(sukhmani, slot.layers(catalog))
    }

    @Test
    fun normalisingTwiceChangesNothingMore() {
        val catalog = bundledCatalog()
        val ids = listOf("", "sukhmani-sahib", "birthday", "retired", OccasionChoice.CUSTOM_ID)
        for (paathId in ids) {
            for (occasionId in ids) {
                val slot = SlotChoice(paathId, OccasionChoice.fromStored(occasionId = occasionId, occasionCustom = "words"))
                val once = slot.normalised(catalog)
                assertEquals("$paathId / $occasionId", once, once.normalised(catalog))
            }
        }
    }

    // endregion

    // region Shared parity fixture

    @Serializable
    private data class ParityFixture(val cases: List<Case>) {
        @Serializable
        data class Stored(val paathId: String, val occasionId: String, val occasionCustom: String)

        @Serializable
        data class Layers(val gurmukhi: String, val transliteration: String, val english: String)

        @Serializable
        data class Case(val name: String, val stored: Stored, val normalised: Stored, val slot: Layers?)
    }

    /**
     * The iOS suite asserts the same file (`ArdaasTests/Fixtures/slot-parity.json`),
     * so the normaliser and the joiner cannot drift between the platforms.
     * Never edit an expected value to make one side pass.
     */
    @Test
    fun sharedParityFixture() {
        val path = requireNotNull(System.getProperty("ardaas.slotParityFixture")) {
            "ardaas.slotParityFixture not set — run through Gradle"
        }
        val fixture = Json { ignoreUnknownKeys = true }
            .decodeFromString<ParityFixture>(File(path).readText(Charsets.UTF_8))
        assertTrue("only ${fixture.cases.size} cases", fixture.cases.size >= 15)
        val catalog = bundledCatalog()
        for (case in fixture.cases) {
            val saved = record().copy(
                paathId = case.stored.paathId,
                occasionId = case.stored.occasionId,
                occasionCustom = case.stored.occasionCustom,
            )

            // What the next save writes: the normalised slot, through the
            // record's own writer.
            val written = saved.withSlotChoice(saved.slotChoice.normalised(catalog))
            assertEquals(case.name, case.normalised.paathId, written.paathId)
            assertEquals(case.name, case.normalised.occasionId, written.occasionId)
            assertEquals(case.name, case.normalised.occasionCustom, written.occasionCustom)

            val expected = case.slot?.let { OccasionLayers(it.gurmukhi, it.transliteration, it.english) }
            // Kotlin compares Strings by code unit, so this is scalar-exact.
            assertEquals(case.name, expected, saved.slotChoice.layers(catalog))
        }
    }

    // endregion

    // region Every combination, in both bundled variants

    /**
     * The combined line in situ, quoted for the proof-read: both variants, all
     * three layers, seams single-spaced and punctuation untouched.
     */
    @Test
    fun paathAndOccasionReadInTheSgpcSentence() {
        val segment = slotSegment(
            record(paathId = "sukhmani-sahib", occasion = OccasionChoice.Catalog("birthday")),
            content("sgpc"),
            bundledCatalog(),
        )
        assertTrue(segment.gurmukhi, segment.gurmukhi.endsWith("ਆਪ ਦੇ ਹਜ਼ੂਰ ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਅਤੇ ਜਨਮ ਦਿਹਾੜੇ ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥"))
        assertEquals(
            segment.transliteration,
            true,
            segment.transliteration?.endsWith("Aap De Hazur Sukhmani Saahib De Paath Ate Janam Dihaarhe Di Ardaas Hai Ji ||"),
        )
        assertEquals(
            segment.english,
            true,
            segment.english?.endsWith(
                "we humbly make prayer in your presence the recitation of Sukhmani Sahib and a birthday.",
            ),
        )
    }

    @Test
    fun paathAndOccasionReadInTheBuddhaDalSentence() {
        val segment = slotSegment(
            record(paathId = "sukhmani-sahib", occasion = OccasionChoice.Catalog("birthday")),
            content("buddha-dal"),
            bundledCatalog(),
        )
        assertTrue(
            segment.gurmukhi,
            segment.gurmukhi.endsWith("ਆਪ ਜੀ ਦੇ ਹਜ਼ੂਰ ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਅਤੇ ਜਨਮ ਦਿਹਾੜੇ ਦੀ ਅਰਦਾਸ ਆਪ ਜੀ ਦੇ ਚਰਨ ਕਮਲਾਂ ਪਾਸ,"),
        )
        assertEquals(
            segment.transliteration,
            true,
            segment.transliteration?.endsWith(
                "Aap Ji De Hazur Sukhmani Saahib De Paath Ate Janam Dihaarhe Di Ardaas Aap Ji De Charan Kamlaa(n) Paas,",
            ),
        )
        assertEquals(
            segment.english,
            true,
            segment.english?.endsWith(
                "In your presence the prayer of the recitation of Sukhmani Sahib and a birthday is laid at your lotus feet,",
            ),
        )
    }

    /**
     * Every combination of the two pickers, in every bundled variant, renders
     * exactly the joined phrase — and with one choice, exactly what that
     * choice rendered before #158.
     */
    @Test
    fun everyCombinationRendersInEveryVariant() {
        val catalog = bundledCatalog()
        val english = "my niece's first birthday"
        val gurmukhi = "ਦਸਤਾਰਬੰਦੀ"
        val combinations = listOf(
            Triple("none", "", OccasionChoice.Unset),
            Triple("paath only", "sukhmani-sahib", OccasionChoice.Unset),
            Triple("occasion only", "", OccasionChoice.Catalog("birthday")),
            Triple("English custom only", "", OccasionChoice.Custom(english)),
            Triple("Gurmukhi custom only", "", OccasionChoice.Custom(gurmukhi)),
            Triple("paath + occasion", "sukhmani-sahib", OccasionChoice.Catalog("birthday")),
            Triple("paath + English custom", "sukhmani-sahib", OccasionChoice.Custom(english)),
            Triple("paath + Gurmukhi custom", "sukhmani-sahib", OccasionChoice.Custom(gurmukhi)),
        )
        for (variantId in listOf("sgpc", "buddha-dal")) {
            val text = content(variantId)
            val authored = requireNotNull(ArdaasComposer.occasionSlotSegment(text, occasion = null))
            for ((name, paathId, occasion) in combinations) {
                val label = "$variantId: $name"
                val segment = slotSegment(record(paathId = paathId, occasion = occasion), text, catalog)

                // The whole render is the composer fed the joined phrase.
                val paath = if (paathId.isEmpty()) null else sukhmani
                val joined = OccasionLayers.joining(paath = paath, occasion = occasion.layers(catalog), joiner = joiner)
                assertEquals(label, ArdaasComposer.occasionSlotSegment(text, joined), segment)

                // And, layer by layer, the phrase is in the sentence where it
                // has words, and the dots are where it has none.
                for (layer in LayerKind.entries) {
                    val rendered = requireNotNull(segment.text(layer)) { label }
                    val phrase = joined?.get(layer).orEmpty()
                    if (phrase.isEmpty()) {
                        assertEquals("$label $layer", authored.text(layer), rendered)
                    } else {
                        assertTrue("$label $layer: $rendered", rendered.contains(phrase))
                        assertFalse("$label $layer: $rendered", rendered.contains(MARKER))
                        assertFalse("$label $layer: $rendered", rendered.contains("  "))
                    }
                }
            }
        }
    }

    /** One choice alone reads exactly as it did before #158. */
    @Test
    fun aSingleChoiceRendersExactlyAsBefore() {
        val catalog = bundledCatalog()
        for (variantId in listOf("sgpc", "buddha-dal")) {
            val text = content(variantId)
            for (entry in catalog.occasions) {
                val layers = OccasionLayers(entry.gurmukhi, entry.transliteration, entry.english)
                val before = ArdaasComposer.occasionSlotSegment(text, layers)
                val saved = if (entry.category == OccasionCategory.PAATH) {
                    record(paathId = entry.id)
                } else {
                    record(occasion = OccasionChoice.Catalog(entry.id))
                }
                assertEquals(entry.id, before, slotSegment(saved, text, catalog))
            }
        }
    }

    @Test
    fun aLegacyRecordRendersItsPaath() {
        val catalog = bundledCatalog()
        val text = content("sgpc")
        assertEquals(
            slotSegment(record(paathId = "sukhmani-sahib"), text, catalog),
            slotSegment(record(occasion = OccasionChoice.Catalog("sukhmani-sahib")), text, catalog),
        )
    }

    @Test
    fun unknownIdsRenderTheDots() {
        val catalog = bundledCatalog()
        val text = content("sgpc")
        val authored = requireNotNull(ArdaasComposer.occasionSlotSegment(text, occasion = null))
        for (saved in listOf(
            record(paathId = "retired-paath"),
            record(paathId = "birthday"),
            record(occasion = OccasionChoice.Catalog("retired-occasion")),
            record(paathId = "retired-paath", occasion = OccasionChoice.Catalog("retired-occasion")),
        )) {
            assertEquals(saved.paathId, authored, slotSegment(saved, text, catalog))
        }
    }

    /** Without a catalog only free text can render — the paath is an id. */
    @Test
    fun aMissingCatalogKeepsTheFreeTextOnly() {
        val saved = record(paathId = "sukhmani-sahib", occasion = OccasionChoice.Custom("a new job"))
        val segment = slotSegment(saved, content("sgpc"), catalog = null)
        assertEquals(segment.english, true, segment.english?.contains("presence a new job."))
        assertTrue(segment.gurmukhi, segment.gurmukhi.contains(MARKER))
    }

    // endregion

    // region The pickers' draft

    @Test
    fun theDraftPreviewShowsTheJoinedSentence() {
        val draft = OccasionDraft(selection = OccasionSelection.Catalog("birthday"), paathId = "sukhmani-sahib")
        val preview = requireNotNull(draft.preview(content("sgpc"), bundledCatalog()))
        assertTrue(preview.isFilled)
        assertEquals(LayerKind.GURMUKHI, preview.layer)
        assertTrue(preview.text, preview.text.contains("ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਅਤੇ ਜਨਮ ਦਿਹਾੜੇ ਦੀ ਅਰਦਾਸ"))
    }

    @Test
    fun thePickersAreIndependent() {
        var draft = OccasionDraft().withSelection(OccasionSelection.Custom).copy(customText = "a new job")
        draft = draft.copy(paathId = "sukhmani-sahib")
        assertEquals(SlotChoice("sukhmani-sahib", OccasionChoice.Custom("a new job")), draft.slot)
        draft = draft.copy(paathId = "")
        assertEquals(SlotChoice("", OccasionChoice.Custom("a new job")), draft.slot)
        draft = draft.withSelection(OccasionSelection.Catalog("birthday")).copy(paathId = "rehras-sahib")
        assertEquals(SlotChoice("rehras-sahib", OccasionChoice.Catalog("birthday")), draft.slot)
        // Changing the occasion row keeps the paath.
        assertEquals("rehras-sahib", draft.withSelection(OccasionSelection.Unset).paathId)
    }

    @Test
    fun theDraftOpensALegacyRecordOnThePaathPicker() {
        val legacy = SlotChoice(paathId = "", occasion = OccasionChoice.Catalog("sukhmani-sahib"))
        val draft = OccasionDraft.from(legacy, bundledCatalog())
        assertEquals("sukhmani-sahib", draft.paathId)
        assertEquals(OccasionSelection.Unset, draft.selection)
        // Without a catalog nothing can be resolved, so nothing is moved.
        assertEquals(legacy, OccasionDraft.from(legacy).slot)
    }

    /**
     * The #72 warning is about the occasion's own words: beside a paath the
     * sentence changes, but English free text on a variant without English
     * still has nowhere to go.
     */
    @Test
    fun theNoEnglishWarningStillFiresBesideAPaath() {
        val noEnglish = fixtureContent(
            segments = listOf(
                ArdaasSegment(
                    id = "slot",
                    gurmukhi = "ਆਪ ਜੀ ਦੇ ਹਜ਼ੂਰ${MARKER}ਦੀ ਅਰਦਾਸ",
                    transliteration = "Aap Ji De Hazur${MARKER}Di Ardaas",
                    english = null,
                ),
            ),
            slotAfter = "slot",
            occasionSlot = fixtureSlot(inSegmentId = "slot", transliteration = MARKER),
        )
        val catalog = bundledCatalog()
        val draft = OccasionDraft(OccasionSelection.Custom, "a new job", paathId = "sukhmani-sahib")
        assertTrue(draft.willNotAppear(noEnglish, catalog))
        assertEquals(true, draft.preview(noEnglish, catalog)?.isFilled)
        // A paath on its own is never flagged.
        assertFalse(OccasionDraft(paathId = "sukhmani-sahib").willNotAppear(noEnglish, catalog))
    }

    // endregion

    // region Persistence and the edit round trip (#154)

    private fun <T> withDatabase(block: suspend (ArdaasDatabase) -> T): T {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ArdaasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return try {
            runBlocking { block(db) }
        } finally {
            db.close()
        }
    }

    @Test
    fun thePaathPersists() = withDatabase { db ->
        val dao = db.savedArdaasDao()
        val id = dao.insert(record(paathId = "sukhmani-sahib", occasion = OccasionChoice.Custom("a new job")))
        val stored = requireNotNull(dao.getById(id))
        assertEquals("sukhmani-sahib", stored.paathId)
        assertEquals(SlotChoice("sukhmani-sahib", OccasionChoice.Custom("a new job")), stored.slotChoice)
    }

    @Test
    fun aRecordWithoutAPaathDefaultsToNone() {
        val saved = SavedArdaas(label = "Morning", bentiText = "bless the family")
        assertEquals("", saved.paathId)
        assertEquals(SlotChoice(), saved.slotChoice)
    }

    @Test
    fun everySlotRoundTripsThroughTheForm() {
        val createdAt = Instant.parse("2026-10-15T09:26:53.589Z")
        val occasions = listOf(
            OccasionChoice.Unset,
            OccasionChoice.Catalog("birthday"),
            OccasionChoice.Custom("a new job"),
            OccasionChoice.Custom("ਦਸਤਾਰਬੰਦੀ"),
        )
        for (paathId in listOf("", "sukhmani-sahib")) {
            for (occasion in occasions) {
                val label = "$paathId $occasion"
                val saved = SavedArdaas(
                    label = "Family",
                    bentiText = "Bless the sangat.",
                    occasion = occasion,
                    createdAt = createdAt,
                    variantId = "buddha-dal",
                    paathId = paathId,
                ).copy(id = 7)
                for (catalog in listOf(null, bundledCatalog())) {
                    val draft = ComposeDraft.from(saved, catalog)
                    assertEquals(label, SlotChoice(paathId, occasion), draft.occasion.slot)
                    assertEquals(label, saved, draft.updating(saved))
                }
            }
        }
    }

    @Test
    fun changingThePaathIsAChange() {
        val original = SavedArdaas(
            label = "Family",
            bentiText = "b",
            occasion = OccasionChoice.Catalog("birthday"),
            paathId = "sukhmani-sahib",
        )
        val mode = ComposeMode.Edit(original, bundledCatalog())
        val initial = mode.initialDraft
        assertTrue(mode.hasUnsavedChanges(initial.copy(occasion = initial.occasion.copy(paathId = "rehras-sahib"))))
        assertFalse(mode.hasUnsavedChanges(initial.copy(occasion = initial.occasion.copy(paathId = "sukhmani-sahib"))))
    }

    /**
     * Opening a legacy record for editing resolves its paath in the draft the
     * screen opens on, so opening is not a change to discard — and Save writes
     * the normalised fields.
     */
    @Test
    fun editingALegacyRecordOpensOnThePaathAndSavesItNormalised() = withDatabase { db ->
        val dao = db.savedArdaasDao()
        val id = dao.insert(record(occasion = OccasionChoice.Catalog("sukhmani-sahib")))
        val saved = requireNotNull(dao.getById(id))

        val mode = ComposeMode.Edit(saved, bundledCatalog())
        val draft = mode.initialDraft
        assertEquals("sukhmani-sahib", draft.occasion.paathId)
        assertEquals(OccasionSelection.Unset, draft.occasion.selection)
        assertFalse(mode.hasUnsavedChanges(draft))

        dao.update(requireNotNull(mode.record(draft)))
        val stored = requireNotNull(dao.getById(id))
        assertEquals("sukhmani-sahib", stored.paathId)
        assertEquals("", stored.occasionId)
        assertEquals("", stored.occasionCustom)
    }

    @Test
    fun editingWithAPaathAndOccasionRoundTripsThroughTheDatabase() = withDatabase { db ->
        val dao = db.savedArdaasDao()
        val id = dao.insert(record(paathId = "sukhmani-sahib", occasion = OccasionChoice.Catalog("birthday")))
        val saved = requireNotNull(dao.getById(id))
        val mode = ComposeMode.Edit(saved, bundledCatalog())
        val edited = mode.initialDraft.let {
            it.copy(occasion = it.occasion.withSelection(OccasionSelection.Custom).copy(customText = "ਦਸਤਾਰਬੰਦੀ"))
        }
        dao.update(requireNotNull(mode.record(edited)))
        val stored = requireNotNull(dao.getById(id))
        assertEquals(SlotChoice("sukhmani-sahib", OccasionChoice.Custom("ਦਸਤਾਰਬੰਦੀ")), stored.slotChoice)
        assertEquals(saved.createdAt, stored.createdAt)
        // Reopening lands on both pickers exactly where they were left.
        assertEquals(edited.occasion, ComposeMode.Edit(stored, bundledCatalog()).initialDraft.occasion)
    }

    @Test
    fun aNewRecordSavesItsPaath() {
        val draft = ComposeDraft(
            label = "Family",
            benti = BentiDraft(typed = "Bless us."),
            occasion = OccasionDraft(OccasionSelection.Catalog("birthday"), paathId = " sukhmani-sahib "),
        )
        val saved = requireNotNull(draft.toSavedArdaas())
        assertEquals("sukhmani-sahib", saved.paathId)
        assertEquals("birthday", saved.occasionId)
    }

    // endregion
}
