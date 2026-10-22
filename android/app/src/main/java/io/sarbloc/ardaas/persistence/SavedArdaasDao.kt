package io.sarbloc.ardaas.persistence

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedArdaasDao {
    /** Saves a new record (leave [SavedArdaas.id] 0) and returns its assigned id. */
    @Insert
    suspend fun insert(saved: SavedArdaas): Long

    /** Writes every field of the record with the same id. Returns rows updated (0 if it is gone). */
    @Update
    suspend fun update(saved: SavedArdaas): Int

    /** Deletes the record with the same id. Returns rows deleted (0 if it was already gone). */
    @Delete
    suspend fun delete(saved: SavedArdaas): Int

    /**
     * Every saved Ardaas, newest first — the order the iOS home list uses
     * (`@Query(sort: \SavedArdaas.createdAt, order: .reverse)`). Re-emits on
     * every change to the table.
     *
     * `id DESC` breaks ties between records saved in the same millisecond, so
     * the order is stable; the later insert wins, as it would by time.
     */
    @Query("SELECT * FROM saved_ardaas ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<SavedArdaas>>

    /** Every saved label, in no particular order — what a duplicate's label must not repeat (#159). */
    @Query("SELECT label FROM saved_ardaas")
    suspend fun labels(): List<String>

    @Query("SELECT * FROM saved_ardaas WHERE id = :id")
    suspend fun getById(id: Long): SavedArdaas?

    /**
     * Saves a copy of [source] (#159), labelled by [DuplicateLabel] against
     * every saved label, and returns the copy's id.
     *
     * One transaction, so two duplicates racing each other cannot both read
     * the same labels and save the same name.
     */
    @Transaction
    suspend fun insertDuplicate(source: SavedArdaas, createdAt: Instant): Long =
        insert(source.duplicate(DuplicateLabel.forCopyOf(source.label, labels()), createdAt))
}
