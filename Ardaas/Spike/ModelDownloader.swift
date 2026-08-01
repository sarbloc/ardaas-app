import CryptoKit
import Foundation

// Spike (#35): first-use download of the 6 model/tokenizer files from
// Hugging Face into Application Support, with streaming SHA-256 verification
// against the hashes pinned in the pipeline spec. Fails loudly on mismatch.
//
// Deliberately not productized: no cellular guard, no background-session
// survival across app kills, minimal retry UX (resume data is reused when
// the transport hands one back, otherwise the file restarts).

@MainActor
final class ModelDownloader: NSObject, ObservableObject {
    struct SpecFile: Sendable {
        let name: String
        let bytes: Int64
        let sha256: String
    }

    enum State: Equatable {
        case idle
        case downloading(fileName: String, fileIndex: Int, fileCount: Int)
        case verifying(fileName: String)
        case ready
        case failed(String)
    }

    /// File list per spec section 6 (repo main @ 2026-08-01).
    static let files: [SpecFile] = [
        SpecFile(name: "encoder_model.onnx", bytes: 75_979_645,
                 sha256: "65726fc70e7fe73bdec5ab922caf16fe92285efe2329965894ec8143a87153f0"),
        SpecFile(name: "decoder_model.onnx", bytes: 142_868_773,
                 sha256: "6925537cbce0576431343b33d0239b7ad442c3dd86aefe0521d8c71d60941c41"),
        SpecFile(name: "decoder_with_past_model.onnx", bytes: 132_902_339,
                 sha256: "73d114343b59fcaa53f0909baf9ab5f13e11c4b0ce98d48404dd8b72d18ebfa1"),
        SpecFile(name: "tokenizer_src.json", bytes: 3_380_130,
                 sha256: "913a7fe797e866de46c805d237846ff6433387e77117c377fddeb28b221808da"),
        SpecFile(name: "tokenizer_meta.json", bytes: 70,
                 sha256: "9f18574a040d815695f98ac53e7d8ca18ae38b90a339eb59301690fccfc62437"),
        SpecFile(name: "dict.TGT.json", bytes: 3_390_440,
                 sha256: "f7817850c9e4b99c59fad57d0611c7720f1921f215e6f247cf25d52eff7f1146"),
    ]

    static let repoBase = URL(
        string: "https://huggingface.co/naklitechie/indictrans2-en-indic-dist-200M-ONNX-int8/resolve/main/")!

    static var modelDirectory: URL {
        // Application Support is the right home for re-downloadable data the
        // user should not see; created on demand below.
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("BentiSpike", isDirectory: true)
    }

    @Published private(set) var state: State = .idle
    /// 0...1 across the total spec byte count.
    @Published private(set) var overallProgress: Double = 0

    static var totalBytes: Int64 { files.reduce(0) { $0 + $1.bytes } }

    private var resumeData: [String: Data] = [:]
    private var isRunning = false

    override init() {
        super.init()
        refreshState()
    }

    /// Cheap readiness check: every file present with the spec's exact size.
    /// Full hashes are only verified right after download (hashing 350 MB on
    /// every launch is not worth it for a spike).
    func refreshState() {
        let allPresent = Self.files.allSatisfy { file in
            let path = Self.modelDirectory.appendingPathComponent(file.name).path
            let attrs = try? FileManager.default.attributesOfItem(atPath: path)
            return (attrs?[.size] as? NSNumber)?.int64Value == file.bytes
        }
        if allPresent {
            state = .ready
            overallProgress = 1
        }
    }

    func download() {
        guard !isRunning, state != .ready else { return }
        isRunning = true
        state = .idle
        Task {
            await runDownload()
            isRunning = false
        }
    }

