import Foundation
import SharedLogic

/// Debug builds' copy of each shown report, saved as text in
/// `SAVED_REPORTS_FOLDER` under the app's Documents folder for a developer to
/// pull with `xcrun devicectl` (see CONTRIBUTING.md). The folder is kept out
/// of backups, so the copies stay on the phone. Release builds save nothing.
enum SavedReports {
    static var folder: URL {
        URL.documentsDirectory.appendingPathComponent(SavedReportKt.SAVED_REPORTS_FOLDER, isDirectory: true)
    }

    /// Saves [text], titled [title], and returns where; nil if it couldn't be written.
    @discardableResult
    static func save(title: String, text: String) -> URL? {
        let url = folder.appendingPathComponent(SavedReportKt.savedReportFileName(title: title))
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            var excluded = URLResourceValues()
            excluded.isExcludedFromBackup = true
            var backedUp = folder
            try backedUp.setResourceValues(excluded)
            try text.write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            NfcLog.log("Couldn't save the report: \(error)")
            return nil
        }
    }
}
