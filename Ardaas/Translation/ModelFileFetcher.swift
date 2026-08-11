import Foundation

/// One artifact download, abstracted so the installer's state machine can be
/// tested without a network (see `FakeModelFileFetcher` in the test target).
protocol ModelFileFetching: Sendable {
    /// Downloads `url` to a caller-owned temporary file.
    ///
    /// - Parameters:
    ///   - resumeData: transport resume token from a previous failed or
    ///     cancelled attempt, when one was produced.
    ///   - allowsCellularAccess: false means Wi-Fi (or wired) only. When no
    ///     permitted interface is available the call fails rather than falling
    ///     back to the user's data plan.
    ///   - onProgress: total bytes written so far for this file.
    ///   - onResumeData: called when a failure produced a resume token.
    /// - Returns: a temporary file URL the caller is responsible for moving or
    ///   deleting.
    func fetch(
        from url: URL,
        resumeData: Data?,
        allowsCellularAccess: Bool,
        onProgress: @escaping @Sendable (Int64) -> Void,
        onResumeData: @escaping @Sendable (Data) -> Void
    ) async throws -> URL
}

/// The production fetcher.
///
/// Uses the `URLSessionDownloadDelegate` API rather than
/// `URLSession.download(for:)` because the async form exposes no incremental
/// progress, and a 359 MB download with no progress bar is not shippable.
struct URLSessionModelFetcher: ModelFileFetching {
    /// 350 MB on a slow link.
    var resourceTimeout: TimeInterval = 60 * 60

    func fetch(
        from url: URL,
        resumeData: Data?,
        allowsCellularAccess: Bool,
        onProgress: @escaping @Sendable (Int64) -> Void,
        onResumeData: @escaping @Sendable (Data) -> Void
    ) async throws -> URL {
        let operation = DownloadOperation(
            onProgress: onProgress, onResumeData: onResumeData,
            resourceTimeout: resourceTimeout)
        return try await operation.run(
            url: url, resumeData: resumeData, allowsCellularAccess: allowsCellularAccess)
    }
}

/// A single download, owning its `URLSession` for the duration.
///
/// Resumes its continuation exactly once, under a lock, from whichever of the
/// three paths gets there first (success, transport failure, cancellation).
private final class DownloadOperation: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<URL, Error>?
    private var task: URLSessionDownloadTask?
    private var session: URLSession?
    private var didResume = false
    private var cancelRequested = false

    private let onProgress: @Sendable (Int64) -> Void
    private let onResumeData: @Sendable (Data) -> Void
    private let resourceTimeout: TimeInterval

    init(
        onProgress: @escaping @Sendable (Int64) -> Void,
        onResumeData: @escaping @Sendable (Data) -> Void,
        resourceTimeout: TimeInterval
    ) {
        self.onProgress = onProgress
        self.onResumeData = onResumeData
        self.resourceTimeout = resourceTimeout
    }

    func run(url: URL, resumeData: Data?, allowsCellularAccess: Bool) async throws -> URL {
        try Task.checkCancellation()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                // Cancellation can land before the continuation exists; without
                // this the task would start and never be cancelled.
                if cancelRequested {
                    didResume = true
                    lock.unlock()
                    continuation.resume(throwing: CancellationError())
                    return
                }
                self.continuation = continuation

                let configuration = URLSessionConfiguration.default
                configuration.timeoutIntervalForResource = resourceTimeout
                // The cellular guard. `expensive` covers a tethered hotspot and
                // `constrained` covers Low Data Mode — both are the user paying
                // for bytes, which is the thing being guarded.
                configuration.allowsCellularAccess = allowsCellularAccess
                configuration.allowsExpensiveNetworkAccess = allowsCellularAccess
                configuration.allowsConstrainedNetworkAccess = allowsCellularAccess
                // Fail fast with NSURLErrorDataNotAllowed instead of parking the
                // task indefinitely when only cellular is up.
                configuration.waitsForConnectivity = false

                let session = URLSession(
                    configuration: configuration, delegate: self, delegateQueue: nil)
                self.session = session
                let task: URLSessionDownloadTask
                if let resumeData {
                    task = session.downloadTask(withResumeData: resumeData)
                } else {
                    task = session.downloadTask(with: url)
                }
                self.task = task
                lock.unlock()
                task.resume()
            }
        } onCancel: {
            self.requestCancel()
        }
    }

    private func requestCancel() {
        lock.lock()
        cancelRequested = true
        let task = self.task
        lock.unlock()
        guard let task else { return }
        task.cancel { [onResumeData] data in
            if let data { onResumeData(data) }
        }
        // `didCompleteWithError` follows with NSURLErrorCancelled and resumes
        // the continuation.
    }

    private func finish(_ result: Result<URL, Error>) {
        lock.lock()
        guard !didResume, let continuation = self.continuation else {
            lock.unlock()
            return
        }
        didResume = true
        self.continuation = nil
        let session = self.session
        self.session = nil
        self.task = nil
        lock.unlock()
        session?.finishTasksAndInvalidate()
        continuation.resume(with: result)
    }

    // MARK: - URLSessionDownloadDelegate

    func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        onProgress(totalBytesWritten)
    }

    func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        // The system deletes `location` as soon as this method returns, so the
        // move has to happen synchronously here.
        do {
            if let response = downloadTask.response as? HTTPURLResponse,
               !(200...299).contains(response.statusCode) {
                // A non-2xx body is an error page, not model bytes.
                throw TranslationError.download("HTTP \(response.statusCode)")
            }
            let holding = FileManager.default.temporaryDirectory
                .appendingPathComponent("benti-\(UUID().uuidString).download")
            try FileManager.default.moveItem(at: location, to: holding)
            finish(.success(holding))
        } catch {
            finish(.failure(error))
        }
    }

    func urlSession(
        _ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?
    ) {
        guard let error else { return }  // the success path resumed above
        let nsError = error as NSError
        if let data = nsError.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
            onResumeData(data)
        }
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
            finish(.failure(CancellationError()))
        } else {
            finish(.failure(TranslationError.wrap(error)))
        }
    }
}
