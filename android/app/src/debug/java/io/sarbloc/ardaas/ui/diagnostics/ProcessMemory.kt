package io.sarbloc.ardaas.ui.diagnostics

import android.os.Debug
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One reading of this process's memory. Kernel figures are null when `/proc` could not be read. */
data class MemorySnapshot(
    /** `VmRSS`: resident set right now — anonymous + file-backed + shared memory. */
    val rssBytes: Long?,
    /**
     * `VmHWM`: the process's resident high-water mark since it started. A
     * lifetime figure — it never comes down — so it bounds every translation
     * so far rather than measuring the last one.
     */
    val peakRssBytes: Long?,
    /** `Debug.getNativeHeapAllocatedSize()`: malloc'd native bytes, which is where ORT's arena lives. */
    val nativeHeapBytes: Long,
    /**
     * `RssAnon`: resident anonymous memory — heaps, ORT's arena, activations.
     * What the MEMORY.md levers were measured against (host "peak anonymous
     * memory"), and the closest kernel figure to iOS's `phys_footprint`.
     */
    val rssAnonBytes: Long?,
    /**
     * `RssFile`: resident file-backed pages, mostly the memory-mapped weight
     * side-cars. Clean, so the kernel can drop and re-read them under
     * pressure — counted in VmRSS, but not memory the process holds onto.
     */
    val rssFileBytes: Long?,
    /** `RssShmem`: resident shared memory (ashmem, memfd). */
    val rssShmemBytes: Long?,
)

/**
 * The RSS breakdown at its peak over one sampled window. Anonymous and total
 * peaks are tracked independently — they need not coincide — and RssFile is
 * the figure from the sample where total RSS peaked, so `rssFileAtPeak`
 * explains how much of [peakRssBytes] was reclaimable weight pages.
 */
data class SampledPeak(
    val peakRssBytes: Long?,
    val peakRssAnonBytes: Long?,
    val rssFileAtPeakBytes: Long?,
)

/** Folds `/proc/self/status` readings into a [SampledPeak]. Not thread-safe; [ProcessMemory] guards it. */
class PeakTracker {
    private var peakRss: Long? = null
    private var peakAnon: Long? = null
    private var fileAtPeak: Long? = null

    fun record(fields: Map<String, Long>) {
        fields[RSS]?.let { rss ->
            if (rss > (peakRss ?: -1)) {
                peakRss = rss
                fileAtPeak = fields[RSS_FILE]
            }
        }
        fields[RSS_ANON]?.let { anon -> if (anon > (peakAnon ?: -1)) peakAnon = anon }
    }

    fun result(): SampledPeak = SampledPeak(peakRss, peakAnon, fileAtPeak)

    internal companion object {
        const val RSS = "VmRSS"
        const val RSS_ANON = "RssAnon"
        const val RSS_FILE = "RssFile"
        const val RSS_SHMEM = "RssShmem"
        const val HWM = "VmHWM"
    }
}

/**
 * Reads process memory the way the Pixel measurement (#119) needs it. RSS
 * rather than PSS: PSS (`Debug.getPss`) is far too slow to sample while a
 * translation runs. Total RSS overstates the pipeline's real cost, because
 * the mapped weights count as RssFile, so the anonymous share is reported
 * alongside it.
 */
object ProcessMemory {
    private const val TAG = "BentiLab"
    private const val STATUS_PATH = "/proc/self/status"
    private const val KIB = 1024L
    private const val SAMPLE_INTERVAL_MILLIS = 20L

    fun snapshot(): MemorySnapshot {
        val fields = readFields()
        return MemorySnapshot(
            rssBytes = fields[PeakTracker.RSS],
            peakRssBytes = fields[PeakTracker.HWM],
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            rssAnonBytes = fields[PeakTracker.RSS_ANON],
            rssFileBytes = fields[PeakTracker.RSS_FILE],
            rssShmemBytes = fields[PeakTracker.RSS_SHMEM],
        )
    }

    /**
     * Runs [block] while sampling `/proc/self/status` every
     * [SAMPLE_INTERVAL_MILLIS] on an IO thread, returning its result and the
     * peaks seen. Sampling is what makes a per-translation peak possible at
     * all: VmHWM cannot be reset by an app process, and there is no
     * high-water mark for RssAnon.
     */
    suspend fun <T> sampledPeak(block: suspend () -> T): Pair<T, SampledPeak> = coroutineScope {
        val tracker = PeakTracker()
        val record = {
            val fields = readFields()
            synchronized(tracker) { tracker.record(fields) }
        }
        val sampler = launch(Dispatchers.IO) {
            while (isActive) {
                record()
                delay(SAMPLE_INTERVAL_MILLIS)
            }
        }
        try {
            val result = block()
            record()
            result to synchronized(tracker) { tracker.result() }
        } finally {
            sampler.cancel()
        }
    }

    /**
     * Parses the `Name:   1234 kB` lines of `/proc/<pid>/status` into bytes.
     * Lines without a kB figure (state, pids, masks) are skipped.
     */
    fun parseStatus(text: String): Map<String, Long> = text.lineSequence()
        .mapNotNull { line ->
            val name = line.substringBefore(':', missingDelimiterValue = "").trim()
            val parts = line.substringAfter(':', missingDelimiterValue = "").trim().split(Regex("\\s+"))
            val kib = parts.firstOrNull()?.toLongOrNull()
            if (name.isEmpty() || kib == null || parts.getOrNull(1) != "kB") null else name to kib * KIB
        }
        .toMap()

    private fun readFields(): Map<String, Long> = readStatus()?.let(::parseStatus).orEmpty()

    /** Once is enough: the sampler would otherwise log the same failure fifty times a second. */
    private val warnedUnreadable = AtomicBoolean(false)

    private fun readStatus(): String? = try {
        File(STATUS_PATH).readText()
    } catch (e: IOException) {
        if (!warnedUnreadable.getAndSet(true)) Log.w(TAG, "Could not read $STATUS_PATH; RSS figures will be blank", e)
        null
    }
}
