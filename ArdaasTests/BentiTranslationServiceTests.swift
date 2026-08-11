import XCTest

@testable import Ardaas

/// The public service (#42): the state machine #44 will render, the memory
/// pre-flight, and the engine boundary.
@MainActor
final class BentiTranslationServiceTests: XCTestCase {
    private var root: URL!
    private var model: TranslationFixtures.SyntheticModel!
    private var fetcher: FakeModelFileFetcher!
    private var optimizer: FakeGraphOptimizer!

    override func setUp() {
        super.setUp()
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("ServiceTests-\(UUID().uuidString)", isDirectory: true)
        model = TranslationFixtures.syntheticModel()
        fetcher = FakeModelFileFetcher(payloads: model.payloads)
        optimizer = FakeGraphOptimizer()
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
        super.tearDown()
    }

    private func makeInstaller() -> ModelInstaller {
        ModelInstaller(
            catalog: model.catalog,
            directory: root,
            fetcher: fetcher,
            optimizer: optimizer,
            availableDiskBytes: { 10_000_000 })
    }

    private func makeService(engine: TranslationEngine? = nil) -> BentiTranslationService {
        BentiTranslationService(
            installer: makeInstaller(),
            engine: engine ?? TranslationEngine(
                availableMemory: { nil },
                makeTranslator: { _, _, _ in StubTranslator() }))
    }

    private func waitForState(
        _ service: BentiTranslationService,
        timeout: TimeInterval = 10,
        _ isSatisfied: (ModelState) -> Bool
    ) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if isSatisfied(service.state) { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("timed out waiting; state is \(service.state)")
    }

    // MARK: - Opt-in

    func testAFreshServiceIsInertAndNotDownloaded() {
        let service = makeService()
        XCTAssertEqual(service.state, .notDownloaded)
        XCTAssertEqual(service.installedBytes, 0)
        XCTAssertTrue(
            fetcher.requests.isEmpty,
            "constructing the service must not start a download")
    }

    func testSizeDisclosureIsTheSumOfThePinnedFileSizes() {
        let service = makeService()
        XCTAssertEqual(service.downloadBytes, model.catalog.files.reduce(0) { $0 + $1.bytes })
        XCTAssertEqual(
            service.peakDiskBytes,
            service.downloadBytes + service.optimizedCacheBytes)
        XCTAssertGreaterThan(service.peakDiskBytes, service.installedDiskBytes)
    }

    /// The production numbers the UI will actually show.
    func testProductionCatalogDisclosesTheExpectedSizes() {
        let catalog = ModelCatalog.production
        XCTAssertEqual(catalog.downloadBytes, 358_521_397)
        XCTAssertEqual(catalog.graphBytes, 351_750_757)
        XCTAssertEqual(catalog.vocabularyBytes, 6_770_640)
        XCTAssertEqual(catalog.graphNames.count, 3)
        XCTAssertEqual(catalog.vocabulary.count, 3)
        // ~1.03 GB during install, ~677 MB afterwards.
        XCTAssertEqual(catalog.estimatedPeakDiskBytes, 1_028_521_397)
        XCTAssertEqual(catalog.estimatedInstalledBytes, 676_770_640)
    }

    // MARK: - State machine

    func testDownloadDrivesTheStateMachineToReady() async throws {
        let service = makeService()

        service.download()
        XCTAssertEqual(service.state, .downloading(progress: 0))

        try await waitForState(service) { $0 == .ready }
        XCTAssertGreaterThan(service.installedBytes, 0)
    }

    func testHashMismatchSurfacesAsAFailedState() async throws {
        fetcher.corrupt("tokenizer_src.json")
        let service = makeService()

        service.download()
        try await waitForState(service) { $0.error != nil }

        guard case .hashMismatch(let file, _, _)? = service.state.error else {
            return XCTFail("expected .hashMismatch, got \(String(describing: service.state.error))")
        }
        XCTAssertEqual(file, "tokenizer_src.json")
    }

    func testCellularRefusalSurfacesAsAFailedState() async throws {
        fetcher.requiresCellular = true
        let service = makeService()

        service.download()
        try await waitForState(service) { $0.error != nil }
        XCTAssertEqual(service.state.error, .cellularNotAllowed)
    }

    func testCancelReturnsToNotDownloadedRatherThanFailed() async throws {
        fetcher.delay = .milliseconds(400)
        let service = makeService()

        service.download()
        var spins = 0
        while fetcher.requestCount == 0 && spins < 200 {
            try await Task.sleep(for: .milliseconds(5))
            spins += 1
        }
        service.cancelDownload()

        XCTAssertEqual(service.state, .notDownloaded)
        XCTAssertNil(service.state.error, "cancelling is not failing")

        // The superseded install must not resurrect its own outcome later.
        try await Task.sleep(for: .milliseconds(600))
        XCTAssertEqual(service.state, .notDownloaded)
    }

