import CryptoKit
import Foundation

/// Owns everything that happens on disk: the opt-in download, SHA-256
/// verification, the one-time graph optimization, deleting the originals
/// afterwards, and removing the lot.
///
/// Nothing here starts on its own. The installer is inert until `install` is
/// called, which is what makes the download opt-in rather than "on first use".
final class ModelInstaller: @unchecked Sendable {
    /// What the install is doing right now, mapped straight onto `ModelState`
    /// by the service.
    enum Phase: Equatable {
        /// 0...1 over the bytes this install actually needs to fetch.
        case downloading(progress: Double)
        case optimizing
    }

    let directory: URL
    let catalog: ModelCatalog

    private let fetcher: ModelFileFetching
    private let optimizer: GraphOptimizing
    private let availableDiskBytes: @Sendable () -> Int64?
    private let fileManager: FileManager

    /// Transport resume tokens, keyed by file name. Best effort and in-memory
    /// only: a resumable failure inside one session is resumed, an app kill
    /// restarts the file that was in flight. Whole files that already landed
    /// are never re-fetched, so the worst case is one file, not 359 MB.
    private let resumeLock = NSLock()
    private var resumeData: [String: Data] = [:]

    init(
        catalog: ModelCatalog = .production,
        directory: URL? = nil,
        fetcher: ModelFileFetching = URLSessionModelFetcher(),
        optimizer: GraphOptimizing = TranslationBuild.makeGraphOptimizer(),
        fileManager: FileManager = .default,
        availableDiskBytes: @escaping @Sendable () -> Int64? =
            ModelInstaller.systemAvailableDiskBytes
    ) {
        self.catalog = catalog
        self.directory = directory ?? catalog.directory(fileManager: fileManager)
        self.fetcher = fetcher
        self.optimizer = optimizer
        self.fileManager = fileManager
        self.availableDiskBytes = availableDiskBytes
    }

    // MARK: - Inspection

    /// Name → SHA-256 of every artifact that passed the hash gate.
    ///
    /// Readiness goes by size (re-hashing ~677 MB per launch is not an
    /// option), and size alone cannot tell a superseded artifact from the
    /// current one when an app update re-pins a file at the same length. The
    /// ledger can: a file only counts as installed when the hash it was
    /// verified against is the one the catalog pins now.
    static let ledgerName = "verified.json"

    private var ledgerURL: URL { directory.appendingPathComponent(Self.ledgerName) }

    /// Cheap, synchronous readiness check, safe to call on every appearance.
    ///
    /// Files are matched by exact expected size plus the hash they were
    /// verified against at download time (the ledger), rather than by
    /// re-hashing ~677 MB on every launch.
    func currentState() -> ModelState {
        // `.ready` is a promise that translate() can run. Without the engine
        // linked it never can, even if a previous ONNX-enabled build left a
        // complete model directory behind, so never claim ready here.
        guard optimizer.isAvailable else { return .notDownloaded }
        let verified = readLedger()
        let vocabularyPresent = catalog.vocabulary.allSatisfy {
            isInstalled($0, verified: verified)
        }
        // Retirement must have finished too: that is the step that proves the
        // cache loads on its own (see ModelOptimizer.isRetirementComplete).
        // An interrupted one reads as .notDownloaded; the next install resumes
        // it without re-downloading anything.
        let ready = vocabularyPresent
            && ModelOptimizer.isCacheValid(in: directory, catalog: catalog)
            && ModelOptimizer.isRetirementComplete(in: directory, catalog: catalog)
        return ready ? .ready : .notDownloaded
    }

    /// Everything this install occupies: whatever catalog files are still on
    /// disk plus the optimized cache and any unfinished scratch/retirement.
    func installedBytes() -> Int64 {
        let catalogBytes = catalog.files.reduce(Int64(0)) { total, file in
            total + (ModelOptimizer.fileSize(directory.appendingPathComponent(file.name)) ?? 0)
        }
        return catalogBytes + ModelOptimizer.cacheBytes(in: directory)
    }

    // MARK: - Install

