package io.sarbloc.ardaas.translation

import android.app.ActivityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager

/** The production probe reads exactly the three signals the guard decides on. */
@RunWith(RobolectricTestRunner::class)
class ActivityManagerMemoryProbeTest {
    private lateinit var shadow: ShadowActivityManager
    private val log = RecordingLogger()
    private lateinit var probe: ActivityManagerMemoryProbe

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        shadow = shadowOf(context.getSystemService(ActivityManager::class.java))
        probe = ActivityManagerMemoryProbe(context, log)
    }

    private fun memoryInfo(available: Long, low: Boolean) = ActivityManager.MemoryInfo().apply {
        availMem = available
        lowMemory = low
    }

    @Test
    fun readsAvailableMemoryAndBothLowFlags() {
        shadow.setMemoryInfo(memoryInfo(available = 1_500_000_000, low = true))
        shadow.setIsLowRamDevice(true)

        assertEquals(MemoryReading(1_500_000_000, isLowMemory = true, isLowRamDevice = true), probe.read())
    }

    @Test
    fun zeroAvailableIsUnmeasurableNotEmpty() {
        shadow.setMemoryInfo(memoryInfo(available = 0, low = false))

        assertNull(probe.read())
        assertTrue(log.warnings.single().contains("unmeasurable"))
    }
}
