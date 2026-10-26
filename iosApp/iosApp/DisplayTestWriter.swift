import CoreNFC
import SharedLogic

/// The test writes of "Report a display" over Core NFC: each pattern is written
/// in its own session, on the same classify and connect path as
/// `DisplayProber`. The shared `TestWriteController` decides what is written,
/// and only to the probed display; this class connects it to the sessions.
@MainActor
final class DisplayTestWriter: NSObject, ObservableObject {
    @Published private(set) var state: TestWriteState
    /// True while a session is open or a write is running.
    @Published private(set) var isBusy = false
    /// Why the last session ended without a write, when the controller doesn't say.
    @Published private(set) var notice: String?
    let controller: TestWriteController
    private let crcMode: CrcMode
    private var watching: (any KotlinAutoCloseable)?
    private var session: NFCTagReaderSession?
    private var isRunning = false
    /// Set when the user finishes during a write; the controller ends once the write does.
    private var finishing = false

    init(controller: TestWriteController, crcMode: CrcMode) {
        self.controller = controller
        self.crcMode = crcMode
        state = controller.state.value as! TestWriteState
        super.init()
        watching = SwiftInteropKt.watchTestWrites(controller: controller) { [weak self] in self?.show($0) }
    }

    /// The records once the test writes have ended; empty before.
    var records: [TestWriteRecord] { (state as? TestWriteStateDone)?.records ?? [] }

    /// The user's opt-in: writes the first pattern to the next tap.
    func start() {
        controller.optIn()
        write()
    }

    /// Opens the NFC sheet to write the waiting pattern.
    func write() {
        guard session == nil, !isRunning, controller.isWaiting else { return }
        notice = nil
        isBusy = true
        session = NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: .main)
        session?.alertMessage = "Hold the top of your iPhone against the display"
        NfcLog.log("Test write session starting")
        session?.begin()
    }

    /// Records [choices], the exact option of each question in order, then writes the next pattern.
    func answer(_ choices: [String]) {
        controller.answer(choices: choices)
        write()
    }

    func retry() {
        controller.retry()
        write()
    }

    /// Ends any session and the test writes, keeping the records so far.
    /// Ignored while only offered, so the report keeps the offer.
    func finish() {
        guard !(controller.state.value is TestWriteStateOffered) else { return }
        session?.invalidate()
        if isRunning {
            finishing = true
        } else {
            controller.cancel()
        }
    }

    /// Hands [tag] to the controller, then ends [session] with the outcome.
    func handle(_ tag: FoundTag, session: NFCTagReaderSession?) async {
        isRunning = true
        // Only cancellation is thrown, and nothing here cancels.
        try? await controller.onTag(tag: tag)
        isRunning = false
        switch controller.state.value {
        case let failed as TestWriteStateFailed:
            NfcLog.log("Test write failed")
            session?.invalidate(errorMessage: failed.message)
        case let waiting as TestWriteStateWaiting where waiting.message != nil:
            session?.invalidate(errorMessage: waiting.message!)
        default:
            NfcLog.log("Test write ended")
            session?.alertMessage = "Done."
            session?.invalidate()
        }
        if finishing {
            finishing = false
            controller.cancel()
        }
        settle()
    }

    private func show(_ newState: TestWriteState) {
        if let writing = newState as? TestWriteStateWriting, !(state is TestWriteStateWriting) {
            session?.alertMessage = "Writing \(writing.pattern.title). Keep the iPhone still."
        }
        state = newState
    }

    /// Clears [isBusy] once nothing is left running.
    private func settle() {
        if session == nil && !isRunning { isBusy = false }
    }

    private func found(_ tags: [NFCTag], session: NFCTagReaderSession) {
        NfcLog.log("Test write detected \(tags.count) tag(s): \(tags.map(NfcLog.describe).joined(separator: "; "))")
        guard tags.count == 1, let tag = tags.first else {
            session.alertMessage = "More than one tag found. Hold just one tag to the iPhone."
            session.restartPolling()
            return
        }
        let classified = classify(tag, crcMode: crcMode)
        session.connect(to: tag) { error in
            MainActor.assumeIsolated {
                if let error {
                    NfcLog.log("Test write connect failed: \(error.localizedDescription)")
                    (classified as? FoundTagWritable)?.transport.close()
                    let message = "Couldn't connect to the display. Hold it still and try again."
                    self.notice = message
                    session.invalidate(errorMessage: message)
                    return
                }
                Task { await self.handle(classified, session: session) }
            }
        }
    }
}

extension DisplayTestWriter: NFCTagReaderSessionDelegate {
    nonisolated func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
        NfcLog.log("Test write session active; waiting for a tag")
    }

    nonisolated func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        MainActor.assumeIsolated { found(tags, session: session) }
    }

    nonisolated func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: any Error) {
        MainActor.assumeIsolated {
            let code = (error as? NFCReaderError)?.code
            NfcLog.log("Test write session ended (code \(code?.rawValue ?? -1)): \(error.localizedDescription)")
            guard session === self.session else { return }
            self.session = nil
            // A write still running fails on its own once the tag is gone.
            if !isRunning, notice == nil, code != .readerSessionInvalidationErrorUserCanceled,
               let waiting = controller.state.value as? TestWriteStateWaiting, waiting.message == nil {
                notice = error.localizedDescription
            }
            settle()
        }
    }
}
