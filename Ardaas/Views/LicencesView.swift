import SwiftUI

/// Open-source licences (#132): the full notice of every third-party
/// component in `OpenSourceLicence.all`, read from the app bundle. Pushed
/// from About & Sources. A text that fails to load says so in its section
/// rather than leaving it blank.
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

#Preview {
    NavigationStack {
        LicencesView()
    }
    .preferredColorScheme(.dark)
}
