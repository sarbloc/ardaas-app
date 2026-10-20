package io.sarbloc.ardaas.ui.compose

import io.sarbloc.ardaas.composer.BentiDraft
import io.sarbloc.ardaas.translation.BentiTranslating
import io.sarbloc.ardaas.translation.BentiTranslation
import io.sarbloc.ardaas.translation.BentiTranslationService
import io.sarbloc.ardaas.translation.FakeGraphOptimizer
import io.sarbloc.ardaas.translation.FakeModelFileFetcher
import io.sarbloc.ardaas.translation.FakeTranslator
import io.sarbloc.ardaas.translation.ModelInstaller
import io.sarbloc.ardaas.translation.ModelState
import io.sarbloc.ardaas.translation.RecordingLogger
import io.sarbloc.ardaas.translation.TranslationEngine
import io.sarbloc.ardaas.translation.TranslationError
import io.sarbloc.ardaas.translation.TranslationFixtures
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Compose screen's translation lifecycle against a real service over the
 * fake fetcher and translator: one translation at a time, results parked for
 * the screen, translate-when-ready after consent, and every way out — cancel,
 * stop waiting, a failed install, and the end of the visit.
 *
 * The session runs on a single thread, as it does on the main thread in the
 * app; each test body runs on that same thread, so the session's coroutines
 * interleave with it only where the test suspends.
 */
class ComposeTranslationSessionTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var model: TranslationFixtures.SyntheticModel
    private lateinit var fetcher: FakeModelFileFetcher
    private lateinit var sessionThread: ExecutorCoroutineDispatcher
    private val log = RecordingLogger()
    private val loads = AtomicInteger()
    private val services = mutableListOf<BentiTranslationService>()
    private val sessions = mutableListOf<ComposeTranslationSession>()

    private val english = BentiDraft(typed = "Bless our family")

    @Before
    fun setUp() {
        root = File(temp.root, "BentiModel")
        model = TranslationFixtures.syntheticModel()
        fetcher = FakeModelFileFetcher(model.payloads)
        sessionThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

    @After
    fun tearDown() {
        runBlocking(sessionThread) { sessions.forEach { it.end() } }
        services.forEach { it.close() }
        sessionThread.close()
    }

    private fun makeService(translator: BentiTranslating = FakeTranslator()): BentiTranslationService {
        val installer = ModelInstaller(
            catalog = model.catalog,
            directory = root,
            fetcher = fetcher,
            optimizer = FakeGraphOptimizer(),
            availableDiskBytes = { 10_000_000 },
            log = RecordingLogger(),
        )
        val engine = TranslationEngine(
            memoryProbe = { null },
            makeTranslator = { _, _, _ ->
                loads.incrementAndGet()
                translator
            },
        )
        return BentiTranslationService(installer, engine, log = RecordingLogger()).also { services += it }
    }

    private suspend fun readyService(translator: BentiTranslating = FakeTranslator()): BentiTranslationService {
        val service = makeService(translator)
        service.download()
        eventually("the install") { service.state.value == ModelState.Ready }
        return service
    }

    private fun session(service: BentiTranslationService) =
        ComposeTranslationSession(service, sessionThread, log).also { sessions += it }

    private fun test(body: suspend () -> Unit) = runBlocking(sessionThread) { body() }

    private suspend fun eventually(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(5) }
        } catch (e: TimeoutCancellationException) {
            fail("timed out waiting for $what")
        }
    }

    /** Blocks inside the engine until [release], so a test can act mid-translation. */
    private class GatedTranslator {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val translator = FakeTranslator(steps = 2) { step ->
            if (step == 0) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
    }

    private fun translationOf(gurmukhi: String) = BentiTranslation(
        gurmukhi = gurmukhi,
        rawDevanagari = "",
        generatedIds = emptyList(),
        latencyMillis = 0,
        sessionLoadMillis = 0,
        isGurmukhiPure = true,
    )

    // Translating

    @Test
    fun startingOnAReadyModelWarmsUpWithoutTranslating() = test {
        val translator = FakeTranslator()
        val session = session(readyService(translator))

        session.start()

        eventually("the warm-up") { loads.get() == 1 }
        assertEquals(0, translator.completedCount)
        assertNull(session.pendingResult)
    }

    @Test
    fun aTranslationIsParkedForTheScreenUntilConsumed() = test {
        val session = session(readyService())
        session.start()

        session.translate(english.copy(typed = "  Bless our family\n"))
        assertTrue(session.isTranslating)
        assertTrue(session.currentStatus().isAwaitingTranslation)

        eventually("the result") { session.pendingResult != null }
        val result = requireNotNull(session.pendingResult)
        assertEquals(CompletedTranslation(gurmukhi = "ਅਰਦਾਸ Bless our family", source = "Bless our family"), result)
        assertFalse(session.isTranslating)
        assertTrue("Save waits until the screen has applied it", session.currentStatus().isAwaitingTranslation)

        session.consumeResult(result)
        assertNull(session.pendingResult)
        assertFalse(session.currentStatus().isAwaitingTranslation)
    }

    @Test
    fun aDoubleTapRunsOneTranslation() = test {
        val gate = GatedTranslator()
        val session = session(readyService(gate.translator))
        session.start()

        session.translate(english)
        session.translate(english)
        gate.release.countDown()

        eventually("the result") { session.pendingResult != null }
        assertEquals(1, gate.translator.completedCount)
        assertEquals(1, gate.translator.maxConcurrent)
    }

    @Test
    fun aFailedTranslationIsShownInlineAndLogged() = test {
        val failing = object : BentiTranslating {
            override suspend fun translate(english: String): BentiTranslation = throw TranslationError.DecodeFailure("bad step")
        }
        val session = session(readyService(failing))
        session.start()

        session.translate(english)

        eventually("the failure") { session.failure != null }
        assertEquals(TranslateFailure.Failed(TranslationError.DecodeFailure("bad step")), session.failure)
        assertNull(session.pendingResult)
        assertFalse(session.isTranslating)
        assertTrue(log.warnings.any { "Translating the benti failed" in it })

        // A new attempt clears it, as iOS's translate() does.
        session.translate(english)
        assertNull(session.failure)
    }

    @Test
    fun aBlankTranslationIsAFailureNotADraft() = test {
        val blank = object : BentiTranslating {
            override suspend fun translate(english: String): BentiTranslation = translationOf(" \n")
        }
        val session = session(readyService(blank))
        session.start()

        session.translate(english)

        eventually("the failure") { session.failure != null }
        assertEquals(TranslateFailure.EmptyResult, session.failure)
        assertNull(session.pendingResult)
    }

    @Test
    fun translateIgnoresWhatCannotBeTranslated() = test {
        val notReady = session(makeService())
        notReady.start()
        notReady.translate(english)
        assertFalse("not before the model is ready", notReady.isTranslating)

        val ready = session(readyService())
        ready.start()
        ready.translate(BentiDraft(typed = "ਅਰਦਾਸ"))
        assertFalse("not Gurmukhi input", ready.isTranslating)
        ready.translate(BentiDraft(typed = "   "))
        assertFalse("not an empty editor", ready.isTranslating)
    }

    // Consent, then translate when ready

    @Test
    fun consentingInstallsThenTranslatesWhatIsInTheEditorByThen() = test {
        val service = makeService()
        val session = session(service)
        session.start()
        session.updateDraft(english)

        session.download(allowingCellular = false)
        assertTrue(session.translateWhenReady)
        assertTrue("Save waits on the queued translation", session.currentStatus().isAwaitingTranslation)
        // The user keeps typing while it installs.
        session.updateDraft(english.copy(typed = "Bless our whole family"))

        eventually("the queued translation") { session.pendingResult != null }
        assertEquals("Bless our whole family", session.pendingResult?.source)
        assertFalse(session.translateWhenReady)
        assertTrue("cellular stays off unless allowed", fetcher.requests.none { it.allowsCellular })
    }

    @Test
    fun consentingWithMobileDataPassesItThrough() = test {
        val session = session(makeService())
        session.start()

        session.download(allowingCellular = true)

        eventually("the install") { session.modelState.value == ModelState.Ready }
        assertTrue(fetcher.requests.all { it.allowsCellular })
    }

    @Test
    fun cancelDownloadStopsTheInstallAndTheQueuedTranslation() = test {
        fetcher.delayMillis = 300
        val service = makeService()
        val session = session(service)
        session.start()
        session.updateDraft(english)

        session.download(allowingCellular = false)
        session.cancelDownload()

        assertFalse(session.translateWhenReady)
        eventually("the cancel") { service.state.value == ModelState.NotDownloaded }
        assertNull(session.pendingResult)
        assertFalse(session.isTranslating)
    }

    @Test
    fun stopWaitingDropsTheQueuedTranslationButNotTheInstall() = test {
        fetcher.delayMillis = 100
        val service = makeService()
        val session = session(service)
        session.start()
        session.updateDraft(english)

        session.download(allowingCellular = false)
        session.stopWaiting()
        assertFalse(session.currentStatus().isAwaitingTranslation)

        // Ready, and the session has seen it: the warm-up ran instead.
        eventually("the install and the warm-up") { service.state.value == ModelState.Ready && loads.get() == 1 }
        assertNull(session.pendingResult)
        assertFalse(session.isTranslating)
    }

    @Test
    fun aFailedInstallDropsTheQueuedTranslation() = test {
        fetcher.corrupt("tokenizer_src.json")
        val session = session(makeService())
        session.start()

        session.download(allowingCellular = false)

        eventually("the failed install") { session.modelState.value is ModelState.Failed }
        eventually("the dropped request") { !session.translateWhenReady }
        assertFalse(session.currentStatus().isAwaitingTranslation)
    }

    // Ending the visit

    @Test
    fun endingCancelsTheTranslationInFlightAndDropsItsResult() = test {
        val gate = GatedTranslator()
        val session = session(readyService(gate.translator))
        session.start()

        session.translate(english)
        // Suspends rather than blocks: the translation is launched on this thread.
        eventually("the engine") { gate.entered.count == 0L }
        session.end()
        gate.release.countDown()

        eventually("the translation to unwind") { !session.isTranslating }
        assertEquals("cancelled between decode steps", 0, gate.translator.completedCount)
        assertNull(session.pendingResult)
        assertNull("a cancel is not a failure", session.failure)
    }

    @Test
    fun endingCancelsAnInstallThisVisitStarted() = test {
        fetcher.delayMillis = 300
        val service = makeService()
        val session = session(service)
        session.start()

        session.download(allowingCellular = false)
        session.end()

        eventually("the cancel") { service.state.value == ModelState.NotDownloaded }
    }

    @Test
    fun endingLeavesAnInstallStartedElsewhere() = test {
        fetcher.delayMillis = 50
        val service = makeService()
        service.download()
        val session = session(service)
        session.start()

        session.end()

        eventually("the install") { service.state.value == ModelState.Ready }
    }

    @Test
    fun consentingDuringAnInstallStartedElsewhereDoesNotTakeItOver() = test {
        fetcher.delayMillis = 50
        val service = makeService()
        service.download()
        val session = session(service)
        session.start()

        session.download(allowingCellular = false)
        session.end()

        eventually("the install") { service.state.value == ModelState.Ready }
    }

    @Test
    fun nothingStartsOnceEnded() = test {
        val service = makeService()
        val session = session(service)
        session.start()
        session.end()

        session.download(allowingCellular = false)
        session.translate(english)

        assertEquals(ModelState.NotDownloaded, service.state.value)
        assertTrue(fetcher.requests.isEmpty())
        assertFalse(session.isTranslating)
        assertFalse(session.translateWhenReady)
    }
}
