import XCTest
@testable import Ardaas

/// About & Sources link detection, mirroring Android's `SourceEntryTest`.
final class SourceEntryTests: XCTestCase {
    func testAWebAddressIsALinkLabelledWithItsHost() {
        let cases = [
            ("https://www.discoversikhism.com/sikhism/sikh_ardas.html", "www.discoversikhism.com"),
            ("http://example.org", "example.org"),
        ]
        for (address, expectedHost) in cases {
            guard case .link(let url, let host) = SourceEntry(address) else {
                XCTFail("not a link: \(address)")
                continue
            }
            XCTAssertEqual(url.absoluteString, address)
            XCTAssertEqual(host, expectedHost)
        }
    }

    func testProseIsShownAsWritten() {
        let prose = "Nihung Santhiya app — transcribed by hand"
        XCTAssertEqual(SourceEntry(prose), .prose(prose))
    }

    /// Truncating this to "api.banidb.com" would lose the description and
    /// link to an address that does not exist (#108).
    func testProseThatBeginsWithAnAddressStaysProse() {
        let source = "https://api.banidb.com/v2/angs/268 and /383 — BaniDB"
        XCTAssertEqual(SourceEntry(source), .prose(source))
    }

    func testOtherSchemesAndHostlessStringsAreProse() {
        for source in [
            "mailto:someone@example.org", "ftp://example.org/x", "https:relative", "ardaas-sgpc.json",
        ] {
            XCTAssertEqual(SourceEntry(source), .prose(source), source)
        }
    }

    /// The screen renders the bundle itself: every variant cites something,
    /// and nothing renders blank.
    func testEveryBundledSourceRendersAsSomething() throws {
        let variants = try ArdaasLibrary.loadBundled().variants
        XCTAssertFalse(variants.isEmpty)
        for variant in variants {
            XCTAssertFalse(variant.content.sources.isEmpty, "\(variant.id) cites no sources")
            for source in variant.content.sources {
                let shown: String
                switch SourceEntry(source) {
                case .link(_, let host): shown = host
                case .prose(let text): shown = text
                }
                XCTAssertFalse(
                    shown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                    "\(variant.id) has a blank source"
                )
            }
        }
    }

    func testTheStandardTextsSourcesAreLinks() throws {
        let sgpc = try XCTUnwrap(ArdaasLibrary.loadBundled().variant(id: "sgpc")).content
        for source in sgpc.sources {
            guard case .link = SourceEntry(source) else {
                return XCTFail("SGPC source is not a link: \(source)")
            }
        }
    }
}
