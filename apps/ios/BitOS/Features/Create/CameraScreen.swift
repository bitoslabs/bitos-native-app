import AVFoundation
import BusinessCore
import SwiftUI
import UIKit

/**
 * Camera capture surface (CAP-002, spec §3.19): AVCaptureSession preview +
 * bounded movie-file recording behind the reference record-screen chrome
 * (`docs/ui/app-app-04-create-camera-editor.html` — grid guides, REC badge,
 * lens zoom rail, torch/self-timer, session takes strip, morphing record
 * button, permission-declined state).
 *
 * The camera layer stays deliberately thin: takes live only in this screen's
 * state; the one a publisher chooses is handed to the caller as Data and
 * flows into the fully tested publish pipeline. No media logic lives here.
 */
struct CameraScreen: View {
    let onCaptured: (Data, String) -> Void
    let onImport: () -> Void
    /// Quick MEM: the latest take (data, mime), or nil to open the editor empty.
    let onOpenMeme: ((Data, String)?) -> Void
    /// M5 take-native handoff: every take as its own timeline clip.
    var onOpenMemeList: ([(Data, String)]) -> Void = { _ in }
    let onCancel: () -> Void

    @State private var sessionBox = CameraSessionBox()
    @State private var movieOutput = AVCaptureMovieFileOutput()
    private var session: AVCaptureSession { sessionBox.session }
    @State private var isRecording = false
    @State private var finalizing = false
    @State private var configured = false
    @State private var permissionDenied = false
    @State private var requestPending = true
    @State private var lensPosition: AVCaptureDevice.Position = .back
    @State private var torchOn = false
    @State private var flashAvailable = false
    @State private var gridOn = true
    @State private var mirrorNextTake = false
    @State private var recordingMirrored = false
    @State private var zoomRatio: CGFloat = 1
    @State private var timerMode: CaptureTimerMode = .off
    @State private var countdown: Int?
    @State private var recordStarted = Date()
    @State private var elapsedSeconds: Double = 0
    @State private var ticker: Task<Void, Never>?
    @State private var countdownTask: Task<Void, Never>?
    @State private var takes: [PendingTake] = []
    @State private var previewId: Int?
    @State private var hint: String?
    @State private var confirmDiscard = false
    @State private var merging = false

    private var activeTake: PendingTake? { takes.first { $0.id == previewId } }

    /// Body content split out — the inline Group branch over the trim
    /// preview exceeded the type-checker's expression budget.
    @ViewBuilder
    private var cameraContent: some View {
        if let take = activeTake {
            VideoPreviewScreen(
                data: take.data,
                mimeType: take.mime,
                initialMirrored: take.mirrored,
                onUse: { data, mime in onCaptured(data, mime) },
                onRetake: {
                    takes.removeAll { $0.id == take.id }
                    previewId = nil
                },
                onBack: { previewId = nil }
            )
        } else {
            cameraSurface
        }
    }

