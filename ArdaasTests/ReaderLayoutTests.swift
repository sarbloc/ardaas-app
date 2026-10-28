import XCTest
@testable import Ardaas

/// The Reader's layout choice (#171): size class and window shape in,
/// layout out. Sizes are the iPhone Duo's screens in points.
final class ReaderLayoutTests: XCTestCase {
    private let duoOpenSideways = CGSize(width: 890, height: 626)
    private let duoOpenUpright = CGSize(width: 626, height: 890)
    private let duoFolded = CGSize(width: 466, height: 678)

    func testCompactWidthIsAlwaysThePhoneLayout() {
        XCTAssertEqual(ReaderLayout.choose(isRegularWidth: false, windowSize: duoFolded), .phone)
        XCTAssertEqual(ReaderLayout.choose(isRegularWidth: false, windowSize: duoOpenSideways), .phone)
        XCTAssertEqual(ReaderLayout.choose(isRegularWidth: false, windowSize: duoOpenUpright), .phone)
    }

    func testRegularWidthAndWideIsSideBySide() {
        XCTAssertEqual(ReaderLayout.choose(isRegularWidth: true, windowSize: duoOpenSideways), .sideBySide)
    }

    func testRegularWidthAndTallIsStacked() {
        XCTAssertEqual(ReaderLayout.choose(isRegularWidth: true, windowSize: duoOpenUpright), .stacked)
    }

    func testSquareWindowIsStacked() {
        let square = CGSize(width: 700, height: 700)
        XCTAssertEqual(ReaderLayout.choose(isRegularWidth: true, windowSize: square), .stacked)
    }

    func testUnmeasuredWindowIsThePhoneLayout() {
        XCTAssertEqual(ReaderLayout.choose(isRegularWidth: true, windowSize: .zero), .phone)
        XCTAssertEqual(ReaderLayout.choose(isRegularWidth: true, windowSize: CGSize(width: 890, height: 0)), .phone)
    }
}

/// The side-by-side column split: the gap straddles the fold.
final class ReaderColumnsTests: XCTestCase {
    func testGapIsCentredOnTheFold() {
        // Duo open sideways: fold at 445, a 24pt margin on the left, the
        // system's control strip and a 24pt margin on the right.
        let columns = ReaderColumns.split(areaMinX: 24, areaMaxX: 802, foldX: 445, gap: 48)
        XCTAssertEqual(columns.left, 397) // 24 ... 421
        XCTAssertEqual(columns.right, 333) // 469 ... 802
        let gapStart = 24 + columns.left
        let gapEnd = 802 - columns.right
        XCTAssertEqual((gapStart + gapEnd) / 2, 445)
    }

    func testFallsBackToEqualColumnsWhenTheFoldLeavesOneTooNarrow() {
        // The Reader sitting right of the fold: the left column would be
        // negative.
        let columns = ReaderColumns.split(areaMinX: 500, areaMaxX: 1100, foldX: 445, gap: 40)
        XCTAssertEqual(columns, ReaderColumns(left: 280, right: 280))
    }

    func testFallbackRespectsTheMinimumOnEitherSide() {
        let columns = ReaderColumns.split(
            areaMinX: 0, areaMaxX: 1000, foldX: 900, gap: 40, minimumWidth: 200
        )
        // Right would be 80: below the minimum, so split evenly.
        XCTAssertEqual(columns, ReaderColumns(left: 480, right: 480))
    }

    func testFallbackNeverGoesNegative() {
        let columns = ReaderColumns.split(areaMinX: 0, areaMaxX: 20, foldX: 10, gap: 48)
        XCTAssertEqual(columns, ReaderColumns(left: 0, right: 0))
    }
}
