import AVFoundation
import AVKit
import SwiftUI

/**
 * Recorded-take preview with trim (CAP-003/004): playback + start/end trim
 * sliders plus an optional left-to-right mirror. "Use this" exports the
 * selected edits together, so the visible review result is the video that
 * enters the publishing pipeline.
 */
struct VideoPreviewScreen: View {
    let data: Data
    let mimeType: String
    var initialMirrored: Bool = false
    let onUse: (Data, String) -> Void
    let onRetake: () -> Void
    var onBack: (() -> Void)? = nil
    @State private var player: AVPlayer?
    @State private var durationSeconds: Double = 0
    @State private var trimStart: Double = 0
    @State private var trimEnd: Double = 0
    @State private var hasTrimmed = false
    @State private var mirrored = false
    @State private var exporting = false
    @State private var exportError: String?
    @State private var tempURL: URL?

    var body: some View {
        if exporting {
            VStack(spacing: BitOSTheme.Spacing.md) {
                ProgressView().tint(BitOSTheme.accent)
                Text("Trimming…").font(.subheadline).foregroundStyle(.white)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black)
            .returning()
        } else {
            content
        }
    }

    private var content: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let player {
                VideoPlayer(player: player)
                    .scaleEffect(x: mirrored ? -1 : 1, y: 1)
                    .ignoresSafeArea()
            }
            VStack {
                Spacer()
                if let onBack {
                    Button("Back to takes", action: onBack)
                        .buttonStyle(.bordered)
                }
                if durationSeconds > 0 {
                    VStack(spacing: 4) {
                        Text("Trim: \(String(format: "%.1f", trimStart))s – \(String(format: "%.1f", trimEnd))s")
                            .font(.caption2)
                            .foregroundStyle(.white.opacity(0.7))
                        HStack(spacing: 8) {
                            Text("Start").font(.caption2).foregroundStyle(.white.opacity(0.5))
                            Slider(value: $trimStart, in: 0...max(trimEnd - 0.5, 0.5)) { editing in
                                if !editing {
                                    hasTrimmed = true
                                    player?.seek(to: CMTime(seconds: trimStart, preferredTimescale: 600))
                                }
                            }
                        }
                        HStack(spacing: 8) {
                            Text("End").font(.caption2).foregroundStyle(.white.opacity(0.5))
                            Slider(value: $trimEnd, in: trimStart + 0.5...max(durationSeconds, trimStart + 0.5)) { editing in
                                if !editing {
                                    hasTrimmed = true
                                    player?.seek(to: CMTime(seconds: max(trimEnd - 0.5, trimStart), preferredTimescale: 600))
                                }
                            }
                        }
                    }
                    .padding(.horizontal, BitOSTheme.Spacing.base)
                }
                Button(mirrored ? "Mirrored" : "Mirror") {
                    mirrored.toggle()
                    player?.seek(to: CMTime(seconds: trimStart, preferredTimescale: 600))
                }
                .buttonStyle(.bordered)
                Text(mirrored ? "Video will be flipped left to right" : "Mirror reverses the final video left to right")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.7))
                HStack(spacing: BitOSTheme.Spacing.md) {
                    Button("Retake", action: onRetake)
                        .buttonStyle(.bordered)
                    Button(hasTrimmed || mirrored ? "Use edited video" : "Use this") {
                        if hasTrimmed || mirrored {
                            exportEditedVideo()
                        } else {
                            onUse(data, mimeType)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
                }
                if let exportError {
                    Text(exportError)
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.error)
                }
                Text("\(data.count / 1024 / 1024)MB" + (hasTrimmed ? " → ~\(Int(Double(trimEnd - trimStart) / durationSeconds * Double(data.count)) / 1024 / 1024)MB trimmed" : ""))
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.7))
            }
            .padding(BitOSTheme.Spacing.lg)
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
        .onAppear(perform: setup)
        .onAppear { mirrored = initialMirrored }
        .onDisappear {
            player?.pause()
            player = nil
            if let url = tempURL { try? FileManager.default.removeItem(at: url) }
        }
    }

    private func setup() {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("bitos-preview-\(Int(Date.now.timeIntervalSince1970)).mp4")
        try? data.write(to: url)
        tempURL = url

        let asset = AVURLAsset(url: url)
        let item = AVPlayerItem(asset: asset)
        let queuePlayer = AVQueuePlayer()
        _ = AVPlayerLooper(player: queuePlayer, templateItem: item)
        queuePlayer.play()
        player = queuePlayer

        Task {
            let duration = try? await asset.load(.duration)
            await MainActor.run {
                durationSeconds = duration?.seconds ?? 0
                trimStart = 0
                trimEnd = durationSeconds
            }
        }
    }

    private func exportEditedVideo() {
        guard let sourceURL = tempURL, durationSeconds > 0 else {
            onUse(data, mimeType)
            return
        }
        exporting = true

        Task {
            let asset = AVURLAsset(url: sourceURL)
            guard let exportSession = AVAssetExportSession(
                asset: asset, presetName: AVAssetExportPresetHighestQuality
            ) else {
                await MainActor.run {
                    exporting = false
                    onUse(data, mimeType) // fallback: untrimmed
                }
                return
            }

            let outputURL = FileManager.default.temporaryDirectory
                .appendingPathComponent("bitos-trim-\(Int(Date.now.timeIntervalSince1970)).mp4")
            try? FileManager.default.removeItem(at: outputURL)

            exportSession.timeRange = CMTimeRange(
                start: CMTime(seconds: trimStart, preferredTimescale: 600),
                end: CMTime(seconds: trimEnd, preferredTimescale: 600)
            )
            exportSession.outputURL = outputURL
            exportSession.outputFileType = .mp4
            if mirrored, let videoTrack = asset.tracks(withMediaType: .video).first {
                exportSession.videoComposition = mirroredComposition(for: videoTrack)
            }

            await exportSession.export()

            await MainActor.run {
                exporting = false
                if exportSession.status == .completed,
                   let trimmed = try? Data(contentsOf: outputURL), !trimmed.isEmpty {
                    try? FileManager.default.removeItem(at: outputURL)
                    onUse(trimmed, "video/mp4")
                } else {
                    exportError = "Couldn’t apply edits. Try again or use the original take."
                }
            }
        }
    }

    /// Builds a composition in the track's upright coordinate space, then
    /// mirrors it about that canvas's vertical axis. This preserves portrait
    /// capture orientation instead of mirroring raw encoded pixels.
    private func mirroredComposition(for track: AVAssetTrack) -> AVVideoComposition {
        let natural = track.naturalSize
        let oriented = CGRect(origin: .zero, size: natural)
            .applying(track.preferredTransform)
            .standardized
        let renderSize = CGSize(width: abs(oriented.width), height: abs(oriented.height))
        let mirror = CGAffineTransform(translationX: renderSize.width, y: 0).scaledBy(x: -1, y: 1)

        let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: track)
        layerInstruction.setTransform(track.preferredTransform.concatenating(mirror), at: .zero)
        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = CMTimeRange(start: .zero, duration: track.timeRange.duration)
        instruction.layerInstructions = [layerInstruction]

        let composition = AVMutableVideoComposition()
        composition.renderSize = renderSize
        composition.frameDuration = CMTime(value: 1, timescale: 30)
        composition.instructions = [instruction]
        return composition
    }
}

private extension View {
    func returning() -> some View { self }
}
