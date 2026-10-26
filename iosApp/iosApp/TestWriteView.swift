import SharedLogic
import SwiftUI

/// What test writes do, shown with the offer on the display report and again before the user confirms.
let testWritesOffer = "Writes a few test patterns to the display and asks what you see. This replaces what the display shows."

/// The display report with its test writes: offered under the report,
/// confirmed and run on their own screen, and added to the report when they end.
struct TestWriteReportView: View {
    let result: ProbeResult
    @ObservedObject var writer: DisplayTestWriter
    @State private var showingTestWrites = false

    var body: some View {
        displayReport(result, testWrites: writer.records)
            .safeAreaInset(edge: .bottom) {
                if writer.state is TestWriteStateOffered { offer }
            }
            .navigationDestination(isPresented: $showingTestWrites) {
                // Leaving the screen ends any session and keeps the records;
                // leaving the confirmation keeps the offer.
                TestWriteView(writer: writer)
                    .onDisappear { writer.finish() }
            }
            .onChange(of: writer.state is TestWriteStateDone) { _, done in
                if done { showingTestWrites = false }
            }
    }

    private var offer: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Opens the confirmation, which writes nothing yet.
            Button("Test writes") { showingTestWrites = true }
            .buttonStyle(.borderedProminent)
            .disabled(!TagWriter.isNfcAvailable)
            Text(testWritesOffer)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.bar)
    }
}

/// One step of the test writes, following the shared controller's state: the
/// confirmation, waiting for the display, writing, asking what it shows, or failed.
struct TestWriteView: View {
    @ObservedObject var writer: DisplayTestWriter
    /// The chosen option of each question, by question id.
    @State private var choices: [String: String] = [:]

    var body: some View {
        Form { content }
            .navigationTitle("Test writes")
    }

    @ViewBuilder private var content: some View {
        switch writer.state {
        case is TestWriteStateOffered:
            Section {
                Text(testWritesOffer)
                Text("Only the \(writer.controller.familyName) display you reported is written. Nothing is written until you start.")
            }
            Section {
                Button("Start test writes") { writer.start() }
                    .disabled(!TagWriter.isNfcAvailable)
            }
        case let waiting as TestWriteStateWaiting:
            Section {
                Text("Pattern \(waiting.index + 1) of \(waiting.count): \(waiting.pattern.title)")
                if writer.isBusy {
                    Text("Hold the top of your iPhone against the display.")
                } else {
                    Button("Write \(waiting.pattern.title)") { writer.write() }
                }
                if let message = waiting.message ?? writer.notice {
                    Text(message).foregroundStyle(.red)
                }
            } footer: {
                Text("This replaces what the display shows.")
            }
        case let writing as TestWriteStateWriting:
            Section {
                Text("Writing \(writing.pattern.title). Keep the iPhone still.")
                ProgressView(value: Double(writing.percent), total: 100)
            }
        case let asking as TestWriteStateAsking:
            ForEach(asking.questions, id: \.id) { question in
                Section {
                    Picker(question.text, selection: choice(question.id)) {
                        ForEach(question.options, id: \.self) { Text($0).tag(Optional($0)) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text(question.text).textCase(nil)
                }
            }
            Section {
                Button("Next") {
                    let answers = asking.questions.compactMap { choices[$0.id] }
                    choices = [:]
                    writer.answer(answers)
                }
                .disabled(asking.questions.contains { choices[$0.id] == nil })
            }
        case let failed as TestWriteStateFailed:
            Section {
                Text(failed.message).foregroundStyle(.red)
                Button("Retry") { writer.retry() }
                    .disabled(writer.isBusy)
                Button("Finish and see report") { writer.finish() }
            }
        default:
            EmptyView()
        }
    }

    private func choice(_ id: String) -> Binding<String?> {
        Binding(get: { choices[id] }, set: { choices[id] = $0 })
    }
}
