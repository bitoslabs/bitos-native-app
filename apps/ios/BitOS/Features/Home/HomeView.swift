import AVKit
import SwiftUI
import UIKit

/// Home surface (FED-001): full-screen vertical paging feed; only the
/// settled page plays. Text notes render as full-screen cards, video notes
/// (kind 22 or legacy mp4 links) render through the three-slot player pool.
struct HomeView: View {
    @State private var store: FeedStore
    @State private var topId: String?
    @State private var pool = PlayerPool()
    @State private var showComposer = false
    @State private var commentTarget: FeedNote?
    @State private var zapTarget: FeedNote?
    @State private var showMediaImport = false
    @State private var authorTarget: String?
    @State private var menu: AppMenuPresentation?
    var videoOnly: Bool = false
    var onOpenDiscover: () -> Void = {}
    var onOpenProfile: () -> Void = {}
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity

    init(store: FeedStore, videoOnly: Bool = false, onOpenDiscover: @escaping () -> Void = {}, onOpenProfile: @escaping () -> Void = {}) {
        _store = State(initialValue: store)
        self.videoOnly = videoOnly
        self.onOpenDiscover = onOpenDiscover
        self.onOpenProfile = onOpenProfile
    }

    /// Per-surface state (fixes the video-only-tab blank-ready edge logged
    /// in the tracker): emptiness is judged on the FILTERED tab notes.
    private var surfaceState: FeedStore.FeedState {
        if !notes.isEmpty { return .ready }
        return store.hasLoadedAnyEvent ? .empty : .loading
    }

    private var notes: [FeedNote] {
        // Shell split (user decision): Home tab = text notes; Bitz tab =
        // video reels. One verified store feeds both surfaces.
        videoOnly ? store.notes.filter { $0.video != nil } : store.notes.filter { $0.video == nil }
    }

    private func toggleFollow(author: String) {
        guard let updated = store.applyFollowChange(author: author, add: !store.following.contains(author)) else { return }
        guard environment.identityStore.account != nil else { return }
        Task { await environment.notePublisher.publishFollowList(follows: updated) }
    }

    private func toggleBookmark(_ note: FeedNote) {
        if let updated = store.applyBookmarkChange(eventId: note.id, add: !store.bookmarkedIds.contains(note.id)) {
            guard environment.identityStore.account != nil else { return }
            Task { await environment.notePublisher.publishBookmarkList(eventIds: updated) }
        } else {
            store.localActions.toggleBookmark(note.id)
        }
    }

    private func repost(_ note: FeedNote) {
        guard environment.identityStore.account != nil else { return }
        Task { await environment.notePublisher.publishRepost(targetEventId: note.id, targetPubkey: note.pubkey) }
    }

    private func report(_ note: FeedNote, reason: String) {
        guard environment.identityStore.account != nil else { return }
        Task { await environment.notePublisher.publishReport(targetEventId: note.id, targetPubkey: note.pubkey, reason: reason) }
        menu = nil
    }

    private func toggleMute(_ author: String) {
        store.toggleMute(author)
        menu = nil
    }

    /// APP-022: screen-clamped popover at the ⋯ trigger (spec §3.4/§3.5 —
    /// mute, report, copy/raw-JSON grow here as surfaces land).
    private func presentMoreMenu(for note: FeedNote, at anchor: CGPoint) {
        menu = AppMenuPresentation(
            anchor: anchor,
            entries: [
                .item(AppMenuItem(
                    id: "mute",
                    label: store.muted.contains(note.pubkey) ? "Unmute author" : "Mute author",
                    systemImage: AppIcons.mute
                )),
                .divider,
                .item(AppMenuItem(id: "report-spam", label: "Report as spam", systemImage: AppIcons.reportSpam, isDestructive: true)),
                .item(AppMenuItem(id: "report-illicit", label: "Report as illicit", systemImage: AppIcons.reportIllicit, isDestructive: true)),
                .item(AppMenuItem(id: "report-harassment", label: "Report as harassment", systemImage: AppIcons.reportHarassment, isDestructive: true)),
            ]
        ) { id in
            switch id {
            case "mute": toggleMute(note.pubkey)
            case "report-spam": report(note, reason: "spam")
            case "report-illicit": report(note, reason: "illicit")
            case "report-harassment": report(note, reason: "harassment")
            default: break
            }
        }
    }

