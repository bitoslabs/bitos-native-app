import AVFoundation
import BusinessCore
import Network
import SwiftUI
import UIKit

// MARK: - Shared-rule seam (APP-007)

/// Thin adapter over `BusinessCoreBridge` for the Bitz surface rules that
/// live in `space.bitos.core.feed.Bitz` (search merge policy, share copy,
/// duration labels, npub encoding). The bridge is stateless pure functions —
/// same instantiation pattern as `SettingsStore`.
@MainActor
struct BitzBridgeRules {
    private let bridge = BusinessCoreBridge()

    func shareText(content: String, authorNpub: String) -> String {
        bridge.noteShareText(content: content, authorNpub: authorNpub)
    }

    func formatDuration(_ seconds: Int64) -> String {
        bridge.formatDurationSeconds(seconds: seconds)
    }

    func npub(_ pubkeyHex: String) -> String {
        bridge.npubEncode(pubkeyHex: pubkeyHex) ?? pubkeyHex
    }

    /// APP-007 remix: advisory license gate (shared rule).
    func remixRequiresAsk(license: String?) -> Bool {
        bridge.remixRequiresAsk(license: license)
    }

    /// Compact rail-count labels (legacy `_formatCount` / `_compactBitSats`
    /// parity) through the shared rules.
    func formatCount(_ value: Int64) -> String {
        bridge.bitzFormatCount(value: value)
    }

    func formatSats(zapMillisats: Int64) -> String? {
        (bridge.bitzFormatSats(zapMillisats: zapMillisats) as String?)
    }

    /// Wire seed tags (remix marker + p attribution + human credit) as JSON.
    func remixSeedTagsJson(note: FeedNote, label: String) -> String {
        let relaysData = (try? JSONSerialization.data(withJSONObject: [String]())) ?? Data()
        let relaysJson = String(data: relaysData, encoding: .utf8) ?? "[]"
        let marker = bridge.remixTagsJson(eventId: note.id, pubkey: note.remixOfPubkey ?? note.pubkey, relaysJson: relaysJson)
        let attribution = bridge.remixAttributionTagJson(label: label)
        let markerTags = (try? JSONSerialization.jsonObject(with: Data(marker.utf8))) as? [[String]] ?? []
        let attributionTags = (try? JSONSerialization.jsonObject(with: Data(attribution.utf8))) as? [[String]] ?? []
        guard let data = try? JSONSerialization.data(withJSONObject: markerTags + attributionTags) else { return "[]" }
        return String(data: data, encoding: .utf8) ?? "[]"
    }

    /// Runs the shared match+merge policy and returns the ordered result ids.
    func mergedResultIds(query: String, local: [FeedNote], relay: [FeedNote], profiles: [String: ProfileMetadata]) -> [String] {
        let localJson = entriesJson(local, profiles: profiles)
        let relayJson = entriesJson(relay, profiles: profiles)
        guard let raw = bridge.bitzSearchResults(query: query, localJson: localJson, relayJson: relayJson),
              let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return array.compactMap { $0["id"] as? String }
    }

    private func entriesJson(_ notes: [FeedNote], profiles: [String: ProfileMetadata]) -> String {
        let entries: [[String: String]] = notes.map { note in
            [
                "id": note.id,
                "content": note.content,
                "authorName": profiles[note.pubkey]?.bestDisplayName ?? "",
            ]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: entries) else { return "[]" }
        return String(data: data, encoding: .utf8) ?? "[]"
    }
}

// MARK: - Bitz surface (spec §3.7)

/// Bitz short-video surface: glass top bar with the persisted
/// Explore · Following · For-you pills, the 3-column explore grid, the snap
/// player with inline controls (scrubber, ±10 s, mute memory, double-tap
/// like, sensitive gate) and the full-screen search overlay. Deterministic
/// rules come from the shared core; this view renders and dispatches.
struct BitzView: View {
    /// APP-003: bumped when the user re-taps the active Bitz tab — scroll
    /// to top; a re-tap while already at top refreshes.
    var retapTick: Int = 0

    @Environment(AppEnvironment.self) private var environment
    @Environment(SettingsStore.self) private var settings
    @Environment(IdentityStore.self) private var identity

    @State private var pool = PlayerPool()
    @State private var mode: SettingsBitzMode?
    @State private var topId: String?
    @State private var spliced: [FeedNote] = []
    @State private var loadMoreCount = 0
    @State private var revealedIds: Set<String> = []
    @State private var wifiUnmetered = false
    @State private var positionMs: Int64 = 0
    @State private var durationMs: Int64 = 0
    @State private var showSearch = false
    @State private var showComposer = false
    /** T4 capture entry: the Create hub (record Bitz / import media). */
    @State private var showCreateHub = false
    @State private var commentTarget: FeedNote?
    /** APP-007 Chain: the note whose ancestry the sheet is showing. */
    @State private var chainTarget: FeedNote?
    @State private var zapTarget: FeedNote?
    @State private var authorTarget: String?
    @State private var menu: AppMenuPresentation?
    @State private var pendingJumpId: String?
    @State private var pathMonitor: NWPathMonitor?
    /** APP-007 remix: seeded composer tags + the advisory-ask target. */
    @State private var composerSeedTagsJson = "[]"
    @State private var remixAskTarget: FeedNote?
    @State private var shareText: String?
    private let rules = BitzBridgeRules()

    private var videos: [FeedNote] {
        environment.feedStore.notes.filter { $0.video != nil }
    }

    private var playerNotes: [FeedNote] {
        let windowIds = Set(videos.map(\.id))
        return spliced.filter { !windowIds.contains($0.id) } + videos
    }

    private var rootContent: some View {
        Group {
            if let mode {
                surface(mode)
            } else {
                BitzLoadingView()
            }
        }
    }

