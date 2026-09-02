import BusinessCore
import PhotosUI
import SwiftUI

/// Reply thread sheet (SOC-002): verified replies with an identity-gated
/// composer. Published replies reconcile through the feed gate.
struct CommentSheet: View {
    let note: FeedNote
    let store: FeedStore
    let publisher: NotePublisher
    let onClose: () -> Void
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    @Environment(SettingsStore.self) private var settings
    @State private var text = ""
    /** Per-comment zap: opens the ZapSheet stacked above this thread. */
    @State private var zapTarget: FeedNote?
    /** Author avatar/name taps open the profile sheet (UX-010). */
    @State private var profileTarget: String?
    /** Own-note deletion (NIP-09): locally hidden rows + confirm target. */
    @State private var deletedIds: Set<String> = []
    @State private var deleteTarget: FeedNote?

    // APP-009 reply bar (legacy `_ThreadReplyBar` parity): sub-reply
    // targeting, URL/GIF/gallery attachments, PoW, pill input + send.
    @State private var replyTarget: FeedNote?
    @State private var attachments: [String] = []
    @State private var uploadStatus = ""
    @State private var gifSheet = false
    @State private var urlAlert = false
    @State private var urlField = ""
    @State private var powSheet = false
    @State private var powTarget = 0
    @State private var powOutcome: PowOutcome?
    @State private var awaitingReply = false
    @State private var pickerItem: PhotosPickerItem?
    @State private var pickerPrompt = false
    /** Text-insert helpers (composer toolbar parity). */
    @State private var emojiSheet = false
    /** Rich comment bodies: media tile tap → lightbox; link tap → confirm. */
    @State private var lightboxUrl: String?
    @State private var externalLink: String?

    private let bridge = BusinessCoreBridge()
    private let uploader = BlossomUploader()
    private let blossomServer = "https://blossom.primal.net"

    private var comments: [FeedNote] { (store.comments[note.id] ?? []).filter { !deletedIds.contains($0.id) } }
    /// NIP-10 assembled display list (depth + orphan flag).
    private var threadItems: [ThreadDisplayItem] { (store.threads[note.id] ?? []).filter { !deletedIds.contains($0.id) } }
    private var noteById: [String: FeedNote] {
        Dictionary(uniqueKeysWithValues: comments.map { ($0.id, $0) })
    }
    // APP-009 live tallies (shared NoteTally mirror).
    private var tally: NoteTallyMirror? { store.tallies[note.id] }

    private var effectiveTarget: FeedNote { replyTarget ?? note }
    private var canAddAttachment: Bool { attachments.count < 4 }
    /** NIP-22 mode (ADR-003): non-kind-1 roots publish kind-1111 comments
     *  instead of kind-1 replies; PoW rides only the kind-1 path. */
    private var commentMode: Bool { note.kind != 1 }
    private var sending: Bool { publisher.busy && awaitingReply }
    private var canSend: Bool {
        !sending && (!text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !attachments.isEmpty)
    }

