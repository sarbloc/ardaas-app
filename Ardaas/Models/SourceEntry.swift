import Foundation

/// How one entry of a variant's `sources` array is shown on About & Sources.
/// Same rule as Android's `SourceEntry` (#88).
enum SourceEntry: Equatable {
    /// A web address: shown as its host, opening `url` when tapped.
    case link(url: URL, host: String)
    /// A description of a source (a book, an app, a borrowed layer): shown
    /// as written.
    case prose(String)

    /// Classifies `source`. Only a string that is wholly an http(s) URL with
    /// a host is a link, so a description that merely *begins* with an
    /// address ("https://… and /383 — BaniDB …") is shown in full as prose
    /// rather than truncated to a host and a broken link.
    ///
    /// `encodingInvalidCharacters: false` is what makes this strict: since
    /// iOS 17 plain `URL(string:)` percent-encodes spaces and the like, so it
    /// accepts almost any prose as a URL (#108).
    init(_ source: String) {
        guard let url = URL(string: source, encodingInvalidCharacters: false),
              let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http",
              let host = url.host(), !host.isEmpty
        else {
            self = .prose(source)
            return
        }
        self = .link(url: url, host: host)
    }
}
