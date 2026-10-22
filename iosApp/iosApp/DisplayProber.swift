import CoreNFC
import SharedLogic

/// "Report a display" over a Core NFC session: reads what the first tag
/// presented says about itself, writing nothing to it. Mirrors Android's
/// `ProbeController`.
@MainActor
final class DisplayProber: NSObject, ObservableObject {
    enum State: Equatable {
        case idle
        case reading
        case done(ProbeResult)
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    /// True while an NFC session is open.
    @Published private(set) var isBusy = false
    private var session: NFCTagReaderSession?
    private let crcMode: CrcMode

    init(crcMode: CrcMode) {
        self.crcMode = crcMode
    }

    /// Opens the NFC sheet and reads the first tag presented.
    func read() {
        guard session == nil else { return }
        state = .idle
        isBusy = true
        session = NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: .main)
        session?.alertMessage = "Hold the top of your iPhone against the display. KegInker reads it and writes nothing."
        NfcLog.log("Probe session starting")
        session?.begin()
    }

    /// Ends any open session, as when the screen closes.
    func cancel() {
        session?.invalidate()
    }

    private func fail(_ message: String, session: NFCTagReaderSession) {
        state = .failed(message)
        session.invalidate(errorMessage: message)
    }

    private func found(_ tags: [NFCTag], session: NFCTagReaderSession) {
        NfcLog.log("Probe detected \(tags.count) tag(s): \(tags.map(NfcLog.describe).joined(separator: "; "))")
        guard tags.count == 1, let tag = tags.first else {
            session.alertMessage = "More than one tag found. Hold just one tag to the iPhone."
            session.restartPolling()
            return
        }
        let classified = classify(tag, crcMode: crcMode)
        session.connect(to: tag) { error in
            MainActor.assumeIsolated {
                if let error {
                    NfcLog.log("Probe connect failed: \(error.localizedDescription)")
                    (classified as? FoundTagWritable)?.transport.close()
                    self.fail("Couldn't connect to the tag. Hold it still and try again.", session: session)
                    return
                }
                self.state = .reading
                Task {
                    do {
                        let result = try await DisplayProbeKt.probeDisplay(tag: classified)
                        if case .reading = self.state { self.state = .done(result) }
                        session.alertMessage = "Done."
                        session.invalidate()
                    } catch {
                        self.fail("Reading the tag was interrupted. Try again.", session: session)
                    }
                }
            }
        }
    }
}

extension DisplayProber: NFCTagReaderSessionDelegate {
    nonisolated func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
        NfcLog.log("Probe session active; waiting for a tag")
    }

    nonisolated func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        MainActor.assumeIsolated { found(tags, session: session) }
    }

    nonisolated func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: any Error) {
        MainActor.assumeIsolated {
            let code = (error as? NFCReaderError)?.code
            NfcLog.log("Probe session ended (code \(code?.rawValue ?? -1)): \(error.localizedDescription)")
            guard session === self.session else { return }
            self.session = nil
            isBusy = false
            switch state {
            case .reading:
                state = .failed("The NFC session ended before the tag was read. Try again and keep the iPhone still.")
            case .idle where code != .readerSessionInvalidationErrorUserCanceled:
                state = .failed(error.localizedDescription)
            default:
                break
            }
        }
    }
}
