package io.sarbloc.ardaas.app

import io.sarbloc.ardaas.persistence.SavedArdaas
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Compose save rejoined after activity recreation writes once and reports the real outcome. */
class InsertOnceTest {

    private val record = SavedArdaas(label = "Family", bentiText = "Bless us.")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun theSameKeyWritesOnce() = runBlocking {
        var writes = 0
        val once = InsertOnce(scope) { writes++; true }
        assertTrue(once("a", record))
        assertTrue(once("a", record))
        assertEquals(1, writes)
        assertTrue(once("b", record))
        assertEquals(2, writes)
    }

    /**
     * The screen that asked is torn down mid-write: its wait is cancelled but
     * the write is not, and the restored screen rejoins it for its outcome.
     */
    @Test
    fun aCancelledCallerDoesNotCancelTheWrite() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val once = InsertOnce(scope) { release.await(); writes++; true }

        // Undispatched: runs until it is waiting on the write, so the write
        // is registered before the screen goes away.
        val firstScreen = async(start = CoroutineStart.UNDISPATCHED) { once("a", record) }
        firstScreen.cancelAndJoin()
        release.complete(Unit)

        assertEquals(true, once.rejoin("a"))
        assertEquals(1, writes)
    }

    @Test
    fun aFailedWriteRejoinsAsFailedThenIsForgotten() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val once = InsertOnce(scope) { release.await(); false }

        val firstScreen = async(start = CoroutineStart.UNDISPATCHED) { once("a", record) }
        firstScreen.cancelAndJoin()
        release.complete(Unit)

        assertEquals(false, once.rejoin("a"))
        assertNull(once.rejoin("a"))
    }

    /**
     * A key this process never wrote under — a restart since, or a write
     * that never began — is unknown, not "failed", and nothing is written.
     */
    @Test
    fun anUnknownKeyRejoinsNothing() = runBlocking {
        var writes = 0
        val once = InsertOnce(scope) { writes++; true }
        assertNull(once.rejoin("never"))
        assertEquals(0, writes)
    }

    /** A failed write is reported, then forgotten, so the same key can try again. */
    @Test
    fun aFailedWriteCanBeRetried() = runBlocking {
        var attempts = 0
        val once = InsertOnce(scope) { attempts++; attempts > 1 }
        assertFalse(once("a", record))
        assertTrue(once("a", record))
        assertEquals(2, attempts)
    }
}
