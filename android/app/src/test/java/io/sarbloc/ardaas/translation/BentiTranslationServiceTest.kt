package io.sarbloc.ardaas.translation

import android.content.ComponentCallbacks2
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * The public service: the state machine the Compose UI will render, the
 * opt-in rule, and the engine boundary — port of
 * `BentiTranslationServiceTests.swift`, plus the cross-thread cases the
 * Kotlin port has to get right without `@MainActor` (superseded installs,
 * deletes racing installs and translations).
 */
class BentiTranslationServiceTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var model: TranslationFixtures.SyntheticModel
    private lateinit var fetcher: FakeModelFileFetcher
    private lateinit var optimizer: FakeGraphOptimizer
    private val services = mutableListOf<BentiTranslationService>()

    @Before
    fun setUp() {
        root = File(temp.root, "BentiModel")
        model = TranslationFixtures.syntheticModel()
        fetcher = FakeModelFileFetcher(model.payloads)
        optimizer = FakeGraphOptimizer()
    }

    @After
    fun tearDown() {
        services.forEach { it.close() }
    }

    private fun makeInstaller() = ModelInstaller(
        catalog = model.catalog,
        directory = root,
        fetcher = fetcher,
        optimizer = optimizer,
        availableDiskBytes = { 10_000_000 },
        log = RecordingLogger(),
    )

    private fun makeEngine(makeTranslator: () -> BentiTranslating = { FakeTranslator() }) = TranslationEngine(
        memoryProbe = { null },
        makeTranslator = { _, _, _ -> makeTranslator() },
    )

    private fun makeService(engine: TranslationEngine = makeEngine()) =
        BentiTranslationService(makeInstaller(), engine, Dispatchers.IO, RecordingLogger()).also { services += it }

    private suspend fun BentiTranslationService.awaitState(predicate: (ModelState) -> Boolean): ModelState =
        withTimeout(10_000) { state.first(predicate) }

    private suspend fun readyService(engine: TranslationEngine = makeEngine()): BentiTranslationService {
        val service = makeService(engine)
        service.download()
        service.awaitState { it == ModelState.Ready }
        return service
    }

    private suspend fun BentiTranslationService.translateError(text: String): TranslationError? = try {
        translate(text)
        null
    } catch (e: TranslationError) {
        e
    }

    // Opt-in

    @Test
    fun aFreshServiceIsInertAndNotDownloaded() {
        val service = makeService()
        assertEquals(ModelState.NotDownloaded, service.state.value)
        assertEquals(0L, service.installedBytes.value)
        assertTrue("constructing the service must not start a download", fetcher.requests.isEmpty())
        assertFalse(root.exists())
    }

    @Test
    fun sizeDisclosureIsTheSumOfThePinnedFileSizes() {
        val service = makeService()
        assertEquals(model.catalog.artifacts.sumOf { it.bytes }, service.downloadBytes)
        assertEquals(service.downloadBytes + service.optimizedCacheBytes, service.peakDiskBytes)
        assertTrue(service.peakDiskBytes > service.installedDiskBytes)
    }

    @Test
    fun anInstalledModelReadsAsReadyFromTheFirstFrame() = runBlocking {
        readyService().close()
        val reopened = makeService()
        assertEquals(ModelState.Ready, reopened.state.value)
        assertTrue(reopened.installedBytes.value > 0)
    }

    // State machine

    @Test
    fun downloadDrivesTheStateMachineToReady() = runBlocking {
        val service = makeService()
        // Every state, as it is set. Reading `state.value` right after
        // download() raced the install (#151): on a fast runner it had
        // already moved past Downloading(0.0). Unconfined + UNDISPATCHED
        // subscribes before download() and records inline, so the state
        // download() sets synchronously is always caught.
        val seen = CopyOnWriteArrayList<ModelState>()
        val recorder = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            service.state.collect { seen += it }
        }

        service.download()
        assertEquals(ModelState.Downloading(0.0), seen.getOrNull(1))

        service.awaitState { it == ModelState.Ready }
        recorder.cancel()
        assertEquals(ModelState.NotDownloaded, seen.first())
        assertTrue("no failure on the way: $seen", seen.none { it.error != null })
        assertTrue(service.installedBytes.value > 0)
        assertTrue("cellular stays off unless asked for", fetcher.requests.none { it.allowsCellular })
    }

    @Test
    fun downloadIsANoOpWhileOneIsRunningAndOnceReady() = runBlocking {
        fetcher.delayMillis = 50
        val service = makeService()
        service.download()
        service.download()
        service.awaitState { it == ModelState.Ready }
        val fetched = fetcher.requestCount
        assertEquals(model.catalog.artifacts.size, fetched)

        service.download()
        delay(100)
        assertEquals(ModelState.Ready, service.state.value)
        assertEquals(fetched, fetcher.requestCount)
    }

    @Test
    fun hashMismatchSurfacesAsAFailedState() = runBlocking {
        fetcher.corrupt("tokenizer_src.json")
        val service = makeService()

        service.download()
        val error = service.awaitState { it.error != null }.error
        if (error !is TranslationError.HashMismatch) return@runBlocking fail("expected HashMismatch, got $error")
        assertEquals("tokenizer_src.json", error.file)
    }

    @Test
    fun cellularRefusalSurfacesAsAFailedStateAndOptingInGetsThrough() = runBlocking {
        fetcher.requiresCellular = true
        val service = makeService()

        service.download()
        assertEquals(TranslationError.CellularNotAllowed, service.awaitState { it.error != null }.error)

        service.download(allowingCellular = true)
        service.awaitState { it == ModelState.Ready }
        assertTrue(fetcher.requests.last().allowsCellular)
    }

    @Test
    fun refreshDoesNotPaperOverAnUnacknowledgedFailure() = runBlocking {
        fetcher.requiresCellular = true
        val service = makeService()
        service.download()
        service.awaitState { it.error != null }

        service.refresh()
        assertEquals(TranslationError.CellularNotAllowed, service.state.value.error)
    }

    @Test
    fun cancelReturnsToNotDownloadedRatherThanFailed() = runBlocking {
        fetcher.delayMillis = 400
        val service = makeService()

        service.download()
        withTimeout(5_000) { while (fetcher.requestCount == 0) delay(5) }
        service.cancelDownload()

        assertEquals(ModelState.NotDownloaded, service.state.value)
        assertNull("cancelling is not failing", service.state.value.error)

        // The superseded install must not resurrect its own outcome later.
        delay(600)
        assertEquals(ModelState.NotDownloaded, service.state.value)
    }

    @Test
    fun aDownloadAfterCancelWaitsForTheOldInstallAndCompletes() = runBlocking {
        fetcher.delayMillis = 200
        val service = makeService()
        service.download()
        withTimeout(5_000) { while (fetcher.requestCount == 0) delay(5) }
        service.cancelDownload()

        fetcher.delayMillis = 0
        service.download()
        service.awaitState { it == ModelState.Ready }
        assertEquals(ModelState.Ready, makeInstaller().currentState())
    }

    @Test
    fun deleteModelReturnsToNotDownloaded() = runBlocking {
        val service = readyService()

        service.deleteModel()

        assertEquals(ModelState.NotDownloaded, service.state.value)
        assertEquals(0L, service.installedBytes.value)
        assertFalse(root.exists())
    }

    @Test
    fun deleteIsSafeBeforeAnythingWasDownloaded() = runBlocking {
        val service = makeService()
        service.deleteModel()
        assertEquals(ModelState.NotDownloaded, service.state.value)
    }

    @Test
    fun deleteMidDownloadWaitsTheInstallOutAndLeavesNothing() = runBlocking {
        fetcher.delayMillis = 100
        val service = makeService()
        service.download()
        withTimeout(5_000) { while (fetcher.requestCount == 0) delay(5) }

        service.deleteModel()

        assertEquals(ModelState.NotDownloaded, service.state.value)
        delay(300)
        assertEquals("the cancelled install must not write after the delete", ModelState.NotDownloaded, service.state.value)
        assertFalse(root.exists())
    }

    // Translate

    @Test
    fun translateBeforeReadyThrowsNotReady() = runBlocking {
        assertEquals(TranslationError.NotReady, makeService().translateError("Please bless my family."))
    }

    @Test
    fun translateOnAReadyModelReturnsGurmukhiForTrimmedInput() = runBlocking {
        val service = readyService()
        val output = service.translate("  Please bless my family.\n")
        assertEquals("the service should hand the engine trimmed text", "ਅਰਦਾਸ Please bless my family.", output.gurmukhi)
        assertFalse("the purity flag is carried through", output.isGurmukhiPure)
    }

    @Test
    fun emptyInputNeverReachesTheEngine() = runBlocking {
        var built = 0
        val service = readyService(makeEngine { built++; FakeTranslator() })

        val output = service.translate("   \n ")
        assertEquals("", output.gurmukhi)
        assertTrue(output.isGurmukhiPure)
        assertEquals(0, built)
    }

    @Test
    fun translateWithoutTheEngineThrowsEngineUnavailable() = runBlocking {
        val service = readyService(makeEngine { throw TranslationError.EngineUnavailable })
        assertEquals(TranslationError.EngineUnavailable, service.translateError("Please bless my family."))
    }

    @Test
    fun deleteWaitsForAnInFlightTranslationAndRefusesNewOnes() = runBlocking {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val translator = FakeTranslator { entered.countDown(); proceed.await(5, TimeUnit.SECONDS) }
        val service = readyService(makeEngine { translator })

        val translation = async(Dispatchers.Default) { service.translate("bless us") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        // Undispatched: deleteModel has registered the delete by the time async returns.
        val deleting = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { service.deleteModel() }
        assertEquals(TranslationError.NotReady, service.translateError("again"))

        delay(100)
        assertTrue("the model must survive while a translation is reading it", root.exists())
        assertFalse(deleting.isCompleted)

        proceed.countDown()
        assertEquals("ਅਰਦਾਸ bless us", translation.await().gurmukhi)
        deleting.await()
        assertEquals(ModelState.NotDownloaded, service.state.value)
        assertFalse(root.exists())
    }

    @Test
    fun aDeleteOutlivesItsCaller() = runBlocking {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val translator = FakeTranslator { entered.countDown(); proceed.await(5, TimeUnit.SECONDS) }
        val service = readyService(makeEngine { translator })
        val translation = async(Dispatchers.Default) { service.translate("hold") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val caller = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { service.deleteModel() }
        caller.cancel()
        proceed.countDown()
        translation.await()

        service.awaitState { it == ModelState.NotDownloaded }
        assertFalse(root.exists())
    }

    // Warm-up

    @Test
    fun warmUpBeforeReadyLoadsNothing() = runBlocking {
        var built = 0
        val engine = makeEngine { built++; FakeTranslator() }
        val service = makeService(engine)

        assertFalse(service.warmUp())
        assertEquals(0, built)
        assertFalse(engine.isLoaded)
        assertTrue("warm-up must never start a download", fetcher.requests.isEmpty())
    }

    @Test
    fun warmUpOnAReadyModelPreloadsSoTheFirstTranslationSkipsTheLoad() = runBlocking {
        var built = 0
        val engine = makeEngine { built++; FakeTranslator() }
        val service = readyService(engine)

        assertTrue(service.warmUp())
        assertTrue(engine.isLoaded)
        assertFalse("already resident", service.warmUp())

        assertEquals("ਅਰਦਾਸ bless us", service.translate("bless us").gurmukhi)
        assertEquals(1, built)
    }

    /** A load failure is the translation's to report; warm-up logs it and carries on. */
    @Test
    fun aFailedWarmUpIsLoggedNotThrown() = runBlocking {
        val log = RecordingLogger()
        val engine = makeEngine { throw TranslationError.MissingModelFile("dict.TGT.json") }
        val service = BentiTranslationService(makeInstaller(), engine, Dispatchers.IO, log).also { services += it }
        service.download()
        service.awaitState { it == ModelState.Ready }

        assertFalse(service.warmUp())
        assertFalse(engine.isLoaded)
        assertTrue(log.warnings.toString(), log.warnings.any { "warm-up" in it && "dict.TGT.json" in it })
    }

    // Memory pressure

    @Test
    fun trimMemoryReachesTheEngine() = runBlocking {
        val engine = makeEngine()
        val service = readyService(engine)
        service.translate("load it")
        assertTrue(engine.isLoaded)

        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertFalse(engine.isLoaded)
    }
}
