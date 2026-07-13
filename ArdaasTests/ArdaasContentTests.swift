import XCTest
@testable import Ardaas

/// Runs against the real bundled JSON — CI-level protection of the
/// content invariants the app relies on.
final class ArdaasContentTests: XCTestCase {
    func testBundledContentLoadsAndValidates() throws {
        let content = try ArdaasContent.loadBundled()
        XCTAssertEqual(content.version, 1)
        XCTAssertEqual(content.segments.count, 20)
        XCTAssertFalse(content.sources.isEmpty)
        XCTAssertEqual(content.bentiSlot.afterSegmentId, "nimaniyan-de-maan")
    }

    func testBundledSlotSegmentPrecedesArdaasHaiJi() throws {
        let content = try ArdaasContent.loadBundled()
        let ids = content.segments.map(\.id)
        let slotIndex = try XCTUnwrap(ids.firstIndex(of: content.bentiSlot.afterSegmentId))
        XCTAssertEqual(ids[slotIndex + 1], "ardaas-hai-ji",
                       "benti must complete 'ਆਪ ਦੇ ਹਜ਼ੂਰ [benti] ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥'")
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
