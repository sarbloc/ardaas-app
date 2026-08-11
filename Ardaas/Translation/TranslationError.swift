import Foundation

/// Every failure the on-device translation stack can surface, as one typed
/// enum. Equatable so `ModelState` is, which is what makes the download state
/// machine assertable in tests.
///
/// Nothing in this stack calls `fatalError` or force-unwraps on a path the
/// user can reach: the model files are downloaded at runtime from a third
/// party, so every one of these is a real, reachable outcome.
enum TranslationError: Error, Equatable, CustomStringConvertible, LocalizedError {
    /// A downloaded file did not match its pinned SHA-256. Never recovered
    /// from silently — the file is discarded and the install fails.
    case hashMismatch(file: String, expected: String, actual: String)
    /// Transport-level failure (HTTP status, connectivity, timeout).
    case download(String)
    /// The download needs the network but cellular is not permitted and no
    /// other interface is available.
    case cellularNotAllowed
    /// The user cancelled.
    case cancelled
    /// `os_proc_available_memory()` reported less headroom than the pipeline's
    /// measured peak needs. Refused rather than risking a jetsam kill.
    case insufficientMemory(availableBytes: Int64, requiredBytes: Int64)
    /// Not enough free space for the download plus the optimized cache, which
    /// coexist until the originals are dropped.
    case insufficientDisk(availableBytes: Int64, requiredBytes: Int64)
    /// The one-time graph optimization failed, or produced a cache that could
    /// not be loaded back.
    case optimizationFailed(graph: String, detail: String)
    /// A file the pipeline needs is not on disk.
    case missingModelFile(String)
    /// A file is on disk but is not what it claims to be.
    case malformedModelFile(name: String, detail: String)
    /// This build was produced without the ONNX Runtime dependency (the
    /// `BENTI_ONNX` compilation condition is off), so no inference is possible.
    case engineUnavailable
    /// `translate` was called before the model reached `.ready`.
    case notReady
    /// Source text exceeds the model's positional limit.
    case inputTooLong(tokens: Int, limit: Int)
    /// The ONNX session could not be created or run.
    case sessionFailure(String)
    /// The decode loop produced something unusable.
    case decodeFailure(String)
    /// Filesystem failure, carried as text because `Error` is not Equatable.
    case fileSystem(String)

    var description: String {
        switch self {
        case .hashMismatch(let file, let expected, let actual):
            return "SHA-256 mismatch for \(file): expected \(expected), got \(actual)"
        case .download(let detail):
            return "Download failed: \(detail)"
        case .cellularNotAllowed:
            return "Wi-Fi is required for this download."
        case .cancelled:
            return "Cancelled."
        case .insufficientMemory(let available, let required):
            return "Not enough free memory: \(available / 1_048_576) MB available, "
                + "\(required / 1_048_576) MB needed."
        case .insufficientDisk(let available, let required):
            return "Not enough free space: \(available / 1_000_000) MB available, "
                + "\(required / 1_000_000) MB needed."
        case .optimizationFailed(let graph, let detail):
            return "Failed to prepare \(graph): \(detail)"
        case .missingModelFile(let name):
            return "Missing model file: \(name)"
        case .malformedModelFile(let name, let detail):
            return "Malformed \(name): \(detail)"
        case .engineUnavailable:
            return "This build was made without the on-device translation engine."
        case .notReady:
            return "The translation model is not ready."
        case .inputTooLong(let tokens, let limit):
            return "Text is too long: \(tokens) tokens, limit \(limit)."
        case .sessionFailure(let detail):
            return "ONNX session failure: \(detail)"
        case .decodeFailure(let detail):
            return "Decode failure: \(detail)"
        case .fileSystem(let detail):
            return "Filesystem error: \(detail)"
        }
    }

    var errorDescription: String? { description }

    /// Maps an arbitrary thrown error onto this enum without losing the text.
    static func wrap(_ error: Error) -> TranslationError {
        if let typed = error as? TranslationError { return typed }
        if error is CancellationError { return .cancelled }
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain {
            switch nsError.code {
            case NSURLErrorDataNotAllowed:
                return .cellularNotAllowed
            case NSURLErrorCancelled:
                return .cancelled
            default:
                return .download(nsError.localizedDescription)
            }
        }
        return .fileSystem(nsError.localizedDescription)
    }
}
