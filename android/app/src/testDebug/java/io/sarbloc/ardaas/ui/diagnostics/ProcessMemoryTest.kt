package io.sarbloc.ardaas.ui.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** `/proc/self/status` as a real Android kernel writes it. */
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
        Threads:	42
        SigQ:	0/21592
        Cpus_allowed_list:	0-7
    """.trimIndent()

    @Test
    fun readsKibFiguresAsBytes() {
        val fields = ProcessMemory.parseStatus(status)
        assertEquals(612_340L * 1024, fields["VmHWM"])
        assertEquals(301_200L * 1024, fields["VmRSS"])
        assertEquals(120_000L * 1024, fields["RssAnon"])
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
}
