package io.sarbloc.ardaas.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import io.sarbloc.ardaas.content.BundledAssets
import io.sarbloc.ardaas.occasion.OccasionChoice
import io.sarbloc.ardaas.occasion.SlotChoice
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #158's Room migration, 1 → 2, against a real on-disk database.
 *
 * The project has no `room-testing` dependency, so this does what
 * `MigrationTestHelper` would: it builds a version-1 database from exactly the
 * schema version 1 exported (`schemas/…/1.json` — its `createSql` and
 * `setupQueries`), fills it with rows the way the 1.0 app wrote them, then
 * opens it with the app's own Room builder. Room runs [ArdaasDatabase.MIGRATIONS]
 * and then validates the migrated table against the version-2 entity, so a
 * migration that left the schema different from what Room expects fails
 * here, not on a user's phone.
 */
@RunWith(RobolectricTestRunner::class)
class ArdaasDatabaseMigrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val name = "migration-test.db"

    @Before
    @After
    fun deleteDatabase() {
        context.deleteDatabase(name)
    }

    /** One exported schema version's JSON, from `android/app/schemas`. */
    private fun exportedSchema(version: Int): kotlinx.serialization.json.JsonObject {
        val dir = requireNotNull(System.getProperty("ardaas.roomSchemaDir")) { "ardaas.roomSchemaDir not set — run through Gradle" }
        return Json.parseToJsonElement(File(dir, "$version.json").readText(Charsets.UTF_8)).jsonObject["database"]!!.jsonObject
    }

    /** Creates [name] at version 1, as the 1.0 app's Room would have, and runs [fill] on it. */
    private fun createVersion1(fill: (SQLiteDatabase) -> Unit) {
        val schema = exportedSchema(1)
        assertEquals(1, schema["version"]!!.jsonPrimitive.content.toInt())
        val entity = schema["entities"]!!.jsonArray.single().jsonObject
        val table = entity["tableName"]!!.jsonPrimitive.content
        val createSql = entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
        val path = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(createSql)
            for (query in schema["setupQueries"]!!.jsonArray) db.execSQL(query.jsonPrimitive.content)
            db.version = 1
            fill(db)
        }
    }

    private fun SQLiteDatabase.insertV1(
        label: String,
        bentiText: String,
        createdAt: Long,
        variantId: String = "sgpc",
        bentiGurmukhi: String = "",
        bentiTransliteration: String = "",
        occasionId: String = "",
        occasionCustom: String = "",
    ) {
        execSQL(
            "INSERT INTO saved_ardaas (label, bentiText, createdAt, variantId, bentiGurmukhi, bentiTransliteration, " +
                "occasionId, occasionCustom) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(label, bentiText, createdAt, variantId, bentiGurmukhi, bentiTransliteration, occasionId, occasionCustom),
        )
    }

    private fun openMigrated(): ArdaasDatabase =
        Room.databaseBuilder(context, ArdaasDatabase::class.java, name)
            .addMigrations(*ArdaasDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    @Test
    fun everyVersion1RowSurvivesWithNoPaath() {
        createVersion1 { db ->
            db.insertV1("Morning", "bless the family", 1_000L, bentiGurmukhi = "ਬੇਨਤੀ", bentiTransliteration = "Benti")
            // A legacy paath, stored as the occasion before #158.
            db.insertV1("After Sukhmani", "b", 2_000L, variantId = "buddha-dal", occasionId = "sukhmani-sahib")
            db.insertV1("Birthday", "b", 3_000L, occasionId = "birthday")
            db.insertV1("Own words", "b", 4_000L, occasionId = "custom", occasionCustom = "ਦਸਤਾਰਬੰਦੀ")
        }

        val db = openMigrated()
        try {
            val rows = runBlocking { db.savedArdaasDao().observeAll().first() }.sortedBy { it.createdAt }
            assertEquals(
                listOf(
                    SavedArdaas("Morning", "bless the family", Instant.ofEpochMilli(1_000L), "sgpc", "ਬੇਨਤੀ", "Benti", id = 1),
                    SavedArdaas("After Sukhmani", "b", Instant.ofEpochMilli(2_000L), "buddha-dal", occasionId = "sukhmani-sahib", id = 2),
                    SavedArdaas("Birthday", "b", Instant.ofEpochMilli(3_000L), occasionId = "birthday", id = 3),
                    SavedArdaas("Own words", "b", Instant.ofEpochMilli(4_000L), occasionId = "custom", occasionCustom = "ਦਸਤਾਰਬੰਦੀ", id = 4),
                ),
                rows,
            )
            assertTrue(rows.all { it.paathId == "" })
            assertEquals(2, db.openHelper.readableDatabase.version)

            // The migration rewrites no row: the legacy paath is still in
            // occasionId, and reads as the paath.
            val legacy = rows[1]
            assertEquals(
                SlotChoice(paathId = "sukhmani-sahib"),
                legacy.slotChoice.normalised(BundledAssets.occasionCatalog()),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun theMigratedTableMatchesTheExportedVersion2Schema() {
        createVersion1 { it.insertV1("Morning", "b", 1_000L) }
        val db = openMigrated()
        try {
            // Room's own post-migration validation has already passed by the
            // first query; this pins the column itself as the schema exports it.
            val column = db.openHelper.readableDatabase.query("PRAGMA table_info(saved_ardaas)").use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor else null }
                    .map { Triple(it.getString(1), it.getInt(3), it.getString(4)) }
                    .firstOrNull { it.first == "paathId" }
            }
            assertEquals(Triple("paathId", 1, "''"), column)
            val exported = exportedSchema(2)["entities"]!!.jsonArray.single().jsonObject["fields"]!!.jsonArray
                .map { it.jsonObject }
                .single { it["columnName"]!!.jsonPrimitive.content == "paathId" }
            assertEquals("''", exported["defaultValue"]!!.jsonPrimitive.content)
            assertEquals("true", exported["notNull"]!!.jsonPrimitive.content)
        } finally {
            db.close()
        }
    }

    @Test
    fun aMigratedRowTakesAPaath() {
        createVersion1 { it.insertV1("Birthday", "b", 1_000L, occasionId = "birthday") }
        val db = openMigrated()
        try {
            runBlocking {
                val dao = db.savedArdaasDao()
                val row = requireNotNull(dao.getById(1))
                dao.update(row.withSlotChoice(SlotChoice("sukhmani-sahib", OccasionChoice.Catalog("birthday"))))
                assertEquals(SlotChoice("sukhmani-sahib", OccasionChoice.Catalog("birthday")), dao.getById(1)?.slotChoice)
            }
        } finally {
            db.close()
        }
    }

    /**
     * No destructive fallback: a build missing the migration refuses to open
     * the database rather than wiping it, and the rows are still there for
     * the build that has it.
     */
    @Test
    fun withoutTheMigrationTheDatabaseIsRefusedNotWiped() {
        createVersion1 { it.insertV1("Morning", "b", 1_000L) }
        val unmigrated = Room.databaseBuilder(context, ArdaasDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            runBlocking { unmigrated.savedArdaasDao().observeAll().first() }
            fail("opened a version-1 database without a migration")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message, expected.message.orEmpty().contains("migration", ignoreCase = true))
        } finally {
            unmigrated.close()
        }

        val db = openMigrated()
        try {
            assertEquals(listOf("Morning"), runBlocking { db.savedArdaasDao().observeAll().first() }.map { it.label })
        } finally {
            db.close()
        }
    }

    @Test
    fun theAppRegistersTheMigration() {
        // ArdaasDatabase.create is the app's only builder; it must carry every
        // migration, in order, with nothing destructive behind it.
        assertEquals(listOf(1 to 2), ArdaasDatabase.MIGRATIONS.map { it.startVersion to it.endVersion })
        createVersion1 { it.insertV1("Morning", "b", 1_000L) }
        // create() opens FILE_NAME; prove it migrates by pointing the same
        // check at a copy under that name.
        context.deleteDatabase(ArdaasDatabase.FILE_NAME)
        context.getDatabasePath(name).copyTo(context.getDatabasePath(ArdaasDatabase.FILE_NAME))
        val db = ArdaasDatabase.create(context)
        try {
            assertEquals(listOf("Morning"), runBlocking { db.savedArdaasDao().observeAll().first() }.map { it.label })
        } finally {
            db.close()
            context.deleteDatabase(ArdaasDatabase.FILE_NAME)
        }
    }
}
