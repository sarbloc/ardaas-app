import SwiftUI

/// Info page: cites the sources behind each bundled Gurbani text, states
/// the accuracy policy, credits the on-device translation stack, links the
/// open-source licences, and shows the app version. Presented as a sheet
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
                translationSection
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
                        // Only a string that is wholly a web address renders
                        // as a Link, labelled with its host; prose is shown
                        // as written (see SourceEntry).
                        switch SourceEntry(source) {
                        case .link(let url, let host):
                            Link(destination: url) {
                                Text(host)
                                    .foregroundStyle(Color.accentColor)
                            }
                        case .prose(let text):
                            Text(text)
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

    /// Credits for on-device benti translation (#132) — same wording as
    /// Android's About & Sources (`sources_credit_*` in strings.xml).
    private var translationSection: some View {
        Section("Translation") {
            ForEach(Self.translationCredits, id: \.self) { credit in
                Text(credit)
                    .font(.footnote)
                    .foregroundStyle(Theme.mist)
            }
        }
        .listRowBackground(Theme.raisedFill)
    }

    private static let translationCredits = [
        "IndicTrans2 (en–indic, distilled 200M) by AI4Bharat — the model that translates a benti into Gurmukhi. MIT licence.",
        "Int8 ONNX export of IndicTrans2 by naklitechie on Hugging Face. MIT licence.",
        "ONNX Runtime by Microsoft — runs the model on your phone. MIT licence.",
    ]

    private var aboutSection: some View {
        Section {
            NavigationLink("Open-source licences") {
                LicencesView()
            }
            .foregroundStyle(Theme.parchment)
            LabeledContent("Version", value: Self.versionString)
                .foregroundStyle(Theme.parchment)
        }
        .listRowBackground(Theme.raisedFill)
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
