package io.sarbloc.ardaas.ui.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * The device a measurement was taken on — what makes a latency or RSS figure
 * from a Pixel 5a comparable with one from anywhere else.
 */
data class DeviceProfile(
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    /** Supported ABIs, preferred first. */
    val abis: List<String>,
    val is64BitProcess: Boolean,
    /** `MemoryInfo.totalMem`; null when no ActivityManager could be had. */
    val totalRamBytes: Long?,
    /** `MemoryInfo.availMem`: what MemoryGuard compares against its 800 MiB threshold. */
    val availableRamBytes: Long?,
    val isLowMemory: Boolean?,
    val isLowRamDevice: Boolean?,
) {
    companion object {
        fun read(context: Context): DeviceProfile {
            val manager = context.getSystemService(ActivityManager::class.java)
            val info = manager?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo) }
            return DeviceProfile(
                model = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                abis = Build.SUPPORTED_ABIS.toList(),
                is64BitProcess = Process.is64Bit(),
                totalRamBytes = info?.totalMem,
                availableRamBytes = info?.availMem,
                isLowMemory = info?.lowMemory,
                isLowRamDevice = manager?.isLowRamDevice,
            )
        }
    }
}
