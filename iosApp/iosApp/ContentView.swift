import PhotosUI
import SharedLogic
import SwiftUI

/// The write screen: choose a display and an image, preview it as the
/// display will show it, then write it to a tag.
struct ContentView: View {
    @StateObject private var writer = TagWriter()
    /// The chosen image as loaded; nil for the built-in test pattern.
    @State private var source: ColorImage?
    /// [source], or the test pattern, cropped and scaled to a display, so re-rendering it is cheap.
    @State private var fitted: Fitted?
    @State private var fitting: Task<Void, Never>?
    @State private var dither = true
    /// Where gray becomes black when dithering is off (0–255).
    @State private var threshold = Double(DitherThreshold.companion.DEFAULT_LEVEL)
    /// Whether labels are in color on displays that show it; otherwise black and white.
    @State private var color = true
    /// Whether the whole image shows, on white; otherwise it's cropped to fill the view.
    @State private var letterbox = false
    /// The image as `fitted.display` will show it; Write waits until that's the chosen display.
    @State private var view: KotlinByteArray?
    /// The preview, built once per render; body re-evaluates on every progress tick.
    @State private var previewCGImage: CGImage?
    @State private var loading: Task<Void, Never>?
    @State private var pickerItem: PhotosPickerItem?
    @State private var loadError: String?
    @AppStorage(offerReportsKey) private var offerReports = false

    /// Tests (the README screenshots) supply [writer] and [source]; the app uses the defaults.
    init(writer: TagWriter? = nil, source: ColorImage? = nil) {
        _writer = StateObject(wrappedValue: writer ?? TagWriter())
        _source = State(initialValue: source)
    }

    private var display: LabelDisplay { writer.display }

