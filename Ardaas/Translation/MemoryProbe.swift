import Darwin
import Foundation
import os

// Memory instrumentation and the pre-flight guard for the on-device
// translation pipeline (#35, #42).
//
// `phys_footprint` is the number Xcode's memory gauge shows and the number
// jetsam enforces against. Critically it counts *anonymous* (heap) pages and
// excludes clean file-backed pages, which is why memory-mapping the model
// weights (see ModelOptimizer) removes them from the footprint entirely even
// though they stay resident in RAM.

/// One `task_info(TASK_VM_INFO)` reading.
struct MemorySnapshot: Sendable, Equatable {
    /// `phys_footprint` — dirty + compressed pages charged to this process.
    let footprintBytes: Int64
    /// `ledger_phys_footprint_peak` — process-lifetime high-water mark.
    let peakBytes: Int64
}

/// What one stage of the pipeline cost.
struct MemoryStage: Sendable, Equatable, Identifiable {
    let label: String
    /// Highest footprint sampled *while the stage was running*. This is the
    /// meaningful number: each stage releases its session before returning, so
    /// a reading taken afterwards says nothing about what the stage needed.
    let peakBytes: Int64
    /// Footprint once the stage finished and released what it owned.
    let footprintBytes: Int64
    var id: String { label }
}

enum MemoryProbe {
    static func snapshot() -> MemorySnapshot? {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(
            MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        guard result == KERN_SUCCESS else { return nil }
        return MemorySnapshot(
            footprintBytes: Int64(info.phys_footprint),
            peakBytes: Int64(info.ledger_phys_footprint_peak)
        )
    }

    static func footprintBytes() -> Int64 {
        snapshot()?.footprintBytes ?? 0
    }

    /// Headroom before jetsam kills this process, per `os_proc_available_memory`.
    /// Returns nil where the call reports 0 — outside an app context (some unit
    /// test hosts) it is not a meaningful reading, and treating an unmeasurable
    /// value as "zero free" would refuse every translation.
    static func availableBytes() -> Int64? {
        let available = Int64(os_proc_available_memory())
        return available > 0 ? available : nil
    }

    /// Polls `phys_footprint` on a background thread so transient in-run peaks
    /// are observed. The single biggest allocation in this pipeline (a 251 MB
    /// dequantized `lm_head`) is allocated and freed *inside* one ORT `Run`, so
    /// sampling only between steps under-reports the peak substantially.
    ///
    /// Diagnostics only: production translations run with sampling off.
    final class Sampler: @unchecked Sendable {
        private let lock = NSLock()
        private var maxFootprint: Int64 = 0
        /// Max since the last `takeWindowPeak()`, for per-stage attribution.
        private var windowMax: Int64 = 0
        private var isRunning = true

        init(intervalSeconds: TimeInterval = 0.01) {
            let initial = MemoryProbe.footprintBytes()
            self.maxFootprint = initial
            self.windowMax = initial
            Thread.detachNewThread { [weak self] in
                while true {
                    guard let self, self.record() else { return }
                    Thread.sleep(forTimeInterval: intervalSeconds)
                }
            }
        }

        /// Returns false once the sampler has been stopped, ending the thread.
        private func record() -> Bool {
            let current = MemoryProbe.footprintBytes()
            lock.lock()
            defer { lock.unlock() }
            guard isRunning else { return false }
            maxFootprint = max(maxFootprint, current)
            windowMax = max(windowMax, current)
            return true
        }

        /// Highest footprint observed since the previous call (or since the
        /// sampler started), then opens a new window.
        func takeWindowPeak() -> Int64 {
            let current = MemoryProbe.footprintBytes()
            lock.lock()
            defer { lock.unlock() }
            let peak = max(windowMax, current)
            maxFootprint = max(maxFootprint, peak)
            windowMax = current
            return peak
        }

        /// Stops sampling and returns the highest footprint observed. Idempotent:
        /// once stopped the recorded peak is frozen, so callers can stop from a
        /// `defer` on the error path without disturbing the reported value.
        @discardableResult
        func stop() -> Int64 {
            let current = MemoryProbe.footprintBytes()
            lock.lock()
            defer { lock.unlock() }
            guard isRunning else { return maxFootprint }
            isRunning = false
            maxFootprint = max(maxFootprint, current)
            return maxFootprint
        }
    }
}

/// Pre-flight gate in front of every session load.
///
/// The optimized pipeline peaks at 582 MB of `phys_footprint` on an
/// iPhone 15 Pro (measured; see docs/translation/MEMORY.md). Jetsam foreground
/// limits are not published by Apple, and the tightest iOS 17 tier (3 GB
/// devices) is community-measured at roughly 1.4 GB, so the guard refuses
/// below 800 MB of headroom rather than gambling on a kill.
enum MemoryGuard {
    /// Measured peak footprint of one translation, iPhone 15 Pro.
    static let measuredPeakBytes: Int64 = 582 * 1_048_576

    /// Headroom demanded before a session is created: the measured peak plus a
    /// ~37% margin for the rest of the app and for slower/older CPUs whose
    /// allocation patterns were not measured directly.
    static let requiredHeadroomBytes: Int64 = 800 * 1_048_576

    /// Pure so the refusal is unit-testable without being able to influence
    /// real jetsam headroom.
    ///
    /// - Parameter availableBytes: nil when `os_proc_available_memory()` is not
    ///   meaningful in this process. Unmeasurable is *not* treated as
    ///   insufficient — refusing every translation on the strength of a reading
    ///   we could not take would be worse than the risk it guards against.
    static func check(
        availableBytes: Int64?,
        requiredBytes: Int64 = requiredHeadroomBytes
    ) throws {
        guard let availableBytes else { return }
        guard availableBytes >= requiredBytes else {
            throw TranslationError.insufficientMemory(
                availableBytes: availableBytes, requiredBytes: requiredBytes)
        }
    }
}