    /// Stage 2 chain (type-checker split): apply over the stage-1 view.
    private func stage2<V: View>(_ base: V) -> some View {
        base
        .onChange(of: topId) { _, id in
            reconcilePool(visibleId: id)
            environment.feedStore.holdNewNotes(id != nil && id != playerNotes.first?.id)
        }
        .onChange(of: playerNotes.count) { _, _ in
            reconcilePool(visibleId: topId)
        }
        .onChange(of: settings.state.videoMuted) { _, _ in reconcilePool(visibleId: topId) }
        .onChange(of: retapTick) { _, tick in
            guard tick > 0 else { return }
            if mode == .explore {
                refreshWindow()
            } else if topId != playerNotes.first?.id, let first = playerNotes.first {
                topId = first.id
            } else {
                refreshWindow()
            }
        }
    }

    /// Stage 2b (task loops; type-checker split).
    private func stage2b<V: View>(_ base: V) -> some View {
        base
        .task(id: topId) {
            // Position polling feeds the settled page's scrubber.
            guard let id = topId else { return }
            while !Task.isCancelled {
                positionMs = pool.positionMs(noteId: id)
                durationMs = pool.durationMs(noteId: id)
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
        }
        .task(id: pendingJumpId) {
            guard let id = pendingJumpId else { return }
            // Splices land on the next store tick; retry briefly.
            for _ in 0..<20 where !Task.isCancelled {
                if playerNotes.firstIndex(where: { $0.id == id }) != nil {
                    topId = id
                    pendingJumpId = nil
                    return
                }
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
        }
        .sheet(item: Binding(
            get: { authorTarget.map { BitzAuthorTarget(id: $0) } },
            set: { authorTarget = $0?.id }
        )) { target in
            AuthorProfileSheet(authorPubkey: target.id, onClose: { authorTarget = nil })
                .environment(identity)
                .presentationDetents([.medium, .large])
        }
        .appMenuHost($menu)
        .sheet(item: $chainTarget) { target in
            BitzChainSheet(
                store: environment.feedStore,
                rootId: target.id,
                onOpenAncestor: { id in
                    if let ancestor = environment.feedStore.remixAncestorNote(id: id) {
                        chainTarget = nil
                        commentTarget = ancestor
                    }
                },
                onClose: { chainTarget = nil }
            )
            .presentationDetents([.medium, .large])
        }
        .sheet(item: $zapTarget) { target in
            ZapSheet(
                note: target,
                profiles: environment.feedStore.profiles,
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
                onClose: { zapTarget = nil }
            )
            .environment(identity)
            .presentationDetents([.medium])
        }
        .sheet(item: $commentTarget) { target in
            CommentSheet(
                note: target,
                store: environment.feedStore,
                publisher: environment.notePublisher,
                onClose: { commentTarget = nil }
            )
            .environment(identity)
            .presentationDetents([.medium, .large])
        }
        .fullScreenCover(isPresented: $showComposer) {
            // APP-008: the composer is a full PAGE (legacy CreateView parity);
            // APP-007 remix opens it seeded with attribution tags.
            ComposerScreen(
                onClose: {
                    environment.notePublisher.dismiss()
                    composerSeedTagsJson = "[]"
                    showComposer = false
                },
                baseTagsJson: composerSeedTagsJson
            )
        }
        .fullScreenCover(isPresented: $showSearch) {
            BitzSearchOverlay(onOpenNote: openInPlayer, onDismiss: { showSearch = false })
                .environment(environment)
                .environment(settings)
                .preferredColorScheme(.dark)
        }
        // T4: record entry → the Create hub (camera/import capture flow).
        .fullScreenCover(isPresented: $showCreateHub) {
            CreateView()
                .preferredColorScheme(.dark)
        }
    }

    /// Stage 3 chain (sheets/overlays; type-checker split).
    private func stage3<V: View>(_ base: V) -> some View {
        base
        // Web-parity share overflow: system sheet with the shared copy.
        .sheet(isPresented: Binding(
            get: { shareText != nil },
            set: { if !$0 { shareText = nil } }
        )) {
            if let shareText {
                ShareSheet(items: [shareText])
            }
        }
        // Remix advisory (bitz/all-reserved · bitz/source-permission).
        .confirmationDialog(
            "Remix anyway?",
            isPresented: Binding(
                get: { remixAskTarget != nil },
                set: { if !$0 { remixAskTarget = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Remix") {
                if let target = remixAskTarget {
                    composerSeedTagsJson = remixSeedTags(target)
                    showComposer = true
                }
                remixAskTarget = nil
            }
            Button("Cancel", role: .cancel) { remixAskTarget = nil }
        } message: {
            Text("This creator marked this bitz \"\(remixAskTarget?.license ?? "")\".\n\nCredit is added automatically when you publish.")
        }
    }

    var body: some View {
        // Split for the type-checker (§ pagerPage/pagerPosition fix class).
        stage3(stage2b(stage2(decoratedRoot)))
    }

    /// Lifecycle decorations stage 1 (split for the type-checker).
    private var decoratedRoot: some View {
        rootContent
        .background(BitOSTheme.background)
        .preferredColorScheme(.dark)
        .onAppear {
            if mode == nil { mode = settings.state.bitzMode }
            startPathMonitor()
        }
        .onDisappear {
            stopPathMonitor()
            pool.releaseAll()
        }
        .task(id: mode) {
            // The pills drive the same shared window Home uses.
            guard let mode else { return }
            switch mode {
            case .forYou: environment.feedStore.selectTimeline(.forYou)
            case .following: environment.feedStore.selectTimeline(.following)
            case .explore: break
            }
        }
        .onChange(of: settings.state.videoMuted) { _, muted in
            pool.setMuted(muted)
        }
    }

    /// APP-007 remix: advisory license gate → composer seeded with the
    /// shared wire tags (remix marker + p attribution + human credit).
    private func handleRemix(_ note: FeedNote) {
        if rules.remixRequiresAsk(license: note.license) {
            remixAskTarget = note
        } else {
            composerSeedTagsJson = remixSeedTags(note)
            showComposer = true
        }
    }

    private func remixSeedTags(_ note: FeedNote) -> String {
        let label = environment.feedStore.profiles[note.pubkey]?.bestDisplayName
            ?? FeedFormat.shortPubkey(note.pubkey)
        return rules.remixSeedTagsJson(note: note, label: label)
    }

    private struct BitzAuthorTarget: Identifiable {
        let id: String
    }

    // MARK: Surface

    @ViewBuilder
    private func surface(_ mode: SettingsBitzMode) -> some View {
        ZStack(alignment: .top) {
            Group {
                switch mode {
                case .explore: exploreGrid
                default: playerSurface
                }
            }
            BitzTopBar(
                mode: mode,
                onSelectMode: selectMode,
                onSearch: { showSearch = true },
                onRecord: { showCreateHub = true }
            )
        }
        .overlay(alignment: .bottomTrailing) {
            Button {
                showComposer = true
            } label: {
                Label("New note", image: "SolarPenLinear")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(BitOSTheme.accent, in: Capsule())
                    .foregroundStyle(Color(red: 0.04, green: 0.04, blue: 0.06))
            }
            .padding(16)
            .accessibilityLabel("New note")
        }
    }

    private var playerSurface: some View {
        Group {
            if playerNotes.isEmpty && !environment.feedStore.isLoading {
                playerEmptyState
            } else {
                pager
            }
        }
    }

    /// Legacy parity: Following-empty gets the Explore CTA + Refresh; the
    /// generic empty gets a filled Refresh.
    @ViewBuilder
    private var playerEmptyState: some View {
        VStack(spacing: BitOSTheme.Spacing.sm) {
            AppIcons.image(for: AppIcons.bitz)
                .font(.system(size: 44))
                .foregroundStyle(BitOSTheme.textTertiary)
            if mode == .following {
                if environment.feedStore.accountPubkey == nil {
                    Text("Following needs an identity")
                        .font(.headline)
                        .foregroundStyle(.white)
                    Text("Create, import or connect a Nostr identity to build a following timeline.")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .multilineTextAlignment(.center)
                } else {
                    Text("Nothing from your follows yet")
                        .font(.headline)
                        .foregroundStyle(.white)
                    Text("Follow more creators and their short videos will land here.")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .multilineTextAlignment(.center)
                    Button {
                        selectMode(.explore)
                    } label: {
                        Text("Explore Bitz")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color(red: 0.04, green: 0.04, blue: 0.06))
                            .padding(.horizontal, 18)
                            .padding(.vertical, 9)
                            .background(BitOSTheme.accent, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    Button {
                        refreshWindow()
                    } label: {
                        Text("Refresh Bitz")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(BitOSTheme.accent)
                    }
                    .buttonStyle(.plain)
                }
            } else {
                Text("No Bitz found")
                    .font(.headline)
                    .foregroundStyle(.white)
                Text(
                    environment.feedStore.relayHealth.isLive
                        ? "Connected relays have not returned verified videos yet. Retrying every few seconds."
                        : "Relays are connecting. Bitz fills once a connection succeeds."
                )
                .font(.caption)
                .foregroundStyle(BitOSTheme.textSecondary)
                .multilineTextAlignment(.center)
                Button {
                    refreshWindow()
                } label: {
                    Text("Refresh Bitz")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color(red: 0.04, green: 0.04, blue: 0.06))
                        .padding(.horizontal, 18)
                        .padding(.vertical, 9)
                        .background(BitOSTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(BitOSTheme.Spacing.xxl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var pager: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(Array(playerNotes.enumerated()), id: \.element.id) { index, note in
                    BitzVideoPage(
                        note: note,
                        profile: environment.feedStore.profiles[note.pubkey],
                        actions: environment.feedStore.localActions,
                        pool: pool,
                        isSettled: topId == note.id,
                        isFollowing: environment.feedStore.following.contains(note.pubkey),
                        isBookmarked: environment.feedStore.bookmarkedIds.contains(note.id)
                            || environment.feedStore.localActions.bookmarked.contains(note.id),
                        muted: settings.state.videoMuted,
                        sensitiveShown: settings.state.sensitiveMedia == .show,
                        revealed: revealedIds.contains(note.id),
                        positionMs: note.id == topId ? positionMs : 0,
                        durationMs: note.id == topId ? durationMs : 0,
                        onToggleMute: { settings.setVideoMuted(!settings.state.videoMuted) },
                        onLike: { like(note) },
                        onBookmark: { toggleBookmark(note) },
                        onComment: { commentTarget = note },
                        onRepost: { repost(note) },
                        onFollow: { toggleFollow(note.pubkey) },
                        onZap: {
                            environment.feedStore.loadZaps(targetEventId: note.id)
                            zapTarget = note
                        },
                        onRemix: { handleRemix(note) },
                        onChain: {
                            chainTarget = note
                            environment.feedStore.loadRemixChain(note: note)
                        },
                        onAuthor: { authorTarget = note.pubkey },
                        onMore: { point in presentMoreMenu(for: note, at: point) },
                        railCounts: railCounts(for: note)
                    )
                    .containerRelativeFrame(.vertical)
                    .id(note.id)
                    .onAppear {
                        topId = topId ?? note.id
                        // APP-004 pagination: settle near the end → older page.
                        if index >= playerNotes.count - 3 { environment.feedStore.loadOlder() }
                    }
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: Binding(get: { topId }, set: { topId = $0 }))
        .scrollIndicators(.hidden)
        .ignoresSafeArea(edges: .bottom)
        .refreshable { refreshWindow() }
    }

    private var exploreGrid: some View {
        let tiles = videos.prefix(BitzExploreVisible.tiles(loadMoreCount))
        let columns = [GridItem(.flexible(), spacing: 2), GridItem(.flexible(), spacing: 2), GridItem(.flexible(), spacing: 2)]
        return ScrollView {
            if environment.feedStore.isLoading && videos.isEmpty {
                LazyVGrid(columns: columns, spacing: 2) {
                    ForEach(0..<12, id: \.self) { _ in SkeletonTile() }
                }
            } else {
                LazyVGrid(columns: columns, spacing: 2) {
                    ForEach(Array(tiles), id: \.id) { note in
                        BitzTile(
                            note: note,
                            profile: environment.feedStore.profiles[note.pubkey],
                            zapCount: environment.feedStore.zapCounts[note.id] ?? 0,
                            likeCount: environment.feedStore.tallies[note.id]?.reactions ?? 0,
                            durationLabel: note.video?.durationSeconds.map { rules.formatDuration($0) },
                            sensitiveShown: settings.state.sensitiveMedia == .show,
                            revealed: revealedIds.contains(note.id),
                            onReveal: { revealedIds.insert(note.id) },
                            onOpen: { openInPlayer(note) }
                        )
                        .onAppear {
                            // Shared bounds: 24 initial + 18/load-more, then
                            // older relay pages when the window runs out.
                            if note.id == tiles.last?.id {
                                if videos.count > BitzExploreVisible.tiles(loadMoreCount) {
                                    loadMoreCount += 1
                                } else {
                                    environment.feedStore.loadOlder()
                                }
                            }
                        }
                    }
                }
                .padding(.top, 48)
                .padding(.bottom, 88)
            }
        }
    }

    // MARK: Actions

    private func reconcilePool(visibleId: String?) {
        pool.update(
            visibleId: visibleId,
            notes: playerNotes,
            autoplayAllowed: autoplayAllowed(),
            rate: Float(Double(settings.state.playbackRate.rawValue) ?? 1),
            muted: settings.state.videoMuted
        )
    }

    private func autoplayAllowed() -> Bool {
        switch settings.state.mediaAutoPlay {
        case .always: true
        case .never: false
        case .wifi: wifiUnmetered
        }
    }

    private func refreshWindow() {
        spliced = []
        loadMoreCount = 0
        environment.feedStore.refresh()
    }

    private func selectMode(_ next: SettingsBitzMode) {
        guard let current = mode else { return }
        if current == next {
            // Re-tap the active pill: back to top; at top, refresh.
            if next == .explore {
                refreshWindow()
            } else if topId != playerNotes.first?.id, let first = playerNotes.first {
                topId = first.id
            } else {
                refreshWindow()
            }
            return
        }
        mode = next
        settings.set(next)
    }

    /// Explore tile / search pick → splice ahead of the window and jump.
    private func openInPlayer(_ note: FeedNote) {
        if !videos.contains(where: { $0.id == note.id }) {
            var next = spliced
            next.insert(note, at: 0)
            spliced = Array(next.prefix(8))
        }
        mode = .forYou
        settings.set(SettingsBitzMode.forYou)
        pendingJumpId = note.id
    }

    private func like(_ note: FeedNote) {
        let turningOn = !environment.feedStore.localActions.liked.contains(note.id)
        environment.feedStore.localActions.toggleLike(note.id)
        guard turningOn, environment.identityStore.account != nil else { return }
        Task { await environment.notePublisher.publishReaction(targetEventId: note.id, targetPubkey: note.pubkey) }
    }

    private func toggleBookmark(_ note: FeedNote) {
        if let updated = environment.feedStore.applyBookmarkChange(eventId: note.id, add: !environment.feedStore.bookmarkedIds.contains(note.id)) {
            guard environment.identityStore.account != nil else { return }
            Task { await environment.notePublisher.publishBookmarkList(eventIds: updated) }
        } else {
            environment.feedStore.localActions.toggleBookmark(note.id)
        }
    }

    private func toggleFollow(_ author: String) {
        guard let updated = environment.feedStore.applyFollowChange(author: author, add: !environment.feedStore.following.contains(author)) else { return }
        guard environment.identityStore.account != nil else { return }
        Task { await environment.notePublisher.publishFollowList(follows: updated) }
    }

    private func repost(_ note: FeedNote) {
        guard environment.identityStore.account != nil else { return }
        Task { await environment.notePublisher.publishRepost(targetEventId: note.id, targetPubkey: note.pubkey) }
    }

    /// Legacy parity: rail counts from the live tallies (+ optimistic like).
    private func railCounts(for note: FeedNote) -> BitzRailCounts {
        let store = environment.feedStore
        let tally = store.tallies[note.id]
        let liked = store.localActions.liked.contains(note.id)
        let comments = store.comments[note.id]?.count ?? 0
        let reposts = tally?.reposts ?? 0
        let likeTotal = (tally?.reactions ?? 0) + (liked && (tally?.reactions ?? 0) == 0 ? 1 : 0)
        let zapLabel = rules.formatSats(zapMillisats: tally?.zapMillisats ?? 0)
            ?? (store.zapCounts[note.id]).flatMap { $0 > 0 ? rules.formatCount(Int64($0)) : nil }
        return BitzRailCounts(
            zap: zapLabel ?? "Zap",
            like: likeTotal > 0 ? rules.formatCount(Int64(likeTotal)) : (liked ? "Unlike" : "Like"),
            comment: comments > 0 ? rules.formatCount(Int64(comments)) : "Comments",
            repost: reposts > 0 ? rules.formatCount(Int64(reposts)) : "Repost"
        )
    }

    private func presentMoreMenu(for note: FeedNote, at anchor: CGPoint) {
        menu = AppMenuPresentation(
            anchor: anchor,
            entries: [
                .item(AppMenuItem(
                    id: "mute",
                    label: environment.feedStore.muted.contains(note.pubkey) ? "Unmute author" : "Mute author",
                    systemImage: AppIcons.mute
                )),
                .item(AppMenuItem(id: "share", label: "Share", systemImage: AppIcons.share)),
                .item(AppMenuItem(id: "copy-id", label: "Copy note ID", systemImage: AppIcons.copy)),
                .divider,
                .item(AppMenuItem(id: "report-spam", label: "Report as spam", systemImage: AppIcons.reportSpam, isDestructive: true)),
                .item(AppMenuItem(id: "report-illicit", label: "Report as illicit", systemImage: AppIcons.reportIllicit, isDestructive: true)),
                .item(AppMenuItem(id: "report-harassment", label: "Report as harassment", systemImage: AppIcons.reportHarassment, isDestructive: true)),
            ]
        ) { id in
            switch id {
            case "mute": environment.feedStore.toggleMute(note.pubkey)
            case "share":
                // Web parity: Share lives in the overflow, not the rail.
                shareText = rules.shareText(content: note.content, authorNpub: rules.npub(note.pubkey))
            case "copy-id": UIPasteboard.general.string = note.id
            case "report-spam": report(note, reason: "spam")
            case "report-illicit": report(note, reason: "illicit")
            case "report-harassment": report(note, reason: "harassment")
            default: break
            }
        }
    }

    private func report(_ note: FeedNote, reason: String) {
        guard environment.identityStore.account != nil else { return }
        Task { await environment.notePublisher.publishReport(targetEventId: note.id, targetPubkey: note.pubkey, reason: reason) }
        menu = nil
    }

    // MARK: Path monitor (autoplay Wi-Fi policy)

    private func startPathMonitor() {
        guard pathMonitor == nil else { return }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { path in
            Task { @MainActor in
                wifiUnmetered = !path.isExpensive
            }
        }
        monitor.start(queue: DispatchQueue(label: "bitz.autoplay"))
        pathMonitor = monitor
    }

    private func stopPathMonitor() {
        pathMonitor?.cancel()
        pathMonitor = nil
    }
}

// 24 + 18 explore paging mirrored from the shared `BitzExplore` object.
enum BitzExploreVisible {
    static func tiles(_ loadMoreCount: Int) -> Int { 24 + 18 * min(max(loadMoreCount, 0), 100) }
}

// MARK: - Top bar

private struct BitzTopBar: View {
    let mode: SettingsBitzMode
    let onSelectMode: (SettingsBitzMode) -> Void
    let onSearch: () -> Void
    /** Spec §3.7 record entry: camera capture → trim → publish. */
    let onRecord: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            // No wordmark, no refresh button (user decision 2026-08-28):
            // refresh = re-tap the active Bitz tab in the bottom bar.
            Spacer(minLength: 8)
            HStack(spacing: 2) {
                pill("Explore", .explore)
                pill("Following", .following)
                pill("For you", .forYou)
            }
            .padding(2)
            .background(Color.black.opacity(0.4), in: Capsule())
            Spacer(minLength: 8)
            Button(action: onRecord) {
                AppIcons.image(for: AppIcons.camera)
                    .foregroundStyle(.white)
            }
            .accessibilityLabel("Record Bitz")
            Button(action: onSearch) {
                AppIcons.image(for: AppIcons.search)
                    .foregroundStyle(.white)
            }
            .accessibilityLabel("Search Bitz")
        }
        .padding(.horizontal, BitOSTheme.Spacing.base)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
    }

    private func pill(_ label: String, _ value: SettingsBitzMode) -> some View {
        let selected = mode == value
        return Button {
            onSelectMode(value)
        } label: {
            Text(label)
                .font(.caption.weight(selected ? .bold : .semibold))
                .foregroundStyle(selected ? Color(red: 0.04, green: 0.04, blue: 0.06) : .white.opacity(0.9))
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(selected ? BitOSTheme.accent : Color.clear, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Show \(label) videos")
    }
}

// MARK: - Explore grid

private struct BitzTile: View {
    let note: FeedNote
    let profile: ProfileMetadata?
    let zapCount: Int
    /** Legacy parity: like count alongside zaps in the tile footer. */
    var likeCount: Int = 0
    private let rules = BitzBridgeRules()
    /** Pre-formatted duration label (shared rule, computed by the parent). */
    let durationLabel: String?
    let sensitiveShown: Bool
    let revealed: Bool
    let onReveal: () -> Void
    let onOpen: () -> Void

    private var covered: Bool {
        note.contentWarning && !sensitiveShown && !revealed
    }

    var body: some View {
        ZStack {
            BitzPosterImage(url: note.video?.posterUrl)
            if note.video?.posterUrl == nil {
                AppIcons.image(for: AppIcons.play)
                    .font(.title2)
                    .foregroundStyle(.white.opacity(0.8))
            }
            if covered {
                VStack(spacing: 4) {
                    Text("Sensitive content")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.white.opacity(0.9))
                    Button("Show", action: onReveal)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(BitOSTheme.accent)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color(red: 0.04, green: 0.04, blue: 0.06).opacity(0.85))
            } else {
                VStack {
                    Spacer()
                    HStack(spacing: 6) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(note.pubkey))
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.white)
                                .lineLimit(1)
                            if zapCount > 0 || likeCount > 0 {
                                HStack(spacing: 6) {
                                    if zapCount > 0 {
                                        HStack(spacing: 2) {
                                            AppIcons.image(for: AppIcons.zap)
                                                .font(.system(size: 9))
                                                .foregroundStyle(BitOSTheme.zap)
                                            Text(rules.formatCount(Int64(zapCount)))
                                                .font(.caption2)
                                                .foregroundStyle(.white.opacity(0.9))
                                        }
                                    }
                                    if likeCount > 0 {
                                        HStack(spacing: 2) {
                                            AppIcons.image(for: AppIcons.heart)
                                                .font(.system(size: 9))
                                                .foregroundStyle(BitOSTheme.like)
                                            Text(rules.formatCount(Int64(likeCount)))
                                                .font(.caption2)
                                                .foregroundStyle(.white.opacity(0.9))
                                        }
                                    }
                                }
                            }
                        }
                        Spacer()
                        if let durationLabel {
                            Text(durationLabel)
                                .font(.system(size: 9, weight: .semibold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 4)
                                .padding(.vertical, 1)
                                .background(Color.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 4))
                        }
                    }
                    .padding(4)
                    .background(
                        LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom)
                    )
                }
            }
        }
        .aspectRatio(9.0 / 16.0, contentMode: .fit)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .accessibilityLabel("Play video by \(profile?.bestDisplayName ?? "author")")
    }
}

private struct SkeletonTile: View {
    @State private var pulsing = false

    var body: some View {
        Rectangle()
            .fill(BitOSTheme.surfaceElevated)
            .aspectRatio(9.0 / 16.0, contentMode: .fit)
            .opacity(pulsing ? 0.75 : 0.35)
            .animation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true), value: pulsing)
            .onAppear { pulsing = true }
    }
}

// MARK: - Player page

/// Legacy-parity rail count labels (computed once per page at the call site).
struct BitzRailCounts: Equatable {
    var zap: String = "Zap"
    var like: String = "Like"
    var comment: String = "Comments"
    var repost: String = "Repost"
}

private struct BitzVideoPage: View {
    let note: FeedNote
    let profile: ProfileMetadata?
    let actions: LocalActions
    let pool: PlayerPool
    let isSettled: Bool
    let isFollowing: Bool
    let isBookmarked: Bool
    let muted: Bool
    let sensitiveShown: Bool
    let revealed: Bool
    let positionMs: Int64
    let durationMs: Int64
    let onToggleMute: () -> Void
    let onLike: () -> Void
    let onBookmark: () -> Void
    let onComment: () -> Void
    let onRepost: () -> Void
    let onFollow: () -> Void
    let onZap: () -> Void
    let onRemix: () -> Void
    var onChain: () -> Void = {}
    let onAuthor: () -> Void
    let onMore: (CGPoint) -> Void
    /** Legacy parity: count labels replace the static ones when > 0. */
    let railCounts: BitzRailCounts

