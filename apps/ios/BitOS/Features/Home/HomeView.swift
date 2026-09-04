import AVKit
import BusinessCore
import Network
import SwiftUI
import UIKit

/// APP-009 ref-open states (mockup app-10 "Loading / not found"): copy and
/// classification come from the shared `ThreadOpen`/`ThreadOpenCopy` rules
/// through the bridge; timing stays native.
enum RefOpenPlate: Identifiable {
    case loading
    case invalid
    case notFound(raw: String, hints: [String])

    var id: String {
        switch self {
        case .loading: return "loading"
        case .invalid: return "invalid"
        case .notFound(let raw, _): return "not-found-\(raw)"
        }
    }

    var hints: [String] {
        if case .notFound(_, let hints) = self { return hints }
        return []
    }
}

/// Shared `ThreadOpenCopy` accessors (single source, no literal drift).
enum ThreadCopy {
    static var loadingTitle: String { BusinessCoreBridge().threadOpenLoadingTitle() as String }
    static var invalidTitle: String { BusinessCoreBridge().threadOpenInvalidTitle() as String }
    static var invalidBody: String { BusinessCoreBridge().threadOpenInvalidBody() as String }
    static var notFoundTitle: String { BusinessCoreBridge().threadOpenNotFoundTitle() as String }
    static var notFoundBody: String { BusinessCoreBridge().threadOpenNotFoundBody() as String }
    static var retry: String { BusinessCoreBridge().threadOpenRetryLabel() as String }
    static var addRelay: String { BusinessCoreBridge().threadOpenAddRelayLabel() as String }
}

/// Home surface (FED-001): full-screen vertical paging feed; only the
/// settled page plays. Text notes render as full-screen cards, video notes
/// (kind 22 or legacy mp4 links) render through the three-slot player pool.
struct HomeView: View {
    @State private var store: FeedStore
    @State private var topId: String?
    @State private var pool = PlayerPool()
    /// Unmetered (wifi) connectivity for the autoplay policy.
    @State private var wifiUnmetered = true
    @State private var pathMonitor: NWPathMonitor?
    @State private var showComposer = false
    @State private var commentTarget: FeedNote?
    @State private var zapTarget: FeedNote?
    @State private var showMediaImport = false
    /** Create hub (record/import/studio) from the app-bar camera. */
    @State private var showCreateHub = false
    @State private var authorTarget: String?
    @State private var menu: AppMenuPresentation?
    /** Legacy-parity ⋯ overflow → bottom sheet (filter stays a popover). */
    @State private var moreSheetTarget: FeedNote?
    /** Card ⋯ raw-event viewer (NIP-01 canonical object). */
    @State private var rawEventText: RawEvent?
    /** External-link confirm sheet (never opens the browser unattended). */
    @State private var externalLink: String?
    /** In-place note-ref open (note1/nevent1/naddr1/hex → thread sheet). */
    @State private var refOpenTarget: String?
    /** APP-009 states plate (mockup app-10): Loading / Invalid / NotFound
     *  with the nevent TLV hints for Retry-with-hints / Add relay. */
    @State private var refOpenPlate: RefOpenPlate?
    @State private var shareText: String?
    /// List-surface scroll anchors (hold/reveal + re-tap-to-top).
    @State private var listAtTop = true
    @State private var listScrollToTopTick = 0
    var videoOnly: Bool = false
    var onOpenDiscover: () -> Void = {}
    var onOpenProfile: () -> Void = {}
    var onOpenHub: () -> Void = {}
    /** APP-003/APP-004: bumped when the user re-taps the ACTIVE shell tab
     * (Home/Bitz) — scrolls to top, or refreshes when already at top. */
    var retapTick: Int = 0
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    @Environment(SettingsStore.self) private var settings
    /// Like-tick drives sensory feedback (gated by the haptics preference).
    @State private var likeTick = 0

    /// APP-018: persisted autoplay policy → can the visible video start?
    private func autoplayAllowed() -> Bool {
        switch settings.state.mediaAutoPlay {
        case .always: true
        case .never: false
        case .wifi: wifiUnmetered
        }
    }

    private func startPathMonitor() {
        guard pathMonitor == nil else { return }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { path in
            Task { @MainActor in wifiUnmetered = !path.isExpensive }
        }
        monitor.start(queue: DispatchQueue(label: "bitos.autoplay-path"))
        pathMonitor = monitor
    }

