package io.sarbloc.ardaas.translation

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Rewrites a downloaded ONNX graph so its weights can be memory-mapped, and
 * checks that the result can be loaded back on its own — port of
 * `GraphOptimizing` in `Ardaas/Translation/ModelOptimizer.swift`.
 *
 * An interface so the cache/disk-hygiene logic is testable without 352 MB of
 * real graphs. The ONNX Runtime implementation is [OnnxGraphOptimizer].
 *
 * Both calls are blocking; [ModelInstaller] runs them on an IO dispatcher.
 */
interface GraphOptimizing {
    /**
     * False when this build cannot optimize graphs at all. Lets an install be
     * refused up front instead of after 359 MB has been fetched.
     */
    val isAvailable: Boolean get() = true

    /**
     * Writes an ORT-optimized copy of [source] at [destination], with its
     * initializers in a side-car named [externalDataName] beside it.
     */
    fun optimize(source: File, destination: File, externalDataName: String)

    /**
     * Loads [graph] and throws if it cannot be used. Called with the original
     * downloads already moved out of the way, so a graph that secretly still
     * depends on them fails here rather than after they are deleted.
     */
    fun verifyLoadable(graph: File)
}

/**
 * Stands in for an engine-less build: install refuses up front and nothing
 * reads as Ready. Exercised by tests; the app wires [OnnxGraphOptimizer].
 */
object UnavailableGraphOptimizer : GraphOptimizing {
    override val isAvailable: Boolean get() = false

    override fun optimize(source: File, destination: File, externalDataName: String) {
        throw TranslationError.EngineUnavailable
    }

    override fun verifyLoadable(graph: File) {
        throw TranslationError.EngineUnavailable
    }
}

/**
 * The one-time, on-device rewrite of the inference graphs into ORT-optimized
 * graphs whose initializers live in a side-car `.data` file, and the disk
 * hygiene around it — the ONNX-free part of `ModelOptimizer.swift`.
 *
 * Initializers stored inside the .onnx protobuf are read onto the heap;
 * initializers in an external data file are memory-mapped. That is the single
 * biggest memory lever (docs/translation/MEMORY.md), which is why a cache
 * without its side-cars is rejected rather than tolerated.
 *
 * All methods block on the file system; call them off the main thread.
 */