    /// APP-004: content-filter popover (spec §3.4). Ordinals mirror the
    /// shared `FeedFilter` enum (ALL=0 … MINE=5).
    private func presentFilterMenu(at anchor: CGPoint) {
        let options: [(String, String)] = [
            ("ALL", "All"), ("ORIGINALS", "Original"), ("REPLIES", "Replies"),
            ("MEDIA", "Media"), ("LIKED", "Liked"), ("MINE", "Mine"),
        ]
        menu = AppMenuPresentation(
            anchor: anchor,
            entries: options.enumerated().map { index, option in
                .item(AppMenuItem(id: option.0, label: option.1, isChecked: index == store.filterOrdinal))
            }
        ) { id in
            if let index = options.firstIndex(where: { $0.0 == id }) {
                store.selectFilter(index)
            }
        }
    }

    private func like(_ note: FeedNote) {
        let turningOn = !store.localActions.liked.contains(note.id)
        store.localActions.toggleLike(note.id)
        // Signed accounts publish a real kind-7 reaction on like; unlikes
        // stay local until reaction deletion (kind 5) lands with SOC-002.
        guard turningOn, environment.identityStore.account != nil else { return }
        Task { await environment.notePublisher.publishReaction(targetEventId: note.id, targetPubkey: note.pubkey) }
    }

