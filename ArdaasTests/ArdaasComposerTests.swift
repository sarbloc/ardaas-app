import XCTest
@testable import Ardaas

final class ArdaasComposerTests: XCTestCase {
    private let content = ArdaasContent.fixture(
        segments: [.fixture(id: "a"), .fixture(id: "b"), .fixture(id: "c")],
        slotAfter: "b"
    )

    func testBentiInsertsImmediatelyAfterSlotSegment() {
        let items = ArdaasComposer.compose(content: content, benti: "my benti")
        XCTAssertEqual(items, [
            .canonical(.fixture(id: "a")),
            .canonical(.fixture(id: "b")),
            .benti("my benti"),
            .canonical(.fixture(id: "c")),
        ])
    }

    func testBentiIsTrimmed() {
        let items = ArdaasComposer.compose(content: content, benti: "  my benti \n")
        XCTAssertEqual(items[2], .benti("my benti"))
    }

    func testNilBentiYieldsCanonicalOnly() {
        let items = ArdaasComposer.compose(content: content, benti: nil)
        XCTAssertEqual(items, content.segments.map(RenderItem.canonical))
    }

    func testWhitespaceOnlyBentiYieldsCanonicalOnly() {
        let items = ArdaasComposer.compose(content: content, benti: " \n\t ")
        XCTAssertEqual(items, content.segments.map(RenderItem.canonical))
    }

    func testUnknownSlotYieldsCanonicalOnly() {
        let broken = ArdaasContent.fixture(segments: [.fixture(id: "a")], slotAfter: "missing")
        let items = ArdaasComposer.compose(content: broken, benti: "my benti")
        XCTAssertEqual(items, [.canonical(.fixture(id: "a"))])
    }

    func testSlotOnLastSegmentAppendsBenti() {
        let tail = ArdaasContent.fixture(
            segments: [.fixture(id: "a"), .fixture(id: "b")], slotAfter: "b")
        let items = ArdaasComposer.compose(content: tail, benti: "my benti")
        XCTAssertEqual(items.last, .benti("my benti"))
    }

    func testBundledContentComposesAtCanonicalPoint() throws {
        let bundled = try XCTUnwrap(
            ArdaasLibrary.loadBundled().variant(id: "sgpc")
        ).content
        let items = ArdaasComposer.compose(content: bundled, benti: "ਮੇਰੀ ਬੇਨਤੀ")
        let bentiIndex = try XCTUnwrap(items.firstIndex(of: .benti("ਮੇਰੀ ਬੇਨਤੀ")))
        guard case let .canonical(before) = items[bentiIndex - 1],
              case let .canonical(after) = items[bentiIndex + 1] else {
            return XCTFail("benti must sit between canonical segments")
        }
        XCTAssertEqual(before.id, "nimaniyan-de-maan")
        XCTAssertEqual(after.id, "bhul-chuk-maaf")
        XCTAssertEqual(items.count, bundled.segments.count + 1)
    }
}
