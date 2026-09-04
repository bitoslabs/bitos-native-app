import BusinessCore
import SwiftUI

/**
 * APP-011 DMs (iOS; mock `app-06-inbox-activity-messages` parity):
 * message list with generic NIP-17 previews, unread dots and message
 * requests; secure chat with the encryption banner, delivery ticks,
 * ⚡ zap chip and new-chat by npub. Conversation state derives from the
 * shared `DmPresentation` rules through the bridge.
 */
struct DmScreen: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    /// Chat-header ⚡ chip → profile zap sheet (mock chip-orange parity).
    var onZapPeer: ((String) -> Void)?
    /// UX-010: chat header → full in-app profile page.
    var onOpenProfile: ((String) -> Void)?

    @State private var newChatOpen = false

    var body: some View {
        Group {
            if environment.dmStore.openPeerPubkey != nil {
                ChatView(
                    peerPubkey: environment.dmStore.openPeerPubkey!,
                    onZapPeer: onZapPeer,
                    onOpenProfile: onOpenProfile
                )
            } else {
                ConversationListView(newChatOpen: $newChatOpen)
            }
        }
        .background(BitOSTheme.background)
        .navigationTitle(environment.dmStore.openPeerPubkey != nil ? "" : "Chats")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: identity.account?.pubkeyHex) {
            environment.dmStore.setAccount(identity.account?.pubkeyHex)
        }
        .sheet(isPresented: $newChatOpen) {
            NewChatSheet { pubkey in
                newChatOpen = false
                environment.dmStore.openConversation(pubkey)
            }
            .presentationDetents([.medium])
            .preferredColorScheme(BitOSTheme.preferredScheme)
        }
    }
}

// ── Conversation list ─────────────────────────────────────────────

private struct ConversationListView: View {
    @Environment(AppEnvironment.self) private var environment
    @Binding var newChatOpen: Bool

    private var accepted: [DmConversationMirror] {
        environment.dmStore.conversations.filter { !environment.dmStore.requestPeers.contains($0.peerPubkey) }
    }

    private var requests: [DmConversationMirror] {
        environment.dmStore.conversations.filter { environment.dmStore.requestPeers.contains($0.peerPubkey) }
    }

    var body: some View {
        Group {
            // Matches the Inbox header inside the shared Activity tab (same
            // gutter, same vertical rhythm on both chips). The nav bar is
            // hidden there, so the title + new-chat live in this row.
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Text("Chats")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                Spacer()
                Button {
                    newChatOpen = true
                } label: {
                    AppIcons.image(for: AppIcons.chat)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                .accessibilityLabel("New message")
            }
            .padding(.horizontal, BitOSTheme.Spacing.base)
            .padding(.top, 10)
            .padding(.bottom, 2)
            if !environment.dmStore.hasAccount {
                placeholder("Chats need an identity (You tab).", symbol: AppIcons.chat)
            } else if environment.dmStore.conversations.isEmpty {
                placeholder(
                    environment.dmStore.loaded
                        ? "No conversations yet.\nNew message (✎) starts one by npub."
                        : "Connecting…",
                    symbol: AppIcons.chat
                )
            } else {
                List {
                    Group {
                        // NIP-17 privacy banner (mock `state-banner info`).
                        Section {
                            InfoBannerRow(
                                text: "NIP-17 secure messages — previews stay generic; content decrypts only inside the app."
                            )
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                        }
                        Section {
                            ForEach(accepted) { conversation in
                                ConversationRow(conversation: conversation)
                                    .onTapGesture { environment.dmStore.openConversation(conversation.peerPubkey) }
                            }
                        }
                        if !requests.isEmpty {
                            // Mock "Message requests — N waiting" row.
                            Section {
                                RequestsHeaderRow(count: requests.count)
                                    .onTapGesture {
                                        if let first = requests.first {
                                            environment.dmStore.openConversation(first.peerPubkey)
                                        }
                                    }
                                ForEach(requests) { conversation in
                                    ConversationRow(conversation: conversation, isRequest: true)
                                        .onTapGesture { environment.dmStore.openConversation(conversation.peerPubkey) }
                                }
                            }
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    newChatOpen = true
                } label: {
                    AppIcons.image(for: AppIcons.chat)
                }
                .accessibilityLabel("New message")
            }
        }
    }

    @ViewBuilder
    private func placeholder(_ message: String, symbol: String) -> some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
            AppIcons.image(for: symbol)
                .font(.system(size: 40, weight: .medium))
                .foregroundStyle(BitOSTheme.textTertiary)
            Text(message)
                .font(.system(size: 13))
                .foregroundStyle(BitOSTheme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, BitOSTheme.Spacing.screen)
    }
}

// ── Rows ──────────────────────────────────────────────────────────

private struct InfoBannerRow: View {
    let text: String

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            AppIcons.image(for: AppIcons.inbox)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(BitOSTheme.textTertiary)
            Text(text)
                .font(.system(size: 11))
                .foregroundStyle(BitOSTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 8)
        .padding(.horizontal, BitOSTheme.Spacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous)
                .fill(BitOSTheme.surfaceElevated)
        )
    }
}