    var body: some View {
        Group {
            cameraContent
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
        .onAppear { requestAccess() }
        .onDisappear {
            ticker?.cancel()
            countdownTask?.cancel()
            session.stopRunning()
            if movieOutput.isRecording {
                movieOutput.stopRecording()
            }
        }
        .onChange(of: torchOn) { _, on in applyTorch(on) }
        .onChange(of: zoomRatio) { _, ratio in applyZoom(ratio) }
        .onChange(of: lensPosition) { _, _ in
            torchOn = false
            configure(position: lensPosition)
        }
        .alert("Discard takes?", isPresented: $confirmDiscard) {
            Button("Discard", role: .destructive, action: onCancel)
            Button("Keep", role: .cancel) {}
        } message: {
            Text("\(takes.count) recorded take\(takes.count == 1 ? "" : "s") in this session would be lost.")
        }
    }

    // MARK: Camera surface

    private var cameraSurface: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if permissionDenied {
                PermissionDeclinedContent(
                    onOpenSettings: openSystemSettings,
                    onImport: onImport,
                    onRetry: {
                        requestPending = true
                        permissionDenied = false
                        requestAccess()
                    }
                )
            } else if configured {
                CameraPreviewLayer(session: session)
                    .scaleEffect(x: mirrorNextTake ? -1 : 1, y: 1)
                    .ignoresSafeArea()
                if gridOn {
                    GridGuides()
                        .allowsHitTesting(false)
                }
                chrome
            }
            if let message = hint {
                VStack {
                    Spacer()
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.warningText)
                        .padding(.horizontal, BitOSTheme.Spacing.base)
                        .padding(.bottom, 320)
                }
                .allowsHitTesting(false)
            }
            if merging {
                Color.black.opacity(0.7).ignoresSafeArea()
                VStack(spacing: BitOSTheme.Spacing.md) {
                    ProgressView().tint(BitOSTheme.accent)
                    Text("Combining \(takes.count) takes…")
                        .font(.footnote)
                        .foregroundStyle(.white)
                }
                .allowsHitTesting(true)
            }
        }
    }

    private var chrome: some View {
        VStack(spacing: 0) {
            topBar
            if isRecording {
                RecBadge(seconds: elapsedSeconds)
                    .padding(.top, 56)
            }
            Spacer()
            bottomScrim
        }
        .overlay {
            HStack {
                Spacer()
                ZoomRail(active: zoomRatio) { zoomRatio = $0 }
                    .padding(.trailing, BitOSTheme.Spacing.base)
            }
            if let remaining = countdown, remaining > 0 {
                Text("\(remaining)")
                    .font(.system(size: 72, weight: .bold))
                    .foregroundStyle(.white)
            }
        }
    }

    private var topBar: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            CircleControl(accessibilityLabel: "Close camera") {
                if takes.isEmpty {
                    onCancel()
                } else {
                    confirmDiscard = true
                }
            } icon: {
                AppIcons.image(for: AppIcons.close)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(.white)
            }
            Spacer()
            if flashAvailable {
                CircleControl(accessibilityLabel: torchOn ? "Torch on" : "Torch off") {
                    torchOn.toggle()
                } icon: {
                    AppIcons.image(for: AppIcons.torch)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(torchOn ? BitOSTheme.warning : .white)
                }
            }
            CircleControl(accessibilityLabel: mirrorNextTake ? "Mirror next take on" : "Mirror next take off") {
                if !isRecording { mirrorNextTake.toggle() }
            } icon: {
                AnyView(Text("↔")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(mirrorNextTake ? BitOSTheme.accent : .white))
            }
            CircleControl(accessibilityLabel: gridOn ? "Grid guides on" : "Grid guides off") {
                gridOn.toggle()
            } icon: {
                AppIcons.image(for: AppIcons.gridGuides)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(gridOn ? BitOSTheme.accent : .white)
            }
            TimerChip(mode: timerMode) { timerMode = $0 }
        }
        .padding(.horizontal, BitOSTheme.Spacing.base)
        .padding(.top, BitOSTheme.Spacing.md)
    }

    private var bottomScrim: some View {
        VStack(spacing: BitOSTheme.Spacing.base) {
            TakesStrip(
                takes: takes,
                onTapTake: { previewId = $0.id },
                onDeleteTake: { id in takes.removeAll { $0.id == id } },
                onAddTake: { if !isRecording { pressRecord() } }
            )
            captureControls
            CameraBottomActions(
                canEditTakes: !takes.isEmpty,
                onFlip: {
                    if isRecording { stopRecording() }
                    lensPosition = lensPosition == .back ? .front : .back
                },
                onEditTakes: { if let last = takes.last { previewId = last.id } }
            )
        }
        .padding(.horizontal, BitOSTheme.Spacing.base)
        .padding(.bottom, BitOSTheme.Spacing.lg)
        .frame(maxWidth: .infinity)
        .background(
            LinearGradient(
                colors: [.clear, .black.opacity(0.85)],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    private var captureControls: some View {
        HStack {
            RoundedRectangle(cornerRadius: 12)
                .fill(
                    LinearGradient(
                        colors: [Color(hex: 0x2858C8), Color(hex: 0x06102A)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(.white.opacity(0.25), lineWidth: 1)
                )
                .overlay(
                    AppIcons.image(for: AppIcons.photo)
                        .font(.system(size: 19, weight: .medium))
                        .foregroundStyle(.white)
                )
                .frame(width: 48, height: 48)
                .accessibilityLabel("Import from library")
                .onTapGesture(perform: onImport)

            Spacer()

            RecordButton(isRecording: isRecording) { pressRecord() }

            Spacer()

            RoundedRectangle(cornerRadius: 12)
                .fill(Color.black.opacity(0.4))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(.white.opacity(0.25), lineWidth: 1)
                )
                .overlay(
                    Text("MEM")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                )
                .frame(width: 48, height: 48)
                .accessibilityLabel("Open the MEM expert editor with your takes")
                .onTapGesture { openMemeWithAllTakes() }
        }
    }

    // MARK: Capture flow

    /// M5 take-native handoff: EVERY recorded take enters the studio as its
    /// own timeline clip (strip order) — no merge re-encode; per-take
    /// trim/effects stay possible in the editor.
    private func openMemeWithAllTakes() {
        guard !takes.isEmpty else { return }
        onOpenMemeList(takes.map { ($0.data, $0.mime) })
    }

    private func pressRecord() {
        if isRecording {
            stopRecording()
        } else if timerMode != .off {
            countdownTask?.cancel()
            countdown = timerMode.seconds
            countdownTask = Task { @MainActor in
                while let left = countdown, left > 0 {
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                    guard !Task.isCancelled, let still = countdown else { return }
                    countdown = still - 1
                }
                if countdown == 0 {
                    countdown = nil
                    beginRecording()
                }
            }
        } else {
            beginRecording()
        }
    }

    private func beginRecording() {
        guard !finalizing else {
            hint = "Finishing previous take…"
            return
        }
        guard takes.count < CameraCaptureLimits.maxPendingTakes else {
            hint = "Take limit reached — review or delete before recording another"
            return
        }
        recordingMirrored = mirrorNextTake
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("bitos-\(Int(Date.now.timeIntervalSince1970)).mp4")
        movieOutput.startRecording(to: url, recordingDelegate: RecordingDelegate { [weak movieOutput] fileURL in
            Task { @MainActor in
                finishTake(at: fileURL, duration: movieOutput?.recordedDuration.seconds ?? 0)
            }
        })
        withAnimation(.easeInOut(duration: 0.2)) { isRecording = true }
        recordStarted = Date()
        elapsedSeconds = 0
        startTicker()
    }

    private func stopRecording() {
        movieOutput.stopRecording()
        withAnimation(.easeInOut(duration: 0.2)) { isRecording = false }
        ticker?.cancel()
        ticker = nil
        finalizing = true
    }

    private func startTicker() {
        ticker?.cancel()
        ticker = Task { @MainActor in
            while !Task.isCancelled {
                elapsedSeconds = Date().timeIntervalSince(recordStarted)
                if elapsedSeconds >= CameraCaptureLimits.recordCapSeconds {
                    stopRecording()
                    return
                }
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
    }

    private func finishTake(at fileURL: URL, duration: Double) {
        finalizing = false
        ticker?.cancel()
        ticker = nil
        // The output can also stop itself (3:00 cap / low-disk reserve);
        // settle the chrome in that path too.
        withAnimation(.easeInOut(duration: 0.2)) { isRecording = false }
        Task.detached(priority: .userInitiated) {
            let loaded: (Data, UIImage?)? = await Self.loadTake(from: fileURL)
            await MainActor.run {
                if let (data, thumbnail) = loaded {
                    let take = PendingTake(
                        id: Int(Date().timeIntervalSince1970 * 1000),
                        data: data,
                        mime: "video/mp4",
                        duration: max(duration, 0),
                        thumbnail: thumbnail,
                        mirrored: recordingMirrored
                    )
                    let bufferedBytes = takes.reduce(0, { $0 + $1.data.count }) + data.count
                    if bufferedBytes > CameraCaptureLimits.maxPendingBytes {
                        hint = "Take exceeds the session buffer — use or delete earlier takes"
                    } else {
                        withAnimation(.easeOut(duration: 0.2)) { takes.append(take) }
                    }
                } else {
                    hint = "Take failed or exceeded the \(Blossom.shared.MAX_FILE_BYTES / (1024 * 1024))MB publish cap"
                }
                try? FileManager.default.removeItem(at: fileURL)
            }
        }
    }

    /// Reads a finished take on a background executor with the publish-size
    /// bound plus a small poster frame.
    private static func loadTake(from url: URL) async -> (Data, UIImage?)? {
        return await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                // The thumbnail session is created off-main so no non-Sendable
                // value crosses the actor boundary.
                let thumbSession = AVCaptureVideoPreviewThumbnailSession(url: url)
                guard let data = try? Data(contentsOf: url), !data.isEmpty,
                      data.count <= Int(Blossom.shared.MAX_FILE_BYTES) else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: (data, thumbSession.thumbnail()))
            }
        }
    }

    // MARK: Session configuration

    private func requestAccess() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureIfNeeded()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor in
                    requestPending = false
                    if granted {
                        configureIfNeeded()
                    } else {
                        permissionDenied = true
                    }
                }
            }
        default:
            requestPending = false
            permissionDenied = true
        }
    }

    private func configureIfNeeded() {
        guard !configured else {
            if !sessionBox.session.isRunning { sessionBox.startAsync() }
            return
        }
        configured = true
        // Duration cap and the low-storage reserve (reference scr-camera):
        // recording stops safely before corruption instead of truncating.
        movieOutput.maxRecordedDuration = CMTime(seconds: CameraCaptureLimits.recordCapSeconds, preferredTimescale: 600)
        movieOutput.minFreeDiskSpaceLimit = 1_200_000_000
        configure(position: lensPosition)
    }

    private func configure(position: AVCaptureDevice.Position) {
        session.beginConfiguration()
        session.sessionPreset = .high
        session.inputs.forEach { session.removeInput($0) }
        if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position),
           let input = try? AVCaptureDeviceInput(device: device),
           session.canAddInput(input) {
            session.addInput(input)
            flashAvailable = device.hasFlash
        } else {
            flashAvailable = false
        }
        if !session.outputs.contains(movieOutput), session.canAddOutput(movieOutput) {
            session.addOutput(movieOutput)
        }
        session.commitConfiguration()
        sessionBox.startAsync()
        applyZoom(zoomRatio)
        applyTorch(torchOn)
    }

    private func applyTorch(_ on: Bool) {
        guard let device = currentInputDevice(), device.hasFlash else { return }
        do {
            try device.lockForConfiguration()
            device.torchMode = on && device.isTorchModeSupported(.on) ? .on : .off
            device.unlockForConfiguration()
        } catch {
            // Torch is best-effort chrome; leave the previous mode.
        }
    }

    private func applyZoom(_ ratio: CGFloat) {
        guard let device = currentInputDevice() else { return }
        do {
            try device.lockForConfiguration()
            device.videoZoomFactor = ratio
                .clamped(to: device.minAvailableVideoZoomFactor...device.maxAvailableVideoZoomFactor)
            device.unlockForConfiguration()
        } catch {
            // Zoom is best-effort chrome; keep the device's current factor.
        }
    }

    private func currentInputDevice() -> AVCaptureDevice? {
        (session.inputs.compactMap { ($0 as? AVCaptureDeviceInput)?.device }).first
    }

    private func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

