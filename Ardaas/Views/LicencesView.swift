import SwiftUI

/// Open-source licences (#132): the full notice of every third-party
/// component in `OpenSourceLicence.all`, read from the app bundle. Pushed
/// from About & Sources. A text that fails to load says so in its section
/// rather than leaving it blank. A component's own third-party notices
/// (ONNX Runtime's, #139) are a pushed `ThirdPartyNoticesView`. iOS ships no
/// other third-party code: project.yml declares no packages, and the ONNX
/// overlay (project-onnx.yml) adds only ONNX Runtime.
struct LicencesView: View {
    /// Loaded on first appearance, not in `init`: a `NavigationLink` builds
    /// its destination every time the parent's body runs.
    @State private var texts: [String: Result<String, Error>] = [:]

    var body: some View {
        List {
            ForEach(OpenSourceLicence.all) { licence in
                Section(licence.component) {
                    Text(licence.source)
                        .font(.footnote)
                        .foregroundStyle(Theme.parchment)
                    licenceText(texts[licence.id])
                    if licence.thirdPartyNoticesResource != nil {
                        NavigationLink("Third-party components in \(licence.component)") {
                            ThirdPartyNoticesView(licence: licence)
                        }
                        .foregroundStyle(Theme.parchment)
                    }
                }
                .listRowBackground(Theme.raisedFill)
            }
        }
        .themedScreen()
        .navigationTitle("Open-source licences")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard texts.isEmpty else { return }
            for licence in OpenSourceLicence.all {
                texts[licence.id] = Result { try OpenSourceLicence.reflow(licence.loadText()) }
            }
        }
    }

    @ViewBuilder
    private func licenceText(_ result: Result<String, Error>?) -> some View {
        switch result {
        case .success(let text):
            Text(text)
                .font(.footnote)
                .foregroundStyle(Theme.mist)
                .textSelection(.enabled)
        case .failure(let error):
            Text("Could not load this licence: \(String(describing: error))")
                .font(.footnote)
                .foregroundStyle(Theme.mist)
        case nil:
            ProgressView()
        }
    }
}

/// A component's own third-party notices (#139) — for ONNX Runtime, its
/// ThirdPartyNotices.txt: ~300 KB across dozens of components. Pushed rather
/// than inlined, and one lazily built row per component, so the licences
/// screen never lays out that much text up front.
struct ThirdPartyNoticesView: View {
    let licence: OpenSourceLicence

    /// Loaded off the main thread on first appearance: splitting and
    /// reflowing the whole file is too much work to do while pushing.
    @State private var sections: Result<[String], Error>?

    var body: some View {
        List {
            Section(licence.component) {
                switch sections {
                case .success(let notices):
                    ForEach(notices.indices, id: \.self) { index in
                        Text(notices[index])
                            .font(.footnote)
                            .foregroundStyle(Theme.mist)
                            .textSelection(.enabled)
                    }
                case .failure(let error):
                    Text("Could not load these notices: \(String(describing: error))")
                        .font(.footnote)
                        .foregroundStyle(Theme.mist)
                case nil:
                    ProgressView()
                }
            }
            .listRowBackground(Theme.raisedFill)
        }
        .themedScreen()
        .navigationTitle("Third-party components")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard sections == nil else { return }
            let licence = licence
            sections = await Task.detached(priority: .userInitiated) { () -> Result<[String], Error> in
                Result<[String], Error> { () throws -> [String] in
                    let raw = try licence.loadThirdPartyNotices() ?? ""
                    return OpenSourceLicence.noticeSections(raw).map { OpenSourceLicence.reflow($0) }
                }
            }.value
        }
    }
}

#Preview {
    NavigationStack {
        LicencesView()
    }
    .preferredColorScheme(.dark)
}
