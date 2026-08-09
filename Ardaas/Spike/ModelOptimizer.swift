import Foundation
import OnnxRuntimeBindings

// Spike (#35), memory pass: rewrite the three downloaded ONNX graphs into
// ORT-optimized graphs whose initializers (and pre-packed initializers) live in
// a side-car `.data` file.
//
// Why this is the single biggest memory lever available through the ONNX
// Runtime Objective-C API:
//
// * When a model's initializers are stored *inside* the .onnx protobuf, ORT
//   reads them onto the heap. ~193 MB of anonymous, dirty pages per decoder
//   graph, all of it charged to `phys_footprint` and therefore to jetsam.
// * When they live in an external data file, ORT memory-maps that file
//   (`GetExtDataFromTensorProto` -> `Env::MapFileIntoMemory`, the default on
//   every POSIX platform including iOS). Mapped read-only file pages are
//   "external" in the XNU ledger and are *not* charged to `phys_footprint`,
//   even while fully resident.
// * `session.save_external_prepacked_constant_initializers` extends that to the
//   pre-packed (kernel-rearranged) weight buffers, which would otherwise be
//   re-materialised on the heap at session creation and undo most of the win.
//
// The rewrite is a pure ORT graph optimization: measured bit-identical greedy
// token ids on all 21 tokenizer parity fixtures.
//
// The pass must run CPU-only. Registering CoreML (or any EP that produces
// compiled nodes) makes ORT refuse to serialize the optimized model with
// "Unable to serialize model as it contains compiled nodes". This spike never
// appends an execution provider, so CPU-only is simply the default.

enum ModelOptimizerError: Error, CustomStringConvertible {
    case missingSource(String)
    case optimizationFailed(graph: String, underlying: String)

    var description: String {
        switch self {
        case .missingSource(let name):
            return "Missing source graph: \(name)"
        case .optimizationFailed(let graph, let underlying):
            return "Failed to optimize \(graph): \(underlying)"
        }
    }
}

enum ModelOptimizer {
    /// The three inference graphs, in load order.
    static let graphNames = [
        "encoder_model.onnx",
        "decoder_model.onnx",
        "decoder_with_past_model.onnx",
    ]

    /// Bump when the optimization recipe changes, or when the pinned ONNX
    /// Runtime version changes — an optimized graph is only guaranteed to be
    /// loadable by the ORT build that wrote it.
    static let formatVersion = 1

    /// Identity of a downloaded source graph.
    ///
    /// Size alone is not enough: a repaired or re-downloaded file of the same
    /// length would keep the fingerprint stable and the stale cache would be
    /// reused against different bytes. Modification time closes that, and
    /// unlike a SHA-256 it does not mean re-hashing 352 MB on every load —
    /// which `ModelDownloader` already declines to do for the same reason.
    struct SourceFingerprint: Codable, Equatable {
        let bytes: Int64
        let modifiedAt: Double
    }

    struct Manifest: Codable, Equatable {
        let formatVersion: Int
        let ortPackageVersion: String
        /// Source graph name -> fingerprint, so an interrupted, repaired or
        /// superseded download invalidates the cache.
        let sources: [String: SourceFingerprint]
        /// Every file the cache produced -> byte size: the optimized graphs and
        /// the `*.onnx.data` initializer side-cars they are useless without.
        /// Checked on load so a partial cleanup, failed restore or truncated
        /// write rebuilds the cache instead of failing every session creation.
        let outputs: [String: Int64]
    }

    private static let manifestName = "manifest.json"

    /// Matches the `exactVersion` pinned for the onnxruntime package in
    /// project.yml. Part of the cache key so bumping ORT rebuilds the cache.
    static let ortPackageVersion = "1.24.2"

    static func optimizedDirectory(in modelDirectory: URL) -> URL {
        modelDirectory.appendingPathComponent("Optimized", isDirectory: true)
    }

    /// Where `prepare` assembles a new cache before moving it into place.
    static func scratchDirectory(in modelDirectory: URL) -> URL {
        modelDirectory.appendingPathComponent("Optimized.building", isDirectory: true)
    }

    /// Every directory this type may hold bytes in. Both are reported and
    /// deleted together: a build killed part-way leaves several hundred MB in
    /// the scratch directory, which the user must be able to see and reclaim.
    private static func cacheDirectories(in modelDirectory: URL) -> [URL] {
        [optimizedDirectory(in: modelDirectory), scratchDirectory(in: modelDirectory)]
    }

    private static func manifestURL(in directory: URL) -> URL {
        directory.appendingPathComponent(manifestName)
    }

