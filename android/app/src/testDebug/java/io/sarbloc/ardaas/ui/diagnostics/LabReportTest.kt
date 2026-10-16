package io.sarbloc.ardaas.ui.diagnostics

import io.sarbloc.ardaas.translation.ModelState
import io.sarbloc.ardaas.translation.TranslationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers the Pixel 5a gate is decided on, and the report they are pasted in. */
class LabReportTest {
    private val mib = 1_048_576L

    private fun snapshot(rss: Long?, hwm: Long?, anon: Long? = null, file: Long? = null, shmem: Long? = null) = MemorySnapshot(
        rssBytes = rss?.times(mib),
        peakRssBytes = hwm?.times(mib),
        nativeHeapBytes = 50 * mib,
        rssAnonBytes = anon?.times(mib),
        rssFileBytes = file?.times(mib),
        rssShmemBytes = shmem?.times(mib),
    )

    private fun run(wallMillis: Long, peakMib: Long? = null, hwmMib: Long? = null, anonMib: Long? = null, fileMib: Long? = null) =
        LabRun(
            english = "Bless us.",
            gurmukhi = "ਸਾਨੂੰ ਅਸੀਸ ਦਿਓ।",
            isGurmukhiPure = true,
            wallMillis = wallMillis,
            engineMillis = wallMillis - 5,
            sessionLoadMillis = 100,
            sampled = SampledPeak(peakMib?.times(mib), anonMib?.times(mib), fileMib?.times(mib)),
            after = snapshot(rss = 200, hwm = hwmMib, anon = 150, file = 45, shmem = 5),
        )

    private val device = DeviceProfile(
        model = "Google Pixel 5a",
        androidRelease = "14",
        sdkInt = 34,
        abis = listOf("arm64-v8a", "armeabi-v7a"),
        is64BitProcess = true,
        totalRamBytes = 5_800 * mib,
        availableRamBytes = 2_100 * mib,
        isLowMemory = false,
        isLowRamDevice = false,
    )

    @Test
    fun anEmptyBatchHasNoSummary() {
        assertNull(BenchmarkSummary.of(emptyList()))
    }

    @Test
    fun summarisesLatencyAndPeakMemory() {
        val summary = checkNotNull(
            BenchmarkSummary.of(
                listOf(
                    run(400, peakMib = 610, hwmMib = 640, anonMib = 520, fileMib = 85),
                    run(100, peakMib = 590, hwmMib = 650, anonMib = 540, fileMib = 45),
                    run(250, peakMib = 600, hwmMib = 655, anonMib = 530, fileMib = 65),
                    run(300, peakMib = null, hwmMib = 660, anonMib = null, fileMib = null),
                ),
            ),
        )
        assertEquals(4, summary.count)
        assertEquals(262.5, summary.meanMillis, 0.0)
        assertEquals("median of an even batch is the mean of the middle two", 275.0, summary.medianMillis, 0.0)
        assertEquals(400L, summary.maxMillis)
        assertEquals(610 * mib, summary.sampled.peakRssBytes)
        assertEquals("anonymous and total peaks are independent", 540 * mib, summary.sampled.peakRssAnonBytes)
        assertEquals("RssFile comes from the run whose total RSS peaked", 85 * mib, summary.sampled.rssFileAtPeakBytes)
        assertEquals("VmHWM is taken from the end of the batch", 660 * mib, summary.processPeakRssBytes)
    }

    @Test
    fun aBatchWithNoReadableProcHasNoPeaks() {
        val summary = checkNotNull(BenchmarkSummary.of(listOf(run(100), run(200))))
        assertEquals(SampledPeak(null, null, null), summary.sampled)
    }

    @Test
    fun medianOfAnOddBatchIsTheMiddleValue() {
        val summary = BenchmarkSummary.of(listOf(run(900), run(100), run(300)))
        assertEquals(300.0, summary?.medianMillis ?: -1.0, 0.0)
    }

    @Test
    fun reportCarriesDeviceStateAndEveryRun() {
        val runs = listOf(
            run(400, peakMib = 610, hwmMib = 640, anonMib = 520, fileMib = 85),
            run(100, peakMib = 590, hwmMib = 650, anonMib = 540, fileMib = 45),
        )
        val report = LabReport.format(
            timestamp = "2026-10-16T12:00:00Z",
            device = device,
            modelState = ModelState.Ready,
            installedBytes = 676_770_640,
            memoryNow = snapshot(rss = 300, hwm = 650, anon = 210, file = 80, shmem = 10),
            runs = runs,
            benchmark = BenchmarkSummary.of(runs),
        )
        listOf(
            "Ardaas Benti Lab — 2026-10-16T12:00:00Z",
            "Device: Google Pixel 5a · Android 14 (API 34) · ABI arm64-v8a (64-bit process)",
            "RAM: total 5800.0 MiB · available 2100.0 MiB · lowMemory=false · lowRamDevice=false",
            "Model: ready · on disk 676.8 MB",
            "Memory now: RSS 300.0 MiB (anon 210.0 MiB · file 80.0 MiB · shmem 10.0 MiB) · VmHWM 650.0 MiB · " +
                "native heap 50.0 MiB",
            "Benchmark (2 runs): mean 250 ms · median 250 ms · max 400 ms · peak RssAnon 540.0 MiB · " +
                "peak RSS 610.0 MiB (RssFile 85.0 MiB at peak) · VmHWM 650.0 MiB",
            "Translations (2):",
            "   → ਸਾਨੂੰ ਅਸੀਸ ਦਿਓ।",
            "pure=true · wall 400 ms (engine 395, sessions 100) · peak RssAnon 520.0 MiB · " +
                "peak RSS 610.0 MiB (RssFile 85.0 MiB at peak) · after: RSS 200.0 MiB (anon 150.0 MiB",
        ).forEach { assertTrue("missing \"$it\" in:\n$report", it in report) }
    }

    @Test
    fun unreadableFiguresSayNotAvailableRatherThanZero() {
        val line = LabReport.memoryLine(MemorySnapshot(null, null, 0, null, null, null))
        assertEquals("RSS n/a (anon n/a · file n/a · shmem n/a) · VmHWM n/a · native heap 0.0 MiB", line)
        assertEquals(
            "peak RssAnon n/a · peak RSS n/a (RssFile n/a at peak)",
            LabReport.sampledLine(SampledPeak(null, null, null)),
        )
    }

    @Test
    fun aFailedStateShowsItsReason() {
        assertEquals(
            "failed: Wi-Fi is required for this download.",
            LabReport.describe(ModelState.Failed(TranslationError.CellularNotAllowed)),
        )
        assertEquals("downloading 42%", LabReport.describe(ModelState.Downloading(0.42)))
    }
}