// MARK: - Capture models

private enum CaptureTimerMode: CaseIterable {
    case off, s3, s10

    var label: String {
        switch self {
        case .off: return "Off"
        case .s3: return "3s"
        case .s10: return "10s"
        }
    }

    var seconds: Int {
        switch self {
        case .off: return 0
        case .s3: return 3
        case .s10: return 10
        }
    }

    var next: CaptureTimerMode {
        switch self {
        case .off: return .s3
        case .s3: return .s10
        case .s10: return .off
        }
    }
}

private enum CameraCaptureLimits {
    static let recordCapSeconds: Double = 3 * 60
    static let maxPendingTakes = 5
    static let maxPendingBytes = 96 * 1024 * 1024
}

private struct PendingTake: Identifiable {
    let id: Int
    let data: Data
    let mime: String
    let duration: Double
    let thumbnail: UIImage?
    let mirrored: Bool
}

// MARK: - Chrome pieces

/// Translucent 48 pt circular control over the preview (reference top bar).
private struct CircleControl: View {
    let accessibilityLabel: String
    let action: () -> Void
    @ViewBuilder let icon: () -> AnyView

    init(
        accessibilityLabel: String,
        action: @escaping () -> Void,
        @ViewBuilder icon: () -> some View
    ) {
        self.accessibilityLabel = accessibilityLabel
        self.action = action
        self.icon = { AnyView(icon()) }
    }

