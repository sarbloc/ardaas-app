package io.sarbloc.ardaas.translation

import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The download state machine, driven end to end against a fake fetcher and a
 * fake graph optimizer — port of `ModelInstallerTests.swift`.
 *
 * The real 359 MB artifact cannot be in CI, so the catalog is synthetic —
 * same shape, tiny payloads, real SHA-256s. The hash gate, the cellular guard,
 * cancellation, the disk-space refusal, source-graph retirement and deletion
 * are all the production code paths.
 */
class ModelInstallerTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var model: TranslationFixtures.SyntheticModel
    private lateinit var fetcher: FakeModelFileFetcher
    private lateinit var optimizer: FakeGraphOptimizer

    @Before
    fun setUp() {
        root = File(temp.root, "BentiModel")
        model = TranslationFixtures.syntheticModel()
        fetcher = FakeModelFileFetcher(model.payloads)
        optimizer = FakeGraphOptimizer()
    }

    private fun makeInstaller(
        availableDiskBytes: Long? = 10_000_000,
        graphOptimizer: GraphOptimizing = optimizer,
    ) = ModelInstaller(
        catalog = model.catalog,
        directory = root,
        fetcher = fetcher,
        optimizer = graphOptimizer,
        availableDiskBytes = { availableDiskBytes },
        log = RecordingLogger(),
    )

    private fun exists(name: String) = File(root, name).exists()

    private fun partial(name: String) = File(root, "Partial/$name.part")

    /** Collects every phase the installer reports, from whatever thread. */
    private class PhaseLog {
        private val phases = java.util.Collections.synchronizedList(mutableListOf<ModelInstaller.Phase>())

        val progressValues: List<Double>
            get() = synchronized(phases) { phases.filterIsInstance<ModelInstaller.Phase.Downloading>().map { it.progress } }

        val sawOptimizing: Boolean get() = synchronized(phases) { ModelInstaller.Phase.Optimizing in phases }

        fun append(phase: ModelInstaller.Phase) {
            phases += phase
        }
    }

    private suspend fun ModelInstaller.installOrThrow(allowingCellular: Boolean = false): TranslationError? =
        try {
            install(allowingCellular) {}
            null
        } catch (e: TranslationError) {
            e
        }

    // Opt-in

    @Test
    fun nothingIsFetchedUntilInstallIsCalled() {
        val installer = makeInstaller()
        assertEquals(ModelState.NotDownloaded, installer.currentState())
        assertEquals(0L, installer.installedBytes())
        assertTrue("constructing the installer must not put a single byte on the wire", fetcher.requests.isEmpty())
        assertFalse(root.exists())
    }

    /** Without the engine the optimization step cannot complete, so it must not spend the user's bandwidth finding out. */
    @Test
    fun aBuildWithoutTheEngineRefusesBeforeDownloading() = runBlocking {
        val installer = makeInstaller(graphOptimizer = UnavailableGraphOptimizer)

        assertEquals(TranslationError.EngineUnavailable, installer.installOrThrow())
        assertTrue(fetcher.requests.isEmpty())
        assertFalse(root.exists())
    }

    // Happy path

    @Test
    fun freshInstallDownloadsOptimizesAndBecomesReady() = runBlocking {
        val installer = makeInstaller()
        val log = PhaseLog()

        installer.install(allowingCellular = false, onPhase = log::append)

        assertEquals(ModelState.Ready, installer.currentState())
        assertEquals(model.catalog.artifacts.map { it.name }.toSet(), fetcher.requests.map { it.name }.toSet())
        assertTrue(log.sawOptimizing)
        assertEquals(1.0, log.progressValues.last(), 0.0001)
        assertEquals("download progress must never go backwards", log.progressValues.sorted(), log.progressValues)
        assertEquals(model.catalog.graphNames.sorted(), optimizer.optimizedGraphs.sorted())
    }

    /** Disk hygiene: the originals are worth ~352 MB in production and the optimized cache does not need them. */
    @Test
    fun originalGraphsAreDeletedAfterOptimization() = runBlocking {
        val installer = makeInstaller()
        installer.install(false) {}

        for (name in model.catalog.graphNames) assertFalse("$name should have been retired", exists(name))
        for (artifact in model.catalog.vocabulary) {
            assertTrue("${artifact.name} is needed at every translation", exists(artifact.name))
        }
        assertEquals(
            "every optimized graph must be proved loadable before the originals go",
            model.catalog.graphNames.sorted(),
            optimizer.verifiedGraphs.sorted(),
        )
        assertFalse(
            "the retirement staging directory must not survive a successful install",
            ModelOptimizer(root, model.catalog, RecordingLogger()).retiredDirectory.exists(),
        )
    }

    @Test
    fun alreadyInstalledFilesAreNotRefetched() = runBlocking {
        val installer = makeInstaller()
        installer.install(false) {}
        val firstPass = fetcher.requestCount

        installer.install(false) {}
        assertEquals("a second install over a valid cache must not re-download", firstPass, fetcher.requestCount)
    }

    /** A repair (a vocabulary file lost to a cleanup) must not re-fetch the graphs, already consumed and deleted. */
    @Test
    fun repairDoesNotRefetchTheGraphs() = runBlocking {
        val installer = makeInstaller()
        installer.install(false) {}
        assertTrue(File(root, "dict.TGT.json").delete())
        assertEquals(ModelState.NotDownloaded, installer.currentState())

        installer.install(false) {}

        assertEquals(ModelState.Ready, installer.currentState())
        assertEquals(listOf("dict.TGT.json"), fetcher.requests.takeLast(1).map { it.name })
    }

    // Hash verification

    @Test
    fun hashMismatchFailsLoudlyAndInstallsNothing() = runBlocking {
        fetcher.corrupt("decoder_model.onnx")
        val installer = makeInstaller()

        val error = installer.installOrThrow()
        if (error !is TranslationError.HashMismatch) return@runBlocking fail("expected HashMismatch, got $error")
        assertEquals("decoder_model.onnx", error.file)
        assertTrue(error.expected != error.actual)

        assertFalse("bytes that failed verification must never land", exists("decoder_model.onnx"))
        assertFalse("nor linger as a resumable prefix", partial("decoder_model.onnx").exists())
        assertEquals(ModelState.NotDownloaded, installer.currentState())
    }

    // Cellular guard

    @Test
    fun cellularIsRefusedByDefaultAndAllowedOnExplicitOptIn() = runBlocking {
        fetcher.requiresCellular = true
        val installer = makeInstaller()

        assertEquals(TranslationError.CellularNotAllowed, installer.installOrThrow(allowingCellular = false))
        assertEquals(ModelState.NotDownloaded, installer.currentState())
        assertEquals(false, fetcher.requests.first().allowsCellular)

        installer.install(allowingCellular = true) {}
        assertEquals(ModelState.Ready, installer.currentState())
        assertTrue(fetcher.requests.takeLast(5).all { it.allowsCellular })
    }

    // Failure and resume

    @Test
    fun aFailedFileIsResumedFromItsPartialDownload() = runBlocking {
        fetcher.leavesPartialOnFailure = true
        fetcher.failOnce["encoder_model.onnx"] = TranslationError.Download("connection lost")
        val installer = makeInstaller()

        assertEquals(TranslationError.Download("connection lost"), installer.installOrThrow())
        assertTrue("the prefix is kept for resume", partial("encoder_model.onnx").length() > 0)
        assertTrue("a partial is never read as installed", installer.currentState() == ModelState.NotDownloaded)

        installer.install(false) {}
        assertEquals(ModelState.Ready, installer.currentState())

        val encoderAttempts = fetcher.requests.filter { it.name == "encoder_model.onnx" }
        assertEquals(2, encoderAttempts.size)
        assertFalse(encoderAttempts[0].hadPartial)
        assertTrue("the retry should resume rather than restart", encoderAttempts[1].hadPartial)
        assertFalse(partial("encoder_model.onnx").exists())
    }

    /** A hash mismatch means the bytes are wrong, so resuming into the same broken transfer is the one thing not to do. */
    @Test
    fun hashMismatchDropsThePartialDownload() = runBlocking {
        val name = "encoder_model.onnx"
        fetcher.leavesPartialOnFailure = true
        fetcher.failOnce[name] = TranslationError.Download("connection lost")
        val installer = makeInstaller()

        // 1. Transport failure -> a prefix is kept.
        installer.installOrThrow()
        // 2. The resumed transfer returns corrupt bytes.
        fetcher.corrupt(name)
        assertTrue(installer.installOrThrow() is TranslationError.HashMismatch)
        assertTrue("attempt 2 should have resumed", fetcher.requests.last { it.name == name }.hadPartial)

        // 3. The next attempt must start clean rather than resume into the same corruption.
        fetcher.setPayload(name, model.payload(name))
        installer.install(false) {}

        val attempts = fetcher.requests.filter { it.name == name }
        assertEquals(3, attempts.size)
        assertFalse(attempts[2].hadPartial)
        assertEquals(ModelState.Ready, installer.currentState())
    }

    // Cancellation

    @Test
    fun cancellationStopsTheInstallWithoutFailing() = runBlocking {
        fetcher.delayMillis = 400
        val installer = makeInstaller()

        val install = async(Dispatchers.Default) { installer.install(false) {} }
        // Wait until the first transfer is genuinely in flight.
        var spins = 0
        while (fetcher.requestCount == 0 && spins < 200) {
            delay(5)
            spins++
        }
        assertTrue("the fetch never started", fetcher.requestCount > 0)
        install.cancel()

        try {
            install.await()
            fail("a cancelled install must not report success")
        } catch (e: CancellationException) {
            // Expected: cancellation is not a failure.
        }
        assertEquals(ModelState.NotDownloaded, installer.currentState())
    }

    // Disk

    @Test
    fun insufficientDiskRefusesBeforeAnythingIsFetched() = runBlocking {
        val installer = makeInstaller(availableDiskBytes = 16)

        val error = installer.installOrThrow()
        if (error !is TranslationError.InsufficientDisk) return@runBlocking fail("expected InsufficientDisk, got $error")
        assertEquals(16L, error.availableBytes)
        assertTrue(error.requiredBytes > error.availableBytes)
        assertTrue(fetcher.requests.isEmpty())
    }

    /** Bytes already in a partial download are held, not needed, so they come off the requirement. */
    @Test
    fun partialBytesAlreadyOnDiskReduceTheSpaceRequired() = runBlocking {
        val name = "encoder_model.onnx"
        val payload = model.payload(name)
        File(root, "Partial").mkdirs()
        partial(name).writeBytes(payload.copyOf(payload.size / 2))
        val installer = makeInstaller(availableDiskBytes = 16)

        val error = installer.installOrThrow()
        if (error !is TranslationError.InsufficientDisk) return@runBlocking fail("expected InsufficientDisk, got $error")
        val expected = model.catalog.downloadBytes - payload.size / 2 + model.catalog.estimatedOptimizedCacheBytes
        assertEquals(expected, error.requiredBytes)
    }

    @Test
    fun unknownFreeSpaceDoesNotBlockTheInstall() = runBlocking {
        val installer = makeInstaller(availableDiskBytes = null)
        installer.install(false) {}
        assertEquals(ModelState.Ready, installer.currentState())
    }

    // Deletion

    @Test
    fun deleteEverythingReturnsToNotDownloaded() = runBlocking {
        val installer = makeInstaller()
        installer.install(false) {}
        assertTrue(installer.installedBytes() > 0)

        installer.deleteEverything()

        assertEquals(ModelState.NotDownloaded, installer.currentState())
        assertEquals(0L, installer.installedBytes())
        assertFalse(root.exists())
    }

    /** A half-fetched graph is real disk the user must see on the settings row and get back on delete. */
    @Test
    fun partialDownloadsAreCountedAndDeleted() = runBlocking {
        fetcher.leavesPartialOnFailure = true
        fetcher.failOnce["encoder_model.onnx"] = TranslationError.Download("connection lost")
        val installer = makeInstaller()
        installer.installOrThrow()

        val held = partial("encoder_model.onnx").length()
        assertTrue(held > 0)
        assertEquals(held, installer.installedBytes())

        installer.deleteEverything()
        assertEquals(0L, installer.installedBytes())
        assertFalse(root.exists())
    }

    @Test
    fun deleteIsSafeWhenNothingWasEverInstalled() {
        val installer = makeInstaller()
        installer.deleteEverything()
        assertEquals(ModelState.NotDownloaded, installer.currentState())
    }
}