private struct RequestsHeaderRow: View {
    let count: Int

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.md) {
            AppIcons.image(for: AppIcons.people)
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(BitOSTheme.textSecondary)
                .frame(width: 36, height: 36)
                .background(Circle().fill(BitOSTheme.surfaceElevated))
            VStack(alignment: .leading, spacing: 2) {
                Text("Message requests")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                Text("\(count) waiting — accepting never reveals you read them")
                    .font(.system(size: 12))
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            Spacer()
            AppIcons.image(for: AppIcons.chevronRight)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(BitOSTheme.textTertiary)
        }
        .contentShape(Rectangle())
    }
}

private struct ConversationRow: View {
    @Environment(AppEnvironment.self) private var environment
    let conversation: DmConversationMirror
    var isRequest: Bool = false

    private var profile: ProfileMetadata? {
        environment.feedStore.profiles[conversation.peerPubkey]
    }

    private var unread: Int {
        environment.dmStore.unreadCounts[conversation.peerPubkey] ?? 0
    }

    private var preview: String {
        environment.dmStore.previews[conversation.peerPubkey] ?? "No messages"
    }

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.md) {
            PubkeyAvatarView(
                pubkey: conversation.peerPubkey,
                size: 44,
                picture: profile?.picture
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(conversation.peerPubkey))
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                if let last = conversation.messages.last {
                    // Generic preview + time: "New message · 4m".
                    Text("\(preview) · \(FeedFormat.timeAgo(createdAt: last.createdAt))")
                        .font(.system(size: 12))
                        .foregroundStyle(unread > 0 ? BitOSTheme.textPrimary : BitOSTheme.textSecondary)
                        .lineLimit(1)
                }
            }
            Spacer()
            if unread > 0 {
                Circle()
                    .fill(BitOSTheme.accent)
                    .frame(width: 10, height: 10)
                    .accessibilityLabel("\(unread) unread")
            }
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
        .listRowBackground(isRequest ? BitOSTheme.surface.opacity(0.6) : BitOSTheme.surface)
        .listRowSeparatorTint(BitOSTheme.divider)
    }
}

// ── New chat (npub / hex) ─────────────────────────────────────────

private struct NewChatSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onStart: (String) -> Void
    @State private var value = ""
    @FocusState private var focused: Bool

    private var resolvedPubkey: String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if trimmed.hasPrefix("npub1") {
            return BusinessCoreBridge().parseNpub(encoded: trimmed)
        }
        return trimmed.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil ? trimmed : nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            Text("New message")
                .font(.system(size: 17, weight: .bold))
            Text("Address by npub or public key (hex).")
                .font(.system(size: 12))
                .foregroundStyle(BitOSTheme.textSecondary)
            BitosField("npub1…", text: $value)
                .focused($focused)
                .onSubmit { if let resolvedPubkey { onStart(resolvedPubkey) } }
            if !value.trimmingCharacters(in: .whitespaces).isEmpty && resolvedPubkey == nil {
                Text("Not a valid npub or hex pubkey.")
                    .font(.system(size: 12))
                    .foregroundStyle(BitOSTheme.error)
            }
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }
                    .foregroundStyle(BitOSTheme.textSecondary)
                Button("Chat") {
                    if let resolvedPubkey { onStart(resolvedPubkey) }
                }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(resolvedPubkey != nil ? BitOSTheme.accent : BitOSTheme.textTertiary)
                .disabled(resolvedPubkey == nil)
            }
        }
        .padding(BitOSTheme.Spacing.screen)
        .onAppear { focused = true }
    }
}

