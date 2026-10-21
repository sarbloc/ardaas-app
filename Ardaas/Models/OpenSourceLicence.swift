import Foundation

/// A third-party component whose licence notice ships with the app (#132).
///
/// MIT requires the copyright and permission notice to travel with every
/// copy, so the full texts are bundled — fetched verbatim from each project
/// at the version the app uses — and shown on the Open-source licences
/// screen. The files live in `Ardaas/Licences/` (not `Ardaas/Resources/`:
/// they are not scripture) and Android packages them from the same place.
/// Keep this list in step with Android's `OpenSourceLicence.all`.
struct OpenSourceLicence: Identifiable, Equatable {
    /// Bundle resource name of the verbatim licence text (`.txt`).
    let resource: String
    /// What the component is, as shown in the screen's section header.
    let component: String
    /// Who publishes it and exactly which version is used.
    let source: String
    /// Bundle resource name (`.txt`) of the notices for third-party code the
    /// component itself includes (#139), verbatim from the project; nil if
    /// none.
    var thirdPartyNoticesResource: String? = nil

    var id: String { resource }

    /// The bundle this code is compiled into (the app target), so lookup
    /// works the same in the app and in hosted tests.
    static var appBundle: Bundle { Bundle(for: LicenceBundleToken.self) }

    static let all: [OpenSourceLicence] = [
        OpenSourceLicence(
            resource: "licence-indictrans2",
            component: "IndicTrans2",
            source: "AI4Bharat — github.com/AI4Bharat/IndicTrans2"
        ),
        OpenSourceLicence(
            resource: "licence-indictrans2-onnx-int8",
            component: "IndicTrans2 int8 ONNX export",
            source: "naklitechie/indictrans2-en-indic-dist-200M-ONNX-int8 on Hugging Face, revision e88c4f8"
        ),
        OpenSourceLicence(
            resource: "licence-onnxruntime",
            component: "ONNX Runtime 1.24.2",
            source: "Microsoft — github.com/microsoft/onnxruntime",
            // ThirdPartyNotices.txt at tag v1.24.2: protobuf, abseil, Eigen
            // and the rest statically linked into its binaries.
            thirdPartyNoticesResource: "licence-onnxruntime-third-party-notices"
        ),
    ]

    /// The licence text exactly as bundled. Throws if the resource is
    /// missing — a build defect, not a runtime state.
    func loadText(from bundle: Bundle = OpenSourceLicence.appBundle) throws -> String {
        try Self.loadResource(resource, from: bundle)
    }

    /// The component's third-party notices exactly as bundled, or nil if it
    /// has none. Throws if the resource is missing, as `loadText` does.
    func loadThirdPartyNotices(from bundle: Bundle = OpenSourceLicence.appBundle) throws -> String? {
        guard let name = thirdPartyNoticesResource else { return nil }
        return try Self.loadResource(name, from: bundle)
    }

    /// The bundled `.txt` resource `name` as UTF-8 text.
    static func loadResource(_ name: String, from bundle: Bundle) throws -> String {
        guard let url = bundle.url(forResource: name, withExtension: "txt") else {
            throw OpenSourceLicenceError.resourceMissing(name)
        }
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// A third-party notices file split into its components' notices. The
    /// files separate components with a line of underscores; those lines are
    /// dropped, and every other line is kept in order. Port of Android's
    /// `OpenSourceLicence.noticeSections`.
    static func noticeSections(_ raw: String) -> [String] {
        let lines = raw.replacingOccurrences(of: "\r\n", with: "\n")
            .split(separator: "\n", omittingEmptySubsequences: false)
        var sections: [String] = []
        var current: [Substring] = []
        for line in lines {
            if isNoticeSeparator(line) {
                appendSection(current, to: &sections)
                current = []
            } else {
                current.append(line)
            }
        }
        appendSection(current, to: &sections)
        return sections
    }

    private static func appendSection(_ lines: [Substring], to sections: inout [String]) {
        let text = lines.joined(separator: "\n").trimmingCharacters(in: .newlines)
        if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            sections.append(text)
        }
    }

    private static func isNoticeSeparator(_ line: Substring) -> Bool {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        return trimmed.count >= 5 && trimmed.allSatisfy { $0 == "_" }
    }

    /// `raw` as readable paragraphs for a phone-width screen. The bundled
    /// texts are hard-wrapped at ~80 columns (and one is indented), which
    /// breaks raggedly when re-wrapped narrower; this joins each paragraph's
    /// lines with single spaces. Words and their order are untouched — only
    /// line breaks and indentation change.
    static func reflow(_ raw: String) -> String {
        // "\r\n" is a single Character in Swift, so normalise before
        // splitting on "\n".
        let lines = raw.replacingOccurrences(of: "\r\n", with: "\n")
            .split(separator: "\n", omittingEmptySubsequences: false)
        var paragraphs: [String] = []
        var current: [String] = []
        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty {
                if !current.isEmpty {
                    paragraphs.append(current.joined(separator: " "))
                    current = []
                }
            } else {
                current.append(trimmed)
            }
        }
        if !current.isEmpty {
            paragraphs.append(current.joined(separator: " "))
        }
        return paragraphs.joined(separator: "\n\n")
    }
}

enum OpenSourceLicenceError: Error, Equatable {
    case resourceMissing(String)
}

private final class LicenceBundleToken {}