    var body: some View {
        NavigationStack {
            content
                .padding(BitOSTheme.Spacing.screen)
                .background(BitOSTheme.background)
                .navigationTitle("")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar(.hidden, for: .navigationBar)
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
        .onAppear { store.loadComments(targetEventId: note.id) }
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let mime = item.supportedContentTypes.first?.preferredMIMEType {
                    // Legacy order: upload BEFORE the URL joins the reply.
                    do {
                        uploadStatus = "Uploading media…"
                        let receipt = try await uploader.upload(bytes: data, mimeType: mime, identity: identity, serverUrl: blossomServer)
                        if canAddAttachment { attachments.append(receipt.url) }
                        uploadStatus = ""
                    } catch {
                        // Stays visible until the next action clears it.
                        uploadStatus = (error as? LocalizedError)?.errorDescription ?? "Upload failed."
                    }
                }
                pickerItem = nil
            }
        }
        // Clear the bar only on a successful ACK (legacy clears on success).
        .onChange(of: publisher.result) { _, result in
            guard awaitingReply, let result else { return }
            if result == .published {
                text = ""
                attachments = []
                powOutcome = nil
                replyTarget = nil
            }
            awaitingReply = false
        }
        .sheet(isPresented: $gifSheet) {
            GifPickerSheet(
                onPick: { gif in
                    if canAddAttachment { attachments.append(gif.url) }
                },
                onDismiss: { gifSheet = false }
            )
            .presentationDetents([.large, .medium])
        }
        // Emoji insert (composer toolbar parity): the shared quick-emoji
        // set, appended at the end of the reply (no cursor tracking here).
        .sheet(isPresented: $emojiSheet) {
            emojiSheetContent
                .presentationDetents([.medium])
        }
        .sheet(isPresented: $powSheet) {
            powSheetContent
                .presentationDetents([.medium, .large])
        }
        .alert("Add media URL", isPresented: $urlAlert) {
            TextField("https://…", text: $urlField)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
            Button("Add") {
                let trimmed = urlField.trimmingCharacters(in: .whitespacesAndNewlines)
                if (trimmed.hasPrefix("https://") || trimmed.hasPrefix("http://")) && canAddAttachment {
                    attachments.append(trimmed)
                }
                urlField = ""
            }
            Button("Cancel", role: .cancel) { urlField = "" }
        }
        // Per-comment zap: opens the zap sheet stacked above this thread.
        .sheet(item: $zapTarget) { target in
            ZapSheet(
                note: target,
                profiles: store.profiles,
                initialAmountSats: settings.state.defaultZapAmount,
                zapCount: store.zapCounts[target.id] ?? 0,
                paidRequestIds: store.zapRequestIds[target.id] ?? [],
                onPaid: { sats, memo in
                    environment.sentZaps.record(.init(
                        id: "zap-\(target.id)-\(sats)-\(Int(Date.now.timeIntervalSince1970))",
                        amountSats: Int64(sats),
                        recipientPubkey: target.pubkey,
                        createdAt: Int64(Date.now.timeIntervalSince1970),
                        targetNoteId: target.id,
                        memo: memo.isEmpty ? nil : memo
                    ))
                },
                onClose: { zapTarget = nil }
            )
            .environment(identity)
            .presentationDetents([.medium])
        }
        // Author taps inside the thread open the profile sheet (UX-010).
        .sheet(item: Binding(
            get: { profileTarget.map { ProfileTarget(id: $0) } },
            set: { profileTarget = $0?.id }
        )) { target in
            AuthorProfileSheet(
                authorPubkey: target.id,
                onClose: { profileTarget = nil }
            )
            .environment(identity)
            .presentationDetents([.medium, .large])
        }
        // Own-note delete confirmation (kind-5).
        .alert("Delete this note?", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("Delete", role: .destructive, action: confirmDelete)
            Button("Cancel", role: .cancel) { deleteTarget = nil }
        } message: {
            Text("The deletion publishes to your relays and cannot be undone.")
        }
        // Rich-body media tiles: zoomable lightbox (feed-card parity).
        .fullScreenCover(item: Binding(
            get: { lightboxUrl.map { LightboxTarget(url: $0) } },
            set: { lightboxUrl = $0?.url }
        )) { target in
            MediaLightbox(url: target.url, onClose: { lightboxUrl = nil })
        }
        // External links in comment bodies: confirm first (never unattended).
        .sheet(isPresented: Binding(
            get: { externalLink != nil },
            set: { if !$0 { externalLink = nil } }
        )) {
            if let externalLink {
                ExternalLinkConfirmSheet(url: externalLink)
            }
        }
    }

    private struct LightboxTarget: Identifiable {
        let url: String
        var id: String { url }
    }

    private struct ProfileTarget: Identifiable {
        let id: String
    }

    @ViewBuilder
    private var content: some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
            // Header (mock parity): "Comments N" + close.
            HStack(alignment: .firstTextBaseline) {
                Text("Comments")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                Text("\(max(comments.count, threadItems.count))")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(BitOSTheme.textSecondary)
                Spacer()
                Button(action: onClose) {
                    AppIcons.image(for: AppIcons.close)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close comments")
            }
            ScrollView {
                VStack(spacing: BitOSTheme.Spacing.sm) {
                    ThreadRootCard(
                        note: note,
                        profile: store.profiles[note.pubkey],
                        replyCount: comments.count,
                        tally: tally,
                        isLiked: store.localActions.liked.contains(note.id),
                        isBookmarked: store.bookmarkedIds.contains(note.id)
                            || store.localActions.bookmarked.contains(note.id),
                        richJson: store.richTokens(for: note.content),
                        resolveMentionName: { store.profiles[$0]?.bestDisplayName },
                        onOpenMentionProfile: { profileTarget = $0 },
                        onOpenExternalLink: { externalLink = $0 },
                        onOpenMedia: { lightboxUrl = $0 },
                        mediaPreview: settings.state.mediaPreview,
                        onLike: { like(note) },
                        onRepost: { repost(note) },
                        onBookmark: { toggleBookmark(note) },
                        onZap: { openZap(note) },
                        onOpenProfile: { profileTarget = note.pubkey },
                        onDelete: note.pubkey == identity.account?.pubkeyHex ? { requestDelete(note) } : nil
                    )
                    if comments.isEmpty {
                        Text(store.hasLoadedAnyEvent ? "No replies yet." : "Loading replies…")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    let lookup = noteById
                    ForEach(threadItems.isEmpty ? comments.map { ThreadDisplayItem(id: $0.id, depth: 0, parentId: nil, orphan: false) } : threadItems) { item in
                        if let reply = lookup[item.id] ?? comments.first(where: { $0.id == item.id }) {
                            ReplyRow(
                                reply: reply,
                                profile: store.profiles[reply.pubkey],
                                depth: item.depth,
                                orphan: item.orphan,
                                tally: store.tallies[reply.id],
                                isLiked: store.localActions.liked.contains(reply.id),
                                richJson: store.richTokens(for: reply.content),
                                resolveMentionName: { store.profiles[$0]?.bestDisplayName },
                                onOpenMentionProfile: { profileTarget = $0 },
                                onOpenExternalLink: { externalLink = $0 },
                                onOpenMedia: { lightboxUrl = $0 },
                                mediaPreview: settings.state.mediaPreview,
                                onLike: { like(reply) },
                                onZap: { openZap(reply) },
                                onOpenProfile: { profileTarget = reply.pubkey },
                                onDelete: reply.pubkey == identity.account?.pubkeyHex ? { requestDelete(reply) } : nil,
                                // Web parity: root comment → one reply only.
                                // A depth-1 reply never becomes a new parent.
                                onReplyTo: item.depth == 0 ? { replyTarget = reply } : nil
                            )
                        }
                    }
                }
            }
            .frame(maxHeight: .infinity)

            if let result = publisher.result {
                switch result {
                case .rejected:
                    Text("Relays rejected the reply.").font(.caption).foregroundStyle(BitOSTheme.error)
                case .signingRefused, .invalid:
                    Text("The reply was not sent.").font(.caption).foregroundStyle(BitOSTheme.error)
                default:
                    EmptyView()
                }
            }

            if identity.account == nil {
                Text("Replying needs an identity (Profile tab).")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                replyBar
            }
        }
    }

    // MARK: - Interactive thread actions (HomeView parity)

    private func like(_ note: FeedNote) {
        let turningOn = !store.localActions.liked.contains(note.id)
        store.localActions.toggleLike(note.id)
        guard environment.identityStore.account != nil else { return }
        if turningOn {
            Task { await environment.notePublisher.publishReaction(targetEventId: note.id, targetPubkey: note.pubkey) }
        } else if let reactionId = store.myReactionEventIds[note.id] {
            // Web unlike parity: delete my kind-7 from relays (NIP-09).
            Task { await environment.notePublisher.publishDeletion(targetEventIds: [reactionId]) }
        }
    }

    private func repost(_ note: FeedNote) {
        guard environment.identityStore.account != nil else { return }
        Task { await environment.notePublisher.publishRepost(targetEventId: note.id, targetPubkey: note.pubkey) }
    }

    private func toggleBookmark(_ note: FeedNote) {
        if let updated = store.applyBookmarkChange(eventId: note.id, add: !store.bookmarkedIds.contains(note.id)) {
            guard environment.identityStore.account != nil else { return }
            Task { await environment.notePublisher.publishBookmarkList(eventIds: updated) }
        } else {
            store.localActions.toggleBookmark(note.id)
        }
    }

    private func openZap(_ note: FeedNote) {
        store.loadZaps(targetEventId: note.id)
        zapTarget = note
    }

    // MARK: - Own-note deletion (NIP-09, web `feed.deleteNote` parity)

    private func requestDelete(_ target: FeedNote) {
        deleteTarget = target
    }

    private func confirmDelete() {
        guard let target = deleteTarget else { return }
        deleteTarget = nil
        Task { await publisher.publishDeletion(targetEventIds: [target.id]) }
        if target.id == note.id {
            onClose()
        } else {
            deletedIds.insert(target.id)
        }
    }

    // MARK: - APP-009 reply bar (legacy `_ThreadReplyBar` parity)

    private var replyBar: some View {
        VStack(spacing: BitOSTheme.Spacing.xs) {
            if replyTarget != nil {
                HStack {
                    Text("Reply to \(targetName(effectiveTarget))")
                        .font(.system(size: 11))
                        .foregroundStyle(BitOSTheme.textSecondary)
                    Spacer()
                    Button {
                        replyTarget = nil
                    } label: {
                        AppIcons.image(for: AppIcons.close)
                            .font(.system(size: 14))
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                    .accessibilityLabel("Cancel reply target")
                }
            }
            if !uploadStatus.isEmpty {
                Text(uploadStatus)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(uploadStatus.hasPrefix("Uploading") ? BitOSTheme.accent : BitOSTheme.error)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            AttachmentPreviewRow(urls: attachments) { index in
                attachments.remove(at: index)
            }
            // Options row — the same Solar tokens as the composer toolbar:
            // gallery · GIF · URL · PoW · hashtag · emoji (legacy reply
            // order, PoW only on the kind-1 reply path).
            HStack(spacing: BitOSTheme.Spacing.xs) {
                optionButton(AppIcons.photo, "Attach from gallery", enabled: canAddAttachment) { pickerPrompt = true }
                optionButton(AppIcons.gifFilm, "Add GIF", enabled: canAddAttachment) { gifSheet = true }
                optionButton(AppIcons.link, "Add media URL", enabled: canAddAttachment) { urlAlert = true }
                if !commentMode {
                    if powOutcome != nil || powTarget > 0 {
                        optionButton(AppIcons.shieldCheck, "Proof of work", text: "\(powOutcome?.targetDifficulty ?? powTarget) bits", active: true) { powSheet = true }
                    } else {
                        optionButton(AppIcons.shieldCheck, "Proof of work") { powSheet = true }
                    }
                }
                optionButton(AppIcons.hashtag, "Insert hashtag") { insertHashtag() }
                optionButton(AppIcons.emoji, "Insert emoji") { emojiSheet = true }
                Spacer(minLength: 0)
            }
            HStack(spacing: BitOSTheme.Spacing.sm) {
                TextField("Write a reply…", text: $text, axis: .vertical)
                    .lineLimit(1...4)
                    .font(.system(size: 14))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .fill(BitOSTheme.surfaceElevated.opacity(0.5))
                    )
                Button(action: send) {
                    Group {
                        if sending {
                            ProgressView().tint(BitOSTheme.accent)
                        } else {
                            AppIcons.image(for: AppIcons.send)
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(canSend ? BitOSTheme.accent : BitOSTheme.textTertiary)
                        }
                    }
                    .frame(width: 40, height: 40)
                    .background(Circle().fill(BitOSTheme.surfaceElevated))
                }
                .buttonStyle(.plain)
                .disabled(!canSend)
                .accessibilityLabel("Send reply")
            }
        }
        .photosPicker(isPresented: $pickerPrompt, selection: $pickerItem, matching: .images)
    }

    private func optionButton(_ symbol: String, _ label: String, text: String? = nil, enabled: Bool = true, active: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                AppIcons.image(for: symbol)
                    .font(.system(size: 18))
                    .foregroundStyle(
                        !enabled ? BitOSTheme.textTertiary.opacity(0.4)
                            : active ? BitOSTheme.accent : BitOSTheme.textSecondary
                    )
                if let text {
                    Text(text)
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(active ? BitOSTheme.accent : BitOSTheme.textSecondary)
                }
            }
            .frame(height: 40)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    private func targetName(_ target: FeedNote) -> String {
        store.profiles[target.pubkey]?.bestDisplayName ?? FeedFormat.shortPubkey(target.pubkey)
    }

    // MARK: - Text-insert helpers (composer toolbar parity)

    /// No cursor tracking in the pill field — inserts land at the end.
    private func insertHashtag() {
        if let map = bridge.composerInsertHashtag(text: text, cursor: Int32(text.count)) as? [String: Any],
           let next = map["text"] as? String {
            text = next
        }
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
                        if let map = bridge.composerInsertEmoji(text: text, cursor: Int32(text.count), emoji: emoji) as? [String: Any],
                           let next = map["text"] as? String {
                            text = next
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

    /// Attachments land in the content (web parity, shared rule).
    private func composedContent() -> String {
        guard !attachments.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: attachments),
              let json = String(data: data, encoding: .utf8) else { return text }
        return bridge.composerComposeContent(text: text, urlsJson: json)
    }

    /// Shared reply-tags rule through the bridge: both NIP-10 markers +
    /// participant p-tags + content entities.
    private func replyTagsJson() -> String? {
        let mentions = effectiveTarget.mentions
        guard let mentionsData = try? JSONSerialization.data(withJSONObject: Array(mentions)),
              let mentionsJson = String(data: mentionsData, encoding: .utf8) else { return nil }
        return bridge.replyTagsJson(
            rootEventId: effectiveTarget.threadRootId ?? effectiveTarget.id,
            targetEventId: effectiveTarget.id,
            targetPubkey: effectiveTarget.pubkey,
            targetPTagsJson: mentionsJson,
            content: composedContent()
        )
    }

    /// NIP-22 comment tags (web `feed.comment` parity): uppercase E/K/P
    /// root + lowercase e/k parent; the parent is the comment being
    /// answered, or the root itself for top-level comments.
    private func commentTagsJson() -> String? {
        let parent = replyTarget
        return bridge.commentTagsJson(
            targetEventId: note.id,
            targetPubkey: note.pubkey,
            targetKind: Int64(note.kind),
            parentEventId: parent.map(\.id),
            parentPubkey: parent.map(\.pubkey),
            content: composedContent()
        ) as String?
    }

    private func send() {
        guard canSend else { return }
        let content = composedContent()
        awaitingReply = true
        if commentMode, let tagsJson = commentTagsJson() {
            Task { await publisher.publishComment(content: content, tagsJson: tagsJson) }
        } else if let tagsJson = replyTagsJson() {
            if let pow = powOutcome {
                Task {
                    await publisher.publishPowNote(
                        content: content, nonce: pow.nonce, targetDifficulty: Int32(pow.targetDifficulty),
                        createdAt: pow.createdAt, tagsJson: tagsJson
                    )
                }
            } else {
                Task { await publisher.publishNote(content: content, tagsJson: tagsJson) }
            }
        }
    }

    private var powSheetContent: some View {
        let content = composedContent()
        let tagsJson = replyTagsJson() ?? "[]"
        return PowCard(
            target: $powTarget,
            mineChunk: { createdAt, startNonce, attempts in
                await publisher.minePowChunkWithTags(
                    content: content, targetDifficulty: Int32(powTarget), createdAt: createdAt,
                    startNonce: startNonce, attempts: attempts, tagsJson: tagsJson
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
}

/**
 * APP-009 root card: author + body + the legacy interactive action row
 * (like · replies · zap · repost · bookmark — Solar icons, live tallies,
 * legacy `_ThreadActionRow` parity) + ⋯ raw §3.9.
 */
private struct ThreadRootCard: View {
    let note: FeedNote
    let profile: ProfileMetadata?
    let replyCount: Int
    let tally: NoteTallyMirror?
    let isLiked: Bool
    let isBookmarked: Bool
    /** Rich body (NIP-27 tokens): mentions/links tappable, media as tiles. */
    var richJson: String = "[]"
    var resolveMentionName: ((String) -> String?)? = nil
    var onOpenMentionProfile: ((String) -> Void)? = nil
    var onOpenExternalLink: ((String) -> Void)? = nil
    var onOpenMedia: ((String) -> Void)? = nil
    var mediaPreview: Bool = true
    let onLike: () -> Void
    let onRepost: () -> Void
    let onBookmark: () -> Void
    let onZap: () -> Void
    var onOpenProfile: (() -> Void)? = nil
    /** Own notes only (NIP-09 kind-5 delete). */
    var onDelete: (() -> Void)? = nil
    @State private var showRaw = false
    /** APP-009 ⋯ Share action → system sheet. */
    @State private var shareText: String?

    /// Shared `NoteShare` copy through the bridge (excerpt + attribution).
    private func shareCopy(npub: String?) -> String {
        if let npub {
            return BusinessCoreBridge().noteShareText(content: note.content, authorNpub: npub)
        }
        return note.content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Button(action: { onOpenProfile?() }) {
                    HStack(spacing: BitOSTheme.Spacing.sm) {
                        PubkeyAvatarView(pubkey: note.pubkey, size: 40, label: profile?.bestDisplayName)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(note.pubkey))
                                .font(.system(size: 14, weight: .bold))
                            Text(FeedFormat.timeAgo(createdAt: note.createdAt))
                                .font(.system(size: 11))
                                .foregroundStyle(BitOSTheme.textTertiary)
                        }
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Open author profile")
            }
            // Rich body: NIP-27 entities tappable; bare media links render
            // as tiles below and disappear from the text (feed-card parity).
            RichTextView(
                json: richJson,
                onOpenProfile: { onOpenMentionProfile?($0) },
                onOpenHashtag: nil,
                hiddenMediaUrls: Set(note.mediaUrls),
                resolveMentionName: resolveMentionName,
                onOpenLink: { onOpenExternalLink?($0) }
            )
            if mediaPreview, !note.mediaUrls.isEmpty {
                MediaGrid(urls: note.mediaUrls) { onOpenMedia?($0) }
            }
            HStack(spacing: BitOSTheme.Spacing.base) {
                threadAction(
                    isLiked ? AppIcons.heartFill : AppIcons.heart,
                    "\(tally?.reactions ?? 0)",
                    isLiked ? BitOSTheme.like : BitOSTheme.textSecondary,
                    action: onLike
                )
                threadAction(AppIcons.comment, "\(replyCount)", BitOSTheme.reply, action: nil)
                threadAction(AppIcons.zap, zapLabel, BitOSTheme.zap, action: onZap)
                threadAction(AppIcons.repost, "\(tally?.reposts ?? 0)", BitOSTheme.repost, action: onRepost)
                threadAction(
                    isBookmarked ? AppIcons.bookmarkFill : AppIcons.bookmark,
                    nil,
                    isBookmarked ? BitOSTheme.bookmark : BitOSTheme.textSecondary,
                    action: onBookmark
                )
                if let onDelete {
                    Button(action: onDelete) {
                        AppIcons.image(for: AppIcons.delete)
                            .font(.system(size: 14))
                            .foregroundStyle(BitOSTheme.error)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Delete this note")
                }
                Spacer()
                Button {
                    showRaw = true
                } label: {
                    AppIcons.image(for: AppIcons.more)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                .accessibilityLabel("Raw note details")
                .alert("Raw note", isPresented: $showRaw) {
                    // Web PostCard menu parity: diagnostic copies.
                    Button("Copy note ID") { UIPasteboard.general.string = note.id }
                    if let npub = BusinessCoreBridge().npubEncode(pubkeyHex: note.pubkey) as String? {
                        Button("Copy author npub") { UIPasteboard.general.string = npub }
                    }
                    Button("Copy note text") { UIPasteboard.general.string = note.content }
                    // APP-009 ⋯ parity (mockup app-10): share + web link.
                    Button("Share") {
                        shareText = shareCopy(npub: BusinessCoreBridge().npubEncode(pubkeyHex: note.pubkey) as String?)
                    }
                    Button("Copy link") {
                        UIPasteboard.general.string = "https://njump.me/\(note.id)"
                    }
                    Button("Close", role: .cancel) {}
                } message: {
                    Text("id: \(note.id)\nauthor: \(note.pubkey)\nkind: \(note.kind)\nat: \(note.createdAt)")
                        .font(.system(size: 11, design: .monospaced))
                }
                // APP-009 ⋯ parity: system share sheet (BitzView pattern).
                .sheet(isPresented: Binding(
                    get: { shareText != nil },
                    set: { if !$0 { shareText = nil } }
                )) {
                    if let shareText {
                        ShareSheet(items: [shareText])
                            .presentationDetents([.medium])
                    }
                }
            }
        }
        .padding(BitOSTheme.Spacing.md)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(BitOSTheme.surface)
        )
    }

    private var zapLabel: String {
        let zaps = tally?.zaps ?? 0
        let sats = (tally?.zapMillisats ?? 0) / 1000
        if sats > 0 {
            return "\(zaps) · \(BusinessCoreBridge().zapFormatSats(sats: sats))"
        }
        return "\(zaps)"
    }

    /// Legacy `_ThreadAction` parity: 14 pt icon + 11 pt semibold count,
    /// tappable when an action exists.
    private func threadAction(_ symbol: String, _ label: String?, _ tint: Color, action: (() -> Void)?) -> some View {
        Group {
            if let action {
                Button(action: action) { threadActionLabel(symbol, label, tint: tint) }
                    .buttonStyle(.plain)
            } else {
                threadActionLabel(symbol, label, tint: tint)
            }
        }
        .accessibilityElement(children: .ignore)
    }

    private func threadActionLabel(_ symbol: String, _ label: String?, tint: Color) -> some View {
        HStack(spacing: 4) {
            AppIcons.image(for: symbol)
                .font(.system(size: 14))
                .foregroundStyle(tint)
            if let label {
                Text(label)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(tint)
            }
        }
    }
}

/**
 * One comment row (legacy `_CommentRow` parity): bold author + time, body,
 * ghost action row — Like (+count) · Zap (+sats) · Reply — with Solar
 * icons and " · count" trailings.
 */
private struct ReplyRow: View {
    let reply: FeedNote
    var profile: ProfileMetadata? = nil
    var depth: Int = 0
    var orphan: Bool = false
    var tally: NoteTallyMirror? = nil
    var isLiked: Bool = false
    /** Rich body (NIP-27 tokens): mentions/links tappable, media as tiles. */
    var richJson: String = "[]"
    var resolveMentionName: ((String) -> String?)? = nil
    var onOpenMentionProfile: ((String) -> Void)? = nil
    var onOpenExternalLink: ((String) -> Void)? = nil
    var onOpenMedia: ((String) -> Void)? = nil
    var mediaPreview: Bool = true
    var onLike: (() -> Void)? = nil
    var onZap: (() -> Void)? = nil
    var onOpenProfile: (() -> Void)? = nil
    /** Own notes only (NIP-09 kind-5 delete). */
    var onDelete: (() -> Void)? = nil
    var onReplyTo: (() -> Void)? = nil

    // Depth-keyed tint for the vertical thread line (TikTok/X pattern).
    private static let depthColors: [Color] = [
        Color(red: 0.40, green: 0.28, blue: 0.96),
        Color(red: 0.12, green: 0.69, blue: 0.95),
        Color(red: 0.95, green: 0.70, blue: 0.12),
        Color(red: 0.22, green: 0.85, blue: 0.60),
    ]
    private var lineColor: Color {
        depth > 0 ? Self.depthColors[(depth - 1) % Self.depthColors.count] : .clear
    }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            // Vertical depth lines at each nesting level.
            if depth > 0 {
                HStack(spacing: 6) {
                    ForEach(0..<depth, id: \.self) { level in
                        Capsule()
                            .fill(Self.depthColors[level % Self.depthColors.count].opacity(0.50))
                            .frame(width: 2)
                    }
                }
                .frame(width: CGFloat(depth) * 8)
                .padding(.trailing, 6)
            }
            HStack(alignment: .top, spacing: BitOSTheme.Spacing.sm) {
                Button(action: { onOpenProfile?() }) {
                    PubkeyAvatarView(pubkey: reply.pubkey, size: depth == 0 ? 28 : 22, label: profile?.bestDisplayName)
                }
                .buttonStyle(.plain)
                .disabled(onOpenProfile == nil)
                .accessibilityLabel("Open author profile")
                VStack(alignment: .leading, spacing: 2) {
                    Button(action: { onOpenProfile?() }) {
                        HStack(spacing: BitOSTheme.Spacing.xs) {
                            Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(reply.pubkey))
                                .font(.caption.weight(.bold))
                                .foregroundStyle(BitOSTheme.textPrimary)
                                .lineLimit(1)
                            if orphan {
                                AppIcons.image(for: AppIcons.branch)
                                    .font(.system(size: 9))
                                    .foregroundStyle(BitOSTheme.textTertiary)
                            }
                            Text(FeedFormat.timeAgo(createdAt: reply.createdAt))
                                .font(.system(size: 11))
                                .foregroundStyle(BitOSTheme.textTertiary)
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(onOpenProfile == nil)
                    .accessibilityLabel("Open author profile")
                    // Rich body: NIP-27 entities tappable; bare media links
                    // render as tiles below (feed-card parity).
                    RichTextView(
                        json: richJson,
                        onOpenProfile: { onOpenMentionProfile?($0) },
                        onOpenHashtag: nil,
                        color: BitOSTheme.textPrimary,
                        hiddenMediaUrls: Set(reply.mediaUrls),
                        resolveMentionName: resolveMentionName,
                        onOpenLink: { onOpenExternalLink?($0) }
                    )
                    .font(depth == 0 ? .subheadline : .footnote)
                    if mediaPreview, !reply.mediaUrls.isEmpty {
                        MediaGrid(urls: reply.mediaUrls) { onOpenMedia?($0) }
                    }
                    HStack(spacing: BitOSTheme.Spacing.base) {
                        if let onLike {
                            commentAction(
                                isLiked ? AppIcons.heartFill : AppIcons.heart,
                                isLiked ? "Unlike" : "Like",
                                tint: isLiked ? BitOSTheme.like : BitOSTheme.textSecondary,
                                trailing: (tally?.reactions ?? 0) > 0 ? "\(tally?.reactions ?? 0)" : nil,
                                action: onLike
                            )
                        }
                        if let onZap {
                            commentAction(
                                AppIcons.zap,
                                "Zap",
                                tint: BitOSTheme.zap,
                                trailing: zapTrailing,
                                action: onZap
                            )
                        }
                        if let onReplyTo {
                            commentAction(
                                AppIcons.comment,
                                "Reply",
                                tint: BitOSTheme.textSecondary,
                                trailing: nil,
                                action: onReplyTo
                            )
                        }
                        if let onDelete {
                            Button(action: onDelete) {
                                Text("Delete")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundStyle(BitOSTheme.error)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Delete this reply")
                        }
                    }
                }
            }
        }
        .padding(.vertical, BitOSTheme.Spacing.sm)
        .padding(.horizontal, BitOSTheme.Spacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surface))
    }

    private var zapTrailing: String? {
        guard let tally else { return nil }
        let sats = tally.zapMillisats / 1000
        if tally.zaps <= 0 { return nil }
        return sats > 0
            ? "\(tally.zaps) · \(BusinessCoreBridge().zapFormatSats(sats: sats))"
            : "\(tally.zaps)"
    }

    /// Legacy `_CommentActionButton` parity: 11 pt icon + 11 pt bold label,
    /// count rides as a " · N" trailing.
    private func commentAction(_ symbol: String, _ label: String, tint: Color, trailing: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 3) {
                AppIcons.image(for: symbol)
                    .font(.system(size: 11))
                    .foregroundStyle(tint)
                Text(label)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(tint)
                if let trailing {
                    Text("· \(trailing)")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(tint)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(label) this reply")
    }
}
