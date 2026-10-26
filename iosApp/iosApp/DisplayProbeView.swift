import SharedLogic
import SwiftUI

/// "Report a display": reads what a tag says about itself without writing to
/// it, and shows the report.
struct DisplayProbeView: View {
    @StateObject private var prober: DisplayProber
    /// Off each time the screen opens.
    @State private var vendorQueries = false

    init(crcMode: CrcMode) {
        _prober = StateObject(wrappedValue: DisplayProber(crcMode: crcMode))
    }

    var body: some View {
        content
            .onDisappear { prober.cancel() }
    }

    @ViewBuilder private var content: some View {
        if case let .done(result) = prober.state {
            if let testWriter = prober.testWriter {
                TestWriteReportView(writer: testWriter)
            } else {
                displayReport(result, testWrites: [])
            }
        } else {
            Form {
                Section {
                    Text("KegInker reads what the tag says about itself and writes nothing to it. You see the report in full, then choose whether to share it.")
                    Toggle("Also try vendor commands", isOn: $vendorQueries)
                        .disabled(prober.isBusy)
                } footer: {
                    Text("These commands aren't documented. They could change what the display shows.")
                }
                Section {
                    Button("Read a display") { prober.read(vendorQueries: vendorQueries) }
                        .disabled(prober.isBusy || !TagWriter.isNfcAvailable)
                } footer: {
                    Text("iPhone only finds tags that use NFC-A or ISO 15693, or offer an application KegInker lists. If yours isn't found, an Android phone can usually read it.")
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

/// The display report of [result], with the records of any [testWrites].
@MainActor
func displayReport(_ result: ProbeResult, testWrites: [TestWriteRecord]) -> ReportView {
    let text = DisplayReportKt.formatDisplayReport(app: Diagnostics.appInfo, device: Diagnostics.deviceInfo(), result: result, testWrites: testWrites)
    return ReportView(title: "Display report", text: text, issueLink: IssueLinkKt.displayReportIssueLink(report: text))
}
