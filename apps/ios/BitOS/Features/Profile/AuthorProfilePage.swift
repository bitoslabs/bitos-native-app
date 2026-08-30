import BusinessCore
import SwiftUI
import UIKit

/**
 * Full-page profile for any author pubkey (UX-010): "You"-page visual parity
 * — full-bleed cover with hex-pattern fallback, hex avatar hero, identity
 * block, Zap + Follow actions, stats, collapsible about, and a pinned
 * Notes · Replies · Bitz tab rail. Data comes from a private AuthorStore REQ
 * so presenting this page over the profile sheet never races the shared
 * store's lifecycle.
 */
struct AuthorProfilePage: View {
    let authorPubkey: String
    let onClose: () -> Void

    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    @Environment(SettingsStore.self) private var settings
    @State private var store: AuthorStore?
    @State private var tab = 0
    @State private var npubCopied = false
    @State private var showZap = false
    /** X-style: tapping a note/grid tile opens its thread (CommentSheet). */
    @State private var threadTarget: FeedNote?

    private var profile: ProfileMetadata? { store?.profile }
    private var notes: [FeedNote] { store?.notes ?? [] }

    private var isFollowing: Bool { environment.feedStore.following.contains(authorPubkey) }
    private var hasLightning: Bool { !(profile?.lud16 ?? "").isEmpty }
    private var npub: String? { BusinessCoreBridge().npubEncode(pubkeyHex: authorPubkey) as String? }

