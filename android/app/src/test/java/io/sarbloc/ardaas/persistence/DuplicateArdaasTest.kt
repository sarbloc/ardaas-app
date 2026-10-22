package io.sarbloc.ardaas.persistence

import androidx.room.Room
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Port of the record half of ArdaasTests/DuplicateArdaasTests.swift (#159):
 * the copy [SavedArdaas.duplicate] makes, and the second row
 * [insertDuplicate] writes through the real Room database.
 */
@RunWith(RobolectricTestRunner::class)
class DuplicateArdaasTest {

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

    private val createdAt = Instant.parse("2026-10-15T09:26:53.589Z")
    private val copiedAt = Instant.parse("2026-10-22T18:04:11.250Z")

    /**
     * Every field set away from its default — the occasion pair and paath
     * deliberately in a shape the `occasion` constructor and
     * [withSlotChoice] would normalise, so a copy made through them would
     * differ.
     */
    private fun fullRecord(id: Long = 42) = SavedArdaas(
        label = "Family",
        bentiText = "Bless the sangat.",
        createdAt = createdAt,
        variantId = "buddha-dal",
        bentiGurmukhi = "ਸਿੰਘ ਅਰਦਾਸ",
        bentiTransliteration = "Singh Ardaas (older rules)",
        occasionId = "japji-sahib",
        occasionCustom = "stale free text",
        paathId = " sukhmani-sahib ",
        id = id,
    )

    /** The entity's stored fields, by reflection: whatever Room persists. */
    private val storedFields: List<Field> =
        SavedArdaas::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }

    /** Set by [SavedArdaas.duplicate] itself; everything else is the user's and is copied. */
    private val replacedFields = setOf("label", "createdAt", "id")

    /**
     * Exhaustive by construction: a field added to [SavedArdaas] fails the
     * first assertion until it is listed here, and once listed must be given
     * a non-default value in [fullRecord] — so it cannot be dropped by
     * [SavedArdaas.duplicate] without this test noticing.
     */
    @Test
    fun everyUserFieldIsCopiedAsStored() {
        assertEquals(
            setOf(
                "label", "bentiText", "createdAt", "variantId", "bentiGurmukhi",
                "bentiTransliteration", "occasionId", "occasionCustom", "paathId", "id",
            ),
            storedFields.map { it.name }.toSet(),
        )
        val defaults = SavedArdaas(label = "", bentiText = "")
        val source = fullRecord()
        val copy = source.duplicate("Family copy", copiedAt)
        for (field in storedFields) {
            assertNotEquals("${field.name} is left at its default in fullRecord", field.get(defaults), field.get(source))
            if (field.name !in replacedFields) {
                assertEquals(field.name, field.get(source), field.get(copy))
            }
        }
        assertEquals("Family copy", copy.label)
        assertEquals(copiedAt, copy.createdAt)
        assertEquals(0L, copy.id)
        assertEquals(" sukhmani-sahib ", copy.paathId)
    }

    @Test
    fun duplicatingInsertsASecondRowWithEveryFieldButIdCreatedAtAndLabel() = runBlocking {
        val sourceId = dao.insert(fullRecord(id = 0))
        val source = requireNotNull(dao.getById(sourceId))

        val copyId = dao.insertDuplicate(source, copiedAt)

        assertNotEquals(sourceId, copyId)
        val all = dao.observeAll().first()
        assertEquals(2, all.size)
        val copy = requireNotNull(dao.getById(copyId))
        assertEquals(source.copy(id = copyId, createdAt = copiedAt, label = "Family copy"), copy)
        // Newest first: the copy leads the list.
        assertEquals(listOf(copyId, sourceId), all.map { it.id })
        assertEquals(source, dao.getById(sourceId))
    }

    @Test
    fun duplicatingAgainCountsUp() = runBlocking {
        val sourceId = dao.insert(fullRecord(id = 0))
        val source = requireNotNull(dao.getById(sourceId))
        val first = requireNotNull(dao.getById(dao.insertDuplicate(source, copiedAt)))
        val second = requireNotNull(dao.getById(dao.insertDuplicate(source, copiedAt)))
        val ofCopy = requireNotNull(dao.getById(dao.insertDuplicate(first, copiedAt)))
        assertEquals("Family copy", first.label)
        assertEquals("Family copy 2", second.label)
        assertEquals("Family copy 3", ofCopy.label)
    }

    /** Codex review: label choice and insert are one transaction, so racing duplicates never share a name. */
    @Test
    fun concurrentDuplicatesGetDistinctLabels() = runBlocking {
        val sourceId = dao.insert(fullRecord(id = 0))
        val source = requireNotNull(dao.getById(sourceId))
        List(8) { async(Dispatchers.IO) { dao.insertDuplicate(source, copiedAt) } }.awaitAll()
        val labels = dao.labels()
        assertEquals(9, labels.size)
        assertEquals(
            setOf("Family", "Family copy") + (2..8).map { "Family copy $it" },
            labels.toSet(),
        )
    }
}