    private func stopPathMonitor() {
        pathMonitor?.cancel()
        pathMonitor = nil
    }

    init(store: FeedStore, videoOnly: Bool = false, onOpenDiscover: @escaping () -> Void = {}, onOpenProfile: @escaping () -> Void = {}, onOpenHub: @escaping () -> Void = {}, retapTick: Int = 0) {
        _store = State(initialValue: store)
        self.videoOnly = videoOnly
        self.onOpenDiscover = onOpenDiscover
        self.onOpenProfile = onOpenProfile
        self.onOpenHub = onOpenHub
        self.retapTick = retapTick
    }

    /// Per-surface state (fixes the video-only-tab blank-ready edge logged
    /// in the tracker): emptiness is judged on the FILTERED tab notes.
    private var surfaceState: FeedStore.FeedState {
        if !notes.isEmpty { return .ready }
        return store.hasLoadedAnyEvent ? .empty : .loading
    }

    private var notes: [FeedNote] {
        // Shell split (user decision): Home tab = text notes; Bitz tab =
        // video reels. These projections are built in FeedStore's coalesced
        // publisher, not during a SwiftUI body evaluation.
        videoOnly ? store.videoNotes : store.textNotes
    }

    /// Settled page ± 1 note ids — the only indices that matter for pool
    /// reconciliation. Changing when new pages load at the tail is benign.
    private var neighborIds: [String?] {
        guard let topId, let index = notes.firstIndex(where: { $0.id == topId }) else { return [] }
        return [
            index > 0 ? notes[index - 1].id : nil,
            topId,
            index < notes.count - 1 ? notes[index + 1].id : nil,
        ]
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

    /// ⋯ overflow → bottom sheet (legacy parity: web item set in the
    /// Flutter sheet chrome; popover retained for the filter trigger).
    private func presentMoreMenu(for note: FeedNote, at anchor: CGPoint) {
        moreSheetTarget = note
    }

    private func moreMenuEntries(_ note: FeedNote) -> [AppMenuEntry] {
        let authorName = store.profiles[note.pubkey]?.bestDisplayName
        var entries: [AppMenuEntry] = [
            .item(AppMenuItem(id: "share", label: "Share", systemImage: AppIcons.share)),
            .item(AppMenuItem(
                id: "save",
                label: store.bookmarkedIds.contains(note.id) ? "Unsave note" : "Save note",
                systemImage: AppIcons.bookmark
            )),
            .item(AppMenuItem(id: "copy-id", label: "Copy note ID", systemImage: AppIcons.copy)),
            .item(AppMenuItem(id: "copy-text", label: "Copy note text", systemImage: AppIcons.pen)),
            .item(AppMenuItem(id: "copy-npub", label: "Copy author npub", systemImage: AppIcons.user)),
            .item(AppMenuItem(id: "raw-event", label: "View raw event JSON", systemImage: AppIcons.appsGrid)),
        ]
        // Web PostCard menu parity: attachment actions when media rides along.
        if !note.mediaUrls.isEmpty {
            entries.append(.item(AppMenuItem(id: "open-attachment", label: "Open attachment", systemImage: AppIcons.globe)))
            entries.append(.item(AppMenuItem(id: "copy-attachment", label: "Copy attachment URL", systemImage: AppIcons.copy)))
        }
        entries.append(.divider)
        // Web interaction-profile parity: local ranking signals.
        let interaction = environment.interaction
        entries.append(.item(AppMenuItem(id: "not-interested", label: "Not interested", systemImage: AppIcons.close)))
        entries.append(.item(AppMenuItem(id: "hide-note", label: "Hide this note", systemImage: AppIcons.mute)))
        entries.append(.item(AppMenuItem(
            id: "show-less-from",
            label: (interaction.isAuthorDemoted(note.pubkey) ? "Show more from " : "Show less from ")
                + (authorName ?? "this author"),
            systemImage: AppIcons.user
        )))
        if let tag = note.hashtags.first {
            entries.append(.item(AppMenuItem(
                id: "show-less-about",
                label: (interaction.isTagDemoted(tag) ? "Show more about #" : "Show less about #") + tag,
                systemImage: AppIcons.close
            )))
        }
        entries.append(.divider)
        entries.append(.item(AppMenuItem(
            id: "mute",
            label: store.muted.contains(note.pubkey) ? "Unmute author" : "Mute author",
            systemImage: AppIcons.mute
        )))
        entries.append(.divider)
        entries.append(.item(AppMenuItem(id: "report-spam", label: "Report as spam", systemImage: AppIcons.reportSpam, isDestructive: true)))
        entries.append(.item(AppMenuItem(id: "report-illicit", label: "Report as illicit", systemImage: AppIcons.reportIllicit, isDestructive: true)))
        entries.append(.item(AppMenuItem(id: "report-harassment", label: "Report as harassment", systemImage: AppIcons.reportHarassment, isDestructive: true)))
        return entries
    }

    private func handleMoreSelect(_ note: FeedNote, _ id: String) {
        let bridge = BusinessCoreBridge()
        switch id {
        case "share":
            let npub = (bridge.npubEncode(pubkeyHex: note.pubkey) as String?) ?? note.pubkey
            shareText = (bridge.noteShareText(content: note.content, authorNpub: npub) as String)
        case "save":
            toggleBookmark(note)
        case "copy-id":
            UIPasteboard.general.string = note.id
        case "copy-text":
            UIPasteboard.general.string = note.content
        case "copy-npub":
            UIPasteboard.general.string = (bridge.npubEncode(pubkeyHex: note.pubkey) as String?) ?? note.pubkey
        case "raw-event":
            rawEventText = store.rawEventJson(forNoteId: note.id).map(RawEvent.init)
                ?? RawEvent(
                    value: "This event is no longer available in this device's bounded feed cache.",
                    isEventJson: false
                )
        case "open-attachment":
            // The external-link confirm gate owns the actual open.
            if let url = note.mediaUrls.first { externalLink = url }
        case "copy-attachment":
            if let url = note.mediaUrls.first { UIPasteboard.general.string = url }
        case "mute":
            toggleMute(note.pubkey)
        case "not-interested":
            // Web parity: hide the note AND demote its author + topics.
            environment.interaction.dismissNote(note.id)
            environment.interaction.demoteAuthor(note.pubkey)
            note.hashtags.forEach { environment.interaction.demoteTag($0) }
            store.republish()
        case "hide-note":
            environment.interaction.dismissNote(note.id)
            store.republish()
        case "show-less-from":
            environment.interaction.toggleDemotedAuthor(note.pubkey)
            store.republish()
        case "show-less-about":
            if let tag = note.hashtags.first {
                environment.interaction.toggleDemotedTag(tag)
                store.republish()
            }
        case "report-spam":
            report(note, reason: "spam")
        case "report-illicit":
            report(note, reason: "illicit")
        case "report-harassment":
            report(note, reason: "harassment")
        default: break
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
        if turningOn { likeTick += 1 } // sensory feedback trigger
        // Signed accounts publish a real kind-7 on like; an unlike deletes
        // my reaction event (kind-5, web `unlikeNote` parity).
        guard environment.identityStore.account != nil else { return }
        if turningOn {
            Task { await environment.notePublisher.publishReaction(targetEventId: note.id, targetPubkey: note.pubkey) }
        } else if let reactionId = store.myReactionEventIds[note.id] {
            Task { await environment.notePublisher.publishDeletion(targetEventIds: [reactionId]) }
        }
    }

    /// APP-009 states-plate title (shared copy through the bridge).
    private var refOpenPlateTitle: String {
        switch refOpenPlate {
        case .loading: return ThreadCopy.loadingTitle
        case .invalid: return ThreadCopy.invalidTitle
        case .notFound: return ThreadCopy.notFoundTitle
        case nil: return ""
        }
    }

    var body: some View {
        NavigationStack {
            feedSurface
        }
        .sensoryFeedback(.impact, trigger: likeTick) { _, _ in
            settings.state.hapticEnabled
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
    }

    /// Modifier chain split into named stages — one giant expression times
    /// out the Swift type-checker (same fix class as pagerPage/pagerPosition).
    private var feedSurface: some View {
        sheetHosted(
            storyViewerHosted(toolbarDecorated)
        )
    }

    private var toolbarDecorated: some View {
        lifecycleDecorated
            .toolbar { appBarItems }
    }

    @ToolbarContentBuilder
    private var appBarItems: some ToolbarContent {
        // APP-004 app bar (spec §3.4): wordmark leading, centered
        // content-filter trigger, search/apps-grid/import actions.
        ToolbarItem(placement: .topBarLeading) { wordmark }
        ToolbarItem(placement: .principal) { filterTrigger }
        ToolbarItem(placement: .topBarTrailing) { appBarActions }
    }

    private var wordmark: some View {
        Image("Wordmark")
            .resizable()
            .scaledToFit()
            .frame(height: 16)
            .accessibilityLabel("BitOS")
    }

    private var filterTrigger: some View {
        AppMenuAnchorButton(
            symbol: AppIcons.filter,
            tint: store.filterOrdinal == 0 ? BitOSTheme.textSecondary : BitOSTheme.accent,
            label: "Filter notes"
        ) { point in
            presentFilterMenu(at: point)
        }
    }

    private var appBarActions: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            Button(action: onOpenDiscover) {
                AppIcons.image(for: AppIcons.search)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .frame(width: 34, height: 34)
            }
            .accessibilityLabel("Search")
            Button(action: onOpenHub) {
                AppIcons.image(for: AppIcons.appsGrid)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .frame(width: 34, height: 34)
            }
            .accessibilityLabel("Open hub")
            // Create hub (Android app-bar camera parity): record/import and
            // the studio entry points behind one surface.
            Button {
                showCreateHub = true
            } label: {
                AppIcons.image(for: AppIcons.camera)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .frame(width: 34, height: 34)
            }
            .accessibilityLabel("Create")
            Button {
                showMediaImport = true
            } label: {
                AppIcons.image(for: AppIcons.photo)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .frame(width: 34, height: 34)
            }
            .accessibilityLabel("Import and publish a video")
        }
    }

    // APP-006: story viewer.
    @State private var storyViewerTick = false

    private var lifecycleDecorated: some View {
        content
            .background(BitOSTheme.background)
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                startPathMonitor()
                store.holdNewNotes(false)
            }
            // APP-004: arrivals hold while scrolled into the pager;
            // being at the top (or unset) auto-reveals.
            .onChange(of: topId) { _, id in
                let atTop = id == nil || id == store.notes.first?.id
                store.holdNewNotes(!atTop)
            }
            .onDisappear {
                stopPathMonitor()
                pool.releaseAll()
            }
            // Re-tapping Home returns to the head; arrivals merge there.
            .onChange(of: retapTick) { _, tick in
                guard tick > 0 else { return }
                handleRetap()
            }
    }

