import XCTest

@testable import Ardaas

/// The download state machine (#42), driven end to end against a fake fetcher
/// and a fake graph optimizer.
///
/// The real 359 MB artifact cannot be in CI, so the catalog here is synthetic —
/// same shape, tiny payloads, real SHA-256s. The hash gate, the cellular guard,
/// cancellation, the disk-space refusal, source-graph retirement and deletion
/// are all the production code paths.
final class ModelInstallerTests: XCTestCase {
    private var root: URL!
    private var model: TranslationFixtures.SyntheticModel!
    private var fetcher: FakeModelFileFetcher!
    private var optimizer: FakeGraphOptimizer!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("ModelInstallerTests-\(UUID().uuidString)", isDirectory: true)
        model = TranslationFixtures.syntheticModel()
        fetcher = FakeModelFileFetcher(payloads: model.payloads)
        optimizer = FakeGraphOptimizer()
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func makeInstaller(availableDiskBytes: Int64? = 10_000_000) -> ModelInstaller {
        ModelInstaller(
            catalog: model.catalog,
            directory: root,
            fetcher: fetcher,
            optimizer: optimizer,
            availableDiskBytes: { availableDiskBytes })
    }

    private func exists(_ name: String) -> Bool {
        FileManager.default.fileExists(atPath: root.appendingPathComponent(name).path)
    }

    private var ledgerURL: URL { root.appendingPathComponent(ModelInstaller.ledgerName) }

    /// The verification ledger as written to disk, or empty when there is none.
    private func ledger() throws -> [String: String] {
        guard FileManager.default.fileExists(atPath: ledgerURL.path) else { return [:] }
        return try JSONDecoder().decode([String: String].self, from: Data(contentsOf: ledgerURL))
    }

    /// Puts the original graphs back beside a finished cache, as if the app
    /// was killed after the manifest landed but before retirement verified it.
    private func restoreOriginalGraphs() throws {
        for name in model.catalog.graphNames {
            try model.payload(name).write(to: root.appendingPathComponent(name))
        }
    }

    /// Collects every phase the installer reports, from whatever thread.
    private final class PhaseLog: @unchecked Sendable {
        private let lock = NSLock()
        private var phases: [ModelInstaller.Phase] = []

        var recorded: [ModelInstaller.Phase] {
            lock.lock()
            defer { lock.unlock() }
            return phases
        }

        var progressValues: [Double] {
            recorded.compactMap {
                if case .downloading(let progress) = $0 { return progress }
                return nil
            }
        }

        var sawOptimizing: Bool { recorded.contains(.optimizing) }

        func append(_ phase: ModelInstaller.Phase) {
            lock.lock()
            phases.append(phase)
            lock.unlock()
        }
    }

    // MARK: - Opt-in

