import BusinessCore
import PhotosUI
import SwiftUI

enum MediaPublishPhase: Sendable, Equatable { case pick, uploading, publishing, done }

struct MediaPublishUiState: Sendable, Equatable {
    var phase: MediaPublishPhase = .pick
    var failure: String?
    var busy = false
}

/**
 * Media import → publish sheet (CAP-005 + PUB media path): photo-library
 * pick → caption → hash-verified Blossom upload → kind-22 through the
 * receipt machine. Oversized files, signer refusal and hash mismatches are
 * surfaced, never swallowed.
 */
struct ImportMediaSheet: View {
    let onClose: () -> Void
    /** Freshly captured take from the camera flow (record → trim); the
     *  sheet opens straight into the caption/publish state for it. */
    var capturedData: Data? = nil
    var capturedMime: String = "video/mp4"
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    @State private var state = MediaPublishUiState()
    @State private var caption = ""
    @State private var altText = ""
    @State private var contentWarningOn = false
    @State private var contentWarningReason = Self.contentWarningReasons.first ?? "Flashing imagery"
    @State private var pickerItem: PhotosPickerItem?
    @State private var pickedData: Data?
    @State private var pickedMime = "video/mp4"
    @State private var seededCapture = false

    /// NIP-36 reason choices (reference scr-details select).
    private static let contentWarningReasons = ["Flashing imagery", "Sensitive topic", "Loud audio"]

    private let maxBytes = 64 * 1024 * 1024

    var body: some View {
        NavigationStack {
            content
                .padding(BitOSTheme.Spacing.screen)
                .background(BitOSTheme.background)
                .navigationTitle("New video")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { SheetCloseButton(action: onClose) }
                }
                .onAppear {
                    guard !seededCapture, let capturedData else { return }
                    seededCapture = true
                    if capturedData.count > maxBytes {
                        state.failure = "Recording exceeds the 64MB limit."
                    } else {
                        pickedData = capturedData
                        pickedMime = capturedMime
                    }
                }
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
    }

    @ViewBuilder
    private var content: some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
            if let failure = state.failure {
                Text(failure).font(.footnote).foregroundStyle(BitOSTheme.error)
            }
            switch state.phase {
            case .pick:
                let pickLabel = pickedData == nil ? "Pick a video from your library" : "Change video"
                PhotosPicker(selection: $pickerItem, matching: .videos) {
                    Label(pickLabel, systemImage: "photo.on.rectangle")
                }
                .buttonStyle(.borderedProminent)
                .tint(BitOSTheme.accent)
                .onChange(of: pickerItem) { _, item in
                    guard let item else { return }
                    Task { @MainActor in
                        if let data = try? await item.loadTransferable(type: Data.self) {
                            if data.count > maxBytes {
                                state.failure = "Media exceeds the 64MB limit."
                                pickedData = nil
                            } else {
                                pickedData = data
                                state.failure = nil
                            }
                        }
                    }
                }
                if let data = pickedData {
                    Text("\(pickedMime) • \(data.count / 1024 / 1024)MB")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                    // Post details (reference scr-details): counter + alt +
                    // content warning ride the existing kind-22 pipeline.
                    VStack(alignment: .leading, spacing: 2) {
                        TextField("Add a caption…", text: $caption)
                            .textFieldStyle(.roundedBorder)
                        Text("\(caption.count) / 2000")
                            .font(.caption2)
                            .foregroundStyle(BitOSTheme.textTertiary)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        TextEditor(text: $altText)
                            .font(.body)
                            .frame(minHeight: 56)
                            .scrollContentBackground(.hidden)
                            .background(BitOSTheme.surface, in: RoundedRectangle(cornerRadius: 8))
                            .accessibilityLabel("Alt text")
                        Text("Alt text · required by your policy (\(altText.count)/200)")
                            .font(.caption2)
                            .foregroundStyle(BitOSTheme.textTertiary)
                    }
                    ContentWarningSection(
                        on: $contentWarningOn,
                        reason: $contentWarningReason,
                        options: Self.contentWarningReasons
                    )
                    Button("Upload & publish") {
                        Task { await publish() }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
                    .disabled(
                        (caption.trimmingCharacters(in: .whitespaces).isEmpty &&
                            altText.trimmingCharacters(in: .whitespaces).isEmpty) || state.busy
                    )
                }
            case .uploading:
                ProgressView().tint(BitOSTheme.accent)
                Text("Uploading (hash-verified)…").font(.subheadline)
            case .publishing:
                ProgressView().tint(BitOSTheme.accent)
                Text("Publishing to relays…").font(.subheadline)
            case .done:
                Label("Published ✓", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(BitOSTheme.success)
                Button("Done") { onClose() }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
            }
            Spacer()
        }
    }

    private func publish() async {
        guard let data = pickedData else { return }
        state.busy = true
        state.phase = .uploading
        state.failure = nil
        do {
            let bridge = (environment.businessCore as? FrameworkBusinessCoreClient)?.bridgeForFollowing() ?? BusinessCoreBridge()
            let uploaded = try await BlossomUploader(bridge: bridge).upload(
                bytes: data, mimeType: pickedMime, identity: identity,
                serverUrl: "https://blossom.primal.net"
            )
            state.phase = .publishing
            await environment.notePublisher.publishMediaNote(
                caption: caption,
                altText: altText,
                contentWarningReason: contentWarningOn ? contentWarningReason : nil,
                url: uploaded.url, hash: uploaded.hash, mime: uploaded.mime, size: uploaded.size
            )
            state.phase = .done
        } catch {
            state.phase = .pick
            state.failure = error.localizedDescription
        }
        state.busy = false
    }
}

/// Content warning gate (reference scr-details): toggle + reason picker.
private struct ContentWarningSection: View {
    @Binding var on: Bool
    @Binding var reason: String
    let options: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Content warning").font(.subheadline.weight(.semibold))
                    Text("Gate playback behind a visible warning")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                Spacer()
                Toggle("", isOn: $on)
                    .labelsHidden()
                    .accessibilityLabel("Content warning")
            }
            if on {
                Picker("Reason", selection: $reason) {
                    ForEach(options, id: \.self) { option in
                        Text(option).tag(option)
                    }
                }
                .pickerStyle(.segmented)
            }
        }
        .padding(BitOSTheme.Spacing.card)
        .background(BitOSTheme.surface, in: RoundedRectangle(cornerRadius: BitOSTheme.Radius.md))
    }
}
