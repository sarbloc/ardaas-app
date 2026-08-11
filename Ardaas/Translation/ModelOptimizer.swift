import Foundation

/// Rewrites a downloaded ONNX graph so its weights can be memory-mapped, and
/// checks that the result can be loaded back on its own.
///
/// Abstracted from `ModelOptimizer` for two reasons: the ONNX Runtime
/// implementation only exists when the `BENTI_ONNX` compilation condition is
/// on, and the cache/disk-hygiene logic has to be testable without 352 MB of
/// real graphs.
protocol GraphOptimizing: Sendable {
    /// False when this build cannot optimize graphs at all. Lets an install be
    /// refused up front instead of after 359 MB has been fetched.
    var isAvailable: Bool { get }

    /// Writes an ORT-optimized copy of `source` at `destination`, with its
    /// initializers in a side-car named `externalDataName` beside it.
    func optimize(source: URL, destination: URL, externalDataName: String) throws

    /// Loads `graph` and throws if it cannot be used. Called with the original
    /// downloads already moved out of the way, so a graph that secretly still
    /// depends on them fails here rather than after they are deleted.
    func verifyLoadable(graph: URL) throws
}

extension GraphOptimizing {
    var isAvailable: Bool { true }
}

/// Used when the app was built without the ONNX Runtime dependency.
struct UnavailableGraphOptimizer: GraphOptimizing {
    var isAvailable: Bool { false }

    func optimize(source: URL, destination: URL, externalDataName: String) throws {
        throw TranslationError.engineUnavailable
    }

    func verifyLoadable(graph: URL) throws {
        throw TranslationError.engineUnavailable
    }
}

/// The one-time, on-device rewrite of the three inference graphs into
/// ORT-optimized graphs whose initializers (and pre-packed initializers) live
/// in a side-car `.data` file.
///
/// This is the single biggest memory lever reachable through the ONNX Runtime
/// Objective-C API — 1374 MB → 582 MB peak `phys_footprint` in total, of which
/// this pass is ~215 MB. The full analysis is in docs/translation/MEMORY.md;
/// the short version is that initializers stored inside the .onnx protobuf are
/// read onto the heap as anonymous dirty pages and charged to jetsam, while
/// initializers in an external data file are memory-mapped and are not charged
/// even while fully resident.
enum ModelOptimizer {
    /// Bump when the optimization recipe changes.
    static let formatVersion = 2

    /// Matches the `exactVersion` pinned for the onnxruntime package in
    /// project-onnx.yml. An optimized graph is only guaranteed loadable by the
    /// ORT build that wrote it, so it is part of the cache key.
    ///
    /// Deliberately a constant rather than something the live `GraphOptimizing`
    /// reports: a build without ONNX must still be able to read (and account
    /// for, and delete) a cache an ONNX build left behind.
    static let ortPackageVersion = "1.24.2"

    static let manifestName = "manifest.json"

    /// What the cache recorded about itself. Written last, so until it lands
    /// the cache reads as invalid.
    struct Manifest: Codable, Equatable {
        let formatVersion: Int
        let ortPackageVersion: String
        /// `ModelCatalog.revision` — the identity of the pinned artifact set
        /// the cache was built from.
        ///
        /// The spike fingerprinted the *source files* by size and mtime, which
        /// is impossible once those files are deleted. Pinning the catalog
        /// revision instead is both stronger and durable: the downloads were
        /// SHA-256 verified against exactly these constants, so a matching
        /// revision means the cache came from exactly these bytes.
        let catalogRevision: String
        /// Every file the cache produced → byte size: the optimized graphs and
        /// the `*.onnx.data` initializer side-cars they are useless without.
        let outputs: [String: Int64]
    }

    // MARK: - Locations

    static func optimizedDirectory(in modelDirectory: URL) -> URL {
        modelDirectory.appendingPathComponent("Optimized", isDirectory: true)
    }

    /// Where a new cache is assembled before being moved into place.
    static func scratchDirectory(in modelDirectory: URL) -> URL {
        modelDirectory.appendingPathComponent("Optimized.building", isDirectory: true)
    }

    /// Where the original graphs are parked while the cache is proved
    /// self-sufficient. Emptied on success, restored on failure.
    static func retiredDirectory(in modelDirectory: URL) -> URL {
        modelDirectory.appendingPathComponent("Originals.retiring", isDirectory: true)
    }

    /// The external initializer blob ORT writes beside an optimized graph. The
    /// whole point of the pass: without it the weights are back inside the
    /// .onnx and back on the heap.
    static func externalDataName(for graph: String) -> String { graph + ".data" }

