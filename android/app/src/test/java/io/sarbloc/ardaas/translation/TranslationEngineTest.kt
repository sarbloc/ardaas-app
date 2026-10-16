package io.sarbloc.ardaas.translation

import android.content.ComponentCallbacks2
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The engine's rules, against a fake translator: the memory pre-flight runs
 * before anything loads, one translation at a time, the translator is kept
 * between translations and dropped on release / memory pressure, and
 * cancellation propagates rather than yielding a partial result.
 */
class TranslationEngineTest {
    private val catalog = TranslationFixtures.syntheticModel().catalog
    private val graphs = File("unused-graphs")
    private val vocabulary = File("unused-vocabulary")
    private val loads = AtomicInteger()
    private val roomy = MemoryReading(availableBytes = 4_000L * 1_048_576, isLowMemory = false, isLowRamDevice = false)

    private fun engine(
        translator: FakeTranslator = FakeTranslator(),
        memory: MemoryReading? = roomy,
    ) = TranslationEngine(
        memoryProbe = { memory },
        makeTranslator = { _, _, _ ->
            loads.incrementAndGet()
            translator
        },
    )

    private suspend fun TranslationEngine.translate(text: String = "bless us") =
        translate(text, catalog, graphs, vocabulary)

    @Test
    fun translatesThroughOneResidentTranslator() = runBlocking {
        val engine = engine()

        assertEquals("ਅਰਦਾਸ one", engine.translate("one").gurmukhi)
        assertEquals("ਅਰਦਾਸ two", engine.translate("two").gurmukhi)
        assertEquals(1, loads.get())
        assertTrue(engine.isLoaded)
    }

    @Test
    fun refusesBeforeLoadingAnythingWhenMemoryIsShort() = runBlocking {
        val short = MemoryReading(availableBytes = 300L * 1_048_576, isLowMemory = false, isLowRamDevice = false)
        val engine = engine(memory = short)

        try {
            engine.translate()
            fail("expected InsufficientMemory")
        } catch (e: TranslationError.InsufficientMemory) {
            assertEquals(MemoryGuard.REQUIRED_HEADROOM_BYTES, e.requiredBytes)
        }
        assertEquals(0, loads.get())
        assertFalse(engine.isLoaded)
    }

    @Test
    fun unmeasurableMemoryDoesNotRefuse() = runBlocking {
        engine(memory = null).translate()
        assertEquals(1, loads.get())
    }

    @Test
    fun runsOneTranslationAtATime() = runBlocking {
        val translator = FakeTranslator(steps = 5) { Thread.sleep(5) }
        val engine = engine(translator)

        (1..4).map { async(Dispatchers.Default) { engine.translate("call $it") } }.awaitAll()

        assertEquals(4, translator.completedCount)
        assertEquals(1, translator.maxConcurrent)
    }

    @Test
    fun releaseDropsTheTranslatorAndTheNextTranslationReloads() = runBlocking {
        val engine = engine()
        engine.translate()

        engine.release()

        assertFalse(engine.isLoaded)
        engine.translate()
        assertEquals(2, loads.get())
    }

    /** iOS semantics: the drop waits for the translation in flight, which keeps the translator it started with. */
    @Test
    fun releaseDuringATranslationTakesEffectWhenItFinishes() = runBlocking {
        val inFlight = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val translator = FakeTranslator { inFlight.countDown(); proceed.await(5, TimeUnit.SECONDS) }
        val engine = engine(translator)

        val pending = async(Dispatchers.Default) { engine.translate() }
        assertTrue(inFlight.await(5, TimeUnit.SECONDS))
        engine.release() // must not block on the running translation
        assertTrue("dropped mid-translation", engine.isLoaded)
        proceed.countDown()

        assertEquals("ਅਰਦਾਸ bless us", pending.await().gurmukhi)
        assertFalse(engine.isLoaded)
    }

    @Test
    fun trimMemoryReleasesUnderPressureAndWhenHidden() = runBlocking {
        val engine = engine()
        engine.translate()

        @Suppress("DEPRECATION")
        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertTrue("moderate pressure is not worth a reload", engine.isLoaded)

        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertFalse(engine.isLoaded)

        engine.translate()
        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertFalse(engine.isLoaded)
    }

    /** The Android side of #52: cancelled mid-decode means cancelled, not a shorter translation. */
    @Test
    fun cancellationMidDecodePropagatesAndFreesTheEngine() = runBlocking {
        val reachedDecode = CompletableDeferred<Unit>()
        val slow = AtomicBoolean(true)
        val translator = FakeTranslator(steps = 10_000) { step ->
            if (step == 1) reachedDecode.complete(Unit)
            if (slow.get()) Thread.sleep(1)
        }
        val engine = engine(translator)

        val pending = async(Dispatchers.Default) { engine.translate() }
        withTimeout(5_000) { reachedDecode.await() }
        pending.cancel()

        try {
            pending.await()
            fail("a cancelled translation must not produce a result")
        } catch (e: CancellationException) {
            // Expected.
        }
        assertEquals(0, translator.completedCount)

        // The lock was released: the next translation is not stuck behind it.
        slow.set(false)
        withTimeout(5_000) { engine.translate() }
        assertEquals(1, translator.completedCount)
    }

    @Test
    fun cancellationIsNotDisguisedAsATranslationError() = runBlocking {
        val translator = FakeTranslator(steps = 10_000) { Thread.sleep(1) }
        val engine = engine(translator)
        val pending = async(Dispatchers.Default) { engine.translate() }
        Thread.sleep(20)
        pending.cancel()

        val error = runCatching { pending.await() }.exceptionOrNull()
        assertTrue("got $error", error is CancellationException)
        assertFalse(error is TranslationError)
    }

    @Test
    fun loadFailuresSurfaceAsTranslationErrors() {
        val engine = TranslationEngine(
            memoryProbe = { roomy },
            makeTranslator = { _, _, _ -> throw IOException("disk on fire") },
        )
        val error = assertThrows(TranslationError.FileSystem::class.java) {
            runBlocking { engine.translate() }
        }
        assertEquals("disk on fire", error.detail)
        assertFalse(engine.isLoaded)
    }
}
