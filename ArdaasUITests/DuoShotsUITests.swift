import XCTest

/// TEMP (#171, dropped before merge): screenshots of the Reader on the
/// iPhone Duo simulator, written to ~/duo-shots on the host for CI to upload.
final class DuoShotsUITests: XCTestCase {
    private var dir: URL!
    private let app = XCUIApplication()

    override func setUpWithError() throws {
        continueAfterFailure = true
        let home = ProcessInfo.processInfo.environment["SIMULATOR_HOST_HOME"] ?? NSTemporaryDirectory()
        dir = URL(fileURLWithPath: home).appendingPathComponent("duo-shots")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private func shot(_ name: String) {
        sleep(2)
        let image = XCUIScreen.main.screenshot()
        let size = app.windows.firstMatch.frame.size
        let file = "\(name)-\(Int(size.width))x\(Int(size.height)).png"
        do {
            try image.pngRepresentation.write(to: dir.appendingPathComponent(file))
        } catch {
            XCTFail("Writing \(file) failed: \(error)")
        }
        let attachment = XCTAttachment(screenshot: image)
        attachment.name = file
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testReaderShots() {
        XCUIDevice.shared.orientation = .portrait
        app.launchArguments = ["-duoShots"]
        app.launch()
        shot("1-portrait-top")

        app.swipeUp()
        app.swipeUp()
        shot("2-portrait-scrolled")

        XCUIDevice.shared.orientation = .landscapeLeft
        shot("3-landscape-after-rotate")

        app.swipeDown()
        app.swipeDown()
        app.swipeDown()
        shot("4-landscape-top")

        let english = app.switches["Translation"]
        if english.exists {
            english.tap()
            shot("5-landscape-english-off")
            english.tap()
        }

        XCUIDevice.shared.orientation = .portrait
        shot("6-portrait-again")
    }
}
