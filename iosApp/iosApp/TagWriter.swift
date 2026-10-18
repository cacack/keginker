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
        case done
        case failed(String)
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

    static var isNfcAvailable: Bool { NFCTagReaderSession.readingAvailable }

    /// Opens the NFC sheet and writes [view] (from `renderView`) to the first
    /// tag presented, packed for whichever kind of tag it is.
    func write(view: KotlinBooleanArray) {
        guard !isBusy else { return }
        isBusy = true
        self.view = view
        state = .idle
        session = NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: .main)
        session?.alertMessage = "Hold the top of your iPhone against the tag and keep it still."
        NfcLog.log("Session starting (CRC \(crcMode.rawValue))")
        session?.begin()
    }

    /// Writes [view] to a simulated tag that accepts everything. Debug and Simulator only.
    func simulate(view: KotlinBooleanArray) {
        guard !isBusy else { return }
        isBusy = true
        let tag = FoundTagWaveshare(transport: SimulatedWaveshareTransport(commandDelayMs: 20))
        Task { await run(tag, view: view, session: nil) }
    }

    private func run(_ tag: FoundTagWritable, view: KotlinBooleanArray, session: NFCTagReaderSession?) async {
        isRunning = true
        state = .writing(0)
        let result: State
        do {
            let outcome = try await ImageWriter().write(tag: tag, panel: panel, view: view) { percent in
                Task { @MainActor in self.progress(percent.intValue, session: session) }
            }
            if let failure = outcome as? WriteResultFailure {
                NfcLog.log("Write failed at \(failure.step.name): \(failure.reason)")
                result = .failed(failure.userMessage)
            } else {
                NfcLog.log("Write succeeded")
                result = .done
            }
        } catch {
            result = .failed("The write failed unexpectedly: \(error.localizedDescription)")
        }
        tag.transport.close()
        isRunning = false
        // The session may already have ended (sheet dismissed, timeout); that
        // failure stands, and the stale result must not replace it.
        if case .writing = state { state = result }
        if let session {
            if case let .failed(message) = state {
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

    private func fail(_ message: String, session: NFCTagReaderSession) {
        state = .failed(message)
        session.invalidate(errorMessage: message)
    }

    private func found(_ tags: [NFCTag], session: NFCTagReaderSession) {
        NfcLog.log("Detected \(tags.count) tag(s): \(tags.map(NfcLog.describe).joined(separator: "; "))")
        guard tags.count == 1, let tag = tags.first else {
            session.alertMessage = "More than one tag found. Hold just one tag to the iPhone."
            session.restartPolling()
            return
        }
        guard let view else {
            fail("Nothing to write. Try again.", session: session)
            return
        }
        let classified: FoundTag
        switch tag {
        case let .miFare(miFare):
            let crcMode = self.crcMode
            classified = FoundTagKt.classifyNfcATag(uid: SwiftInteropKt.kotlinBytes(data: miFare.identifier)) {
                MiFareTransport(tag: miFare, crcMode: crcMode)
            }
        case let .iso7816(iso7816):
            classified = FoundTagKt.classifyIsoDepTag(
                uid: SwiftInteropKt.kotlinBytes(data: iso7816.identifier),
                historicalBytes: SwiftInteropKt.kotlinBytes(data: iso7816.historicalBytes ?? Data())
            ) { Iso7816Transport(tag: iso7816) }
        default:
            fail(WriteMessagesKt.UNSUPPORTED_TAG_MESSAGE, session: session)
            return
        }
        guard let writable = classified as? FoundTagWritable else {
            fail(WriteMessagesKt.UNSUPPORTED_TAG_MESSAGE, session: session)
            return
        }
        NfcLog.log("Classified as \(writable is FoundTagWaveshareIsoDep ? "Waveshare Rev2.2 (ISO-DEP)" : "Waveshare classic")")
        session.connect(to: tag) { error in
            MainActor.assumeIsolated {
                if let error {
                    NfcLog.log("Connect failed: \(error.localizedDescription)")
                    writable.transport.close()
                    self.fail("Couldn't connect to the tag. Hold it still and try again.", session: session)
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
                state = .failed("The NFC session ended before the write finished. Try again and keep the iPhone still.")
            } else if case .idle = state, (error as? NFCReaderError)?.code != .readerSessionInvalidationErrorUserCanceled {
                state = .failed(error.localizedDescription)
            }
            settle()
        }
    }
}
