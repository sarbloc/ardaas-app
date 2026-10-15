package io.sarbloc.ardaas.ui.compose

import androidx.room.Room
import io.sarbloc.ardaas.composer.BentiDraft
import io.sarbloc.ardaas.composer.BentiLayers
import io.sarbloc.ardaas.occasion.OccasionChoice
import io.sarbloc.ardaas.occasion.OccasionDraft
import io.sarbloc.ardaas.occasion.OccasionSelection
import io.sarbloc.ardaas.persistence.ArdaasDatabase
import io.sarbloc.ardaas.persistence.SavedArdaasDao
import io.sarbloc.ardaas.persistence.bentiLayers
import io.sarbloc.ardaas.persistence.occasionChoice
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #89's acceptance: a record saved from Compose round-trips every field —
 * through the real Room database, under Robolectric, exactly as Save inserts
 * it.
 */
@RunWith(RobolectricTestRunner::class)
class ComposeSaveRoundTripTest {

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

    private fun saveAndReload(draft: ComposeDraft) = runBlocking {
        val record = requireNotNull(draft.toSavedArdaas(Instant.parse("2026-10-15T09:26:53.589Z")))
        val id = dao.insert(record)
        record to requireNotNull(dao.getById(id))
    }

    @Test
    fun everyFieldRoundTrips() {
        val (record, stored) = saveAndReload(
            ComposeDraft(
                label = "Akhand Paath",
                benti = BentiDraft(typed = "Bless the sangat.", gurmukhi = "ਸਿੰਘ ਅਰਦਾਸ"),
                variantId = "buddha-dal",
                occasion = OccasionDraft(selection = OccasionSelection.Custom, customText = "ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ"),
            ),
        )
        assertEquals(record.copy(id = stored.id), stored)
        assertEquals("Akhand Paath", stored.label)
        assertEquals("buddha-dal", stored.variantId)
        assertEquals(
            BentiLayers(gurmukhi = "ਸਿੰਘ ਅਰਦਾਸ", transliteration = "Singh Ardaas", english = "Bless the sangat."),
            stored.bentiLayers,
        )
        assertEquals(OccasionChoice.Custom("ਅੰਮ੍ਰਿਤ ਸੰਚਾਰ"), stored.occasionChoice)
    }

    @Test
    fun aCatalogOccasionRoundTrips() {
        val (_, stored) = saveAndReload(
            ComposeDraft(
                label = "Morning",
                benti = BentiDraft(typed = "Bless us."),
                occasion = OccasionDraft(selection = OccasionSelection.Catalog("japji-sahib")),
            ),
        )
        assertEquals(OccasionChoice.Catalog("japji-sahib"), stored.occasionChoice)
        assertEquals(OccasionDraft(selection = OccasionSelection.Catalog("japji-sahib")), OccasionDraft.from(stored.occasionChoice))
    }

    @Test
    fun anEnglishOnlyBentiRoundTripsWithEmptyGurmukhi() {
        val (_, stored) = saveAndReload(ComposeDraft(label = "Morning", benti = BentiDraft(typed = "Bless us.")))
        assertEquals(BentiLayers(english = "Bless us."), stored.bentiLayers)
        assertEquals(OccasionChoice.Unset, stored.occasionChoice)
    }
}
