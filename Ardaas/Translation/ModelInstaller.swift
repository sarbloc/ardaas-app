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

    /// Cheap, synchronous readiness check, safe to call on every appearance.
    ///
    /// Files are matched by exact expected size rather than by hash: the
    /// artifacts are hash-verified once, at download time, before being moved
    /// into place, and re-hashing ~677 MB on every launch would cost seconds
    /// for no new information.
    func currentState() -> ModelState {
        let vocabularyPresent = catalog.vocabulary.allSatisfy(fileMatchesCatalog)
        if vocabularyPresent && ModelOptimizer.isCacheValid(in: directory, catalog: catalog) {
            return .ready
        }
        return .notDownloaded
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
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        ModelOptimizer.excludeFromBackup(directory)

        // A valid cache means the graphs have already done their job, so a
        // repair (e.g. a deleted vocabulary file) must not re-fetch 352 MB.
        let cacheIsValid = ModelOptimizer.isCacheValid(in: directory, catalog: catalog)
        let wanted = cacheIsValid ? catalog.vocabulary : catalog.files
        let outstanding = wanted.filter { !fileMatchesCatalog($0) }

        let totalBytes = max(wanted.reduce(Int64(0)) { $0 + $1.bytes }, 1)
        var completedBytes = wanted.reduce(Int64(0)) {
            $0 + (fileMatchesCatalog($1) ? $1.bytes : 0)
        }
        onPhase(.downloading(progress: Double(completedBytes) / Double(totalBytes)))

        if !outstanding.isEmpty {
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
            try? fileManager.removeItem(at: destination)
            try fileManager.moveItem(at: temporaryURL, to: destination)
        } catch {
            try? fileManager.removeItem(at: temporaryURL)
            throw TranslationError.wrap(error)
        }
        ModelOptimizer.excludeFromBackup(destination)
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

    private func fileMatchesCatalog(_ file: ModelCatalog.File) -> Bool {
        ModelOptimizer.fileSize(directory.appendingPathComponent(file.name)) == file.bytes
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
