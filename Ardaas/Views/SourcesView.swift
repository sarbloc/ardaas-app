import SwiftUI

/// Info page: cites the sources behind each bundled Gurbani text, states
/// the accuracy policy, and shows the app version. Presented as a sheet
/// from the Home screen.
struct SourcesView: View {
    @Environment(\.dismiss) private var dismiss

    /// Loaded once; a broken bundle shows the failure rather than hiding it.
    @State private var loadResult: Result<ArdaasLibrary, Error>?

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
                loadResult = Result { try ArdaasLibrary.loadBundled() }
            }
        }
    }

    @ViewBuilder
    private var textSection: some View {
        switch loadResult {
        case .success(let library):
            ForEach(library.variants) { variant in
                Section("Ardaas — \(variant.displayName)") {
                    ForEach(variant.content.sources, id: \.self) { source in
                        // Only real web links render as Link — iOS 17's
                        // lenient URL(string:) would otherwise turn prose
                        // source descriptions into broken links.
                        if let url = URL(string: source),
                           url.scheme == "https" || url.scheme == "http" {
                            Link(destination: url) {
                                Text(displayName(for: source))
                                    .foregroundStyle(Color.accentColor)
                            }
                        } else {
                            Text(source)
                                .font(.footnote)
                                .foregroundStyle(Theme.mist)
                        }
                    }
                }
                .listRowBackground(Theme.raisedFill)
            }
        case .failure(let error):
            Section("Texts") {
                Text("Could not load bundled texts: \(String(describing: error))")
                    .foregroundStyle(Theme.mist)
            }
            .listRowBackground(Theme.raisedFill)
        case nil:
            Section("Texts") {
                ProgressView()
            }
            .listRowBackground(Theme.raisedFill)
        }
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