    var body: some View {
        NavigationStack {
            content
                .background(BitOSTheme.background)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .principal) { header }
                }
                .task { await store.start() }
                .onChange(of: environment.identityStore.account) { _, account in
                    store.setAccount(account?.pubkeyHex)
                }
                .onAppear {
                    store.setAccount(environment.identityStore.account?.pubkeyHex)
                    store.holdNewNotes(false)
                }
                // APP-004: arrivals hold while scrolled into the pager;
                // being at the top (or unset) auto-reveals.
                .onChange(of: topId) { _, id in
                    let atTop = id == nil || id == store.notes.first?.id
                    store.holdNewNotes(!atTop)
                }
                .onDisappear {
                    store.stop()
                    pool.releaseAll()
                }
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        HStack(spacing: BitOSTheme.Spacing.sm) {
                            Button {
                                showMediaImport = true
                            } label: {
                                Image(systemName: "photo.on.rectangle")
                            }
                            .accessibilityLabel("Import and publish a video")
                            Button {
                                showComposer = true
                            } label: {
                                Image(systemName: "square.and.pencil")
                            }
                            .accessibilityLabel("Compose a note")
                        }
                    }
                }
                .sheet(item: Binding(
                    get: { authorTarget.map { AuthorTarget(id: $0) } },
                    set: { authorTarget = $0?.id }
                )) { target in
                    AuthorProfileSheet(
                        authorPubkey: target.id,
                        onClose: { authorTarget = nil }
                    )
                    .environment(identity)
                    .presentationDetents([.medium, .large])
                }
                .sheet(isPresented: $showMediaImport) {
                    ImportMediaSheet(onClose: { showMediaImport = false })
                        .environment(identity)
                        .presentationDetents([.medium, .large])
                }
                .appMenuHost($menu)
                .sheet(item: $zapTarget) { target in
                    ZapSheet(
                        note: target,
                        profiles: store.profiles,
                        onClose: { zapTarget = nil }
                    )
                    .environment(identity)
                    .presentationDetents([.medium])
                }
                .sheet(item: $commentTarget) { target in
                    CommentSheet(
                        note: target,
                        store: store,
                        publisher: environment.notePublisher,
                        onClose: { commentTarget = nil }
                    )
                    .environment(identity)
                    .presentationDetents([.medium, .large])
                }
                .sheet(isPresented: $showComposer) {
                    ComposerSheet(publisher: environment.notePublisher) {
                        environment.notePublisher.dismiss()
                        showComposer = false
                    }
                    .presentationDetents([.medium, .large])
                }
        }
        .preferredColorScheme(.dark)
    }

    @ViewBuilder
    private var content: some View {
        VStack(spacing: 0) {
            if !videoOnly && !store.pendingNotes.isEmpty {
                NewNotesPill(
                    count: store.pendingNotes.count,
                    authors: store.pendingAuthors,
                    onReveal: { store.revealPendingNotes() }
                )
            }
            if identity.account == nil {
                GuestBanner(onGetStarted: onOpenProfile)
            }
            switch store.timeline {
            case .forYou:
                switch surfaceState {
                case .loading: FeedLoadingView()
                case .empty: FeedEmptyView(health: store.relayHealth)
                case .ready:
                    if videoOnly { pager } else { notesList }
                }
            case .following:
                FollowingPlaceholderView()
            }
        }
        .overlay(alignment: .bottomTrailing) {
            // APP-004: New-note extended FAB (spec §3.4).
            Button {
                showComposer = true
            } label: {
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    AppIcons.image(for: AppIcons.pen)
                    Text("New note")
                }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(BitOSTheme.background)
                .padding(.horizontal, BitOSTheme.Spacing.base)
                .padding(.vertical, 12)
                .background(Capsule().fill(BitOSTheme.accent))
            }
            .padding(BitOSTheme.Spacing.base)
            .accessibilityLabel("Compose a note")
        }
    }

    // MARK: - Vertical pager (iOS 17 scroll-target paging)

    /// Home tab: scrolling compact NoteCard list (legacy UX parity).
    private var notesList: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(notes) { note in
                    NoteCardRow(
                        note: note,
                        profile: store.profiles[note.pubkey],
                        actions: store.localActions,
                        isBookmarked: store.bookmarkedIds.contains(note.id) || store.localActions.bookmarked.contains(note.id),
                        richJson: store.richTokens(for: note.content),
                        onLike: { like(note) },
                        onBookmark: { toggleBookmark(note) },
                        onComment: { commentTarget = note },
                        onRepost: { repost(note) },
                        onZap: { zapTarget = note },
                        onAuthor: { authorTarget = note.pubkey },
                        onMore: { point in presentMoreMenu(for: note, at: point) }
                    )
                    Divider().background(BitOSTheme.divider)
                }
            }
        }
        .refreshable { store.refresh() }
        .onAppear { store.holdNewNotes(!store.notes.isEmpty) }
    }

    private var pager: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(notes) { note in
                    pagerPage(note)
                        .containerRelativeFrame(.vertical)
                        .id(note.id)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: pagerPosition)
        .scrollIndicators(.hidden)
        .ignoresSafeArea(edges: .bottom)
        .onAppear {
            if topId == nil { topId = notes.first?.id }
        }
        .onChange(of: topId) { _, newValue in
            pool.update(visibleId: newValue, notes: notes)
        }
        .onChange(of: notes.count) { _, _ in
            pool.update(visibleId: topId, notes: notes)
        }
        .refreshable { store.refresh() }
    }

    private var pagerPosition: Binding<String?> {
        Binding(
            get: { topId },
            set: { topId = $0 }
        )
    }

    private func pagerPage(_ note: FeedNote) -> some View {
        FeedPage(
            note: note,
            profiles: store.profiles,
            actions: store.localActions,
            pool: pool,
            following: store.following,
            bookmarks: store.bookmarkedIds,
            richJson: store.richTokens(for: note.content),
            onLike: { like(note) },
            onBookmark: { toggleBookmark(note) },
            onComment: { commentTarget = note },
            onRepost: { repost(note) },
            onFollow: { toggleFollow(author: note.pubkey) },
            onZap: { zapTarget = note },
            onAuthor: { authorTarget = note.pubkey },
            onMore: { point in presentMoreMenu(for: note, at: point) }
        )
    }

    private var header: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            TimelineTab(title: "For You", isSelected: store.timeline == .forYou) {
                store.selectTimeline(.forYou)
            }
            TimelineTab(title: "Following", isSelected: store.timeline == .following) {
                store.selectTimeline(.following)
            }
            Spacer(minLength: BitOSTheme.Spacing.base)
            RelayHealthPill(health: store.relayHealth)
            AppMenuAnchorButton(
                symbol: AppIcons.filter,
                tint: store.filterOrdinal == 0 ? BitOSTheme.textSecondary : BitOSTheme.accent,
                label: "Filter notes"
            ) { point in
                presentFilterMenu(at: point)
            }
            Button(action: onOpenDiscover) {
                Image(systemName: AppIcons.search)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .padding(8)
                    .background(Circle().fill(BitOSTheme.surfaceOverlay))
            }
            .accessibilityLabel("Search")
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - APP-004 chrome: new-notes pill + guest banner

