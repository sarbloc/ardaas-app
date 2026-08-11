import CryptoKit
import Foundation

@testable import Ardaas

// Test doubles for the on-device translation plumbing (#42).
//
// The real artifact set is 359 MB of ONNX and cannot be in CI, so the download
// state machine is driven by a *synthetic* catalog: the same six-ish file
// shape, but a few bytes each, with SHA-256s computed from the payloads. That
// keeps the hash gate genuinely exercised rather than stubbed out.

enum TranslationFixtures {
    static func sha256(_ data: Data) -> String {
        CryptoKit.SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    struct SyntheticModel {
        let catalog: ModelCatalog
        /// File name → the bytes the fake fetcher serves.
        let payloads: [String: Data]

        func payload(_ name: String) -> Data {
            payloads[name] ?? Data()
        }
    }

    /// A catalog with the production shape (three graphs, two vocabulary files)
    /// and tiny, real, hash-matched payloads.
    static func syntheticModel(cacheEstimate: Int64 = 4096) -> SyntheticModel {
        let specs: [(name: String, kind: ModelCatalog.FileKind, body: String)] = [
            ("encoder_model.onnx", .graph, "encoder-graph-bytes"),
            ("decoder_model.onnx", .graph, "decoder-graph-bytes"),
            ("decoder_with_past_model.onnx", .graph, "decoder-with-past-graph-bytes"),
            ("tokenizer_src.json", .vocabulary, "{\"tokenizer\":true}"),
            ("dict.TGT.json", .vocabulary, "{\"dict\":true}"),
        ]

        var files: [ModelCatalog.File] = []
        var payloads: [String: Data] = [:]
        for spec in specs {
            let data = Data(spec.body.utf8)
            payloads[spec.name] = data
            files.append(
                ModelCatalog.File(
                    name: spec.name,
                    bytes: Int64(data.count),
                    sha256: sha256(data),
                    kind: spec.kind))
        }

        let catalog = ModelCatalog(
            files: files,
            repoBase: URL(string: "https://example.invalid/model/")!,
            directoryName: "SyntheticBentiModel",
            estimatedOptimizedCacheBytes: cacheEstimate)
        return SyntheticModel(catalog: catalog, payloads: payloads)
    }
}

/// Serves canned bytes instead of hitting the network, and records what it was
/// asked for so the cellular guard and resume behaviour can be asserted.
final class FakeModelFileFetcher: ModelFileFetching, @unchecked Sendable {
    struct Request: Equatable {
        let name: String
        let allowsCellularAccess: Bool
        let hadResumeData: Bool
    }

    private let lock = NSLock()
    private var payloads: [String: Data]
    private var _requests: [Request] = []

    /// Fail the named file once, then serve it normally on the next attempt.
    var failOnce: [String: Error] = [:]
    /// Resume token handed back with `failOnce` failures.
    var resumeTokenOnFailure: Data?
    /// Simulates "only a cellular interface is up": every fetch with
    /// `allowsCellularAccess == false` fails the way URLSession would.
    var requiresCellular = false
    /// Sleep before returning, so a test can cancel mid-transfer.
    var delay: Duration?

    init(payloads: [String: Data]) {
        self.payloads = payloads
    }

    var requests: [Request] {
        lock.lock()
        defer { lock.unlock() }
        return _requests
    }

    var requestCount: Int { requests.count }

    /// Replaces a payload with bytes that will not match the pinned hash.
    func corrupt(_ name: String) {
        setPayload(name, Data("corrupted-\(name)".utf8))
    }

    func setPayload(_ name: String, _ data: Data) {
        lock.lock()
        payloads[name] = data
        lock.unlock()
    }

