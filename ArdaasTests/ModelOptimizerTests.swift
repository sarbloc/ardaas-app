import XCTest

@testable import Ardaas

/// Spike (#35), memory pass. The optimized-graph cache is derived state: if a
/// stale cache is ever accepted, the app silently runs graphs written by a
/// different ORT build or from superseded downloads. These tests pin the
/// fail-closed behaviour. They touch only the filesystem — no ONNX Runtime, so
/// they run on the CI simulator without the 352 MB of model files.
final class ModelOptimizerTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("ModelOptimizerTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    // MARK: - Helpers

    /// Writes placeholder source graphs of the given size.
    private func makeSources(bytes: Int = 32) throws {
        for name in ModelOptimizer.graphNames {
            try Data(repeating: 0x41, count: bytes)
                .write(to: root.appendingPathComponent(name))
        }
    }

    /// Writes placeholder optimized graphs, each with the `*.onnx.data`
    /// initializer side-car a real optimized session cannot load without.
    private func makeCachedGraphs() throws -> URL {
        let directory = ModelOptimizer.optimizedDirectory(in: root)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for name in ModelOptimizer.graphNames {
            try Data(repeating: 0x42, count: 8).write(to: directory.appendingPathComponent(name))
            try Data(repeating: 0x43, count: 64)
                .write(to: directory.appendingPathComponent(name + ".data"))
        }
        return directory
    }

    private func sidecar(_ directory: URL, _ index: Int) -> URL {
        directory.appendingPathComponent(ModelOptimizer.graphNames[index] + ".data")
    }

    // MARK: - Tests

    func testCacheIsInvalidWhenNothingHasBeenBuilt() throws {
        try makeSources()
        XCTAssertFalse(ModelOptimizer.isCacheValid(in: root))
    }

    func testCacheIsInvalidWithoutAManifest() throws {
        try makeSources()
        _ = try makeCachedGraphs()
        XCTAssertFalse(
            ModelOptimizer.isCacheValid(in: root),
            "graphs present but unmanifested must not be trusted")
    }

    func testCacheIsInvalidWhenAGraphIsMissing() throws {
        try makeSources()
        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, for: root)
        try FileManager.default.removeItem(
            at: directory.appendingPathComponent(ModelOptimizer.graphNames[1]))
        XCTAssertFalse(ModelOptimizer.isCacheValid(in: root))
    }

    func testCacheIsValidWhenManifestAndGraphsMatchTheSources() throws {
        try makeSources()
        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, for: root)
        XCTAssertTrue(ModelOptimizer.isCacheValid(in: root))
    }

    func testCacheIsInvalidatedWhenASourceGraphChangesSize() throws {
        try makeSources(bytes: 32)
        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, for: root)
        XCTAssertTrue(ModelOptimizer.isCacheValid(in: root))

        // A re-download that produced different bytes must not reuse the cache.
        try Data(repeating: 0x41, count: 64)
            .write(to: root.appendingPathComponent(ModelOptimizer.graphNames[0]))
        XCTAssertFalse(ModelOptimizer.isCacheValid(in: root))
    }

    /// An optimized graph is worthless without its external initializer file,
    /// so losing one must rebuild the cache rather than fail every load.
    func testCacheIsInvalidWhenAnInitializerSidecarIsMissing() throws {
        try makeSources()
        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, for: root)
        XCTAssertTrue(ModelOptimizer.isCacheValid(in: root))

        try FileManager.default.removeItem(at: sidecar(directory, 0))
        XCTAssertFalse(ModelOptimizer.isCacheValid(in: root))
    }

    func testCacheIsInvalidWhenAnInitializerSidecarIsTruncated() throws {
        try makeSources()
        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, for: root)

        try Data(repeating: 0x43, count: 8).write(to: sidecar(directory, 2))
        XCTAssertFalse(ModelOptimizer.isCacheValid(in: root))
    }

    func testManifestPinsTheRecipeRuntimeVersionAndEveryProducedFile() throws {
        try makeSources()
        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, for: root)

        let data = try Data(contentsOf: directory.appendingPathComponent("manifest.json"))
        let manifest = try JSONDecoder().decode(ModelOptimizer.Manifest.self, from: data)
        XCTAssertEqual(manifest.formatVersion, ModelOptimizer.formatVersion)
        XCTAssertEqual(manifest.ortPackageVersion, ModelOptimizer.ortPackageVersion)
        XCTAssertEqual(Set(manifest.sources.keys), Set(ModelOptimizer.graphNames))
        // Graphs and side-cars, and never the manifest itself.
        XCTAssertEqual(
            Set(manifest.outputs.keys),
            Set(ModelOptimizer.graphNames + ModelOptimizer.graphNames.map { $0 + ".data" }))
    }

    func testSourceFingerprintsFailWhenASourceIsMissing() throws {
        try makeSources()
        try FileManager.default.removeItem(
            at: root.appendingPathComponent(ModelOptimizer.graphNames[2]))
        XCTAssertThrowsError(try ModelOptimizer.sourceFingerprints(for: root))
    }

    /// A repaired or re-downloaded graph can be byte-different at the same
    /// length; size alone would keep the stale cache alive.
    func testCacheIsInvalidatedWhenASourceIsRewrittenAtTheSameSize() throws {
        try makeSources(bytes: 32)
        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, for: root)
        XCTAssertTrue(ModelOptimizer.isCacheValid(in: root))

        let source = root.appendingPathComponent(ModelOptimizer.graphNames[1])
        try Data(repeating: 0x5A, count: 32).write(to: source)
        try FileManager.default.setAttributes(
            [.modificationDate: Date().addingTimeInterval(60)], ofItemAtPath: source.path)
        XCTAssertFalse(ModelOptimizer.isCacheValid(in: root))
    }

    func testCacheBytesAndRemoval() throws {
        try makeSources()
        XCTAssertEqual(ModelOptimizer.cacheBytes(in: root), 0)

        let directory = try makeCachedGraphs()
        try ModelOptimizer.writeManifest(in: directory, for: root)
        XCTAssertGreaterThan(ModelOptimizer.cacheBytes(in: root), 0)

        ModelOptimizer.removeCache(in: root)
        XCTAssertEqual(ModelOptimizer.cacheBytes(in: root), 0)
        XCTAssertFalse(ModelOptimizer.isCacheValid(in: root))
    }

    /// A build killed part-way strands several hundred MB in the scratch
    /// directory; the user must be able to see and reclaim it.
    func testAbandonedScratchIsCountedAndReclaimable() throws {
        try makeSources()
        let scratch = ModelOptimizer.scratchDirectory(in: root)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        try Data(repeating: 0x44, count: 4096)
            .write(to: scratch.appendingPathComponent(ModelOptimizer.graphNames[0]))

        XCTAssertEqual(
            ModelOptimizer.cacheBytes(in: root), 4096,
            "an abandoned scratch build must be visible on the disk row")
        XCTAssertFalse(ModelOptimizer.isCacheValid(in: root))

        ModelOptimizer.removeCache(in: root)
        XCTAssertEqual(ModelOptimizer.cacheBytes(in: root), 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: scratch.path))
    }
}

/// The memory numbers in the lab screen are only meaningful if the mach
/// plumbing behind them actually reports.
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
}