    /// Downloads whatever is missing, verifies it, builds the optimized cache
    /// and drops the originals.
    ///
    /// Cancellable at file boundaries and mid-transfer (the fetcher cancels its
    /// URLSession task and keeps the resume token). The optimization step is
    /// not cancellable — it is three opaque ORT rewrites — so cancellation
    /// during it takes effect when it returns.
    func install(
        allowingCellular: Bool,
        onPhase: @escaping @Sendable (Phase) -> Void
    ) async throws {
        // A build without ONNX Runtime can never finish the optimization step,
        // so refuse now rather than after 359 MB has been fetched.
        guard optimizer.isAvailable else { throw TranslationError.engineUnavailable }

        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        ModelOptimizer.excludeFromBackup(directory)

        let verified = readLedger()

        // A valid cache means the graphs have already done their job, so a
        // repair (e.g. a deleted vocabulary file) must not re-fetch 352 MB.
        let cacheIsValid = ModelOptimizer.isCacheValid(in: directory, catalog: catalog)
        let wanted = cacheIsValid ? catalog.vocabulary : catalog.files
        let outstanding = wanted.filter { !isInstalled($0, verified: verified) }

        let totalBytes = max(wanted.reduce(Int64(0)) { $0 + $1.bytes }, 1)
        var completedBytes = wanted.reduce(Int64(0)) {
            $0 + (isInstalled($1, verified: verified) ? $1.bytes : 0)
        }
        onPhase(.downloading(progress: Double(completedBytes) / Double(totalBytes)))

        // Also check when nothing is left to download but the cache still has
        // to be built — e.g. killed after the downloads, before optimization.
        // That path still needs the full scratch budget, and failing here is
        // far better than dying mid-optimize with a filesystem error.
        if !outstanding.isEmpty || !cacheIsValid {
            try checkDiskSpace(
                downloadBytes: outstanding.reduce(Int64(0)) { $0 + $1.bytes },
                buildingCache: !cacheIsValid)
        }

        for file in outstanding {
            try Task.checkCancellation()
            let base = completedBytes
            let temporaryURL: URL
            do {
                temporaryURL = try await fetcher.fetch(
                    from: catalog.url(for: file),
                    resumeData: takeResumeData(for: file.name),
                    allowsCellularAccess: allowingCellular,
                    onProgress: { written in
                        let seen = base + min(max(written, 0), file.bytes)
                        onPhase(.downloading(progress: Double(seen) / Double(totalBytes)))
                    },
                    onResumeData: { [weak self] data in
                        self?.storeResumeData(data, for: file.name)
                    }
                )
            } catch {
                // Everything the caller sees is a TranslationError, including
                // NSURLErrorDataNotAllowed -> .cellularNotAllowed.
                throw TranslationError.wrap(error)
            }
            // Hashing is ~350 MB of reads per graph; keep it off the
            // cooperative pool.
            try await Task.detached(priority: .userInitiated) { [self] in
                try verifyAndInstall(temporaryURL, as: file)
            }.value
            completedBytes += file.bytes
            onPhase(.downloading(progress: Double(completedBytes) / Double(totalBytes)))
        }

        try Task.checkCancellation()

        if !ModelOptimizer.isCacheValid(in: directory, catalog: catalog) {
            onPhase(.optimizing)
        }
        // Off the cooperative pool: the rewrite is CPU-bound and briefly loads
        // each graph onto the heap.
        let modelDirectory = directory
        let modelCatalog = catalog
        let graphOptimizer = optimizer
        try await Task.detached(priority: .userInitiated) {
            try ModelOptimizer.build(
                modelDirectory: modelDirectory, catalog: modelCatalog, optimizer: graphOptimizer)
            try ModelOptimizer.retireSourceGraphs(
                in: modelDirectory, catalog: modelCatalog, optimizer: graphOptimizer)
        }.value

        guard currentState() == .ready else {
            throw TranslationError.optimizationFailed(
                graph: "install",
                detail: "install completed but the model did not become ready")
        }
    }

    /// Removes every byte this feature owns and returns to `.notDownloaded`.
    func deleteEverything() throws {
        resumeLock.lock()
        resumeData.removeAll()
        resumeLock.unlock()

        guard fileManager.fileExists(atPath: directory.path) else { return }
        do {
            try fileManager.removeItem(at: directory)
        } catch {
            throw TranslationError.wrap(error)
        }
    }

    // MARK: - Verification