    /// Every directory this type may hold bytes in. All are reported and
    /// deleted together: a build killed part-way leaves several hundred MB
    /// behind, which the user must be able to see and reclaim.
    private static func cacheDirectories(in modelDirectory: URL) -> [URL] {
        [
            optimizedDirectory(in: modelDirectory),
            scratchDirectory(in: modelDirectory),
            retiredDirectory(in: modelDirectory),
        ]
    }

    private static func manifestURL(in directory: URL) -> URL {
        directory.appendingPathComponent(manifestName)
    }

    static func fileSize(_ url: URL) -> Int64? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = (attributes[.size] as? NSNumber)?.int64Value
        else { return nil }
        return size
    }

    /// Total bytes held on disk by the optimized cache, any abandoned scratch
    /// build, and any retirement that did not finish.
    static func cacheBytes(in modelDirectory: URL) -> Int64 {
        cacheDirectories(in: modelDirectory).reduce(0) { total, directory in
            guard let names = try? FileManager.default.contentsOfDirectory(atPath: directory.path)
            else { return total }
            return names.reduce(total) { subtotal, name in
                subtotal + (fileSize(directory.appendingPathComponent(name)) ?? 0)
            }
        }
    }

    static func removeCache(in modelDirectory: URL) {
        for directory in cacheDirectories(in: modelDirectory) {
            try? FileManager.default.removeItem(at: directory)
        }
    }

    // MARK: - Validation

    /// True when a cache matching the current recipe, runtime and artifact
    /// revision exists, and every file it recorded is still present at its
    /// recorded size.
    static func isCacheValid(in modelDirectory: URL, catalog: ModelCatalog) -> Bool {
        let directory = optimizedDirectory(in: modelDirectory)
        guard let data = try? Data(contentsOf: manifestURL(in: directory)),
              let stored = try? JSONDecoder().decode(Manifest.self, from: data),
              stored.formatVersion == formatVersion,
              stored.ortPackageVersion == ortPackageVersion,
              stored.catalogRevision == catalog.revision
        else { return false }

        // Every graph AND its initializer side-car must be accounted for: a
        // cache of graphs with their weights still inline would load fine and
        // silently give up the entire memory saving.
        let required = catalog.graphNames.flatMap { [$0, externalDataName(for: $0)] }
        guard required.allSatisfy({ stored.outputs[$0] != nil }) else { return false }
        return stored.outputs.allSatisfy { name, size in
            fileSize(directory.appendingPathComponent(name)) == size
        }
    }

    /// Records the recipe, the runtime, the artifact revision and every file
    /// the cache produced. Must be called once the directory is fully written —
    /// it fingerprints whatever it finds there.
    ///
    /// Exposed for tests; `build` is the only caller in the app.
    static func writeManifest(in directory: URL, catalog: ModelCatalog) throws {
        var outputs: [String: Int64] = [:]
        let names = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        for name in names where name != manifestName {
            guard let size = fileSize(directory.appendingPathComponent(name)) else { continue }
            outputs[name] = size
        }
        let manifest = Manifest(
            formatVersion: formatVersion,
            ortPackageVersion: ortPackageVersion,
            catalogRevision: catalog.revision,
            outputs: outputs
        )
        try JSONEncoder().encode(manifest).write(to: manifestURL(in: directory), options: .atomic)
    }

    // MARK: - Build

    /// Builds the optimized cache if it is missing or stale, and returns the
    /// directory the translator should load its graphs from.
    ///
    /// Writes into a scratch directory and moves it into place, so a crash or
    /// kill mid-pass can never leave a half-written cache behind.
    @discardableResult
    static func build(
        modelDirectory: URL,
        catalog: ModelCatalog,
        optimizer: GraphOptimizing,
        onProgress: (String) -> Void = { _ in }
    ) throws -> URL {
        let destination = optimizedDirectory(in: modelDirectory)
        if isCacheValid(in: modelDirectory, catalog: catalog) { return destination }

        for name in catalog.graphNames {
            let source = modelDirectory.appendingPathComponent(name)
            guard FileManager.default.fileExists(atPath: source.path) else {
                throw TranslationError.missingModelFile(name)
            }
        }

        let scratch = scratchDirectory(in: modelDirectory)
        try? FileManager.default.removeItem(at: scratch)
        // Drop the previous cache before rebuilding rather than after. Getting
        // here means it already failed validation, so it is dead weight — and
        // keeping it would make a routine rebuild (ORT bump, re-download)
        // demand a second cache's worth of free space, turning an upgrade into
        // an out-of-space failure on a nearly full device.
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)

        var moved = false
        defer {
            // Half-written graphs are several hundred MB; never leave them.
            if !moved { try? FileManager.default.removeItem(at: scratch) }
        }

        for name in catalog.graphNames {
            onProgress(name)
            try autoreleasepool {
                try optimizeOne(
                    source: modelDirectory.appendingPathComponent(name),
                    destination: scratch.appendingPathComponent(name),
                    optimizer: optimizer
                )
            }
        }

        // Written last: until the manifest lands the cache reads as invalid.
        try writeManifest(in: scratch, catalog: catalog)

        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.moveItem(at: scratch, to: destination)
        moved = true
        excludeFromBackup(destination)
        return destination
    }

    private static func optimizeOne(
        source: URL, destination: URL, optimizer: GraphOptimizing
    ) throws {
        let externalDataName = Self.externalDataName(for: destination.lastPathComponent)
        try optimizer.optimize(
            source: source, destination: destination, externalDataName: externalDataName)

        // Fail loudly rather than cache a graph whose weights are still inline:
        // it would load perfectly and quietly forfeit the whole memory saving.
        let externalData = destination.deletingLastPathComponent()
            .appendingPathComponent(externalDataName)
        guard let bytes = fileSize(externalData), bytes > 0 else {
            throw TranslationError.optimizationFailed(
                graph: source.lastPathComponent,
                detail: "no external initializer file was written (\(externalDataName)); "
                    + "this ONNX Runtime build may not support external initializers")
        }
    }

    // MARK: - Disk hygiene

    /// Deletes the original `.onnx` downloads once the optimized cache has been
    /// proved to load without them.
    ///
    /// The spike kept both, costing ~670 MB of cache *on top of* ~352 MB of
    /// originals for no benefit. Deleting them is only safe if the cache is
    /// genuinely self-sufficient, so the originals are moved aside first,
    /// every optimized graph is loaded with them absent, and only then are they
    /// dropped. Any failure puts them back and throws, leaving a working (if
    /// fat) install rather than a broken thin one.
    ///
    /// Idempotent: a no-op once the originals are gone.
    static func retireSourceGraphs(
        in modelDirectory: URL,
        catalog: ModelCatalog,
        optimizer: GraphOptimizing
    ) throws {
        guard isCacheValid(in: modelDirectory, catalog: catalog) else {
            throw TranslationError.optimizationFailed(
                graph: "cache", detail: "refusing to delete originals without a valid cache")
        }

        let fileManager = FileManager.default
        let present = catalog.graphNames.filter {
            fileManager.fileExists(atPath: modelDirectory.appendingPathComponent($0).path)
        }
        // Deliberately do NOT delete a pre-existing retirement directory here.
        // If a previous run was killed mid-retirement, those parked originals
        // are the only recovery copy, and this path has not re-run
        // verifyLoadable — dropping them could strand an unverified cache with
        // nothing to fall back to. They are reclaimed safely elsewhere:
        // removeCache (disk-pressure rebuild) and deleteEverything (user
        // delete) both include Originals.retiring.
        guard !present.isEmpty else { return }

        let retired = retiredDirectory(in: modelDirectory)
        try? fileManager.removeItem(at: retired)
        try fileManager.createDirectory(at: retired, withIntermediateDirectories: true)

        var parked: [String] = []
        func restore() {
            for name in parked {
                try? fileManager.moveItem(
                    at: retired.appendingPathComponent(name),
                    to: modelDirectory.appendingPathComponent(name))
            }
            try? fileManager.removeItem(at: retired)
        }

        do {
            for name in present {
                try fileManager.moveItem(
                    at: modelDirectory.appendingPathComponent(name),
                    to: retired.appendingPathComponent(name))
                parked.append(name)
            }
            let cache = optimizedDirectory(in: modelDirectory)
            for name in catalog.graphNames {
                try autoreleasepool {
                    try optimizer.verifyLoadable(graph: cache.appendingPathComponent(name))
                }
            }
        } catch {
            restore()
            // The cache we just proved unloadable still looks structurally
            // valid (manifest + sizes), so leaving it would make
            // currentState() report .ready and route translate() straight
            // into it after a relaunch. Drop it; the originals are back, so
            // the next install rebuilds rather than re-downloading.
            removeCache(in: modelDirectory)
            throw TranslationError.optimizationFailed(
                graph: "cache",
                detail: "optimized graphs did not load without the originals: \(error)")
        }

        try? fileManager.removeItem(at: retired)
    }

    // MARK: - Backup

    static func excludeFromBackup(_ url: URL) {
        var url = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        do {
            try url.setResourceValues(values)
        } catch {
            // Non-fatal, but never silent.
            print("BentiModel: failed to exclude \(url.lastPathComponent) from backup: \(error)")
        }
    }
}
