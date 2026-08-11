import XCTest

@testable import Ardaas

/// The optimized-graph cache is derived state: if a stale cache is ever
/// accepted, the app silently runs graphs written by a different ORT build or
/// from superseded downloads. These tests pin the fail-closed behaviour, and
/// the retirement of the original graphs that #42 added on top.
///
/// Filesystem only — no ONNX Runtime, so they run on the CI simulator without
/// the 352 MB of real graphs.
final class ModelOptimizerTests: XCTestCase {
    private var root: URL!
    private var catalog: ModelCatalog!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("ModelOptimizerTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        catalog = TranslationFixtures.syntheticModel().catalog
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    // MARK: - Helpers

    /// Writes placeholder source graphs of the given size.
    private func makeSources(bytes: Int = 32) throws {
        for name in catalog.graphNames {
            try Data(repeating: 0x41, count: bytes)
                .write(to: root.appendingPathComponent(name))
        }
    }

    /// Writes placeholder optimized graphs, each with the `*.onnx.data`
    /// initializer side-car a real optimized session cannot load without.
    @discardableResult
    private func makeCachedGraphs() throws -> URL {
        let directory = ModelOptimizer.optimizedDirectory(in: root)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for name in catalog.graphNames {
            try Data(repeating: 0x42, count: 8).write(to: directory.appendingPathComponent(name))
            try Data(repeating: 0x43, count: 64)
                .write(to: directory.appendingPathComponent(name + ".data"))
        }
        return directory
    }

    private func sidecar(_ directory: URL, _ index: Int) -> URL {
        directory.appendingPathComponent(catalog.graphNames[index] + ".data")
    }

    private func isValid() -> Bool {
        ModelOptimizer.isCacheValid(in: root, catalog: catalog)
    }

    private func sourceExists(_ index: Int) -> Bool {
        FileManager.default.fileExists(
            atPath: root.appendingPathComponent(catalog.graphNames[index]).path)
    }

    // MARK: - Cache validation

    func testCacheIsInvalidWhenNothingHasBeenBuilt() throws {
        try makeSources()
        XCTAssertFalse(isValid())
    }

    func testCacheIsInvalidWithoutAManifest() throws {
        try makeSources()
        try makeCachedGraphs()
        XCTAssertFalse(isValid(), "graphs present but unmanifested must not be trusted")
    }

    func testCacheIsValidWhenManifestAndGraphsMatch() throws {
        try makeSources()
        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, catalog: catalog)
        XCTAssertTrue(isValid())
    }

