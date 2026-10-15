package io.sarbloc.ardaas.app

import io.sarbloc.ardaas.persistence.SavedArdaas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Runs an insert at most once per key, in a scope that outlives the screen
 * that asked for it.
 *
 * Compose saves under a fresh key per tap. If the activity is recreated
 * mid-insert (a theme or font-size change), the old screen's coroutine is
 * cancelled but the write — running in [scope] — is not; the restored screen
 * [rejoin]s it by key and gets *that* write's outcome rather than inserting a
 * second record, or leaving before the first one landed.
 *
 * A failed write is forgotten once reported, so the key can be retried; a
 * successful one is remembered (one small entry per save, for the life of
 * the process), so a late re-ask still hears "saved".
 */
class InsertOnce(
    private val scope: CoroutineScope,
    private val insert: suspend (SavedArdaas) -> Boolean,
) {
    private val writes = mutableMapOf<String, Deferred<Boolean>>()

    suspend operator fun invoke(key: String, saved: SavedArdaas): Boolean {
        val write = synchronized(writes) { writes.getOrPut(key) { scope.async { insert(saved) } } }
        return outcome(key, write)
    }

    /**
     * The outcome of the write already started under [key], or null when
     * this process never started one — it was never launched, or the process
     * has been restarted since. Null means "unknown", so the caller must not
     * write again on its own: the map is in memory only, and a write that
     * committed before a process death would otherwise be duplicated.
     */
    suspend fun rejoin(key: String): Boolean? {
        val write = synchronized(writes) { writes[key] } ?: return null
        return outcome(key, write)
    }

    private suspend fun outcome(key: String, write: Deferred<Boolean>): Boolean {
        val stored = write.await()
        if (!stored) synchronized(writes) { if (writes[key] === write) writes.remove(key) }
        return stored
    }
}
