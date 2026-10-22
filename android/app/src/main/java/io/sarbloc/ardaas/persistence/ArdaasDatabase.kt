package io.sarbloc.ardaas.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's only database: saved Ardaas, on this device and nowhere else
 * (`allowBackup="false"` in the manifest; the privacy policy promises it).
 *
 * The schema of every version is exported to `android/app/schemas/` and
 * committed, so each migration is a reviewable diff. There is deliberately no
 * destructive-migration fallback: these are the user's own words, and a
 * missing migration must fail loudly rather than wipe them.
 *
 * Versions:
 * 1. The first release.
 * 2. `paathId` (#158) — see [MIGRATION_1_2].
 */
@Database(entities = [SavedArdaas::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class ArdaasDatabase : RoomDatabase() {
    abstract fun savedArdaasDao(): SavedArdaasDao

    companion object {
        const val FILE_NAME = "ardaas.db"

        /**
         * 1 → 2 (#158): adds the slot's paath as its own column. Purely
         * additive — every existing row keeps every value and reads `''`, "no
         * paath". A paath saved before #158 stays in `occasionId` and is
         * resolved at read time ([io.sarbloc.ardaas.occasion.SlotChoice.normalised]),
         * so the migration needs no catalog and rewrites no row.
         *
         * `NOT NULL DEFAULT ''` matches what Room expects for the entity's
         * non-null `@ColumnInfo(defaultValue = "")` field, so the post-migration
         * schema check passes (pinned by `ArdaasDatabaseMigrationTest`).
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `saved_ardaas` ADD COLUMN `paathId` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Every migration, in order. Registered by [create] and the migration test. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun create(context: Context): ArdaasDatabase =
            Room.databaseBuilder(context.applicationContext, ArdaasDatabase::class.java, FILE_NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
