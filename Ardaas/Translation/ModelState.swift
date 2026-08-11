import Foundation

/// The lifecycle of the on-device model, as the UI sees it.
///
/// Deliberately the whole public surface: `#44` renders exactly these five
/// cases and never reaches behind them for file paths or task handles.
enum ModelState: Equatable {
    /// Nothing (or only a resumable partial download) is on disk. This is the
    /// state a fresh install starts in and the state `deleteModel()` returns
    /// to; no bytes are fetched until `download()` is called explicitly.
    case notDownloaded
    /// Fetching artifacts. `progress` is 0...1 over
    /// `ModelCatalog.downloadBytes` — the download only, not the optimization
    /// that follows it.
    case downloading(progress: Double)
    /// One-time on-device graph optimization (see docs/translation/MEMORY.md).
    /// No progress fraction: it is three opaque ORT rewrites, ~2 s total.
    case optimizing
    /// Verified, optimized and loadable. `translate` only works here.
    case ready
    /// Terminal until the caller retries `download()` or `deleteModel()`.
    case failed(TranslationError)

    var isReady: Bool { self == .ready }

    /// True while an install is in flight, so the UI can offer Cancel and
    /// suppress a second Download tap.
    var isWorking: Bool {
        switch self {
        case .downloading, .optimizing: return true
        case .notDownloaded, .ready, .failed: return false
        }
    }

    var error: TranslationError? {
        if case .failed(let error) = self { return error }
        return nil
    }
}
