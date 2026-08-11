import XCTest
@testable import Ardaas

final class ArdaasComposerTests: XCTestCase {
    private let content = ArdaasContent.fixture(
        segments: [.fixture(id: "a"), .fixture(id: "b"), .fixture(id: "c")],
        slotAfter: "b"
    )

    private let benti = BentiLayers(
        gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ",
        transliteration: "merī bentī",
        english: "my benti"
    )

    func testBentiInsertsImmediatelyAfterSlotSegment() {
        let items = ArdaasComposer.compose(content: content, benti: benti)
        XCTAssertEqual(items, [
            .canonical(.fixture(id: "a")),
            .canonical(.fixture(id: "b")),
            .benti(benti),
            .canonical(.fixture(id: "c")),
        ])
    }

    func testAllThreeLayersSurviveComposition() throws {
        let items = ArdaasComposer.compose(content: content, benti: benti)
        guard case let .benti(layers) = items[2] else {
            return XCTFail("expected the benti at the slot position")
        }
        XCTAssertEqual(layers.gurmukhi, "ਮੇਰੀ ਬੇਨਤੀ")
        XCTAssertEqual(layers.transliteration, "merī bentī")
        XCTAssertEqual(layers.english, "my benti")
    }

    /// Every layer is trimmed independently, so padding on one layer never
    /// leaks into the rendered text or into equality comparisons.
    func testEachLayerIsTrimmedIndependently() {
        let padded = BentiLayers(
            gurmukhi: "  ਮੇਰੀ ਬੇਨਤੀ\n",
            transliteration: "\tmerī bentī  ",
            english: "\n my benti \n"
        )
        let items = ArdaasComposer.compose(content: content, benti: padded)
        XCTAssertEqual(items[2], .benti(benti))
    }

    /// The layer the user actually typed is enough: an untranslated benti
    /// still composes, so nothing is hidden while translation is pending.
    func testEnglishOnlyBentiStillComposes() {
        let items = ArdaasComposer.compose(content: content, benti: BentiLayers(english: "my benti"))
        XCTAssertEqual(items[2], .benti(BentiLayers(english: "my benti")))
        XCTAssertEqual(items.count, content.segments.count + 1)
    }

    /// Gurmukhi-only is the mirror case (a benti typed straight in
    /// Gurmukhi, or a variant that renders only that layer).
    func testGurmukhiOnlyBentiStillComposes() {
        let items = ArdaasComposer.compose(content: content, benti: BentiLayers(gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ"))
        XCTAssertEqual(items[2], .benti(BentiLayers(gurmukhi: "ਮੇਰੀ ਬੇਨਤੀ")))
    }

    func testNilBentiYieldsCanonicalOnly() {
        let items = ArdaasComposer.compose(content: content, benti: nil)
        XCTAssertEqual(items, content.segments.map(RenderItem.canonical))
    }

    /// The empty rule: a benti counts as absent only when *every* layer is
    /// blank after trimming.
    func testAllBlankBentiYieldsCanonicalOnly() {
        let blank = BentiLayers(gurmukhi: "", transliteration: "", english: "")
        XCTAssertTrue(blank.isEmpty)
        XCTAssertEqual(
            ArdaasComposer.compose(content: content, benti: blank),
            content.segments.map(RenderItem.canonical)
        )
    }

    func testWhitespaceOnlyLayersYieldCanonicalOnly() {
        let blank = BentiLayers(gurmukhi: " ", transliteration: "\t", english: " \n\t ")
        XCTAssertTrue(blank.isEmpty)
        XCTAssertEqual(
            ArdaasComposer.compose(content: content, benti: blank),
            content.segments.map(RenderItem.canonical)
        )
    }

    func testUnknownSlotYieldsCanonicalOnly() {
        let broken = ArdaasContent.fixture(segments: [.fixture(id: "a")], slotAfter: "missing")
        let items = ArdaasComposer.compose(content: broken, benti: benti)
        XCTAssertEqual(items, [.canonical(.fixture(id: "a"))])
    }

    func testSlotOnLastSegmentAppendsBenti() {
        let tail = ArdaasContent.fixture(
            segments: [.fixture(id: "a"), .fixture(id: "b")], slotAfter: "b")
        let items = ArdaasComposer.compose(content: tail, benti: benti)
        XCTAssertEqual(items.last, .benti(benti))
    }

    func testBundledContentComposesAtCanonicalPoint() throws {
        try assertBundledSlot(
            variantId: "sgpc",
            expectedBefore: "nimaniyan-de-maan",
            expectedAfter: "bhul-chuk-maaf"
        )
    }

    /// The Buddha Dal variant carries no attested English, but the benti's
    /// layers are stored and composed identically — the variant decides
    /// what is *shown*, never what is *kept*.
    func testBundledBuddhaDalComposesAtCanonicalPoint() throws {
        try assertBundledSlot(
            variantId: "buddha-dal",
            expectedBefore: "he-deen-dayal",
            expectedAfter: "waheguru-ji-ka-khalsa"
        )
    }

    private func assertBundledSlot(
        variantId: String,
        expectedBefore: String,
        expectedAfter: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        let bundled = try XCTUnwrap(
            ArdaasLibrary.loadBundled().variant(id: variantId), file: file, line: line
        ).content
        let items = ArdaasComposer.compose(content: bundled, benti: benti)
        let bentiIndex = try XCTUnwrap(
            items.firstIndex(of: .benti(benti)), file: file, line: line
        )
        guard case let .canonical(before) = items[bentiIndex - 1],
              case let .canonical(after) = items[bentiIndex + 1] else {
            return XCTFail("benti must sit between canonical segments", file: file, line: line)
        }
        XCTAssertEqual(before.id, expectedBefore, file: file, line: line)
        XCTAssertEqual(after.id, expectedAfter, file: file, line: line)
        XCTAssertEqual(items.count, bundled.segments.count + 1, file: file, line: line)
    }
}
