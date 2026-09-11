import SwiftUI
import AVFoundation
import BusinessCore

/**
 * Trending sounds ("use this sound" Wave D, plan docs/product/
 * use-this-sound-plan.md §5; APP-021 bootstrap): counting `sound` tags
 * over the LIVE feed window IS the rank — no marketplace. Rows are
 * deterministic (shared `MemeSoundTrending.rank`, 3-day half-life);
 * "Use in Studio" re-attaches by URL through the Wave C editor path
 * (hash-verified download, no re-upload).
 */
struct TrendingSoundsView: View {
    let notes: [FeedNote]
    let onClose: () -> Void
    let onUseSound: (MemeSoundTrending.Row) -> Void

    @Environment(AppEnvironment.self) private var environment

    // ── Row preview (§3.21): stream the ranked artifact and play it —
    // one row at a time; toggling another row stops the current one. The
    // first preview also fetches the artifact's waveform peaks (bounded
    // download + decode + shared bucket math), session-cached per URL.
    @State private var playingUrl: String?
    @State private var previewPlayer: AVPlayer?
    @State private var waveforms: [String: [Float]] = [:]

    private func stopPreview() {
        previewPlayer?.pause()
        previewPlayer = nil
        playingUrl = nil
    }

    private func fetchWaveform(_ url: String) {
        guard waveforms[url] == nil, let target = URL(string: url) else { return }
        Task {
            let peaks: [Float]? = await Task.detached(priority: .utility) { () -> [Float]? in
                guard let (data, _) = try? await URLSession.shared.data(from: target),
                      data.count <= 64 * 1024 * 1024,
                      let pcm = MemeVideoSoundIos.decodePcm(data: data) else { return nil }
                let kotlinPcm = KotlinFloatArray(size: Int32(pcm.count)) { index in
                    KotlinFloat(float: pcm[Int(truncating: index)])
                }
                let kotlinPeaks = MemeSoundWaveform.shared.peaks(
                    pcm: kotlinPcm,
                    bars: MemeSoundWaveform.shared.BARS
                )
                return (0..<Int(kotlinPeaks.size)).map { kotlinPeaks.get(index: Int32($0)) }
            }.value
            if let peaks, !peaks.isEmpty {
                waveforms[url] = peaks
            }
        }
    }

    private func togglePreview(_ url: String) {
        if playingUrl == url {
            stopPreview()
            return
        }
        stopPreview()
        fetchWaveform(url)
        guard let target = URL(string: url) else { return }
        let player = AVPlayer(url: target)
        previewPlayer = player
        playingUrl = url
        player.play()
    }

    private var rows: [MemeSoundTrending.Row] {
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        let usages = notes.compactMap { note -> MemeSoundTrending.Usage? in
            guard let sound = note.soundUrl else { return nil }
            return MemeSoundTrending.Usage(
                eventId: note.id,
                createdAtMs: note.createdAt,
                url: sound,
                sha256: note.soundSha256 ?? "",
                sourceEventId: note.soundSourceEventId,
                authorPubkey: note.soundAuthorPubkey
            )
        }
        return Array(MemeSoundTrending.shared.rank(usages: usages, nowMs: now, maxRows: MemeSoundTrending.shared.MAX_ROWS))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Most-borrowed ♪ in your feed — ranked with a 3-day half-life.")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                if rows.isEmpty {
                    Section {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("No borrowed sounds yet")
                                .font(.subheadline.weight(.semibold))
                            Text("Open a video Bitz → Sound to borrow its audio, or publish a meme with a picked soundtrack — every borrow counts here.")
                                .font(.caption)
                                .foregroundStyle(BitOSTheme.textSecondary)
                        }
                        .padding(.vertical, 4)
                    }
                }
                Section {
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                        HStack(spacing: BitOSTheme.Spacing.md) {
                            Image(systemName: "music.note")
                                .foregroundStyle(BitOSTheme.accent)
                                .frame(width: 34, height: 34)
                                .background(BitOSTheme.accentContainer)
                                .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Original sound")
                                    .font(.subheadline.weight(.semibold))
                                Text("\(row.uses) borrow\(row.uses == 1 ? "" : "s") · trending")
                                    .font(.caption)
                                    .foregroundStyle(BitOSTheme.textSecondary)
                                if let peaks = waveforms[row.url] {
                                    WaveformBars(peaks: peaks, active: playingUrl == row.url)
                                        .frame(height: 18)
                                        .padding(.top, 1)
                                }
                            }
                            Spacer()
                            Button {
                                togglePreview(row.url)
                            } label: {
                                Image(systemName: playingUrl == row.url ? "pause.circle" : "play.circle")
                                    .font(.system(size: 22))
                                    .foregroundStyle(BitOSTheme.textSecondary)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(playingUrl == row.url ? "Pause preview" : "Preview sound")
                            Button {
                                onUseSound(row)
                            } label: {
                                Text("Use in Studio")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(BitOSTheme.accent)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            .navigationTitle("Trending sounds")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: onClose)
                }
            }
            .onDisappear { stopPreview() }
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
    }
}

/// §3.21 waveform bars: normalized peaks as a compact bar row (accent
/// tint while this row is the one previewing).
private struct WaveformBars: View {
    let peaks: [Float]
    let active: Bool

    var body: some View {
        GeometryReader { proxy in
            HStack(spacing: 1.5) {
                ForEach(Array(peaks.enumerated()), id: \.offset) { _, peak in
                    RoundedRectangle(cornerRadius: 1)
                        .fill((active ? BitOSTheme.accent : BitOSTheme.textTertiary).opacity(0.8))
                        .frame(
                            width: max(1.5, proxy.size.width / CGFloat(max(1, peaks.count)) - 1.5),
                            height: max(1.5, proxy.size.height * CGFloat(min(1, max(0, peak))))
                        )
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        }
    }
}
