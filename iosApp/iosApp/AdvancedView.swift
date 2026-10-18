import SwiftUI

/// Diagnostics most people never need, kept off the write screen.
struct AdvancedView: View {
    @ObservedObject var writer: TagWriter

    var body: some View {
        Form {
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