    var body: some View {
        Circle()
            .fill(Color.black.opacity(0.4))
            .overlay(Circle().strokeBorder(.white.opacity(0.25), lineWidth: 1))
            .frame(width: 48, height: 48)
            .overlay(icon())
            .accessibilityLabel(accessibilityLabel)
            .onTapGesture(perform: action)
    }
}

/// Self-timer chip: cycles off → 3 s → 10 s (reference timer toast).
private struct TimerChip: View {
    let mode: CaptureTimerMode
    let onCycle: (CaptureTimerMode) -> Void

    var body: some View {
        HStack(spacing: 4) {
            AppIcons.image(for: AppIcons.timer)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(mode == .off ? .white : BitOSTheme.accent)
            if mode != .off {
                Text(mode.label)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(Capsule().fill(Color.black.opacity(0.4)))
        .overlay(Capsule().strokeBorder(.white.opacity(0.25), lineWidth: 1))
        .accessibilityLabel("Self-timer \(mode.label)")
        .onTapGesture { onCycle(mode.next) }
    }
}

/// REC badge: blinking dot + monospace elapsed clock (reference recBadge).
private struct RecBadge: View {
    let seconds: Double
    @State private var blink = false

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(BitOSTheme.error)
                .frame(width: 8, height: 8)
                .opacity(blink ? 1 : 0.25)
                .animation(.easeInOut(duration: 0.55).repeatForever(autoreverses: true), value: blink)
            Text(CaptureClock.format(seconds))
                .font(.system(size: 11, weight: .medium, design: .monospaced))
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Capsule().fill(Color.black.opacity(0.5)))
        .overlay(Capsule().strokeBorder(.white.opacity(0.25), lineWidth: 1))
        .onAppear { blink = true }
        .accessibilityLabel("Recording \(CaptureClock.format(seconds))")
    }
}

