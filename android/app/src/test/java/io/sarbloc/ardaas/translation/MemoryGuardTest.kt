package io.sarbloc.ardaas.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pre-flight refusal — port of the MemoryGuard cases in ModelOptimizerTests.swift, plus Android's extra signals. */
class MemoryGuardTest {
    private val mib = 1_048_576L

    private fun reading(availableMib: Long, lowMemory: Boolean = false, lowRamDevice: Boolean = false) =
        MemoryReading(availableMib * mib, lowMemory, lowRamDevice)

    @Test
    fun refusesBelowTheRequiredHeadroom() {
        val error = assertThrows(TranslationError.InsufficientMemory::class.java) {
            MemoryGuard.check(reading(200), requiredBytes = 800 * mib)
        }
        assertEquals(TranslationError.InsufficientMemory(200 * mib, 800 * mib), error)
    }

    @Test
    fun allowsAtOrAboveTheRequiredHeadroom() {
        MemoryGuard.check(reading(800), requiredBytes = 800 * mib)
        MemoryGuard.check(reading(4_000), requiredBytes = 800 * mib)
    }

    /**
     * A reading that could not be taken is "don't know", not "no memory":
     * refusing every translation on the strength of it would be worse than
     * the risk the guard exists for.
     */
    @Test
    fun doesNotRefuseWhenMemoryIsUnmeasurable() {
        MemoryGuard.check(null)
    }

    @Test
    fun requiredHeadroomExceedsTheMeasuredPeak() {
        assertTrue(MemoryGuard.REQUIRED_HEADROOM_BYTES > MemoryGuard.MEASURED_PEAK_BYTES)
    }

    @Test
    fun defaultThresholdIsTheIosOne() {
        assertEquals(800 * mib, MemoryGuard.REQUIRED_HEADROOM_BYTES)
        assertThrows(TranslationError.InsufficientMemory::class.java) { MemoryGuard.check(reading(799)) }
        MemoryGuard.check(reading(800))
    }

    /** The low-memory killer is already busy; a ~580 MB allocation now is how the app gets killed. */
    @Test
    fun refusesWhenTheSystemReportsLowMemoryEvenWithHeadroom() {
        val error = assertThrows(TranslationError.InsufficientMemory::class.java) {
            MemoryGuard.check(reading(2_000, lowMemory = true))
        }
        assertEquals(2_000 * mib, error.availableBytes)
    }

    @Test
    fun refusesOnLowRamDevicesEvenWithHeadroom() {
        assertThrows(TranslationError.InsufficientMemory::class.java) {
            MemoryGuard.check(reading(2_000, lowRamDevice = true))
        }
    }
}
