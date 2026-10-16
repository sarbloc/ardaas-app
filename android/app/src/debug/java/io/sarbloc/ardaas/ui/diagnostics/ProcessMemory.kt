package io.sarbloc.ardaas.ui.diagnostics

import android.os.Debug
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One reading of this process's memory. Kernel figures are null when `/proc` could not be read. */
data class MemorySnapshot(
    /** `VmRSS`: resident set right now. */
    val rssBytes: Long?,
    /**
     * `VmHWM`: the process's resident high-water mark since it started. A
     * lifetime figure — it never comes down — so it bounds every translation
     * so far rather than measuring the last one.
     */
    val peakRssBytes: Long?,
    /** `Debug.getNativeHeapAllocatedSize()`: malloc'd native bytes, which is where ORT's arena lives. */
    val nativeHeapBytes: Long,
)

/**
 * Reads process memory the way the Pixel measurement (#119) needs it. RSS
 * rather than PSS: PSS (`Debug.getMemoryInfo`) is too slow to sample while a
 * translation runs, and RSS — what the low-memory killer's accounting starts
 * from — is the number the iOS `phys_footprint` figures are compared to.
 */
object ProcessMemory {
    private const val TAG = "BentiLab"
    private const val STATUS_PATH = "/proc/self/status"
    private const val KIB = 1024L
    private const val SAMPLE_INTERVAL_MILLIS = 20L

    fun snapshot(): MemorySnapshot {
        val fields = readStatus()?.let(::parseStatus).orEmpty()
        return MemorySnapshot(
            rssBytes = fields["VmRSS"],
            peakRssBytes = fields["VmHWM"],
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
        )
    }

    /**
     * Runs [block] while sampling VmRSS every [SAMPLE_INTERVAL_MILLIS] on an
     * IO thread, returning its result and the highest RSS seen (null if none
     * could be read). Sampling is what makes a per-translation peak possible
     * at all: VmHWM cannot be reset by an app process.
     */
    suspend fun <T> sampledPeakRss(block: suspend () -> T): Pair<T, Long?> = coroutineScope {
        val peak = AtomicLong(-1)
        val record = { readRssBytes()?.let { rss -> peak.accumulateAndGet(rss, ::maxOf) } }
        val sampler = launch(Dispatchers.IO) {
            while (isActive) {
                record()
                delay(SAMPLE_INTERVAL_MILLIS)
            }
        }
        try {
            val result = block()
            record()
            result to peak.get().takeIf { it >= 0 }
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

    private fun readRssBytes(): Long? = readStatus()?.let(::parseStatus)?.get("VmRSS")

    /** Once is enough: the sampler would otherwise log the same failure fifty times a second. */
    private val warnedUnreadable = AtomicBoolean(false)

    private fun readStatus(): String? = try {
        File(STATUS_PATH).readText()
    } catch (e: IOException) {
        if (!warnedUnreadable.getAndSet(true)) Log.w(TAG, "Could not read $STATUS_PATH; RSS figures will be blank", e)
        null
    }
}
