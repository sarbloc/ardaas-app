import XCTest
@testable import Ardaas

/// Wiring smoke test: proves the test target builds against the app target
/// and runs in CI. Real tests land with the composition logic (issue #3).
final class ArdaasTests: XCTestCase {
    func testTestTargetIsWired() {
        XCTAssertNotNil(ArdaasApp.self)
    }
}
