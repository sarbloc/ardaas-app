import Foundation

/// The single point where the ONNX Runtime dependency is compiled in or out.
///
/// `BENTI_ONNX` is set by `project-onnx.yml`, the XcodeGen overlay that also
/// adds the `onnxruntime` package dependency. The default `project.yml` sets
/// neither, so a stock build — including every TestFlight/App Store archive
/// today — links no ONNX Runtime at all. See docs/translation/README.md.
///
/// Everything else in `Ardaas/Translation` compiles in both configurations:
/// the catalog, the download state machine, the cache manifest, the memory
/// guard, the tokenizer, the preprocessor and the postprocessor are plain
/// Foundation and are unit-tested on every CI run. Only session creation and
/// inference live behind the flag.
enum TranslationBuild {
    /// False in a stock build: the model can be downloaded and accounted for,
    /// but `translate` will throw `.engineUnavailable`.
    static var isEngineAvailable: Bool {
        #if BENTI_ONNX
        return true
        #else
        return false
        #endif
    }

    static func makeGraphOptimizer() -> GraphOptimizing {
        #if BENTI_ONNX
        return OnnxGraphOptimizer()
        #else
        return UnavailableGraphOptimizer()
        #endif
    }

    static func makeTranslator(
        catalog: ModelCatalog,
        graphDirectory: URL,
        vocabularyDirectory: URL
    ) throws -> BentiTranslating {
        #if BENTI_ONNX
        return try BentiTranslator(
            catalog: catalog,
            graphDirectory: graphDirectory,
            vocabularyDirectory: vocabularyDirectory)
        #else
        throw TranslationError.engineUnavailable
        #endif
    }
}