/// "↑ N new notes" reveal pill with a stacked author avatar row — tap
/// reveals, never auto-jumps (spec §3.4).
private struct NewNotesPill: View {
    let count: Int
    let authors: [String]
    let onReveal: () -> Void

    var body: some View {
        Button(action: onReveal) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                HStack(spacing: -6) {
                    ForEach(authors, id: \.self) { pubkey in
                        PubkeyAvatarView(pubkey: pubkey, size: 20)
                            .overlay(Circle().stroke(BitOSTheme.surface, lineWidth: 1.5))
                    }
                }
                Text("↑ \(count) new \(count == 1 ? "note" : "notes")")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(BitOSTheme.accent)
            }
            .padding(.horizontal, BitOSTheme.Spacing.base)
            .padding(.vertical, 8)
            .background(Capsule().fill(BitOSTheme.accent.opacity(0.14)))
        }
        .buttonStyle(.plain)
        .padding(.top, BitOSTheme.Spacing.xs)
        .accessibilityLabel("Show \(count) new notes")
    }
}

/// Guest banner — browsing without an identity (spec §3.4).
private struct GuestBanner: View {
    let onGetStarted: () -> Void

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            Text("Browsing BitOS as a guest")
                .font(.system(size: 12))
                .foregroundStyle(BitOSTheme.textSecondary)
            Spacer()
            Button("Get started", action: onGetStarted)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(BitOSTheme.accent)
        }
        .padding(.horizontal, BitOSTheme.Spacing.base)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous)
                .fill(BitOSTheme.surfaceElevated)
        )
        .padding(.horizontal, BitOSTheme.Spacing.screen)
        .padding(.top, BitOSTheme.Spacing.xs)
    }
}

/// Derivation of presentation state so empty/loading is decided in one place.
private extension FeedStore {
    enum FeedState { case loading, empty, ready }

    var state: FeedState {
        if !notes.isEmpty { return .ready }
        return hasLoadedAnyEvent ? .empty : .loading
    }
}

// MARK: - APP-005: compact feed card (Home tab list parity)

