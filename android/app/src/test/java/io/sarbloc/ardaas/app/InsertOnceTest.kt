package io.sarbloc.ardaas.app

import io.sarbloc.ardaas.persistence.SavedArdaas
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
     * the write is not, and the restored screen's re-ask gets its outcome.
     */
    @Test
    fun aCancelledCallerDoesNotCancelTheWrite() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val once = InsertOnce(scope) { release.await(); writes++; true }

        val firstScreen = async(Dispatchers.Default) { once("a", record) }
        firstScreen.cancelAndJoin()
        release.complete(Unit)

        assertTrue(once("a", record))
        assertEquals(1, writes)
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
