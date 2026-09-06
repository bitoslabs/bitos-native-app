import BusinessCore
import PhotosUI
import SwiftUI

/**
 * APP-006 story composer (web `StoryComposer` parity): 9:16 preview
 * (gradient text slide or image carousel with caption scrim), ≤280-char
 * caption, six IG-style gradient backgrounds, ≤6 photo-picker images
 * uploaded via Blossom BEFORE anything references them, alt text +
 * sensitive flag, and a kind-30315 publish through the receipt machine.
 * Stories double as a 24h status note when no image is attached.
 */
@MainActor
struct StoryComposerSheet: View {
    /// Publishes the composed story (kind-30315 through the receipt machine).
    let onPublish: (_ text: String, _ imageUrls: [String], _ background: String?, _ altText: String, _ sensitive: Bool) -> Void
    let onClose: () -> Void

    @Environment(IdentityStore.self) private var identity
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

    private var canPost: Bool {
        (!text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !images.isEmpty) && !uploading
    }

    private var backgroundCss: String? {
        guard images.isEmpty else { return nil }
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

                    TextField(
                        images.isEmpty ? "What's on your mind?" : "Add a caption…",
                        text: $text,
                        axis: .vertical
                    )
                    .font(.system(size: 14))
                    .lineLimit(1...3)
                    .onChange(of: text) { _, value in
                        if value.count > 280 { text = String(value.prefix(280)) }
                    }
                    .padding(BitOSTheme.Spacing.base)
                    .background(BitOSTheme.surfaceElevated, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(alignment: .trailing) {
                        Text("\(text.count)/280")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(BitOSTheme.textSecondary)
                            .padding(.trailing, 8)
                    }

                    if images.isEmpty {
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
                        imageStrip
                        TextField("Describe the images for screen readers (alt text)…", text: $altText)
                            .font(.system(size: 12))
                            .padding(BitOSTheme.Spacing.base)
                            .background(BitOSTheme.surfaceElevated, in: RoundedRectangle(cornerRadius: 12))
                            .onChange(of: altText) { _, value in
                                if value.count > 280 { altText = String(value.prefix(280)) }
                            }
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
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Mark as sensitive")
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
            uploadPicked(Array(items.prefix(room)))
        }
    }

    private static let maxImages = 6

    /// The 9:16 preview: gradient text slide or image + caption scrim.
    private var preview: some View {
        ZStack {
            if images.isEmpty {
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
                            Image(systemName: AppIcons.close)
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
                          await identity.account != nil else {
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
        onPublish(
            text.trimmingCharacters(in: .whitespacesAndNewlines),
            images,
            backgroundCss,
            altText.trimmingCharacters(in: .whitespacesAndNewlines),
            sensitive
        )
        onClose()
    }
}

/// The dashed add-image tile (static so PhotosUI's Sendable label closure
/// never reads MainActor state).
private struct StoryAddImageTile: View {
    var body: some View {
        Image(systemName: AppIcons.photo)
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
