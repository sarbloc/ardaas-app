import Foundation

/// One completed translation, plus the diagnostics the Benti Lab reads.
///
/// Callers that only want the text use `BentiTranslationService.translate`,
/// which returns the Gurmukhi string.
struct BentiTranslation: Sendable, Equatable {
    let gurmukhi: String
    /// Model-raw output after decode + cleanup, before transliteration.
    let rawDevanagari: String
    let generatedTokenCount: Int
    let latencySeconds: Double
    /// Time inside `ORTSession` creation for this translation.
    let sessionLoadSeconds: Double
    /// Highest `phys_footprint` sampled while this translation ran. Zero when
    /// metrics collection was off, which is the production default.
    let peakFootprintBytes: Int64
    /// Footprint at each pipeline boundary. Empty when metrics were off.
    let stages: [MemoryStage]
    /// Script purity gate (spec section 5.5). Advisory — see
    /// `BentiPostprocessor.isGurmukhiPure`.
    let isGurmukhiPure: Bool
}

/// The inference engine, behind a protocol so the rest of the stack compiles
/// with or without ONNX Runtime linked.
///
/// Implementations are not thread-safe; `TranslationEngine` is what serialises
/// access to them.
protocol BentiTranslating: AnyObject {
    func translate(_ english: String, collectMetrics: Bool) throws -> BentiTranslation
}