    func testCacheIsInvalidWhenAGraphIsMissing() throws {
        try makeSources()
        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, catalog: catalog)
        try FileManager.default.removeItem(
            at: directory.appendingPathComponent(catalog.graphNames[1]))
        XCTAssertFalse(isValid())
    }

    /// An optimized graph is worthless without its external initializer file,
    /// so losing one must rebuild the cache rather than fail every load.
    func testCacheIsInvalidWhenAnInitializerSidecarIsMissing() throws {
        try makeSources()
        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, catalog: catalog)
        XCTAssertTrue(isValid())

        try FileManager.default.removeItem(at: sidecar(directory, 0))
        XCTAssertFalse(isValid())
    }

    func testCacheIsInvalidWhenAnInitializerSidecarIsTruncated() throws {
        try makeSources()
        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, catalog: catalog)

        try Data(repeating: 0x43, count: 8).write(to: sidecar(directory, 2))
        XCTAssertFalse(isValid())
    }

    /// A cache of graphs with their weights still inline would load fine and
    /// silently forfeit the entire memory saving, so it must be rejected.
    func testCacheIsInvalidWhenNoSidecarWasEverRecorded() throws {
        try makeSources()
        let directory = ModelOptimizer.optimizedDirectory(in: root)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for name in catalog.graphNames {
            try Data(repeating: 0x42, count: 8).write(to: directory.appendingPathComponent(name))
        }
        try ModelOptimizer.writeManifest(in: directory, catalog: catalog)
        XCTAssertFalse(isValid())
    }

    /// The cache is keyed on the pinned artifact set rather than on the source
    /// files, precisely so the source files can be deleted. Changing the
    /// catalog must therefore invalidate it.
    func testCacheIsInvalidatedWhenTheCatalogRevisionChanges() throws {
        try makeSources()
        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, catalog: catalog)
        XCTAssertTrue(isValid())

        var files = catalog.files
        files[0] = ModelCatalog.File(
            name: files[0].name, bytes: files[0].bytes + 1,
            sha256: String(repeating: "0", count: 64), kind: files[0].kind)
        let bumped = ModelCatalog(
            files: files, repoBase: catalog.repoBase, directoryName: catalog.directoryName,
            estimatedOptimizedCacheBytes: catalog.estimatedOptimizedCacheBytes)

        XCTAssertNotEqual(bumped.revision, catalog.revision)
        XCTAssertFalse(ModelOptimizer.isCacheValid(in: root, catalog: bumped))
    }

    func testCacheSurvivesTheSourceGraphsBeingDeleted() throws {
        try makeSources()
        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, catalog: catalog)

        for name in catalog.graphNames {
            try FileManager.default.removeItem(at: root.appendingPathComponent(name))
        }
        XCTAssertTrue(
            isValid(),
            "the whole point of retiring the originals is that the cache stays valid without them")
    }

    func testManifestPinsTheRecipeRuntimeRevisionAndEveryProducedFile() throws {
        try makeSources()
        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, catalog: catalog)

        let data = try Data(contentsOf: directory.appendingPathComponent("manifest.json"))
        let manifest = try JSONDecoder().decode(ModelOptimizer.Manifest.self, from: data)
        XCTAssertEqual(manifest.formatVersion, ModelOptimizer.formatVersion)
        XCTAssertEqual(manifest.ortPackageVersion, ModelOptimizer.ortPackageVersion)
        XCTAssertEqual(manifest.catalogRevision, catalog.revision)
        // Graphs and side-cars, and never the manifest itself.
        XCTAssertEqual(
            Set(manifest.outputs.keys),
            Set(catalog.graphNames + catalog.graphNames.map { $0 + ".data" }))
    }

    // MARK: - Disk accounting

    func testCacheBytesAndRemoval() throws {
        try makeSources()
        XCTAssertEqual(ModelOptimizer.cacheBytes(in: root), 0)

        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, catalog: catalog)
        XCTAssertGreaterThan(ModelOptimizer.cacheBytes(in: root), 0)

        ModelOptimizer.removeCache(in: root)
        XCTAssertEqual(ModelOptimizer.cacheBytes(in: root), 0)
        XCTAssertFalse(isValid())
    }

    /// A build killed part-way strands several hundred MB in the scratch
    /// directory; the user must be able to see and reclaim it.
    func testAbandonedScratchIsCountedAndReclaimable() throws {
        try makeSources()
        let scratch = ModelOptimizer.scratchDirectory(in: root)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        try Data(repeating: 0x44, count: 4096)
            .write(to: scratch.appendingPathComponent(catalog.graphNames[0]))

        XCTAssertEqual(
            ModelOptimizer.cacheBytes(in: root), 4096,
            "an abandoned scratch build must be visible on the disk row")
        XCTAssertFalse(isValid())

        ModelOptimizer.removeCache(in: root)
        XCTAssertEqual(ModelOptimizer.cacheBytes(in: root), 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: scratch.path))
    }

    // MARK: - Build

    func testBuildProducesAValidCacheAndIsIdempotent() throws {
        try makeSources()
        let optimizer = FakeGraphOptimizer()

        let cache = try ModelOptimizer.build(
            modelDirectory: root, catalog: catalog, optimizer: optimizer)

        XCTAssertEqual(cache, ModelOptimizer.optimizedDirectory(in: root))
        XCTAssertTrue(isValid())
        XCTAssertEqual(optimizer.optimizedGraphs.sorted(), catalog.graphNames.sorted())

        try ModelOptimizer.build(modelDirectory: root, catalog: catalog, optimizer: optimizer)
        XCTAssertEqual(
            optimizer.optimizedGraphs.count, catalog.graphNames.count,
            "a valid cache must not be rebuilt")
    }

    func testBuildFailsWhenNoSidecarIsWritten() throws {
        try makeSources()
        let optimizer = FakeGraphOptimizer()
        optimizer.writesSidecar = false

        XCTAssertThrowsError(
            try ModelOptimizer.build(
                modelDirectory: root, catalog: catalog, optimizer: optimizer)
        ) { error in
            guard case .optimizationFailed = (error as? TranslationError) else {
                return XCTFail("expected .optimizationFailed, got \(error)")
            }
        }
        XCTAssertFalse(isValid())
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: ModelOptimizer.scratchDirectory(in: root).path),
            "a failed build must not strand hundreds of MB of scratch")
    }

    func testBuildFailsWhenASourceGraphIsMissing() throws {
        try makeSources()
        try FileManager.default.removeItem(
            at: root.appendingPathComponent(catalog.graphNames[2]))

        XCTAssertThrowsError(
            try ModelOptimizer.build(
                modelDirectory: root, catalog: catalog, optimizer: FakeGraphOptimizer())
        ) { error in
            XCTAssertEqual(
                error as? TranslationError, .missingModelFile(catalog.graphNames[2]))
        }
    }

    // MARK: - Retiring the originals

    func testRetireDeletesTheOriginalsOnlyAfterVerifyingTheCache() throws {
        try makeSources()
        let optimizer = FakeGraphOptimizer()
        try ModelOptimizer.build(modelDirectory: root, catalog: catalog, optimizer: optimizer)

        try ModelOptimizer.retireSourceGraphs(
            in: root, catalog: catalog, optimizer: optimizer)

        XCTAssertEqual(optimizer.verifiedGraphs.sorted(), catalog.graphNames.sorted())
        for index in catalog.graphNames.indices {
            XCTAssertFalse(sourceExists(index))
        }
        XCTAssertTrue(isValid())
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: ModelOptimizer.retiredDirectory(in: root).path))
    }

    /// If the optimized graphs turn out not to be self-sufficient, the
    /// originals have to come back — a fat working install beats a thin broken
    /// one.
    func testRetireRestoresTheOriginalsWhenVerificationFails() throws {
        try makeSources()
        let optimizer = FakeGraphOptimizer()
        try ModelOptimizer.build(modelDirectory: root, catalog: catalog, optimizer: optimizer)
        optimizer.verifyError = TranslationError.sessionFailure("cannot load external data")

        XCTAssertThrowsError(
            try ModelOptimizer.retireSourceGraphs(
                in: root, catalog: catalog, optimizer: optimizer))

        for index in catalog.graphNames.indices {
            XCTAssertTrue(sourceExists(index), "the originals must be put back")
        }
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: ModelOptimizer.retiredDirectory(in: root).path),
            "the staging directory must not be left behind")
    }

    func testRetireRefusesWithoutAValidCache() throws {
        try makeSources()
        XCTAssertThrowsError(
            try ModelOptimizer.retireSourceGraphs(
                in: root, catalog: catalog, optimizer: FakeGraphOptimizer()))
        for index in catalog.graphNames.indices {
            XCTAssertTrue(sourceExists(index))
        }
    }

    func testRetireIsIdempotent() throws {
        try makeSources()
        let optimizer = FakeGraphOptimizer()
        try ModelOptimizer.build(modelDirectory: root, catalog: catalog, optimizer: optimizer)
        try ModelOptimizer.retireSourceGraphs(in: root, catalog: catalog, optimizer: optimizer)
        let verifiedOnce = optimizer.verifiedGraphs.count

        try ModelOptimizer.retireSourceGraphs(in: root, catalog: catalog, optimizer: optimizer)
        XCTAssertEqual(
            optimizer.verifiedGraphs.count, verifiedOnce,
            "with nothing left to retire there is nothing to verify")
        XCTAssertTrue(isValid())
    }
}

