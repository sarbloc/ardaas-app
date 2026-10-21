import XCTest
@testable import Ardaas

/// The licence notices About & Sources links to (#132) are bundled, carry
/// their component's copyright line, and survive reflowing word for word.
final class OpenSourceLicenceTests: XCTestCase {

    /// The copyright line each notice must carry, by resource name.
    private let expectedCopyright = [
        "licence-indictrans2": "Copyright (c) AI4Bharat.",
        "licence-indictrans2-onnx-int8": "Copyright (c) AI4Bharat.",
        "licence-onnxruntime": "Copyright (c) Microsoft Corporation",
    ]

    func testEveryLicenceIsBundledWithItsCopyrightAndPermissionNotice() throws {
        XCTAssertEqual(Set(OpenSourceLicence.all.map(\.resource)), Set(expectedCopyright.keys))
        for licence in OpenSourceLicence.all {
            let text = try licence.loadText()
            let copyright = try XCTUnwrap(expectedCopyright[licence.resource])
            XCTAssertTrue(text.contains(copyright), "\(licence.resource) lacks its copyright line")
            XCTAssertTrue(
                text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("MIT License"),
                "\(licence.resource) is not the MIT licence"
            )
            XCTAssertTrue(
                text.contains("The above copyright notice and this permission notice shall be included in all"),
                "\(licence.resource) lacks the permission notice"
            )
        }
    }

    func testAMissingResourceIsAnError() {
        let missing = OpenSourceLicence(resource: "nope", component: "Nothing", source: "nowhere")
        XCTAssertThrowsError(try missing.loadText()) { error in
            XCTAssertEqual(error as? OpenSourceLicenceError, .resourceMissing("nope"))
        }
    }

    func testReflowJoinsHardWrappedLinesAndDropsIndentation() {
        let raw = "    MIT License\n\n    Copyright (c) Someone.\n\n    Permission is hereby\n    granted, free\r\n    of charge.\n\n\n    THE END"
        XCTAssertEqual(
            OpenSourceLicence.reflow(raw),
            "MIT License\n\nCopyright (c) Someone.\n\nPermission is hereby granted, free of charge.\n\nTHE END"
        )
    }

    func testReflowKeepsEveryWordOfARealNotice() throws {
        func words(_ text: String) -> [Substring] {
            text.split(whereSeparator: { $0.isWhitespace })
        }
        for licence in OpenSourceLicence.all {
            let raw = try licence.loadText()
            XCTAssertEqual(words(OpenSourceLicence.reflow(raw)), words(raw), licence.resource)
        }
    }
}
