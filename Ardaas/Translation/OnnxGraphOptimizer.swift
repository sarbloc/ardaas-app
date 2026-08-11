#if BENTI_ONNX
import Foundation
import OnnxRuntimeBindings

/// The ONNX Runtime implementation of the one-time graph rewrite.
///
/// Creates a throwaway session purely for its side effect: ORT writes the
/// optimized graph plus its external initializer blob to disk. The session is
/// never run and is released immediately.
///
/// The pass must run CPU-only. Registering CoreML (or any execution provider
/// that produces compiled nodes) makes ORT refuse to serialize with "Unable to
/// serialize model as it contains compiled nodes". No provider is appended
/// anywhere in this stack, so CPU-only is simply the default — but it is a trap
/// for anyone who later adds one.
struct OnnxGraphOptimizer: GraphOptimizing {
    func optimize(source: URL, destination: URL, externalDataName: String) throws {
        guard FileManager.default.fileExists(atPath: source.path) else {
            throw TranslationError.missingModelFile(source.lastPathComponent)
        }
        do {
            let env = try ORTEnv(loggingLevel: .warning)
            let options = try ORTSessionOptions()
            try options.setLogSeverityLevel(.warning)
            try options.setGraphOptimizationLevel(.all)
            try options.setOptimizedModelFilePath(destination.path)
            // Written relative to the optimized model's own directory, which is
            // why the cache does not reach back to the originals.
            try options.addConfigEntry(
                withKey: "session.optimized_model_external_initializers_file_name",
                value: externalDataName)
            // Anything smaller than a page is not worth a mapping.
            try options.addConfigEntry(
                withKey: "session.optimized_model_external_initializers_min_size_in_bytes",
                value: "4096")
            // Pre-packed weights go to the same file, so they are mapped rather
            // than heap-allocated on every later session creation. Worth 65 MB
            // of peak; the alternative (`session.disable_prepacking`) reaches
            // the same peak but costs ~26% decode latency.
            try options.addConfigEntry(
                withKey: "session.save_external_prepacked_constant_initializers",
                value: "1")
            _ = try ORTSession(env: env, modelPath: source.path, sessionOptions: options)
        } catch {
            throw TranslationError.optimizationFailed(
                graph: source.lastPathComponent, detail: "\(error)")
        }
    }

    func verifyLoadable(graph: URL) throws {
        do {
            let env = try ORTEnv(loggingLevel: .warning)
            let session = try ORTSession(env: env, modelPath: graph.path, sessionOptions: nil)
            // Touching the IO metadata forces ORT past lazy initialization, so
            // a graph that cannot resolve its external initializers fails here
            // rather than at the first translation.
            _ = try session.inputNames()
            _ = try session.outputNames()
        } catch {
            throw TranslationError.optimizationFailed(
                graph: graph.lastPathComponent, detail: "\(error)")
        }
    }
}
#endif
