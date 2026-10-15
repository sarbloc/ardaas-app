package io.sarbloc.ardaas.translation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The optimized-graph cache is derived state: if a stale cache is ever
 * accepted, the app silently runs graphs written by a different ORT build or
 * from superseded downloads. These tests pin the fail-closed behaviour and the
 * retirement of the original graphs — port of `ModelOptimizerTests.swift`.
 *
 * File system only, no ONNX Runtime.
 */
class ModelOptimizerTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var catalog: ModelCatalog
    private lateinit var log: RecordingLogger
    private lateinit var cache: ModelOptimizer

    @Before
    fun setUp() {
        root = temp.newFolder("model")
        catalog = TranslationFixtures.syntheticModel().catalog
        log = RecordingLogger()
        cache = ModelOptimizer(root, catalog, log)
    }

    // Helpers

    /** Writes placeholder source graphs of the given size. */
    private fun makeSources(bytes: Int = 32) {
        for (name in catalog.graphNames) File(root, name).writeBytes(ByteArray(bytes) { 0x41 })
    }

    /** Writes placeholder optimized graphs, each with its `*.onnx.data` side-car. */
    private fun makeCachedGraphs(): File {
        val directory = cache.optimizedDirectory
        directory.mkdirs()
        for (name in catalog.graphNames) {
            File(directory, name).writeBytes(ByteArray(8) { 0x42 })
            File(directory, "$name.data").writeBytes(ByteArray(64) { 0x43 })
        }
        return directory
    }

    private fun sidecar(directory: File, index: Int) = File(directory, catalog.graphNames[index] + ".data")

    private fun isValid() = cache.isCacheValid()

    private fun sourceExists(index: Int) = File(root, catalog.graphNames[index]).exists()

    // Cache validation

    @Test
    fun cacheIsInvalidWhenNothingHasBeenBuilt() {
        makeSources()
        assertFalse(isValid())
    }

    @Test
    fun cacheIsInvalidWithoutAManifest() {
        makeSources()
        makeCachedGraphs()
        assertFalse("graphs present but unmanifested must not be trusted", isValid())
    }

    @Test
    fun cacheIsValidWhenManifestAndGraphsMatch() {
        makeSources()
        cache.writeManifest(makeCachedGraphs())
        assertTrue(isValid())
    }

    @Test
    fun cacheIsInvalidWhenAGraphIsMissing() {
        makeSources()
        val directory = makeCachedGraphs()
        cache.writeManifest(directory)
        assertTrue(File(directory, catalog.graphNames[1]).delete())
        assertFalse(isValid())
    }

    /** An optimized graph is worthless without its initializer file; losing one must force a rebuild. */
    @Test
    fun cacheIsInvalidWhenAnInitializerSidecarIsMissing() {
        makeSources()
        val directory = makeCachedGraphs()
        cache.writeManifest(directory)
        assertTrue(isValid())

        assertTrue(sidecar(directory, 0).delete())
        assertFalse(isValid())
    }

    @Test
    fun cacheIsInvalidWhenAnInitializerSidecarIsTruncated() {
        makeSources()
        val directory = makeCachedGraphs()
        cache.writeManifest(directory)

        sidecar(directory, 2).writeBytes(ByteArray(8) { 0x43 })
        assertFalse(isValid())
    }

    /** A cache of graphs with their weights still inline would forfeit the memory saving, so it is rejected. */
    @Test
    fun cacheIsInvalidWhenNoSidecarWasEverRecorded() {
        makeSources()
        val directory = cache.optimizedDirectory
        directory.mkdirs()
        for (name in catalog.graphNames) File(directory, name).writeBytes(ByteArray(8) { 0x42 })
        cache.writeManifest(directory)
        assertFalse(isValid())
    }

    /** Keyed on the pinned artifact set, precisely so the sources can be deleted: a catalog change must invalidate. */
    @Test
    fun cacheIsInvalidatedWhenTheCatalogRevisionChanges() {
        makeSources()
        cache.writeManifest(makeCachedGraphs())
        assertTrue(isValid())

        val first = catalog.artifacts[0]
        val bumped = catalog.copy(
            artifacts = listOf(first.copy(bytes = first.bytes + 1, sha256 = "0".repeat(64))) +
                catalog.artifacts.drop(1),
        )

        assertNotEquals(catalog.revision, bumped.revision)
        assertFalse(ModelOptimizer(root, bumped, log).isCacheValid())
    }

    /** A manifest from another ORT build or recipe must not be trusted. */
    @Test
    fun cacheIsInvalidWhenTheRuntimeOrRecipeDiffers() {
        makeSources()
        val directory = makeCachedGraphs()
        cache.writeManifest(directory)
        val manifest = File(directory, ModelOptimizer.MANIFEST_NAME)
        val original = manifest.readText()

        manifest.writeText(original.replace(ModelOptimizer.ORT_PACKAGE_VERSION, "0.0.0-other"))
        assertFalse(isValid())

        manifest.writeText(
            original.replace("\"formatVersion\":${ModelOptimizer.FORMAT_VERSION}", "\"formatVersion\":1"),
        )
        assertFalse(isValid())
    }

    @Test
    fun aCorruptManifestFailsClosedAndIsLogged() {
        makeSources()
        val directory = makeCachedGraphs()
        File(directory, ModelOptimizer.MANIFEST_NAME).writeText("{ not json")
        assertFalse(isValid())
        assertEquals(1, log.warnings.size)
    }

    @Test
    fun cacheSurvivesTheSourceGraphsBeingDeleted() {
        makeSources()
        cache.writeManifest(makeCachedGraphs())

        for (name in catalog.graphNames) assertTrue(File(root, name).delete())
        assertTrue(
            "the whole point of retiring the originals is that the cache stays valid without them",
            isValid(),
        )
    }

    @Test
    fun manifestPinsTheRecipeRuntimeRevisionAndEveryProducedFile() {
        makeSources()
        val directory = makeCachedGraphs()
        cache.writeManifest(directory)

        val manifest = ModelOptimizer.json.decodeFromString(
            ModelOptimizer.Manifest.serializer(),
            File(directory, "manifest.json").readText(),
        )
        assertEquals(ModelOptimizer.FORMAT_VERSION, manifest.formatVersion)
        assertEquals(ModelOptimizer.ORT_PACKAGE_VERSION, manifest.ortPackageVersion)
        assertEquals(catalog.revision, manifest.catalogRevision)
        // Graphs and side-cars, and never the manifest itself.
        assertEquals(
            (catalog.graphNames + catalog.graphNames.map { "$it.data" }).toSet(),
            manifest.outputs.keys,
        )
        assertFalse("no temporary file is left behind", File(directory, "manifest.json.tmp").exists())
    }

    // Disk accounting

    @Test
    fun cacheBytesAndRemoval() {
        makeSources()
        assertEquals(0L, cache.cacheBytes())

        cache.writeManifest(makeCachedGraphs())
        assertTrue(cache.cacheBytes() > 0)

        cache.removeCache()
        assertEquals(0L, cache.cacheBytes())
        assertFalse(isValid())
    }

    /** A build killed part-way strands hundreds of MB in scratch; the user must be able to see and reclaim it. */
    @Test
    fun abandonedScratchIsCountedAndReclaimable() {
        makeSources()
        val scratch = cache.scratchDirectory
        scratch.mkdirs()
        File(scratch, catalog.graphNames[0]).writeBytes(ByteArray(4096) { 0x44 })

        assertEquals("an abandoned scratch build must be visible on the disk row", 4096L, cache.cacheBytes())
        assertFalse(isValid())

        cache.removeCache()
        assertEquals(0L, cache.cacheBytes())
        assertFalse(scratch.exists())
    }

    // Build

    @Test
    fun buildProducesAValidCacheAndIsIdempotent() {
        makeSources()
        val optimizer = FakeGraphOptimizer()

        val built = cache.build(optimizer)

        assertEquals(cache.optimizedDirectory, built)
        assertTrue(isValid())
        assertEquals(catalog.graphNames.sorted(), optimizer.optimizedGraphs.sorted())

        cache.build(optimizer)
        assertEquals("a valid cache must not be rebuilt", catalog.graphNames.size, optimizer.optimizedGraphs.size)
    }

    @Test
    fun buildFailsWhenNoSidecarIsWritten() {
        makeSources()
        val optimizer = FakeGraphOptimizer().apply { writesSidecar = false }

        assertThrows(TranslationError.OptimizationFailed::class.java) { cache.build(optimizer) }
        assertFalse(isValid())
        assertFalse("a failed build must not strand hundreds of MB of scratch", cache.scratchDirectory.exists())
    }

    @Test
    fun buildFailsWhenASourceGraphIsMissing() {
        makeSources()
        assertTrue(File(root, catalog.graphNames[2]).delete())

        val error = assertThrows(TranslationError::class.java) { cache.build(FakeGraphOptimizer()) }
        assertEquals(TranslationError.MissingModelFile(catalog.graphNames[2]), error)
    }

    // Retiring the originals

    @Test
    fun retireDeletesTheOriginalsOnlyAfterVerifyingTheCache() {
        makeSources()
        val optimizer = FakeGraphOptimizer()
        cache.build(optimizer)

        cache.retireSourceGraphs(optimizer)

        assertEquals(catalog.graphNames.sorted(), optimizer.verifiedGraphs.sorted())
        for (index in catalog.graphNames.indices) assertFalse(sourceExists(index))
        assertTrue(isValid())
        assertFalse(cache.retiredDirectory.exists())
    }

    /** If the optimized graphs are not self-sufficient the originals come back — a fat working install beats a thin broken one. */
    @Test
    fun retireRestoresTheOriginalsWhenVerificationFails() {
        makeSources()
        val optimizer = FakeGraphOptimizer()
        cache.build(optimizer)
        optimizer.verifyError = TranslationError.SessionFailure("cannot load external data")

        assertThrows(TranslationError.OptimizationFailed::class.java) { cache.retireSourceGraphs(optimizer) }

        for (index in catalog.graphNames.indices) assertTrue("the originals must be put back", sourceExists(index))
        assertFalse("the staging directory must not be left behind", cache.retiredDirectory.exists())
        assertFalse("a cache proved unloadable must not keep reading as valid", isValid())
    }

    @Test
    fun retireRefusesWithoutAValidCache() {
        makeSources()
        assertThrows(TranslationError.OptimizationFailed::class.java) {
            cache.retireSourceGraphs(FakeGraphOptimizer())
        }
        for (index in catalog.graphNames.indices) assertTrue(sourceExists(index))
    }

    @Test
    fun retireIsIdempotent() {
        makeSources()
        val optimizer = FakeGraphOptimizer()
        cache.build(optimizer)
        cache.retireSourceGraphs(optimizer)
        val verifiedOnce = optimizer.verifiedGraphs.size

        cache.retireSourceGraphs(optimizer)
        assertEquals("with nothing left to retire there is nothing to verify", verifiedOnce, optimizer.verifiedGraphs.size)
        assertTrue(isValid())
    }

    /** A kill mid-retirement leaves originals parked; the next pass must unpark and re-verify, not skip or lose them. */
    @Test
    fun anUnfinishedRetirementIsUnparkedAndReverified() {
        makeSources()
        val optimizer = FakeGraphOptimizer()
        cache.build(optimizer)
        val retired = cache.retiredDirectory
        retired.mkdirs()
        val parked = catalog.graphNames[0]
        assertTrue(File(root, parked).renameTo(File(retired, parked)))

        optimizer.verifyError = TranslationError.SessionFailure("still needs the originals")
        assertThrows(TranslationError.OptimizationFailed::class.java) { cache.retireSourceGraphs(optimizer) }

        for (index in catalog.graphNames.indices) {
            assertTrue("the parked original must be back too", sourceExists(index))
        }
        assertFalse(retired.exists())
    }
}
