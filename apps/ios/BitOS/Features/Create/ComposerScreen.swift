import BusinessCore
import PhotosUI
import SwiftUI

/**
 * APP-008 note composer PAGE (legacy Flutter `CreateView` parity — a full
 * screen, not a sheet): author header, mention-aware field with @-autocomplete
 * at the real caret (cursor-tracking `ComposerTextEditor`), ≤4 image grid
 * (gallery picks + URLs), content-warning field, upload status, toolbar
 * (image/URL/CW/hashtag/emoji/PoW) with the 4,000/16,000 character counter,
 * and the published success state. All rules execute in shared
 * `ComposerRules` through the bridge; uploads run hash-verified through
 * Blossom before anything is signed.
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
    /// Caret offset in UTF-16 code units — the index basis of the shared
    /// `ComposerRules` string operations the bridge expects.
    @State private var cursor = 0
    @State private var focused = false
    @State private var pendingEdit: ComposerTextEdit?
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
    @State private var gifSheet = false
    @State private var urlAlert = false
    @State private var urlField = ""
    @State private var pickerItem: PhotosPickerItem?
    @State private var suggestions: [MentionPick] = []
    @State private var showDiscardConfirm = false
    @State private var restoredDraft = false

    private let draftKey = "bitos_composer_draft"

    private var mediaCount: Int { remoteImageUrls.count + pickedImages.count }
    private var canAddImage: Bool { mediaCount < 4 }

    /// Kotlin string length basis (UTF-16 code units), not grapheme count.
    private var utf16Length: Int { (text as NSString).length }

    private var canPublish: Bool {
        !busy && !published &&
            (!text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || mediaCount > 0) &&
            utf16Length <= 16_000
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
            .navigationTitle("Create Post")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    SheetCloseButton {
                        if !published && !draftIsEmpty { showDiscardConfirm = true } else { onClose() }
                    }
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
                            Text("Post")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(canPublish ? BitOSTheme.accent : BitOSTheme.textTertiary)
                        }
                    }
                    .disabled(!canPublish)
                    .accessibilityLabel("Post note")
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
        .onChange(of: text) { _, _ in
            refreshSuggestions()
            if !errorMessage.isEmpty { errorMessage = "" }
            if !seeded { saveDraft() }
        }
        .onChange(of: cursor) { _, _ in refreshSuggestions() }
        .onChange(of: focused) { _, _ in refreshSuggestions() }
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
        .sheet(isPresented: $gifSheet) {
            GifPickerSheet(
                onPick: { gif in
                    if canAddImage { remoteImageUrls.append(gif.url) }
                },
                onDismiss: { gifSheet = false }
            )
            .presentationDetents([.large, .medium])
        }
        .alert("Add Image URL", isPresented: $urlAlert) {
            TextField("https://example.com/image.jpg", text: $urlField)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
            Button("Done") {
                let trimmed = urlField.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty && !isValidRemoteUrl(trimmed) {
                    // Legacy parity (`create.invalid_url`): surface the
                    // invalid URL instead of silently dropping it.
                    errorMessage = "Please enter a valid image URL"
                } else if isValidRemoteUrl(trimmed) && canAddImage {
                    remoteImageUrls.append(trimmed)
                }
                urlField = ""
            }
            Button("Cancel", role: .cancel) { urlField = "" }
        }
    }

    // MARK: - Editor

    /// Pinned error banner text: the local error wins over the publish
    /// failure; cleared on the next edit (legacy parity).
    private var bannerMessage: String {
        if !errorMessage.isEmpty { return errorMessage }
        if let result = environment.notePublisher.result, result != .published, !published {
            return publishFailureText(result)
        }
        return ""
    }

    private var editorContent: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
                    authorHeader
                    ComposerTextEditor(text: $text, cursor: $cursor, focused: $focused, pendingEdit: pendingEdit)
                        .frame(minHeight: 110)
                        .overlay(alignment: .topLeading) {
                            if text.isEmpty {
                                Text("Post a note…")
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
                }
                .padding(.horizontal, BitOSTheme.Spacing.screen)
                .padding(.vertical, BitOSTheme.Spacing.md)
            }
            // Error banner (legacy parity): full-width tinted strip above
            // the upload status / toolbar, cleared on the next edit.
            if !bannerMessage.isEmpty {
                Text(bannerMessage)
                    .font(.footnote)
                    .foregroundStyle(BitOSTheme.error)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, BitOSTheme.Spacing.base)
                    .padding(.vertical, BitOSTheme.Spacing.sm)
                    .background(BitOSTheme.error.opacity(0.1))
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
                Text("Posting as you — notes are signed with your key")
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
        .overlay(
            // Legacy parity: 20 % outline on the candidate panel.
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(BitOSTheme.border.opacity(0.2))
        )
    }

    /// Media grid: real thumbnails (legacy parity), removable.
    private var mediaGrid: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                ForEach(pickedImages) { picked in
                    mediaThumb {
                        pickedImages.removeAll { $0.id == picked.id }
                    } content: {
                        if let image = UIImage(data: picked.data) {
                            Image(uiImage: image).resizable().scaledToFill()
                        } else {
                            brokenThumbPlaceholder
                        }
                    }
                }
                ForEach(remoteImageUrls, id: \.self) { url in
                    mediaThumb {
                        remoteImageUrls.removeAll { $0 == url }
                    } content: {
                        RemoteMediaThumb(url: url)
                    }
                }
            }
        }
        .frame(height: 96)
    }

    private var brokenThumbPlaceholder: some View {
        ZStack {
            BitOSTheme.surfaceElevated
            AppIcons.image(for: AppIcons.brokenImage)
                .font(.system(size: 18))
                .foregroundStyle(BitOSTheme.textTertiary)
        }
    }

    /// Removable 96 pt tile; the caller supplies the real media content.
    private func mediaThumb(onRemove: @escaping () -> Void, @ViewBuilder content: () -> some View) -> some View {
        ZStack(alignment: .topTrailing) {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(BitOSTheme.surfaceElevated)
                .frame(width: 96, height: 96)
                .overlay { content().clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous)) }
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
                Text("Content Warning")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(BitOSTheme.warning)
            }
            BitosField("e.g. NSFW, Spoiler...", text: $contentWarningReason)
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
            // Legacy toolbar order (minus Meme Studio, which awaits the
            // studio phase): image · URL · GIF · poll · PoW · CW · hashtag · emoji.
            toolbarButton(AppIcons.photo, "Attach image", enabled: canAddImage, active: mediaCount > 0, badge: mediaCount > 0 ? "\(mediaCount)" : nil) {
                emojiSheet = false
                powSheet = false
                // PhotosPicker lives in the overlay below; toggle via state.
                pickerPrompt = true
            }
            toolbarButton(AppIcons.globe, "Add Image URL", enabled: canAddImage) { urlAlert = true }
            toolbarGlyphButton("GIF", "Add GIF", enabled: canAddImage) { gifSheet = true }
            toolbarButton("chart.bar", "Create poll") { pollSheet = true }
            toolbarButton(AppIcons.qrCode, "Proof of Work", enabled: pickedImages.isEmpty, active: powOutcome != nil || powTarget > 0,
                          badge: powOutcome.map { "\($0.targetDifficulty)" } ?? (powTarget > 0 ? "\(powTarget)" : nil)) { powSheet = true }
            toolbarButton(AppIcons.mute, "Content Warning", active: contentWarningOn) {
                contentWarningOn.toggle()
                if !contentWarningOn { contentWarningReason = "" }
            }
            toolbarButton("number", "Insert hashtag") {
                applyInsert(bridge.composerInsertHashtag(text: text, cursor: Int32(cursor)))
            }
            toolbarButton("face.smiling", "Insert emoji") { emojiSheet = true }
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

    /// Text-glyph toolbar button (GIF has no SF Symbol; the legacy app
    /// renders a text-like glyph too).
    private func toolbarGlyphButton(_ glyph: String, _ label: String, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(glyph)
                .font(.system(size: 12, weight: .heavy))
                .foregroundStyle(enabled ? BitOSTheme.textSecondary : BitOSTheme.textTertiary.opacity(0.4))
                .frame(width: 40, height: 40)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

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
        let map = bridge.composerCounter(length: Int32(utf16Length)) as? [String: Any] ?? [:]
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
                        applyInsert(bridge.composerInsertEmoji(text: text, cursor: Int32(cursor), emoji: emoji))
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

    /// @-autocomplete at the real caret (legacy `_MentionField` parity):
    /// only while the field is focused and the caret sits in a trailing
    /// `@query` — a bare `@` still resolves, with the empty query matching
    /// every known profile (cap 6).
    private func refreshSuggestions() {
        let atCursor = Int32(min(max(cursor, 0), utf16Length))
        guard focused, bridge.composerIsComposingMention(text: text, cursor: atCursor) else {
            if !suggestions.isEmpty { suggestions = [] }
            return
        }
        let query = bridge.composerMentionQuery(text: text, cursor: atCursor)
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

    /// Replace the `@query` in front of the caret with `@Name ` and track
    /// the pick so publish rewrites it to `nostr:npub…` (NIP-27).
    private func pickMention(_ suggestion: MentionPick) {
        let nsText = text as NSString
        let search = NSRange(location: 0, length: min(max(cursor, 0), utf16Length))
        let at = nsText.range(of: "@", options: .backwards, range: search)
        guard at.location != NSNotFound else {
            suggestions = []
            return
        }
        let replacement = "@\(suggestion.name) " as NSString
        let next = nsText.replacingCharacters(
            in: NSRange(location: at.location, length: search.length - at.location),
            with: replacement as String
        )
        applyProgrammaticEdit(next, selection: at.location + replacement.length)
        trackedMentions.append((name: suggestion.name, npub: suggestion.npub))
        suggestions = []
    }

    /// Apply a text change driven from outside the field (mention pick,
    /// toolbar insert): place the caret at the edit result and bring the
    /// keyboard back (legacy `_textWorker` refocus parity).
    private func applyProgrammaticEdit(_ next: String, selection: Int, refocus: Bool = true) {
        text = next
        cursor = selection
        pendingEdit = ComposerTextEdit(selection: selection, refocus: refocus)
    }

    /// Bridge toolbar inserts return {text, cursor}; apply both.
    private func applyInsert(_ map: [String: Any]) {
        guard let next = map["text"] as? String else { return }
        let selection = (map["cursor"] as? KotlinInt)?.intValue
            ?? (map["cursor"] as? NSNumber)?.intValue
            ?? (next as NSString).length
        applyProgrammaticEdit(next, selection: selection)
    }

    /// Legacy `_isValidUrl` parity: http(s) with an absolute path.
    private func isValidRemoteUrl(_ url: String) -> Bool {
        guard let components = URLComponents(string: url),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else { return false }
        return components.path.isEmpty || components.path.hasPrefix("/")
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
        // Restored caret sits at the end; the composer opens without
        // stealing focus (the legacy field focuses only on tap).
        cursor = (text as NSString).length
        pendingEdit = ComposerTextEdit(selection: cursor, refocus: false)
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
                uploadStatus = "Uploading media…"
                let uploaded = try await uploader.upload(
                    bytes: picked.data,
                    mimeType: picked.mimeType,
                    identity: identity,
                    serverUrl: blossomServer
                )
                uploadedUrls.append(uploaded.url)
                uploadStatus = "Uploaded \(index + 1) of \(pickedImages.count)"
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

/// Remote media tile: broken-image fallback on failure (legacy parity).
private struct RemoteMediaThumb: View {
    let url: String

    var body: some View {
        AsyncImage(url: URL(string: url)) { phase in
            switch phase {
            case .success(let image):
                image.resizable().scaledToFill()
            case .failure:
                ZStack {
                    BitOSTheme.surfaceElevated
                    AppIcons.image(for: AppIcons.brokenImage)
                        .font(.system(size: 18))
                        .foregroundStyle(BitOSTheme.textTertiary)
                }
            default:
                BitOSTheme.surfaceElevated
            }
        }
    }
}

    private var publishedState: some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
            AppIcons.image(for: AppIcons.checkCircle)
                .font(.system(size: 44))
                .foregroundStyle(BitOSTheme.success)
            Text("Published!")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(BitOSTheme.textPrimary)
            Text("Your note has been sent to the Nostr network.")
                .font(.footnote)
                .foregroundStyle(BitOSTheme.textSecondary)
            Button {
                onClose()
            } label: {
                Text("Back to Home")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(BitOSTheme.background)
                    .frame(width: 220)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(BitOSTheme.accent))
            }
            .buttonStyle(.plain)
            Button("New Post") {
                environment.notePublisher.dismiss()
                text = ""
                cursor = 0
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
