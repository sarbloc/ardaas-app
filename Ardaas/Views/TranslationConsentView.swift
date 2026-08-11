import SwiftUI

/// The one-time explanation shown before a single byte is downloaded.
///
/// The translation model is ~359 MB and takes ~1 GB of free space to install,
/// so it is never fetched behind the user's back: tapping Translate with no
/// model installed presents this sheet, and only "Download" here calls
/// `BentiTranslationService.download(allowingCellular:)`.
///
/// Deliberately dumb — it owns no service and starts nothing. It takes the
/// three byte figures (which come from the pinned catalog, not from an
/// estimate) and hands back the one decision it collects: whether cellular is
/// allowed.
struct TranslationConsentView: View {
    let downloadBytes: Int64
    let peakDiskBytes: Int64
    let installedDiskBytes: Int64
    /// Called with the cellular choice when the user confirms.
    let onConfirm: (Bool) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var allowCellular = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Ardaas can translate your benti into Gurmukhi on this phone. To do that it downloads a translation model once.")
                        .font(.callout)
                        .foregroundStyle(Theme.parchment)
                }
                .listRowBackground(Theme.raisedFill)

                Section("What gets downloaded") {
                    figure("Download", Self.bytes(downloadBytes), "once")
                    figure("Free space while installing", Self.bytes(peakDiskBytes), nil)
                    figure("Kept on your phone after", Self.bytes(installedDiskBytes), nil)
                }
                .listRowBackground(Theme.raisedFill)

                Section("Privacy") {
                    point(
                        "lock.iphone",
                        "Everything happens on this phone. Your benti is never uploaded, and translating keeps working with no signal at all.")
                }
                .listRowBackground(Theme.raisedFill)

                Section("Network") {
                    point(
                        "wifi",
                        "Wi-Fi is used by default. Turn on mobile data below to download without it — that spends about \(Self.bytes(downloadBytes)) of your data plan.")
                    Toggle("Download over mobile data", isOn: $allowCellular)
                        .font(.subheadline)
                    point(
                        "hourglass",
                        "Keep this screen open while it downloads. If you leave, whatever finished is kept and the download picks up from there next time.")
                }
                .listRowBackground(Theme.raisedFill)

                Section {
                    Button {
                        onConfirm(allowCellular)
                        dismiss()
                    } label: {
                        Text("Download \(Self.bytes(downloadBytes))")
                            .frame(maxWidth: .infinity)
                            .fontWeight(.semibold)
                    }
                    .buttonStyle(.borderedProminent)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
            }
            .themedScreen()
            .navigationTitle("Translate on this phone")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Not now") { dismiss() }
                }
            }
        }
    }

    private func figure(_ label: String, _ value: String, _ note: String?) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(Theme.mist)
            Spacer(minLength: 12)
            Text(note.map { "\(value) \($0)" } ?? value)
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(Theme.parchment)
        }
        .accessibilityElement(children: .combine)
    }

    private func point(_ symbol: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(Theme.kesri)
                .accessibilityHidden(true)
            Text(text)
                .font(.footnote)
                .foregroundStyle(Theme.mist)
        }
    }

    /// Decimal units, matching how iOS reports storage to the user.
    private static func bytes(_ value: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: value, countStyle: .file)
    }
}

#Preview {
    TranslationConsentView(
        downloadBytes: 358_521_397,
        peakDiskBytes: 1_028_521_397,
        installedDiskBytes: 676_770_640,
        onConfirm: { _ in }
    )
}
