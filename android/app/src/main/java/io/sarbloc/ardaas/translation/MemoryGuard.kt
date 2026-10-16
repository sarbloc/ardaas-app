package io.sarbloc.ardaas.translation

import android.app.ActivityManager
import android.content.Context

/**
 * One reading of system memory, as [ActivityManager] reports it.
 *
 * Android has no per-process jetsam headroom like iOS's
 * `os_proc_available_memory()`. What ends a process here is the low-memory
 * killer acting on *system* memory, so the system-wide figures are the right
 * proxy for "can this process grow by a translation's peak and survive".
 */
data class MemoryReading(
    /** `MemoryInfo.availMem`: memory the system can hand out without killing anything. */
    val availableBytes: Long,
    /** `MemoryInfo.lowMemory`: the system already considers itself low and is killing background processes. */
    val isLowMemory: Boolean,
    /** `ActivityManager.isLowRamDevice`: a ~1 GB-class device the OS itself asks apps to stay small on. */
    val isLowRamDevice: Boolean,
)

/**
 * Where [MemoryGuard] gets its reading. A seam so the refusal logic is plain
 * JVM-testable without a device whose memory a test cannot influence.
 */
fun interface MemoryProbe {
    /** The current reading, or null when it cannot be taken. */
    fun read(): MemoryReading?
}

/** Production probe: `ActivityManager.getMemoryInfo` plus `isLowRamDevice`. */
class ActivityManagerMemoryProbe(
    context: Context,
    private val log: TranslationLogger = TranslationLogger.Android,
) : MemoryProbe {
    private val activityManager: ActivityManager? = context.getSystemService(ActivityManager::class.java)

    override fun read(): MemoryReading? {
        val manager = activityManager
        if (manager == null) {
            log.warn("No ActivityManager; translating without the memory pre-flight", null)
            return null
        }
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        if (info.availMem <= 0) {
            log.warn("ActivityManager reported ${info.availMem} bytes available; treating it as unmeasurable", null)
            return null
        }
        return MemoryReading(info.availMem, info.lowMemory, manager.isLowRamDevice)
    }
}

/**
 * Pre-flight gate in front of every session load — port of `MemoryGuard` in
 * `Ardaas/Translation/MemoryProbe.swift`.
 *
 * The optimized pipeline peaks at 582 MB of `phys_footprint` on an
 * iPhone 15 Pro (docs/translation/MEMORY.md); the Android engine runs the same
 * graphs with the same levers. Starting it without that much room invites the
 * low-memory killer, which the user experiences as the app vanishing, so the
 * guard refuses below 800 MB — the iOS threshold, kept equal so the two apps
 * refuse in the same conditions until Android has its own device numbers.
 */
object MemoryGuard {
    private const val MIB = 1_048_576L

    /** Measured peak footprint of one translation (iPhone 15 Pro). */
    const val MEASURED_PEAK_BYTES: Long = 582 * MIB

    /**
     * Headroom demanded before a session is created: the measured peak plus a
     * ~37% margin for the rest of the app and for slower devices whose
     * allocation patterns were not measured directly.
     */
    const val REQUIRED_HEADROOM_BYTES: Long = 800 * MIB

    /**
     * Pure, so every refusal is unit-testable.
     *
     * @param reading null when memory could not be measured. Unmeasurable is
     *   *not* treated as insufficient — refusing every translation on the
     *   strength of a reading we could not take would be worse than the risk
     *   it guards against.
     * @throws TranslationError.InsufficientMemory on a low-RAM device, when the
     *   system already reports low memory, or below [requiredBytes] available.
     */
    fun check(reading: MemoryReading?, requiredBytes: Long = REQUIRED_HEADROOM_BYTES) {
        if (reading == null) return
        if (reading.isLowRamDevice || reading.isLowMemory || reading.availableBytes < requiredBytes) {
            throw TranslationError.InsufficientMemory(reading.availableBytes, requiredBytes)
        }
    }
}
