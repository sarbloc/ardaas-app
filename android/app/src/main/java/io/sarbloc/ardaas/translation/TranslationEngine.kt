package io.sarbloc.ardaas.translation

import android.content.ComponentCallbacks2
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serialises access to the inference engine and owns its lifetime — port of
 * `Ardaas/Translation/TranslationEngine.swift`.
 *
 * One translation at a time, by a [Mutex] rather than a thread lock because
 * every caller is a coroutine and a waiting caller must stay cancellable. Two
 * concurrent translations would put two copies of the pipeline's peak on the
 * books at once — precisely what the memory work was about.
 *
 * @param memoryProbe the pre-flight's source; [ActivityManagerMemoryProbe] in
 *   the app.
 * @param makeTranslator builds the resident translator from the catalog, the
 *   optimized graph directory and the vocabulary directory.
 * @param dispatcher where the blocking inference runs; it is CPU-bound.
 */
class TranslationEngine(
    private val memoryProbe: MemoryProbe,
    private val makeTranslator: (ModelCatalog, File, File) -> BentiTranslating = ::BentiTranslator,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()

    /**
     * Loaded lazily on first translation and dropped on memory pressure. It
     * holds the tokenizer and target dictionary (~7 MB) — *not* ORT sessions,
     * which are created and closed inside each translation. Written only
     * while [mutex] is held; volatile so [isLoaded] reads it from any thread.
     */
    @Volatile private var translator: BentiTranslating? = null

    /** Set by [release]; honoured by whichever holder of [mutex] sees it first. */
    private val releaseRequested = AtomicBoolean(false)

    /** True once a translator is resident, i.e. the next translation skips the ~200 ms load. */
    val isLoaded: Boolean get() = translator != null

    /**
     * Translates one benti. Waits for any translation already running.
     *
     * @throws TranslationError on every failure, [TranslationError.InsufficientMemory]
     *   before anything is allocated when the device lacks headroom.
     * @throws CancellationException when the caller is cancelled, including
     *   mid-decode — never a partial translation.
     */
    suspend fun translate(
        english: String,
        catalog: ModelCatalog,
        graphDirectory: File,
        vocabularyDirectory: File,
    ): BentiTranslation = try {
        mutex.withLock {
            withContext(dispatcher) {
                try {
                    dropIfReleaseRequested()
                    // Pre-flight before anything is allocated: a low-memory kill
                    // mid-decode is the one failure the user cannot be told about.
                    MemoryGuard.check(memoryProbe.read())
                    loadedTranslator(catalog, graphDirectory, vocabularyDirectory).translate(english)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Everything the caller sees is a TranslationError.
                    throw TranslationError.wrap(e)
                }
            }
        }
    } finally {
        // After unlocking, not before: a release() that arrived while the
        // lock was still held failed its tryLock and is left to us.
        honourPendingRelease()
    }

    /**
     * Drops the resident translator. Call before the model files are deleted
     * and on memory pressure (see [onTrimMemory]).
     *
     * Never blocks: it is called from main-thread callbacks and a translation
     * can run for seconds. If one is in flight the drop happens when it
     * finishes — its sessions are closed by then, by construction — and an
     * ORT run in progress is never interrupted.
     */
    fun release() {
        releaseRequested.set(true)
        honourPendingRelease()
    }

    /**
     * Hook for `ComponentCallbacks2.onTrimMemory`: the Android counterpart of
     * iOS's memory warning. Releases on foreground pressure (RUNNING_LOW and
     * up, delivered through API 33) and once the UI is hidden or the process
     * is in the background cache (UI_HIDDEN, BACKGROUND — the only levels
     * API 34+ still delivers). The cost is one ~200 ms reload next time.
     */
    fun onTrimMemory(level: Int) {
        @Suppress("DEPRECATION") // Deprecated from API 34, still delivered on the 26–33 minSdk covers.
        val threshold = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        if (level >= threshold) release()
    }

    /**
     * Drops now if a release is pending and nobody holds [mutex]. Both
     * [release] (after setting the flag) and every translation (after
     * unlocking) call this, so a request is never stranded: either its own
     * tryLock wins, or the holder it lost to sees the flag on the way out, or
     * the next translation to take the lock drops on the way in. Loops
     * because this function is itself a lock holder: a release() that lost
     * its tryLock to us is picked up after our unlock.
     */
    private fun honourPendingRelease() {
        while (releaseRequested.get() && mutex.tryLock()) {
            try {
                dropIfReleaseRequested()
            } finally {
                mutex.unlock()
            }
        }
    }

    /** Must be called with [mutex] held. */
    private fun dropIfReleaseRequested() {
        if (releaseRequested.getAndSet(false)) translator = null
    }

    /** Must be called with [mutex] held. */
    private fun loadedTranslator(catalog: ModelCatalog, graphDirectory: File, vocabularyDirectory: File): BentiTranslating =
        translator ?: makeTranslator(catalog, graphDirectory, vocabularyDirectory).also { translator = it }
}
