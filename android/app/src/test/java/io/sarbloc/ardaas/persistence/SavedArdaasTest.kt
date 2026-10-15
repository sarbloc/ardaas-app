package io.sarbloc.ardaas.persistence

import androidx.room.Room
import io.sarbloc.ardaas.composer.BentiLayers
import io.sarbloc.ardaas.content.ArdaasLibrary
import io.sarbloc.ardaas.occasion.OccasionChoice
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Port of ArdaasTests/SavedArdaasTests.swift, plus the storage layer itself: every field round-trips, and the DAO inserts,
 * updates, deletes and lists newest first.
 *
 * Runs on the JVM under Robolectric against a real in-memory SQLite database,
 * so the generated Room code, the type converter and the SQL are all
 * exercised — no emulator.
 */
@RunWith(RobolectricTestRunner::class)
class SavedArdaasTest {

    private lateinit var db: ArdaasDatabase
    private lateinit var dao: SavedArdaasDao

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ArdaasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.savedArdaasDao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    /** A record with every field set to a non-default value, so each one must survive on its own. */
    private fun everyFieldSet() = SavedArdaas(
        label = "Akhand Paath",
        bentiText = "my benti",
        createdAt = Instant.parse("2026-03-14T09:26:53.589Z"),
        variantId = "buddha-dal",
        bentiGurmukhi = "ਮੇਰੀ ਬੇਨਤੀ",
        bentiTransliteration = "merī bentī",
        occasionId = "custom",
        occasionCustom = "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ",
    )

    private suspend fun insertAndFetch(saved: SavedArdaas): SavedArdaas {
        val id = dao.insert(saved)
        return requireNotNull(dao.getById(id)) { "no row for inserted id $id" }
    }

    // region Defaults (iOS: testNewLayersDefaultToEmpty, testOccasionDefaultsToNothingChosen)

    /** The two non-English layers default to empty: only the typed text is required. */
    @Test
    fun newLayersDefaultToEmpty() {
        val saved = SavedArdaas(label = "Morning", bentiText = "my benti")
        assertEquals("", saved.bentiGurmukhi)
        assertEquals("", saved.bentiTransliteration)
        assertEquals("my benti", saved.bentiText)
    }

    /** The occasion fields default to "nothing chosen", which renders the canonical dots. */
    @Test
    fun occasionDefaultsToNothingChosen() {
        val saved = SavedArdaas(label = "Morning", bentiText = "my benti")
        assertEquals("", saved.occasionId)
        assertEquals("", saved.occasionCustom)
        assertEquals(OccasionChoice.Unset, saved.occasionChoice)
    }

    /** The pinned default, not whatever the manifest happens to list first. */
    @Test
    fun variantDefaultsToThePinnedDefault() {
        val saved = SavedArdaas(label = "Morning", bentiText = "my benti")
        assertEquals(ArdaasLibrary.DEFAULT_VARIANT_ID, saved.variantId)
        assertEquals("sgpc", saved.variantId)
    }

    @Test
    fun defaultsSurviveTheStore() = runBlocking {
        val fresh = SavedArdaas(label = "Morning", bentiText = "my benti")
        val fetched = insertAndFetch(fresh)
        assertEquals(fresh.copy(id = fetched.id), fetched)
    }

