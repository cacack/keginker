import CoreNFC
import SharedLogic

/// Runs one write at a time over a Core NFC session: begin the session, wait
/// for a tag, write the frame, and report progress in the system NFC sheet.
/// The shared `WriteController` runs each write and decides how it ends, as
/// on Android; this class connects it to the session.
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

    /// The display labels are written to; remembered across launches by its name. Change it with `choose`.
    @Published private(set) var display: LabelDisplay
    static let displayKey = "labelDisplay"

    private let controller: WriteController
    private var watching: (any KotlinAutoCloseable)?
    private var session: NFCTagReaderSession?
    private var isRunning = false

    static var isNfcAvailable: Bool { NFCTagReaderSession.readingAvailable }

    override init() {
        let saved = UserDefaults.standard.string(forKey: Self.displayKey)
        let display = LabelDisplay.entries.first { $0.name == saved } ?? .waveshare2in7
        self.display = display
        controller = WriteController(display: display, imageWriter: ImageWriter())
        super.init()
        watching = SwiftInteropKt.watch(controller: controller) { [weak self] in self?.show($0) }
    }

    /// Writes to [display] from now on, and remembers it. Ignored while busy.
    func choose(_ display: LabelDisplay) {
        guard !isBusy else { return }
        controller.choose(display: display)
        self.display = display
        UserDefaults.standard.set(display.name, forKey: Self.displayKey)
        show(current)
    }

    private var current: WriteState { controller.state.value as! WriteState }

    /// Opens the NFC sheet and writes [view] (from `renderView`) to the first
    /// tag presented, packed for whichever kind of tag it is. [testPattern]
    /// says, for reports, whether it shows the built-in test pattern.
    func write(view: KotlinBooleanArray, testPattern: Bool) {
        guard !isBusy else { return }
        isBusy = true
        controller.arm(view: view, testPattern: testPattern)
        show(current)
        session = NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: .main)
        session?.alertMessage = "Hold the top of your iPhone against the tag to write the label. Keep it still until it's done."
        NfcLog.log("Session starting (CRC \(crcMode.rawValue))")
        session?.begin()
    }

    /// Writes [view] to a simulated tag that accepts everything, or to
    /// [transport] in tests. Debug and Simulator only.
    func simulate(
        view: KotlinBooleanArray,
        testPattern: Bool,
        transport: any Transport = SimulatedWaveshareTransport(commandDelayMs: 20)
    ) {
        guard !isBusy else { return }
        isBusy = true
        controller.arm(view: view, testPattern: testPattern)
        show(current)
        Task { await handle(FoundTagWaveshare(transport: transport), session: nil) }
    }

    /// Hands [tag] to the controller, then ends [session] with the outcome.
    private func handle(_ tag: FoundTag, session: NFCTagReaderSession?) async {
        isRunning = true
        // Only cancellation is thrown, and nothing here cancels.
        try? await controller.onTag(tag: tag)
        isRunning = false
        show(current)
        switch state {
        case let .failed(message, report):
            NfcLog.log("Write failed at \(report?.step?.name ?? "no step"): \(report?.reason ?? message)")
            session?.invalidate(errorMessage: message)
        case .done:
            NfcLog.log("Write succeeded")
            session?.alertMessage = "Done."
            session?.invalidate()
        case .idle, .writing:
            session?.invalidate()
        }
        settle()
    }

    /// Shows the controller's [writeState]; waiting for a tag looks idle, since the NFC sheet prompts for it.
    private func show(_ writeState: WriteState) {
        let shown: State = switch writeState {
        case let writing as WriteStateWriting: .writing(Int(writing.percent))
        case let done as WriteStateDone: .done(done.report)
        case let failed as WriteStateFailed: .failed(failed.message, failed.report)
        default: .idle
        }
        guard shown != state else { return }
        state = shown
        // A write starts at 0%, before any progress; the sheet keeps its prompt until then.
        if case let .writing(percent) = shown, percent > 0 {
            session?.alertMessage = WriteMessagesKt.writeProgressMessage(percent: Int32(percent), phone: "iPhone")
        }
    }

    /// Clears [isBusy] once nothing is left running.
    private func settle() {
        if session == nil && !isRunning { isBusy = false }
    }

    private func fail(_ message: String, reason: String?, tag: FoundTag?, session: NFCTagReaderSession) {
        controller.fail(message: message, reason: reason, tag: tag)
        show(current)
        session.invalidate(errorMessage: message)
    }

    private func found(_ tags: [NFCTag], session: NFCTagReaderSession) {
        NfcLog.log("Detected \(tags.count) tag(s): \(tags.map(NfcLog.describe).joined(separator: "; "))")
        guard tags.count == 1, let tag = tags.first else {
            session.alertMessage = "More than one tag found. Hold just one tag to the iPhone."
            session.restartPolling()
            return
        }
        let classified = classify(tag, crcMode: crcMode)
        switch classified {
        case let writable as FoundTagWritable:
            NfcLog.log("Classified as \(writable is FoundTagWaveshareIsoDep ? "Waveshare Rev2.2 (ISO-DEP)" : "Waveshare classic")")
        case let unsupported as FoundTagUnsupported where unsupported.kind == .isoDep && display.isEzSign:
            // An EZ Sign display is found as an unsupported ISO-DEP tag; the controller identifies it.
            NfcLog.log("Classified as ISO-DEP; identifying it as \(display.name)")
        default:
            Task { await handle(classified, session: session) }
            return
        }
        session.connect(to: tag) { error in
            MainActor.assumeIsolated {
                if let error {
                    NfcLog.log("Connect failed: \(error.localizedDescription)")
                    (classified as? FoundTagWritable)?.transport.close()
                    let message = "Couldn't connect to the tag. Hold it still and try again."
                    self.fail(message, reason: error.localizedDescription, tag: classified, session: session)
                    return
                }
                NfcLog.log("Connected")
                Task { await self.handle(classified, session: session) }
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
            if current is WriteStateWriting {
                // During an EZ Sign refresh the controller says instead that the refresh wasn't confirmed.
                let message = "The NFC session ended before the write finished. Try again and keep the iPhone still."
                controller.fail(message: message, reason: error.localizedDescription, tag: nil)
            } else if current is WriteStateWaiting {
                if (error as? NFCReaderError)?.code == .readerSessionInvalidationErrorUserCanceled {
                    controller.cancel()
                } else {
                    controller.fail(message: error.localizedDescription, reason: nil, tag: nil)
                }
            }
            show(current)
            settle()
        }
    }
}
