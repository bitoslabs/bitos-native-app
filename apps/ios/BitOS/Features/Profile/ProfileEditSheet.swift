import BusinessCore
import PhotosUI
import SwiftUI

/// Profile editing sheet: bounded fields → signed kind-0 through the
/// receipt machine.
///
/// UX parity with the legacy Flutter editor (`settings/pages/profile_page`):
/// a live header preview (banner + "Change banner" pill, hex avatar with a
/// camera chip → pick → center-crop → upload → URL) above the full kind-0
/// field form.
struct ProfileEditSheet: View {
    let publisher: NotePublisher
    let initialProfile: ProfileMetadata?
    let onClose: () -> Void
    @Environment(IdentityStore.self) private var identity
    @State private var name = ""
    @State private var displayName = ""
    @State private var about = ""
    @State private var nip05 = ""
    @State private var lud16 = ""
    @State private var picture = ""
    @State private var banner = ""
    @State private var website = ""
    @State private var published = false
    @State private var loaded = false
    @State private var uploadingTarget: String?
    @State private var uploadError: String?
    @State private var avatarItem: PhotosPickerItem?
    @State private var bannerItem: PhotosPickerItem?
    @State private var showAvatarPicker = false
    @State private var showBannerPicker = false
    /// Legacy step flow: pick target → source sheet (camera/library).
    @State private var sourceSheetTarget: SourceTarget?
    @State private var showCamera = false
    @State private var cameraTarget: String?
    @Environment(AppEnvironment.self) private var environment

    /// Legacy ImageCropEditor default-crop parity: normalize EXIF
    /// orientation, center-crop to the target aspect, downscale, re-encode
    /// JPEG, Blossom upload, fill the URL field.
    private func uploadPicked(_ target: String, _ data: Data?) async {
        guard let data, let raw = UIImage(data: data) else { return }
        let ui = raw.normalizedForCrop()
        let isAvatar = target == "avatar"
        let targetW: CGFloat = isAvatar ? 512 : 1500
        let targetH: CGFloat = isAvatar ? 512 : 500
        let targetAspect = targetW / targetH
        let srcW = ui.size.width
        let srcH = ui.size.height
        var cropRect: CGRect
        if srcW / srcH > targetAspect {
            let h = srcH
            let w = srcH * targetAspect
            cropRect = CGRect(x: (srcW - w) / 2, y: 0, width: w, height: h)
        } else {
            let w = srcW
            let h = srcW / targetAspect
            cropRect = CGRect(x: 0, y: (srcH - h) / 2, width: w, height: h)
        }
        guard let cg = ui.cgImage?.cropping(to: cropRect) else { return }
        let cropped = UIImage(cgImage: cg)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let rendered = UIGraphicsImageRenderer(size: CGSize(width: targetW, height: targetH), format: format).image { _ in
            cropped.draw(in: CGRect(x: 0, y: 0, width: targetW, height: targetH))
        }
        guard let jpeg = rendered.jpegData(compressionQuality: 0.88) else { return }
        uploadingTarget = target
        uploadError = nil
        do {
            let receipt = try await BlossomUploader().upload(
                bytes: jpeg, mimeType: "image/jpeg",
                identity: environment.identityStore,
                serverUrl: "https://blossom.primal.net"
            )
            if isAvatar { picture = receipt.url } else { banner = receipt.url }
        } catch {
            uploadError = "Upload failed — check your connection and try again."
        }
        uploadingTarget = nil
    }

    var body: some View {
        VStack(spacing: 0) {
            pageHeader
            content
                .padding(.horizontal, BitOSTheme.Spacing.screen)
                .padding(.bottom, BitOSTheme.Spacing.base)
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .background(BitOSTheme.background)
        .preferredColorScheme(.dark)
        .sheet(item: $sourceSheetTarget) { target in
            sourceSheet(target: target.id)
        }
        .fullScreenCover(isPresented: $showCamera) { cameraPicker }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            name = initialProfile?.name ?? ""
            displayName = initialProfile?.displayName ?? ""
            about = initialProfile?.about ?? ""
            nip05 = initialProfile?.nip05 ?? ""
            lud16 = initialProfile?.lud16 ?? ""
            picture = initialProfile?.picture ?? ""
            banner = initialProfile?.banner ?? ""
            website = initialProfile?.website ?? ""
        }
    }

