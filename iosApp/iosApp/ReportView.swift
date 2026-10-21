import SharedLogic
import SwiftUI
import UIKit

/// A diagnostics or display report, shown in full before the user shares or
/// copies it. Nothing is sent anywhere unless they choose to. With an
/// [issueLink], it can also open a pre-filled GitHub issue in the browser.
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
                    ShareLink("Share", item: text, subject: Text("KegInker \(title.lowercased())"))
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
                    Text(issueLink.includesReport
                        ? "Opens the New display report form in your browser with this report filled in. Add a photo and the model printed on the display."
                        : "Opens the New display report form in your browser and copies this report, which is too long to fill in, so you can paste it.")
                        .font(.footnote)
                }
                Text(text)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
            }
            .padding()
        }
        .navigationTitle(title)
    }

    private func copy() {
        UIPasteboard.general.string = text
        copied = true
    }
}