    func testNothingIsFetchedUntilInstallIsCalled() {
        let installer = makeInstaller()
        XCTAssertEqual(installer.currentState(), .notDownloaded)
        XCTAssertEqual(installer.installedBytes(), 0)
        XCTAssertTrue(
            fetcher.requests.isEmpty,
            "constructing the installer must not put a single byte on the wire")
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.path))
    }

    /// A stock build (no BENTI_ONNX) cannot complete the optimization step, so
    /// it must not spend the user's bandwidth discovering that.
    func testABuildWithoutTheEngineRefusesBeforeDownloading() async {
        let installer = ModelInstaller(
            catalog: model.catalog,
            directory: root,
            fetcher: fetcher,
            optimizer: UnavailableGraphOptimizer(),
            availableDiskBytes: { 10_000_000 })

        do {
            try await installer.install(allowingCellular: false) { _ in }
            XCTFail("expected .engineUnavailable")
        } catch {
            XCTAssertEqual(error as? TranslationError, .engineUnavailable)
        }
        XCTAssertTrue(fetcher.requests.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.path))
    }

    // MARK: - Happy path

    func testFreshInstallDownloadsOptimizesAndBecomesReady() async throws {
        let installer = makeInstaller()
        let log = PhaseLog()

        try await installer.install(allowingCellular: false) { log.append($0) }

        XCTAssertEqual(installer.currentState(), .ready)
        XCTAssertEqual(
            Set(fetcher.requests.map(\.name)), Set(model.catalog.files.map(\.name)))
        XCTAssertTrue(log.sawOptimizing)
        XCTAssertEqual(try XCTUnwrap(log.progressValues.last), 1, accuracy: 0.0001)
        XCTAssertEqual(
            log.progressValues, log.progressValues.sorted(),
            "download progress must never go backwards")
        XCTAssertEqual(optimizer.optimizedGraphs.sorted(), model.catalog.graphNames.sorted())
    }

    /// Disk hygiene: the originals are worth ~352 MB in production and the
    /// optimized cache does not need them.
    func testOriginalGraphsAreDeletedAfterOptimization() async throws {
        let installer = makeInstaller()
        try await installer.install(allowingCellular: false) { _ in }

        for name in model.catalog.graphNames {
            XCTAssertFalse(exists(name), "\(name) should have been retired after optimization")
        }
        for file in model.catalog.vocabulary {
            XCTAssertTrue(exists(file.name), "\(file.name) is needed at every translation")
        }
        XCTAssertEqual(
            optimizer.verifiedGraphs.sorted(), model.catalog.graphNames.sorted(),
            "every optimized graph must be proved loadable before the originals go")
        XCTAssertFalse(
            FileManager.default.fileExists(
                atPath: ModelOptimizer.retiredDirectory(in: root).path),
            "the retirement staging directory must not survive a successful install")
    }

    func testAlreadyInstalledFilesAreNotRefetched() async throws {
        let installer = makeInstaller()
        try await installer.install(allowingCellular: false) { _ in }
        let firstPass = fetcher.requestCount

        try await installer.install(allowingCellular: false) { _ in }
        XCTAssertEqual(
            fetcher.requestCount, firstPass,
            "a second install over a valid cache must not re-download anything")
    }

    /// A repair (say a vocabulary file lost to a cleanup) must not re-fetch the
    /// graphs, which have already been consumed and deleted.
    func testRepairDoesNotRefetchTheGraphs() async throws {
        let installer = makeInstaller()
        try await installer.install(allowingCellular: false) { _ in }
        try FileManager.default.removeItem(at: root.appendingPathComponent("dict.TGT.json"))
        XCTAssertEqual(installer.currentState(), .notDownloaded)

        try await installer.install(allowingCellular: false) { _ in }

        XCTAssertEqual(installer.currentState(), .ready)
        let secondPass = fetcher.requests.suffix(1).map(\.name)
        XCTAssertEqual(secondPass, ["dict.TGT.json"])
    }

    /// Killed after the cache manifest landed but before the load-back check
    /// finished: the cache was never proved loadable, so it must not read as
    /// ready, and the next install must finish the check without re-fetching.
    func testAnInterruptedRetirementIsNotReadyUntilVerified() async throws {
        let installer = makeInstaller()
        try await installer.install(allowingCellular: false) { _ in }
        // Recreate the kill: originals back on disk, one of them parked.
        try restoreOriginalGraphs()
        let retired = ModelOptimizer.retiredDirectory(in: root)
        try FileManager.default.createDirectory(at: retired, withIntermediateDirectories: true)
        let parked = model.catalog.graphNames[0]
        try FileManager.default.moveItem(
            at: root.appendingPathComponent(parked), to: retired.appendingPathComponent(parked))
        let verifiedBefore = optimizer.verifiedGraphs.count
        let fetchedBefore = fetcher.requestCount

        XCTAssertEqual(
            installer.currentState(), .notDownloaded,
            "a cache that was never proved loadable must not read as ready")

        try await installer.install(allowingCellular: false) { _ in }

        XCTAssertEqual(installer.currentState(), .ready)
        XCTAssertEqual(
            optimizer.verifiedGraphs.count, verifiedBefore + model.catalog.graphNames.count,
            "every graph must be load-checked again")
        XCTAssertEqual(fetcher.requestCount, fetchedBefore, "finishing the check must not re-download")
        for name in model.catalog.graphNames {
            XCTAssertFalse(exists(name), "\(name) should have been retired")
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: retired.path))
    }

    /// The same interruption, but the cache does not actually load: it must
    /// never read as ready, and the originals stay for a rebuild.
    func testAnInterruptedRetirementThatFailsVerificationIsNeverReady() async throws {
        let installer = makeInstaller()
        try await installer.install(allowingCellular: false) { _ in }
        try restoreOriginalGraphs()
        optimizer.verifyError = TranslationError.sessionFailure("cannot load external data")

        XCTAssertEqual(installer.currentState(), .notDownloaded)
        do {
            try await installer.install(allowingCellular: false) { _ in }
            XCTFail("a cache that does not load must fail the install")
        } catch let error as TranslationError {
            guard case .optimizationFailed = error else {
                return XCTFail("expected .optimizationFailed, got \(error)")
            }
        }
        XCTAssertEqual(installer.currentState(), .notDownloaded)
        for name in model.catalog.graphNames {
            XCTAssertTrue(exists(name), "\(name) is kept for a rebuild")
        }
    }

    // MARK: - Verification ledger

    func testEveryVerifiedArtifactIsRecordedInTheLedger() async throws {
        let installer = makeInstaller()
        try await installer.install(allowingCellular: false) { _ in }

        let expected = Dictionary(
            uniqueKeysWithValues: model.catalog.files.map { ($0.name, $0.sha256) })
        XCTAssertEqual(try ledger(), expected)
    }

    /// An app update that re-pins a file at the same byte count must not keep
    /// the superseded bytes just because the size matches.
    func testARepinnedFileOfTheSameSizeIsFetchedAgain() async throws {
        try await makeInstaller().install(allowingCellular: false) { _ in }
        let name = "dict.TGT.json"
        let old = model.payload(name)
        let new = Data("{\"dict\":null}".utf8)
        XCTAssertEqual(old.count, new.count, "the scenario needs a same-size re-pin")

        let repinnedFiles = model.catalog.files.map { file -> ModelCatalog.File in
            guard file.name == name else { return file }
            return ModelCatalog.File(
                name: file.name, bytes: file.bytes,
                sha256: TranslationFixtures.sha256(new), kind: file.kind)
        }
        let repinned = ModelCatalog(
            files: repinnedFiles,
            repoBase: model.catalog.repoBase,
            directoryName: model.catalog.directoryName,
            estimatedOptimizedCacheBytes: model.catalog.estimatedOptimizedCacheBytes)
        fetcher.setPayload(name, new)
        let upgraded = ModelInstaller(
            catalog: repinned,
            directory: root,
            fetcher: fetcher,
            optimizer: optimizer,
            availableDiskBytes: { 10_000_000 })

        XCTAssertEqual(upgraded.currentState(), .notDownloaded)
        try await upgraded.install(allowingCellular: false) { _ in }
        XCTAssertEqual(upgraded.currentState(), .ready)
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent(name)), new)
        XCTAssertEqual(try ledger()[name], TranslationFixtures.sha256(new))
    }

    /// The upgrade path (#127): an install made before the ledger existed has
    /// every file at its pinned size but nothing recorded. It must not read as
    /// ready on size alone, and repairing it fetches only the vocabulary — the
    /// cache is still valid, so the graphs are never needed again.
    func testAnInstallFromBeforeTheLedgerRefetchesOnlyTheVocabulary() async throws {
        let installer = makeInstaller()
        try await installer.install(allowingCellular: false) { _ in }
        try FileManager.default.removeItem(at: ledgerURL)
        let fetchedBefore = fetcher.requestCount
        let optimizedBefore = optimizer.optimizedGraphs.count

        XCTAssertEqual(installer.currentState(), .notDownloaded)

        try await installer.install(allowingCellular: false) { _ in }

        XCTAssertEqual(installer.currentState(), .ready)
        XCTAssertEqual(
            Set(fetcher.requests.dropFirst(fetchedBefore).map(\.name)),
            Set(model.catalog.vocabulary.map(\.name)))
        XCTAssertEqual(
            optimizer.optimizedGraphs.count, optimizedBefore, "the cache is reused, not rebuilt")
    }

    /// An unreadable ledger fails closed: nothing is trusted on size alone,
    /// and the next install repairs it.
    func testAMalformedLedgerFailsClosed() async throws {
        let installer = makeInstaller()
        try await installer.install(allowingCellular: false) { _ in }
        try Data("not json".utf8).write(to: ledgerURL)

        XCTAssertEqual(installer.currentState(), .notDownloaded)

        try await installer.install(allowingCellular: false) { _ in }
        XCTAssertEqual(installer.currentState(), .ready)
        XCTAssertEqual(try ledger().count, model.catalog.vocabulary.count)
    }

    // MARK: - Hash verification

    func testHashMismatchFailsLoudlyAndInstallsNothing() async throws {
        fetcher.corrupt("decoder_model.onnx")
        let installer = makeInstaller()

        do {
            try await installer.install(allowingCellular: false) { _ in }
            XCTFail("a hash mismatch must not be swallowed")
        } catch let error as TranslationError {
            guard case .hashMismatch(let file, let expected, let actual) = error else {
                return XCTFail("expected .hashMismatch, got \(error)")
            }
            XCTAssertEqual(file, "decoder_model.onnx")
            XCTAssertNotEqual(expected, actual)
        }

        XCTAssertFalse(
            exists("decoder_model.onnx"), "bytes that failed verification must never land")
        XCTAssertNil(try ledger()["decoder_model.onnx"], "nor be recorded as verified")
        XCTAssertEqual(installer.currentState(), .notDownloaded)
    }

    // MARK: - Cellular guard

    func testCellularIsRefusedByDefaultAndAllowedOnExplicitOptIn() async throws {
        fetcher.requiresCellular = true
        let installer = makeInstaller()

        do {
            try await installer.install(allowingCellular: false) { _ in }
            XCTFail("expected the cellular guard to refuse")
        } catch let error as TranslationError {
            XCTAssertEqual(error, .cellularNotAllowed)
        }
        XCTAssertEqual(installer.currentState(), .notDownloaded)
        XCTAssertEqual(fetcher.requests.first?.allowsCellularAccess, false)

        try await installer.install(allowingCellular: true) { _ in }
        XCTAssertEqual(installer.currentState(), .ready)
        XCTAssertTrue(fetcher.requests.suffix(5).allSatisfy(\.allowsCellularAccess))
    }

    // MARK: - Failure and resume

    func testAFailedFileIsRetriedWithItsResumeToken() async throws {
        fetcher.resumeTokenOnFailure = Data("resume-me".utf8)
        fetcher.failOnce["encoder_model.onnx"] = TranslationError.download("connection lost")
        let installer = makeInstaller()

        do {
            try await installer.install(allowingCellular: false) { _ in }
            XCTFail("expected the transport failure to surface")
        } catch let error as TranslationError {
            XCTAssertEqual(error, .download("connection lost"))
        }

        try await installer.install(allowingCellular: false) { _ in }
        XCTAssertEqual(installer.currentState(), .ready)

        let encoderAttempts = fetcher.requests.filter { $0.name == "encoder_model.onnx" }
        XCTAssertEqual(encoderAttempts.count, 2)
        XCTAssertFalse(encoderAttempts[0].hadResumeData)
        XCTAssertTrue(
            encoderAttempts[1].hadResumeData,
            "the retry should resume rather than restart when a token was produced")
    }

    /// A hash mismatch means the bytes are wrong, so resuming into the same
    /// broken transfer is the one thing not to do.
    func testHashMismatchDropsTheResumeToken() async throws {
        let name = "encoder_model.onnx"
        fetcher.resumeTokenOnFailure = Data("resume-me".utf8)
        fetcher.failOnce[name] = TranslationError.download("connection lost")
        let installer = makeInstaller()

        // 1. Transport failure -> a resume token is banked.
        _ = try? await installer.install(allowingCellular: false) { _ in }
        // 2. The resumed transfer returns corrupt bytes.
        fetcher.corrupt(name)
        _ = try? await installer.install(allowingCellular: false) { _ in }
        XCTAssertTrue(
            try XCTUnwrap(fetcher.requests.filter { $0.name == name }.last).hadResumeData,
            "attempt 2 should have resumed")

        // 3. The next attempt must start clean rather than resume into the
        //    same corruption.
        fetcher.setPayload(name, model.payload(name))
        try await installer.install(allowingCellular: false) { _ in }

        let attempts = fetcher.requests.filter { $0.name == name }
        XCTAssertEqual(attempts.count, 3)
        XCTAssertFalse(attempts[2].hadResumeData)
        XCTAssertEqual(installer.currentState(), .ready)
    }

    // MARK: - Cancellation

    func testCancellationStopsTheInstallWithoutFailing() async throws {
        fetcher.delay = .milliseconds(400)
        let installer = makeInstaller()

        let task = Task { try await installer.install(allowingCellular: false) { _ in } }
        // Wait until the first transfer is genuinely in flight.
        var spins = 0
        while fetcher.requestCount == 0 && spins < 200 {
            try await Task.sleep(for: .milliseconds(5))
            spins += 1
        }
        XCTAssertGreaterThan(fetcher.requestCount, 0, "the fetch never started")
        task.cancel()

        do {
            try await task.value
            XCTFail("a cancelled install must not report success")
        } catch {
            XCTAssertTrue(
                error is CancellationError || (error as? TranslationError) == .cancelled,
                "expected cancellation, got \(error)")
        }
        XCTAssertEqual(installer.currentState(), .notDownloaded)
    }

    // MARK: - Disk

    func testInsufficientDiskRefusesBeforeAnythingIsFetched() async throws {
        let installer = makeInstaller(availableDiskBytes: 16)

        do {
            try await installer.install(allowingCellular: false) { _ in }
            XCTFail("expected the free-space check to refuse")
        } catch let error as TranslationError {
            guard case .insufficientDisk(let available, let required) = error else {
                return XCTFail("expected .insufficientDisk, got \(error)")
            }
            XCTAssertEqual(available, 16)
            XCTAssertGreaterThan(required, available)
        }
        XCTAssertTrue(fetcher.requests.isEmpty)
    }

    func testUnknownFreeSpaceDoesNotBlockTheInstall() async throws {
        let installer = makeInstaller(availableDiskBytes: nil)
        try await installer.install(allowingCellular: false) { _ in }
        XCTAssertEqual(installer.currentState(), .ready)
    }

    // MARK: - Deletion

    func testDeleteEverythingReturnsToNotDownloaded() async throws {
        let installer = makeInstaller()
        try await installer.install(allowingCellular: false) { _ in }
        XCTAssertGreaterThan(installer.installedBytes(), 0)

        try installer.deleteEverything()

        XCTAssertEqual(installer.currentState(), .notDownloaded)
        XCTAssertEqual(installer.installedBytes(), 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.path))
    }

    func testDeleteIsSafeWhenNothingWasEverInstalled() throws {
        let installer = makeInstaller()
        XCTAssertNoThrow(try installer.deleteEverything())
        XCTAssertEqual(installer.currentState(), .notDownloaded)
    }
}
