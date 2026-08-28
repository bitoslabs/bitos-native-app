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
                    if comments.isEmpty {
                        Text(store.hasLoadedAnyEvent ? "No replies yet." : "Loading replies…")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    ForEach(comments) { reply in
                        ReplyRow(reply: reply)
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
                    TextField("Write a reply…", text: $text)
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

private struct ReplyRow: View {
    let reply: FeedNote

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
            }
        }
        .padding(BitOSTheme.Spacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surface))
    }
}
