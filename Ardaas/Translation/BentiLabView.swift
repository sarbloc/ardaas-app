#if DEBUG
import SwiftUI

/// Diagnostics screen for the on-device translation stack (#35, #42).
///
/// DEBUG-only and reached from a flask button in the Home toolbar. It is *not*
/// the production entry point — the shipping UI is #44, which will drive the
/// same `BentiTranslationService` this screen drives, through the same public
/// API. Everything here is read-outs and manual triggers.
struct BentiLabView: View {
    /// Peak `phys_footprint` measured on an iPhone 15 Pro before the memory
    /// pass, for at-a-glance comparison.
    static let baselinePeakBytes: Int64 = 1374 * 1_048_576

    @Environment(\.dismiss) private var dismiss
    @StateObject private var service = BentiTranslationService()

    @State private var allowCellular = false
    @State private var isTranslating = false
    @State private var input = "Please bless my family with health and happiness."
    @State private var result: BentiTranslation?
    @State private var errorMessage: String?
    @State private var memory: MemorySnapshot?

    var body: some View {
        NavigationStack {
            Form {
                buildSection
                modelSection
                diskSection
                translateSection
                if let result {
                    outputSection(result)
                    memorySection(result)
                    latencySection(result)
                }
                if let errorMessage {
                    Section("Error") {
                        Text(errorMessage)
                            .font(.footnote.monospaced())
                            .foregroundStyle(.red)
                    }
                }
            }
            .themedScreen()
            .navigationTitle("Benti Lab")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear {
                service.refresh()
                memory = MemoryProbe.snapshot()
            }
        }
    }

    // MARK: - Sections

    private var buildSection: some View {
        Section("Build") {
            row(
                "ONNX Runtime",
                TranslationBuild.isEngineAvailable ? "linked (BENTI_ONNX)" : "not linked")
            if !TranslationBuild.isEngineAvailable {
                Text(
                    "Stock build: the engine is not linked, so installing and translating "
                    + "both throw engineUnavailable and the download controls are hidden. "
                    + "Regenerate with `xcodegen --spec project-onnx.yml` to link it."
                )
                .font(.caption)
                .foregroundStyle(Theme.mist)
            }
        }
        .listRowBackground(Theme.raisedFill)
    }

    private var modelSection: some View {
        Section("Model") {
            switch service.state {
            case .notDownloaded where !TranslationBuild.isEngineAvailable:
                // The installer refuses up front without the engine, so don't
                // advertise an action that can only fail here.
                Text("Link the engine to enable the install.")
                    .font(.footnote)
                    .foregroundStyle(Theme.mist)
            case .notDownloaded:
                disclosure
                Toggle("Allow cellular", isOn: $allowCellular)
                    .font(.footnote)
                Button("Download model") { service.download(allowingCellular: allowCellular) }
            case .downloading(let progress):
                VStack(alignment: .leading, spacing: 6) {
                    Text("Downloading — \(Int(progress * 100))%")
                        .font(.footnote)
                        .foregroundStyle(Theme.mist)
                    ProgressView(value: progress)
                }
                Button("Cancel", role: .cancel) { service.cancelDownload() }
            case .optimizing:
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Optimizing graphs (one-time)…").foregroundStyle(Theme.mist)
                }
            case .ready:
                Label("Ready", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(Theme.kesri)
                Button("Release engine from memory") {
                    Task { @MainActor in await service.releaseEngine() }
                }
                .font(.footnote)
                Button("Delete model", role: .destructive) {
                    Task { @MainActor in
                        result = nil
                        await service.deleteModel()
                    }
                }
            case .failed(let error):
                Text(error.description)
                    .font(.footnote.monospaced())
                    .foregroundStyle(.red)
                Button("Retry") { service.download(allowingCellular: allowCellular) }
                Button("Delete model", role: .destructive) {
                    Task { @MainActor in await service.deleteModel() }
                }
                .font(.footnote)
            }
        }
        .listRowBackground(Theme.raisedFill)
    }