    private func handleRetap() {
        if videoOnly {
            if topId != notes.first?.id, let first = notes.first {
                topId = first.id
            } else {
                store.refresh()
            }
        } else {
            if listAtTop {
                store.refresh()
            } else {
                listScrollToTopTick += 1
            }
        }
    }

    private func storyViewerHosted(_ base: some View) -> some View {
        base.fullScreenCover(isPresented: Binding(
            get: { environment.storiesStore.viewerTarget != nil },
            set: { if !$0 { environment.storiesStore.openViewer(nil) } }
        )) {
            if let target = environment.storiesStore.viewerTarget {
                StoryViewerView(
                    author: target,
                    onSeen: { environment.storiesStore.markSeen($0) },
                    onClose: { environment.storiesStore.openViewer(nil) }
                )
                .preferredColorScheme(BitOSTheme.preferredScheme)
            }
        }
    }

    private func sheetHosted(_ base: some View) -> some View {
        base
            .sheet(item: $moreSheetTarget) { note in
                AppBottomSheetMenu(
                    title: "Post actions",
                    entries: moreMenuEntries(note)
                ) { id in
                    moreSheetTarget = nil
                    handleMoreSelect(note, id)
                }
                .presentationDetents([.medium])
            }
            .sheet(item: $rawEventText) { raw in
                RawEventSheet(text: raw.value, isEventJson: raw.isEventJson)
            }
            .sheet(isPresented: Binding(
                get: { shareText != nil },
                set: { if !$0 { shareText = nil } }
            )) {
                if let shareText {
                    ShareSheet(items: [shareText])
                }
            }
            // External-link confirm: the browser only opens on an explicit Open.
            .sheet(isPresented: Binding(
                get: { externalLink != nil },
                set: { if !$0 { externalLink = nil } }
            )) {
                if let externalLink {
                    ExternalLinkConfirmSheet(url: externalLink)
                }
            }
            // In-place note-ref open (mockup app-10 states): classify via
            // the shared rule (invalid never issues a REQ), poll until the
            // head arrives (3 s), then open the thread sheet or surface the
            // not-found plate with Retry / Add relay.
            .task(id: refOpenTarget) {
                guard let raw = refOpenTarget else { return }
                defer { refOpenTarget = nil }
                guard let ref = BusinessCoreBridge().eventRefParse(bech32: raw) else {
                    refOpenPlate = .invalid
                    return
                }
                refOpenPlate = .loading
                for _ in 0..<20 where !Task.isCancelled {
                    if let fetched = store.refNote(raw: raw) {
                        refOpenPlate = nil
                        commentTarget = fetched
                        return
                    }
                    try? await Task.sleep(nanoseconds: 150_000_000)
                }
                let hints = (ref["relays"] as? [String]) ?? []
                refOpenPlate = .notFound(raw: raw, hints: hints)
            }
            // APP-009 states plate (loading / invalid / not-found).
            .alert(
                refOpenPlateTitle,
                isPresented: Binding(
                    get: { refOpenPlate != nil },
                    set: { if !$0 { refOpenPlate = nil } }
                )
            ) {
                if case .notFound = refOpenPlate {
                    Button(ThreadCopy.retry) {
                        if case .notFound(let raw, _) = refOpenPlate {
                            refOpenPlate = nil
                            refOpenTarget = raw
                            store.openNoteReference(raw: raw)
                        }
                    }
                    let hints = (refOpenPlate.flatMap(\.hints) ?? [])
                    if !hints.isEmpty {
                        Button(ThreadCopy.addRelay) {
                            for hint in hints { environment.relayManager.add(rawUrl: hint) }
                            refOpenPlate = nil
                        }
                    }
                }
                Button("Close", role: .cancel) { refOpenPlate = nil }
            } message: {
                switch refOpenPlate {
                case .loading: Text("REQ ids / coordinate · readable relays")
                case .invalid: Text(ThreadCopy.invalidBody)
                case .notFound: Text(ThreadCopy.notFoundBody)
                case nil: Text("")
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
            // Create hub from the app-bar camera (BitzView presentation parity).
            .fullScreenCover(isPresented: $showCreateHub) {
                CreateView()
                    .environment(environment)
                    .environment(identity)
                    .preferredColorScheme(BitOSTheme.preferredScheme)
            }
            .appMenuHost($menu)
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
            .fullScreenCover(isPresented: $showComposer) {
                // APP-008: the composer is a full PAGE (legacy CreateView
                // parity) — shared ComposerRules through the bridge.
                ComposerScreen {
                    environment.notePublisher.dismiss()
                    showComposer = false
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        VStack(spacing: 0) {
            timelineTabs
            VStack(spacing: 0) {
                    if identity.account == nil {
                        GuestBanner(onGetStarted: onOpenProfile)
            // APP-006: stories bar (above the timeline content).
            if !videoOnly && !environment.storiesStore.authors.isEmpty {
                StoriesBarView(
                    authors: environment.storiesStore.authors,
                    seenIds: environment.storiesStore.seenIds,
                    onOpen: { environment.storiesStore.openViewer($0) }
                )
            }
                    }
                timelineContent
            }
        }
        // Prototype tabdock parity: creation moved to the shell's center ＋
        // (the Home FAB conflicted with the tab bar and is removed).
    }

    /// Timeline states are shared by both tabs; Following is honest about
    /// identity (needs an account; contacts resolve before notes show).
    @ViewBuilder
    private var timelineContent: some View {
        switch store.timeline {
        case .forYou:
            feedStates
        case .following:
            if store.accountPubkey == nil {
                FollowingPlaceholderView()
            } else if !store.followingResolved {
                FeedLoadingView()
            } else {
                feedStates
            }
        }
    }

    @ViewBuilder
    private var feedStates: some View {
        switch surfaceState {
        case .loading: FeedLoadingView()
        case .empty:
            FeedEmptyView(
                health: store.relayHealth,
                filterActive: store.filterOrdinal != 0 &&
                    (store.timeline == .following ? store.followingCount : store.forYouCount) > 0,
                onRetry: { store.retryNow() },
                onShowAll: { store.selectFilter(0) }
            )
        case .ready:
            if videoOnly { pager } else { notesList }
        }
    }

    // MARK: - APP-004: sticky mode tabs (underline)

    private var timelineTabs: some View {
        HStack(spacing: BitOSTheme.Spacing.lg) {
            timelineTab(title: "For you", symbol: AppIcons.sparkles, isSelected: store.timeline == .forYou) {
                store.selectTimeline(.forYou)
            }
            timelineTab(title: "Following", symbol: AppIcons.people, isSelected: store.timeline == .following) {
                store.selectTimeline(.following)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, BitOSTheme.Spacing.screen)
        .overlay(alignment: .bottom) {
            Divider().background(BitOSTheme.divider)
        }
    }

    private func timelineTab(title: LocalizedStringKey, symbol: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                HStack(spacing: 6) {
                    AppIcons.image(for: symbol)
                        .font(.system(size: 12, weight: .semibold))
                    Text(title)
                        .font(.system(size: 13, weight: isSelected ? .semibold : .medium))
                }
                .foregroundStyle(isSelected ? BitOSTheme.accent : BitOSTheme.textSecondary)
                Rectangle()
                    .fill(isSelected ? BitOSTheme.accent : .clear)
                    .frame(height: 2.5)
            }
            .contentShape(Rectangle())
            .padding(.vertical, 6)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityHint("Switches the feed timeline")
    }

    // MARK: - Vertical pager (iOS 17 scroll-target paging)


    /// Type-checker split: one card per function (§ pagerPage fix class).
    @ViewBuilder
    private func noteCardRow(note: FeedNote, index: Int) -> some View {
        FeedNoteCard(
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
                            onMore: { point in presentMoreMenu(for: note, at: point) },
                            onOpenNoteRef: { raw in
                                // In-place note-ref open (web parity).
                                refOpenTarget = raw
                                store.openNoteReference(raw: raw)
                            },
                            // Mention taps open the mentioned user's sheet,
                            // not the note author's.
                            onOpenMentionProfile: { authorTarget = $0 },
                            onOpenExternalLink: { externalLink = $0 },
                            // APP-008 poll voting.
                            pollTally: store.pollTallies[note.id],
                            canVotePoll: environment.identityStore.account != nil,
                            onLoadPollVotes: { store.loadPollVotes(targetEventId: note.id) },
                            onVotePoll: { optionIndex in
                                store.applyOptimisticPollVote(pollId: note.id, optionIndex: optionIndex)
                                Task { await environment.notePublisher.publishPollVote(targetEventId: note.id, optionIndex: optionIndex) }
                            }
                        )
                        // Legacy UI parity: hairline divider between cards.
                        .overlay(alignment: .bottom) {
                            if index < notes.count - 1 {
                                Rectangle()
                                    .fill(BitOSTheme.divider)
                                    .frame(height: 0.5)
                            }
                        }
                        .onAppear {
                            // Top visibility drives hold/reveal + re-tap
                            // refresh; near the end prefetches an older page.
                            if index == 0 {
                                listAtTop = true
                                store.holdNewNotes(false)
                            }
                            if index >= notes.count - store.paginationPrefetchThreshold,
                               !store.isLoadingOlder, !store.noMoreOlder { store.loadOlder() }
                        }
                        .onDisappear {
                            if index == 0 {
                                listAtTop = false
                                store.holdNewNotes(true)
                            }
                        }
    }

    /// Home tab: scrolling compact NoteCard list (legacy UX parity).
    private var notesList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(Array(notes.enumerated()), id: \.element.id) { index, note in
                        noteCardRow(note: note, index: index)
                    }
                    // Dedicated bottom sentinel. Unlike a card's onAppear,
                    // this remains a reliable pagination edge after short
                    // lists or live list mutations. FeedStore guards overlap
                    // and exhaustion before issuing any relay request.
                    if !store.noMoreOlder, let last = notes.last {
                        Color.clear
                            .frame(height: 1)
                            .id("older-trigger-\(last.id)")
                            .onAppear {
                                if !store.isLoadingOlder { store.loadOlder() }
                            }
                    }
                    // APP-004 pagination: footer spinner while an older
                    // page loads.
                    if store.isLoadingOlder {
                        HStack {
                            ProgressView().tint(BitOSTheme.accent)
                        }
                        .padding(BitOSTheme.Spacing.base)
                    } else if store.noMoreOlder && !notes.isEmpty {
                        // UX U7: the walk is exhausted for this lane — replace
                        // the silent dead-end with an explicit boundary.
                        Text("You're all caught up")
                            .font(.footnote)
                            .foregroundStyle(BitOSTheme.textTertiary)
                            .frame(maxWidth: .infinity)
                            .padding(BitOSTheme.Spacing.base)
                            .accessibilityLabel("End of timeline")
                    }
                }
            }
            .onChange(of: listScrollToTopTick) { _, _ in
                if let first = notes.first {
                    withAnimation(.easeInOut(duration: 0.25)) {
                        proxy.scrollTo(first.id, anchor: .top)
                    }
                }
            }
            .refreshable { store.refresh() }
        }
    }

