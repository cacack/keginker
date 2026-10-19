import SwiftUI

/// UserDefaults key for whether a successful write offers its diagnostics report.
let offerReportsKey = "offerReportAfterSuccess"

/// Diagnostics most people never need, kept off the write screen.
struct AdvancedView: View {
    @ObservedObject var writer: TagWriter
    @AppStorage(offerReportsKey) private var offerReports = false

    var body: some View {
        Form {
            Section {
                Toggle("Offer a report after every write", isOn: $offerReports)
            } header: {
                Text("Diagnostics")
            } footer: {
                Text("Failed writes always offer one. Turn this on when a write finishes but the tag doesn't look right.")
            }
            Section {
                Label("Report a display", systemImage: "antenna.radiowaves.left.and.right")
                    .foregroundStyle(.secondary)
            } header: {
                Text("Unsupported displays")
            } footer: {
                Text("Coming soon: read what an unsupported tag reports about itself, so you can share it with the KegInker project.")
            }
            #if DEBUG
            Section {
                Picker("CRC framing", selection: $writer.crcMode) {
                    ForEach(CrcMode.allCases) { Text($0.rawValue.capitalized).tag($0) }
                }
            } header: {
                Text("Earlier Waveshare boards")
            } footer: {
                Text("Whether commands to classic (pre-Rev2.2) boards carry an NFC checksum. Auto follows Apple's guidance; change it only when testing a classic board. Debug builds only.")
            }
            #endif
        }
        .navigationTitle("Advanced")
    }
}
