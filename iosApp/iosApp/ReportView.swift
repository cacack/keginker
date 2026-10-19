import SharedLogic
import SwiftUI
import UIKit

/// A diagnostics report, shown in full before the user shares or copies it.
/// Nothing is sent anywhere unless they choose to.
struct ReportView: View {
    let report: WriteReport
    @State private var copied = false

    private var text: String {
        DiagnosticReportKt.formatDiagnosticReport(app: Diagnostics.appInfo, device: Diagnostics.deviceInfo(), report: report)
    }

    var body: some View {
        let text = self.text
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("This is everything the report contains. Nothing leaves your iPhone unless you share it.")
                HStack {
                    ShareLink("Share", item: text, subject: Text("KegInker diagnostics report"))
                        .buttonStyle(.borderedProminent)
                    Button("Copy") {
                        UIPasteboard.general.string = text
                        copied = true
                    }
                    .buttonStyle(.bordered)
                    if copied { Text("Copied").font(.footnote) }
                }
                Text(text)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
            }
            .padding()
        }
        .navigationTitle("Diagnostics report")
    }
}