    private var pager: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(Array(notes.enumerated()), id: \.element.id) { index, note in
                    pagerPage(note)
                        .containerRelativeFrame(.vertical)
                        .id(note.id)
                        .onAppear {
                            if index >= notes.count - store.paginationPrefetchThreshold,
                               !store.isLoadingOlder, !store.noMoreOlder { store.loadOlder() }
                        }
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
            pool.update(
                visibleId: newValue, notes: notes,
                autoplayAllowed: autoplayAllowed(),
                rate: Float(Double(settings.state.playbackRate.rawValue) ?? 1),
                videoQuality: settings.state.videoQuality.rawValue
            )
        }
        // Re-reconcile only when the settled page's neighbors change; live
        // arrivals appended to the tail do not affect the three active slots.
        .onChange(of: neighborIds) { _, _ in
            pool.update(
                visibleId: topId, notes: notes,
                autoplayAllowed: autoplayAllowed(),
                rate: Float(Double(settings.state.playbackRate.rawValue) ?? 1),
                videoQuality: settings.state.videoQuality.rawValue
            )
        }
        // UX U9: a video-quality change re-prepares the bounded slots at
        // the new rung immediately (pool rebuilds on preference change).
        .onChange(of: settings.state.videoQuality) { _, _ in
            pool.update(
                visibleId: topId, notes: notes,
                autoplayAllowed: autoplayAllowed(),
                rate: Float(Double(settings.state.playbackRate.rawValue) ?? 1),
                videoQuality: settings.state.videoQuality.rawValue
            )
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
            onMore: { point in presentMoreMenu(for: note, at: point) },
            onOpenExternalLink: { externalLink = $0 },
            onOpenMentionProfile: { authorTarget = $0 }
            )
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
    /// APP-005: external links get the confirm sheet.
    var onOpenExternalLink: (String) -> Void = { _ in }
    /// note1/nevent1/naddr1 tap → in-place thread open.
    var onOpenNoteRef: (String) -> Void = { _ in }
    /// Profile-mention tap → the mentioned user's profile (not the author).
    var onOpenMentionProfile: ((String) -> Void)? = nil

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
                         onComment: onComment, onRepost: onRepost, onFollow: onFollow, onZap: onZap, onAuthor: onAuthor,
                         onOpenExternalLink: onOpenExternalLink,
                         onOpenMentionProfile: onOpenMentionProfile)
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
                PubkeyAvatarView(pubkey: note.pubkey, size: 36, picture: profile?.picture, label: profile?.bestDisplayName, hasLightning: !(profile?.lud16?.isEmpty ?? true))
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 4) {
                        Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(note.pubkey))
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                        if !(profile?.nip05?.isEmpty ?? true) {
                            AppIcons.image(for: AppIcons.checkCircle)
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(BitOSTheme.accent)
                                .accessibilityLabel("NIP-05 identity claim")
                        }
                    }
                    RelativeTimeText(createdAt: note.createdAt)
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

