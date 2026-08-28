import AVFoundation
import AVKit
import SwiftUI

/**
 * Recorded-take preview with trim (CAP-003/004): playback + start/end trim
 * sliders + export via AVAssetExportSession. "Use this" exports the trimmed
 * clip (or the original when untrimmed) and hands the Data to the publish
 * pipeline unchanged.
 */
struct VideoPreviewScreen: View {
    let data: Data
    let mimeType: String
    let onUse: (Data, String) -> Void
    let onRetake: () -> Void
    @State private var player: AVPlayer?
    @State private var durationSeconds: Double = 0
    @State private var trimStart: Double = 0
    @State private var trimEnd: Double = 0
    @State private var hasTrimmed = false
    @State private var exporting = false
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
                    .ignoresSafeArea()
            }
            VStack {
                Spacer()
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
                HStack(spacing: BitOSTheme.Spacing.md) {
                    Button("Retake", action: onRetake)
                        .buttonStyle(.bordered)
                    Button(hasTrimmed ? "Trim & use" : "Use this") {
                        if hasTrimmed {
                            exportTrimmed()
                        } else {
                            onUse(data, mimeType)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
                }
                Text("\(data.count / 1024 / 1024)MB" + (hasTrimmed ? " → ~\(Int(Double(trimEnd - trimStart) / durationSeconds * Double(data.count)) / 1024 / 1024)MB trimmed" : ""))
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.7))
            }
            .padding(BitOSTheme.Spacing.lg)
        }
        .preferredColorScheme(.dark)
        .onAppear(perform: setup)
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

    private func exportTrimmed() {
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

            await exportSession.export()

            await MainActor.run {
                exporting = false
                if exportSession.status == .completed,
                   let trimmed = try? Data(contentsOf: outputURL), !trimmed.isEmpty {
                    try? FileManager.default.removeItem(at: outputURL)
                    onUse(trimmed, "video/mp4")
                } else {
                    // Fallback: publish the original.
                    onUse(data, mimeType)
                }
            }
        }
    }
}

private extension View {
    func returning() -> some View { self }
}
