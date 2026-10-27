import CoreNFC
import SharedLogic
import XCTest
@testable import KegInker

/// What iOS puts in a diagnostics report: tag and device details, and the
/// reports a write leaves behind.
@MainActor
final class DiagnosticsTests: XCTestCase {
    func testMiFareTagNamesItsFamilyAndMarksAtqaAndSakUnavailable() {
        let info = Diagnostics.tagInfo(.miFare(FakeMiFareTag(family: .ultralight)))

        XCTAssertEqual(info.technologies, ["MIFARE (ultralight)"])
        XCTAssertEqual(SwiftInteropKt.nsData(bytes: info.uid) as Data, Data([0x04, 0x01, 0x02, 0x03]))
        let text = format(info)
        XCTAssertTrue(text.contains("ATQA: unavailable"), text)
        XCTAssertTrue(text.contains("SAK: unavailable"), text)
    }

    func testIso7816TagNamesItsAid() {
        let info = Diagnostics.tagInfo(.iso7816(FakeIso7816Tag()))

        XCTAssertEqual(info.technologies, ["ISO 7816 (AID )"])
        XCTAssertNil(info.historicalBytes)
    }

    func testDeviceIsAnAppleDeviceRunningIos() {
        let device = Diagnostics.deviceInfo()

        XCTAssertEqual(device.manufacturer, "Apple")
        XCTAssertFalse(device.model.isEmpty)
        XCTAssertTrue(device.os.hasPrefix("iOS "), device.os)
    }

    func testAppReportsTheGradleVersionAndCommit() {
        XCTAssertEqual(Diagnostics.appInfo.version, BuildInfo.shared.VERSION)
        XCTAssertEqual(Diagnostics.appInfo.commit, BuildInfo.shared.COMMIT)
        XCTAssertEqual(Diagnostics.appInfo.buildType, "debug")
    }

    func testASuccessfulWriteLeavesAReportNamingTheTestPattern() async throws {
        // The simulated tag is Waveshare, whichever display an earlier run chose.
        UserDefaults.standard.removeObject(forKey: TagWriter.displayKey)
        let writer = TagWriter()
        let view = LabelDisplayKt.renderView(
            display: writer.display,
            image: ColourImage.companion.fromGray(
                image: TestPatternKt.testPattern(width: writer.display.viewWidth, height: writer.display.viewHeight)
            ),
            dither: DitherFloydSteinberg.shared,
            colour: true
        )

        writer.simulate(view: view, testPattern: true)
        for _ in 0..<600 where writer.isBusy { try await Task.sleep(for: .milliseconds(50)) }

        guard case let .done(report) = writer.state else { return XCTFail("Expected done, got \(writer.state)") }
        XCTAssertEqual(report.outcome, .succeeded)
        XCTAssertTrue(report.testPattern)
        XCTAssertFalse(report.exchanges.isEmpty)
        let text = DiagnosticReportKt.formatDiagnosticReport(app: Diagnostics.appInfo, device: Diagnostics.deviceInfo(), report: report)
        XCTAssertTrue(text.contains("Outcome: succeeded"), text)
        XCTAssertTrue(text.contains("Image: built-in test pattern"), text)
        XCTAssertTrue(text.contains("bytes image data"), text)
    }

    func testASavedReportIsTheReportTextUnderDocumentsReports() throws {
        let text = format(Diagnostics.tagInfo(.miFare(FakeMiFareTag(family: .ultralight))))

        let url = try XCTUnwrap(SavedReports.save(title: "Diagnostics report", text: text))
        defer { try? FileManager.default.removeItem(at: url) }

        XCTAssertEqual(url.deletingLastPathComponent(), SavedReports.folder)
        XCTAssertEqual(SavedReports.folder.lastPathComponent, "reports")
        XCTAssertTrue(url.lastPathComponent.hasPrefix("diagnostics-report-"), url.lastPathComponent)
        XCTAssertEqual(try String(contentsOf: url, encoding: .utf8), text)
        XCTAssertEqual(try SavedReports.folder.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
    }

    private func format(_ info: TagInfo) -> String {
        let report = WriteReport(
            tag: info, protocol: "unsupported", panel: "WAVESHARE_2IN7", message: "x",
            outcome: .notAttempted, testPattern: false, step: nil, reason: nil, cause: nil, exchanges: []
        )
        return DiagnosticReportKt.formatDiagnosticReport(app: Diagnostics.appInfo, device: Diagnostics.deviceInfo(), report: report)
    }
}
