import BusinessCore
import PhotosUI
import SwiftUI

/**
 * APP-008 note composer PAGE (legacy Flutter `CreateView` parity — a full
 * screen, not a sheet): author header, mention-aware field with @-autocomplete
 * (end-of-text cursor approximation: SwiftUI TextEditor exposes no public
 * cursor API), ≤4 image grid (gallery picks + URLs), content-warning field,
 * upload status, toolbar (image/URL/CW/hashtag/emoji/PoW) with the
 * 4,000/16,000 character counter, and the published success state. All
 * rules execute in shared `ComposerRules` through the bridge; uploads run
 * hash-verified through Blossom before anything is signed.
 */
struct ComposerScreen: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity

    let onClose: () -> Void

    /** APP-007 remix entry: seed tags (JSON `[[name, …], …]`) merged into
     *  the published set; a seeded session never touches the saved draft. */
    var baseTagsJson: String = "[]"

    private let bridge = BusinessCoreBridge()
    private let uploader = BlossomUploader()
    private let blossomServer = "https://blossom.primal.net"

    private var seeded: Bool { baseTagsJson != "[]" && !baseTagsJson.isEmpty }

    struct PickedImage: Identifiable {
        let id = UUID()
        let data: Data
        let mimeType: String
    }

    struct MentionPick: Identifiable, Equatable {
        let name: String
        let pubkey: String
        let npub: String
        let picture: String?
        var id: String { pubkey }
    }

    @State private var text = ""
    @State private var trackedMentions: [(name: String, npub: String)] = []
    @State private var remoteImageUrls: [String] = []
    @State private var pickedImages: [PickedImage] = []
    @State private var contentWarningOn = false
    @State private var contentWarningReason = ""
    @State private var uploadStatus = ""
    @State private var errorMessage = ""
    @State private var published = false
    @State private var busy = false
    @State private var powTarget = 0
    @State private var powOutcome: PowOutcome?
    @State private var powSheet = false
    @State private var emojiSheet = false
    @State private var pollSheet = false
    @State private var urlAlert = false
    @State private var urlField = ""
    @State private var pickerItem: PhotosPickerItem?
    @State private var suggestions: [MentionPick] = []
    @State private var showDiscardConfirm = false
    @State private var restoredDraft = false

    private let draftKey = "bitos_composer_draft"

    private var mediaCount: Int { remoteImageUrls.count + pickedImages.count }
    private var canAddImage: Bool { mediaCount < 4 }

    private var counterLabel: String {
        let map = bridge.composerCounter(length: Int32(text.count)) as? [String: Any] ?? [:]
        return (map["label"] as? String) ?? ""
    }

    private var canPublish: Bool {
        !busy && !published &&
            (!text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || mediaCount > 0) &&
            text.count <= 16_000
    }

    var body: some View {
        NavigationStack {
            Group {
                if published {
                    publishedState
                } else {
                    editorContent
                }
            }
            .background(BitOSTheme.background)
            .navigationTitle("New note")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Close") {
                        if !published && !draftIsEmpty { showDiscardConfirm = true } else { onClose() }
                    }
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .confirmationDialog("Discard draft?", isPresented: $showDiscardConfirm, titleVisibility: .visible) {
                        Button("Discard", role: .destructive) {
                            clearDraft()
                            onClose()
                        }
                        Button("Keep editing", role: .cancel) {}
                    } message: {
                        Text("Your draft is saved on this device. Discard it and close the composer?")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await publish() }
                    } label: {
                        if busy {
                            ProgressView().tint(BitOSTheme.accent)
                        } else {
                            Text("Publish")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(canPublish ? BitOSTheme.accent : BitOSTheme.textTertiary)
                        }
                    }
                    .disabled(!canPublish)
                    .accessibilityLabel("Publish note")
                }
            }
        }
        .preferredColorScheme(.dark)
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   canAddImage, let mime = item.supportedContentTypes.first?.preferredMIMEType {
                    pickedImages.append(PickedImage(data: data, mimeType: mime))
                }
                pickerItem = nil
            }
        }
        .onChange(of: text) { _, _ in refreshSuggestions(); if !seeded { saveDraft() } }
        .onChange(of: remoteImageUrls) { _, _ in if !seeded { saveDraft() } }
        .onChange(of: contentWarningReason) { _, _ in if !seeded { saveDraft() } }
        .onChange(of: contentWarningOn) { _, _ in if !seeded { saveDraft() } }
        .onAppear {
            guard !restoredDraft else { return }
            restoredDraft = true
            if !seeded { restoreDraft() }
        }
        .sheet(isPresented: $powSheet) {
            powSheetContent
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $emojiSheet) {
            emojiSheetContent
                .presentationDetents([.medium])
        }
        .sheet(isPresented: $pollSheet) {
            PollComposerSheet(publishing: busy) { question, options in
                publishPoll(question: question, options: options)
                pollSheet = false
            }
            .presentationDetents([.medium, .large])
        }
        .alert("Add image URL", isPresented: $urlAlert) {
            TextField("https://example.com/image.jpg", text: $urlField)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
            Button("Done") {
                let trimmed = urlField.trimmingCharacters(in: .whitespacesAndNewlines)
                if (trimmed.hasPrefix("https://") || trimmed.hasPrefix("http://")) && canAddImage {
                    remoteImageUrls.append(trimmed)
                }
                urlField = ""
            }
            Button("Cancel", role: .cancel) { urlField = "" }
        }
    }

    // MARK: - Editor

    private var editorContent: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
                    authorHeader
                    TextEditor(text: $text)
                        .font(.system(size: 16))
                        .frame(minHeight: 110)
                        .scrollContentBackground(.hidden)
                        .overlay(alignment: .topLeading) {
                            if text.isEmpty {
                                Text("What's happening?")
                                    .font(.system(size: 16))
                                    .foregroundStyle(BitOSTheme.textTertiary)
                                    .padding(.top, 8)
                                    .padding(.leading, 4)
                                    .allowsHitTesting(false)
                            }
                        }
                    if !suggestions.isEmpty {
                        mentionSuggestions
                    }
                    if mediaCount > 0 {
                        mediaGrid
                    }
                    if contentWarningOn {
                        contentWarningField
                    }
                    if !errorMessage.isEmpty {
                        Text(errorMessage)
                            .font(.footnote)
                            .foregroundStyle(BitOSTheme.error)
                    }
                    if let result = environment.notePublisher.result, result != .published, !published {
                        Text(publishFailureText(result))
                            .font(.footnote)
                            .foregroundStyle(BitOSTheme.error)
                    }
                }
                .padding(.horizontal, BitOSTheme.Spacing.screen)
                .padding(.vertical, BitOSTheme.Spacing.md)
            }
            if !uploadStatus.isEmpty {
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    ProgressView().tint(BitOSTheme.accent)
                    Text(uploadStatus)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(BitOSTheme.accent)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, BitOSTheme.Spacing.base)
                .padding(.vertical, 6)
                .background(BitOSTheme.accent.opacity(0.08))
            }
            toolbar
        }
    }

    private var authorHeader: some View {
        let account = identity.account
        let profile = account.flatMap { environment.feedStore.profiles[$0.pubkeyHex] }
        return HStack(spacing: BitOSTheme.Spacing.md) {
            PubkeyAvatarView(
                pubkey: account?.pubkeyHex ?? "",
                size: 40,
                label: profile?.bestDisplayName
            )
            VStack(alignment: .leading, spacing: 1) {
                Text(profile?.bestDisplayName ?? "You")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .lineLimit(1)
                Text("Now")
                    .font(.system(size: 10))
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
            Spacer()
        }
    }

    private var mentionSuggestions: some View {
        VStack(spacing: 0) {
            ForEach(suggestions) { suggestion in
                Button {
                    pickMention(suggestion)
                } label: {
                    HStack(spacing: BitOSTheme.Spacing.sm) {
                        PubkeyAvatarView(pubkey: suggestion.pubkey, size: 28)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(suggestion.name)
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(BitOSTheme.textPrimary)
                                .lineLimit(1)
                            Text(suggestion.npub.prefix(12) + "…")
                                .font(.system(size: 10))
                                .foregroundStyle(BitOSTheme.textTertiary)
                        }
                        Spacer()
                    }
                    .padding(.horizontal, BitOSTheme.Spacing.md)
                    .padding(.vertical, 6)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Mention \(suggestion.name)")
            }
        }
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(BitOSTheme.surfaceElevated)
        )
    }

    private var mediaGrid: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                ForEach(pickedImages) { picked in
                    mediaThumb(label: "image") {
                        pickedImages.removeAll { $0.id == picked.id }
                    }
                }
                ForEach(remoteImageUrls, id: \.self) { url in
                    mediaThumb(label: String(url.split(separator: "/").last ?? "").prefix(14).description) {
                        remoteImageUrls.removeAll { $0 == url }
                    }
                }
            }
        }
        .frame(height: 96)
    }

    private func mediaThumb(label: String, onRemove: @escaping () -> Void) -> some View {
        ZStack(alignment: .topTrailing) {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(BitOSTheme.surfaceElevated)
                .frame(width: 96, height: 96)
                .overlay {
                    VStack(spacing: 2) {
                        AppIcons.image(for: AppIcons.photo)
                            .font(.system(size: 18))
                            .foregroundStyle(BitOSTheme.textTertiary)
                        Text(label)
                            .font(.system(size: 9))
                            .foregroundStyle(BitOSTheme.textTertiary)
                            .lineLimit(1)
                    }
                }
            Button(action: onRemove) {
                Image(systemName: AppIcons.close)
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(5)
                    .background(Circle().fill(.black.opacity(0.6)))
            }
            .padding(4)
            .accessibilityLabel("Remove attachment")
        }
    }

    private var contentWarningField: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
            HStack(spacing: 6) {
                AppIcons.image(for: AppIcons.reportSpam)
                    .font(.system(size: 13))
                    .foregroundStyle(BitOSTheme.warning)
                Text("Content warning")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(BitOSTheme.warning)
            }
            BitosField("Why is this sensitive? (optional)", text: $contentWarningReason)
                .font(.footnote)
                .onChange(of: contentWarningReason) { _, value in
                    if value.count > 120 { contentWarningReason = String(value.prefix(120)) }
                }
        }
        .padding(BitOSTheme.Spacing.sm)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(BitOSTheme.warning.opacity(0.1))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(BitOSTheme.warning.opacity(0.3))
        )
    }

    private var toolbar: some View {
        HStack(spacing: 2) {
            toolbarButton(AppIcons.photo, "Attach image", enabled: canAddImage, active: mediaCount > 0, badge: mediaCount > 0 ? "\(mediaCount)" : nil) {
                emojiSheet = false
                powSheet = false
                // PhotosPicker lives in the overlay below; toggle via state.
                pickerPrompt = true
            }
            toolbarButton(AppIcons.globe, "Add image URL", enabled: canAddImage) { urlAlert = true }
            toolbarButton(AppIcons.mute, "Content warning", active: contentWarningOn) {
                contentWarningOn.toggle()
                if !contentWarningOn { contentWarningReason = "" }
            }
            toolbarButton("number", "Insert hashtag") { insertAtEnd { bridge.composerInsertHashtag(text: $0, cursor: Int32($0.count)) } }
            toolbarButton("face.smiling", "Insert emoji") { emojiSheet = true }
            toolbarButton(AppIcons.qrCode, "Proof of work", enabled: pickedImages.isEmpty, active: powOutcome != nil || powTarget > 0,
                          badge: powOutcome.map { "\($0.targetDifficulty)" } ?? (powTarget > 0 ? "\(powTarget)" : nil)) { powSheet = true }
            toolbarButton("chart.bar", "Create poll") { pollSheet = true }
            Spacer(minLength: BitOSTheme.Spacing.sm)
            charCounter
        }
        .padding(.horizontal, 8)
        .padding(.vertical, BitOSTheme.Spacing.sm)
        .background(BitOSTheme.surface)
        .overlay(alignment: .top) { Divider().background(BitOSTheme.divider) }
        .photosPicker(isPresented: $pickerPrompt, selection: $pickerItem, matching: .images)
    }

    @State private var pickerPrompt = false

    private func toolbarButton(_ symbol: String, _ label: String, enabled: Bool = true, active: Bool = false, badge: String? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
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

    private var charCounter: some View {
        let map = bridge.composerCounter(length: Int32(text.count)) as? [String: Any] ?? [:]
        let ratio = (map["ratio"] as? KotlinDouble)?.doubleValue ?? (map["ratio"] as? NSNumber)?.doubleValue ?? 0
        let near = (map["near"] as? KotlinBoolean)?.boolValue ?? false
        let over = (map["over"] as? KotlinBoolean)?.boolValue ?? false
        let label = (map["label"] as? String) ?? ""
        return Group {
            if !label.hasPrefix("0 /") {
                HStack(spacing: 4) {
                    Text(label)
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(over ? BitOSTheme.error : near ? BitOSTheme.warning : BitOSTheme.textSecondary)
                    Circle()
                        .trim(from: 0, to: ratio)
                        .stroke(over ? BitOSTheme.error : near ? BitOSTheme.warning : BitOSTheme.textSecondary,
                                style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .frame(width: 16, height: 16)
                }
            }
        }
    }

    // MARK: - Sheets

    private var powSheetContent: some View {
        let content = composedContent()
        let tagsJson = derivedTags()
        return PowCard(
            target: $powTarget,
            mineChunk: { createdAt, startNonce, attempts in
                await environment.notePublisher.minePowChunkWithTags(
                    content: content,
                    targetDifficulty: Int32(powTarget),
                    createdAt: createdAt,
                    startNonce: startNonce,
                    attempts: attempts,
                    tagsJson: tagsJson
                )
            },
            onMined: { outcome in
                powOutcome = outcome
                powSheet = false
            }
        )
        .padding(.horizontal, BitOSTheme.Spacing.screen)
        .padding(.bottom, BitOSTheme.Spacing.xl)
    }

    private var emojiSheetContent: some View {
        let emojis = bridge.composerEmojis() as? [String] ?? []
        return VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            Text("Insert emoji")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(BitOSTheme.textPrimary)
                .padding(.horizontal, BitOSTheme.Spacing.screen)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 8), spacing: BitOSTheme.Spacing.sm) {
                ForEach(emojis, id: \.self) { emoji in
                    Button {
                        text += (text.isEmpty || text.hasSuffix(" ") || text.hasSuffix("\n")) ? emoji : " \(emoji)"
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

    // MARK: - Actions

    /// SwiftUI TextEditor has no public cursor API: mentions detect and
    /// insert at the end of the text (documented approximation).
    private func refreshSuggestions() {
        let query = bridge.composerMentionQuery(text: text, cursor: Int32(text.count))
        guard !query.isEmpty else {
            if !suggestions.isEmpty { suggestions = [] }
            return
        }
        let profiles = environment.feedStore.profiles.values.map { profile in
            [
                "pubkey": profile.pubkey,
                "name": profile.name ?? "",
                "displayName": profile.displayName ?? "",
                "picture": profile.picture ?? "",
            ] as [String: String]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: profiles),
              let json = String(data: data, encoding: .utf8),
              let out = (bridge.composerMentionSuggestions(query: query, profilesJson: json) as String?),
              let outData = out.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: outData) as? [[String: Any]] else {
            suggestions = []
            return
        }
        suggestions = array.compactMap { obj in
            guard let name = obj["name"] as? String,
                  let pubkey = obj["pubkey"] as? String,
                  let npub = obj["npub"] as? String else { return nil }
            let picture = (obj["picture"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            return MentionPick(name: name, pubkey: pubkey, npub: npub, picture: picture)
        }
    }

    private func pickMention(_ suggestion: MentionPick) {
        guard let range = text.range(of: "@\(currentQueryTail)", options: .backwards) else {
            suggestions = []
            return
        }
        text = text.replacingCharacters(in: range, with: "@\(suggestion.name) ")
        trackedMentions.append((name: suggestion.name, npub: suggestion.npub))
        suggestions = []
    }

    private var currentQueryTail: String {
        bridge.composerMentionQuery(text: text, cursor: Int32(text.count))
    }

    private func insertAtEnd(_ transform: (String) -> [String: Any]) {
        let map = transform(text)
        if let next = map["text"] as? String { text = next }
    }

    private func composedContent() -> String {
        let tracked = trackedMentions.map { ["name": $0.name, "npub": $0.npub] as [String: String] }
        let rewritten: String
        if tracked.isEmpty {
            rewritten = text
        } else if let data = try? JSONSerialization.data(withJSONObject: tracked),
                  let json = String(data: data, encoding: .utf8) {
            rewritten = bridge.composerRewriteMentions(content: text, trackedJson: json)
        } else {
            rewritten = text
        }
        let urls = remoteImageUrls
        if urls.isEmpty { return rewritten }
        guard let data = try? JSONSerialization.data(withJSONObject: urls),
              let json = String(data: data, encoding: .utf8) else {
            return rewritten
        }
        return bridge.composerComposeContent(text: rewritten, urlsJson: json)
    }

    private func derivedTags() -> String {
        let reason = contentWarningOn && !contentWarningReason.trimmingCharacters(in: .whitespaces).isEmpty
            ? contentWarningReason.trimmingCharacters(in: .whitespaces) : nil
        return bridge.composerDeriveTags(content: composedContent(), contentWarningReason: reason) ?? "[]"
    }

    private func publishFailureText(_ result: PublishResult) -> String {
        switch result {
        case .signingRefused: return "Signing refused — add an identity first."
        case .rejected: return "Relays rejected the note."
        case .timeout: return "No relay receipt before timeout."
        default: return "Publish failed."
        }
    }

    // MARK: - Draft persistence (APP-008; shared contract via bridge)

    private var draftIsEmpty: Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            remoteImageUrls.isEmpty &&
            (contentWarningOn ? contentWarningReason.trimmingCharacters(in: .whitespaces).isEmpty : true)
    }

    private func saveDraft() {
        guard !seeded else { return }
        let tracked = trackedMentions.map { ["n": $0.name, "u": $0.npub] as [String: String] }
        guard let trackedData = try? JSONSerialization.data(withJSONObject: tracked),
              let trackedJson = String(data: trackedData, encoding: .utf8),
              let urlsData = try? JSONSerialization.data(withJSONObject: remoteImageUrls),
              let urlsJson = String(data: urlsData, encoding: .utf8) else { return }
        let wire = bridge.composerDraftEncode(
            text: text,
            urlsJson: urlsJson,
            cwReason: contentWarningOn ? contentWarningReason : "",
            trackedJson: trackedJson,
            powTarget: Int32(powTarget)
        )
        UserDefaults.standard.set(wire, forKey: draftKey)
    }

    private func restoreDraft() {
        guard !seeded else { return }
        guard let wire = UserDefaults.standard.string(forKey: draftKey),
              let map = bridge.composerDraftDecode(json: wire) as? [String: Any] else { return }
        text = (map["text"] as? String) ?? ""
        remoteImageUrls = (map["urls"] as? [String]) ?? []
        let cw = (map["cw"] as? String) ?? ""
        contentWarningOn = !cw.isEmpty
        contentWarningReason = cw
        powTarget = (map["pow"] as? KotlinInt)?.intValue ?? 0
        let mentions = (map["mentions"] as? [[String: Any]]) ?? []
        trackedMentions = mentions.compactMap { obj in
            guard let name = obj["n"] as? String, let npub = obj["u"] as? String else { return nil }
            return (name: name, npub: npub)
        }
    }

    private func clearDraft() {
        guard !seeded else { return }
        UserDefaults.standard.removeObject(forKey: draftKey)
    }

    /// APP-008 poll publish: validated tags via the bridge, then the
    /// standard tags-aware note path (kind-1 question + poll_option tags).
    private func publishPoll(question: String, options: [String]) {
        guard let data = try? JSONSerialization.data(withJSONObject: options),
              let optionsJson = String(data: data, encoding: .utf8),
              let tagsJson = (bridge.composePollTags(question: question, optionsJson: optionsJson) as String?) else {
            errorMessage = "Poll needs a question and 2–6 choices (legacy limits)."
            return
        }
        busy = true
        Task {
            defer { busy = false }
            await environment.notePublisher.publishNote(content: question.trimmingCharacters(in: .whitespacesAndNewlines), tagsJson: tagsJson)
            if environment.notePublisher.result == .published {
                published = true
                clearDraft()
            } else if let failure = environment.notePublisher.result {
                errorMessage = publishFailureText(failure)
            }
        }
    }

    private func publish() async {
        guard canPublish else { return }
        busy = true
        errorMessage = ""
        uploadStatus = ""
        defer {
            busy = false
            uploadStatus = ""
        }
        do {
            var uploadedUrls: [String] = []
            for (index, picked) in pickedImages.enumerated() {
                uploadStatus = pickedImages.count == 1 ? "Uploading media…" : "Uploading media \(index + 1)/\(pickedImages.count)…"
                let uploaded = try await uploader.upload(
                    bytes: picked.data,
                    mimeType: picked.mimeType,
                    identity: identity,
                    serverUrl: blossomServer
                )
                uploadedUrls.append(uploaded.url)
            }
            uploadStatus = "Publishing…"
            // Base already carries remote URLs (composedContent); only the
            // freshly uploaded picks join here — single append, and the PoW
            // template (mined over composedContent) stays byte-exact.
            let contentWithMedia: String
            if uploadedUrls.isEmpty {
                contentWithMedia = composedContent()
            } else if let data = try? JSONSerialization.data(withJSONObject: uploadedUrls),
                      let urlsJson = String(data: data, encoding: .utf8) {
                contentWithMedia = bridge.composerComposeContent(text: composedContent(), urlsJson: urlsJson)
            } else {
                contentWithMedia = composedContent()
            }
            let tagsJson = bridge.composerDeriveTags(
                content: contentWithMedia,
                contentWarningReason: contentWarningOn ? contentWarningReason : nil
            ) ?? "[]"
            // Seed tags (remix/attribution) merge first, deduped by shared rule.
            let mergedTagsJson = seeded
                ? bridge.mergeTagsJson(baseJson: baseTagsJson, derivedJson: tagsJson)
                : tagsJson
            if let outcome = powOutcome {
                await environment.notePublisher.publishPowNote(
                    content: contentWithMedia,
                    nonce: outcome.nonce,
                    targetDifficulty: Int32(outcome.targetDifficulty),
                    createdAt: outcome.createdAt,
                    tagsJson: mergedTagsJson
                )
            } else {
                await environment.notePublisher.publishNote(content: contentWithMedia, tagsJson: mergedTagsJson)
            }
            if environment.notePublisher.result == .published {
                published = true
                clearDraft()
            } else if let failure = environment.notePublisher.result {
                errorMessage = publishFailureText(failure)
            }
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    /// APP-008 poll composer (legacy `PollComposer` parity): question ≤280,
/// 2–6 choices ≤80, live counters, publish through the tags path.
private struct PollComposerSheet: View {
    let publishing: Bool
    let onPost: (String, [String]) -> Void

    @State private var question = ""
    @State private var options: [String] = ["", ""]

 private var clean: [String] { options.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty } }

    private var canPost: Bool {
        let q = question.trimmingCharacters(in: .whitespacesAndNewlines)
        return !publishing && !q.isEmpty && q.count <= 280 &&
            clean.count >= 2 && clean.allSatisfy { $0.count <= 80 }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            HStack {
                Text("Create poll")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                Spacer()
                Button("Cancel") { onPost("", []) }.foregroundStyle(BitOSTheme.textSecondary)
                Button {
                    onPost(question, options)
                } label: {
                    Text("Post")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(canPost ? BitOSTheme.accent : BitOSTheme.textTertiary)
                }
                .disabled(!canPost)
                .accessibilityLabel("Post poll")
            }
            BitosField("Question (\(question.count)/280)", text: $question, axis: .vertical)
                .lineLimit(2...4)
                .onChange(of: question) { _, value in
                    if value.count > 280 { question = String(value.prefix(280)) }
                }
            ForEach(Array(options.enumerated()), id: \.offset) { index, _ in
                HStack {
                    TextField("Choice \(index + 1)", text: Binding(
                        get: { options.indices.contains(index) ? options[index] : "" },
                        set: { next in
                            if options.indices.contains(index) {
                                options[index] = String(next.prefix(80))
                            }
                        }
                    ))
                    if options.count > 2 {
                        Button {
                            options.remove(at: index)
                        } label: {
                            AppIcons.image(for: AppIcons.close)
                                .foregroundStyle(BitOSTheme.textSecondary)
                        }
                        .accessibilityLabel("Remove choice")
                    }
                }
            }
            if options.count < 6 {
                Button {
                    options.append("")
                } label: {
                    HStack(spacing: 4) {
                        AppIcons.image(for: AppIcons.add)
                        Text("Add choice")
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(BitOSTheme.accent)
                }
                .accessibilityLabel("Add choice")
            }
            Text("2–6 choices, published as a note with poll tags (legacy wire).")
                .font(.system(size: 11))
                .foregroundStyle(BitOSTheme.textTertiary)
        }
        .padding(.horizontal, BitOSTheme.Spacing.screen)
        .padding(.bottom, BitOSTheme.Spacing.xl)
    }
}

// MARK: - Published state

    private var publishedState: some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
            AppIcons.image(for: AppIcons.checkCircle)
                .font(.system(size: 44))
                .foregroundStyle(BitOSTheme.success)
            Text("Published")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(BitOSTheme.textPrimary)
            Text("Your note is on its way to the relays.")
                .font(.footnote)
                .foregroundStyle(BitOSTheme.textSecondary)
            Button {
                onClose()
            } label: {
                Text("Back to feed")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(BitOSTheme.background)
                    .frame(width: 220)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(BitOSTheme.accent))
            }
            .buttonStyle(.plain)
            Button("New post") {
                environment.notePublisher.dismiss()
                text = ""
                trackedMentions = []
                remoteImageUrls = []
                pickedImages = []
                contentWarningOn = false
                contentWarningReason = ""
                powOutcome = nil
                published = false
                clearDraft()
            }
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(BitOSTheme.accent)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
