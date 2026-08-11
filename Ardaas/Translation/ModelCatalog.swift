import CryptoKit
import Foundation

/// The pinned set of artifacts the on-device English → Gurmukhi model needs.
///
/// Every entry is pinned by exact byte count *and* SHA-256, so the download is
/// byte-accurate before it starts (the size disclosure the user is shown is a
/// sum of these, not an estimate) and verifiable after it finishes.
///
/// A value rather than a namespace of constants so the installer's state
/// machine can be driven by a tiny synthetic catalog in tests. Production code
/// always uses `.production`.
struct ModelCatalog: Sendable, Equatable {
    enum FileKind: Sendable, Equatable {
        /// An ONNX inference graph. Consumed once by the optimizer and deleted
        /// afterwards — the optimized cache is self-sufficient.
        case graph
        /// Tokenizer/vocabulary JSON. Needed at every translation, so it stays
        /// on disk for the lifetime of the install.
        case vocabulary
    }

    struct File: Sendable, Equatable {
        let name: String
        let bytes: Int64
        let sha256: String
        let kind: FileKind

        init(name: String, bytes: Int64, sha256: String, kind: FileKind) {
            self.name = name
            self.bytes = bytes
            self.sha256 = sha256
            self.kind = kind
        }
    }

    let files: [File]
    let repoBase: URL
    /// Subdirectory of Application Support that holds the install.
    let directoryName: String
    /// Size of the ORT-optimized graph cache (optimized graphs plus their
    /// external-initializer side-cars). An estimate rather than a pinned number
    /// because ORT writes it and the exact size moves with the runtime version.
    /// Only ever used for disclosure and for the free-space check — never for
    /// validation, which goes by the manifest.
    let estimatedOptimizedCacheBytes: Int64

    // MARK: - Production

    /// Artifacts from `naklitechie/indictrans2-en-indic-dist-200M-ONNX-int8`
    /// (IndicTrans2 en-indic dist-200M, int8 ONNX), repo main @ 2026-08-01.
    static let production = ModelCatalog(
        files: [
            File(name: "encoder_model.onnx", bytes: 75_979_645,
                 sha256: "65726fc70e7fe73bdec5ab922caf16fe92285efe2329965894ec8143a87153f0",
                 kind: .graph),
            File(name: "decoder_model.onnx", bytes: 142_868_773,
                 sha256: "6925537cbce0576431343b33d0239b7ad442c3dd86aefe0521d8c71d60941c41",
                 kind: .graph),
            File(name: "decoder_with_past_model.onnx", bytes: 132_902_339,
                 sha256: "73d114343b59fcaa53f0909baf9ab5f13e11c4b0ce98d48404dd8b72d18ebfa1",
                 kind: .graph),
            File(name: "tokenizer_src.json", bytes: 3_380_130,
                 sha256: "913a7fe797e866de46c805d237846ff6433387e77117c377fddeb28b221808da",
                 kind: .vocabulary),
            File(name: "tokenizer_meta.json", bytes: 70,
                 sha256: "9f18574a040d815695f98ac53e7d8ca18ae38b90a339eb59301690fccfc62437",
                 kind: .vocabulary),
            File(name: "dict.TGT.json", bytes: 3_390_440,
                 sha256: "f7817850c9e4b99c59fad57d0611c7720f1921f215e6f247cf25d52eff7f1146",
                 kind: .vocabulary),
        ],
        repoBase: URL(
            string: "https://huggingface.co/naklitechie/"
                + "indictrans2-en-indic-dist-200M-ONNX-int8/resolve/main/")!,
        directoryName: "BentiModel",
        // ~639 MiB, measured on the reference host.
        estimatedOptimizedCacheBytes: 670_000_000
    )

    // MARK: - Slices

    /// The inference graphs, in load order.
    var graphs: [File] { files.filter { $0.kind == .graph } }
    var vocabulary: [File] { files.filter { $0.kind == .vocabulary } }
    var graphNames: [String] { graphs.map(\.name) }

    func url(for file: File) -> URL { repoBase.appendingPathComponent(file.name) }

    func file(named name: String) -> File? { files.first { $0.name == name } }

    // MARK: - Size disclosure

    /// Exact bytes transferred over the network for a full install.
    /// Production: 358,521,397 B ≈ 358.5 MB.
    var downloadBytes: Int64 { files.reduce(0) { $0 + $1.bytes } }

    /// Bytes of ONNX graph, deleted once the optimized cache is built and
    /// verified. Production: 351,750,757 B ≈ 351.8 MB.
    var graphBytes: Int64 { graphs.reduce(0) { $0 + $1.bytes } }

    /// Bytes that stay resident forever. Production: 6,770,640 B ≈ 6.8 MB.
    var vocabularyBytes: Int64 { vocabulary.reduce(0) { $0 + $1.bytes } }

    /// Worst-case free space needed to complete an install: the download and
    /// the cache coexist until the graphs are deleted. Production: ≈ 1.03 GB.
    var estimatedPeakDiskBytes: Int64 { downloadBytes + estimatedOptimizedCacheBytes }

    /// Steady-state disk cost once the originals have been dropped.
    /// Production: ≈ 677 MB.
    var estimatedInstalledBytes: Int64 { vocabularyBytes + estimatedOptimizedCacheBytes }

    // MARK: - Revision

    /// Identity of this artifact set: a digest over every pinned name, size and
    /// hash. Any change to the catalog changes it, which invalidates an
    /// optimized cache built from the previous artifacts.
    ///
    /// This is what lets the original graphs be deleted after optimization: the
    /// cache no longer has to fingerprint files that are gone, because the
    /// downloads were SHA-256 verified against exactly these constants, so a
    /// matching revision means the cache came from exactly those bytes.
    var revision: String {
        let manifest = files
            .map { "\($0.name):\($0.bytes):\($0.sha256)" }
            .joined(separator: ";")
        return SHA256.hash(data: Data(manifest.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }

    // MARK: - Location

    /// Application Support is the right home for large, re-downloadable data
    /// the user should not see in Files. Excluded from backup on write.
    func directory(fileManager: FileManager = .default) -> URL {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent(directoryName, isDirectory: true)
    }
}