/// The memory numbers are only meaningful if the mach plumbing behind them
/// reports, and the pre-flight refusal is the guard standing between a large
/// allocation and a jetsam kill.
final class MemoryProbeTests: XCTestCase {
    func testSnapshotReportsAFootprint() throws {
        let snapshot = try XCTUnwrap(MemoryProbe.snapshot())
        XCTAssertGreaterThan(snapshot.footprintBytes, 0)
        XCTAssertGreaterThanOrEqual(snapshot.peakBytes, snapshot.footprintBytes)
    }

    func testSamplerReportsAtLeastTheFootprintAtStop() {
        let sampler = MemoryProbe.Sampler(intervalSeconds: 0.001)
        var blocks: [Data] = []
        for _ in 0..<8 { blocks.append(Data(repeating: 0x7, count: 1 << 20)) }
        let peak = sampler.stop()
        XCTAssertGreaterThan(peak, 0)
        XCTAssertEqual(sampler.stop(), peak, "stop must be idempotent")
        XCTAssertEqual(blocks.count, 8)
    }

    func testGuardRefusesBelowTheRequiredHeadroom() {
        XCTAssertThrowsError(
            try MemoryGuard.check(availableBytes: 200 * 1_048_576, requiredBytes: 800 * 1_048_576)
        ) { error in
            XCTAssertEqual(
                error as? TranslationError,
                .insufficientMemory(
                    availableBytes: 200 * 1_048_576, requiredBytes: 800 * 1_048_576))
        }
    }

    func testGuardAllowsAtOrAboveTheRequiredHeadroom() {
        XCTAssertNoThrow(
            try MemoryGuard.check(availableBytes: 800 * 1_048_576, requiredBytes: 800 * 1_048_576))
        XCTAssertNoThrow(
            try MemoryGuard.check(availableBytes: 4_000 * 1_048_576, requiredBytes: 800 * 1_048_576))
    }

    /// `os_proc_available_memory()` reports 0 outside an app context. Refusing
    /// every translation on the strength of a reading we could not take would
    /// be worse than the risk the guard exists for.
    func testGuardDoesNotRefuseWhenHeadroomIsUnmeasurable() {
        XCTAssertNoThrow(try MemoryGuard.check(availableBytes: nil))
    }

    func testRequiredHeadroomExceedsTheMeasuredPeak() {
        XCTAssertGreaterThan(MemoryGuard.requiredHeadroomBytes, MemoryGuard.measuredPeakBytes)
    }
}
