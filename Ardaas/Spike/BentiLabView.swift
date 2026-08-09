import SwiftUI

// Spike (#35): debug-only lab screen for the on-device translation pipeline.
// Reached from HomeView's flask toolbar button (DEBUG builds only).
//
// The memory pass added a mode picker so both pipelines can be measured in one
// cable run: "Baseline" reproduces the original 1374 MB configuration and
// "Optimized" runs the memory-reduced one.

struct BentiLabView: View {
    /// Peak `phys_footprint` measured on an iPhone 15 Pro before the memory
    /// pass, for at-a-glance comparison.
    static let baselinePeakBytes: Int64 = 1374 * 1_048_576

    enum Mode: String, CaseIterable, Identifiable {
        case baseline = "Baseline"
        case optimized = "Optimized"

        var id: String { rawValue }

        var options: BentiTranslator.Options {
            switch self {
            case .baseline: return .baseline
            case .optimized: return .optimized
            }
        }

        var caption: String {
            switch self {
            case .baseline:
                return "All 3 sessions resident, heap-resident weights, ORT-owned tensors."
            case .optimized:
                return "Sequential sessions, memory-mapped weights, detached tensors."
            }
        }
    }

    /// Load-time status text, in a class so the progress callback can capture a
    /// reference rather than the view struct.
    @MainActor
    final class LoadProgress: ObservableObject {
        @Published var text = "Preparing…"
    }

    @Environment(\.dismiss) private var dismiss
    @StateObject private var downloader = ModelDownloader()
    @StateObject private var loadProgress = LoadProgress()

    @State private var mode: Mode = .optimized
    @State private var translator: BentiTranslator?
    @State private var isLoadingModel = false
    @State private var isTranslating = false
    @State private var input = "Please bless my family with health and happiness."
    @State private var result: BentiTranslation?
    @State private var errorMessage: String?
    @State private var memory: MemorySnapshot?
    @State private var cacheBytes: Int64 = 0
    /// Bumped whenever the selected pipeline changes. A load or translation
    /// started under an older generation must not publish its numbers — they
    /// would be attributed to the pipeline the picker now shows.
    @State private var generation = 0