    private var disclosure: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Downloads \(Self.bytes(service.downloadBytes)) once.")
            Text(
                "Needs \(Self.bytes(service.peakDiskBytes)) free while it "
                + "installs, and keeps \(Self.bytes(service.installedDiskBytes)) "
                + "afterwards."
            )
        }
        .font(.footnote)
        .foregroundStyle(Theme.mist)
    }

    private var diskSection: some View {
        Section("Disk") {
            row("On disk now", Self.bytes(service.installedBytes))
            row("Optimized cache (est.)", Self.bytes(service.optimizedCacheBytes))
            if let available = MemoryProbe.availableBytes() {
                row("Headroom before jetsam", Self.bytes(available))
            }
            row("Pre-flight requires", Self.bytes(MemoryGuard.requiredHeadroomBytes))
        }
        .listRowBackground(Theme.raisedFill)
    }

    private var translateSection: some View {
        Section("English benti") {
            TextField("Benti in English", text: $input, axis: .vertical)
                .lineLimit(3...6)
            Button {
                translate()
            } label: {
                if isTranslating {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("Translating…")
                    }
                } else {
                    Text("Translate")
                }
            }
            .disabled(!service.state.isReady || isTranslating
                      || input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .listRowBackground(Theme.raisedFill)
    }

    private func outputSection(_ result: BentiTranslation) -> some View {
        Section("Gurmukhi") {
            Text(result.gurmukhi)
                .font(.title3)
                .foregroundStyle(Theme.parchment)
                .textSelection(.enabled)
            if !result.isGurmukhiPure {
                Label("Non-Gurmukhi codepoints in output", systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
        .listRowBackground(Theme.raisedFill)
    }

    /// Peak footprint against the 1374 MB pre-optimization baseline, plus the
    /// per-stage breakdown that shows where the peak actually happens and that
    /// each stage hands its memory back.
    private func memorySection(_ result: BentiTranslation) -> some View {
        Section("Memory") {
            let peak = result.peakFootprintBytes
            HStack {
                Text("Peak this translate").foregroundStyle(Theme.mist)
                Spacer()
                Text(Self.megabytes(peak))
                    .font(.title3.monospacedDigit().bold())
                    .foregroundStyle(peak < Self.baselinePeakBytes ? Theme.kesri : .red)
            }
            row("vs 1374 MB baseline", Self.delta(peak, Self.baselinePeakBytes))
            if let memory {
                row("Footprint now", Self.megabytes(memory.footprintBytes))
                row("Process peak (ledger)", Self.megabytes(memory.peakBytes))
            }
            Text("Per stage: peak while it ran / footprint after it released.")
                .font(.caption)
                .foregroundStyle(Theme.mist)
            ForEach(result.stages) { stage in
                row(
                    stage.label,
                    "\(Self.megabytes(stage.peakBytes)) / \(Self.megabytes(stage.footprintBytes))",
                    secondary: true)
            }
        }
        .listRowBackground(Theme.raisedFill)
    }

    private func latencySection(_ result: BentiTranslation) -> some View {
        Section("Latency") {
            row("Sentence latency", "\(Self.ms(result.latencySeconds)) ms")
            row("of which session load", "\(Self.ms(result.sessionLoadSeconds)) ms")
            row("Generated tokens", "\(result.generatedTokenCount)")
            row("Tokens/s", String(format: "%.1f",
                Double(result.generatedTokenCount) / max(result.latencySeconds, 0.001)))
        }
        .listRowBackground(Theme.raisedFill)
    }

    private func row(_ label: String, _ value: String, secondary: Bool = false) -> some View {
        HStack {
            Text(label)
                .font(secondary ? .footnote : .body)
                .foregroundStyle(Theme.mist)
            Spacer()
            Text(value)
                .font(secondary ? .footnote.monospacedDigit() : .body.monospacedDigit())
                .foregroundStyle(Theme.parchment)
        }
    }

    // MARK: - Actions

    @MainActor
    private func translate() {
        guard !isTranslating else { return }
        isTranslating = true
        errorMessage = nil
        let sentence = input
        Task { @MainActor in
            do {
                let translation = try await service.translateDetailed(
                    sentence, collectMetrics: true)
                result = translation
                memory = MemoryProbe.snapshot()
            } catch {
                errorMessage = "\(TranslationError.wrap(error))"
            }
            isTranslating = false
        }
    }

    // MARK: - Formatting

    private static func ms(_ seconds: Double) -> String {
        String(format: "%.0f", seconds * 1000)
    }

    /// Decimal units, matching how iOS reports storage to the user.
    private static func bytes(_ value: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: value, countStyle: .file)
    }

    /// Binary MB, matching the memory numbers in docs/translation/MEMORY.md.
    private static func megabytes(_ value: Int64) -> String {
        String(format: "%.0f MB", Double(value) / 1_048_576)
    }

    private static func delta(_ value: Int64, _ reference: Int64) -> String {
        let difference = Double(value - reference) / 1_048_576
        let percent = Double(value) / Double(reference) * 100
        return String(format: "%+.0f MB (%.0f%%)", difference, percent)
    }
}

#Preview {
    BentiLabView()
}
#endif
