import Darwin
import Foundation
import os

// Spike (#35): memory instrumentation for the on-device translation pipeline.
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
    /// `ledger_phys_footprint_peak` — process-lifetime high-water mark. Only
    /// ever rises, so it is directly comparable to the 1374 MB baseline.
    let peakBytes: Int64
}

/// A footprint reading taken at a named point in the pipeline.
struct MemoryStage: Sendable, Equatable, Identifiable {
    let label: String
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
    /// Returns nil outside an app context (e.g. the unit-test host in some
    /// configurations), where the call reports 0.
    static func availableBytes() -> Int64? {
        let available = Int64(os_proc_available_memory())
        return available > 0 ? available : nil
    }

    /// Polls `phys_footprint` on a background thread so transient in-run peaks
    /// are observed. The single biggest allocation in this pipeline (a 251 MB
    /// dequantized `lm_head`) is allocated and freed *inside* one ORT `Run`, so
    /// sampling only between steps under-reports the peak substantially.
    final class Sampler: @unchecked Sendable {
        private let lock = NSLock()
        private var maxFootprint: Int64 = 0
        private var isRunning = true

        init(intervalSeconds: TimeInterval = 0.01) {
            self.maxFootprint = MemoryProbe.footprintBytes()
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
            return true
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
