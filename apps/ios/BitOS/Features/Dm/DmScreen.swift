import SwiftUI

/**
 * APP-011 DMs (iOS): conversation list → chat with encrypted bubbles
 * (sent right-aligned accent, received left surface).
 */
struct DmScreen: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity

    var body: some View {
        Group {
            if environment.dmStore.openPeerPubkey != nil {
                ChatView(peerPubkey: environment.dmStore.openPeerPubkey!)
            } else {
                ConversationListView()
            }
        }
        .background(BitOSTheme.background)
        .navigationTitle(environment.dmStore.openPeerPubkey != nil ? "" : "Chats")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: identity.account?.pubkeyHex) {
            environment.dmStore.setAccount(identity.account?.pubkeyHex)
        }
    }
}

// ── Conversation list ─────────────────────────────────────────────

private struct ConversationListView: View {
    @Environment(AppEnvironment.self) private var environment

    var body: some View {
        Group {
            if !environment.dmStore.hasAccount {
                Text("Chats need an identity (You tab).")
                    .font(.footnote)
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if environment.dmStore.conversations.isEmpty {
                Text(environment.dmStore.loaded ? "No conversations yet." : "Connecting…")
                    .font(.footnote)
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(environment.dmStore.conversations) { conversation in
                    Button {
                        environment.dmStore.openConversation(conversation.peerPubkey)
                    } label: {
                        HStack(spacing: BitOSTheme.Spacing.md) {
                            PubkeyAvatarView(pubkey: conversation.peerPubkey, size: 44)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(FeedFormat.shortPubkey(conversation.peerPubkey))
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundStyle(BitOSTheme.textPrimary)
                                if let last = conversation.messages.last {
                                    Text(last.content)
                                        .font(.system(size: 12))
                                        .foregroundStyle(BitOSTheme.textSecondary)
                                        .lineLimit(2)
                                }
                            }
                            Spacer()
                            if let last = conversation.messages.last {
                                Text(FeedFormat.timeAgo(createdAt: last.createdAt))
                                    .font(.system(size: 10))
                                    .foregroundStyle(BitOSTheme.textTertiary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(BitOSTheme.surface)
                    .listRowSeparatorTint(BitOSTheme.divider)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
    }
}

// ── Chat ──────────────────────────────────────────────────────────

private struct ChatView: View {
    @Environment(AppEnvironment.self) private var environment
    let peerPubkey: String
    @State private var text = ""

    private var conversation: DmConversationMirror? {
        environment.dmStore.conversations.first { $0.peerPubkey == peerPubkey }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Button("← Chats") { environment.dmStore.openConversation(nil) }
                    .font(.system(size: 13))
                    .foregroundStyle(BitOSTheme.textSecondary)
                PubkeyAvatarView(pubkey: peerPubkey, size: 32)
                Text(FeedFormat.shortPubkey(peerPubkey))
                    .font(.system(size: 14, weight: .bold))
                Spacer()
            }
            .padding(.horizontal, BitOSTheme.Spacing.screen)
            .padding(.vertical, BitOSTheme.Spacing.sm)

            ScrollView {
                LazyVStack(spacing: BitOSTheme.Spacing.sm) {
                    ForEach(conversation?.messages ?? []) { message in
                        MessageBubble(message: message, isMine: message.authorPubkey != peerPubkey)
                    }
                }
                .padding(.horizontal, BitOSTheme.Spacing.screen)
            }

            HStack(spacing: BitOSTheme.Spacing.sm) {
                BitosField("Write a message…", text: $text)
                    .onSubmit { send() }
                Button {
                    send()
                } label: {
                    Text("Send")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(text.trimmingCharacters(in: .whitespaces).isEmpty ? BitOSTheme.textTertiary : BitOSTheme.accent)
                }
                .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(.horizontal, BitOSTheme.Spacing.screen)
            .padding(.vertical, BitOSTheme.Spacing.sm)
        }
    }

    private func send() {
        let content = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !content.isEmpty else { return }
        text = ""
        Task {
            _ = await environment.dmStore.sendMessage(recipientPubkey: peerPubkey, content: content)
        }
    }
}

private struct MessageBubble: View {
    let message: DmMessageMirror
    let isMine: Bool

    var body: some View {
        HStack {
            if isMine { Spacer(minLength: 40) }
            VStack(alignment: .trailing, spacing: 2) {
                Text(message.content)
                    .font(.system(size: 14))
                    .foregroundStyle(isMine ? Color(hex: 0x0A0A0F) : BitOSTheme.textPrimary)
                    .frame(maxWidth: 280, alignment: isMine ? .trailing : .leading)
                Text(FeedFormat.timeAgo(createdAt: message.createdAt))
                    .font(.system(size: 10))
                    .foregroundStyle(isMine ? Color(hex: 0x0A0A0F).opacity(0.6) : BitOSTheme.textTertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(isMine ? BitOSTheme.accent : BitOSTheme.surfaceElevated)
            )
            if !isMine { Spacer(minLength: 40) }
        }
    }
}