    @State private var likeBurst = false
    @State private var seekHint: String?
    /** Long-press 2× fast-forward indicator (legacy parity). */
    @State private var fastForward = false

    private var covered: Bool {
        note.contentWarning && !sensitiveShown && !revealed
    }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()
                BitzPosterImage(url: note.video?.posterUrl)
                PlayerSurface(player: pool.player(for: note.id))
                if !covered {
                    Color.clear
                        .contentShape(Rectangle())
                        // Legacy parity: tap pause/play; double-tap thirds —
                        // left −10 s, center like, right +10 s; hold = 2×.
                        .gesture(
                            SpatialTapGesture(count: 2).onEnded { tap in
                                handleDoubleTap(at: tap.location, width: geo.size.width)
                            }
                        )
                        .onTapGesture(count: 1) {
                            pool.togglePlay(noteId: note.id)
                        }
                        .onLongPressGesture(minimumDuration: .infinity, pressing: { pressing in
                            if pressing {
                                fastForward = true
                                pool.setRateBoost(noteId: note.id, multiplier: 2)
                            } else if fastForward {
                                fastForward = false
                                pool.setRateBoost(noteId: nil, multiplier: nil)
                            }
                        }, perform: {})
                        .accessibilityLabel("Pause or resume playback")
                        .accessibilityAddTraits(.isButton)
                }
                if fastForward {
                    Text("2×")
                        .font(.footnote.weight(.heavy))
                        .foregroundStyle(Color(red: 0.04, green: 0.04, blue: 0.06))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 3)
                        .background(BitOSTheme.accent, in: Capsule())
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                        .padding(.top, BitOSTheme.Spacing.xl)
                        .padding(.trailing, BitOSTheme.Spacing.base)
                }
                if likeBurst {
                    AppIcons.image(for: AppIcons.heartFill)
                        .font(.system(size: 88))
                        .foregroundStyle(BitOSTheme.like)
                        .transition(.scale.combined(with: .opacity))
                        .task {
                            try? await Task.sleep(nanoseconds: 700_000_000)
                            likeBurst = false
                        }
                }
                if let seekHint {
                    Text(seekHint)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
                        .task {
                            try? await Task.sleep(nanoseconds: 800_000_000)
                            self.seekHint = nil
                        }
                }
                if covered {
                    SensitiveCover(onReveal: {})
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(Color.black.opacity(0.9))
                }
                VStack {
                    Spacer()
                    caption
                        .padding(.bottom, 44)
                }
                HStack {
                Spacer()
                rail
            }
            VStack {
                Spacer()
                controls
            }
            }
        }
    }

    /// Double-tap thirds (legacy parity): left −10 s, center like, right +10 s.
    private func handleDoubleTap(at point: CGPoint, width: CGFloat) {
        guard width > 0 else {
            onLike()
            likeBurst = true
            return
        }
        if point.x < width / 3 {
            pool.seekBy(noteId: note.id, deltaMs: -10_000)
            seekHint = "10 seconds back"
        } else if point.x > width * 2 / 3 {
            pool.seekBy(noteId: note.id, deltaMs: 10_000)
            seekHint = "10 seconds forward"
        } else {
            onLike()
            likeBurst = true
        }
    }

    private var caption: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Button(action: onAuthor) {
                    HStack(spacing: 8) {
                        PubkeyAvatarView(
                            pubkey: note.pubkey,
                            size: 36,
                            label: profile?.bestDisplayName,
                            hasLightning: !(profile?.lud16?.isEmpty ?? true)
                        )
                        VStack(alignment: .leading, spacing: 1) {
                            HStack(spacing: 4) {
                                Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(note.pubkey))
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.white)
                                    .lineLimit(1)
                                if !(profile?.nip05?.isEmpty ?? true) {
                                    AppIcons.image(for: AppIcons.checkCircle)
                                        .font(.caption2)
                                        .foregroundStyle(BitOSTheme.accent)
                                        .accessibilityLabel("NIP-05 identity claim")
                                }
                            }
                            Text(FeedFormat.timeAgo(createdAt: note.createdAt))
                                .font(.caption2)
                                .foregroundStyle(.white.opacity(0.7))
                        }
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("View author profile")
                Button(action: onFollow) {
                    Text(isFollowing ? "Following" : "Follow")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(isFollowing ? .white.opacity(0.7) : BitOSTheme.accent)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(
                            isFollowing ? Color.white.opacity(0.08) : BitOSTheme.accent.opacity(0.16),
                            in: Capsule()
                        )
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isFollowing ? "Unfollow author" : "Follow author")
            }
            if note.repostedBy != nil {
                Text("Reposted")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.repost)
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
            LinearGradient(colors: [.clear, .clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom)
        )
    }

    private var rail: some View {
        VStack(spacing: BitOSTheme.Spacing.lg) {
            // Web-parity order (legacy bitz rail): Remix first (violet), then
            // Zap · Like · Comments · Repost · Save; Share lives in the ⋯ menu.
            railButton(AppIcons.sparkles, "Remix", tint: Color(red: 0.545, green: 0.361, blue: 0.965), action: onRemix)
            // Chain: only when this note declares a remix source (web parity).
            if note.remixOfEventId != nil {
                railButton(AppIcons.appsGrid, "Chain", tint: .white, action: onChain)
            }
            railButton(AppIcons.zap, railCounts.zap, tint: BitOSTheme.zap, action: onZap)
            railButton(
                actions.liked.contains(note.id) ? AppIcons.heartFill : AppIcons.heart,
                railCounts.like,
                tint: actions.liked.contains(note.id) ? BitOSTheme.like : .white,
                action: onLike
            )
            railButton(AppIcons.comment, railCounts.comment, tint: .white, action: onComment)
            railButton(AppIcons.repost, railCounts.repost, tint: .white, action: onRepost)
            railButton(
                isBookmarked ? AppIcons.bookmarkFill : AppIcons.bookmark,
                isBookmarked ? "Saved" : "Save",
                tint: isBookmarked ? BitOSTheme.bookmark : .white,
                action: onBookmark
            )
            AppMenuAnchorButton(symbol: AppIcons.more, tint: .white, label: "More options") { point in
                onMore(point)
            }
        }
        .padding(.trailing, BitOSTheme.Spacing.base)
    }

    private var rules: BitzBridgeRules { BitzBridgeRules() }

    private func railButton(_ symbol: String, _ label: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) { railLabel(symbol, label, tint: tint) }
            .buttonStyle(.plain)
            .accessibilityLabel(label)
    }

    private func railLabel(_ symbol: String, _ label: String, tint: Color) -> some View {
        VStack(spacing: 2) {
            AppIcons.image(for: symbol)
                .font(.title3)
                .foregroundStyle(tint)
                .frame(width: 44, height: 44)
                .background(Color.black.opacity(0.2), in: Circle())
            Text(label)
                .font(.caption2)
                .foregroundStyle(tint)
        }
    }

    /// Compact bottom controls: mute memory, ±10 s pills, scrubber (§3.7).
    private var controls: some View {
        HStack(spacing: 8) {
            Button(action: onToggleMute) {
                AppIcons.image(for: muted ? AppIcons.mute : AppIcons.soundOn)
                    .font(.subheadline)
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
            }
            .accessibilityLabel(muted ? "Unmute video" : "Mute video")
            Button {
                pool.seekBy(noteId: note.id, deltaMs: -10_000)
                seekHint = "10 seconds back"
            } label: {
                AppIcons.image(for: AppIcons.back10)
                    .font(.subheadline)
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
            }
            .accessibilityLabel("Seek 10 seconds back")
            Slider(
                value: Binding(
                    get: {
                        durationMs > 0 ? Double(positionMs) / Double(durationMs) : 0
                    },
                    set: { fraction in
                        if durationMs > 0 {
                            let target = Int64(fraction * Double(durationMs))
                            pool.seekTo(noteId: note.id, positionMs: target)
                        }
                    }
                ),
                in: 0...1
            )
            .disabled(durationMs <= 0)
            .tint(BitOSTheme.accent)
            .accessibilityLabel("Video position")
            Button {
                pool.seekBy(noteId: note.id, deltaMs: 10_000)
                seekHint = "10 seconds forward"
            } label: {
                AppIcons.image(for: AppIcons.forward10)
                    .font(.subheadline)
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
            }
            .accessibilityLabel("Seek 10 seconds forward")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 2)
        .background(Color.black.opacity(0.26))
    }
}

