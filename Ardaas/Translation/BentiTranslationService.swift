import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// The public face of the on-device translation feature — the whole API `#44`
/// is expected to use.
///
/// Contract:
/// * Nothing downloads until `download(allowingCellular:)` is called. There is
///   no "fetch on first translate".
/// * Cellular is off by default; a caller that wants it must say so.
/// * `state` is the only status surface, and it is `Equatable`.
/// * Every failure is a `TranslationError`. No `fatalError`, and no
///   force-unwraps on any path a user can reach.
///
/// A stock build links no ONNX Runtime (see `TranslationBuild`), so download,
/// disk accounting and deletion all work but `translate` throws
/// `.engineUnavailable`.
@MainActor
final class BentiTranslationService: ObservableObject {
    // MARK: - Size disclosure (shown before the user opts in)

    /// Exact bytes over the network. Not an estimate — the sum of the pinned
    /// per-file sizes the download is verified against.
    var downloadBytes: Int64 { installer.catalog.downloadBytes }
    /// Estimated size of the one-time optimized cache built on device.
    var optimizedCacheBytes: Int64 { installer.catalog.estimatedOptimizedCacheBytes }
    /// Free space needed to get through the install, while the download and
    /// the cache briefly coexist.
    var peakDiskBytes: Int64 { installer.catalog.estimatedPeakDiskBytes }
    /// Disk still held once the originals have been dropped.
    var installedDiskBytes: Int64 { installer.catalog.estimatedInstalledBytes }

    // MARK: - Observable state

    @Published private(set) var state: ModelState = .notDownloaded
    /// Actual bytes on disk right now, so a settings screen can show the truth
    /// rather than the estimate.
    @Published private(set) var installedBytes: Int64 = 0

    let installer: ModelInstaller
    private let engine: TranslationEngine
    private var installTask: Task<Void, Never>?
    /// A cancelled install keeps running until its current step returns —
    /// optimization in particular cannot be interrupted mid-flight. Held here
    /// so the next install waits it out instead of racing it over the same
    /// files. Cleared once awaited.
    private var cancelledInstallTask: Task<Void, Never>?
    /// Bumped whenever an install is superseded (cancel, delete, restart), so a
    /// late progress hop from the old one cannot overwrite the new state.
    private var generation = 0
    private let memoryWarningObserver = NotificationObserverBox()

    init(
        installer: ModelInstaller = ModelInstaller(),
        engine: TranslationEngine = TranslationEngine()
    ) {
        self.installer = installer
        self.engine = engine
        self.state = installer.currentState()
        self.installedBytes = installer.installedBytes()
        observeMemoryWarnings()
    }

    /// Re-reads disk. Cheap (size checks plus one small JSON manifest), so it
    /// is safe to call on every appearance — but it never overrides an install
    /// that is currently running.
    func refresh() {
        installedBytes = installer.installedBytes()
        guard !state.isWorking else { return }
        let onDisk = installer.currentState()
        // Don't paper over a failure the user has not acknowledged.
        if state.error != nil && onDisk != .ready { return }
        state = onDisk
    }

    // MARK: - Opt-in install

    /// Starts the download. Explicit opt-in: this is the only thing that ever
    /// puts bytes on the wire.
    ///
    /// - Parameter allowingCellular: defaults to false. With it false the
    ///   transfer fails with `.cellularNotAllowed` rather than quietly spending
    ///   ~359 MB of the user's data plan.
    func download(allowingCellular: Bool = false) {
        guard installTask == nil, !state.isReady else { return }
        generation += 1
        let token = generation
        state = .downloading(progress: 0)

        let installer = self.installer
        let onPhase: @Sendable (ModelInstaller.Phase) -> Void = { [weak self] phase in
            Task { @MainActor in self?.apply(phase, token: token) }
        }

        let previous = cancelledInstallTask
        installTask = Task { [weak self] in
            // Let any cancelled predecessor finish unwinding before we touch
            // the model directory, or two installs mutate the same files.
            await previous?.value
            if Task.isCancelled { return }
            let outcome: ModelState
            do {
                try await installer.install(
                    allowingCellular: allowingCellular, onPhase: onPhase)
                outcome = .ready
            } catch is CancellationError {
                // Cancelling is not failing: partial progress is kept.
                outcome = installer.currentState()
            } catch {
                let translationError = TranslationError.wrap(error)
                outcome = translationError == .cancelled
                    ? installer.currentState()
                    : .failed(translationError)
            }
            guard let self, token == self.generation else { return }
            self.installTask = nil
            self.cancelledInstallTask = nil
            self.state = outcome
            self.installedBytes = installer.installedBytes()
        }
    }