// ── Chat ──────────────────────────────────────────────────────────

private struct ChatView: View {
    @Environment(AppEnvironment.self) private var environment
    let peerPubkey: String
    let onZapPeer: ((String) -> Void)?
    let onOpenProfile: ((String) -> Void)?
    @State private var text = ""

    private var conversation: DmConversationMirror? {
        environment.dmStore.conversations.first { $0.peerPubkey == peerPubkey }
    }

    private var profile: ProfileMetadata? {
        environment.feedStore.profiles[peerPubkey]
    }

    private var displayName: String {
        profile?.bestDisplayName ?? FeedFormat.shortPubkey(peerPubkey)
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header: back · avatar+name (→ profile) · NIP-17 line · zap chip.
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Button {
                    environment.dmStore.openConversation(nil)
                } label: {
                    AppIcons.image(for: AppIcons.chevronLeft)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(BitOSTheme.textPrimary)
                }
                .accessibilityLabel("Back to messages")
                PubkeyAvatarView(pubkey: peerPubkey, size: 36, picture: profile?.picture)
                    .onTapGesture { onOpenProfile?(peerPubkey) }
                    .accessibilityAddTraits(.isButton)
                    .accessibilityLabel("Open profile")
                VStack(alignment: .leading, spacing: 1) {
                    Text(displayName)
                        .font(.system(size: 14, weight: .bold))
                        .lineLimit(1)
                    // Mock green "End-to-end encrypted · NIP-17" line.
                    Label {
                        Text("End-to-end encrypted · NIP-17")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(BitOSTheme.success)
                    } icon: {
                        AppIcons.image(for: AppIcons.lock)
                            .font(.system(size: 8, weight: .semibold))
                            .foregroundStyle(BitOSTheme.success)
                    }
                }
                Spacer()
                // Mock `chip chip-orange ⚡ Zap`.
                Button {
                    onZapPeer?(peerPubkey)
                } label: {
                    HStack(spacing: 3) {
                        AppIcons.image(for: AppIcons.zap)
                            .font(.system(size: 11, weight: .bold))
                        Text("Zap")
                            .font(.system(size: 12, weight: .bold))
                    }
                    .foregroundStyle(BitOSTheme.zap)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(
                        Capsule().fill(BitOSTheme.accentContainer.opacity(0.5))
                    )
                }
                .accessibilityLabel("Zap \(displayName)")
            }
            .padding(.horizontal, BitOSTheme.Spacing.sm)
            .padding(.vertical, BitOSTheme.Spacing.xs)

            ScrollView {
                LazyVStack(spacing: BitOSTheme.Spacing.sm) {
                    // Encryption banner (mock first chip row).
                    Text("Messages decrypt only in this app — never in a push notification")
                        .font(.system(size: 10))
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .padding(.horizontal, BitOSTheme.Spacing.md)
                        .padding(.vertical, 5)
                        .background(
                            Capsule().fill(BitOSTheme.surfaceElevated)
                        )
                        .padding(.bottom, BitOSTheme.Spacing.sm)
                    ForEach(conversation?.messages ?? []) { message in
                        MessageBubble(
                            message: message,
                            isMine: message.authorPubkey != peerPubkey,
                            delivered: environment.dmStore.deliveryByRumorId[message.id]
                        )
                    }
                }
                .padding(.horizontal, BitOSTheme.Spacing.screen)
                .padding(.vertical, BitOSTheme.Spacing.sm)
            }

            HStack(spacing: BitOSTheme.Spacing.sm) {
                BitosField("Message \(displayName)", text: $text)
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
    let delivered: Bool?

    var body: some View {
        HStack {
            if isMine { Spacer(minLength: 40) }
            VStack(alignment: .trailing, spacing: 2) {
                Text(message.content)
                    .font(.system(size: 14))
                    .foregroundStyle(isMine ? Color(hex: 0x0A0A0F) : BitOSTheme.textPrimary)
                    .frame(maxWidth: 280, alignment: isMine ? .trailing : .leading)
                // Delivery ticks: sending… → delivered (relay OK receipt).
                HStack(spacing: 3) {
                    Text(FeedFormat.timeAgo(createdAt: message.createdAt))
                    if isMine {
                        if delivered == true {
                            Text("· delivered")
                        } else if delivered == false {
                            Text("· sending…")
                        }
                    }
                }
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