// MARK: - Search overlay (spec §3.7)

struct BitzSearchOverlay: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(SettingsStore.self) private var settings
    let onOpenNote: (FeedNote) -> Void
    let onDismiss: () -> Void

    @State private var query = ""
    @State private var revealedIds: Set<String> = []
    @FocusState private var focused: Bool
    private let rules = BitzBridgeRules()

    private var localVideos: [FeedNote] {
        environment.feedStore.notes.filter { $0.video != nil }
    }

    private var relayVideos: [FeedNote] {
        environment.searchStore.results.filter { $0.video != nil }
    }

    private var results: [FeedNote] {
        let ids = rules.mergedResultIds(
            query: query,
            local: localVideos,
            relay: relayVideos,
            profiles: environment.feedStore.profiles
        )
        let byId = Dictionary(uniqueKeysWithValues: (localVideos + relayVideos).map { ($0.id, $0) })
        return ids.compactMap { byId[$0] }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                HStack(spacing: 6) {
                    AppIcons.image(for: AppIcons.search)
                        .foregroundStyle(BitOSTheme.textSecondary)
                    TextField("Search Bitz", text: $query)
                        .focused($focused)
                        .submitLabel(.search)
                        .autocorrectionDisabled()
                }
                .padding(10)
                .background(BitOSTheme.surface, in: RoundedRectangle(cornerRadius: 10))
                Button("Cancel", action: onDismiss)
                    .foregroundStyle(BitOSTheme.accent)
            }
            .padding(BitOSTheme.Spacing.base)

            if query.trimmingCharacters(in: .whitespaces).isEmpty {
                Spacer()
                Text("Search captions and creators")
                    .font(.subheadline)
                    .foregroundStyle(BitOSTheme.textTertiary)
                Spacer()
            } else if results.isEmpty && environment.searchStore.isSearching {
                Spacer()
                ProgressView().tint(BitOSTheme.accent)
                Spacer()
            } else if results.isEmpty && environment.searchStore.hasSearched {
                Spacer()
                Text("No videos match \"\(query.trimmingCharacters(in: .whitespaces))\"")
                    .font(.subheadline)
                    .foregroundStyle(BitOSTheme.textSecondary)
                Spacer()
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 2), GridItem(.flexible(), spacing: 2), GridItem(.flexible(), spacing: 2)], spacing: 2) {
                        ForEach(results, id: \.id) { note in
                            BitzTile(
                                note: note,
                                profile: environment.feedStore.profiles[note.pubkey],
                                zapCount: environment.feedStore.zapCounts[note.id] ?? 0,
                                likeCount: environment.feedStore.tallies[note.id]?.reactions ?? 0,
                                durationLabel: note.video?.durationSeconds.map { rules.formatDuration($0) },
                                sensitiveShown: settings.state.sensitiveMedia == .show,
                                revealed: revealedIds.contains(note.id),
                                onReveal: { revealedIds.insert(note.id) },
                                onOpen: {
                                    onDismiss()
                                    onOpenNote(note)
                                }
                            )
                        }
                    }
                    .padding(2)
                }
            }
        }
        .background(Color(red: 0.04, green: 0.04, blue: 0.06).opacity(0.95))
        .onAppear { focused = true }
        .onChange(of: query) { _, value in
            // SearchStore applies the shared 400 ms relay debounce.
            environment.searchStore.search(value)
        }
    }
}

