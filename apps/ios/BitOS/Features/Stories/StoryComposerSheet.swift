import AVFoundation
import BusinessCore
import PhotosUI
import SwiftUI

/// A completed story mining session — the `dTag` is part of the template,
/// so publish must commit exactly this (dTag, nonce, target, createdAt).
struct StoryPowCommit: Equatable {
    let dTag: String
    let nonce: Int64
    let targetDifficulty: Int
    let createdAt: Int64
}

/**
 * APP-006 story composer (web `StoryComposer` parity): 9:16 preview
 * (gradient text slide, image carousel with caption scrim, or a video
 * slide with duration chip), ≤280-char caption, six IG-style gradient
 * backgrounds, ≤6 photo-picker images uploaded via Blossom BEFORE
 * anything references them, one video per slide (measured duration
 * rides the NIP-92 imeta `duration`; video replaces the carousel — web
 * parity), GIF picks (already-public URLs ride the same imeta
 * carousel), emoji inserts, optional NIP-13 PoW mined over the exact
 * kind-30315 template, alt text + sensitive flag, and a kind-30315
 * publish through the receipt machine. Stories double as a 24h status
 * note with no media attached.
 */
@MainActor
struct StoryComposerSheet: View {
    /// Publishes the composed story (kind-30315 through the receipt machine).
    let onPublish: (_ text: String, _ imageUrls: [String], _ background: String?, _ altText: String, _ sensitive: Bool, _ videoUrl: String?, _ videoMime: String, _ videoDurationMs: Int64?, _ videoPoster: String?, _ pow: StoryPowCommit?) -> Void
    let onClose: () -> Void

    @Environment(IdentityStore.self) private var identity
    @Environment(AppEnvironment.self) private var environment
    @State private var text = ""
    @State private var altText = ""
    @State private var sensitive = false
    @State private var bgIndex = 0
    @State private var images: [String] = []
    @State private var previewIndex = 0
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var uploading = false
    @State private var failure: String?
    @State private var uploader = BlossomUploader()
    @State private var gifSheet = false
    @State private var emojiSheet = false
    @State private var showPowPanel = false
    @State private var powTarget = 0
    @State private var powOutcome: PowOutcome?
    // One video per slide (web parity: the first video imeta replaces the
    // carousel in the viewer) — picking either clears the other.
    @State private var videoUrl: String?
    @State private var videoMime = "video/mp4"
    @State private var videoDurationMs: Int64?
    // Poster frame (NIP-92 imeta `thumb`): instant rail/viewer preview
    // while the video streams; a failed extraction just omits it.
    @State private var videoPoster: String?
    @State private var videoPickerItems: [PhotosPickerItem] = []
    /// Fixed for the whole session: the dTag is part of the PoW mining
    /// template, so it cannot be regenerated at publish time. Same wire
    /// format as `NotePublisher.publishStory` (`Stories.storyDTag`).
    @State private var storyDTag: String = {
        let now = Int64(Date.now.timeIntervalSince1970)
        return "bitos-story-\(now)-\(String(UInt32.random(in: 0...UInt32.max), radix: 36))"
    }()
    private let bridge = BusinessCoreBridge()

    private let blossomServer = "https://blossom.primal.net"

    /// Web backgrounds — published as CSS tokens both clients parse.
    private static let backgrounds: [[Color]] = [
        [Color(hex: 0x2F95F6), Color(hex: 0x55D69A)],
        [Color(hex: 0xFF755F), Color(hex: 0xFFB86B)],
        [Color(hex: 0x8B5CF6), Color(hex: 0xEC4899)],
        [Color(hex: 0x0F172A), Color(hex: 0x334155)],
        [Color(hex: 0xF59E0B), Color(hex: 0xEF4444)],
        [Color(hex: 0x10B981), Color(hex: 0x06B6D4)],
    ]

    private var hasVideo: Bool { videoUrl != nil }

    private var canPost: Bool {
        (!text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !images.isEmpty || hasVideo) && !uploading
    }

