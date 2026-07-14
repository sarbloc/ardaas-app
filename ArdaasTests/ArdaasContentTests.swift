import XCTest
@testable import Ardaas

/// Bundled-content tests run against the real JSON via the library —
/// CI-level protection of the content invariants the app relies on.
final class ArdaasContentTests: XCTestCase {
    func testBundledLibraryLoadsAndValidates() throws {
        let library = try ArdaasLibrary.loadBundled()
        XCTAssertEqual(library.variants.map(\.id), ["sgpc"])
        XCTAssertEqual(library.defaultVariant.id, ArdaasLibrary.defaultVariantId)
    }

    func testBundledSgpcContent() throws {
        let content = try XCTUnwrap(
            ArdaasLibrary.loadBundled().variant(id: "sgpc")
        ).content
        XCTAssertEqual(content.version, 1)
        XCTAssertEqual(content.segments.count, 19)
        XCTAssertFalse(content.sources.isEmpty)
        XCTAssertEqual(content.bentiSlot.afterSegmentId, "nimaniyan-de-maan")
        XCTAssertTrue(content.hasTransliteration)
        XCTAssertTrue(content.hasEnglish)
    }

    func testBundledSlotFollowsIntactSentence() throws {
        let content = try XCTUnwrap(
            ArdaasLibrary.loadBundled().variant(id: "sgpc")
        ).content
        let ids = content.segments.map(\.id)
        let slotIndex = try XCTUnwrap(ids.firstIndex(of: content.bentiSlot.afterSegmentId))
        // The ਹੇ ਨਿਮਾਣਿਆਂ sentence is recited whole (its ….. names the paath
        // or occasion); the benti follows as its own passage, before the
        // forgiveness line. Per SGPC Maryada + documented practice.
        XCTAssertTrue(content.segments[slotIndex].gurmukhi.hasSuffix("ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥"))
        XCTAssertEqual(ids[slotIndex + 1], "bhul-chuk-maaf")
    }

    func testResolvedVariantFallsBackToDefaultForUnknownId() throws {
        let library = try ArdaasLibrary.loadBundled()
        XCTAssertEqual(library.resolvedVariant(id: "no-such-variant").id, "sgpc")
    }

    func testValidateRejectsDuplicateIds() {
        let content = ArdaasContent.fixture(segments: [
            .fixture(id: "a"), .fixture(id: "a"),
        ], slotAfter: "a")
        XCTAssertThrowsError(try content.validate()) {
            XCTAssertEqual($0 as? ArdaasContentError, .duplicateSegmentIds)
        }
    }

    func testValidateRejectsUnknownSlot() {
        let content = ArdaasContent.fixture(segments: [.fixture(id: "a")], slotAfter: "missing")
        XCTAssertThrowsError(try content.validate()) {
            XCTAssertEqual($0 as? ArdaasContentError, .invalidSlot("missing"))
        }
    }

    func testValidateRejectsEmptyLayer() {
        let content = ArdaasContent.fixture(segments: [
            .fixture(id: "a"),
            ArdaasSegment(id: "b", gurmukhi: "ਬ", transliteration: "", english: "b"),
        ], slotAfter: "a")
        XCTAssertThrowsError(try content.validate()) {
            XCTAssertEqual($0 as? ArdaasContentError, .emptyLayer(segmentId: "b"))
        }
    }

    func testValidateRejectsInconsistentLayerCoverage() {
        let content = ArdaasContent.fixture(segments: [
            .fixture(id: "a"),
            ArdaasSegment(id: "b", gurmukhi: "ਬ", transliteration: nil, english: "e"),
        ], slotAfter: "a")
        XCTAssertThrowsError(try content.validate()) {
            XCTAssertEqual($0 as? ArdaasContentError, .inconsistentLayer("transliteration"))
        }
    }

    func testGurmukhiOnlyVariantValidates() throws {
        let content = ArdaasContent.fixture(segments: [
            ArdaasSegment(id: "a", gurmukhi: "ਗ", transliteration: nil, english: nil),
            ArdaasSegment(id: "b", gurmukhi: "ਬ", transliteration: nil, english: nil),
        ], slotAfter: "a")
        XCTAssertNoThrow(try content.validate())
        XCTAssertFalse(content.hasTransliteration)
        XCTAssertFalse(content.hasEnglish)
    }

    func testLibraryValidateRejectsDuplicateVariantIds() {
        let variant = ArdaasVariant(
            id: "sgpc",
            displayName: "A",
            content: .fixture(segments: [.fixture(id: "a")], slotAfter: "a")
        )
        let library = ArdaasLibrary(variants: [variant, variant])
        XCTAssertThrowsError(try library.validate()) {
            XCTAssertEqual($0 as? ArdaasLibraryError, .duplicateVariantIds)
        }
    }

    func testLibraryValidateRequiresDefaultVariant() {
        let library = ArdaasLibrary(variants: [
            ArdaasVariant(
                id: "taksali",
                displayName: "T",
                content: .fixture(segments: [.fixture(id: "a")], slotAfter: "a")
            ),
        ])
        XCTAssertThrowsError(try library.validate()) {
            XCTAssertEqual($0 as? ArdaasLibraryError, .missingDefaultVariant)
        }
    }
}

// MARK: - Fixtures

extension ArdaasSegment {
    static func fixture(id: String) -> ArdaasSegment {
        ArdaasSegment(id: id, gurmukhi: "ਗ", transliteration: "g", english: "e")
    }
}

extension ArdaasContent {
    static func fixture(segments: [ArdaasSegment], slotAfter: String) -> ArdaasContent {
        ArdaasContent(version: 1, sources: ["test"], segments: segments,
                      bentiSlot: BentiSlot(afterSegmentId: slotAfter))
    }
}
