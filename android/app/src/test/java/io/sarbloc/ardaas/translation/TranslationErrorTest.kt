package io.sarbloc.ardaas.translation

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationErrorTest {

    @Test
    fun casesAreValueEqual() {
        assertEquals(TranslationError.InputTooLong(300, 256), TranslationError.InputTooLong(300, 256))
        assertEquals(
            ModelState.Failed(TranslationError.Download("503")),
            ModelState.Failed(TranslationError.Download("503")),
        )
    }

    @Test
    fun messagesMatchIos() {
        assertEquals(
            "Not enough free memory: 500 MB available, 800 MB needed.",
            TranslationError.InsufficientMemory(500L * 1_048_576, 800L * 1_048_576).message,
        )
        assertEquals(
            "Not enough free space: 900 MB available, 1030 MB needed.",
            TranslationError.InsufficientDisk(900_000_000, 1_030_000_000).message,
        )
        assertEquals("Text is too long: 300 tokens, limit 256.", TranslationError.InputTooLong(300, 256).message)
    }

    @Test
    fun wrapKeepsTypedErrorsAndNamesCancellation() {
        val typed = TranslationError.NotReady
        assertSame(typed, TranslationError.wrap(typed))
        assertEquals(TranslationError.Cancelled, TranslationError.wrap(CancellationException("gone")))
        assertEquals(TranslationError.FileSystem("disk full"), TranslationError.wrap(IOException("disk full")))
    }

    @Test
    fun modelStateFlags() {
        assertTrue(ModelState.Ready.isReady)
        assertTrue(ModelState.Downloading(0.5).isWorking)
        assertTrue(ModelState.Optimizing.isWorking)
        assertFalse(ModelState.Ready.isWorking)
        assertFalse(ModelState.NotDownloaded.isWorking)
        assertNull(ModelState.Ready.error)
        assertEquals(TranslationError.Cancelled, ModelState.Failed(TranslationError.Cancelled).error)
    }
}