private struct NoteCardRow: View {
    let note: FeedNote
    let profile: ProfileMetadata?
    let actions: LocalActions
    let isBookmarked: Bool
    var richJson: String = "[]"
    let onLike: () -> Void
    let onBookmark: () -> Void
    let onComment: () -> Void
    let onRepost: () -> Void
    let onZap: () -> Void
    let onAuthor: () -> Void
    let onMore: (CGPoint) -> Void
    @State private var revealed = false
    @State private var lightboxUrl: String?

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                PubkeyAvatarView(pubkey: note.pubkey, size: 36)
                    .onTapGesture(perform: onAuthor)
                VStack(alignment: .leading, spacing: 1) {
                    Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(note.pubkey))
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(BitOSTheme.textPrimary)
                        .lineLimit(1)
                    HStack(spacing: 4) {
                        if note.repostedBy != nil {
                            AppIcons.image(for: AppIcons.repost)
                                .font(.system(size: 10))
                                .foregroundStyle(BitOSTheme.repost)
                        }
                        Text(FeedFormat.timeAgo(createdAt: note.createdAt))
                            .font(.system(size: 11))
                            .foregroundStyle(BitOSTheme.textTertiary)
                    }
                }
                Spacer()
                AppMenuAnchorButton(symbol: AppIcons.more, tint: BitOSTheme.textSecondary, label: "More options", action: onMore)
            }
            if note.contentWarning && !revealed {
                SensitiveCover { revealed = true }
            } else {
                RichTextView(json: richJson, onOpenProfile: { _ in onAuthor() }, onOpenHashtag: nil)
                MediaGrid(urls: note.mediaUrls) { lightboxUrl = $0 }
            }
            HStack(spacing: BitOSTheme.Spacing.base) {
                cardAction(AppIcons.comment, "Replies", BitOSTheme.reply, onComment)
                cardAction(AppIcons.repost, "Repost", BitOSTheme.repost, onRepost)
                let liked = actions.liked.contains(note.id)
                cardAction(liked ? AppIcons.heartFill : AppIcons.heart, liked ? "Unlike" : "Like", liked ? BitOSTheme.like : BitOSTheme.textSecondary, onLike)
                cardAction(AppIcons.zap, "Zap", BitOSTheme.zap, onZap)
                cardAction(isBookmarked ? AppIcons.bookmarkFill : AppIcons.bookmark, isBookmarked ? "Remove bookmark" : "Bookmark", isBookmarked ? BitOSTheme.bookmark : BitOSTheme.textSecondary, onBookmark)
            }
        }
        .padding(.horizontal, BitOSTheme.Spacing.screen)
        .padding(.vertical, BitOSTheme.Spacing.md)
        .sheet(item: Binding(
            get: { lightboxUrl.map { LightboxTarget(url: $0) } },
            set: { lightboxUrl = $0?.url }
        )) { target in
            MediaLightbox(url: target.url, onClose: { lightboxUrl = nil })
        }
    }

    private func cardAction(_ symbol: String, _ label: String, _ tint: Color, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            AppIcons.image(for: symbol)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(tint)
                .frame(width: 36, height: 36)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

// MARK: - Pages

private struct FeedPage: View {
    let note: FeedNote
    let profiles: [String: ProfileMetadata]
    let actions: LocalActions
    let pool: PlayerPool
    let following: Set<String>
    let bookmarks: Set<String>
    /// APP-005: NIP-27 token JSON computed once at the list level.
    var richJson: String = "[]"
    let onLike: () -> Void
    let onBookmark: () -> Void
    let onComment: () -> Void
    let onRepost: () -> Void
    let onFollow: () -> Void
    let onZap: () -> Void
    let onAuthor: () -> Void
    let onMore: (CGPoint) -> Void

    var body: some View {
        if let video = note.video {
            VideoNotePage(note: note, video: video, profile: profiles[note.pubkey],
                          actions: actions, pool: pool, isFollowing: following.contains(note.pubkey),
                          isBookmarked: bookmarks.contains(note.id) || actions.bookmarked.contains(note.id),
                          onLike: onLike, onBookmark: onBookmark,
                          onComment: onComment, onRepost: onRepost, onFollow: onFollow, onZap: onZap, onAuthor: onAuthor, onMore: onMore)
        } else {
            TextNotePage(note: note, profile: profiles[note.pubkey],
                         actions: actions,
                         isBookmarked: bookmarks.contains(note.id) || actions.bookmarked.contains(note.id),
                         richJson: richJson,
                         onLike: onLike, onBookmark: onBookmark,
                         onComment: onComment, onRepost: onRepost, onFollow: onFollow, onZap: onZap, onAuthor: onAuthor)
        }
    }
}

// MARK: - Video page

private struct VideoNotePage: View {
    let note: FeedNote
    let video: MediaMetadata
    let profile: ProfileMetadata?
    let actions: LocalActions
    let pool: PlayerPool
    let isFollowing: Bool
    let isBookmarked: Bool
    let onLike: () -> Void
    let onBookmark: () -> Void
    let onComment: () -> Void
    let onRepost: () -> Void
    let onFollow: () -> Void
    let onZap: () -> Void
    let onAuthor: () -> Void
    let onMore: (CGPoint) -> Void

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            PosterImage(url: video.posterUrl)
            PlayerSurface(player: pool.player(for: note.id))
            // Tap layer: pause/play the settled slot only.
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture { pool.togglePlay(noteId: note.id) }
                .accessibilityLabel("Pause or resume playback")
                .accessibilityAddTraits(.isButton)
            VStack {
                Spacer()
                caption
            }
            HStack {
                Spacer()
                VideoActionRail(
                    isLiked: actions.liked.contains(note.id),
                    isBookmarked: isBookmarked,
                    onLike: onLike,
                    onBookmark: onBookmark,
                    onComment: onComment,
                    onRepost: onRepost,
                    onFollow: onFollow,
                    onZap: onZap,
                    onAuthor: onAuthor,
                    onMore: onMore
                )
            }
        }
    }

    private var caption: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
            HStack(spacing: BitOSSpacingAvatar) {
                PubkeyAvatarView(pubkey: note.pubkey, size: 36)
                VStack(alignment: .leading, spacing: 1) {
                    Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(note.pubkey))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text(FeedFormat.timeAgo(createdAt: note.createdAt))
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.7))
                }
                Spacer().frame(width: BitOSTheme.Spacing.sm)
                Button {
                    onAuthor()
                } label: {
                    FollowChip(
                        isFollowing: isFollowing,
                        onToggle: { onFollow() }
                    )
                }
                .buttonStyle(.plain)
                .accessibilityLabel("View author profile")
            }
            if note.repostedBy != nil {
                HStack(spacing: 4) {
                    AppIcons.image(for: AppIcons.repost)
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.repost)
                    Text("Reposted")
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.repost)
                }
            }
            Text(note.content)
                .font(.subheadline)
                .foregroundStyle(.white)
                .lineLimit(3)
            if !note.hashtags.isEmpty {
                Text(note.hashtags.prefix(4).map { "#\($0)" }.joined(separator: " "))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(BitOSTheme.accent)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, BitOSTheme.Spacing.base)
        .padding(.vertical, BitOSTheme.Spacing.lg)
        .background(
            LinearGradient(
                colors: [.clear, .clear, .black.opacity(0.8)],
                startPoint: .top, endPoint: .bottom
            )
        )
    }

    private var BitOSSpacingAvatar: CGFloat { BitOSTheme.Spacing.sm }
}