    /// Stops an install in flight. Not a failure: whole files that already
    /// landed are kept, so calling `download` again resumes from there.
    func cancelDownload() {
        guard let task = installTask else { return }
        generation += 1
        installTask = nil
        task.cancel()
        // Keep the handle: the task is still unwinding, and the next install
        // must await it before touching the same files.
        cancelledInstallTask = task
        state = installer.currentState()
        installedBytes = installer.installedBytes()
    }

    /// Removes every byte the feature owns — downloads, optimized cache and
    /// any unfinished scratch — and returns to `.notDownloaded`.
    func deleteModel() async {
        generation += 1
        let task = installTask
        installTask = nil
        task?.cancel()
        // Wait the cancelled install out rather than deleting underneath it —
        // including one cancelled earlier that may still be unwinding.
        let stillUnwinding = cancelledInstallTask
        cancelledInstallTask = nil
        await task?.value
        await stillUnwinding?.value
        await engine.release()

        let installer = self.installer
        do {
            // Off the main thread: this can be ~677 MB of unlink.
            try await Task.detached(priority: .userInitiated) {
                try installer.deleteEverything()
            }.value
            state = installer.currentState()
        } catch {
            state = .failed(TranslationError.wrap(error))
        }
        installedBytes = installer.installedBytes()
    }

    // MARK: - Translate

    /// English benti → Gurmukhi, fully on device.
    ///
    /// - Throws: `.notReady` before the model is installed,
    ///   `.engineUnavailable` in a build without ONNX Runtime,
    ///   `.insufficientMemory` when there is not enough jetsam headroom,
    ///   `.inputTooLong` past the model's 256-position limit.
    func translate(_ english: String) async throws -> String {
        try await translateDetailed(english).gurmukhi
    }

    /// As `translate`, plus the latency and memory diagnostics the Benti Lab
    /// renders. `collectMetrics` spins up a sampling thread, so it is off for
    /// production callers.
    func translateDetailed(
        _ english: String,
        collectMetrics: Bool = false
    ) async throws -> BentiTranslation {
        guard state.isReady else { throw TranslationError.notReady }
        let trimmed = english.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return BentiTranslation(
                gurmukhi: "", rawDevanagari: "", generatedTokenCount: 0,
                latencySeconds: 0, sessionLoadSeconds: 0, peakFootprintBytes: 0,
                stages: [], isGurmukhiPure: true)
        }
        return try await engine.translate(
            trimmed,
            catalog: installer.catalog,
            graphDirectory: ModelOptimizer.optimizedDirectory(in: installer.directory),
            vocabularyDirectory: installer.directory,
            collectMetrics: collectMetrics
        )
    }

    /// Drops the resident translator. Exposed for the lab; production callers
    /// get this automatically on a memory warning.
    func releaseEngine() async {
        await engine.release()
    }

    // MARK: - Internals

    private func apply(_ phase: ModelInstaller.Phase, token: Int) {
        guard token == generation, installTask != nil else { return }
        switch phase {
        case .downloading(let progress):
            state = .downloading(progress: min(max(progress, 0), 1))
        case .optimizing:
            state = .optimizing
        }
    }

    private func observeMemoryWarnings() {
        #if canImport(UIKit)
        let engine = self.engine
        memoryWarningObserver.token = NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: .main
        ) { _ in
            // Sessions are already per-stage, so this only drops the tokenizer
            // and dictionary — a ~200 ms reload, against being killed.
            Task { await engine.release() }
        }
        #endif
    }
}

/// Removes its observer when the owner deallocates, without needing a `deinit`
/// on a `@MainActor` type.
private final class NotificationObserverBox {
    var token: NSObjectProtocol?

    deinit {
        if let token { NotificationCenter.default.removeObserver(token) }
    }
}