    /// Legacy editor page chrome: back chevron + inline title.
    private var pageHeader: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            Button(action: onClose) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .frame(width: 36, height: 36)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Back")
            Text("Edit profile")
                .font(.system(size: 17, weight: .bold))
            Spacer()
        }
        .padding(.horizontal, BitOSTheme.Spacing.md)
        .padding(.vertical, BitOSTheme.Spacing.xs + 2)
    }

    /// Legacy `_chooseImageSource` parity: camera (when available) or
    /// photo library, as a bottom-sheet step before the picker.
    private func sourceSheet(target: String) -> some View {
        var entries: [AppMenuEntry] = [
            .item(AppMenuItem(id: "library", label: "Choose from library", systemImage: "photo.on.rectangle")),
        ]
        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            entries.insert(.item(AppMenuItem(id: "camera", label: "Take photo", systemImage: AppIcons.camera)), at: 0)
        }
        return AppBottomSheetMenu(title: "Choose photo", entries: entries) { id in
            sourceSheetTarget = nil
            if id == "camera" {
                cameraTarget = target
                showCamera = true
            } else if target == "avatar" {
                showAvatarPicker = true
            } else {
                showBannerPicker = true
            }
        }
    }

    private var cameraPicker: some View {
        CameraPicker { data in
            showCamera = false
            if let data, let target = cameraTarget {
                cameraTarget = nil
                Task { await uploadPicked(target, data) }
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        // Live header preview + legacy form card: single-column full-width
        // fields, compact system-style inputs, bio counter, full kind-0 set.
        ScrollView {
            VStack(spacing: BitOSTheme.Spacing.md) {
                headerPreview
                if published {
                    Label { Text("Profile published \u{2713}") } icon: { AppIcons.image(for: AppIcons.checkCircle) }
                        .foregroundStyle(BitOSTheme.success)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else if case .rejected(let detail) = publisher.result {
                    Text(detail ?? "Relays rejected the profile.").font(.caption).foregroundStyle(BitOSTheme.error)
                }

                field("Username", "username", $name)
                field("Display name", "Your name", $displayName)
                VStack(alignment: .leading, spacing: 4) {
                    fieldLabel("Bio")
                    FormTextField(placeholder: "Tell the world about yourself\u{2026}", text: $about, axis: .vertical)
                        .lineLimit(3)
                    Text("\(about.count) / 300 characters")
                        .font(.system(size: 11))
                        .foregroundStyle(BitOSTheme.textTertiary)
                }
                if let uploadError {
                    Text(uploadError).font(.system(size: 12)).foregroundStyle(BitOSTheme.error)
                }
                field(uploadingTarget == "avatar" ? "Uploading\u{2026} (avatar URL)" : "Avatar picture URL", "https://\u{2026}", $picture)
                field(uploadingTarget == "banner" ? "Uploading\u{2026} (banner URL)" : "Banner picture URL", "https://\u{2026}", $banner)
                field("Website", "https://example.com", $website)
                field("NIP-05", "name@example.com", $nip05)
                field("Lightning address", "name@getalby.com", $lud16)

                Text("Publishes a signed profile event to your relays; the change appears once confirmed.")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 4)

                // Legacy save: full-width pill, spinner while publishing.
                Button {
                    Task {
                        await publisher.publishProfile(
                            name: name, displayName: displayName, about: about,
                            picture: picture, nip05: nip05, lud16: lud16,
                            banner: banner, website: website
                        )
                        published = true
                    }
                } label: {
                    HStack(spacing: 8) {
                        if publisher.busy {
                            ProgressView()
                                .tint(.white)
                                .controlSize(.small)
                        }
                        Text(publisher.busy ? "Saving\u{2026}" : "Save changes")
                            .font(.system(size: 15, weight: .bold))
                    }
                    .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.white)
                .background(Capsule(style: .continuous).fill(BitOSTheme.accent))
                // Media must be uploaded and hash-verified before signing.
                .disabled(publisher.busy || uploadingTarget != nil || name.isEmpty && displayName.isEmpty)
                .padding(.top, BitOSTheme.Spacing.sm)
            }
        }
    }

    // MARK: - Live header preview (legacy _ProfileHeaderPreview parity)

    /// Banner preview (120pt, radius 12) with the "Change banner" pill, the
    /// hex avatar (88pt, lifted -36) with its camera chip, and the identity
    /// row — exactly what the profile page will show once saved.
    private var headerPreview: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .bottomTrailing) {
                bannerPreviewMedia
                    .frame(height: 120)
                    .frame(maxWidth: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous))
                changeBannerPill
                    .padding(BitOSTheme.Spacing.sm)
            }
            VStack(spacing: BitOSTheme.Spacing.xs + 2) {
                avatarWithCameraChip
                identityPreviewRow
            }
            // Lift exactly half the avatar (88/2): the hexagon's center
            // sits ON the banner bottom edge — same line as the hero.
            .offset(y: -44)
            .padding(.bottom, -44)
        }
        .padding(.top, BitOSTheme.Spacing.sm)
        .padding(.bottom, BitOSTheme.Spacing.lg)
        .photosPicker(isPresented: $showAvatarPicker, selection: $avatarItem, matching: .images)
        .photosPicker(isPresented: $showBannerPicker, selection: $bannerItem, matching: .images)
        .onChange(of: avatarItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) {
                    await uploadPicked("avatar", data)
                }
                avatarItem = nil
            }
        }
        .onChange(of: bannerItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) {
                    await uploadPicked("banner", data)
                }
                bannerItem = nil
            }
        }
    }

    @ViewBuilder
    private var bannerPreviewMedia: some View {
        ZStack {
            if let url = URL(string: banner), !banner.isEmpty {
                AsyncImage(url: url) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    bannerPlaceholder
                }
            } else {
                bannerPlaceholder
            }
            if uploadingTarget == "banner" {
                Color.black.opacity(0.45)
                VStack(spacing: 6) {
                    ProgressView().tint(.white)
                    Text("Uploading")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white)
                }
            }
        }
    }

    private var bannerPlaceholder: some View {
        ZStack {
            RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous)
                .fill(BitOSTheme.accent.opacity(0.15))
            AppIcons.image(for: "photo.badge.plus")
                .font(.system(size: 40, weight: .medium))
                .foregroundStyle(BitOSTheme.accent.opacity(0.40))
        }
    }

    private var changeBannerPill: some View {
        Button {
            sourceSheetTarget = SourceTarget(id: "banner")
        } label: {
            HStack(spacing: 4) {
                AppIcons.image(for: AppIcons.camera)
                    .font(.system(size: 12, weight: .semibold))
                Text("Change banner")
                    .font(.system(size: 11, weight: .semibold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm).fill(.black.opacity(0.60)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Change banner photo")
    }

    private var avatarWithCameraChip: some View {
        Button {
            sourceSheetTarget = SourceTarget(id: "avatar")
        } label: {
            ZStack(alignment: .bottom) {
                HexAvatarView(
                    pubkey: identity.account?.pubkeyHex ?? "",
                    size: 88,
                    imageURL: safeProfilePictureURL(picture.isEmpty ? nil : picture),
                    label: displayName.isEmpty ? name : displayName
                )
                .shadow(color: .black.opacity(0.18), radius: 12, y: 5)
                .shadow(color: BitOSTheme.accent.opacity(0.14), radius: 8)
                // Camera chip — bottom-center-left, clear of the ⚡ slot.
                ZStack {
                    Circle().fill(BitOSTheme.accent)
                    Circle().strokeBorder(BitOSTheme.background, lineWidth: 2)
                    if uploadingTarget == "avatar" {
                        ProgressView()
                            .tint(.white)
                            .controlSize(.small)
                    } else {
                        AppIcons.image(for: AppIcons.camera)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                }
                .frame(width: 28, height: 28)
                .offset(x: -14, y: 6)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(uploadingTarget == "avatar" ? "Uploading avatar" : "Change profile picture")
    }

    private var identityPreviewRow: some View {
        VStack(spacing: 2) {
            HStack(spacing: 4) {
                Text(displayName.isEmpty ? (name.isEmpty ? "Anonymous" : name) : displayName)
                    .font(.system(size: 20, weight: .heavy))
                    .lineLimit(1)
                if !nip05.isEmpty {
                    Image(systemName: AppIcons.checkCircle)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(BitOSTheme.cyan)
                }
            }
            if !name.isEmpty {
                Text("@\(name)")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(BitOSTheme.accent)
            }
        }
    }

    private func fieldLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(BitOSTheme.textSecondary)
    }

    private func field(_ label: String, _ placeholder: String, _ binding: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            fieldLabel(label)
            FormTextField(placeholder: placeholder, text: binding)
        }
    }
}

private extension UIImage {
    /// Redraws non-up orientations into `.up` so pixel-space center-cropping
    /// (and the JPEG re-encode) matches what the user saw in the picker.
    func normalizedForCrop() -> UIImage {
        guard imageOrientation != .up else { return self }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = scale
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            draw(in: CGRect(origin: .zero, size: size))
        }
    }
}


/// Identifiable wrapper so `.sheet(item:)` can present the source sheet.
private struct SourceTarget: Identifiable {
    let id: String
}

/// System camera capture (legacy `ImageSource.camera` step). The shot
/// flows through the same crop → upload pipeline as library picks.
private struct CameraPicker: UIViewControllerRepresentable {
    let completion: (Data?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(completion: completion) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let completion: (Data?) -> Void

        init(completion: @escaping (Data?) -> Void) {
            self.completion = completion
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            let data = (info[.originalImage] as? UIImage)?.jpegData(compressionQuality: 0.92)
            completion(data)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            completion(nil)
        }
    }
}


/// Compact brand input (legacy Flutter `InputDecorationTheme` parity):
/// filled surfaceElevated, 12° radius, 2pt accent focused border, 14pt
/// text, tight h12/v9 padding — the system-design field size.
struct FormTextField: View {
    let placeholder: String
    @Binding var text: String
    var axis: Axis = .horizontal
    @FocusState private var focused: Bool

    var body: some View {
        TextField(placeholder, text: $text, axis: axis == .vertical ? .vertical : .horizontal)
            .font(.system(size: 14))
            .foregroundStyle(BitOSTheme.textPrimary)
            .focused($focused)
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .frame(minHeight: 44, alignment: axis == .vertical ? .top : .center)
            .background(
                RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous)
                    .fill(BitOSTheme.surfaceElevated)
            )
            .overlay(
                RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous)
                    .strokeBorder(focused ? BitOSTheme.accent : BitOSTheme.border, lineWidth: focused ? 2 : 1)
            )
    }
}
