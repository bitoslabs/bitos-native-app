import BusinessCore
import PhotosUI
import SwiftUI

/// Profile editing sheet: bounded fields → signed kind-0 through the
/// receipt machine.
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
    @Environment(AppEnvironment.self) private var environment

    /// Legacy ImageCropEditor default-crop parity: center-crop to the
    /// target aspect, downscale, re-encode JPEG, Blossom upload, fill URL.
    private func uploadPicked(_ target: String, _ data: Data?) async {
        guard let data, let ui = UIImage(data: data) else { return }
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
        NavigationStack {
            content
                .padding(BitOSTheme.Spacing.screen)
                .background(BitOSTheme.background)
                .navigationTitle("Edit profile")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { SheetCloseButton(action: onClose) }
                }
        }
        .preferredColorScheme(.dark)
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

    @ViewBuilder
    private var content: some View {
        // Web-form parity (settings/+page.svelte): labeled uppercase
        // fields, 2-col grids, bio counter, full kind-0 field set.
        ScrollView {
            VStack(spacing: BitOSTheme.Spacing.sm) {
                if published {
                    Label { Text("Profile published \u{2713}") } icon: { AppIcons.image(for: AppIcons.checkCircle) }
                        .foregroundStyle(BitOSTheme.success)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else if case .rejected(let detail) = publisher.result {
                    Text(detail ?? "Relays rejected the profile.").font(.caption).foregroundStyle(BitOSTheme.error)
                }

                HStack(spacing: BitOSTheme.Spacing.md) {
                    field("Username", "username", $name)
                    field("Display name", "Your name", $displayName)
                }
                VStack(alignment: .leading, spacing: 4) {
                    fieldLabel("Bio")
                    TextField("Tell the world about yourself\u{2026}", text: $about, axis: .vertical)
                        .lineLimit(3)
                        .textFieldStyle(.roundedBorder)
                    Text("\(about.count) / 300 characters")
                        .font(.system(size: 11))
                        .foregroundStyle(BitOSTheme.textTertiary)
                }
                if let uploadError {
                    Text(uploadError).font(.system(size: 12)).foregroundStyle(BitOSTheme.error)
                }
                HStack(spacing: BitOSTheme.Spacing.md) {
                    VStack(alignment: .leading, spacing: 4) {
                        fieldLabel(uploadingTarget == "avatar" ? "Uploading\u{2026}" : "Avatar")
                        PhotosPicker(selection: $avatarItem, matching: .images) {
                            Image(systemName: "photo.badge.plus")
                                .foregroundStyle(BitOSTheme.accent)
                        }
                        .frame(height: 30)
                        TextField("https://\u{2026}", text: $picture)
                            .textFieldStyle(.roundedBorder)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        fieldLabel(uploadingTarget == "banner" ? "Uploading\u{2026}" : "Banner")
                        PhotosPicker(selection: $bannerItem, matching: .images) {
                            Image(systemName: "photo.badge.plus")
                                .foregroundStyle(BitOSTheme.accent)
                        }
                        .frame(height: 30)
                        TextField("https://\u{2026}", text: $banner)
                            .textFieldStyle(.roundedBorder)
                    }
                }
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
                HStack(spacing: BitOSTheme.Spacing.md) {
                    field("Website", "https://example.com", $website)
                    field("NIP-05", "name@example.com", $nip05)
                }
                field("Lightning address", "name@getalby.com", $lud16)

                Text("Publishes a signed profile event to your relays; the change appears once confirmed.")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 4)

                HStack(spacing: BitOSTheme.Spacing.sm) {
                    Button(publisher.busy ? "Saving\u{2026}" : "Save changes") {
                        Task {
                            await publisher.publishProfile(
                                name: name, displayName: displayName, about: about,
                                picture: picture, nip05: nip05, lud16: lud16,
                                banner: banner, website: website
                            )
                            published = true
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
                    .disabled(publisher.busy || name.isEmpty && displayName.isEmpty)
                    Button("Cancel", action: onClose)
                        .buttonStyle(.bordered)
                        .disabled(publisher.busy)
                }
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
            TextField(placeholder, text: binding)
                .textFieldStyle(.roundedBorder)
        }
    }
}
