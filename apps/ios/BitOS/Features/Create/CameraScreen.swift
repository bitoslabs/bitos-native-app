import AVFoundation
import SwiftUI
import UIKit

/**
 * Camera capture surface (CAP-001): AVCaptureSession preview + bounded
 * movie-file recording. The camera layer is deliberately thin — a finished
 * take is handed to the caller as Data and flows into the fully tested
 * publish pipeline; no media logic lives here.
 */
struct CameraScreen: View {
    let onCaptured: (Data, String) -> Void
    let onCancel: () -> Void
    @State private var sessionBox = CameraSessionBox()
    @State private var movieOutput = AVCaptureMovieFileOutput()
    private var session: AVCaptureSession { sessionBox.session }
    @State private var isRecording = false
    @State private var configured = false
    @State private var permissionDenied = false
    @State private var previewData: Data?
    @State private var previewMime = "video/mp4"

    var body: some View {
        if let data = previewData {
            VideoPreviewScreen(
                data: data,
                mimeType: previewMime,
                onUse: { usedData, usedMime in
                    onCaptured(usedData, usedMime)
                    previewData = nil
                },
                onRetake: { previewData = nil }
            )
        } else {
        ZStack {
            Color.black.ignoresSafeArea()
            if permissionDenied {
                VStack(spacing: BitOSTheme.Spacing.md) {
                    Text("Camera access needed").font(.headline)
                    Text("Recording requires the camera permission. You can still import videos from your library.")
                        .font(.footnote)
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .multilineTextAlignment(.center)
                    Button("Try again") { requestAccess() }
                        .buttonStyle(.borderedProminent).tint(BitOSTheme.accent)
                    Button("Back", action: onCancel).buttonStyle(.bordered)
                }
                .padding(BitOSTheme.Spacing.screen)
            } else {
                CameraPreviewLayer(session: session)
                    .ignoresSafeArea()
                VStack {
                    Spacer()
                    Button {
                        toggleRecording()
                    } label: {
                        Circle()
                            .fill(isRecording ? BitOSTheme.error : BitOSTheme.accent)
                            .frame(width: 72, height: 72)
                    }
                    .accessibilityLabel(isRecording ? "Stop recording" : "Start recording")
                    Text(isRecording ? "Tap to stop" : "Tap to record")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.7))
                    Button("Cancel", action: onCancel)
                        .buttonStyle(.bordered)
                        .padding(.top, BitOSTheme.Spacing.sm)
                }
                .padding(BitOSTheme.Spacing.lg)
            }
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
        .onAppear { requestAccess() }
        .onDisappear {
            session.stopRunning()
            if movieOutput.isRecording {
                movieOutput.stopRecording()
            }
        }
        }
    }

    private func requestAccess() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureIfNeeded()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor in
                    if granted {
                        configureIfNeeded()
                    } else {
                        permissionDenied = true
                    }
                }
            }
        default:
            permissionDenied = true
        }
    }

    private func configureIfNeeded() {
        guard !configured else {
            if !sessionBox.session.isRunning { sessionBox.startAsync() }
            return
        }
        configured = true
        session.beginConfiguration()
        session.sessionPreset = .high
        if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
           let input = try? AVCaptureDeviceInput(device: device),
           session.canAddInput(input) {
            session.addInput(input)
        }
        if session.canAddOutput(movieOutput) {
            session.addOutput(movieOutput)
        }
        session.commitConfiguration()
        sessionBox.startAsync()
    }

    private func toggleRecording() {
        if movieOutput.isRecording {
            movieOutput.stopRecording()
            isRecording = false
            return
        }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("bitos-\(Int(Date.now.timeIntervalSince1970)).mp4")
        movieOutput.startRecording(to: url, recordingDelegate: RecordingDelegate { fileURL in
            Task { @MainActor in
                isRecording = false
                if let data = try? Data(contentsOf: fileURL), !data.isEmpty,
                   data.count <= 64 * 1024 * 1024 {
                    previewData = data
                    previewMime = "video/mp4"
                }
                try? FileManager.default.removeItem(at: fileURL)
            }
        })
        isRecording = true
    }
}

/// AVCaptureVideoPreviewLayer host.
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

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
        guard error == nil else { return }
        onComplete(outputFileURL)
    }
}