    private var backgroundCss: String? {
        guard images.isEmpty && !hasVideo else { return nil }
        let pair = Self.backgrounds[bgIndex]
        return "linear-gradient(135deg, \(hex(pair[0])), \(hex(pair[1])))"
    }

    /// `#rrggbb` for the CSS token (both clients parse hex pairs).
    private func hex(_ color: Color) -> String {
        String(format: "#%06x", color.toHexInt())
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: BitOSTheme.Spacing.md) {
                    preview

                    // Caption + live counter (brand field, legacy Flutter
                    // InputDecoration parity).
                    BitosField(
                        images.isEmpty ? "What's on your mind?" : "Add a caption…",
                        text: $text,
                        axis: .vertical
                    )
                    .lineLimit(1...3)
                    .onChange(of: text) { _, value in
                        if value.count > 280 { text = String(value.prefix(280)) }
                    }
                    .overlay(alignment: .trailing) {
                        Text("\(text.count)/280")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(BitOSTheme.textSecondary)
                            .padding(.trailing, 12)
                            .allowsHitTesting(false)
                    }

                    if images.isEmpty && !hasVideo {
                        // Gradient swatches (text-only slides).
                        HStack(spacing: BitOSTheme.Spacing.sm) {
                            ForEach(Self.backgrounds.indices, id: \.self) { index in
                                let pair = Self.backgrounds[index]
                                Circle()
                                    .fill(LinearGradient(colors: pair, startPoint: .topLeading, endPoint: .bottomTrailing))
                                    .frame(width: 28, height: 28)
                                    .overlay(
                                        Circle().stroke(
                                            bgIndex == index ? BitOSTheme.accent : .white.opacity(0.3),
                                            lineWidth: bgIndex == index ? 2 : 1
                                        )
                                    )
                                    .onTapGesture { bgIndex = index }
                                    .accessibilityLabel("Use background \(index + 1)")
                            }
                        }
                        Text("No image? Stories double as a 24h status note.")
                            .font(.system(size: 11))
                            .foregroundStyle(BitOSTheme.textSecondary)
                    } else {
                        if hasVideo {
                            videoTile
                        } else {
                            imageStrip
                        }
                        BitosField(
                            "Describe the media for screen readers (alt text)…",
                            text: $altText,
                            size: .small
                        )
                        .onChange(of: altText) { _, value in
                            if value.count > 280 { altText = String(value.prefix(280)) }
                        }
                        // Same card chrome as the media-details
                        // content-warning gate.
                        Button {
                            sensitive.toggle()
                        } label: {
                            HStack(spacing: BitOSTheme.Spacing.sm) {
                                Image(systemName: sensitive ? AppIcons.checkCircle : "circle")
                                    .foregroundStyle(sensitive ? Color(red: 1.0, green: 0.46, blue: 0.37) : BitOSTheme.textSecondary)
                                Text("Mark as sensitive — blur until tapped")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(sensitive ? Color(red: 1.0, green: 0.46, blue: 0.37) : BitOSTheme.textSecondary)
                                Spacer()
                            }
                            .padding(BitOSTheme.Spacing.base)
                            .frame(maxWidth: .infinity, minHeight: BitOSTheme.minTouchTarget, alignment: .leading)
                            .background(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .fill(BitOSTheme.surface)
                            )
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Mark as sensitive")
                    }

                    // Quick actions (note-composer toolbar parity): photo ·
                    // video · GIF · emoji · PoW. A video replaces the photo
                    // carousel (web parity), so the photo/GIF entries wait
                    // while one is attached.
                    HStack(spacing: 2) {
                        actionButton(AppIcons.photo, "Add image", enabled: images.count < Self.maxImages && !uploading && !hasVideo,
                                     active: !images.isEmpty, badge: images.isEmpty ? nil : "\(images.count)") {
                            pickerPrompt = true
                        }
                        actionButton(AppIcons.video, "Add video", enabled: !uploading, active: hasVideo) {
                            videoPickerPrompt = true
                        }
                        actionButton(AppIcons.gifFilm, "Add GIF", enabled: images.count < Self.maxImages && !hasVideo) {
                            gifSheet = true
                        }
                        actionButton(AppIcons.emoji, "Insert emoji") {
                            emojiSheet = true
                        }
                        actionButton(AppIcons.shieldCheck, "Proof of Work",
                                     active: showPowPanel || powOutcome != nil,
                                     badge: powOutcome.map { "\($0.targetDifficulty)" }) {
                            showPowPanel.toggle()
                        }
                        Spacer(minLength: BitOSTheme.Spacing.sm)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, BitOSTheme.Spacing.xs)
                    .background(BitOSTheme.surface, in: RoundedRectangle(cornerRadius: 12))

                    // NIP-13 PoW over the exact kind-30315 template (the
                    // nonce tag is appended by the miner; any edit voids
                    // the session — `.id` keys the card to the template).
                    if showPowPanel {
                        powPanel
                    }

                    if let failure {
                        Text(failure)
                            .font(.system(size: 12))
                            .foregroundStyle(.red)
                    }

                    Button {
                        post()
                    } label: {
                        Group {
                            if uploading {
                                ProgressView().tint(.white)
                            } else {
                                Text("Post · 24h").bold()
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 44)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.white)
                    .background(canPost ? AnyShapeStyle(BitOSTheme.accent) : AnyShapeStyle(Color.gray.opacity(0.4)), in: RoundedRectangle(cornerRadius: 12))
                    .disabled(!canPost)
                }
                .padding(BitOSTheme.Spacing.lg)
            }
            .navigationTitle("New story")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onClose)
                }
            }
        }
        .onChange(of: pickerItems) { _, items in
            let room = StoryComposerSheet.maxImages - images.count
            guard room > 0, !items.isEmpty else {
                pickerItems = []
                return
            }
            // Images and the video are mutually exclusive slides.
            videoUrl = nil
            videoDurationMs = nil
            videoPoster = nil
            uploadPicked(Array(items.prefix(room)))
        }
        .onChange(of: videoPickerItems) { _, items in
            guard let item = items.first else {
                videoPickerItems = []
                return
            }
            videoPickerItems = []
            Task { await uploadVideo(item) }
        }
        .sheet(isPresented: $gifSheet) {
            GifPickerSheet(
                onPick: { gif in
                    if images.count < Self.maxImages {
                        // Images and the video are mutually exclusive slides.
                        videoUrl = nil
                        videoDurationMs = nil
                        videoPoster = nil
                        images.append(gif.url)
                        previewIndex = images.count - 1
                    }
                    gifSheet = false
                },
                onDismiss: { gifSheet = false }
            )
            .presentationDetents([.large, .medium])
        }
        .sheet(isPresented: $emojiSheet) {
            emojiSheetContent
                .presentationDetents([.medium])
        }
        .photosPicker(
            isPresented: $pickerPrompt,
            selection: $pickerItems,
            maxSelectionCount: max(0, Self.maxImages - images.count),
            matching: .images
        )
        .photosPicker(
            isPresented: $videoPickerPrompt,
            selection: $videoPickerItems,
            maxSelectionCount: 1,
            matching: .videos
        )
    }

    private static let maxImages = 6

    @State private var pickerPrompt = false
    @State private var videoPickerPrompt = false

    /// One bounded video upload (PUB-002: uploaded BEFORE anything
    /// references it); the measured duration rides the NIP-92 imeta.
    @MainActor
    private func uploadVideo(_ item: PhotosPickerItem) async {
        uploading = true
        failure = nil
        defer { uploading = false }
        do {
            guard let data = try await item.loadTransferable(type: Data.self),
                  identity.account != nil else {
                throw BlossomUploader.UploadFailure(message: "Posting needs an identity.")
            }
            let mime = item.supportedContentTypes.first?.preferredMIMEType ?? "video/mp4"
            // Duration + poster frame off the main actor: temp file →
            // AVURLAsset → AVAssetImageGenerator (web `thumb` parity).
            let bytes = data
            let measured: (ms: Int64?, poster: Data?) = await Task.detached(priority: .userInitiated) { () -> (Int64?, Data?) in
                let url = FileManager.default.temporaryDirectory
                    .appendingPathComponent("bitos-story-\(UUID().uuidString).mov")
                defer { try? FileManager.default.removeItem(at: url) }
                do {
                    try bytes.write(to: url)
                    let asset = AVURLAsset(url: url)
                    let duration = try await asset.load(.duration)
                    let ms: Int64? = duration.seconds > 0 ? Int64((duration.seconds * 1000).rounded()) : nil
                    let generator = AVAssetImageGenerator(asset: asset)
                    generator.appliesPreferredTrackTransform = true
                    generator.maximumSize = CGSize(width: 720, height: 1280)
                    var poster: Data?
                    if let cg = try? generator.copyCGImage(at: CMTime(seconds: 1, preferredTimescale: 600), actualTime: nil) {
                        poster = UIImage(cgImage: cg).jpegData(compressionQuality: 0.8)
                    }
                    return (ms, poster)
                } catch {
                    return (nil, nil)
                }
            }.value
            let uploaded = try await uploader.upload(
                bytes: data,
                mimeType: mime.hasPrefix("video/") ? mime : "video/mp4",
                identity: identity,
                serverUrl: blossomServer
            )
            // Best-effort poster upload — it never blocks the story itself.
            var poster: String?
            if let jpg = measured.poster {
                poster = (try? await uploader.upload(
                    bytes: jpg, mimeType: "image/jpeg", identity: identity, serverUrl: blossomServer
                ))?.url
            }
            images.removeAll()
            videoMime = mime.hasPrefix("video/") ? mime : "video/mp4"
            videoDurationMs = measured.ms
            videoPoster = poster
            videoUrl = uploaded.url
        } catch {
            failure = (error as? BlossomUploader.UploadFailure)?.message ?? error.localizedDescription
        }
    }

    /// Quick-action icon (note-composer `toolbarButton` parity).
    private func actionButton(_ symbol: String, _ label: String, enabled: Bool = true, active: Bool = false, badge: String? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            AppIcons.image(for: symbol)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(
                    !enabled ? BitOSTheme.textTertiary.opacity(0.4) :
                    active ? BitOSTheme.accent : BitOSTheme.textSecondary
                )
                .frame(width: 40, height: 40)
                .background {
                    if active { Circle().fill(BitOSTheme.accent.opacity(0.15)) }
                }
                .overlay(alignment: .topTrailing) {
                    if let badge {
                        Text(badge)
                            .font(.system(size: 8, weight: .bold))
                            .foregroundStyle(BitOSTheme.background)
                            .padding(.horizontal, 3)
                            .background(Capsule().fill(BitOSTheme.accent))
                    }
                }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    /// Emoji grid (note-composer parity): appends to the caption.
    private var emojiSheetContent: some View {
        let emojis = bridge.composerEmojis()
        return VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            Text("Insert emoji")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(BitOSTheme.textPrimary)
                .padding(.horizontal, BitOSTheme.Spacing.screen)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 8), spacing: BitOSTheme.Spacing.sm) {
                ForEach(emojis, id: \.self) { emoji in
                    Button {
                        if (text as NSString).length + (emoji as NSString).length <= 280 {
                            text += emoji
                        }
                        emojiSheet = false
                    } label: {
                        Text(emoji)
                            .font(.system(size: 22))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Insert \(emoji)")
                }
            }
            .padding(.horizontal, BitOSTheme.Spacing.base)
            .padding(.bottom, BitOSTheme.Spacing.xl)
        }
    }

    /// Video tile (replaces the carousel — web parity): play glyph,
    /// measured duration, removable.
    private var videoTile: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            ZStack(alignment: .topTrailing) {
                ZStack {
                    RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous)
                        .fill(Color(red: 0.06, green: 0.08, blue: 0.11))
                    AppIcons.image(for: AppIcons.play)
                        .font(.system(size: 22))
                        .foregroundStyle(.white)
                }
                .frame(width: 64, height: 64)
                .overlay(alignment: .bottom) {
                    if let videoDurationMs {
                        Text(Self.formatDuration(videoDurationMs))
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(.black.opacity(0.55), in: Capsule())
                            .padding(.bottom, 4)
                    }
                }
                Button {
                    videoUrl = nil
                    videoDurationMs = nil
                    videoPoster = nil
                } label: {
                    Image(systemName: AppIcons.close)
                        .font(.system(size: 8, weight: .bold))
                        .frame(width: 18, height: 18)
                        .background(Circle().fill(BitOSTheme.surface))
                }
                .offset(x: 7, y: -7)
                .accessibilityLabel("Remove video")
            }
            Text("Video plays instead of a photo carousel")
                .font(.system(size: 11))
                .foregroundStyle(BitOSTheme.textSecondary)
        }
    }

    /// Web Composer parity: the PoW panel rides inline under the actions.
    /// `.id` keys the card to the mining template — a mined nonce is valid
    /// for exactly one (content, target, tags) story, so any edit resets
    /// the card.
    private var powPanel: some View {
        PowCard(
            target: $powTarget,
            mineChunk: { createdAt, startNonce, attempts in
                await environment.notePublisher.mineStoryPowChunk(
                    text: text,
                    imageUrls: images,
                    background: backgroundCss,
                    altText: altText,
                    sensitive: sensitive,
                    dTag: storyDTag,
                    targetDifficulty: Int32(powTarget),
                    createdAt: createdAt,
                    startNonce: startNonce,
                    attempts: attempts,
                    videoUrl: videoUrl,
                    videoMime: videoMime,
                    videoDurationMs: videoDurationMs ?? 0,
                    videoPoster: videoPoster
                )
            },
            onMined: { outcome in powOutcome = outcome }
        )
        .id("\(powTarget)|\(text)|\(images)|\(bgIndex)|\(altText)|\(sensitive)|\(storyDTag)|\(videoUrl ?? "")|\(videoDurationMs ?? 0)")
    }

    /// The 9:16 preview: gradient text slide, video slide (play glyph +
    /// duration chip — no decode; the viewer plays the real stream), or
    /// image + caption scrim.
    private var preview: some View {
        ZStack {
            if hasVideo {
                Color.black
                if let videoPoster, let poster = URL(string: videoPoster) {
                    AsyncImage(url: poster) { phase in
                        if let image = phase.image {
                            image.resizable().scaledToFill()
                        }
                    }
                }
                Image(systemName: AppIcons.play)
                    .font(.system(size: 26, weight: .medium))
                    .foregroundStyle(.white)
                    .frame(width: 52, height: 52)
                    .background(Circle().fill(.black.opacity(0.6)))
                    .accessibilityLabel("Video story")
                if let videoDurationMs {
                    Text(Self.formatDuration(videoDurationMs))
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(.black.opacity(0.55), in: Capsule())
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .padding(.bottom, 8)
                }
            } else if images.isEmpty {
                LinearGradient(
                    colors: Self.backgrounds[bgIndex],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                )
                Text(text.isEmpty ? "Type a note…" : text)
                    .font(.system(size: 17, weight: .heavy))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(BitOSTheme.Spacing.md)
            } else {
                Color.black
                AsyncImage(url: URL(string: images[previewIndex])) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    }
                }
                if images.count > 1 {
                    VStack {
                        HStack {
                            Spacer()
                            Text("\(previewIndex + 1)/\(images.count)")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(.black.opacity(0.55), in: Capsule())
                                .padding(6)
                        }
                        Spacer()
                        if !text.isEmpty {
                            Text(text)
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(.white)
                                .lineLimit(3)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(8)
                                .background(
                                    LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom)
                                )
                        }
                    }
                }
            }
        }
        .frame(width: 190, height: 338)
        .overlay(alignment: .topLeading) {
            // Prototype `#/story-compose` parity: expiry chip on the preview.
            Text("24 h · kind-30315")
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(.white)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(.black.opacity(0.5), in: Capsule())
                .padding(6)
        }
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .onTapGesture {
            if !images.isEmpty { previewIndex = (previewIndex + 1) % images.count }
        }
        .accessibilityLabel("Story preview")
    }

    /// Attached images + the add tile (web thumbnail strip parity).
    private var imageStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                ForEach(images.indices, id: \.self) { index in
                    AsyncImage(url: URL(string: images[index])) { phase in
                        if let image = phase.image {
                            image.resizable().scaledToFill()
                        } else {
                            BitOSTheme.surfaceElevated
                        }
                    }
                    .frame(width: 64, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md))
                    .overlay(
                        RoundedRectangle(cornerRadius: BitOSTheme.Radius.md)
                            .stroke(previewIndex == index ? BitOSTheme.accent : .clear, lineWidth: 2)
                    )
                    .onTapGesture { previewIndex = index }
                    .overlay(alignment: .topTrailing) {
                        Button {
                            images.remove(at: index)
                            previewIndex = min(previewIndex, max(0, images.count - 1))
                        } label: {
                            AppIcons.image(for: AppIcons.close)
                                .font(.system(size: 8, weight: .bold))
                                .frame(width: 18, height: 18)
                                .background(Circle().fill(BitOSTheme.surface))
                        }
                        .offset(x: 7, y: -7)
                        .accessibilityLabel("Remove image \(index + 1)")
                    }
                }
                if images.count < Self.maxImages {
                    PhotosPicker(selection: $pickerItems, maxSelectionCount: Self.maxImages - images.count, matching: .images) {
                        // Static label: PhotosUI's label closure is Sendable,
                        // so it may never read MainActor state. Upload
                        // progress shows in the Post button + failure line.
                        StoryAddImageTile()
                    }
                    .accessibilityLabel("Add image")
                }
            }
        }
    }

    /// Upload each picked photo BEFORE its URL joins the carousel (PUB-002:
    /// nothing references media that was never uploaded).
    @MainActor
    private func uploadPicked(_ items: [PhotosPickerItem]) {
        uploading = true
        failure = nil
        Task {
            for item in items {
                guard images.count < Self.maxImages else { break }
                do {
                    guard let data = try await item.loadTransferable(type: Data.self),
                          identity.account != nil else {
                        throw BlossomUploader.UploadFailure(message: "Posting needs an identity.")
                    }
                    let uploaded = try await uploader.upload(
                        bytes: data,
                        mimeType: "image/jpeg",
                        identity: identity,
                        serverUrl: blossomServer
                    )
                    images.append(uploaded.url)
                    previewIndex = images.count - 1
                } catch {
                    failure = (error as? BlossomUploader.UploadFailure)?.message ?? error.localizedDescription
                }
            }
            uploading = false
            pickerItems = []
        }
    }

    private func post() {
        guard canPost else { return }
        let commit = powOutcome.map {
            StoryPowCommit(dTag: storyDTag, nonce: $0.nonce, targetDifficulty: $0.targetDifficulty, createdAt: $0.createdAt)
        }
        onPublish(
            text.trimmingCharacters(in: .whitespacesAndNewlines),
            images,
            backgroundCss,
            altText.trimmingCharacters(in: .whitespacesAndNewlines),
            sensitive,
            videoUrl,
            videoMime,
            videoDurationMs,
            videoPoster,
            commit
        )
        onClose()
    }

    /// `m:ss` for the video duration chips.
    private static func formatDuration(_ ms: Int64) -> String {
        let totalSeconds = max(0, Int(ms / 1000))
        return String(format: "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}

/// The dashed add-image tile (static so PhotosUI's Sendable label closure
/// never reads MainActor state).
private struct StoryAddImageTile: View {
    var body: some View {
        AppIcons.image(for: AppIcons.photo)
            .font(.system(size: 20))
            .foregroundStyle(BitOSTheme.textSecondary)
            .frame(width: 64, height: 64)
            .background(
                RoundedRectangle(cornerRadius: BitOSTheme.Radius.md)
                    .stroke(style: StrokeStyle(lineWidth: 1, dash: [4]))
            )
    }
}

private extension Color {
    /// RGB int for the background CSS hex token.
    func toHexInt() -> Int {
        let ui = UIColor(self)
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        ui.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return (Int(red * 255) << 16) | (Int(green * 255) << 8) | Int(blue * 255)
    }
}
