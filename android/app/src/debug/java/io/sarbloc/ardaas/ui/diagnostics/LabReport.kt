package io.sarbloc.ardaas.ui.diagnostics

import io.sarbloc.ardaas.translation.ModelState
import java.util.Locale

/** One translation as the lab measured it. */
data class LabRun(
    val english: String,
    val gurmukhi: String,
    /** Advisory script-purity flag from the postprocessor (spec section 5.5). */
    val isGurmukhiPure: Boolean,
    /** Wall clock around the whole `translate` call: the latency a user would feel. */
    val wallMillis: Long,
    /** The engine's own figure, excluding the wait for the engine lock and the memory pre-flight. */
    val engineMillis: Long,
    /** Of which spent creating the three ORT sessions. */
    val sessionLoadMillis: Long,
    /** Peak RssAnon, peak total RSS and RssFile at that peak, sampled while this translation ran. */
    val sampled: SampledPeak,
    /** Memory straight after it finished. */
    val after: MemorySnapshot,
)

/** Latency and memory over a batch of runs — the numbers the Pixel 5a gate (#119) is decided on. */
data class BenchmarkSummary(
    val count: Int,
    val meanMillis: Double,
    val medianMillis: Double,
    val maxMillis: Long,
    /**
     * Highest RssAnon and highest total RSS sampled across the batch, with
     * the RssFile of the run whose total RSS peaked highest.
     */
    val sampled: SampledPeak,
    /** VmHWM when the batch ended: the process's lifetime peak, so an upper bound. */
    val processPeakRssBytes: Long?,
) {
    companion object {
        /** Null for an empty batch: there is nothing to summarise, and a 0 ms mean would read as a result. */
        fun of(runs: List<LabRun>): BenchmarkSummary? {
            if (runs.isEmpty()) return null
            val latencies = runs.map { it.wallMillis }.sorted()
            val highestRss = runs.filter { it.sampled.peakRssBytes != null }.maxByOrNull { it.sampled.peakRssBytes ?: 0 }
            return BenchmarkSummary(
                count = runs.size,
                meanMillis = latencies.average(),
                medianMillis = median(latencies),
                maxMillis = latencies.last(),
                sampled = SampledPeak(
                    peakRssBytes = highestRss?.sampled?.peakRssBytes,
                    peakRssAnonBytes = runs.mapNotNull { it.sampled.peakRssAnonBytes }.maxOrNull(),
                    rssFileAtPeakBytes = highestRss?.sampled?.rssFileAtPeakBytes,
                ),
                processPeakRssBytes = runs.last().after.peakRssBytes,
            )
        }

        private fun median(sorted: List<Long>): Double {
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle].toDouble() else (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }
}

/**
 * The plain-text report "Copy results" puts on the clipboard — written to be
 * pasted into a chat as-is, so it is compact and self-describing.
 */
object LabReport {
    private const val MIB = 1_048_576.0
    private const val MB = 1_000_000.0

    fun format(
        timestamp: String,
        device: DeviceProfile,
        modelState: ModelState,
        installedBytes: Long,
        memoryNow: MemorySnapshot,
        runs: List<LabRun>,
        benchmark: BenchmarkSummary?,
    ): String = buildString {
        appendLine("Ardaas Benti Lab — $timestamp")
        appendLine(deviceLine(device))
        appendLine(ramLine(device))
        appendLine("Model: ${describe(modelState)} · on disk ${mb(installedBytes)}")
        appendLine("Memory now: ${memoryLine(memoryNow)}")
        if (benchmark != null) {
            appendLine()
            appendLine(benchmarkLine(benchmark))
        }
        if (runs.isNotEmpty()) {
            appendLine()
            appendLine("Translations (${runs.size}):")
            runs.forEachIndexed { index, run -> append(runBlock(index + 1, run)) }
        }
    }.trimEnd()

    fun deviceLine(device: DeviceProfile): String =
        "Device: ${device.model} · Android ${device.androidRelease} (API ${device.sdkInt}) · " +
            "ABI ${device.abis.firstOrNull() ?: "unknown"} (${if (device.is64BitProcess) "64" else "32"}-bit process)"

    fun ramLine(device: DeviceProfile): String =
        "RAM: total ${mib(device.totalRamBytes)} · available ${mib(device.availableRamBytes)} · " +
            "lowMemory=${device.isLowMemory ?: "unknown"} · lowRamDevice=${device.isLowRamDevice ?: "unknown"}"

    fun memoryLine(memory: MemorySnapshot): String =
        "RSS ${mib(memory.rssBytes)} (anon ${mib(memory.rssAnonBytes)} · file ${mib(memory.rssFileBytes)} · " +
            "shmem ${mib(memory.rssShmemBytes)}) · VmHWM ${mib(memory.peakRssBytes)} · " +
            "native heap ${mib(memory.nativeHeapBytes)}"

    /**
     * Anonymous first: it is the figure comparable with MEMORY.md and iOS's
     * `phys_footprint`. Total RSS includes the clean, reclaimable mapped
     * weights, which RssFile-at-peak accounts for.
     */
    fun sampledLine(peak: SampledPeak): String =
        "peak RssAnon ${mib(peak.peakRssAnonBytes)} · peak RSS ${mib(peak.peakRssBytes)} " +
            "(RssFile ${mib(peak.rssFileAtPeakBytes)} at peak)"

    fun benchmarkLine(summary: BenchmarkSummary): String =
        "Benchmark (${summary.count} runs): mean ${millis(summary.meanMillis)} · median ${millis(summary.medianMillis)} · " +
            "max ${summary.maxMillis} ms · ${sampledLine(summary.sampled)} · VmHWM ${mib(summary.processPeakRssBytes)}"

    fun runLine(run: LabRun): String =
        "pure=${run.isGurmukhiPure} · wall ${run.wallMillis} ms (engine ${run.engineMillis}, sessions ${run.sessionLoadMillis}) · " +
            "${sampledLine(run.sampled)} · after: ${memoryLine(run.after)}"

    fun describe(state: ModelState): String = when (state) {
        ModelState.NotDownloaded -> "not downloaded"
        is ModelState.Downloading -> "downloading ${(state.progress * 100).toInt()}%"
        ModelState.Optimizing -> "optimizing"
        ModelState.Ready -> "ready"
        is ModelState.Failed -> "failed: ${state.error.message}"
    }

    fun mib(bytes: Long?): String = bytes?.let { "%.1f MiB".format(Locale.US, it / MIB) } ?: "n/a"

    fun mb(bytes: Long?): String = bytes?.let { "%.1f MB".format(Locale.US, it / MB) } ?: "n/a"

    private fun millis(value: Double): String = "%.0f ms".format(Locale.US, value)

    private fun runBlock(number: Int, run: LabRun): String = buildString {
        appendLine("$number. ${run.english}")
        appendLine("   → ${run.gurmukhi}")
        appendLine("   ${runLine(run)}")
    }
}
