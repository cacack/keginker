import SharedLogic
import SwiftUI

/// "Report a display": reads what a tag says about itself without writing to
/// it, and shows the report.
struct DisplayProbeView: View {
    @StateObject private var prober: DisplayProber

    init(crcMode: CrcMode) {
        _prober = StateObject(wrappedValue: DisplayProber(crcMode: crcMode))
    }

    var body: some View {
        content
            .onDisappear { prober.cancel() }
    }

    @ViewBuilder private var content: some View {
        if case let .done(result) = prober.state {
            let text = DisplayReportKt.formatDisplayReport(app: Diagnostics.appInfo, device: Diagnostics.deviceInfo(), result: result)
            ReportView(title: "Display report", text: text, issueLink: IssueLinkKt.displayReportIssueLink(report: text))
        } else {
            Form {
                Section {
                    Text("KegInker reads what the tag says about itself and writes nothing to it. You see the report in full, then choose whether to share it.")
                    Button("Read a display", action: prober.read)
                        .disabled(prober.isBusy || !TagWriter.isNfcAvailable)
                } footer: {
                    Text("iPhone only finds tags that use NFC-A or offer an application KegInker lists. If yours isn't found, an Android phone can usually read it.")
                }
                if case .reading = prober.state {
                    Text("Reading the tag. Keep the iPhone still.")
                }
                if case let .failed(message) = prober.state {
                    Text(message).foregroundStyle(.red)
                }
                if !TagWriter.isNfcAvailable {
                    Text("This device can't read NFC tags.").foregroundStyle(.red)
                }
            }
            .navigationTitle("Report a display")
        }
    }
}