/// Lens zoom rail on the right edge: .5× / 1× / 2× (reference lens switch).
private struct ZoomRail: View {
    let active: CGFloat
    let onSelect: (CGFloat) -> Void

    var body: some View {
        VStack(spacing: 10) {
            ForEach([CGFloat(0.5), 1, 2], id: \.self) { ratio in
                let label = ratio == 0.5 ? ".5×" : ratio == 1 ? "1×" : "2×"
                Text(label)
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(active == ratio ? Color(hex: 0x1A1000) : .white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(
                        Capsule().fill(active == ratio ? BitOSTheme.accent : Color.black.opacity(0.4))
                    )
                    .overlay(
                        Capsule().strokeBorder(
                            active == ratio ? BitOSTheme.accent : Color.white.opacity(0.25),
                            lineWidth: 1
                        )
                    )
                    .accessibilityLabel("Zoom \(label)")
                    .onTapGesture { onSelect(ratio) }
            }
        }
    }
}

/// Session takes strip: thumbnails with delete, dashed "+" and summary.
private struct TakesStrip: View {
    let takes: [PendingTake]
    let onTapTake: (PendingTake) -> Void
    let onDeleteTake: (Int) -> Void
    let onAddTake: () -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(takes.enumerated()), id: \.element.id) { index, take in
                    takeTile(index: index, take: take)
                }
                RoundedRectangle(cornerRadius: 6)
                    .strokeBorder(
                        Color.white.opacity(0.4),
                        style: StrokeStyle(lineWidth: 1, dash: [4, 4])
                    )
                    .frame(width: 40, height: 44)
                    .overlay(Text("+").font(.system(size: 18)).foregroundStyle(.white.opacity(0.6)))
                    .accessibilityLabel("Record the next take")
                    .onTapGesture(perform: onAddTake)
                if !takes.isEmpty {
                    Text("\(takes.count) take\(takes.count == 1 ? "" : "s") · \(CaptureClock.format(takes.reduce(0) { $0 + $1.duration }))")
                        .font(.system(size: 9, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.6))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func takeTile(index: Int, take: PendingTake) -> some View {
        ZStack {
            if let thumbnail = take.thumbnail {
                Image(uiImage: thumbnail)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 56, height: 44)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
            } else {
                RoundedRectangle(cornerRadius: 6)
                    .fill(Color(hex: 0x2858C8).opacity(0.2))
            }
            Text("\(index + 1)")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(.white.opacity(take.thumbnail != nil ? 0.9 : 0.7))
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    Text(CaptureClock.format(take.duration))
                        .font(.system(size: 7, design: .monospaced))
                        .foregroundStyle(.white)
                        .padding(2)
                        .background(Color.black.opacity(0.53).cornerRadius(2))
                }
            }
        }
        .frame(width: 56, height: 44)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(alignment: .topTrailing) {
            Circle()
                .fill(Color.black.opacity(0.7))
                .frame(width: 16, height: 16)
                .overlay(
                    AppIcons.image(for: AppIcons.close)
                        .font(.system(size: 8, weight: .bold))
                        .foregroundStyle(.white)
                )
                .offset(x: 6, y: -6)
                .accessibilityLabel("Delete take \(index + 1)")
                .onTapGesture { onDeleteTake(take.id) }
        }
        .accessibilityLabel("Review take \(index + 1)")
        .onTapGesture { onTapTake(take) }
    }
}