// MARK: - Shared bits

private struct BitzLoadingView: View {
    var body: some View {
        ProgressView().tint(BitOSTheme.accent).frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Poster loader (media pipeline lands later; HomeView parity).
/// System share sheet for the ⋯ overflow Share action (web parity).
private struct ShareSheet: UIViewControllerRepresentable {
    let items: [String]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

// MARK: - Chain sheet (APP-007, web RemixChainDialog parity)

private struct BitzChainSheet: View {
    let store: FeedStore
    let rootId: String
    let onOpenAncestor: (String) -> Void
    let onClose: () -> Void

    @State private var visibleRows = 8

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            HStack {
                Text(header)
                    .font(.headline)
                    .foregroundStyle(BitOSTheme.textPrimary)
                Spacer()
                Button("Close", action: onClose)
                    .foregroundStyle(BitOSTheme.accent)
            }
            content
        }
        .padding(BitOSTheme.Spacing.base)
        .onAppear { visibleRows = 8 }
        .background(BitOSTheme.background)
    }

    private var header: String {
        let state = store.remixChainState
        guard state.rootId == rootId else { return "Remix chain" }
        if state.isCompleted, !state.isCycle {
            return "\(state.steps.count) remix source\(state.steps.count == 1 ? "" : "s") traced"
        }
        return "Remix chain"
    }

    @ViewBuilder
    private var content: some View {
        let state = store.remixChainState
        if state.isLoading || state.rootId != rootId {
            VStack(spacing: BitOSTheme.Spacing.sm) {
                ProgressView().tint(BitOSTheme.accent)
                Text("Tracing the chain…")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, BitOSTheme.Spacing.xl)
        } else if state.isCycle {
            Text("Couldn't read the full chain — the lineage loops or a relay failed.")
                .font(.caption)
                .foregroundStyle(BitOSTheme.textSecondary)
                .padding(.vertical, BitOSTheme.Spacing.md)
        } else if state.steps.isEmpty {
            Text("No remix ancestry found on your relays.")
                .font(.caption)
                .foregroundStyle(BitOSTheme.textSecondary)
                .padding(.vertical, BitOSTheme.Spacing.md)
        } else {
            ScrollView {
                VStack(spacing: BitOSTheme.Spacing.sm) {
                    ForEach(state.steps.prefix(visibleRows), id: \.eventId) { step in
                        row(step)
                    }
                    if visibleRows < state.steps.count {
                        Button {
                            visibleRows += 8
                        } label: {
                            Text("Show more (\(state.steps.count - visibleRows) older)")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(BitOSTheme.accent)
                                .frame(maxWidth: .infinity)
                        }
                    }
                    if state.truncated {
                        Text("Chain longer than 32 — oldest steps hidden.")
                            .font(.caption2)
                            .foregroundStyle(BitOSTheme.textTertiary)
                    }
                }
            }
            .frame(maxHeight: 420)
        }
    }

    private func row(_ step: FeedStore.RemixChainStep) -> some View {
        Button {
            onOpenAncestor(step.eventId)
        } label: {
            HStack(spacing: BitOSTheme.Spacing.md) {
                PubkeyAvatarView(
                    pubkey: step.pubkey ?? "",
                    size: 32,
                    label: step.pubkey.flatMap { store.profiles[$0]?.bestDisplayName },
                    hasLightning: !(step.pubkey.flatMap { store.profiles[$0]?.lud16 } ?? "").isEmpty
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(step.pubkey.flatMap { store.profiles[$0]?.bestDisplayName }
                         ?? (step.pubkey.map(FeedFormat.shortPubkey) ?? "Unknown author"))
                        .font(.subheadline)
                        .foregroundStyle(BitOSTheme.textPrimary)
                        .lineLimit(1)
                    Text(step.depth == 0 ? "Direct source" : "\(step.depth) steps back")
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.textTertiary)
                }
                Spacer()
                if step.depth == 0 {
                    Text("Source")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(BitOSTheme.accent)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(BitOSTheme.accent.opacity(0.14), in: Capsule())
                }
            }
            .padding(.horizontal, BitOSTheme.Spacing.sm)
            .padding(.vertical, 6)
            .background(BitOSTheme.surface, in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Open remix source")
    }
}

private struct BitzPosterImage: View {
    let url: String?
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            LinearGradient(colors: [BitOSTheme.surface, BitOSTheme.surfaceElevated], startPoint: .topLeading, endPoint: .bottomTrailing)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            }
        }
        .clipped()
        .task(id: url) {
            guard let url, let target = URL(string: url) else { return }
            image = nil
            guard let (data, _) = try? await URLSession.shared.data(from: target) else { return }
            let loaded = UIImage(data: data)
            guard url == self.url else { return }
            image = loaded
        }
    }
}