    private func runDownload() async {
        do {
            try FileManager.default.createDirectory(
                at: Self.modelDirectory, withIntermediateDirectories: true)

            var completedBytes: Int64 = 0
            let totalBytes = Self.totalBytes

            for (index, file) in Self.files.enumerated() {
                let destination = Self.modelDirectory.appendingPathComponent(file.name)

                if fileMatchesSpecSize(destination, file: file) {
                    completedBytes += file.bytes
                    overallProgress = Double(completedBytes) / Double(totalBytes)
                    continue
                }

                state = .downloading(
                    fileName: file.name, fileIndex: index + 1, fileCount: Self.files.count)

                let downloader = FileDownloader()
                let base = completedBytes
                let tempURL = try await downloader.download(
                    url: Self.repoBase.appendingPathComponent(file.name),
                    resumeData: resumeData.removeValue(forKey: file.name),
                    onProgress: { [weak self] written in
                        Task { @MainActor [weak self] in
                            self?.overallProgress =
                                Double(base + min(written, file.bytes)) / Double(totalBytes)
                        }
                    },
                    onResumeData: { [weak self] data in
                        Task { @MainActor [weak self] in
                            self?.resumeData[file.name] = data
                        }
                    }
                )

                state = .verifying(fileName: file.name)
                let digest = try await Self.sha256(of: tempURL)
                guard digest == file.sha256 else {
                    try? FileManager.default.removeItem(at: tempURL)
                    throw ModelDownloadError.hashMismatch(
                        file: file.name, expected: file.sha256, actual: digest)
                }

                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.moveItem(at: tempURL, to: destination)
                excludeFromBackup(destination)

                completedBytes += file.bytes
                overallProgress = Double(completedBytes) / Double(totalBytes)
            }

            state = .ready
        } catch {
            // Surface the failure loudly; resume data (if any) was captured
            // for the next attempt.
            state = .failed("\(error)")
        }
    }

    private func fileMatchesSpecSize(_ url: URL, file: SpecFile) -> Bool {
        let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attrs?[.size] as? NSNumber)?.int64Value == file.bytes
    }

    private func excludeFromBackup(_ url: URL) {
        var url = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        do {
            try url.setResourceValues(values)
        } catch {
            // Non-fatal, but don't hide it.
            print("BentiSpike: failed to exclude \(url.lastPathComponent) from backup: \(error)")
        }
    }

    /// Streaming SHA-256 (1 MiB chunks) off the main actor.
    nonisolated private static func sha256(of url: URL) async throws -> String {
        let task = Task.detached(priority: .utility) { () throws -> String in
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            var hasher = SHA256()
            while true {
                guard let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty else { break }
                hasher.update(data: chunk)
            }
            return hasher.finalize().map { String(format: "%02x", $0) }.joined()
        }
        return try await task.value
    }
}

enum ModelDownloadError: Error, CustomStringConvertible {
    case hashMismatch(file: String, expected: String, actual: String)
    case transport(String)

    var description: String {
        switch self {
        case .hashMismatch(let file, let expected, let actual):
            return "SHA-256 mismatch for \(file): expected \(expected), got \(actual)"
        case .transport(let detail):
            return "Download failed: \(detail)"
        }
    }
}

/// One-shot download of a single URL to a temporary file, with byte-level
/// progress. Uses the delegate API because async `URLSession.download(for:)`
/// exposes no incremental progress.
private final class FileDownloader: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private var continuation: CheckedContinuation<URL, Error>?
    private var onProgress: ((Int64) -> Void)?
    private var onResumeData: ((Data) -> Void)?
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForResource = 60 * 60  // 350 MB on slow links
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    func download(
        url: URL,
        resumeData: Data?,
        onProgress: @escaping (Int64) -> Void,
        onResumeData: @escaping (Data) -> Void
    ) async throws -> URL {
        self.onProgress = onProgress
        self.onResumeData = onResumeData
        defer { session.finishTasksAndInvalidate() }
        return try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            let task: URLSessionDownloadTask
            if let resumeData {
                task = session.downloadTask(withResumeData: resumeData)
            } else {
                task = session.downloadTask(with: url)
            }
            task.resume()
        }
    }

    func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        onProgress?(totalBytesWritten)
    }

    func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        // The system deletes `location` after this method returns — move it
        // synchronously to our own temp path first.
        let holding = FileManager.default.temporaryDirectory
            .appendingPathComponent("benti-\(UUID().uuidString).download")
        do {
            if let http = downloadTask.response as? HTTPURLResponse,
               !(200...299).contains(http.statusCode) {
                throw ModelDownloadError.transport("HTTP \(http.statusCode)")
            }
            try FileManager.default.moveItem(at: location, to: holding)
            continuation?.resume(returning: holding)
        } catch {
            continuation?.resume(throwing: error)
        }
        continuation = nil
    }

    func urlSession(
        _ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?
    ) {
        guard let error else { return }  // success path resumed above
        if let resumeData = (error as NSError)
            .userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
            onResumeData?(resumeData)
        }
        continuation?.resume(throwing: ModelDownloadError.transport(error.localizedDescription))
        continuation = nil
    }
}
