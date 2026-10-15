package io.sarbloc.ardaas.persistence

import androidx.room.TypeConverter
import java.time.Instant

/**
 * [Instant] ⇄ epoch milliseconds. An INTEGER column sorts chronologically in
 * SQL (the list is ordered by it) and reads plainly in the exported schema and
 * a database dump; milliseconds are far finer than anything a save time needs.
 */
object Converters {
    @TypeConverter
    fun instantToEpochMillis(instant: Instant): Long = instant.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(millis: Long): Instant = Instant.ofEpochMilli(millis)
}