/// Aspect-fill, no built-in controls: playback control is the tap layer.
private struct PlayerSurface: UIViewControllerRepresentable {
    let player: AVQueuePlayer?

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.showsPlaybackControls = false
        controller.videoGravity = .resizeAspectFill
        controller.view.backgroundColor = .clear
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        if controller.player !== player {
            controller.player = player
        }
    }
}

private struct VideoActionRail: View {
    let isLiked: Bool
    let isBookmarked: Bool
    let onLike: () -> Void
    let onBookmark: () -> Void
    let onComment: () -> Void
    let onRepost: () -> Void
    let onFollow: () -> Void
    let onZap: () -> Void
    let onAuthor: () -> Void
    let onMore: (CGPoint) -> Void

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.lg) {
            RailButton(
                symbol: AppIcons.comment,
                tint: .white,
                label: "Replies",
                action: onComment
            )
            RailButton(
                symbol: AppIcons.repost,
                tint: .white,
                label: "Repost",
                action: onRepost
            )
            RailButton(
                symbol: AppIcons.zap,
                tint: BitOSTheme.zap,
                label: "Zap",
                action: onZap
            )
            RailButton(
                symbol: isLiked ? AppIcons.heartFill : AppIcons.heart,
                tint: isLiked ? BitOSTheme.like : .white,
                label: isLiked ? "Unlike" : "Like",
                action: onLike
            )
            RailButton(
                symbol: isBookmarked ? AppIcons.bookmarkFill : AppIcons.bookmark,
                tint: isBookmarked ? BitOSTheme.bookmark : .white,
                label: isBookmarked ? "Remove bookmark" : "Bookmark",
                action: onBookmark
            )
            RailButton(
                symbol: AppIcons.share,
                tint: .white,
                label: "Share",
                action: {}
            )
            AppMenuAnchorButton(
                label: "More options",
                action: onMore
            )
        }
        .padding(.trailing, BitOSTheme.Spacing.base)
    }
}