    /**
     * The same defaults hold in the schema itself, so a row written without
     * the optional columns — as a future migration or a raw insert would —
     * reads back exactly like a record built in Kotlin.
     */
    @Test
    fun schemaDefaultsMatchTheModelDefaults() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO saved_ardaas (label, bentiText, createdAt) VALUES ('Morning', 'my benti', 0)",
        )
        val fetched = dao.observeAll().first().single()
        assertEquals(
            SavedArdaas(label = "Morning", bentiText = "my benti", createdAt = Instant.EPOCH, id = fetched.id),
            fetched,
        )
    }

    /** A fresh record's time equals what the millisecond column reads back. */
    @Test
    fun defaultCreatedAtIsWholeMilliseconds() {
        val createdAt = SavedArdaas(label = "Morning", bentiText = "my benti").createdAt
        assertEquals(Instant.ofEpochMilli(createdAt.toEpochMilli()), createdAt)
    }

    // endregion

    // region Computed views (iOS: the SavedArdaas extension)

    /** iOS sets the alias in place; the entity is immutable here, so the setter is a copy. */
    @Test
    fun bentiEnglishAliasesTheStoredText() {
        val saved = SavedArdaas(label = "Morning", bentiText = "my benti")
        assertEquals("my benti", saved.bentiEnglish)
        val edited = saved.withBentiEnglish("edited benti")
        assertEquals("edited benti", edited.bentiText)
    }

    @Test
    fun bentiLayersExposesAllThreeLayers() {
        val saved = SavedArdaas(
            label = "Morning",
            bentiText = "my benti",
            bentiGurmukhi = "ਮੇਰੀ ਬੇਨਤੀ",
            bentiTransliteration = "merī bentī",
        )
        assertEquals(
            BentiLayers(gurmukhi = "ਮੇਰੀ ਬੇਨਤੀ", transliteration = "merī bentī", english = "my benti"),
            saved.bentiLayers,
        )
    }

    /** An untranslated record still yields a composable benti — only the English layer is populated, and it is not empty. */
    @Test
    fun untranslatedRecordYieldsEnglishOnlyLayers() {
        val saved = SavedArdaas(label = "Morning", bentiText = "  my benti  ")
        assertEquals(BentiLayers(english = "my benti"), saved.bentiLayers)
        assertFalse(saved.bentiLayers.isEmpty)
    }

    @Test
    fun blankRecordYieldsEmptyLayers() {
        val saved = SavedArdaas(label = "Morning", bentiText = " \n ")
        assertTrue(saved.bentiLayers.isEmpty)
    }

    /**
     * The choice is the only way in, so the two columns can never hold an
     * impossible pair — an id with a stale free-text draft beside it, or free
     * text with no sentinel id to reach it by.
     */
    @Test
    fun theInitialiserStoresTheChoiceAsAConsistentPair() {
        val picked = SavedArdaas(label = "Morning", bentiText = "b", occasion = OccasionChoice.Catalog("japji-sahib"))
        assertEquals("japji-sahib", picked.occasionId)
        assertEquals("", picked.occasionCustom)
        assertEquals(OccasionChoice.Catalog("japji-sahib"), picked.occasionChoice)

        val typed = SavedArdaas(label = "Morning", bentiText = "b", occasion = OccasionChoice.Custom("my niece's birth"))
        assertEquals(OccasionChoice.CUSTOM_ID, typed.occasionId)
        assertEquals("my niece's birth", typed.occasionCustom)
        assertEquals(OccasionChoice.Custom("my niece's birth"), typed.occasionChoice)
    }

    /**
     * Setting the choice keeps the pair consistent — switching from free text
     * to a catalog entry must not leave the old typed words behind.
     */
    @Test
    fun settingTheChoiceClearsTheOtherField() {
        var saved = SavedArdaas(label = "Morning", bentiText = "b")
        saved = saved.withOccasionChoice(OccasionChoice.Custom("my niece's birth"))
        assertEquals(OccasionChoice.CUSTOM_ID, saved.occasionId)
        assertEquals("my niece's birth", saved.occasionCustom)

        saved = saved.withOccasionChoice(OccasionChoice.Catalog("japji-sahib"))
        assertEquals("japji-sahib", saved.occasionId)
        assertEquals("", saved.occasionCustom)

        saved = saved.withOccasionChoice(OccasionChoice.Unset)
        assertEquals("", saved.occasionId)
        assertEquals("", saved.occasionCustom)
    }

    // endregion

    // region Round-trip (iOS: testAllThreeLayersPersist, testOccasionFieldsPersist)

    @Test
    fun occasionFieldsPersist() = runBlocking {
        val fetched = insertAndFetch(
            SavedArdaas(label = "Akhand Paath", bentiText = "my benti", occasion = OccasionChoice.Custom("ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ")),
        )
        assertEquals(OccasionChoice.CUSTOM_ID, fetched.occasionId)
        assertEquals("ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ", fetched.occasionCustom)
        assertEquals(OccasionChoice.Custom("ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ"), fetched.occasionChoice)
    }

    /** Every stored field survives the store, each set to a value distinct from its default. */
    @Test
    fun everyFieldRoundTrips() = runBlocking {
        val original = everyFieldSet()
        val fetched = insertAndFetch(original)

        assertEquals("Akhand Paath", fetched.label)
        assertEquals("my benti", fetched.bentiText)
        assertEquals(Instant.parse("2026-03-14T09:26:53.589Z"), fetched.createdAt)
        assertEquals("buddha-dal", fetched.variantId)
        assertEquals("ਮੇਰੀ ਬੇਨਤੀ", fetched.bentiGurmukhi)
        assertEquals("merī bentī", fetched.bentiTransliteration)
        assertEquals("custom", fetched.occasionId)
        assertEquals("ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ", fetched.occasionCustom)
        // And nothing else differs: catches a field added later but not mapped.
        assertEquals(original.copy(id = fetched.id), fetched)
    }

    /** The converter keeps millisecond precision and drops anything finer, by design. */
    @Test
    fun createdAtKeepsMillisecondsAndDropsFinerPrecision() = runBlocking {
        val fetched = insertAndFetch(
            everyFieldSet().copy(createdAt = Instant.parse("2026-03-14T09:26:53.589793238Z")),
        )
        assertEquals(Instant.parse("2026-03-14T09:26:53.589Z"), fetched.createdAt)
    }

    /** A time before 1970 is a negative epoch value, and must survive too. */
    @Test
    fun createdAtBeforeTheEpochRoundTrips() = runBlocking {
        val fetched = insertAndFetch(everyFieldSet().copy(createdAt = Instant.parse("1969-07-20T20:17:40.123Z")))
        assertEquals(Instant.parse("1969-07-20T20:17:40.123Z"), fetched.createdAt)
    }

    // endregion

    // region DAO

    @Test
    fun insertAssignsDistinctIds() = runBlocking {
        val first = dao.insert(everyFieldSet())
        val second = dao.insert(everyFieldSet())
        assertTrue(first > 0)
        assertNotEquals(first, second)
    }

    @Test
    fun getByIdReturnsNullForAnUnknownId() = runBlocking {
        assertNull(dao.getById(42))
    }

    @Test
    fun updateRewritesEveryField() = runBlocking {
        val stored = insertAndFetch(SavedArdaas(label = "Morning", bentiText = "my benti"))
        val edited = everyFieldSet().copy(id = stored.id)

        assertEquals(1, dao.update(edited))
        assertEquals(edited, dao.getById(stored.id))
    }

    /** Clearing a field back to empty is a real write, not "no change". */
    @Test
    fun updateCanClearAnOccasionBackToNothingChosen() = runBlocking {
        val stored = insertAndFetch(everyFieldSet())
        dao.update(stored.copy(occasionId = "", occasionCustom = ""))

        val fetched = requireNotNull(dao.getById(stored.id))
        assertEquals("", fetched.occasionId)
        assertEquals("", fetched.occasionCustom)
    }

    @Test
    fun updateOfAMissingRecordChangesNothing() = runBlocking {
        assertEquals(0, dao.update(everyFieldSet().copy(id = 42)))
        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun deleteRemovesOnlyThatRecord() = runBlocking {
        val kept = insertAndFetch(everyFieldSet().copy(label = "Kept"))
        val gone = insertAndFetch(everyFieldSet().copy(label = "Gone"))

        assertEquals(1, dao.delete(gone))
        assertNull(dao.getById(gone.id))
        assertEquals(listOf(kept), dao.observeAll().first())
    }

    /** AUTOINCREMENT: a deleted record's id is never reused for a new one. */
    @Test
    fun deletedIdIsNotReused() = runBlocking {
        val gone = insertAndFetch(everyFieldSet())
        dao.delete(gone)
        assertNotEquals(gone.id, dao.insert(everyFieldSet()))
    }

    /** Newest first, as the iOS home list sorts, regardless of insertion order. */
    @Test
    fun observeAllListsNewestFirst() = runBlocking {
        val middle = Instant.parse("2026-02-01T00:00:00Z")
        dao.insert(everyFieldSet().copy(label = "Middle", createdAt = middle))
        dao.insert(everyFieldSet().copy(label = "Oldest", createdAt = middle.minusSeconds(60)))
        dao.insert(everyFieldSet().copy(label = "Newest", createdAt = middle.plusSeconds(60)))

        assertEquals(listOf("Newest", "Middle", "Oldest"), dao.observeAll().first().map { it.label })
    }

    /** Records saved in the same millisecond still have a stable order: the later insert first. */
    @Test
    fun observeAllBreaksTimeTiesByInsertionOrder() = runBlocking {
        val sameTime = Instant.parse("2026-02-01T00:00:00Z")
        dao.insert(everyFieldSet().copy(label = "First", createdAt = sameTime))
        dao.insert(everyFieldSet().copy(label = "Second", createdAt = sameTime))

        assertEquals(listOf("Second", "First"), dao.observeAll().first().map { it.label })
    }

    /** The list re-emits after a write, so the home screen follows saves and deletes. */
    @Test
    fun observeAllReEmitsAfterAChange() = runBlocking {
        val emissions = mutableListOf<List<Long>>()
        var insertedId = 0L
        // One collection throughout: the insert happens only after the first
        // (empty) emission, so the second emission must be the change itself.
        withTimeout(5_000) {
            dao.observeAll().take(2).collect { list ->
                emissions += list.map { it.id }
                if (emissions.size == 1) insertedId = dao.insert(everyFieldSet())
            }
        }
        assertEquals(listOf(emptyList(), listOf(insertedId)), emissions)
    }

    // endregion
}
