import CoreNFC
import SharedLogic

/// Runs one write at a time over a Core NFC session: begin the session, wait
/// for a tag, write the frame, and report progress in the system NFC sheet.
/// Mirrors Android's `WriteController`; keep their behaviour in step.
@MainActor
final class TagWriter: NSObject, ObservableObject {
    enum State: Equatable {
        case idle
        case writing(Int)
        /// The report is offered when that's turned on under Advanced.
        case done(WriteReport)
        /// The report is nil when there was no tag to report on.
        case failed(String, WriteReport?)
    }

    @Published private(set) var state: State = .idle
    /// True from starting a write until both its session and its write have ended.
    @Published private(set) var isBusy = false
    /// Debug option for classic boards (see AdvancedView); remembered across launches.
    @Published var crcMode: CrcMode = CrcMode(rawValue: UserDefaults.standard.string(forKey: crcModeKey) ?? "") ?? .auto {
        didSet { UserDefaults.standard.set(crcMode.rawValue, forKey: Self.crcModeKey) }
    }
    private static let crcModeKey = "crcMode"

    let panel = WavesharePanel.epd2in7
    private var session: NFCTagReaderSession?
    private var isRunning = false
    private var view: KotlinBooleanArray?
    /// Whether [view] shows the built-in test pattern, for reports.
    private var testPattern = false
    /// The running write's tag and exchanges, for a report if the session ends under it.
    private var current: (tag: FoundTag, log: ExchangeLog)?

    static var isNfcAvailable: Bool { NFCTagReaderSession.readingAvailable }

