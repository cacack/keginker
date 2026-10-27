import SharedLogic
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// A diagnostics or display report, shown in full before the user shares or
/// copies it. Nothing is sent anywhere unless they choose to. With an
/// [issueLink], it can also open a pre-filled GitHub issue in the browser; if
/// the report is too long for the link, Share sends it as a `.txt` file.
/// Debug builds also save each report shown, with `SavedReports`.
struct ReportView: View {
    let title: String
    let text: String
    var issueLink: IssueLink?
    @State private var copied = false
    @Environment(\.openURL) private var openURL

    /// The diagnostics report of a write.
    init(report: WriteReport) {
        self.init(
            title: "Diagnostics report",
            text: DiagnosticReportKt.formatDiagnosticReport(app: Diagnostics.appInfo, device: Diagnostics.deviceInfo(), report: report)
        )
    }

    init(title: String, text: String, issueLink: IssueLink? = nil) {
        self.title = title
        self.text = text
        self.issueLink = issueLink
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("This is everything the report contains. Nothing leaves your iPhone unless you share it.")
                HStack {
                    shareLink
                        .buttonStyle(.borderedProminent)
                    Button("Copy", action: copy)
                        .buttonStyle(.bordered)
                    if copied { Text("Copied").font(.footnote) }
                }
                if let issueLink, let url = URL(string: issueLink.url) {
                    Button("Open a GitHub issue") {
                        // Too long to fill in: copy it so the user can paste it into the form.
                        if !issueLink.includesReport { copy() }
                        openURL(url)
                    }
                    .buttonStyle(.bordered)
                    Text(issueLink.caption)
                        .font(.footnote)
                }
                Text(text)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
            }
            .padding()
        }
        .navigationTitle(title)
        #if DEBUG
        // The very text shown and shared, so a saved report hides what a shared one does.
        .task(id: text) { SavedReports.save(title: title, text: text) }
        #endif
    }

    @ViewBuilder private var shareLink: some View {
        let subject = "KegInker \(title.lowercased())"
        if issueLink?.sharesReportAsFile == true {
            ShareLink("Share", item: ReportFile(text: text), subject: Text(subject), preview: SharePreview(IssueLinkKt.REPORT_FILE_NAME))
        } else {
            ShareLink("Share", item: text, subject: Text(subject))
        }
    }

    private func copy() {
        UIPasteboard.general.string = text
        copied = true
    }
}

/// A report shared as a `REPORT_FILE_NAME` file, written alone to a folder in
/// the temporary directory only when shared. Each share replaces the last
/// one's file.
struct ReportFile: Transferable {
    let text: String

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .utf8PlainText) { file in
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent("reports", isDirectory: true)
            try? FileManager.default.removeItem(at: folder)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let url = folder.appendingPathComponent(IssueLinkKt.REPORT_FILE_NAME)
            try file.text.write(to: url, atomically: true, encoding: .utf8)
            return SentTransferredFile(url)
        }
    }
}
