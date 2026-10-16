package io.sarbloc.ardaas.translation

import android.content.Context
import android.os.StatFs
import io.sarbloc.ardaas.translation.ModelCatalog.Artifact
import io.sarbloc.ardaas.translation.ModelCatalog.Companion.toHex
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Owns everything that happens on disk: the opt-in download, SHA-256
 * verification, the one-time graph optimization, deleting the originals
 * afterwards, and removing the lot — port of
 * `Ardaas/Translation/ModelInstaller.swift`.
 *
 * Nothing here starts on its own. The installer is inert until [install] is
 * called, which is what makes the download opt-in rather than "on first use".
 * It is not re-entrant: the owning service (#118) runs one install at a time
 * and never deletes underneath one.
 *
 * Layout under [directory]: the verified artifacts at the top level, in-flight
 * downloads under `Partial/` (never read as installed), and the cache
 * directories [ModelOptimizer] owns.
 */
class ModelInstaller(
    val catalog: ModelCatalog,
    val directory: File,
    private val fetcher: ModelFileFetching,
    private val optimizer: GraphOptimizing,
    /** Free bytes on the install volume, or null when it cannot be measured (then the check is skipped). */
    private val availableDiskBytes: () -> Long?,
    private val log: TranslationLogger = TranslationLogger.Android,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** What the install is doing right now, mapped onto [ModelState] by the service. */
    sealed interface Phase {
        /** 0..1 over the bytes this install actually needs to fetch. */
        data class Downloading(val progress: Double) : Phase

        data object Optimizing : Phase
    }

    private val cache = ModelOptimizer(directory, catalog, log)

    /**
     * In-flight downloads. Unlike iOS's in-memory resume tokens these survive
     * an app kill, so an interrupted 143 MB graph resumes rather than restarts.
     */
    private val partialDirectory: File get() = File(directory, "Partial")

    /**
     * Name → SHA-256 of every artifact that passed the hash gate. Readiness
     * goes by size (re-hashing 677 MB per launch is not an option), and size
     * alone cannot tell a superseded artifact from the current one when an app
     * update re-pins a file at the same length. The ledger can: a file only
     * counts as installed when the hash it was verified against is the one
     * the catalog pins now.
     */
    private val ledgerFile: File get() = File(directory, LEDGER_NAME)

    // Inspection

    /**
     * Cheap readiness check, safe to call on every appearance.
     *
     * Files are matched by exact expected size plus the hash they were
     * verified against at download time (the ledger), rather than by
     * re-hashing ~677 MB on every launch.
     */
    fun currentState(): ModelState {
        // Ready is a promise that translate() can run. Without the engine it
        // never can, even if an earlier engine build left a complete model
        // directory behind, so never claim it here.
        if (!optimizer.isAvailable) return ModelState.NotDownloaded
        val verified = readLedger()
        val vocabularyPresent = catalog.vocabulary.all { isInstalled(it, verified) }
        // Retirement must have finished too: that is the step that proves the
        // cache loads on its own (see ModelOptimizer.isRetirementComplete).
        // An interrupted one reads as NotDownloaded; the next install resumes
        // it without re-downloading anything.
        val ready = vocabularyPresent && cache.isCacheValid() && cache.isRetirementComplete()
        return if (ready) ModelState.Ready else ModelState.NotDownloaded
    }

    /**
     * Everything this install occupies: catalog files still on disk, partial
     * downloads, the optimized cache and any unfinished scratch/retirement —
     * the real number for a settings row, so it must count what delete frees.
     */
    fun installedBytes(): Long {
        val catalogBytes = catalog.artifacts.sumOf { ModelOptimizer.fileSize(installedFile(it)) ?: 0 }
        val partialBytes = partialDirectory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
        return catalogBytes + partialBytes + cache.cacheBytes()
    }

    // Install

    /**
     * Downloads whatever is missing, verifies it, builds the optimized cache
     * and drops the originals. Throws a [TranslationError] on failure and
     * rethrows [CancellationException] on cancel.
     *
     * Cancellable at file boundaries and mid-transfer (the partial file is
     * kept for resume). The optimization step is not cancellable — it is
     * three opaque ORT rewrites — so cancellation during it takes effect
     * when it returns.
     *
     * @param onPhase progress and phase changes, from any thread.
     */
    suspend fun install(allowingCellular: Boolean, onPhase: (Phase) -> Unit) {
        // A build without ONNX Runtime can never finish the optimization step,
        // so refuse now rather than after 359 MB has been fetched.
        if (!optimizer.isAvailable) throw TranslationError.EngineUnavailable

        val verified = withContext(ioDispatcher) {
            createDirectory(partialDirectory)
            discardStalePartials()
            readLedger()
        }

        // A valid cache means the graphs have already done their job, so a
        // repair (e.g. a deleted vocabulary file) must not re-fetch 352 MB.
        val cacheIsValid = withContext(ioDispatcher) { cache.isCacheValid() }
        val wanted = if (cacheIsValid) catalog.vocabulary else catalog.artifacts
        val outstanding = wanted.filterNot { isInstalled(it, verified) }

        val totalBytes = maxOf(wanted.sumOf { it.bytes }, 1L)
        var completedBytes = wanted.filter { isInstalled(it, verified) }.sumOf { it.bytes }
        onPhase(Phase.Downloading(completedBytes.toDouble() / totalBytes))

        // Also check when nothing is left to download but the cache still has
        // to be built — e.g. killed after the downloads, before optimization.
        // That path still needs the full scratch budget, and failing here is
        // far better than dying mid-optimize with a filesystem error.
        if (outstanding.isNotEmpty() || !cacheIsValid) {
            withContext(ioDispatcher) { checkDiskSpace(outstanding, buildingCache = !cacheIsValid) }
        }

        for (artifact in outstanding) {
            currentCoroutineContext().ensureActive()
            download(artifact, allowingCellular) { written ->
                val seen = completedBytes + written.coerceIn(0, artifact.bytes)
                onPhase(Phase.Downloading(seen.toDouble() / totalBytes))
            }
            completedBytes += artifact.bytes
            onPhase(Phase.Downloading(completedBytes.toDouble() / totalBytes))
        }

        currentCoroutineContext().ensureActive()
        optimizeAndRetire(onPhase)

        if (currentState() != ModelState.Ready) {
            throw TranslationError.OptimizationFailed(
                "install",
                "install completed but the model did not become ready",
            )
        }
    }

    private suspend fun download(artifact: Artifact, allowingCellular: Boolean, onProgress: (Long) -> Unit) {
        val partial = partialFile(artifact)
        try {
            fetcher.fetch(catalog.url(artifact), partial, artifact.bytes, allowingCellular, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Everything the caller sees is a TranslationError.
            throw TranslationError.wrap(e)
        }
        // Hashing is ~140 MB of reads for the largest graph; keep it on IO.
        withContext(ioDispatcher) { verifyAndInstall(partial, artifact) }
    }

    private suspend fun optimizeAndRetire(onPhase: (Phase) -> Unit) {
        withContext(ioDispatcher) {
            if (!cache.isCacheValid()) onPhase(Phase.Optimizing)
            try {
                cache.build(optimizer)
                cache.retireSourceGraphs(optimizer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw TranslationError.wrap(e)
            }
        }
    }

    /**
     * Removes every byte this feature owns — downloads, partials, cache,
     * abandoned scratch, unfinished retirement — returning to NotDownloaded.
     * Blocking (can be ~677 MB of unlink); call it off the main thread.
     */
    fun deleteEverything() {
        if (!directory.exists()) return
        if (!directory.deleteRecursively()) {
            throw TranslationError.FileSystem("Could not delete ${directory.path}")
        }
    }

    // Verification

    /**
     * The hash gate. Only bytes that match the pinned SHA-256 are moved into
     * place; anything else is discarded and fails loudly — these bytes execute
     * as a model on the user's device, and a mismatch is either corruption or
     * substitution, neither of which a silent retry should paper over.
     */
    private fun verifyAndInstall(partial: File, artifact: Artifact) {
        val digest = try {
            sha256(partial)
        } catch (e: IOException) {
            discardPartial(partial, "an unreadable download")
            throw TranslationError.FileSystem("Reading ${artifact.name}: ${e.message ?: e}")
        }
        if (digest != artifact.sha256) {
            // The prefix is part of the broken transfer: resuming into it
            // would reproduce the mismatch, so the next attempt starts clean.
            discardPartial(partial, "a hash mismatch")
            throw TranslationError.HashMismatch(artifact.name, artifact.sha256, digest)
        }
        try {
            ModelOptimizer.move(partial, installedFile(artifact))
            // After the move: a kill in between leaves an unrecorded file,
            // which is re-fetched — never a recorded file that is not there.
            recordVerified(artifact)
        } catch (e: IOException) {
            // A partial that is still there is verified, so keep it: the retry
            // re-verifies it without spending the network again.
            throw TranslationError.FileSystem("Installing ${artifact.name}: ${e.message ?: e}")
        }
    }

    private fun readLedger(): Map<String, String> {
        val file = ledgerFile
        if (!file.isFile) return emptyMap()
        return try {
            ModelOptimizer.json.decodeFromString(ledgerSerializer, file.readText())
        } catch (e: IOException) {
            log.warn("Unreadable verification ledger; every artifact will be re-fetched", e)
            emptyMap()
        } catch (e: SerializationException) {
            log.warn("Malformed verification ledger; every artifact will be re-fetched", e)
            emptyMap()
        } catch (e: IllegalArgumentException) {
            log.warn("Malformed verification ledger; every artifact will be re-fetched", e)
            emptyMap()
        }
    }

    /** Atomic read-modify-write; only ever called from the single running install. */
    private fun recordVerified(artifact: Artifact) {
        val updated = readLedger() + (artifact.name to artifact.sha256)
        val temporary = File(directory, "$LEDGER_NAME.tmp")
        temporary.writeText(ModelOptimizer.json.encodeToString(ledgerSerializer, updated))
        ModelOptimizer.move(temporary, ledgerFile)
    }

    /**
     * Partials are named for the hash they are meant to reach, so a prefix of
     * an artifact an app update has since re-pinned is never resumed into
     * (that could only end in a hash mismatch). Anything not for the current
     * catalog is dropped up front.
     */
    private fun discardStalePartials() {
        val current = catalog.artifacts.map { partialFile(it).name }.toSet()
        for (file in partialDirectory.listFiles().orEmpty()) {
            if (file.name !in current) discardPartial(file, "the catalog moved on")
        }
    }

    private fun discardPartial(partial: File, reason: String) {
        if (partial.exists() && !partial.delete()) {
            log.warn("Could not delete ${partial.name} after $reason; the next attempt may resume into it", null)
        }
    }

    // Disk

    private fun checkDiskSpace(outstanding: List<Artifact>, buildingCache: Boolean) {
        val available = availableDiskBytes() ?: return
        // Bytes already sitting in Partial/ are space we hold, not space we need.
        val downloadBytes = outstanding.sumOf { artifact ->
            artifact.bytes - (ModelOptimizer.fileSize(partialFile(artifact)) ?: 0).coerceIn(0, artifact.bytes)
        }
        val required = downloadBytes + if (buildingCache) catalog.estimatedOptimizedCacheBytes else 0
        if (available >= required) return
        // A stale cache we are about to rebuild is space we already own: count
        // it as reclaimable rather than failing an upgrade or repair on a
        // nearly full device. removeCache clears exactly what cacheBytes counts.
        if (buildingCache) {
            val reclaimable = cache.cacheBytes()
            if (reclaimable > 0 && available + reclaimable >= required) {
                cache.removeCache()
                return
            }
        }
        throw TranslationError.InsufficientDisk(available, required)
    }

    // Helpers

    private fun installedFile(artifact: Artifact): File = File(directory, artifact.name)

    private fun partialFile(artifact: Artifact): File =
        File(partialDirectory, "${artifact.name}.${artifact.sha256.take(PARTIAL_HASH_CHARS)}.part")

    private fun isInstalled(artifact: Artifact, verified: Map<String, String>): Boolean =
        verified[artifact.name] == artifact.sha256 &&
            ModelOptimizer.fileSize(installedFile(artifact)) == artifact.bytes

    private fun createDirectory(dir: File) {
        if (!dir.mkdirs() && !dir.isDirectory) throw TranslationError.FileSystem("Cannot create ${dir.path}")
    }

    companion object {
        private const val HASH_CHUNK_BYTES = 1 shl 20
        private const val LEDGER_NAME = "verified.json"
        private const val PARTIAL_HASH_CHARS = 16
        private val ledgerSerializer = MapSerializer(String.serializer(), String.serializer())

        /**
         * The production wiring: the pinned catalog under `noBackupFilesDir`,
         * the HTTP fetcher behind the metered-network gate, and StatFs for
         * free space, and the ONNX Runtime graph rewrite.
         */
        fun create(context: Context, optimizer: GraphOptimizing = OnnxGraphOptimizer): ModelInstaller {
            val log = TranslationLogger.Android
            val catalog = ModelCatalog.PRODUCTION
            val volume = context.noBackupFilesDir
            return ModelInstaller(
                catalog = catalog,
                directory = catalog.directory(context),
                fetcher = HttpModelFileFetcher(ConnectivityDownloadNetwork(context), log),
                optimizer = optimizer,
                availableDiskBytes = { availableBytes(volume, log) },
                log = log,
            )
        }

        /** Free bytes on [path]'s volume, or null (logged) when StatFs cannot read it. */
        private fun availableBytes(path: File, log: TranslationLogger): Long? = try {
            StatFs(path.path).availableBytes
        } catch (e: IllegalArgumentException) {
            log.warn("StatFs could not read ${path.path}; skipping the free-space check", e)
            null
        }

        /** Streaming SHA-256 in 1 MiB chunks — the graphs are far too large to load whole. */
        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(HASH_CHUNK_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().toHex()
        }
    }
}