    func fetch(
        from url: URL,
        resumeData: Data?,
        allowsCellularAccess: Bool,
        onProgress: @escaping @Sendable (Int64) -> Void,
        onResumeData: @escaping @Sendable (Data) -> Void
    ) async throws -> URL {
        let name = url.lastPathComponent
        lock.lock()
        _requests.append(
            Request(
                name: name,
                allowsCellularAccess: allowsCellularAccess,
                hadResumeData: resumeData != nil))
        let payload = payloads[name]
        let pendingFailure = failOnce.removeValue(forKey: name)
        let token = resumeTokenOnFailure
        let needsCellular = requiresCellular
        let sleepFor = delay
        lock.unlock()

        try Task.checkCancellation()
        if let sleepFor { try await Task.sleep(for: sleepFor) }

        if needsCellular && !allowsCellularAccess {
            throw NSError(
                domain: NSURLErrorDomain, code: NSURLErrorDataNotAllowed,
                userInfo: [NSLocalizedDescriptionKey: "cellular data is not allowed"])
        }
        if let pendingFailure {
            if let token { onResumeData(token) }
            throw pendingFailure
        }
        guard let payload else {
            throw TranslationError.download("no fixture for \(name)")
        }

        onProgress(Int64(payload.count) / 2)
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("fake-fetch-\(UUID().uuidString)")
        try payload.write(to: destination)
        onProgress(Int64(payload.count))
        return destination
    }
}

/// Stands in for the ONNX Runtime graph rewrite: writes a stub optimized graph
/// plus the external-initializer side-car the real pass produces, so the cache
/// manifest, the validation and the source-graph retirement are all exercised
/// for real.
final class FakeGraphOptimizer: GraphOptimizing, @unchecked Sendable {
    private let lock = NSLock()
    private var _optimized: [String] = []
    private var _verified: [String] = []

    /// Skip the side-car, reproducing an ORT build that cannot write external
    /// initializers. The cache must reject this rather than silently forfeit
    /// the whole memory saving.
    var writesSidecar = true
    var optimizeError: Error?
    /// Makes the self-sufficiency check fail, which must restore the originals.
    var verifyError: Error?

    var optimizedGraphs: [String] {
        lock.lock()
        defer { lock.unlock() }
        return _optimized
    }

    var verifiedGraphs: [String] {
        lock.lock()
        defer { lock.unlock() }
        return _verified
    }

    func optimize(source: URL, destination: URL, externalDataName: String) throws {
        lock.lock()
        _optimized.append(source.lastPathComponent)
        let failure = optimizeError
        let sidecar = writesSidecar
        lock.unlock()

        if let failure { throw failure }
        guard FileManager.default.fileExists(atPath: source.path) else {
            throw TranslationError.missingModelFile(source.lastPathComponent)
        }
        try Data(repeating: 0x42, count: 32).write(to: destination)
        if sidecar {
            try Data(repeating: 0x43, count: 128).write(
                to: destination.deletingLastPathComponent()
                    .appendingPathComponent(externalDataName))
        }
    }

    func verifyLoadable(graph: URL) throws {
        lock.lock()
        _verified.append(graph.lastPathComponent)
        let failure = verifyError
        lock.unlock()

        if let failure { throw failure }
        guard FileManager.default.fileExists(atPath: graph.path) else {
            throw TranslationError.missingModelFile(graph.lastPathComponent)
        }
        let sidecar = graph.deletingLastPathComponent()
            .appendingPathComponent(ModelOptimizer.externalDataName(for: graph.lastPathComponent))
        guard FileManager.default.fileExists(atPath: sidecar.path) else {
            throw TranslationError.missingModelFile(sidecar.lastPathComponent)
        }
    }
}

/// A translator that never touches ONNX, so the service's ready path can be
/// driven end to end in CI.
final class StubTranslator: BentiTranslating, @unchecked Sendable {
    private let lock = NSLock()
    private var _inputs: [String] = []
    var error: Error?

    var inputs: [String] {
        lock.lock()
        defer { lock.unlock() }
        return _inputs
    }

    func translate(_ english: String, collectMetrics: Bool) throws -> BentiTranslation {
        lock.lock()
        _inputs.append(english)
        let failure = error
        lock.unlock()
        if let failure { throw failure }
        return BentiTranslation(
            gurmukhi: "ਸਤਿਗੁਰੂ ਜੀ",
            rawDevanagari: "सतिगुरू जी",
            generatedTokenCount: 4,
            latencySeconds: 0.1,
            sessionLoadSeconds: 0.01,
            peakFootprintBytes: collectMetrics ? 1234 : 0,
            stages: [],
            isGurmukhiPure: true)
    }
}