    /// Opens the NFC sheet and writes [view] (from `renderView`) to the first
    /// tag presented, packed for whichever kind of tag it is. [testPattern]
    /// says, for reports, whether it shows the built-in test pattern.
    func write(view: KotlinBooleanArray, testPattern: Bool) {
        guard !isBusy else { return }
        isBusy = true
        self.view = view
        self.testPattern = testPattern
        state = .idle
        session = NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: .main)
        session?.alertMessage = "Hold the top of your iPhone against the tag and keep it still."
        NfcLog.log("Session starting (CRC \(crcMode.rawValue))")
        session?.begin()
    }

    /// Writes [view] to a simulated tag that accepts everything. Debug and Simulator only.
    func simulate(view: KotlinBooleanArray, testPattern: Bool) {
        guard !isBusy else { return }
        isBusy = true
        self.testPattern = testPattern
        let tag = FoundTagWaveshare(transport: SimulatedWaveshareTransport(commandDelayMs: 20))
        Task { await run(tag, view: view, session: nil) }
    }

    private func run(_ tag: FoundTagWritable, view: KotlinBooleanArray, session: NFCTagReaderSession?) async {
        isRunning = true
        state = .writing(0)
        let log = ExchangeLog()
        current = (tag, log)
        let result: State
        do {
            let outcome = try await ImageWriter().write(tag: tag, panel: panel, view: view, log: log) { percent in
                Task { @MainActor in self.progress(percent.intValue, session: session) }
            }
            if let failure = outcome as? WriteResultFailure {
                NfcLog.log("Write failed at \(failure.step.name): \(failure.reason)")
                let message = failure.userMessage
                result = .failed(message, report(tag, message, .failed, log: log, step: failure.step, reason: failure.reason, cause: failure.cause))
            } else {
                NfcLog.log("Write succeeded")
                result = .done(report(tag, WriteMessagesKt.DONE_MESSAGE, .succeeded, log: log))
            }
        } catch {
            let message = "The write failed unexpectedly: \(error.localizedDescription)"
            result = .failed(message, report(tag, message, .failed, log: log, reason: error.localizedDescription))
        }
        tag.transport.close()
        current = nil
        isRunning = false
        // The session may already have ended (sheet dismissed, timeout); that
        // failure stands, and the stale result must not replace it.
        if case .writing = state { state = result }
        if let session {
            if case let .failed(message, _) = state {
                session.invalidate(errorMessage: message)
            } else {
                session.alertMessage = "Done."
                session.invalidate()
            }
        }
        settle()
    }

    private func progress(_ percent: Int, session: NFCTagReaderSession?) {
        guard case .writing = state else { return }
        state = .writing(percent)
        session?.alertMessage = WriteMessagesKt.writeProgressMessage(percent: Int32(percent), phone: "iPhone")
    }

    /// Clears [isBusy] once nothing is left running.
    private func settle() {
        if session == nil && !isRunning { isBusy = false }
    }

    private func fail(_ message: String, report: WriteReport?, session: NFCTagReaderSession) {
        state = .failed(message, report)
        session.invalidate(errorMessage: message)
    }

    /// A report on [tag], built as Android builds it.
    private func report(
        _ tag: FoundTag,
        _ message: String,
        _ outcome: Outcome,
        log: ExchangeLog?,
        step: WriteStep? = nil,
        reason: String? = nil,
        cause: KotlinThrowable? = nil
    ) -> WriteReport {
        WriteReportKt.writeReport(
            tag: tag, panel: panel, message: message, outcome: outcome, testPattern: testPattern,
            log: log, step: step, reason: reason, cause: cause
        )
    }

    private func failUnsupported(_ tag: FoundTag, session: NFCTagReaderSession) {
        let message = WriteMessagesKt.UNSUPPORTED_TAG_MESSAGE
        fail(message, report: report(tag, message, .notAttempted, log: nil), session: session)
    }

    private func found(_ tags: [NFCTag], session: NFCTagReaderSession) {
        NfcLog.log("Detected \(tags.count) tag(s): \(tags.map(NfcLog.describe).joined(separator: "; "))")
        guard tags.count == 1, let tag = tags.first else {
            session.alertMessage = "More than one tag found. Hold just one tag to the iPhone."
            session.restartPolling()
            return
        }
        guard let view else {
            fail("Nothing to write. Try again.", report: nil, session: session)
            return
        }
        let info = Diagnostics.tagInfo(tag)
        let classified: FoundTag
        switch tag {
        case let .miFare(miFare):
            let crcMode = self.crcMode
            classified = FoundTagKt.classifyNfcATag(info: info) { MiFareTransport(tag: miFare, crcMode: crcMode) }
        case let .iso7816(iso7816):
            classified = FoundTagKt.classifyIsoDepTag(info: info) { Iso7816Transport(tag: iso7816) }
        default:
            failUnsupported(FoundTagUnsupported(info: info), session: session)
            return
        }
        guard let writable = classified as? FoundTagWritable else {
            failUnsupported(classified, session: session)
            return
        }
        NfcLog.log("Classified as \(writable is FoundTagWaveshareIsoDep ? "Waveshare Rev2.2 (ISO-DEP)" : "Waveshare classic")")
        session.connect(to: tag) { error in
            MainActor.assumeIsolated {
                if let error {
                    NfcLog.log("Connect failed: \(error.localizedDescription)")
                    writable.transport.close()
                    let message = "Couldn't connect to the tag. Hold it still and try again."
                    let report = self.report(writable, message, .failed, log: nil, reason: error.localizedDescription)
                    self.fail(message, report: report, session: session)
                    return
                }
                NfcLog.log("Connected")
                Task { await self.run(writable, view: view, session: session) }
            }
        }
    }
}

extension TagWriter: NFCTagReaderSessionDelegate {
    nonisolated func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
        NfcLog.log("Session active; waiting for a tag")
    }

    nonisolated func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        MainActor.assumeIsolated { found(tags, session: session) }
    }

    nonisolated func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: any Error) {
        MainActor.assumeIsolated {
            let code = (error as? NFCReaderError)?.code.rawValue ?? -1
            NfcLog.log("Session ended (code \(code)): \(error.localizedDescription)")
            guard session === self.session else { return }
            self.session = nil
            if case .writing = state {
                let message = "The NFC session ended before the write finished. Try again and keep the iPhone still."
                let report = current.map { self.report($0.tag, message, .failed, log: $0.log, reason: error.localizedDescription) }
                state = .failed(message, report)
            } else if case .idle = state, (error as? NFCReaderError)?.code != .readerSessionInvalidationErrorUserCanceled {
                state = .failed(error.localizedDescription, nil)
            }
            settle()
        }
    }
}
