import SwiftUI

/// Info page: cites the sources behind each bundled Gurbani text, states
/// the accuracy policy, and shows the app version. Presented as a sheet
/// from the Home screen.
struct SourcesView: View {
    @Environment(\.dismiss) private var dismiss

    /// Loaded once; a broken bundle shows the failure rather than hiding it.
    @State private var loadResult: Result<ArdaasContent, Error>?

    var body: some View {
        NavigationStack {
            List {
                textSection
                accuracySection
                aboutSection
            }
            .themedScreen()
            .navigationTitle("About & Sources")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
        .onAppear {
            if loadResult == nil {
                loadResult = Result { try ArdaasContent.loadBundled() }
            }
        }
    }

    @ViewBuilder
    private var textSection: some View {
        Section("Ardaas — Standard (SGPC)") {
            switch loadResult {
            case .success(let content):
                ForEach(content.sources, id: \.self) { source in
                    if let url = URL(string: source) {
                        Link(destination: url) {
                            Text(displayName(for: source))
                                .foregroundStyle(Color.accentColor)
                        }
                    } else {
                        Text(source)
                            .foregroundStyle(Theme.mist)
                    }
                }
            case .failure(let error):
                Text("Could not load bundled text: \(String(describing: error))")
                    .foregroundStyle(Theme.mist)
            case nil:
                ProgressView()
            }
        }
        .listRowBackground(Theme.raisedFill)
    }

    private var accuracySection: some View {
        Section("Accuracy") {
            Text("The Gurmukhi text is cross-checked against independent sources and proof-read before every change. If you spot an error, please report it — scripture accuracy is treated as a defect of the highest severity.")
                .font(.footnote)
                .foregroundStyle(Theme.mist)
        }
        .listRowBackground(Theme.raisedFill)
    }

    private var aboutSection: some View {
        Section {
            LabeledContent("Version", value: Self.versionString)
                .foregroundStyle(Theme.parchment)
        }
        .listRowBackground(Theme.raisedFill)
    }

    /// Hostname as a readable label, e.g. "discoversikhism.com".
    private func displayName(for source: String) -> String {
        URL(string: source)?.host() ?? source
    }

    private static var versionString: String {
        let version = Bundle.main
            .object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—"
        let build = Bundle.main
            .object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "—"
        return "\(version) (\(build))"
    }
}

#Preview {
    SourcesView()
        .preferredColorScheme(.dark)
}