/// 76 pt capture button: white ring with a red core that morphs into a
/// rounded stop-square while recording (reference recBtn).
private struct RecordButton: View {
    let isRecording: Bool
    let onToggle: () -> Void

    var body: some View {
        ZStack {
            Circle()
                .strokeBorder(.white.opacity(0.9), lineWidth: 5)
            RoundedRectangle(cornerRadius: isRecording ? 6 : 29)
                .fill(BitOSTheme.error)
                .frame(width: isRecording ? 26 : 58, height: isRecording ? 26 : 58)
        }
        .frame(width: 76, height: 76)
        .accessibilityLabel(isRecording ? "Stop recording" : "Start recording")
        .onTapGesture(perform: onToggle)
    }
}

/// Flip · Edit takes · duration cap row (reference bottom actions).
private struct CameraBottomActions: View {
    let canEditTakes: Bool
    let onFlip: () -> Void
    let onEditTakes: () -> Void

    var body: some View {
        HStack(spacing: 32) {
            bottomAction("Flip", label: "Flip lens", enabled: true, action: onFlip) {
                AppIcons.image(for: AppIcons.cameraRotate)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(.white.opacity(0.7))
            }
            bottomAction("Edit takes", label: "Edit takes", enabled: canEditTakes, action: onEditTakes) {
                AppIcons.image(for: AppIcons.pen)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(.white.opacity(canEditTakes ? 0.7 : 0.3))
            }
            VStack(spacing: 4) {
                AppIcons.image(for: AppIcons.clockCircle)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(.white.opacity(0.4))
                Text("3:00 cap")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(.white.opacity(0.4))
            }
        }
    }

    private func bottomAction(
        _ title: String,
        label: String,
        enabled: Bool,
        action: @escaping () -> Void,
        @ViewBuilder icon: () -> some View
    ) -> some View {
        VStack(spacing: 4) {
            icon()
            Text(title)
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(.white.opacity(enabled ? 0.7 : 0.3))
        }
        .opacity(enabled ? 1 : 0.55)
        .accessibilityLabel(label)
        .onTapGesture { if enabled { action() } }
    }
}

