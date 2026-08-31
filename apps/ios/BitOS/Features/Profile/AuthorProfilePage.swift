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
    /** Note zap from a profile card. */
    @State private var noteZapTarget: FeedNote?
    /** Report-user flow (kind-1984, p-tag only). */
    @State private var reportReason = ""
    @State private var showReportPrompt = false
    /** Web Zaps-tab parity: this viewer's verified zaps to the author. */
    @State private var sentZaps = SentZapsStore()
    /** Web `/bitz?author=<npub>#bitz=<id>` parity: Bitz-tab tile → the
     *  shared reels player scoped to this author. */
    @State private var bitzPlayerTarget: BitzPlayerTarget?
    /** Web ProfileActionMenu parity popover (rounded pill rows). */
    @State private var moreMenu: AppMenuPresentation?

    private var profile: ProfileMetadata? { store?.profile }
    private var notes: [FeedNote] { store?.notes ?? [] }

    private var isFollowing: Bool { environment.feedStore.following.contains(authorPubkey) }
    private var hasLightning: Bool { !(profile?.lud16 ?? "").isEmpty }
    private var npub: String? { BusinessCoreBridge().npubEncode(pubkeyHex: authorPubkey) as String? }

    private var tabNotes: [FeedNote] { notes.filter { $0.replyTo == nil } }
    private var tabReplies: [FeedNote] { notes.filter { $0.replyTo != nil } }
    private var tabBitz: [FeedNote] { notes.filter { $0.video != nil || !$0.mediaUrls.isEmpty } }
    /// Verified zaps THIS viewer sent the author (local ledger only —
    /// relays can't truthfully total everyone else's zaps to an author).
    private var sentToAuthor: [SentZapsStore.SentZapRecord] {
        sentZaps.records.filter { $0.recipientPubkey == authorPubkey }
    }
    private var tabs: [(String, [FeedNote])] {
        [("Notes", tabNotes), ("Replies", tabReplies), ("Bitz", tabBitz), ("Zaps", [])]
    }

    private func zapFormatSats(_ sats: Int64) -> String {
        BusinessCoreBridge().zapFormatSats(sats: sats)
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
                    moreMenuButton
                }
            }
        }
        .preferredColorScheme(.dark)
        .appMenuHost($moreMenu)
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
        // Web ProfileBitzGrid parity: tile tap → shared reels player
        // scoped to this author, deep-linked at the tapped tile.
        .fullScreenCover(item: $bitzPlayerTarget) { target in
            BitzView(
                authorPubkey: target.authorPubkey,
                initialNoteId: target.noteId,
                onExitAuthorMode: { bitzPlayerTarget = nil }
            )
            .environment(environment)
            .environment(identity)
            .environment(settings)
            .preferredColorScheme(.dark)
        }
        // Note zap from a profile card.
        .sheet(item: $noteZapTarget) { target in
            ZapSheet(
                note: target,
                profiles: zapProfiles,
                initialAmountSats: settings.state.defaultZapAmount,
                zapCount: environment.feedStore.zapCounts[target.id] ?? 0,
                paidRequestIds: environment.feedStore.zapRequestIds[target.id] ?? [],
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
                onClose: { noteZapTarget = nil }
            )
            .environment(identity)
            .presentationDetents([.medium])
        }
        // Report user (kind-1984, p-tag only — web ProfileActionMenu parity).
        .alert("Report this user", isPresented: $showReportPrompt) {
            TextField("Reason (spam, harassment…)", text: $reportReason)
            Button("Report", role: .destructive) {
                let reason = reportReason.trimmingCharacters(in: .whitespacesAndNewlines)
                reportReason = ""
                guard !reason.isEmpty else { return }
                Task {
                    await environment.notePublisher.publishReport(
                        targetEventId: nil, targetPubkey: authorPubkey, reason: reason
                    )
                }
            }
            Button("Cancel", role: .cancel) { reportReason = "" }
        } message: {
            Text("The report is published as a kind-1984 event.")
        }
    }

    /// Feed-store profiles overlaid with this author's live kind-0.
    private var zapProfiles: [String: ProfileMetadata] {
        var merged = environment.feedStore.profiles
        if let profile { merged[authorPubkey] = profile }
        return merged
    }

    // MARK: - Note actions (web PostCard parity, shared-core paths)

    private func like(_ note: FeedNote) {
        let turningOn = !environment.feedStore.localActions.liked.contains(note.id)
        environment.feedStore.localActions.toggleLike(note.id)
        guard identity.account != nil else { return }
        if turningOn {
            Task { await environment.notePublisher.publishReaction(targetEventId: note.id, targetPubkey: note.pubkey) }
        } else if let reactionId = environment.feedStore.myReactionEventIds[note.id] {
            // Web unlike parity: delete my kind-7 from relays (NIP-09).
            Task { await environment.notePublisher.publishDeletion(targetEventIds: [reactionId]) }
        }
    }

    private func repostNote(_ note: FeedNote) {
        guard identity.account != nil else { return }
        Task { await environment.notePublisher.publishRepost(targetEventId: note.id, targetPubkey: note.pubkey) }
    }

    private func openNoteZap(_ note: FeedNote) {
        environment.feedStore.loadZaps(targetEventId: note.id)
        noteZapTarget = note
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
            // Web stats parity: sats THIS viewer zapped the author (local
            // verified ledger — relays can't truthfully total everyone).
            Button { showZap = true } label: {
                stat("Zapped by you", zapFormatSats(Int64(sentToAuthor.reduce(0) { $0 + $1.amountSats })))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Zap author")
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

    // MARK: - More menu (copy link · npub · lightning · mute · report)

    /// Web ProfileActionMenu parity: the AppMenu popover (rounded pill
    /// rows with press fill) instead of a system-styled Menu.
    private var moreMenuButton: some View {
        let entries: [AppMenuEntry] = {
            var items: [AppMenuEntry] = []
            if let npub {
                items.append(.item(AppMenuItem(id: "copy-link", label: "Copy profile link", systemImage: "link")))
                items.append(.item(AppMenuItem(id: "copy-npub", label: "Copy npub", systemImage: AppIcons.copy)))
            }
            if let lud16 = profile?.lud16, !lud16.isEmpty {
                items.append(.item(AppMenuItem(id: "copy-lightning", label: "Copy lightning address", systemImage: AppIcons.zap)))
            }
            if identity.account?.pubkeyHex != authorPubkey {
                let muted = environment.feedStore.muted.contains(authorPubkey)
                items.append(.divider)
                items.append(.item(AppMenuItem(
                    id: "mute",
                    label: muted ? "Unmute author" : "Mute author",
                    systemImage: muted ? "speaker.wave.2" : "speaker.slash"
                )))
                items.append(.item(AppMenuItem(
                    id: "report",
                    label: "Report user…",
                    systemImage: "exclamationmark.bubble",
                    isDestructive: true
                )))
            }
            return items
        }()
        return Button {
            // no-op — the overlay tap gesture below reports the anchor
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(BitOSTheme.textPrimary)
                .frame(width: 36, height: 36)
                .contentShape(Rectangle())
        }
        .overlay {
            GeometryReader { geo in
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture {
                        let frame = geo.frame(in: .global)
                        moreMenu = AppMenuPresentation(
                            anchor: CGPoint(x: frame.maxX, y: frame.minY),
                            entries: entries
                        ) { id in
                            handleMoreMenu(id)
                        }
                    }
            }
        }
        .accessibilityLabel("More profile actions")
        .accessibilityAddTraits(.isButton)
    }

    private func handleMoreMenu(_ id: String) {
        switch id {
        case "copy-link":
            if let npub { UIPasteboard.general.string = "https://njump.me/\(npub)" }
        case "copy-npub":
            if let npub { UIPasteboard.general.string = npub }
        case "copy-lightning":
            if let lud16 = profile?.lud16 { UIPasteboard.general.string = lud16 }
        case "mute":
            environment.feedStore.toggleMute(authorPubkey)
        case "report":
            showReportPrompt = true
        default:
            break
        }
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
        if tab == 3 {
            // Zaps — this viewer's verified zaps to the author (wallet rule).
            zapTabContent
        } else {
            let content = tabs[tab].1
            if (store?.isLoading ?? true), notes.isEmpty {
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
                            bitzPlayerTarget = .init(authorPubkey: authorPubkey, noteId: note.id)
                        } label: {
                            BitzGridTile(note: note)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Play bitz")
                        .onAppear {
                            if note.id == content.last?.id { store?.loadMoreNotes() }
                        }
                    }
                }
                .padding(.horizontal, 2)
                loadMoreFooter
            } else {
                notesList(content)
            }
        }
    }

    /// Zaps tab body (web parity): rows from the local verified ledger.
    @ViewBuilder
    private var zapTabContent: some View {
        if sentToAuthor.isEmpty {
            VStack(spacing: BitOSTheme.Spacing.md) {
                Text("⚡").font(.system(size: 34))
                Text("You haven't zapped this author yet")
                    .font(.system(size: 14))
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, BitOSTheme.Spacing.xxl)
        } else {
            LazyVStack(spacing: BitOSTheme.Spacing.sm) {
                ForEach(Array(sentToAuthor.prefix(50).enumerated()), id: \.offset) { index, record in
                    ZapLedgerRowView(
                        row: .init(
                            id: "sent-\(record.id)",
                            direction: "sent",
                            sats: record.amountSats,
                            peer: record.recipientPubkey,
                            at: record.createdAt,
                            memo: record.memo ?? "",
                            note: record.targetNoteId ?? ""
                        ),
                        profile: environment.feedStore.profiles[record.recipientPubkey]
                    )
                }
            }
            .padding(.horizontal, BitOSTheme.Spacing.base)
        }
    }
    /// Notes/replies list — shared card, X-style thread open on tap.
    @ViewBuilder
    private func notesList(_ content: [FeedNote]) -> some View {
        ForEach(Array(content), id: \.id) { note in
            ProfileNoteCard(
                note: note,
                profile: profile,
                onOpen: { threadTarget = note },
                actionRow: NoteActionRow(
                    isLiked: environment.feedStore.localActions.liked.contains(note.id),
                    tally: environment.feedStore.tallies[note.id],
                    onLike: { like(note) },
                    onRepost: { repostNote(note) },
                    onZap: { openNoteZap(note) }
                )
            )
            .onAppear {
                if note.id == content.last?.id { store?.loadMoreNotes() }
            }
        }
        loadMoreFooter
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
            case 3: return (AppIcons.zap, "No zaps yet")
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
