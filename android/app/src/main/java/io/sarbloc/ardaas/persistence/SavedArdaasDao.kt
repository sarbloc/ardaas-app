package io.sarbloc.ardaas.persistence

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
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

    @Query("SELECT * FROM saved_ardaas WHERE id = :id")
    suspend fun getById(id: Long): SavedArdaas?
}