    func testDeleteModelReturnsToNotDownloaded() async throws {
        let service = makeService()
        service.download()
        try await waitForState(service) { $0 == .ready }

        await service.deleteModel()

        XCTAssertEqual(service.state, .notDownloaded)
        XCTAssertEqual(service.installedBytes, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.path))
    }

    func testDeleteIsSafeBeforeAnythingWasDownloaded() async {
        let service = makeService()
        await service.deleteModel()
        XCTAssertEqual(service.state, .notDownloaded)
    }

    // MARK: - Translate

    func testTranslateBeforeReadyThrowsNotReady() async {
        let service = makeService()
        do {
            _ = try await service.translate("Please bless my family.")
            XCTFail("translating before the model is installed must not silently work")
        } catch {
            XCTAssertEqual(error as? TranslationError, .notReady)
        }
    }

    func testTranslateOnAReadyModelReturnsGurmukhi() async throws {
        let translator = StubTranslator()
        let service = makeService(
            engine: TranslationEngine(
                availableMemory: { nil }, makeTranslator: { _, _, _ in translator }))

        service.download()
        try await waitForState(service) { $0 == .ready }

        let output = try await service.translate("  Please bless my family.  ")
        XCTAssertEqual(output, "ਸਤਿਗੁਰੂ ਜੀ")
        XCTAssertEqual(
            translator.inputs, ["Please bless my family."],
            "the service should hand the engine trimmed text")
    }

    func testEmptyInputNeverReachesTheEngine() async throws {
        let translator = StubTranslator()
        let service = makeService(
            engine: TranslationEngine(
                availableMemory: { nil }, makeTranslator: { _, _, _ in translator }))
        service.download()
        try await waitForState(service) { $0 == .ready }

        let output = try await service.translate("   \n ")
        XCTAssertEqual(output, "")
        XCTAssertTrue(translator.inputs.isEmpty)
    }

    func testTranslateWithoutTheEngineThrowsEngineUnavailable() async throws {
        let service = makeService(
            engine: TranslationEngine(
                availableMemory: { nil },
                makeTranslator: { _, _, _ in throw TranslationError.engineUnavailable }))
        service.download()
        try await waitForState(service) { $0 == .ready }

        do {
            _ = try await service.translate("Please bless my family.")
            XCTFail("a build without ONNX Runtime must not pretend to translate")
        } catch {
            XCTAssertEqual(error as? TranslationError, .engineUnavailable)
        }
    }
}

/// The memory pre-flight sits in front of session creation, so the refusal has
/// to happen before the translator is even built.
final class TranslationEngineTests: XCTestCase {
    private final class FactoryProbe: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0

        var callCount: Int {
            lock.lock()
            defer { lock.unlock() }
            return count
        }

        func record() {
            lock.lock()
            count += 1
            lock.unlock()
        }
    }

    private let directory = URL(fileURLWithPath: "/tmp/does-not-matter")

    func testInsufficientHeadroomRefusesBeforeLoadingAnything() async {
        let probe = FactoryProbe()
        let engine = TranslationEngine(
            availableMemory: { 128 * 1_048_576 },
            makeTranslator: { _, _, _ in
                probe.record()
                return StubTranslator()
            })

        do {
            _ = try await engine.translate(
                "Please bless my family.",
                catalog: .production,
                graphDirectory: directory,
                vocabularyDirectory: directory)
            XCTFail("expected the pre-flight to refuse")
        } catch {
            guard case .insufficientMemory(let available, let required)?
                = error as? TranslationError
            else {
                return XCTFail("expected .insufficientMemory, got \(error)")
            }
            XCTAssertEqual(available, 128 * 1_048_576)
            XCTAssertEqual(required, MemoryGuard.requiredHeadroomBytes)
        }
        XCTAssertEqual(
            probe.callCount, 0,
            "nothing may be allocated once the pre-flight has refused")
    }

    func testTheTranslatorIsBuiltOnceAndReleasedOnDemand() async throws {
        let probe = FactoryProbe()
        let engine = TranslationEngine(
            availableMemory: { 4_000 * 1_048_576 },
            makeTranslator: { _, _, _ in
                probe.record()
                return StubTranslator()
            })

        _ = try await engine.translate(
            "one", catalog: .production, graphDirectory: directory,
            vocabularyDirectory: directory)
        _ = try await engine.translate(
            "two", catalog: .production, graphDirectory: directory,
            vocabularyDirectory: directory)
        XCTAssertEqual(probe.callCount, 1)
        let loaded = await engine.isLoaded
        XCTAssertTrue(loaded)

        await engine.release()
        let released = await engine.isLoaded
        XCTAssertFalse(released, "a memory warning must actually drop the translator")

        _ = try await engine.translate(
            "three", catalog: .production, graphDirectory: directory,
            vocabularyDirectory: directory)
        XCTAssertEqual(probe.callCount, 2)
    }
}

/// The `BENTI_ONNX` switch. Whichever way it is set, the behaviour on the other
/// side of it has to be coherent.
final class TranslationBuildTests: XCTestCase {
    func testStockBuildsRefuseToTranslateRatherThanCrash() throws {
        guard !TranslationBuild.isEngineAvailable else {
            throw XCTSkip("built with BENTI_ONNX; the engine is linked")
        }
        let directory = FileManager.default.temporaryDirectory
        XCTAssertThrowsError(
            try TranslationBuild.makeTranslator(
                catalog: .production,
                graphDirectory: directory,
                vocabularyDirectory: directory)
        ) { error in
            XCTAssertEqual(error as? TranslationError, .engineUnavailable)
        }
        XCTAssertThrowsError(
            try TranslationBuild.makeGraphOptimizer().verifyLoadable(
                graph: directory.appendingPathComponent("nope.onnx"))
        ) { error in
            XCTAssertEqual(error as? TranslationError, .engineUnavailable)
        }
    }
}
