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

    func testOnnxRuntimeThirdPartyNoticesAreBundled() throws {
        let ort = try XCTUnwrap(OpenSourceLicence.all.first { $0.resource == "licence-onnxruntime" })
        XCTAssertEqual(ort.thirdPartyNoticesResource, "licence-onnxruntime-third-party-notices")
        let raw = try XCTUnwrap(try ort.loadThirdPartyNotices())
        XCTAssertTrue(raw.hasPrefix("THIRD PARTY SOFTWARE NOTICES AND INFORMATION"))
        let sections = OpenSourceLicence.noticeSections(raw)
        XCTAssertGreaterThan(sections.count, 50)
        // Spot-check components the native binaries link statically.
        for component in ["protocolbuffers/protobuf", "Eigen", "abseil"] {
            XCTAssertTrue(sections.contains { $0.contains(component) }, "no notice for \(component)")
        }
    }

    func testOnlyOnnxRuntimeCarriesThirdPartyNotices() throws {
        for licence in OpenSourceLicence.all where licence.resource != "licence-onnxruntime" {
            XCTAssertNil(try licence.loadThirdPartyNotices(), licence.resource)
        }
    }

    func testAMissingThirdPartyNoticesResourceIsAnError() {
        let missing = OpenSourceLicence(
            resource: "licence-onnxruntime", component: "Nothing", source: "nowhere",
            thirdPartyNoticesResource: "nope"
        )
        XCTAssertThrowsError(try missing.loadThirdPartyNotices()) { error in
            XCTAssertEqual(error as? OpenSourceLicenceError, .resourceMissing("nope"))
        }
    }

    func testNoticeSectionsSplitOnUnderscoreRulesOnly() {
        let raw = "Preamble\nline two\n\n_____\n\nFirst component\n\nIts licence ___ inline\n_____\nSecond\n  _____  \n\n"
        XCTAssertEqual(
            OpenSourceLicence.noticeSections(raw),
            ["Preamble\nline two", "First component\n\nIts licence ___ inline", "Second"]
        )
    }

    func testNoticeSectionsKeepEveryWordOfTheRealNotices() throws {
        func words(_ text: String) -> [Substring] {
            text.split(whereSeparator: { $0.isWhitespace })
                .filter { !($0.count >= 5 && $0.allSatisfy { $0 == "_" }) }
        }
        let ort = try XCTUnwrap(OpenSourceLicence.all.first { $0.thirdPartyNoticesResource != nil })
        let raw = try XCTUnwrap(try ort.loadThirdPartyNotices())
        let sections = OpenSourceLicence.noticeSections(raw)
        XCTAssertEqual(words(sections.joined(separator: "\n")), words(raw))
        XCTAssertEqual(sections.flatMap { words(OpenSourceLicence.reflow($0)) }, words(raw))
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