class ModelOptimizer(
    private val modelDirectory: File,
    private val catalog: ModelCatalog,
    private val log: TranslationLogger,
) {
    /**
     * What the cache recorded about itself. Written last, so until it lands
     * the cache reads as invalid. Same field names as the iOS manifest.
     */
    @Serializable
    data class Manifest(
        val formatVersion: Int,
        val ortPackageVersion: String,
        /**
         * [ModelCatalog.revision] — the identity of the pinned artifact set
         * the cache was built from. Stronger than fingerprinting the source
         * files, and survives their deletion: the downloads were SHA-256
         * verified against exactly these constants.
         */
        val catalogRevision: String,
        /** Every file the cache produced → byte size: optimized graphs and their `.data` side-cars. */
        val outputs: Map<String, Long>,
    )

    // Locations

    val optimizedDirectory: File get() = File(modelDirectory, "Optimized")

    /** Where a new cache is assembled before being moved into place. */
    val scratchDirectory: File get() = File(modelDirectory, "Optimized.building")

    /**
     * Where the original graphs are parked while the cache is proved
     * self-sufficient. Emptied on success, restored on failure.
     */
    val retiredDirectory: File get() = File(modelDirectory, "Originals.retiring")

    /**
     * Every directory this type may hold bytes in. All are reported and
     * deleted together: a build killed part-way leaves several hundred MB
     * behind, which the user must be able to see and reclaim.
     */
    private val cacheDirectories: List<File>
        get() = listOf(optimizedDirectory, scratchDirectory, retiredDirectory)

    /**
     * Total bytes held by the optimized cache, any abandoned scratch build,
     * and any retirement that did not finish.
     */
    fun cacheBytes(): Long = cacheDirectories.sumOf { directory ->
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun removeCache() {
        for (directory in cacheDirectories) deleteBestEffort(directory, "clearing the optimized cache")
    }

    // Validation

    /**
     * True when a cache matching the current recipe, runtime and artifact
     * revision exists, and every file it recorded is still present at its
     * recorded size. Fails closed: anything unreadable is "invalid", which
     * costs a rebuild, never a broken load.
     */
    fun isCacheValid(): Boolean {
        val stored = readManifest(optimizedDirectory) ?: return false
        if (stored.formatVersion != FORMAT_VERSION ||
            stored.ortPackageVersion != ORT_PACKAGE_VERSION ||
            stored.catalogRevision != catalog.revision
        ) {
            return false
        }
        // Every graph AND its side-car must be accounted for: a cache of graphs
        // with their weights still inline would load fine and silently give up
        // the entire memory saving.
        val required = catalog.graphNames.flatMap { listOf(it, externalDataName(it)) }
        if (!required.all { it in stored.outputs }) return false
        return stored.outputs.all { (name, size) -> fileSize(File(optimizedDirectory, name)) == size }
    }

    private fun readManifest(directory: File): Manifest? {
        val file = File(directory, MANIFEST_NAME)
        if (!file.isFile) return null
        return try {
            json.decodeFromString(Manifest.serializer(), file.readText())
        } catch (e: IOException) {
            log.warn("Unreadable cache manifest; treating the cache as invalid", e)
            null
        } catch (e: SerializationException) {
            log.warn("Malformed cache manifest; treating the cache as invalid", e)
            null
        } catch (e: IllegalArgumentException) {
            log.warn("Malformed cache manifest; treating the cache as invalid", e)
            null
        }
    }

    /**
     * Records the recipe, the runtime, the artifact revision and every file
     * the cache produced. Must be called once [directory] is fully written —
     * it fingerprints whatever it finds there.
     *
     * Exposed for tests; [build] is the only caller in the app.
     */
    fun writeManifest(directory: File) {
        val outputs = (directory.listFiles() ?: throw TranslationError.FileSystem("Cannot list ${directory.path}"))
            .filter { it.isFile && it.name != MANIFEST_NAME && it.name != MANIFEST_TEMP_NAME }
            .associate { it.name to it.length() }
        val manifest = Manifest(FORMAT_VERSION, ORT_PACKAGE_VERSION, catalog.revision, outputs)
        val temporary = File(directory, MANIFEST_TEMP_NAME)
        try {
            temporary.writeText(json.encodeToString(Manifest.serializer(), manifest))
            move(temporary, File(directory, MANIFEST_NAME))
        } catch (e: IOException) {
            deleteBestEffort(temporary, "a failed manifest write")
            throw TranslationError.FileSystem("Writing the cache manifest: ${e.message ?: e}")
        }
    }

    // Build

    /**
     * Builds the optimized cache if it is missing or stale, and returns the
     * directory the translator should load its graphs from.
     *
     * Writes into a scratch directory and moves it into place, so a crash or
     * kill mid-pass can never leave a half-written cache behind.
     */
    fun build(optimizer: GraphOptimizing, onProgress: (String) -> Unit = {}): File {
        val destination = optimizedDirectory
        if (isCacheValid()) return destination

        for (name in catalog.graphNames) {
            if (!File(modelDirectory, name).isFile) throw TranslationError.MissingModelFile(name)
        }

        val scratch = scratchDirectory
        deleteBestEffort(scratch, "clearing an abandoned scratch build")
        // Drop the previous cache before rebuilding rather than after. Getting
        // here means it already failed validation, so it is dead weight — and
        // keeping it would make a routine rebuild (ORT bump, re-download)
        // demand a second cache's worth of free space.
        deleteBestEffort(destination, "dropping a stale cache")
        if (!scratch.mkdirs()) throw TranslationError.FileSystem("Cannot create ${scratch.path}")

        var moved = false
        try {
            for (name in catalog.graphNames) {
                onProgress(name)
                optimizeOne(File(modelDirectory, name), File(scratch, name), optimizer)
            }
            // Written last: until the manifest lands the cache reads as invalid.
            writeManifest(scratch)
            move(scratch, destination)
            moved = true
        } catch (e: IOException) {
            throw TranslationError.FileSystem("Installing the optimized cache: ${e.message ?: e}")
        } finally {
            // Half-written graphs are several hundred MB; never leave them.
            if (!moved) deleteBestEffort(scratch, "a failed optimization")
        }
        return destination
    }

    private fun optimizeOne(source: File, destination: File, optimizer: GraphOptimizing) {
        val externalDataName = externalDataName(destination.name)
        optimizer.optimize(source, destination, externalDataName)

        // Fail loudly rather than cache a graph whose weights are still inline:
        // it would load perfectly and quietly forfeit the whole memory saving.
        val bytes = fileSize(File(destination.parentFile, externalDataName)) ?: 0
        if (bytes <= 0) {
            throw TranslationError.OptimizationFailed(
                source.name,
                "no external initializer file was written ($externalDataName); " +
                    "this ONNX Runtime build may not support external initializers",
            )
        }
    }

    // Disk hygiene

    /**
     * True once [retireSourceGraphs] has finished: no original graph is left
     * beside the cache and none is parked. A valid manifest alone is not
     * enough for Ready — it lands before the load-back check, so a kill in
     * between would otherwise advertise a cache that was never proved
     * loadable. The originals' presence is the durable "not yet verified"
     * marker: a failed check drops the cache, so they only coexist with a
     * valid one when verification never completed.
     */
    fun isRetirementComplete(): Boolean =
        !retiredDirectory.exists() && catalog.graphNames.none { File(modelDirectory, it).exists() }

    /**
     * Deletes the original `.onnx` downloads once the optimized cache has been
     * proved to load without them.
     *
     * The originals are moved aside first, every optimized graph is loaded
     * with them absent, and only then are they dropped. Any failure puts them
     * back and throws, leaving a working (if fat) install rather than a
     * broken thin one.
     *
     * Idempotent: a no-op once the originals are gone.
     */
    fun retireSourceGraphs(optimizer: GraphOptimizing) {
        if (!isCacheValid()) {
            throw TranslationError.OptimizationFailed("cache", "refusing to delete originals without a valid cache")
        }
        val retired = retiredDirectory
        unparkLeftovers(retired)

        val present = catalog.graphNames.filter { File(modelDirectory, it).isFile }
        // Nothing to retire and nothing was parked: a previous pass completed
        // in full, verification included.
        if (present.isEmpty()) return

        if (!retired.mkdirs() && !retired.isDirectory) {
            throw TranslationError.FileSystem("Cannot create ${retired.path}")
        }
        val parked = mutableListOf<String>()
        try {
            for (name in present) {
                move(File(modelDirectory, name), File(retired, name))
                parked += name
            }
            for (name in catalog.graphNames) optimizer.verifyLoadable(File(optimizedDirectory, name))
        } catch (e: Exception) {
            restore(parked, retired)
            if (e is CancellationException) throw e
            // The cache just proved unloadable still looks structurally valid
            // (manifest + sizes), so leaving it would report Ready and route
            // translate() straight into it after a relaunch. Drop it; the
            // originals are back, so the next install rebuilds rather than
            // re-downloading.
            removeCache()
            throw TranslationError.OptimizationFailed(
                "cache",
                "optimized graphs did not load without the originals: ${e.message ?: e}",
            )
        }
        deleteBestEffort(retired, "dropping the verified originals")
    }

    /**
     * A leftover retirement directory means a previous attempt parked graphs
     * and was killed before verification finished. Unpark everything and
     * start the whole retire+verify pass clean — otherwise the leftovers are
     * either lost (deleted without a fallback) or silently skipped (cache
     * treated as verified when it never was).
     */
    private fun unparkLeftovers(retired: File) {
        if (!retired.exists()) return
        for (parked in retired.listFiles().orEmpty()) {
            val destination = File(modelDirectory, parked.name)
            if (destination.exists()) continue
            try {
                move(parked, destination)
            } catch (e: IOException) {
                log.warn("Could not restore parked ${parked.name}; it will be re-downloaded", e)
            }
        }
        deleteBestEffort(retired, "clearing an unfinished retirement")
    }

    private fun restore(parked: List<String>, retired: File) {
        for (name in parked) {
            try {
                move(File(retired, name), File(modelDirectory, name))
            } catch (e: IOException) {
                log.warn("Could not restore original $name after a failed load-back check", e)
            }
        }
        deleteBestEffort(retired, "restoring the originals")
    }

    private fun deleteBestEffort(file: File, context: String) {
        if (file.exists() && !file.deleteRecursively()) {
            log.warn("Could not delete ${file.path} while $context", null)
        }
    }

    companion object {
        /** Bump when the optimization recipe changes. Kept equal to iOS so the recipes stay comparable. */
        const val FORMAT_VERSION = 2

        /**
         * The ONNX Runtime build the cache was written by. An optimized graph
         * is only guaranteed loadable by the ORT build that wrote it, so it is
         * part of the cache key.
         *
         * A constant rather than something the live [GraphOptimizing]
         * reports: a build without ORT must still be able to read (and account
         * for, and delete) a cache an ORT build left behind. Mirrors iOS's
         * `ortPackageVersion`. Must equal the `onnxruntime` version pinned in
         * gradle/libs.versions.toml; OnnxGraphOptimizerTest enforces it.
         */
        const val ORT_PACKAGE_VERSION = "1.24.2"

        const val MANIFEST_NAME = "manifest.json"
        private const val MANIFEST_TEMP_NAME = "manifest.json.tmp"

        /**
         * The external initializer blob ORT writes beside an optimized graph.
         * The whole point of the pass: without it the weights are back inside
         * the .onnx and back on the heap.
         */
        fun externalDataName(graph: String): String = "$graph.data"

        internal val json = Json { ignoreUnknownKeys = true }

        internal fun fileSize(file: File): Long? = if (file.isFile) file.length() else null

        /**
         * Atomic rename within the install directory. Throws [IOException]
         * with the reason, which [File.renameTo] would reduce to `false`.
         */
        internal fun move(source: File, destination: File) {
            Files.move(
                source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
