import SwiftUI
import AVFoundation
import PhotosUI
import BusinessCore

/// "Start with a sound" source picker (MEM-003b, plan docs/native/
/// create-hub-sound-remix-plan.md §Sound source picker; Android
/// `SoundSourcePickerScreen` parity): Shared sounds → Trending on BitOS
/// (a link into the existing surface) → My sound library → Pick sound
/// from a video. One preview plays at a time with an exposed
/// play/pause state; selecting any row dispatches exactly ONE
/// `MemeSoundSeed` (or the video handoff) and dismisses only after the
/// editor accepts it. Cancel leaves the hub untouched.
struct SoundSourcePickerView: View {
    let sharedRows: [SharedSoundStore.Row]
    let trendingRows: [MemeSoundTrending.Row]
    let libraryEntries: [SharedSoundLibraryStore.Entry]
    let libraryBytes: (String) -> Data?

    let onUseShared: (SharedSoundStore.Row) -> Void
    let onUseTrending: (MemeSoundTrending.Row) -> Void
    let onUseLibrary: (SharedSoundLibraryStore.Entry) -> Void
    /// MEM-003b "Pick sound from a video": hands the picked video's bytes
    /// to the editor's existing extraction path.
    let onPickFromVideo: (Data) -> Void
    let onOpenTrending: () -> Void
    let onClose: () -> Void

    @State private var previewKey: String?
    @State private var previewPlayer: AVAudioPlayer?
    @State private var previewError: String?
    @State private var videoItem: PhotosPickerItem?
    @State private var urlPreviewPlayer: AVPlayer?

    /// One preview at a time across BOTH player kinds (plan rule).
    private func stopAllPreviews() {
        previewPlayer?.stop()
        previewPlayer = nil
        urlPreviewPlayer?.pause()
        urlPreviewPlayer = nil
        previewKey = nil
    }

    private func toggleUrlPreview(key: String, url: String) {
        if previewKey == key {
            stopAllPreviews()
            return
        }
        stopAllPreviews()
        previewError = nil
        guard let target = URL(string: url) else { return }
        let player = AVPlayer(url: target)
        urlPreviewPlayer = player
        previewKey = key
        player.play()
    }