private struct FollowChip: View {
    let isFollowing: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            Text(isFollowing ? "Following" : "Follow")
                .font(.caption.weight(.semibold))
                .foregroundStyle(isFollowing ? .white.opacity(0.7) : BitOSTheme.accent)
                .padding(.horizontal, BitOSTheme.Spacing.md)
                .padding(.vertical, 4)
                .background(Capsule().fill(isFollowing ? .clear : BitOSTheme.accentContainer))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isFollowing ? "Unfollow author" : "Follow author")
    }
}

private struct RailButton: View {
    let symbol: String
    let tint: Color
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            AppIcons.image(for: symbol)
                .font(.system(size: 22, weight: .medium))
                .foregroundStyle(tint)
                .padding(10)
                .background(Circle().fill(.black.opacity(0.2)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

// MARK: - Text page

private struct TextNotePage: View {
    let note: FeedNote
    let profile: ProfileMetadata?
    let actions: LocalActions
    let isBookmarked: Bool
    var richJson: String = "[]"
    let onLike: () -> Void
    let onBookmark: () -> Void
    let onComment: () -> Void
    let onRepost: () -> Void
    let onFollow: () -> Void
    let onZap: () -> Void
    let onAuthor: () -> Void
    @State private var revealed = false
    @State private var lightboxUrl: String?

    var body: some View {
        ZStack {
            BitOSTheme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.base) {
                HStack(spacing: BitOSTheme.Spacing.avatarGap) {
                    PubkeyAvatarView(pubkey: note.pubkey)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(note.pubkey))
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(BitOSTheme.textPrimary)
                            .lineLimit(1)
                        Text(FeedFormat.timeAgo(createdAt: note.createdAt))
                            .font(.caption2)
                            .foregroundStyle(BitOSTheme.textTertiary)
                    }
                }
                if note.repostedBy != nil {
                    HStack(spacing: 4) {
                        AppIcons.image(for: AppIcons.repost)
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.repost)
                        Text("Reposted")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.repost)
                    }
                }
                Spacer().frame(height: BitOSTheme.Spacing.xs)
                if note.contentWarning && !revealed {
                    // APP-005: NIP-36 cover with per-session reveal.
                    SensitiveCover { revealed = true }
                } else {
                    // APP-005: NIP-27 rich body (entities, links, hashtags)
                    // + image grid with lightbox.
                    RichTextView(json: richJson, onOpenProfile: { _ in onAuthor() }, onOpenHashtag: nil)
                    MediaGrid(urls: note.mediaUrls) { lightboxUrl = $0 }
                }
                Spacer()
            }
            .padding(BitOSTheme.Spacing.screen)
            .padding(.top, BitOSTheme.Spacing.xl)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)

            HStack {
                Spacer()
                VStack(spacing: BitOSTheme.Spacing.lg) {
                    RailButton(
                        symbol: AppIcons.comment,
                        tint: BitOSTheme.textSecondary,
                        label: "Replies",
                        action: onComment
                    )
                    RailButton(
                        symbol: AppIcons.repost,
                        tint: BitOSTheme.textSecondary,
                        label: "Repost",
                        action: onRepost
                    )
                    RailButton(
                        symbol: AppIcons.zap,
                        tint: BitOSTheme.zap,
                        label: "Zap",
                        action: onZap
                    )
                    RailButton(
                        symbol: actions.liked.contains(note.id) ? "heart.fill" : "heart",
                        tint: actions.liked.contains(note.id) ? BitOSTheme.like : BitOSTheme.textSecondary,
                        label: actions.liked.contains(note.id) ? "Unlike" : "Like",
                        action: onLike
                    )
                    RailButton(
                        symbol: isBookmarked ? AppIcons.bookmarkFill : AppIcons.bookmark,
                        tint: isBookmarked ? BitOSTheme.bookmark : BitOSTheme.textSecondary,
                        label: isBookmarked ? "Remove bookmark" : "Bookmark",
                        action: onBookmark
                    )
                }
                .padding(.trailing, BitOSTheme.Spacing.base)
                .padding(.bottom, BitOSTheme.Spacing.xl)
            }
            .frame(maxHeight: .infinity, alignment: .bottom)
        }
        .sheet(item: Binding(
            get: { lightboxUrl.map { LightboxTarget(url: $0) } },
            set: { lightboxUrl = $0?.url }
        )) { target in
            MediaLightbox(url: target.url, onClose: { lightboxUrl = nil })
        }
    }
}

