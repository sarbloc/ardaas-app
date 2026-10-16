package io.sarbloc.ardaas.translation

import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * The public face of on-device translation — the whole API the Compose UI
 * (#120) is meant to use. Port of `Ardaas/Translation/BentiTranslationService.swift`.
 *
 * Contract (docs/translation/README.md, "Public API"):
 * - Nothing downloads until [download] is called. There is no "fetch on first
 *   translate"; [translate] before [ModelState.Ready] throws
 *   [TranslationError.NotReady].
 * - Cellular is off by default; a caller that wants it must say so.
 * - [state] is the only status surface, and it is value-equal.
 * - Every failure is a [TranslationError].
 *
 * Lives as long as the process (one instance, in `AppContainer`), not a
 * screen: an install must keep going when the screen that started it closes,
 * and a second instance would race the first over the same files. It
 * therefore owns its own scope rather than borrowing a caller's.
 *
 * Thread-safety: iOS gets it from `@MainActor`. Here every public member may
 * be called from any thread; the install bookkeeping is guarded by [lock],
 * which is never held across a suspension or a disk read, and state is
 * published through [StateFlow]s.
 *
 * @param dispatcher where installs and deletes run. Must actually dispatch
 *   (not `Unconfined`): the bookkeeping assumes a launched job does not run
 *   inline inside the call that launched it.
 */
class BentiTranslationService(
    private val installer: ModelInstaller,
    private val engine: TranslationEngine,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: TranslationLogger = TranslationLogger.Android,
) {
    /**
     * SupervisorJob so one failed install does not cancel the scope for the
     * next. The handler is a last resort — every expected failure is caught
     * and published as [ModelState.Failed] — but an unexpected throwable must
     * be logged, not crash the process from a background thread.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineName("BentiTranslationService") +
            CoroutineExceptionHandler { _, error -> log.warn("Unexpected failure in the translation service", error) },
    )

    private val lock = Any()

    // Size disclosure (shown before the user opts in)

    /** Exact bytes over the network: the sum of the pinned per-file sizes the download is verified against. */
    val downloadBytes: Long get() = installer.catalog.downloadBytes

    /** Estimated size of the one-time optimized cache built on device. */
    val optimizedCacheBytes: Long get() = installer.catalog.estimatedOptimizedCacheBytes

    /** Free space needed to get through the install, while the download and the cache briefly coexist. */
    val peakDiskBytes: Long get() = installer.catalog.estimatedPeakDiskBytes

    /** Disk still held once the originals have been dropped. */
    val installedDiskBytes: Long get() = installer.catalog.estimatedInstalledBytes

    // Observable state. Read from disk up front (a size check plus one small
    // JSON file) so the first frame tells the truth instead of flashing
    // "not downloaded" over an installed model.

    private val mutableState = MutableStateFlow(installer.currentState())

    /** The only status surface. */
    val state: StateFlow<ModelState> = mutableState.asStateFlow()

    private val mutableInstalledBytes = MutableStateFlow(installer.installedBytes())

    /** Actual bytes on disk, so a settings screen can show the truth rather than the estimate. */
    val installedBytes: StateFlow<Long> = mutableInstalledBytes.asStateFlow()

    // Bookkeeping, all guarded by [lock].

    private var installJob: Job? = null

    /**
     * Cancelled installs that may still be unwinding — optimization in
     * particular cannot be interrupted mid-flight. The next install and any
     * delete wait for all of them rather than racing them over the same
     * files. A list, not a single job: an install cancelled before it even
     * started never waits for its own predecessor, so the chain cannot be
     * delegated to it.
     */
    private val unwindingInstallJobs = mutableListOf<Job>()

    /** Non-null for the whole of a delete; blocks new installs and translations. */
    private var deleteJob: Job? = null

    /**
     * Bumped whenever an install is superseded (cancel, delete, restart) and
     * whenever a settled state is published, so a late progress report, a
     * superseded install's outcome or a [refresh] whose disk read predates
     * the newest state can never overwrite it.
     */
    private var generation = 0L

    /** Translations past the readiness check; a delete waits for these before removing their files. */
    private val inFlightTranslations = MutableStateFlow(0)

    private val isDeleting: Boolean get() = deleteJob != null

    /**
     * Re-reads disk. Cheap (size checks plus one small JSON manifest), so it
     * is safe to call on every appearance, from the main thread — but it never
     * overrides an install or delete that is running, nor a failure the user
     * has not acknowledged (unless the model turns out to be ready after all).
     */
    fun refresh() {
        val token = synchronized(lock) { generation }
        val onDisk = installer.currentState()
        val bytes = installer.installedBytes()
        synchronized(lock) {
            if (token != generation || isDeleting) return
            mutableInstalledBytes.value = bytes
            val current = mutableState.value
            if (current.isWorking) return
            if (current.error != null && onDisk != ModelState.Ready) return
            mutableState.value = onDisk
        }
    }

    // Opt-in install

    /**
     * Starts the download: the only thing that ever puts bytes on the wire.
     * A no-op while an install is running, once the model is ready, or while
     * a delete is in progress.
     *
     * @param allowingCellular false by default. With it false the transfer
     *   fails with [TranslationError.CellularNotAllowed] rather than quietly
     *   spending ~359 MB of the user's data plan.
     */
    fun download(allowingCellular: Boolean = false) {
        val job = synchronized(lock) {
            if (installJob != null || mutableState.value.isReady || isDeleting) return
            generation += 1
            val token = generation
            val predecessors = unwindingInstallJobs.toList()
            mutableState.value = ModelState.Downloading(0.0)
            // Lazy, so the job is recorded before it can possibly run.
            scope.launch(start = CoroutineStart.LAZY) { runInstall(token, predecessors, allowingCellular) }
                .also { installJob = it }
        }
        job.start()
    }

    /**
     * Stops an install in flight. Not a failure: whole files that already
     * landed, and the partial one, are kept, so [download] resumes from there.
     */
    fun cancelDownload() {
        val (job, token) = synchronized(lock) {
            val job = installJob ?: return
            generation += 1
            installJob = null
            unwindingInstallJobs.removeAll { it.isCompleted }
            unwindingInstallJobs += job
            job to generation
        }
        job.cancel()
        publishDiskState(token)
    }

    /**
     * Removes every byte the feature owns — downloads, optimized cache and
     * any unfinished scratch — and returns to [ModelState.NotDownloaded].
     *
     * Runs in the service's scope and only *waits* here: a caller that goes
     * away mid-delete (a screen closing) must not leave half a model behind.
     * A second call while one is running waits for the same delete.
     */
    suspend fun deleteModel() {
        val job = synchronized(lock) {
            deleteJob ?: scope.launch(start = CoroutineStart.LAZY) { performDelete() }.also { deleteJob = it }
        }
        job.start()
        job.join()
    }

    // Translate

    /**
     * English benti → Gurmukhi, fully on device. Input is trimmed; blank
     * input returns an empty translation without touching the engine.
     *
     * @throws TranslationError.NotReady before the model is installed, or
     *   while it is being deleted.
     * @throws TranslationError.EngineUnavailable when ONNX Runtime cannot run here.
     * @throws TranslationError.InsufficientMemory when the device lacks headroom.
     * @throws TranslationError.InputTooLong past the model's positional limit.
     * @throws CancellationException when the caller is cancelled.
     */
    suspend fun translate(english: String): BentiTranslation {
        synchronized(lock) {
            // The delete check matters: state stays Ready while a delete
            // waits for installs to unwind, and a translation started then
            // would read files that are about to vanish.
            if (!mutableState.value.isReady || isDeleting) throw TranslationError.NotReady
            inFlightTranslations.update { it + 1 }
        }
        try {
            val trimmed = english.trimmingWhitespaceAndNewlines()
            if (trimmed.isEmpty()) return EMPTY_TRANSLATION
            return engine.translate(
                trimmed,
                catalog = installer.catalog,
                graphDirectory = ModelOptimizer.optimizedDirectory(installer.directory),
                vocabularyDirectory = installer.directory,
            )
        } finally {
            inFlightTranslations.update { it - 1 }
        }
    }

    /** Drops the resident translator. Exposed for the lab; production gets it via [onTrimMemory]. */
    fun releaseEngine() = engine.release()

    /** Forwarded from `ComponentCallbacks2.onTrimMemory`; see [TranslationEngine.onTrimMemory]. */
    fun onTrimMemory(level: Int) = engine.onTrimMemory(level)

    /**
     * Cancels everything the service is running. The app never calls this —
     * the service lives as long as the process — but tests do, so no install
     * outlives the temporary directory it writes to.
     */
    fun close() {
        scope.cancel()
    }

    // Internals

    private suspend fun runInstall(token: Long, predecessors: List<Job>, allowingCellular: Boolean) {
        // Let any cancelled predecessor finish unwinding before touching the
        // model directory, or two installs mutate the same files.
        predecessors.joinAll()
        val outcome = try {
            currentCoroutineContext().ensureActive()
            installer.install(allowingCellular) { phase -> applyPhase(phase, token) }
            ModelState.Ready
        } catch (e: CancellationException) {
            // Superseded by cancel or delete (which already published the
            // state) or the service closed: nothing to publish.
            if (!currentCoroutineContext().isActive) throw e
            // A CancellationException we were not cancelled with (a timeout
            // somewhere below, say) is not the user cancelling either.
            log.warn("Model install stopped by a stray CancellationException", e)
            installer.currentState()
        } catch (e: Exception) {
            val error = TranslationError.wrap(e)
            if (error == TranslationError.Cancelled) {
                installer.currentState()
            } else {
                log.warn("Model install failed", e)
                ModelState.Failed(error)
            }
        }
        val bytes = installer.installedBytes()
        synchronized(lock) {
            if (token != generation) return
            installJob = null
            // This install waited for every one of them, and none has been
            // added since (that would have bumped the generation).
            unwindingInstallJobs.clear()
            publishLocked(outcome, bytes)
        }
    }

    private suspend fun performDelete() {
        try {
            val superseded = synchronized(lock) {
                generation += 1
                val jobs = listOfNotNull(installJob) + unwindingInstallJobs
                installJob = null
                unwindingInstallJobs.clear()
                jobs
            }
            // Wait them out rather than deleting underneath them.
            superseded.forEach { it.cancel() }
            superseded.joinAll()
            // Likewise translations that passed the readiness check before
            // the delete began: their sessions read the files about to go.
            inFlightTranslations.first { it == 0 }
            engine.release()
            val outcome = try {
                installer.deleteEverything()
                installer.currentState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Deleting the translation model failed", e)
                ModelState.Failed(TranslationError.wrap(e))
            }
            val bytes = installer.installedBytes()
            synchronized(lock) { publishLocked(outcome, bytes) }
        } finally {
            synchronized(lock) { deleteJob = null }
        }
    }

    /** Progress from the installer, on whatever thread it reports from. Dropped once superseded. */
    private fun applyPhase(phase: ModelInstaller.Phase, token: Long) {
        synchronized(lock) {
            if (token != generation || installJob == null) return
            mutableState.value = when (phase) {
                is ModelInstaller.Phase.Downloading -> ModelState.Downloading(phase.progress.coerceIn(0.0, 1.0))
                ModelInstaller.Phase.Optimizing -> ModelState.Optimizing
            }
        }
    }

    /** Publishes what is on disk, unless something newer than [token] has published since. */
    private fun publishDiskState(token: Long) {
        val onDisk = installer.currentState()
        val bytes = installer.installedBytes()
        synchronized(lock) {
            if (token != generation) return
            publishLocked(onDisk, bytes)
        }
    }

    /** Publishes a settled state and invalidates any read taken before it. Call with [lock] held. */
    private fun publishLocked(newState: ModelState, bytes: Long) {
        generation += 1
        mutableState.value = newState
        mutableInstalledBytes.value = bytes
    }

    private companion object {
        val EMPTY_TRANSLATION = BentiTranslation(
            gurmukhi = "",
            rawDevanagari = "",
            generatedIds = emptyList(),
            latencyMillis = 0,
            sessionLoadMillis = 0,
            isGurmukhiPure = true,
        )
    }
}
