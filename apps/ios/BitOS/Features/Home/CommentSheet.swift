import BusinessCore
import BusinessCore
import SwiftUI

/// Reply thread sheet (SOC-002): verified replies with an identity-gated
/// composer. Published replies reconcile through the feed gate.
struct CommentSheet: View {
    let note: FeedNote
    let store: FeedStore
    let publisher: NotePublisher
    let onClose: () -> Void
    @Environment(IdentityStore.self) private var identity
    @State private var text = ""

    private var comments: [FeedNote] { store.comments[note.id] ?? [] }
    // APP-009 live tallies (shared NoteTally mirror).
    private var tally: NoteTallyMirror? { store.tallies[note.id] }

    var body: some View {
        NavigationStack {
            content
                .padding(BitOSTheme.Spacing.screen)
                .background(BitOSTheme.background)
                .navigationTitle("Replies")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close") { onClose() }
                    }
                }
        }
        .preferredColorScheme(.dark)
        .onAppear { store.loadComments(targetEventId: note.id) }
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
                        tally: tally
                    )
                    if comments.isEmpty {
                        Text(store.hasLoadedAnyEvent ? "No replies yet." : "Loading replies…")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    ForEach(comments) { reply in
                        ReplyRow(reply: reply, tally: store.tallies[reply.id])
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
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    BitosField("Write a reply…", text: $text)
                        .textFieldStyle(.roundedBorder)
                    Button("Reply") {
                        let content = text
                        text = ""
                        Task {
                            await publisher.publishReply(
                                content: content, targetEventId: note.id, targetPubkey: note.pubkey
                            )
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
                    .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty || publisher.busy)
                }
            }
        }
    }
}

/**
 * APP-009 root card: author + body + the full live action row
 * (reply count · like+count · repost+count · zap+sats · ⋯ raw) §3.9.
 */
private struct ThreadRootCard: View {
    let note: FeedNote
    let profile: ProfileMetadata?
    let replyCount: Int
    let tally: NoteTallyMirror?
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
                tallyAction(AppIcons.comment, "\(replyCount)", BitOSTheme.reply)
                tallyAction(AppIcons.heart, "\(tally?.reactions ?? 0)", BitOSTheme.like)
                tallyAction(AppIcons.repost, "\(tally?.reposts ?? 0)", BitOSTheme.repost)
                tallyAction(AppIcons.zap, zapLabel, BitOSTheme.zap)
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

    private func tallyAction(_ symbol: String, _ label: String, _ tint: Color) -> some View {
        HStack(spacing: 4) {
            AppIcons.image(for: symbol)
                .font(.system(size: 14))
                .foregroundStyle(tint)
            Text(label)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(tint)
        }
        .accessibilityElement(children: .ignore)
    }
}

private struct ReplyRow: View {
    let reply: FeedNote
    var tally: NoteTallyMirror? = nil

    var body: some View {
        HStack(alignment: .top, spacing: BitOSTheme.Spacing.sm) {
            PubkeyAvatarView(pubkey: reply.pubkey, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    Text(FeedFormat.shortPubkey(reply.pubkey))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(BitOSTheme.accent)
                    Text(FeedFormat.timeAgo(createdAt: reply.createdAt))
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.textTertiary)
                }
                Text(reply.content)
                    .font(.subheadline)
                    .foregroundStyle(BitOSTheme.textPrimary)
                // APP-009: live per-reply deltas (reactions · zaps+sats).
                if let tally, tally.reactions > 0 || tally.zaps > 0 {
                    HStack(spacing: BitOSTheme.Spacing.base) {
                        if tally.reactions > 0 {
                            HStack(spacing: 3) {
                                AppIcons.image(for: AppIcons.heart)
                                    .font(.system(size: 11))
                                    .foregroundStyle(BitOSTheme.like)
                                Text("\(tally.reactions)")
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundStyle(BitOSTheme.like)
                            }
                        }
                        if tally.zaps > 0 {
                            HStack(spacing: 3) {
                                AppIcons.image(for: AppIcons.zap)
                                    .font(.system(size: 11))
                                    .foregroundStyle(BitOSTheme.zap)
                                let sats = tally.zapMillisats / 1000
                                Text(sats > 0 ? "\(tally.zaps) · \(BusinessCoreBridge().zapFormatSats(sats: sats))" : "\(tally.zaps)")
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundStyle(BitOSTheme.zap)
                            }
                        }
                    }
                }
            }
        }
        .padding(BitOSTheme.Spacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surface))
    }
}