    private func verifyAndInstall(_ temporaryURL: URL, as file: ModelCatalog.File) throws {
        let digest: String
        do {
            digest = try Self.sha256(of: temporaryURL)
        } catch {
            try? fileManager.removeItem(at: temporaryURL)
            throw TranslationError.wrap(error)
        }

        guard digest == file.sha256 else {
            // Fail loudly. These bytes execute as a model on the user's device;
            // a mismatch is either corruption or substitution and neither is
            // something to paper over with a silent retry.
            try? fileManager.removeItem(at: temporaryURL)
            // A resume token points back at the same broken transfer.
            clearResumeData(for: file.name)
            throw TranslationError.hashMismatch(
                file: file.name, expected: file.sha256, actual: digest)
        }

        let destination = directory.appendingPathComponent(file.name)
        do {
            // Forget any earlier verification of this name before its bytes
            // change, so no kill below can leave a record that describes bytes
            // which are no longer on disk.
            try forgetVerified(file.name)
            try? fileManager.removeItem(at: destination)
            try fileManager.moveItem(at: temporaryURL, to: destination)
        } catch {
            try? fileManager.removeItem(at: temporaryURL)
            throw TranslationError.wrap(error)
        }
        ModelOptimizer.excludeFromBackup(destination)
        do {
            // After the move: a kill in between leaves an unrecorded file,
            // which is re-fetched — never a recorded file that is not there.
            try recordVerified(file)
        } catch {
            throw TranslationError.wrap(error)
        }
    }

    // MARK: - Ledger

    /// The verification ledger, or empty when there is none or it cannot be
    /// read. Empty fails closed: every artifact reads as not installed and is
    /// re-fetched, never trusted on size alone.
    private func readLedger() -> [String: String] {
        guard fileManager.fileExists(atPath: ledgerURL.path) else { return [:] }
        do {
            let data = try Data(contentsOf: ledgerURL)
            return try JSONDecoder().decode([String: String].self, from: data)
        } catch {
            print(
                "BentiModel: unreadable verification ledger; "
                    + "every artifact will be re-fetched: \(error)")
            return [:]
        }
    }

    /// Atomic read-modify-write. Only ever called from the single running
    /// install (the service never runs two), so there is no lost update.
    private func writeLedger(_ ledger: [String: String]) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(ledger).write(to: ledgerURL, options: .atomic)
    }

    private func recordVerified(_ file: ModelCatalog.File) throws {
        var ledger = readLedger()
        ledger[file.name] = file.sha256
        try writeLedger(ledger)
    }

    private func forgetVerified(_ name: String) throws {
        var ledger = readLedger()
        guard ledger.removeValue(forKey: name) != nil else { return }
        try writeLedger(ledger)
    }

    /// Streaming SHA-256 in 1 MiB chunks — the graphs are far too large to load
    /// whole just to hash them.
    static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            guard let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty else { break }
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Disk

    private func checkDiskSpace(downloadBytes: Int64, buildingCache: Bool) throws {
        guard let available = availableDiskBytes() else { return }
        let required = downloadBytes
            + (buildingCache ? catalog.estimatedOptimizedCacheBytes : 0)
        guard available >= required else {
            // A stale cache we are about to rebuild is space we already own:
            // count it as reclaimable rather than failing an upgrade or repair
            // on a nearly full device.
            if buildingCache {
                // removeCache clears exactly the directories cacheBytes counts
                // (Optimized, Optimized.building, Originals.retiring), so the
                // space we counted is the space we actually reclaim.
                let reclaimable = ModelOptimizer.cacheBytes(in: directory)
                if reclaimable > 0, available + reclaimable >= required {
                    ModelOptimizer.removeCache(in: directory)
                    return
                }
            }
            throw TranslationError.insufficientDisk(
                availableBytes: available, requiredBytes: required)
        }
    }

    static func systemAvailableDiskBytes() -> Int64? {
        let home = URL(fileURLWithPath: NSHomeDirectory())
        guard let values = try? home.resourceValues(
            forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
              let capacity = values.volumeAvailableCapacityForImportantUsage
        else { return nil }
        return capacity
    }

    // MARK: - Helpers

    /// On disk at the pinned size AND recorded as verified against the pinned
    /// hash. Size alone would keep a superseded artifact re-pinned at the same
    /// byte count.
    private func isInstalled(_ file: ModelCatalog.File, verified: [String: String]) -> Bool {
        verified[file.name] == file.sha256
            && ModelOptimizer.fileSize(directory.appendingPathComponent(file.name)) == file.bytes
    }

    private func takeResumeData(for name: String) -> Data? {
        resumeLock.lock()
        defer { resumeLock.unlock() }
        return resumeData.removeValue(forKey: name)
    }

    private func storeResumeData(_ data: Data, for name: String) {
        resumeLock.lock()
        resumeData[name] = data
        resumeLock.unlock()
    }

    private func clearResumeData(for name: String) {
        resumeLock.lock()
        resumeData.removeValue(forKey: name)
        resumeLock.unlock()
    }
}