    var body: some View {
        NavigationStack {
            screen
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        NavigationLink("Advanced") { AdvancedView(writer: writer) }
                            .disabled(writer.isBusy)
                    }
                }
        }
        .onAppear(perform: fitNow)
        .onChange(of: writer.display) { fitInBackground() }
        .onChange(of: dither) { render() }
        .onChange(of: threshold) { render() }
        .onChange(of: color) { render() }
        .onChange(of: letterbox) { fitInBackground() }
        .onChange(of: pickerItem) { load(pickerItem) }
    }

    private var screen: some View {
        ScrollView {
            VStack(spacing: 16) {
                displayPicker
                preview
                HStack {
                    PhotosPicker("Choose image", selection: $pickerItem, matching: .images)
                        .buttonStyle(.bordered)
                    Toggle("Dither", isOn: $dither).toggleStyle(.button)
                    Toggle("Fit", isOn: $letterbox).toggleStyle(.button)
                    if display.palette.hasColor {
                        Toggle("Color", isOn: $color).toggleStyle(.button)
                    }
                }
                .disabled(writer.isBusy)
                if !dither {
                    VStack(spacing: 4) {
                        Text("Black below gray level \(Int(threshold))").font(.footnote)
                        Slider(value: $threshold, in: 0...255, step: 1)
                            .frame(width: 260)
                    }
                    .disabled(writer.isBusy)
                }
                if let loadError { Text(loadError).foregroundStyle(.red) }
                if !TagWriter.isNfcAvailable && !canSimulate {
                    Text("This device can't read NFC tags.").foregroundStyle(.red)
                }
                status
            }
            .padding()
        }
    }

    private var displayPicker: some View {
        HStack {
            Text("Display").font(.headline)
            Picker("Display", selection: Binding(get: { writer.display }, set: { writer.choose($0) })) {
                ForEach(LabelDisplay.entries, id: \.self) { Text($0.title).tag($0) }
            }
            .pickerStyle(.menu)
            .labelsHidden()
        }
        // Keeps the chosen display's name on one line.
        .fixedSize()
        .disabled(writer.isBusy)
    }

    @ViewBuilder private var preview: some View {
        if let image = previewCGImage {
            // Fits the tallest label (EZ Sign 2.9") without cropping; Waveshare 2.7" fills it at 220 × 330.
            Image(decorative: image, scale: 1)
                .resizable()
                .interpolation(.none)
                .aspectRatio(CGFloat(image.width) / CGFloat(image.height), contentMode: .fit)
                .frame(maxWidth: 220, maxHeight: 330)
                .border(.secondary)
                .accessibilityLabel("Preview of the tag")
        }
    }

    @ViewBuilder private var status: some View {
        switch writer.state {
        case .idle:
            writeButton("Write to tag")
        case let .writing(percent):
            ProgressView(value: Double(percent), total: 100)
            Text(WriteMessagesKt.writeProgressMessage(percent: Int32(percent), phone: "iPhone"))
        case let .done(report):
            Text(WriteMessagesKt.DONE_MESSAGE)
            HStack {
                writeButton("Write another tag")
                if offerReports { shareDetails(report) }
            }
        case let .failed(message, report):
            Text(message).foregroundStyle(.red).multilineTextAlignment(.center)
            HStack {
                writeButton("Retry")
                if let report { shareDetails(report) }
            }
        }
    }

    private func shareDetails(_ report: WriteReport) -> some View {
        NavigationLink("Share details") { ReportView(report: report) }
            .buttonStyle(.bordered)
            .disabled(writer.isBusy)
    }

    /// Whether [view] is for the chosen display; just after choosing one, it's still being fitted.
    private var viewIsForDisplay: Bool { view != nil && fitted?.display == display }

    private func writeButton(_ title: String) -> some View {
        Button(title) {
            guard let view, viewIsForDisplay else { return }
            let testPattern = source == nil
            if canSimulate {
                writer.simulate(view: view, testPattern: testPattern)
            } else {
                writer.write(view: view, testPattern: testPattern)
            }
        }
        .buttonStyle(.borderedProminent)
        .disabled(writer.isBusy || !viewIsForDisplay || !(TagWriter.isNfcAvailable || canSimulate))
    }

    /// The Simulator has no NFC, so debug builds there write to a simulated tag.
    private var canSimulate: Bool {
        #if DEBUG && targetEnvironment(simulator)
        true
        #else
        false
        #endif
    }

    /// Fits and renders the first preview at once, so the screen opens with
    /// it; the app opens on the test pattern, which is quick to make.
    private func fitNow() {
        fitted = Fitted(display: display, image: fit(source, to: display, letterbox: letterbox))
        render()
    }

    /// Fits [source] to the display off the main actor, then renders it; a
    /// later fit replaces one still running.
    private func fitInBackground() {
        let source = self.source
        let display = self.display
        let letterbox = self.letterbox
        fitting?.cancel()
        fitting = Task {
            let image = await Task.detached { fit(source, to: display, letterbox: letterbox) }.value
            guard !Task.isCancelled else { return }
            fitted = Fitted(display: display, image: image)
            render()
        }
    }

    private func render() {
        guard let fitted else { return }
        let method: Dither = dither ? DitherFloydSteinberg.shared : DitherThreshold(level: Int32(threshold))
        let rendered = LabelDisplayKt.renderView(display: fitted.display, image: fitted.image, dither: method, color: color)
        view = rendered
        previewCGImage = previewImage(rendered, display: fitted.display)
    }

    private func load(_ item: PhotosPickerItem?) {
        guard let item else { return }
        pickerItem = nil
        loading?.cancel()
        loading = Task {
            let data = try? await item.loadTransferable(type: Data.self)
            let image = await Task.detached { data.flatMap { loadImage($0) } }.value
            guard !Task.isCancelled else { return }
            if let image {
                source = image
                loadError = nil
                fitInBackground()
            } else {
                loadError = "Couldn't open that image."
            }
        }
    }
}

/// An image scaled to [display]'s view.
private struct Fitted {
    let display: LabelDisplay
    let image: ColorImage
}

/// Scales [source], cropped or [letterbox]ed, or makes the test pattern, for [display]. Scaling
/// a photo takes a while, so it runs off the main actor once the screen is up.
private func fit(_ source: ColorImage?, to display: LabelDisplay, letterbox: Bool) -> ColorImage {
    source.map { LabelDisplayKt.fitView(display: display, image: $0, letterbox: letterbox) }
        ?? ColorImage.companion.fromGray(image: TestPatternKt.testPattern(width: display.viewWidth, height: display.viewHeight))
}
