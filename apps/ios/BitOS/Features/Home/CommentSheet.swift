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

    private let bridge = BusinessCoreBridge()
    private let uploader = BlossomUploader()
    private let blossomServer = "https://blossom.primal.net"

    private var comments: [FeedNote] { store.comments[note.id] ?? [] }
    /// NIP-10 assembled display list (depth + orphan flag).
    private var threadItems: [ThreadDisplayItem] { store.threads[note.id] ?? [] }
    private var noteById: [String: FeedNote] {
        Dictionary(uniqueKeysWithValues: comments.map { ($0.id, $0) })
    }
    // APP-009 live tallies (shared NoteTally mirror).
    private var tally: NoteTallyMirror? { store.tallies[note.id] }

    private var effectiveTarget: FeedNote { replyTarget ?? note }
    private var canAddAttachment: Bool { attachments.count < 4 }
    private var sending: Bool { publisher.busy && awaitingReply }
    private var canSend: Bool {
        !sending && (!text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !attachments.isEmpty)
    }

    var body: some View {
        NavigationStack {
            content
                .padding(BitOSTheme.Spacing.screen)
                .background(BitOSTheme.background)
                .navigationTitle("Replies")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        SheetCloseButton(action: onClose)
                    }
                }
        }
        .preferredColorScheme(.dark)
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
    }

    @ViewBuilder
    private var content: some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
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
                        onLike: { like(note) },
                        onRepost: { repost(note) },
                        onBookmark: { toggleBookmark(note) },
                        onZap: { openZap(note) }
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
                                onLike: { like(reply) },
                                onZap: { openZap(reply) }
                            ) {
                                replyTarget = reply
                            }
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
        guard turningOn, environment.identityStore.account != nil else { return }
        Task { await environment.notePublisher.publishReaction(targetEventId: note.id, targetPubkey: note.pubkey) }
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
            // Options row: gallery · GIF · media URL · PoW (legacy order).
            HStack(spacing: BitOSTheme.Spacing.xs) {
                optionButton(AppIcons.photo, "Attach from gallery", enabled: canAddAttachment) { pickerPrompt = true }
                optionGlyphButton("GIF", "Add GIF", enabled: canAddAttachment) { gifSheet = true }
                optionButton(AppIcons.globe, "Add media URL", enabled: canAddAttachment) { urlAlert = true }
                if powOutcome != nil || powTarget > 0 {
                    optionButton(AppIcons.qrCode, "Proof of work", text: "\(powOutcome?.targetDifficulty ?? powTarget) bits", active: true) { powSheet = true }
                } else {
                    optionButton(AppIcons.qrCode, "Proof of work") { powSheet = true }
                }
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

    /// GIF has no SF Symbol; the legacy app renders a text glyph.
    private func optionGlyphButton(_ glyph: String, _ label: String, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(glyph)
                .font(.system(size: 12, weight: .heavy))
                .foregroundStyle(enabled ? BitOSTheme.textSecondary : BitOSTheme.textTertiary.opacity(0.4))
                .frame(height: 40)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    private func targetName(_ target: FeedNote) -> String {
        store.profiles[target.pubkey]?.bestDisplayName ?? FeedFormat.shortPubkey(target.pubkey)
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

    private func send() {
        guard canSend, let tagsJson = replyTagsJson() else { return }
        let content = composedContent()
        awaitingReply = true
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
    let onLike: () -> Void
    let onRepost: () -> Void
    let onBookmark: () -> Void
    let onZap: () -> Void
    @State private var showRaw = false

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
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
            Text(note.content)
                .font(.system(size: 14))
                .foregroundStyle(BitOSTheme.textPrimary)
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
                Spacer()
                Button {
                    showRaw = true
                } label: {
                    Text("⋯")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                .accessibilityLabel("Raw note details")
                .alert("Raw note", isPresented: $showRaw) {
                    Button("Close", role: .cancel) {}
                } message: {
                    Text("id: \(note.id)\nauthor: \(note.pubkey)\nkind: \(note.kind)\nat: \(note.createdAt)")
                        .font(.system(size: 11, design: .monospaced))
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
    var onLike: (() -> Void)? = nil
    var onZap: (() -> Void)? = nil
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
                PubkeyAvatarView(pubkey: reply.pubkey, size: depth == 0 ? 28 : 22, label: profile?.bestDisplayName)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: BitOSTheme.Spacing.xs) {
                        Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(reply.pubkey))
                            .font(.caption.weight(.bold))
                            .foregroundStyle(BitOSTheme.textPrimary)
                            .lineLimit(1)
                        if orphan {
                            Image(systemName: "arrow.triangle.branch")
                                .font(.system(size: 9))
                                .foregroundStyle(BitOSTheme.textTertiary)
                        }
                        Text(FeedFormat.timeAgo(createdAt: reply.createdAt))
                            .font(.system(size: 11))
                            .foregroundStyle(BitOSTheme.textTertiary)
                    }
                    Text(reply.content)
                        .font(depth == 0 ? .subheadline : .footnote)
                        .foregroundStyle(BitOSTheme.textPrimary)
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