    private var tabNotes: [FeedNote] { notes.filter { $0.replyTo == nil } }
    private var tabReplies: [FeedNote] { notes.filter { $0.replyTo != nil } }
    private var tabBitz: [FeedNote] { notes.filter { $0.video != nil || !$0.mediaUrls.isEmpty } }
    private var tabs: [(String, [FeedNote])] {
        [("Notes", tabNotes), ("Replies", tabReplies), ("Bitz", tabBitz)]
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: BitOSTheme.Spacing.md, pinnedViews: [.sectionHeaders]) {
                    heroSection
                    identityBlock
                    actionRow
                    statsRow
                    ProfileAboutSection(profile: profile)
                    Section(header: pinnedTabBar) {
                        tabContent
                    }
                }
            }
            .background(BitOSTheme.background)
            .ignoresSafeArea(edges: .top)
            .navigationTitle(profile?.bestDisplayName ?? FeedFormat.shortPubkey(authorPubkey))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        onClose()
                    } label: {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(BitOSTheme.textPrimary)
                    }
                    .accessibilityLabel("Close profile")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    moreMenu
                }
            }
        }
        .preferredColorScheme(.dark)
        .task {
            // Private store: cover/sheet lifecycles cannot clear this page's data.
            let store = store ?? AuthorStore(pool: environment.relayPool, client: environment.businessCore)
            self.store = store
            store.open(authorPubkey: authorPubkey)
        }
        .onDisappear { store?.close() }
        .sheet(isPresented: $showZap) {
            ZapSheet(
                authorPubkey: authorPubkey,
                profile: profile,
                initialAmountSats: settings.state.defaultZapAmount,
                onPaid: { sats, memo in
                    environment.sentZaps.record(.init(
                        id: "zap-\(authorPubkey)-\(sats)-\(Int(Date.now.timeIntervalSince1970))",
                        amountSats: Int64(sats),
                        recipientPubkey: authorPubkey,
                        createdAt: Int64(Date.now.timeIntervalSince1970),
                        targetNoteId: nil,
                        memo: memo.isEmpty ? nil : memo
                    ))
                },
                onClose: { showZap = false }
            )
            .environment(identity)
            .presentationDetents([.medium])
        }
        // X-style note detail: the tapped note's thread.
        .sheet(item: $threadTarget) { target in
            CommentSheet(
                note: target,
                store: environment.feedStore,
                publisher: environment.notePublisher,
                onClose: { threadTarget = nil }
            )
            .environment(identity)
            .presentationDetents([.medium, .large])
        }
    }

    // MARK: - Hero (cover + avatar, "You"-page parity)

    private var heroSection: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .top) {
                bannerCover
                    .frame(height: 160 + statusBarInset)
            }
            ZStack(alignment: .top) {
                Color.clear.frame(height: 64)
                // Hex plate behind the avatar: the mock's border-4 float —
                // the avatar reads as a pure hexagon over the cover edge.
                ZStack {
                    HexShape()
                        .fill(BitOSTheme.background)
                        .frame(width: 100, height: 100)
                        .shadow(color: .black.opacity(0.18), radius: 12, y: 5)
                    HexAvatarView(
                        pubkey: authorPubkey,
                        size: 92,
                        imageURL: safeProfilePictureURL(profile?.picture),
                        label: profile?.bestDisplayName,
                        hasLightning: hasLightning
                    )
                }
                .offset(y: -46)
                .shadow(color: BitOSTheme.accent.opacity(0.14), radius: 8)
            }
        }
    }

    private var bannerCover: some View {
        ZStack {
            if let banner = profile?.banner, !banner.isEmpty, let url = URL(string: banner) {
                AsyncImage(url: url) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    defaultCover
                }
                .overlay(DefaultCoverHexPattern())
            } else {
                defaultCover
            }
            LinearGradient(
                stops: [
                    .init(color: .black.opacity(0.25), location: 0),
                    .init(color: .clear, location: 0.45),
                    .init(color: .black.opacity(0.35), location: 1),
                ],
                startPoint: .top, endPoint: .bottom
            )
        }
    }

    private var defaultCover: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.976, green: 0.659, blue: 0.294), Color(red: 0.831, green: 0.475, blue: 0.059)],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
            DefaultCoverHexPattern()
        }
    }

    private var statusBarInset: CGFloat {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first(where: { $0.keyWindow != nil })?.keyWindow?.safeAreaInsets.top ?? 0
    }

    // MARK: - Identity block ("You"-page parity)

    private var identityBlock: some View {
        VStack(spacing: 4) {
            HStack(spacing: 4) {
                Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(authorPubkey))
                    .font(.system(size: 24, weight: .heavy))
                    .lineLimit(1)
                if let nip05 = profile?.nip05, !nip05.isEmpty {
                    Image(systemName: AppIcons.checkCircle)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(BitOSTheme.cyan)
                        .accessibilityLabel("NIP-05 verified")
                }
            }
            if let nip05 = profile?.nip05, !nip05.isEmpty {
                Text(nip05)
                    .font(.system(size: 13))
                    .foregroundStyle(BitOSTheme.accent)
                    .lineLimit(1)
            }
            Button {
                if let npub {
                    UIPasteboard.general.string = npub
                    UISelectionFeedbackGenerator().selectionChanged()
                    npubCopied = true
                    Task {
                        try? await Task.sleep(for: .seconds(1.8))
                        npubCopied = false
                    }
                }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: npubCopied ? AppIcons.check : AppIcons.copy)
                        .font(.system(size: 13))
                        .foregroundStyle(npubCopied ? BitOSTheme.success : BitOSTheme.textSecondary)
                    Text(settings.shortNpub(npub ?? FeedFormat.shortPubkey(authorPubkey)))
                        .font(.system(size: 11.5, design: .monospaced))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(Capsule().fill(BitOSTheme.surfaceOverlay.opacity(0.5)))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Copy npub")
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, BitOSTheme.Spacing.base)
    }

    // MARK: - Actions: Zap + Follow (UX-010 mock parity)

    private var actionRow: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            Button {
                guard hasLightning else { return }
                showZap = true
            } label: {
                HStack(spacing: 8) {
                    AppIcons.image(for: AppIcons.zap)
                        .font(.system(size: 15, weight: .semibold))
                    Text("Zap")
                        .font(.system(size: 14, weight: .bold))
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(Capsule(style: .continuous).fill(hasLightning ? BitOSTheme.accent : BitOSTheme.surfaceOverlay))
            }
            .buttonStyle(.plain)
            .disabled(!hasLightning)
            .accessibilityLabel(hasLightning ? "Zap this profile" : "No lightning address")

            Button {
                if let updated = environment.feedStore.applyFollowChange(
                    author: authorPubkey,
                    add: !isFollowing
                ) {
                    guard identity.account != nil else { return }
                    Task { await environment.notePublisher.publishFollowList(follows: updated) }
                }
            } label: {
                Text(isFollowing ? "Following ✓" : "Follow")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(isFollowing ? BitOSTheme.textPrimary : BitOSTheme.textPrimary)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(
                        Capsule(style: .continuous).fill(isFollowing ? BitOSTheme.surface : BitOSTheme.textPrimary)
                    )
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isFollowing ? "Unfollow" : "Follow")
        }
        .padding(.horizontal, BitOSTheme.Spacing.base)
    }

    // MARK: - Stats (truthful counts from the author REQ window)

    private var statsRow: some View {
        HStack {
            Spacer()
            stat("Posts", FeedFormat.count(tabNotes.count))
            Spacer()
            stat("Replies", FeedFormat.count(tabReplies.count))
            Spacer()
            stat("Bitz", FeedFormat.count(tabBitz.count))
            Spacer()
        }
        .padding(.horizontal, BitOSTheme.Spacing.base)
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(BitOSTheme.textPrimary)
            Text(label)
                .font(.system(size: 12))
                .foregroundStyle(BitOSTheme.textSecondary)
        }
    }

    // MARK: - More menu (copy link · npub · lightning)

    private var moreMenu: some View {
        Menu {
            if let npub {
                Button {
                    UIPasteboard.general.string = "https://njump.me/\(npub)"
                } label: {
                    Label("Copy profile link", systemImage: "link")
                }
                Button {
                    UIPasteboard.general.string = npub
                } label: {
                    Label("Copy npub", systemImage: AppIcons.copy)
                }
            }
            if let lud16 = profile?.lud16, !lud16.isEmpty {
                Button {
                    UIPasteboard.general.string = lud16
                } label: {
                    Label("Copy lightning address", systemImage: AppIcons.zap)
                }
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(BitOSTheme.textPrimary)
                .frame(width: 36, height: 36)
        }
        .accessibilityLabel("More profile actions")
    }

    // MARK: - Pinned tab rail (Notes · Replies · Bitz)

    private var pinnedTabBar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                ForEach(Array(tabs.enumerated()), id: \.offset) { index, entry in
                    let selected = tab == index
                    Button {
                        withAnimation(.easeInOut(duration: 0.18)) { tab = index }
                    } label: {
                        VStack(spacing: 8) {
                            Text(entry.0)
                                .font(.system(size: 14, weight: selected ? .bold : .medium))
                                .foregroundStyle(selected ? BitOSTheme.accent : BitOSTheme.textSecondary)
                            Capsule()
                                .fill(selected ? BitOSTheme.accent : .clear)
                                .frame(height: 3)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? [.isSelected] : [])
                }
            }
            Rectangle()
                .fill(BitOSTheme.border.opacity(0.15))
                .frame(height: 1)
        }
        .padding(.top, statusBarInset)
        .background(BitOSTheme.background)
    }

    // MARK: - Tab content

    @ViewBuilder
    private var tabContent: some View {
        let content = tabs[tab].1
        if store?.isLoading ?? true, notes.isEmpty {
            ProgressView()
                .tint(BitOSTheme.accent)
                .frame(maxWidth: .infinity)
                .padding(BitOSTheme.Spacing.xxl)
        } else if content.isEmpty {
            tabEmptyState
        } else if tab == 2 {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 3), spacing: 2) {
                ForEach(Array(content), id: \.id) { note in
                    Button {
                        threadTarget = note
                    } label: {
                        BitzGridTile(note: note)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Open note thread")
                    .onAppear {
                        if note.id == content.last?.id { store?.loadMoreNotes() }
                    }
                }
            }
            .padding(.horizontal, 2)
            loadMoreFooter
        } else {
            ForEach(Array(content), id: \.id) { note in
                ProfileNoteCard(note: note, profile: profile) {
                    threadTarget = note
                }
                .onAppear {
                    if note.id == content.last?.id { store?.loadMoreNotes() }
                }
            }
            loadMoreFooter
        }
    }

    /// Pages of five load on demand; footer surfaces the in-flight page.
    @ViewBuilder
    private var loadMoreFooter: some View {
        if store?.isLoadingMore == true {
            HStack(spacing: 6) {
                ProgressView()
                    .tint(BitOSTheme.accent)
                Text("Loading more…")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, BitOSTheme.Spacing.md)
        }
    }

    @ViewBuilder
    private var tabEmptyState: some View {
        let (symbol, message): (String, String) = {
            switch tab {
            case 1: return (AppIcons.comment, "No replies yet")
            case 2: return (AppIcons.photo, "No bitz yet")
            default: return (AppIcons.pen, "No posts yet")
            }
        }()
        VStack(spacing: BitOSTheme.Spacing.md) {
            AppIcons.image(for: symbol)
                .font(.system(size: 48, weight: .medium))
                .foregroundStyle(BitOSTheme.textSecondary.opacity(0.5))
            Text(message)
                .font(.system(size: 14))
                .foregroundStyle(BitOSTheme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, BitOSTheme.Spacing.xxl)
    }
}
