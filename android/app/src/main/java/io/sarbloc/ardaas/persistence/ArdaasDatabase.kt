package io.sarbloc.ardaas.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The app's only database: saved Ardaas, on this device and nowhere else
 * (`allowBackup="false"` in the manifest; the privacy policy promises it).
 *
 * The schema of every version is exported to `android/app/schemas/` and
 * committed, so each migration is a reviewable diff. There is deliberately no
 * destructive-migration fallback: these are the user's own words, and a
 * missing migration must fail loudly rather than wipe them.
 */
@Database(entities = [SavedArdaas::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class ArdaasDatabase : RoomDatabase() {
    abstract fun savedArdaasDao(): SavedArdaasDao

    companion object {
        const val FILE_NAME = "ardaas.db"

        fun create(context: Context): ArdaasDatabase =
            Room.databaseBuilder(context.applicationContext, ArdaasDatabase::class.java, FILE_NAME).build()
    }
}
