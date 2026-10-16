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

    private fun run(wallMillis: Long, sampledPeakMib: Long? = null, hwmMib: Long? = null) = LabRun(
        english = "Bless us.",
        gurmukhi = "ਸਾਨੂੰ ਅਸੀਸ ਦਿਓ।",
        isGurmukhiPure = true,
        wallMillis = wallMillis,
        engineMillis = wallMillis - 5,
        sessionLoadMillis = 100,
        sampledPeakRssBytes = sampledPeakMib?.times(mib),
        after = MemorySnapshot(rssBytes = 200 * mib, peakRssBytes = hwmMib?.times(mib), nativeHeapBytes = 50 * mib),
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
            BenchmarkSummary.of(listOf(run(400, 610, 640), run(100, 590, 650), run(250, 600, 655), run(300, null, 660))),
        )
        assertEquals(4, summary.count)
        assertEquals(262.5, summary.meanMillis, 0.0)
        assertEquals("median of an even batch is the mean of the middle two", 275.0, summary.medianMillis, 0.0)
        assertEquals(400L, summary.maxMillis)
        assertEquals(610 * mib, summary.sampledPeakRssBytes)
        assertEquals("VmHWM is taken from the end of the batch", 660 * mib, summary.processPeakRssBytes)
    }

    @Test
    fun medianOfAnOddBatchIsTheMiddleValue() {
        val summary = BenchmarkSummary.of(listOf(run(900), run(100), run(300)))
        assertEquals(300.0, summary?.medianMillis ?: -1.0, 0.0)
    }

    @Test
    fun reportCarriesDeviceStateAndEveryRun() {
        val runs = listOf(run(400, 610, 640), run(100, 590, 650))
        val report = LabReport.format(
            timestamp = "2026-10-16T12:00:00Z",
            device = device,
            modelState = ModelState.Ready,
            installedBytes = 676_770_640,
            memoryNow = MemorySnapshot(rssBytes = 300 * mib, peakRssBytes = 650 * mib, nativeHeapBytes = 60 * mib),
            runs = runs,
            benchmark = BenchmarkSummary.of(runs),
        )
        listOf(
            "Ardaas Benti Lab — 2026-10-16T12:00:00Z",
            "Device: Google Pixel 5a · Android 14 (API 34) · ABI arm64-v8a (64-bit process)",
            "RAM: total 5800.0 MiB · available 2100.0 MiB · lowMemory=false · lowRamDevice=false",
            "Model: ready · on disk 676.8 MB",
            "Memory now: RSS 300.0 MiB · VmHWM 650.0 MiB · native heap 60.0 MiB",
            "Benchmark (2 runs): mean 250 ms · median 250 ms · max 400 ms · peak RSS sampled 610.0 MiB · VmHWM 650.0 MiB",
            "Translations (2):",
            "   → ਸਾਨੂੰ ਅਸੀਸ ਦਿਓ।",
            "pure=true · wall 400 ms (engine 395, sessions 100) · peak RSS sampled 610.0 MiB",
        ).forEach { assertTrue("missing \"$it\" in:\n$report", it in report) }
    }

    @Test
    fun unreadableFiguresSayNotAvailableRatherThanZero() {
        val line = LabReport.memoryLine(MemorySnapshot(rssBytes = null, peakRssBytes = null, nativeHeapBytes = 0))
        assertEquals("RSS n/a · VmHWM n/a · native heap 0.0 MiB", line)
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
