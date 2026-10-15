import PhotosUI
import SharedLogic
import SwiftUI

/// The write screen: choose an image, preview it as the panel will show it,
/// then write it to a tag.
struct ContentView: View {
    @StateObject private var writer = TagWriter()
    @State private var source: GrayImage?
    @State private var dither = true
    @State private var view: KotlinBooleanArray?
    /// The preview, built once per render; body re-evaluates on every progress tick.
    @State private var previewCGImage: CGImage?
    @State private var loading: Task<Void, Never>?
    @State private var pickerItem: PhotosPickerItem?
    @State private var loadError: String?

    private var panel: WavesharePanel { writer.panel }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Text("Waveshare 2.7\" e-paper").font(.headline)
                preview
                HStack {
                    PhotosPicker("Choose image", selection: $pickerItem, matching: .images)
                        .buttonStyle(.bordered)
                    Toggle("Dither", isOn: $dither).toggleStyle(.button)
                }
                .disabled(writer.isBusy)
                if let loadError { Text(loadError).foregroundStyle(.red) }
                if !TagWriter.isNfcAvailable && !canSimulate {
                    Text("This device can't read NFC tags.").foregroundStyle(.red)
                }
                status
                #if DEBUG
                Picker("CRC", selection: $writer.crcMode) {
                    ForEach(CrcMode.allCases) { Text("CRC \($0.rawValue)").tag($0) }
                }
                .pickerStyle(.segmented)
                .disabled(writer.isBusy)
                #endif
            }
            .padding()
        }
        .onAppear(perform: render)
        .onChange(of: dither) { render() }
        .onChange(of: pickerItem) { load(pickerItem) }
    }

    @ViewBuilder private var preview: some View {
        if let image = previewCGImage {
            Image(decorative: image, scale: 1)
                .resizable()
                .interpolation(.none)
                .aspectRatio(CGFloat(panel.viewWidth) / CGFloat(panel.viewHeight), contentMode: .fit)
                .frame(width: 220)
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
            Text("Writing \(percent)%. Keep the iPhone still.")
        case .done:
            Text("Done. The tag keeps this image without power.")
            writeButton("Write another tag")
        case let .failed(message):
            Text(message).foregroundStyle(.red).multilineTextAlignment(.center)
            writeButton("Retry")
        }
    }

    private func writeButton(_ title: String) -> some View {
        Button(title) {
            guard let view else { return }
            let frame = WaveshareFrameKt.packFrame(panel: panel, white: view)
            if canSimulate { writer.simulate(frame: frame) } else { writer.write(frame: frame) }
        }
        .buttonStyle(.borderedProminent)
        .disabled(writer.isBusy || !(TagWriter.isNfcAvailable || canSimulate))
    }

    /// The Simulator has no NFC, so debug builds there write to a simulated tag.
    private var canSimulate: Bool {
        #if DEBUG && targetEnvironment(simulator)
        true
        #else
        false
        #endif
    }

    private func render() {
        let image = source ?? TestPatternKt.testPattern(width: panel.viewWidth, height: panel.viewHeight)
        let method: Dither = dither ? DitherFloydSteinberg.shared : DitherThreshold(level: 128)
        let rendered = WaveshareFrameKt.renderView(panel: panel, image: image, dither: method)
        view = rendered
        previewCGImage = previewImage(rendered, width: Int(panel.viewWidth))
    }

    private func load(_ item: PhotosPickerItem?) {
        guard let item else { return }
        pickerItem = nil
        let panel = self.panel
        loading?.cancel()
        loading = Task {
            let data = try? await item.loadTransferable(type: Data.self)
            let image = await Task.detached { data.flatMap { loadGrayImage($0, panel: panel) } }.value
            guard !Task.isCancelled else { return }
            if let image {
                source = image
                loadError = nil
                render()
            } else {
                loadError = "Couldn't open that image."
            }
        }
    }
}
