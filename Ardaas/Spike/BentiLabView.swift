import SwiftUI

// Spike (#35): debug-only lab screen for the on-device translation pipeline.
// Reached from HomeView's flask toolbar button (DEBUG builds only).

/// `task_info(TASK_VM_INFO)` snapshot — `phys_footprint` is what Xcode's
/// memory gauge reports; the ledger peak is the high-water mark.
enum MemoryFootprint {
    static func snapshot() -> (currentBytes: Int64, peakBytes: Int64)? {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(
            MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        guard result == KERN_SUCCESS else { return nil }
        return (Int64(info.phys_footprint), Int64(info.ledger_phys_footprint_peak))
    }
}

struct BentiLabView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var downloader = ModelDownloader()

    @State private var translator: BentiTranslator?
    @State private var isLoadingModel = false
    @State private var isTranslating = false
    @State private var input = "Please bless my family with health and happiness."
    @State private var result: BentiTranslation?
    @State private var errorMessage: String?
    @State private var memory: (currentBytes: Int64, peakBytes: Int64)?

    var body: some View {
        NavigationStack {
            Form {
                modelSection
                translateSection
                if let result {
                    outputSection(result)
                    metricsSection(result)
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
        }
    }

    // MARK: - Sections

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
                        "Loaded in \(Self.ms(translator.modelLoadSeconds)) ms",
                        systemImage: "checkmark.circle.fill"
                    )
                    .foregroundStyle(Theme.kesri)
                } else if isLoadingModel {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("Creating ONNX sessions…").foregroundStyle(Theme.mist)
                    }
                } else {
                    Button("Load model") { loadModel() }
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

    private func metricsSection(_ result: BentiTranslation) -> some View {
        Section("Metrics") {
            row("Sentence latency", "\(Self.ms(result.latencySeconds)) ms")
            row("Generated tokens", "\(result.generatedTokenCount)")
            row("Tokens/s", String(format: "%.1f",
                Double(result.generatedTokenCount) / max(result.latencySeconds, 0.001)))
            if let translator {
                row("Model load", "\(Self.ms(translator.modelLoadSeconds)) ms")
            }
            if let memory {
                row("Footprint", Self.mb(memory.currentBytes))
                row("Peak footprint", Self.mb(memory.peakBytes))
            }
        }
        .listRowBackground(Theme.raisedFill)
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(Theme.mist)
            Spacer()
            Text(value).font(.body.monospacedDigit()).foregroundStyle(Theme.parchment)
        }
    }

    // MARK: - Actions

    private func loadModel() {
        isLoadingModel = true
        errorMessage = nil
        let directory = ModelDownloader.modelDirectory
        Task.detached(priority: .userInitiated) {
            do {
                let loaded = try BentiTranslator(modelDirectory: directory)
                await MainActor.run {
                    translator = loaded
                    isLoadingModel = false
                    memory = MemoryFootprint.snapshot()
                }
            } catch {
                await MainActor.run {
                    errorMessage = "Model load failed: \(error)"
                    isLoadingModel = false
                }
            }
        }
    }

    private func translate() {
        guard let translator else { return }
        isTranslating = true
        errorMessage = nil
        let sentence = input
        Task.detached(priority: .userInitiated) {
            do {
                let translation = try translator.translate(sentence)
                await MainActor.run {
                    result = translation
                    isTranslating = false
                    memory = MemoryFootprint.snapshot()
                }
            } catch {
                await MainActor.run {
                    errorMessage = "Translation failed: \(error)"
                    isTranslating = false
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
}

#Preview {
    BentiLabView()
}