    private static func fileSize(_ url: URL) -> Int64? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = (attributes[.size] as? NSNumber)?.int64Value
        else { return nil }
        return size
    }

    /// Total bytes held on disk by the optimized cache and any abandoned
    /// scratch build, for the lab screen's disk row.
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

    /// True when a cache matching the current recipe and source files exists,
    /// and every file it recorded is still present at its recorded size.
    static func isCacheValid(in modelDirectory: URL) -> Bool {
        let directory = optimizedDirectory(in: modelDirectory)
        guard let sources = try? sourceFingerprints(for: modelDirectory),
              let data = try? Data(contentsOf: manifestURL(in: directory)),
              let stored = try? JSONDecoder().decode(Manifest.self, from: data),
              stored.formatVersion == formatVersion,
              stored.ortPackageVersion == ortPackageVersion,
              stored.sources == sources
        else { return false }

        // Every graph must be accounted for, and every recorded file — graphs
        // and their initializer side-cars alike — must still be intact.
        guard graphNames.allSatisfy({ stored.outputs[$0] != nil }) else { return false }
        return stored.outputs.allSatisfy { name, size in
            fileSize(directory.appendingPathComponent(name)) == size
        }
    }

    /// Records the recipe, the source fingerprint and every file the cache
    /// produced. Must be called once the cache directory is fully written —
    /// it fingerprints whatever it finds there.
    ///
    /// Exposed for tests; `prepare` is the only caller in the app.
    static func writeManifest(in directory: URL, for modelDirectory: URL) throws {
        var outputs: [String: Int64] = [:]
        let names = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        for name in names where name != manifestName {
            guard let size = fileSize(directory.appendingPathComponent(name)) else { continue }
            outputs[name] = size
        }
        let sources = try sourceFingerprints(for: modelDirectory)
        let manifest = Manifest(
            formatVersion: formatVersion,
            ortPackageVersion: ortPackageVersion,
            sources: sources,
            outputs: outputs
        )
        try JSONEncoder().encode(manifest).write(to: manifestURL(in: directory), options: .atomic)
    }

    /// Fingerprints of the downloaded graphs the cache was derived from.
    static func sourceFingerprints(for modelDirectory: URL) throws -> [String: SourceFingerprint] {
        var sources: [String: SourceFingerprint] = [:]
        for name in graphNames {
            let url = modelDirectory.appendingPathComponent(name)
            guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
                  let bytes = (attributes[.size] as? NSNumber)?.int64Value,
                  let modified = attributes[.modificationDate] as? Date
            else { throw ModelOptimizerError.missingSource(name) }
            sources[name] = SourceFingerprint(
                bytes: bytes, modifiedAt: modified.timeIntervalSince1970)
        }
        return sources
    }

    /// Builds the optimized cache if it is missing or stale, and returns the
    /// directory the translator should load its graphs from.
    ///
    /// Writes into a scratch directory and moves it into place, so a crash or
    /// kill mid-pass can never leave a half-written cache behind. On a thrown
    /// error the scratch is cleaned up here; if the process dies first, the
    /// next `prepare` clears it and `removeCache` can reclaim it meanwhile.
    @discardableResult
    static func prepare(
        modelDirectory: URL,
        env: ORTEnv,
        onProgress: (String) -> Void = { _ in }
    ) throws -> URL {
        let destination = optimizedDirectory(in: modelDirectory)
        if isCacheValid(in: modelDirectory) { return destination }

        let scratch = scratchDirectory(in: modelDirectory)
        try? FileManager.default.removeItem(at: scratch)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)

        var moved = false
        defer {
            // Half-written graphs are several hundred MB; never leave them.
            if !moved { try? FileManager.default.removeItem(at: scratch) }
        }

        for name in graphNames {
            onProgress(name)
            try autoreleasepool {
                try optimize(
                    source: modelDirectory.appendingPathComponent(name),
                    destination: scratch.appendingPathComponent(name),
                    env: env
                )
            }
        }

        // Written last: until the manifest lands the cache reads as invalid.
        try writeManifest(in: scratch, for: modelDirectory)

        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.moveItem(at: scratch, to: destination)
        moved = true
        excludeFromBackup(destination)
        return destination
    }

    /// Creates a throwaway session purely for its side effect: ORT writes the
    /// optimized graph plus its external initializer blob to disk. The session
    /// is never run and is released immediately.
    private static func optimize(source: URL, destination: URL, env: ORTEnv) throws {
        guard FileManager.default.fileExists(atPath: source.path) else {
            throw ModelOptimizerError.missingSource(source.lastPathComponent)
        }
        let externalDataName = destination.lastPathComponent + ".data"
        do {
            let options = try ORTSessionOptions()
            try options.setLogSeverityLevel(.warning)
            try options.setGraphOptimizationLevel(.all)
            try options.setOptimizedModelFilePath(destination.path)
            // Written relative to the optimized model's own directory.
            try options.addConfigEntry(
                withKey: "session.optimized_model_external_initializers_file_name",
                value: externalDataName)
            // Anything smaller than a page is not worth a mapping.
            try options.addConfigEntry(
                withKey: "session.optimized_model_external_initializers_min_size_in_bytes",
                value: "4096")
            // Pre-packed weights go to the same file, so they are mapped rather
            // than heap-allocated on every later session creation.
            try options.addConfigEntry(
                withKey: "session.save_external_prepacked_constant_initializers",
                value: "1")
            _ = try ORTSession(env: env, modelPath: source.path, sessionOptions: options)
        } catch {
            throw ModelOptimizerError.optimizationFailed(
                graph: source.lastPathComponent, underlying: "\(error)")
        }
    }

    private static func excludeFromBackup(_ url: URL) {
        var url = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        do {
            try url.setResourceValues(values)
        } catch {
            print("BentiSpike: failed to exclude optimized cache from backup: \(error)")
        }
    }
}