    var body: some View {
        NavigationStack {
            Form {
                modeSection
                modelSection
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
            .onAppear { cacheBytes = ModelOptimizer.cacheBytes(in: ModelDownloader.modelDirectory) }
        }
    }

    // MARK: - Sections

    private var modeSection: some View {
        Section("Pipeline") {
            Picker("Mode", selection: $mode) {
                ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .onChange(of: mode) { _, _ in
                // Sessions and the graph directory both differ per mode, so the
                // translator has to be rebuilt before the next measurement. Any
                // in-flight work belongs to the previous mode; ORT runs cannot
                // be cancelled, so orphan its results instead of letting them
                // report. `isLoadingModel`/`isTranslating` are deliberately not
                // cleared here — that task really is still running, and letting
                // it clear its own flag is what stops a second, concurrent
                // `ModelOptimizer.prepare` racing on the cache directories.
                generation += 1
                translator = nil
                result = nil
                errorMessage = nil
            }
            Text(mode.caption)
                .font(.footnote)
                .foregroundStyle(Theme.mist)
        }
        .listRowBackground(Theme.raisedFill)
    }

    private var modelSection: some View {
        Section("Model (~359 MB, Wi-Fi recommended)") {
            switch downloader.state {
            case .idle:
                Button("Download model files") { downloader.download() }
            case .downloading(let fileName, let fileIndex, let fileCount):
                VStack(alignment: .leading, spacing: 6) {
                    Text("Downloading \(fileName) (\(fileIndex)/\(fileCount))")
                        .font(.footnote)
                        .foregroundStyle(Theme.mist)
                    ProgressView(value: downloader.overallProgress)
                }
            case .verifying(let fileName):
                Label("Verifying \(fileName)…", systemImage: "checkmark.shield")
                    .foregroundStyle(Theme.mist)
            case .failed(let message):
                VStack(alignment: .leading, spacing: 6) {
                    Text(message)
                        .font(.footnote.monospaced())
                        .foregroundStyle(.red)
                    Button("Retry") { downloader.download() }
                }
            case .ready:
                if let translator {
                    Label(
                        "Ready in \(Self.ms(translator.modelLoadSeconds)) ms",
                        systemImage: "checkmark.circle.fill"
                    )
                    .foregroundStyle(Theme.kesri)
                } else if isLoadingModel {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text(loadProgress.text).foregroundStyle(Theme.mist)
                    }
                } else {
                    // A translation orphaned by a mode switch still owns its
                    // sessions; loading over it would measure both at once.
                    Button("Load model") { loadModel() }
                        .disabled(isTranslating)
                }
                if cacheBytes > 0 {
                    row("Optimized cache", Self.mb(cacheBytes))
                    Button("Delete optimized cache", role: .destructive) {
                        // Same reasoning as the mode picker: orphan whatever is
                        // in flight rather than let it report against a cache
                        // that no longer exists.
                        generation += 1
                        ModelOptimizer.removeCache(in: ModelDownloader.modelDirectory)
                        cacheBytes = 0
                        translator = nil
                        // The displayed numbers came from graphs that no longer
                        // exist; the rebuild will produce different artifacts.
                        result = nil
                        errorMessage = nil
                    }
                    .font(.footnote)
                    .disabled(isLoadingModel || isTranslating)
                }
            }
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
            .disabled(translator == nil || isTranslating
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

    /// The headline section: peak footprint against the 1374 MB baseline, plus
    /// the per-stage breakdown that shows where the peak actually happens.
    private func memorySection(_ result: BentiTranslation) -> some View {
        Section("Memory") {
            let peak = result.peakFootprintBytes
            HStack {
                Text("Peak this translate").foregroundStyle(Theme.mist)
                Spacer()
                Text(Self.mb(peak))
                    .font(.title3.monospacedDigit().bold())
                    .foregroundStyle(peak < Self.baselinePeakBytes ? Theme.kesri : .red)
            }
            row("vs 1374 MB baseline", Self.delta(peak, Self.baselinePeakBytes))
            if let memory {
                row("Footprint now", Self.mb(memory.footprintBytes))
                row("Process peak (ledger)", Self.mb(memory.peakBytes))
            }
            if let available = MemoryProbe.availableBytes() {
                row("Headroom before jetsam", Self.mb(available))
            }
            Text("Per stage: peak while it ran / footprint after it released.")
                .font(.caption)
                .foregroundStyle(Theme.mist)
            ForEach(result.stages) { stage in
                row(
                    stage.label,
                    "\(Self.mb(stage.peakBytes)) / \(Self.mb(stage.footprintBytes))",
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
            if let translator {
                row("Model prepare", "\(Self.ms(translator.modelLoadSeconds)) ms")
            }
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

    private func loadModel() {
        // Two concurrent inits would both run `ModelOptimizer.prepare` against
        // the same scratch and cache directories. And loading while a previous
        // translation is still running (possible after a mode switch, which
        // clears `translator` but cannot cancel the ORT run) would measure the
        // two pipelines on top of each other.
        guard !isLoadingModel, !isTranslating else { return }
        isLoadingModel = true
        loadProgress.text = "Preparing…"
        errorMessage = nil
        let directory = ModelDownloader.modelDirectory
        let options = mode.options
        let progress = loadProgress
        let token = generation
        Task.detached(priority: .userInitiated) {
            do {
                let loaded = try BentiTranslator(
                    modelDirectory: directory,
                    options: options,
                    onProgress: { status in
                        Task { @MainActor in progress.text = status }
                    }
                )
                await MainActor.run {
                    isLoadingModel = false
                    // Refreshed even when the result is orphaned: the load may
                    // still have written the ~667 MB cache, and hiding it would
                    // leave the user no way to reclaim it.
                    cacheBytes = ModelOptimizer.cacheBytes(in: directory)
                    guard token == generation else { return }
                    translator = loaded
                    memory = MemoryProbe.snapshot()
                }
            } catch {
                await MainActor.run {
                    isLoadingModel = false
                    cacheBytes = ModelOptimizer.cacheBytes(in: directory)
                    guard token == generation else { return }
                    errorMessage = "Model load failed: \(error)"
                }
            }
        }
    }

    private func translate() {
        guard let translator, !isTranslating, !isLoadingModel else { return }
        isTranslating = true
        errorMessage = nil
        let sentence = input
        let token = generation
        Task.detached(priority: .userInitiated) {
            do {
                let translation = try translator.translate(sentence)
                await MainActor.run {
                    isTranslating = false
                    guard token == generation else { return }
                    result = translation
                    memory = MemoryProbe.snapshot()
                }
            } catch {
                await MainActor.run {
                    isTranslating = false
                    guard token == generation else { return }
                    errorMessage = "Translation failed: \(error)"
                }
            }
        }
    }

    // MARK: - Formatting

    private static func ms(_ seconds: Double) -> String {
        String(format: "%.0f", seconds * 1000)
    }

    private static func mb(_ bytes: Int64) -> String {
        String(format: "%.0f MB", Double(bytes) / 1_048_576)
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
