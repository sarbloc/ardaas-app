import Foundation

/// Serialises access to the inference engine and owns its lifetime.
///
/// An actor rather than a lock because every entry point is already async and
/// because two concurrent translations would put two copies of the pipeline's
/// peak footprint on the ledger at once — which is precisely the thing the
/// memory work was about.
actor TranslationEngine {
    /// Loaded lazily on first translation and dropped on memory pressure. It
    /// holds the tokenizer and target dictionary (~7 MB) — *not* ONNX
    /// sessions, which are created and released inside each translation.
    private var translator: BentiTranslating?

    private let availableMemory: () -> Int64?
    private let makeTranslator: (ModelCatalog, URL, URL) throws -> BentiTranslating

    init(
        availableMemory: @escaping () -> Int64? = MemoryProbe.availableBytes,
        makeTranslator: @escaping (ModelCatalog, URL, URL) throws -> BentiTranslating
            = TranslationBuild.makeTranslator
    ) {
        self.availableMemory = availableMemory
        self.makeTranslator = makeTranslator
    }

    /// True once a translator is resident, so the lab can show whether the
    /// next translation pays the load cost.
    var isLoaded: Bool { translator != nil }

    func translate(
        _ english: String,
        catalog: ModelCatalog,
        graphDirectory: URL,
        vocabularyDirectory: URL,
        collectMetrics: Bool = false
    ) throws -> BentiTranslation {
        // Pre-flight before anything is allocated. The optimized pipeline peaks
        // at 582 MB; starting it with less headroom than that is asking for a
        // jetsam kill, which the user experiences as the app vanishing.
        try MemoryGuard.check(availableBytes: availableMemory())

        let translator = try loadedTranslator(
            catalog: catalog,
            graphDirectory: graphDirectory,
            vocabularyDirectory: vocabularyDirectory)
        return try translator.translate(english, collectMetrics: collectMetrics)
    }

    /// Drops the resident translator. Called on
    /// `UIApplication.didReceiveMemoryWarningNotification` and before the model
    /// files are deleted.
    ///
    /// An ORT `Run` already in flight cannot be interrupted, so this takes
    /// effect from the next translation; the sessions that run belongs to are
    /// released when it returns, by construction.
    func release() {
        translator = nil
    }

    private func loadedTranslator(
        catalog: ModelCatalog, graphDirectory: URL, vocabularyDirectory: URL
    ) throws -> BentiTranslating {
        if let translator { return translator }
        let loaded = try makeTranslator(catalog, graphDirectory, vocabularyDirectory)
        translator = loaded
        return loaded
    }
}