/// Permission-declined state (reference scr-perm).
private struct PermissionDeclinedContent: View {
    let onOpenSettings: () -> Void
    let onImport: () -> Void
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
            AppIcons.image(for: AppIcons.camera)
                .font(.system(size: 30, weight: .medium))
                .foregroundStyle(BitOSTheme.error)
                .frame(width: 80, height: 80)
                .background(BitOSTheme.error.opacity(0.12), in: RoundedRectangle(cornerRadius: 24))
            Text("Camera permission declined")
                .font(.headline)
            Text(
                "Recording needs the camera, and we only ever ask from the record screen — never at launch. "
                    + "You can still create with media you already have."
            )
            .font(.footnote)
            .foregroundStyle(BitOSTheme.textSecondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, BitOSTheme.Spacing.base)
            Button("Open system settings", action: onOpenSettings)
                .buttonStyle(.borderedProminent)
                .tint(BitOSTheme.accent)
                .clipShape(Capsule())
            Button("Import from library instead", action: onImport)
                .buttonStyle(.bordered)
            Button("Try the permission request again", action: onRetry)
                .font(.footnote)
                .foregroundStyle(BitOSTheme.textTertiary)
                .underline()
        }
        .padding(BitOSTheme.Spacing.screen)
    }
}

/// Rule-of-thirds guides (reference grid overlay).
private struct GridGuides: View {
    var body: some View {
        GeometryReader { proxy in
            let width = proxy.size.width
            let height = proxy.size.height
            Path { path in
                for i in 1...2 {
                    let x = width * CGFloat(i) / 3
                    path.move(to: CGPoint(x: x, y: 0))
                    path.addLine(to: CGPoint(x: x, y: height))
                    let y = height * CGFloat(i) / 3
                    path.move(to: CGPoint(x: 0, y: y))
                    path.addLine(to: CGPoint(x: width, y: y))
                }
            }
            .stroke(Color.white.opacity(0.07), lineWidth: 1)
        }
        .ignoresSafeArea()
    }
}

/// mm:ss clock for badges, take durations and summaries.
private enum CaptureClock {
    static func format(_ seconds: Double) -> String {
        let total = max(0, Int(seconds.rounded(.down)))
        return String(format: "%02d:%02d", total / 60, total % 60)
    }
}

extension Comparable {
    fileprivate func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

/// Poster-frame extraction for a finished take (used by the strip tiles).
private final class AVCaptureVideoPreviewThumbnailSession {
    private let generator: AVAssetImageGenerator

    init(url: URL) {
        let asset = AVURLAsset(url: url)
        generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 192, height: 192)
    }

    func thumbnail() -> UIImage? {
        guard let cgImage = try? generator.copyCGImage(at: .zero, actualTime: nil) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

/// AVCaptureSession preview host.
/// AVCaptureSession container outside main-actor isolation (the session is
/// documented thread-safe for configuration and start/stop).
final class CameraSessionBox: @unchecked Sendable {
    let session = AVCaptureSession()

    func startAsync() {
        nonisolated(unsafe) let session = self.session
        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
    }
}

private struct CameraPreviewLayer: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)
        preview.frame = UIScreen.main.bounds
        DispatchQueue.main.async {
            preview.frame = view.bounds
        }
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        uiView.layer.sublayers?.first?.frame = uiView.bounds
    }
}

/// Recording completion: surfaces the finished file URL.
private final class RecordingDelegate: NSObject, AVCaptureFileOutputRecordingDelegate {
    let onComplete: (URL) -> Void

    init(onComplete: @escaping (URL) -> Void) {
        self.onComplete = onComplete
    }

    func fileOutput(
        _ output: AVCaptureFileOutput,
        didFinishRecordingTo outputFileURL: URL,
        from connections: [AVCaptureConnection],
        error: Error?
    ) {
        guard error == nil else { return }
        onComplete(outputFileURL)
    }
}
