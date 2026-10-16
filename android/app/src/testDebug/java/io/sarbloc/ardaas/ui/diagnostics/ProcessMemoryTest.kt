package io.sarbloc.ardaas.ui.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** `/proc/self/status` as a real Android kernel writes it, and the peaks sampled from it. */
class ProcessMemoryTest {
    private val status = """
        Name:	io.sarbloc.ardaas
        Umask:	0077
        State:	S (sleeping)
        Tgid:	12345
        VmPeak:	15123456 kB
        VmHWM:	  612340 kB
        VmRSS:	  301200 kB
        RssAnon:	  120000 kB
        RssFile:	  176000 kB
        RssShmem:	    5200 kB
        Threads:	42
        SigQ:	0/21592
        Cpus_allowed_list:	0-7
    """.trimIndent()

    private val kib = 1024L

    private fun sample(rss: Long, anon: Long, file: Long) = mapOf("VmRSS" to rss, "RssAnon" to anon, "RssFile" to file)

    @Test
    fun readsKibFiguresAsBytes() {
        val fields = ProcessMemory.parseStatus(status)
        assertEquals(612_340L * kib, fields["VmHWM"])
        assertEquals(301_200L * kib, fields["VmRSS"])
        assertEquals(120_000L * kib, fields["RssAnon"])
        assertEquals(176_000L * kib, fields["RssFile"])
        assertEquals(5_200L * kib, fields["RssShmem"])
    }

    @Test
    fun skipsLinesThatAreNotSizes() {
        val fields = ProcessMemory.parseStatus(status)
        listOf("Name", "Umask", "Tgid", "Threads", "SigQ", "State").forEach { assertFalse(it, it in fields) }
    }

    @Test
    fun garbageYieldsNothingRatherThanThrowing() {
        assertEquals(emptyMap<String, Long>(), ProcessMemory.parseStatus("VmRSS: lots\n:::\n\nVmHWM"))
    }

    @Test
    fun tracksAnonymousAndTotalPeaksIndependently() {
        val tracker = PeakTracker()
        tracker.record(sample(rss = 500, anon = 300, file = 200))
        // Weights paged in: total RSS peaks here, anonymous does not.
        tracker.record(sample(rss = 800, anon = 350, file = 450))
        // Arena grows while weights are reclaimed: anonymous peaks here.
        tracker.record(sample(rss = 700, anon = 520, file = 180))
        tracker.record(sample(rss = 400, anon = 250, file = 150))

        assertEquals(SampledPeak(peakRssBytes = 800, peakRssAnonBytes = 520, rssFileAtPeakBytes = 450), tracker.result())
    }

    @Test
    fun unreadableSamplesLeaveThePeaksUnknown() {
        val tracker = PeakTracker()
        tracker.record(emptyMap())
        assertEquals(SampledPeak(null, null, null), tracker.result())

        // A kernel without RssAnon/RssFile (pre-4.5) still yields the total.
        tracker.record(mapOf("VmRSS" to 600L))
        assertEquals(SampledPeak(600, null, null), tracker.result())
    }
}