private struct LightboxTarget: Identifiable {
    let url: String
    var id: String { url }
}

// MARK: - Poster loading (minimal until the media pipeline lands)

private struct PosterImage: View {
    let url: String?
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { geo in
            Group {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: geo.size.width, height: geo.size.height)
                        .clipped()
                } else {
                    LinearGradient(
                        colors: [BitOSTheme.surface, BitOSTheme.surfaceElevated],
                        startPoint: .topLeading, endPoint: .bottomTrailing
                    )
                }
            }
        }
        .task(id: url) {
            image = nil
            guard let url, let imageURL = URL(string: url) else { return }
            let loaded: UIImage? = await withCheckedContinuation { continuation in
                URLSession.shared.dataTask(with: imageURL) { data, _, _ in
                    continuation.resume(returning: data.flatMap(UIImage.init(data:)))
                }.resume()
            }
            if !Task.isCancelled {
                image = loaded
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Header / states (unchanged behavior)

private struct TimelineTab: View {
    let title: LocalizedStringKey
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(isSelected ? .semibold : .medium))
                .foregroundStyle(isSelected ? BitOSTheme.accent : BitOSTheme.textSecondary)
                .padding(.horizontal, BitOSTheme.Spacing.md)
                .padding(.vertical, 6)
                .background(Capsule().fill(isSelected ? BitOSTheme.accentContainer : .clear))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityHint("Switches the feed timeline")
    }
}

private struct RelayHealthPill: View {
    let health: RelayHealth

    private var tint: Color {
        if health.isLive { return BitOSTheme.success }
        return health.total == 0 ? BitOSTheme.textTertiary : BitOSTheme.error
    }

    var body: some View {
        HStack(spacing: 4) {
            Circle().fill(tint).frame(width: 6, height: 6)
            Text("\(health.connected)/\(health.total)")
                .font(.caption2.weight(.medium))
                .foregroundStyle(tint)
        }
        .padding(.horizontal, BitOSTheme.Spacing.sm)
        .padding(.vertical, 4)
        .background(Capsule().fill(tint.opacity(0.12)))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Relay connections \(health.connected) of \(health.total)")
    }
}

private struct FeedLoadingView: View {
    var body: some View {
        ProgressView()
            .tint(BitOSTheme.accent)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct FeedEmptyView: View {
    let health: RelayHealth

    var body: some View {
        ContentPlaceholderView(
            title: "No notes yet",
            message: health.isLive
                ? "Connected relays have not returned verified notes yet."
                : "Relays are connecting. The feed fills once a connection succeeds.",
            symbol: "bolt.horizontal"
        )
    }
}

private struct FollowingPlaceholderView: View {
    var body: some View {
        ContentPlaceholderView(
            title: "Following needs an identity",
            message: "Create, import or connect a Nostr identity to build a following timeline.",
            symbol: "person.badge.key"
        )
    }
}

struct ContentPlaceholderView: View {
    let title: LocalizedStringKey
    let message: LocalizedStringKey
    let symbol: String

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.sm) {
            Image(systemName: symbol)
                .font(.system(size: 36, weight: .semibold))
                .foregroundStyle(BitOSTheme.accent)
                .accessibilityHidden(true)
            Text(title).font(.headline)
            Text(message)
                .font(.footnote)
                .foregroundStyle(BitOSTheme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(BitOSTheme.Spacing.xxl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct AuthorTarget: Identifiable { let id: String }
