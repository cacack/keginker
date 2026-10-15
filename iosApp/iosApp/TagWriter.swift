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
    @Published var crcMode: CrcMode = .auto

    let panel = WavesharePanel.epd2in7
    private var session: NFCTagReaderSession?
    private var isRunning = false
    private var frame: KotlinByteArray?

    static var isNfcAvailable: Bool { NFCTagReaderSession.readingAvailable }

    /// Opens the NFC sheet and writes [frame] to the first tag presented.
    func write(frame: KotlinByteArray) {
        guard !isBusy else { return }
        isBusy = true
        self.frame = frame
        state = .idle
        session = NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: .main)
        session?.alertMessage = "Hold the top of your iPhone against the tag and keep it still."
        session?.begin()
    }

    /// Writes [frame] to a simulated tag that accepts everything. Debug and Simulator only.
    func simulate(frame: KotlinByteArray) {
        guard !isBusy else { return }
        isBusy = true
        Task { await run(SimulatedWaveshareTransport(commandDelayMs: 20), frame: frame, session: nil) }
    }

    private func run(_ transport: Transport, frame: KotlinByteArray, session: NFCTagReaderSession?) async {
        isRunning = true
        state = .writing(0)
        let writer = WaveshareWriter(transport: transport)
        let result: State
        do {
            let outcome = try await writer.write(panel: panel, frame: frame) { percent in
                Task { @MainActor in self.progress(percent.intValue, session: session) }
            }
            if let failure = outcome as? WriteResultFailure {
                result = .failed(failure.userMessage)
            } else {
                result = .done
            }
        } catch {
            result = .failed("The write failed unexpectedly: \(error.localizedDescription)")
        }
        transport.close()
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
        session?.alertMessage = "Writing \(percent)%. Keep the iPhone still."
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
        guard tags.count == 1, let tag = tags.first else {
            session.alertMessage = "More than one tag found. Hold just one tag to the iPhone."
            session.restartPolling()
            return
        }
        guard let frame else {
            fail("Nothing to write. Try again.", session: session)
            return
        }
        guard case let .miFare(miFare) = tag else {
            fail(WriteMessagesKt.UNSUPPORTED_TAG_MESSAGE, session: session)
            return
        }
        let crcMode = self.crcMode
        let classified = FoundTagKt.classifyTag(uid: SwiftInteropKt.kotlinBytes(data: miFare.identifier)) {
            MiFareTransport(tag: miFare, crcMode: crcMode)
        }
        guard let waveshare = classified as? FoundTagWaveshare else {
            fail(WriteMessagesKt.UNSUPPORTED_TAG_MESSAGE, session: session)
            return
        }
        session.connect(to: tag) { error in
            MainActor.assumeIsolated {
                if error != nil {
                    waveshare.transport.close()
                    self.fail("Couldn't connect to the tag. Hold it still and try again.", session: session)
                    return
                }
                Task { await self.run(waveshare.transport, frame: frame, session: session) }
            }
        }
    }
}

extension TagWriter: NFCTagReaderSessionDelegate {
    nonisolated func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {}

    nonisolated func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        MainActor.assumeIsolated { found(tags, session: session) }
    }

    nonisolated func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: any Error) {
        MainActor.assumeIsolated {
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