/// Aspect-fit, no built-in controls: playback control is the tap layer.
/// Black page space is intentional so the creator's original frame is never
/// cropped by the feed.
struct PlayerSurface: UIViewControllerRepresentable {
    let player: AVQueuePlayer?

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.showsPlaybackControls = false
        controller.videoGravity = .resizeAspect
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
    @Environment(SettingsStore.self) private var settings
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
    /// APP-005: external links get the confirm sheet.
    var onOpenExternalLink: (String) -> Void = { _ in }
    /// Profile-mention tap → the mentioned user's profile (not the author).
    var onOpenMentionProfile: ((String) -> Void)? = nil
    @State private var revealed = false
    @State private var lightboxUrl: String?

    var body: some View {
        ZStack {
            BitOSTheme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.base) {
                HStack(spacing: BitOSTheme.Spacing.avatarGap) {
                    PubkeyAvatarView(pubkey: note.pubkey, picture: profile?.picture, label: profile?.bestDisplayName)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(note.pubkey))
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(BitOSTheme.textPrimary)
                            .lineLimit(1)
                        RelativeTimeText(createdAt: note.createdAt)
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
                if note.contentWarning && !revealed && settings.state.sensitiveMedia != .show {
                    // APP-005: NIP-36 cover with per-session reveal.
                    SensitiveCover { revealed = true }
                } else {
                    // APP-005: NIP-27 rich body (entities, links, hashtags)
                    // + image grid with lightbox. Bare media links render as
                    // tiles and disappear from the body; external links get
                    // the confirm sheet.
                    RichTextView(
                        json: richJson,
                        onOpenProfile: { onOpenMentionProfile?($0) },
                        onOpenHashtag: nil,
                        hiddenMediaUrls: Set(note.mediaUrls),
                        onOpenLink: { onOpenExternalLink($0) }
                    )
                    if !note.mediaUrls.isEmpty {
                        if settings.state.mediaPreview {
                            MediaGrid(urls: note.mediaUrls) { lightboxUrl = $0 }
                        } else {
                            Text("\(note.mediaUrls.count) attachment(s) \u{2014} previews off")
                                .font(.system(size: 12))
                                .foregroundStyle(BitOSTheme.textTertiary)
                        }
                    }
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
    @Environment(AppEnvironment.self) private var environment
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { geo in
            Group {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(width: geo.size.width, height: geo.size.height)
                } else {
                    Color.black
                }
            }
            .task(id: url) {
                // Decode at the ROW's rendered size (audit R8): a feed-card
                // poster needs its laid-out pixel bucket, not a full-screen
                // multi-megapixel decode. The previous poster stays visible
                // until the new one is ready (no flash-to-black on scroll).
                guard let url else {
                    image = nil
                    return
                }
                // Pre-layout geometry is zero: fall back to the viewport's
                // short edge rather than a full-screen decode.
                let rendered = max(geo.size.width, geo.size.height)
                let fallback = min(UIScreen.main.bounds.width, UIScreen.main.bounds.height)
                let maxPixels = max(rendered, fallback) * UIScreen.main.scale
                let loaded = await environment.posterImages.image(urlString: url, maxPixelSize: maxPixels)
                if !Task.isCancelled, url == self.url {
                    image = loaded
                }
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Header / states (unchanged behavior)

private struct FeedLoadingView: View {
    var body: some View {
        // UX U2 (§2.5 skeletons): matched skeleton rows instead of a bare
        // spinner — the shape of the incoming content, shimmer honoring
        // reduce-motion inside AppSkeleton.
        ScrollView {
            VStack(spacing: 0) {
                ForEach(0..<6, id: \.self) { _ in
                    AppSkeletonRow()
                }
            }
            .padding(.top, BitOSTheme.Spacing.base)
        }
        .accessibilityLabel("Loading feed")
    }
}

private struct FeedEmptyView: View {
    let health: RelayHealth
    /// True when the window has notes under the All filter but the active
    /// content filter matches none — the “Show all” CTA applies.
    let filterActive: Bool
    let onRetry: () -> Void
    let onShowAll: () -> Void

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.sm) {
            if !health.isLive && health.total > 0 {
                // APP-004: relay-error state — relays configured, none
                // connected; the pool keeps reconnecting, user can force it.
                Text("Can't reach relays").font(.headline)
                Text("No relay connection right now. We keep retrying automatically.")
                    .font(.footnote)
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .multilineTextAlignment(.center)
            } else if filterActive {
                // APP-004: filter-mismatch empty — notes exist under All.
                Text("No notes match this filter").font(.headline)
                Button("Show all notes", action: onShowAll)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(BitOSTheme.accent)
            } else {
                Text("No notes yet").font(.headline)
                Text(health.isLive
                     ? "Connected relays have not returned verified notes yet. Retrying every few seconds."
                     : "Relays are connecting. The feed fills once a connection succeeds.")
                    .font(.footnote)
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .multilineTextAlignment(.center)
            }
            // Manual retry resets the APP-004 auto-retry backoff (2 s).
            Button(action: onRetry) {
                HStack(spacing: 6) {
                    AppIcons.image(for: AppIcons.refresh)
                        .font(.system(size: 13, weight: .semibold))
                    Text("Retry now")
                        .font(.footnote.weight(.semibold))
                }
                .foregroundStyle(BitOSTheme.accent)
                .padding(.horizontal, BitOSTheme.Spacing.base)
                .padding(.vertical, 8)
                .background(Capsule().strokeBorder(BitOSTheme.accent.opacity(0.5)))
            }
            .accessibilityLabel("Retry loading notes")
        }
        .padding(BitOSTheme.Spacing.xxl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