    private func toggleLibraryPreview(entry: SharedSoundLibraryStore.Entry) {
        let key = "local:\(entry.id)"
        if previewKey == key {
            stopAllPreviews()
            return
        }
        stopAllPreviews()
        previewError = nil
        guard let bytes = libraryBytes(entry.id) else {
            previewError = "That saved sound's audio is missing"
            return
        }
        do {
            let player = try AVAudioPlayer(data: bytes)
            player.delegate = PreviewPlayerDelegate.shared
            PreviewPlayerDelegate.shared.onEnd = { Task { @MainActor in stopAllPreviews() } }
            previewPlayer = player
            previewKey = key
            player.play()
        } catch {
            previewError = "That preview could not play"
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
                HStack {
                    Text("Start with a sound")
                        .font(.title2.weight(.bold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button("Cancel") {
                        stopAllPreviews()
                        onClose()
                    }
                    .accessibilityLabel("Close sound picker")
                }
                Text("Add your video after you choose a sound.")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary)

                if let previewError {
                    Text(previewError)
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.error)
                }

                // ── 1. Shared sounds — the existing verified rail ──────
                sectionTitle("Shared sounds")
                if sharedRows.isEmpty {
                    Text("Shared sounds are unavailable right now.")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textTertiary)
                } else {
                    ForEach(sharedRows) { row in
                        soundRow(
                            label: row.label,
                            supporting: [row.durationMs > 0 ? "\(row.durationMs / 1000)s" : nil,
                                         row.license.isEmpty ? nil : row.license]
                                .compactMap { $0 }.joined(separator: " · "),
                            credit: "by \(Self.shortKey(row.authorPubkey))",
                            key: row.url,
                            onPreview: { toggleUrlPreview(key: row.url, url: row.url) },
                            onUse: {
                                stopAllPreviews()
                                onUseShared(row)
                            }
                        )
                    }
                }

                // ── 2. Trending on BitOS — link into the existing rail ─
                sectionTitle("Trending on BitOS")
                ForEach(trendingRows.prefix(3), id: \.url) { row in
                    soundRow(
                        label: "Original sound",
                        supporting: "\(row.uses) borrow\(row.uses == 1 ? "" : "s") · trending",
                        credit: nil,
                        key: row.url,
                        onPreview: { toggleUrlPreview(key: row.url, url: row.url) },
                        onUse: {
                            stopAllPreviews()
                            onUseTrending(row)
                        }
                    )
                }
                Button {
                    stopAllPreviews()
                    onOpenTrending()
                } label: {
                    HStack {
                        Text("Open Trending sounds")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(BitOSTheme.accent)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textTertiary)
                    }
                    .padding(BitOSTheme.Spacing.sm)
                    .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surface))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Open trending sounds")

                // ── 3. My sound library — local, hash-verified ─────────
                sectionTitle("My sound library")
                if libraryEntries.isEmpty {
                    Text("No saved sounds yet — every soundtrack you attach is saved here on this device.")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                } else {
                    ForEach(libraryEntries) { entry in
                        soundRow(
                            label: entry.label,
                            supporting: [entry.durationMs > 0 ? "\(entry.durationMs / 1000)s" : nil,
                                         entry.url.isEmpty ? "local only" : nil]
                                .compactMap { $0 }.joined(separator: " · "),
                            credit: nil,
                            key: "local:\(entry.id)",
                            onPreview: { toggleLibraryPreview(entry: entry) },
                            onUse: {
                                stopAllPreviews()
                                onUseLibrary(entry)
                            }
                        )
                    }
                }

                // ── 4. Pick sound from a video — editor extraction ─────
                sectionTitle("Pick sound from a video")
                PhotosPicker(selection: $videoItem, matching: .videos) {
                    Label("Choose a video — its audio becomes the soundtrack",
                          systemImage: "music.note")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.accent))
                        .foregroundStyle(.white)
                }
                .accessibilityLabel("Pick sound from a video")
                .onChange(of: videoItem) { _, item in
                    guard let item else { return }
                    Task {
                        if let data = try? await item.loadTransferable(type: Data.self) {
                            stopAllPreviews()
                            onPickFromVideo(data)
                        } else {
                            previewError = "That video could not be read"
                        }
                        videoItem = nil
                    }
                }

                // ── Empty state: nothing selectable anywhere ───────────
                if sharedRows.isEmpty && trendingRows.isEmpty && libraryEntries.isEmpty {
                    VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
                        Text("No sounds available yet")
                            .font(.subheadline.weight(.semibold))
                        Text("Pick a sound from a video to start — shared and trending sounds appear as relays deliver them.")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                    .padding(BitOSTheme.Spacing.base)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surface))
                }
            }
            .padding(BitOSTheme.Spacing.base)
        }
        .background(BitOSTheme.background)
        .onDisappear {
            stopAllPreviews()
        }
    }

    private func sectionTitle(_ title: String) -> some View {
        Text(title).font(.subheadline.weight(.semibold))
    }

    private func soundRow(
        label: String,
        supporting: String,
        credit: String?,
        key: String,
        onPreview: @escaping () -> Void,
        onUse: @escaping () -> Void
    ) -> some View {
        Button(action: onUse) {
            HStack(spacing: BitOSTheme.Spacing.md) {
                AppIcons.image(for: AppIcons.musicNote)
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(BitOSTheme.accent)
                    .frame(width: 40, height: 40)
                    .background(BitOSTheme.accentContainer)
                    .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .foregroundStyle(BitOSTheme.textPrimary)
                    if !supporting.isEmpty {
                        Text(supporting)
                            .font(.caption2)
                            .foregroundStyle(BitOSTheme.textSecondary)
                            .lineLimit(1)
                    }
                    if let credit {
                        Text(credit)
                            .font(.caption2)
                            .foregroundStyle(BitOSTheme.textTertiary)
                            .lineLimit(1)
                    }
                }
                Spacer()
                Button(action: onPreview) {
                    Image(systemName: previewKey == key ? "pause.circle" : "play.circle")
                        .font(.system(size: 22))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(previewKey == key ? "Pause preview of \(label)" : "Preview sound \(label)")
                Text("Use")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(BitOSTheme.accent)
            }
            .padding(BitOSTheme.Spacing.sm)
            .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surface))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Use sound \(label)")
    }

    static func shortKey(_ pubkey: String) -> String {
        guard pubkey.count >= 16 else { return pubkey.isEmpty ? "unknown" : pubkey }
        return "\(pubkey.prefix(8))…\(pubkey.suffix(4))"
    }
}

/// AVAudioPlayer end-of-play hook for library previews (main-actor
/// singleton; the delegate callback hops).
@MainActor
private final class PreviewPlayerDelegate: NSObject, AVAudioPlayerDelegate {
    static let shared = PreviewPlayerDelegate()
    var onEnd: (() -> Void)?
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            self.onEnd?()
        }
    }
}
