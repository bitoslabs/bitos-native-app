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

    /// M4b remix relay hints (shared rule): the source's own remix-tag
    /// relays first, then the app's write relays, deduped, ≤3.
    func remixRelayHints(sourceRelays: [String], writeRelays: [String]) -> [String] {
        func encode(_ values: [String]) -> String {
            guard let data = try? JSONSerialization.data(withJSONObject: values),
                  let json = String(data: data, encoding: .utf8) else { return "[]" }
            return json
        }
        let merged = bridge.remixRelayHintsJson(
            sourceRelaysJson: encode(sourceRelays), writeRelaysJson: encode(writeRelays)
        )
        guard let data = merged.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [String] else { return [] }
        return array
    }

    /// Compact rail-count labels (legacy `_formatCount` / `_compactBitSats`
    /// parity) through the shared rules.
    func formatCount(_ value: Int64) -> String {
        bridge.bitzFormatCount(value: value)
    }

    func formatSats(zapMillisats: Int64) -> String? {
        (bridge.bitzFormatSats(zapMillisats: zapMillisats) as String?)
    }

    func exploreVisibleCount(loadMoreCount: Int) -> Int {
        Int(bridge.bitzExploreVisibleCount(loadMoreCount: Int32(clamping: loadMoreCount)))
    }

    func prefetchThreshold() -> Int {
        Int(bridge.bitzWalkPrefetchThreshold())
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
    /// Web `/bitz?author=<npub>` parity: author-scoped playback from a
    /// profile Bitz-grid tile. Nil = the normal three-tab surface.
    var authorPubkey: String? = nil
    /// Web `#bitz=<id>` parity: the tapped profile grid tile lands first.
    var initialNoteId: String? = nil
    /// Author-mode back bar → returns to the profile.
    var onExitAuthorMode: (() -> Void)? = nil

    @Environment(AppEnvironment.self) private var environment
    @Environment(SettingsStore.self) private var settings
    @Environment(IdentityStore.self) private var identity

    @State private var pool = PlayerPool()
    @State private var mode: SettingsBitzMode?
    @State private var topId: String?
    @State private var spliced: [FeedNote] = []
    /// The last For You visit is a native presentation snapshot. Explore
    /// refreshes the same relay lane, but must not replace a reader's For You
    /// pager when they return to it.
    @State private var forYouNotes: [FeedNote] = []
    @State private var loadMoreCount = 0
    @State private var explorePrefetchStart = 0
    /** Explore browse-stable snapshot (§ explore stability): the window is
     *  a moving, bounded projection — live arrivals re-ordered the grid and
     *  the cap deleted old tiles mid-browse. Captured on entry/refresh,
     *  appended (never re-ordered) as pages land. */
    @State private var exploreNotes: [FeedNote] = []
    @State private var revealedIds: Set<String> = []
    @State private var wifiUnmetered = false
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
    /** Legacy-parity ⋯ overflow → bottom sheet. */
    @State private var moreSheetTarget: FeedNote?
    /** Card ⋯ raw-event viewer (NIP-01 canonical object). */
    @State private var rawEventText: RawEvent?
    /** External-link confirm sheet (never opens the browser unattended). */
    @State private var externalLink: String?
    @State private var pendingJumpId: String?
    @State private var pathMonitor: NWPathMonitor?
    /** APP-007 remix: seeded composer tags + the advisory-ask target. */
    @State private var composerSeedTagsJson = "[]"
    @State private var remixAskTarget: FeedNote?
    /** M4b pre-flight: the tapped note's lineage loops — refuse the remix. */
    @State private var remixCycleBlocked = false
    /** M4b remix: the editor handoff (media + layout + lineage). */
    @State private var memeRemixSeed: MemeRemixSeed?
    private let remixSlotStore = MemeProjectStore()
    @State private var shareText: String?
    /** Swipe-right on settled Bitz video → full-screen profile for that creator. */
    @State private var fullProfileTarget: String?
    /** Author playback scope (web `/bitz?author=` parity): one REQ + one
     * private window; leaving releases it. */
    @State private var authorStore: AuthorStore?
    private let rules = BitzBridgeRules()

    private var authorMode: Bool { authorPubkey != nil }

    private var videos: [FeedNote] {
        if authorMode {
            return authorStore?.notes.filter { $0.video != nil } ?? []
        }
        // Store-derived projection (audit §4): the video window is filtered
        // once per coalesced publication, never per body evaluation.
        return environment.feedStore.videoNotes
    }

    private var playerNotes: [FeedNote] {
        // The paged list is the active tab's window (legacy
        // `displayedEvents`): search picks spliced ahead, then the
        // verified video window. Author mode plays the author's owned
        // window in loaded order (no splice — playback scope must match
        // the profile grid).
        if authorMode { return videos }
        // Search splices are exceptional. Preserve the store's existing
        // immutable projection in steady state instead of rebuilding a set
        // and copying up to 200 notes on every SwiftUI body access.
        let displayedVideos = mode == .forYou && !forYouNotes.isEmpty ? forYouNotes : videos
        guard !spliced.isEmpty else { return displayedVideos }
        let windowIds = Set(displayedVideos.map(\.id))
        return spliced.filter { !windowIds.contains($0.id) } + displayedVideos
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
        .onChange(of: revealedIds) { _, _ in reconcilePool(visibleId: topId) }
        .onChange(of: settings.state.videoMuted) { _, _ in reconcilePool(visibleId: topId) }
        // UX U9: quality change re-prepares the bounded slots at the new
        // rung immediately (pool rebuilds on preference change).
        .onChange(of: settings.state.videoQuality) { _, _ in reconcilePool(visibleId: topId) }
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

    /// Stage 2b (task loops; type-checker split). Position polling lives on
    /// the settled BitzVideoPage — a root-level 2 Hz tick invalidated the
    /// whole view tree (pager, sheets, computed projections) twice a second.
    private func stage2b<V: View>(_ base: V) -> some View {
        base
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
        .task(id: authorPubkey) {
            // Web `#bitz=<id>` parity: the tapped profile grid tile is the
            // first thing on screen; the resolver waits for the author REQ.
            guard authorMode, let initialNoteId else { return }
            pendingJumpId = initialNoteId
        }
        .sheet(item: Binding(
            get: { authorTarget.map { BitzAuthorTarget(id: $0) } },
            set: { authorTarget = $0?.id }
        )) { target in
            AuthorProfileSheet(
                authorPubkey: target.id,
                onClose: { authorTarget = nil },
                onOpenFullProfile: {
                    authorTarget = nil
                    fullProfileTarget = target.id
                }
            )
            .environment(identity)
            .presentationDetents([.medium, .large])
        }
        .fullScreenCover(item: Binding(
            get: { fullProfileTarget.map { BitzAuthorTarget(id: $0) } },
            set: { fullProfileTarget = $0?.id }
        )) { target in
            AuthorProfileFullView(authorPubkey: target.id, onClose: { fullProfileTarget = nil })
                .environment(environment)
                .environment(identity)
        }
        .appMenuHost($menu)
        .sheet(item: $moreSheetTarget) { note in
            AppBottomSheetMenu(
                title: "Bitz actions",
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
        // External-link confirm: the browser only opens on an explicit Open.
        .sheet(isPresented: Binding(
            get: { externalLink != nil },
            set: { if !$0 { externalLink = nil } }
        )) {
            if let externalLink {
                ExternalLinkConfirmSheet(url: externalLink)
            }
        }
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
                // Immersive pager: the video plays behind the sheet, so the
                // origin card never repeats it.
                showRootCard: false,
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
        // M4b: a bitz remix opens the meme EDITOR with the source media,
        // its decoded layout and the lineage attached (web studio parity).
        .fullScreenCover(isPresented: Binding(
            get: { memeRemixSeed != nil },
            set: { if !$0 { memeRemixSeed = nil } }
        )) {
            if let memeRemixSeed {
                MemeEditorView(
                    slotStore: remixSlotStore,
                    remixSeed: memeRemixSeed
                )
                .preferredColorScheme(nil)
            }
        }
        .fullScreenCover(isPresented: $showSearch) {
            BitzSearchOverlay(onOpenNote: openInPlayer, onDismiss: { showSearch = false })
                .environment(environment)
                .environment(settings)
                .preferredColorScheme(BitOSTheme.preferredScheme)
        }
        // T4: record entry → the Create hub (camera/import capture flow).
        .fullScreenCover(isPresented: $showCreateHub) {
            CreateView()
                .preferredColorScheme(BitOSTheme.preferredScheme)
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
                    openRemixTarget(target)
                }
                remixAskTarget = nil
            }
            Button("Cancel", role: .cancel) { remixAskTarget = nil }
        } message: {
            Text("This creator marked this bitz \"\(remixAskTarget?.license ?? "")\".\n\nCredit is added automatically when you publish.")
        }
        // M4b cycle pre-flight outcome (web "This remix chain loops" parity).
        .confirmationDialog(
            "Remix chain loops",
            isPresented: $remixCycleBlocked,
            titleVisibility: .visible
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("This bitz's remix lineage loops back on itself, so remaking it would break the chain. It can still be watched and shared.")
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
        .preferredColorScheme(BitOSTheme.preferredScheme)
        .onAppear {
            if mode == nil { mode = settings.state.bitzMode }
            startPathMonitor()
        }
        .onDisappear {
            stopPathMonitor()
            pool.releaseAll()
        }
        .task(id: authorPubkey) {
            // Web `/bitz?author=` parity: one REQ for one author over the
            // private store (cover/sheet lifecycles never clear it).
            guard let authorPubkey else { return }
            let store = authorStore ?? AuthorStore(pool: environment.relayPool, client: environment.businessCore)
            authorStore = store
            store.open(authorPubkey: authorPubkey)
        }
        .task(id: mode) {
            // Three tabs (legacy Flutter parity): the pills drive the same
            // shared window Home uses. Author playback skips the global
            // lanes entirely — its data comes from the author REQ.
            guard let mode, !authorMode else { return }
            switch mode {
            case .forYou:
                environment.feedStore.selectTimeline(.forYou)
                if forYouNotes.isEmpty { forYouNotes = videos }
                // A pending jump (explore/search pick) owns the pager —
                // never reset it to the window head underneath the jump.
                guard pendingJumpId == nil else { break }
                let visibleId = playerNotes.contains(where: { $0.id == topId }) ? topId : playerNotes.first?.id
                topId = visibleId
                reconcilePool(visibleId: visibleId)
            case .following:
                environment.feedStore.selectTimeline(.following)
                guard pendingJumpId == nil else { break }
                let visibleId = playerNotes.contains(where: { $0.id == topId }) ? topId : playerNotes.first?.id
                topId = visibleId
                reconcilePool(visibleId: visibleId)
            case .explore:
                // Explore pages the global/For You lane. Without this reset,
                // switching from Following can make its older-page request
                // use the Following cursor and appear to stop loading.
                environment.feedStore.selectTimeline(.forYou)
                pool.releaseAll()
                // Capture the browse-stable snapshot for this visit.
                exploreNotes = videos
            }
        }
        .onChange(of: settings.state.videoMuted) { _, muted in
            pool.setMuted(muted)
        }
        .onChange(of: videos) { _, newList in
            // Keep the active For You pager current, but leave its prior
            // visit untouched while Explore refreshes the shared relay lane.
            guard !authorMode, mode == .forYou else { return }
            let known = Set(forYouNotes.map(\.id))
            forYouNotes.append(contentsOf: newList.filter { !known.contains($0.id) })
        }
    }

    /// APP-007/M4b remix: advisory license gate → cycle pre-flight → the
    /// meme editor seeded with the source media, its `meme` layout and the
    /// lineage facts (web `remixReel` + studio guard parity). A note
    /// without loadable media falls back to the note composer seeded with
    /// the attribution tags.
    private func handleRemix(_ note: FeedNote) {
        if rules.remixRequiresAsk(license: note.license) {
            remixAskTarget = note
        } else {
            openRemixTarget(note)
        }
    }

    /// Cycle pre-flight (web studio guard parity), then the editor handoff.
    private func openRemixTarget(_ note: FeedNote) {
        Task {
            if await environment.feedStore.remixLineageCycles(note: note) {
                remixCycleBlocked = true
                return
            }
            if let seed = remixEditorSeed(for: note) {
                memeRemixSeed = seed
            } else {
                composerSeedTagsJson = remixSeedTags(note)
                showComposer = true
            }
        }
    }

    /// Builds the editor handoff: source media URL, decoded-layout payload
    /// id, relay hints (source-tag relays + write relays, ≤3) and the
    /// author label for the attribution credit.
    private func remixEditorSeed(for note: FeedNote) -> MemeRemixSeed? {
        let mediaUrl = note.video?.url ?? note.mediaUrls.first
        guard let mediaUrl else { return nil }
        let label = environment.feedStore.profiles[note.pubkey]?.bestDisplayName
            ?? FeedFormat.shortPubkey(note.pubkey)
        return MemeRemixSeed(
            eventId: note.id,
            pubkey: note.pubkey,
            label: label,
            relays: rules.remixRelayHints(
                sourceRelays: note.remixRelays,
                writeRelays: DefaultRelays.writeUrls.map(\.rawValue)
            ),
            mediaUrl: mediaUrl,
            isVideo: note.video != nil,
            memeTag: note.memeTag
        )
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
            if authorMode {
                // Author-mode chrome (web back-to-profile bar): replaces the
                // mode rail; the title opens the full profile.
                BitzAuthorBar(
                    title: authorStore?.profile?.bestDisplayName
                        ?? (authorPubkey.map(FeedFormat.shortPubkey) ?? ""),
                    onBack: { onExitAuthorMode?() },
                    onOpenAuthor: {
                        onExitAuthorMode?()
                        fullProfileTarget = authorPubkey
                    }
                )
            } else {
                BitzTopBar(
                    mode: mode,
                    onSelectMode: selectMode,
                    onSearch: { showSearch = true },
                    onRecord: { showCreateHub = true }
                )
            }
        }
        // No "New note" FAB (user decision 2026-08-29): creation entries are
        // the header record button and the feed FAB.
    }

    private var playerSurface: some View {
        Group {
            if authorMode {
                if authorStore?.isLoading ?? true, playerNotes.isEmpty {
                    BitzLoadingView()
                } else if playerNotes.isEmpty {
                    VStack(spacing: BitOSTheme.Spacing.sm) {
                        AppIcons.image(for: AppIcons.bitz)
                            .font(.system(size: 44))
                            .foregroundStyle(BitOSTheme.textTertiary)
                        Text("No Bitz yet")
                            .font(.headline)
                            .foregroundStyle(.white)
                        Text("Short videos this creator publishes will collect here.")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(BitOSTheme.Spacing.xxl)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    pager
                }
            } else if playerNotes.isEmpty && !environment.feedStore.isLoading {
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
                        onReveal: { revealedIds.insert(note.id) },
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
                        onOpenExternalLink: { externalLink = $0 },
                        onOpenMentionProfile: { authorTarget = $0 },
                        onSwipe: { left in
                            // Swipe right on For-you settled page → creator full profile
                            // (TikTok pattern). Other modes use the standard mode cycle.
                            if !left, mode == .forYou, topId == note.id {
                                fullProfileTarget = note.pubkey
                            } else {
                                handleSwipe(left: left)
                            }
                        },
                        richJson: environment.feedStore.richTokens(for: note.content),
                        railCounts: railCounts(for: note)
                    )
                    .containerRelativeFrame(.vertical)
                    .id(note.id)
                    .onAppear {
                        topId = topId ?? note.id
                        if authorMode {
                            // Author window pages through the private REQ —
                            // same 5-note pages as the profile grid.
                            if index >= playerNotes.count - 2 {
                                authorStore?.loadMoreNotes()
                            }
                            return
                        }
                        // Prepare the next ten videos before this tab reaches its edge.
                        let threshold = environment.feedStore.paginationPrefetchThreshold
                        if index >= playerNotes.count - threshold,
                           !environment.feedStore.isLoadingOlder,
                           !environment.feedStore.noMoreOlder {
                            environment.feedStore.loadOlder()
                        }
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
        .onChange(of: environment.feedStore.isLoadingOlder) { _, loading in
            guard !authorMode, !loading, !environment.feedStore.noMoreOlder,
                  let topId,
                  let index = playerNotes.firstIndex(where: { $0.id == topId }),
                  index >= playerNotes.count - environment.feedStore.paginationPrefetchThreshold else { return }
            environment.feedStore.loadOlder()
        }
    }

    @ViewBuilder
    private var exploreGrid: some View {
        // The snapshot (falling back to the live window only until the
        // first snapshot lands): stable while the reader browses — merges
        // append at the tail, never re-order, never evict.
        let stableList = exploreNotes.isEmpty ? videos : exploreNotes
        let visibleCount = rules.exploreVisibleCount(loadMoreCount: loadMoreCount)
        let tiles = stableList.prefix(visibleCount)
        let prefetchUrls = stableList.dropFirst(explorePrefetchStart).prefix(12).compactMap { $0.video?.posterUrl }
        let columns = [GridItem(.flexible(), spacing: 4), GridItem(.flexible(), spacing: 4), GridItem(.flexible(), spacing: 4)]
        // Legacy bitz parity: centered spinner while the first page loads;
        // Flutter `_BitsEmptyState` when the window is empty — both keep
        // pull-to-refresh alive.
        if stableList.isEmpty && environment.feedStore.isLoading {
            // UX U2: skeleton tiles in the grid shape while the first relay
            // page loads (§2.5 parity with the Android skeleton grid).
            ScrollView {
                LazyVGrid(columns: columns, spacing: 4) {
                    ForEach(0..<9, id: \.self) { _ in
                        AppSkeleton(height: 220, cornerRadius: BitOSTheme.Radius.md)
                    }
                }
                .padding(EdgeInsets(top: 76, leading: 10, bottom: 16, trailing: 10))
            }
            .refreshable { refreshWindow() }
        } else if stableList.isEmpty {
            exploreEmptyState
        } else {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 4) {
                    ForEach(Array(tiles.enumerated()), id: \.element.id) { index, note in
                        BitzTile(
                            note: note,
                            profile: environment.feedStore.profiles[note.pubkey],
                            likeCount: environment.feedStore.tallies[note.id]?.reactions ?? 0,
                            onOpenAuthor: { authorTarget = note.pubkey },
                            sensitiveShown: settings.state.sensitiveMedia == .show,
                            revealed: revealedIds.contains(note.id),
                            onReveal: { revealedIds.insert(note.id) },
                            onOpen: { openInPlayer(note) }
                        )
                        .onAppear {
                            explorePrefetchStart = max(explorePrefetchStart, index + 1)
                            let threshold = environment.feedStore.paginationPrefetchThreshold
                            let trigger = max(0, tiles.count - threshold)
                            if index == trigger {
                                if videos.count > visibleCount {
                                    loadMoreCount += 1
                                } else if !environment.feedStore.isLoadingOlder,
                                          !environment.feedStore.noMoreOlder {
                                    environment.feedStore.loadOlder()
                                }
                            }
                        }
                    }
                    // One trailing spinner tile while the next relay page walks
                    // (Flutter `_ExploreLoadingTile` parity; no footer).
                    if environment.feedStore.isLoadingOlder && !environment.feedStore.noMoreOlder {
                        ExploreLoadingTile()
                    }
                }
                .padding(EdgeInsets(top: 76, leading: 10, bottom: 16, trailing: 10))
                // UX U7: exhausted walk — an explicit boundary instead of a
                // silent dead-end at the grid's last tile.
                if environment.feedStore.noMoreOlder && !stableList.isEmpty {
                    Text("You're all caught up")
                        .font(.footnote)
                        .foregroundStyle(BitOSTheme.textTertiary)
                        .frame(maxWidth: .infinity)
                        .padding(.bottom, 16)
                }
            }
            .simultaneousGesture(
                // Grid parity with the player: horizontal swipes cycle modes.
                DragGesture(minimumDistance: 24)
                    .onEnded { value in horizontalSwipeEnded(value) }
            )
            .refreshable { refreshWindow() }
            .onChange(of: environment.feedStore.isLoadingOlder) { _, loading in
                guard !loading, !environment.feedStore.noMoreOlder,
                      explorePrefetchStart >= max(0, visibleCount - environment.feedStore.paginationPrefetchThreshold) else { return }
                if stableList.count > visibleCount {
                    loadMoreCount += 1
                } else {
                    environment.feedStore.loadOlder()
                }
            }
            // Append-only merge into the snapshot (§ explore stability):
            // relay pages and arrivals land at the tail, never re-ordering
            // what the reader is already looking at.
            .onChange(of: videos) { _, newList in
                guard mode == .explore else { return }
                let known = Set(exploreNotes.map(\.id))
                exploreNotes.append(contentsOf: newList.filter { !known.contains($0.id) })
            }
            .task(id: "\(explorePrefetchStart)-\(stableList.count)") {
                let scale = UIScreen.main.scale
                let tilePixels = UIScreen.main.bounds.width * scale / 3
                await environment.posterImages.prefetch(
                    urlStrings: Array(prefetchUrls),
                    maxPixelSize: tilePixels * 16 / 9
                )
            }
        }
    }

    /// Empty state (Flutter `_BitsEmptyState` parity): rounded icon box,
    /// bold title, muted hint.
    private var exploreEmptyState: some View {
        ScrollView {
            VStack(spacing: BitOSTheme.Spacing.base) {
                RoundedRectangle(cornerRadius: 16)
                    .fill(Color.white.opacity(0.06))
                    .frame(width: 64, height: 64)
                    .overlay {
                        AppIcons.image(for: AppIcons.play)
                            .font(.system(size: 32))
                            .foregroundStyle(Color.white.opacity(0.4))
                    }
                Text("No Bitz found")
                    .font(.title2.weight(.heavy))
                    .foregroundStyle(.white)
                Text("Your configured relays did not return kind-1 notes with video links.")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }
            .frame(maxWidth: .infinity, minHeight: 480)
            .padding(.top, 100)
        }
        .refreshable { refreshWindow() }
        .simultaneousGesture(
            DragGesture(minimumDistance: 24)
                .onEnded { value in horizontalSwipeEnded(value) }
        )
    }

    // MARK: Actions

    private func reconcilePool(visibleId: String?) {
        let covered = playerNotes.first(where: { $0.id == visibleId }).map {
            $0.contentWarning && settings.state.sensitiveMedia != .show && !revealedIds.contains($0.id)
        } ?? false
        pool.update(
            visibleId: visibleId,
            notes: playerNotes,
            autoplayAllowed: !covered && autoplayAllowed(),
            rate: Float(Double(settings.state.playbackRate.rawValue) ?? 1),
            muted: settings.state.videoMuted,
            videoQuality: settings.state.videoQuality.rawValue
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
        explorePrefetchStart = 0
        if mode == .forYou { forYouNotes = videos }
        // Explore keeps its grid stable across the refresh: re-snapshot the
        // current window now, refreshed items merge in afterwards.
        if mode == .explore { exploreNotes = videos }
        environment.feedStore.refresh()
    }

    private func selectMode(_ next: SettingsBitzMode) {
        // Author playback has no mode rail (web parity) — swipes stay inert.
        guard !authorMode else { return }
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

    /**
     * TikTok-style horizontal swipe (3-tab cycle, legacy Flutter parity):
     * a left swipe advances Explore → Following → For you; the final left
     * swipe on For you opens the settled page's creator profile; a right
     * swipe steps back one mode. Presented sheets/overlays swallow the
     * gesture.
     */
    private func handleSwipe(left: Bool) {
        guard !authorMode, !showSearch,
              commentTarget == nil, chainTarget == nil, zapTarget == nil,
              authorTarget == nil, remixAskTarget == nil else { return }
        let order: [SettingsBitzMode] = [.explore, .following, .forYou]
        guard let current = mode, let index = order.firstIndex(of: current) else { return }
        if left {
            if current == .forYou {
                let settled = playerNotes.first { $0.id == topId } ?? playerNotes.first
                if let settled {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    authorTarget = settled.pubkey
                }
            } else {
                selectMode(order[index + 1])
            }
        } else if index > 0 {
            selectMode(order[index - 1])
        }
    }

    /// Horizontal-dominant drag filter shared by the player and the grid.
    private func horizontalSwipeEnded(_ value: DragGesture.Value) {
        let h = value.translation.width
        guard abs(h) > 60, abs(h) > abs(value.translation.height) * 1.5 else { return }
        handleSwipe(left: h < 0)
    }

    /// Explore tile / search pick → splice ahead of the window and jump.
    private func openInPlayer(_ note: FeedNote) {
        if !playerNotes.contains(where: { $0.id == note.id }) {
            var next = spliced
            next.insert(note, at: 0)
            spliced = Array(next.prefix(8))
        }
        mode = .forYou
        settings.set(SettingsBitzMode.forYou)
        // The tapped tile is in playerNotes NOW (window or spliced) — pin
        // the pager to it immediately. Without this, the mode-change task's
        // first-item fallback could pin the wrong page while topId was nil
        // (first pager open) or stale (explore-tap bug).
        topId = note.id
        pendingJumpId = note.id
    }

    private func like(_ note: FeedNote) {
        let turningOn = !environment.feedStore.localActions.liked.contains(note.id)
        environment.feedStore.localActions.toggleLike(note.id)
        guard environment.identityStore.account != nil else { return }
        if turningOn {
            Task { await environment.notePublisher.publishReaction(targetEventId: note.id, targetPubkey: note.pubkey) }
        } else if let reactionId = environment.feedStore.myReactionEventIds[note.id] {
            // Web unlike parity: delete my kind-7 from relays (NIP-09).
            Task { await environment.notePublisher.publishDeletion(targetEventIds: [reactionId]) }
        }
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

    /// ⋯ overflow → bottom sheet (legacy parity: web item set in the
    /// Flutter sheet chrome).
    private func presentMoreMenu(for note: FeedNote, at anchor: CGPoint) {
        moreSheetTarget = note
    }

    private func moreMenuEntries(_ note: FeedNote) -> [AppMenuEntry] {
        [
            .item(AppMenuItem(id: "share", label: "Share", systemImage: AppIcons.share)),
            .item(AppMenuItem(
                id: "save",
                label: environment.feedStore.bookmarkedIds.contains(note.id) ? "Unsave bitz" : "Save bitz",
                systemImage: AppIcons.bookmark
            )),
            .item(AppMenuItem(id: "copy-id", label: "Copy note ID", systemImage: AppIcons.copy)),
            .item(AppMenuItem(id: "copy-text", label: "Copy note text", systemImage: AppIcons.pen)),
            .item(AppMenuItem(id: "copy-npub", label: "Copy author npub", systemImage: AppIcons.user)),
            .item(AppMenuItem(id: "raw-event", label: "View raw event JSON", systemImage: AppIcons.appsGrid)),
            .divider,
            .item(AppMenuItem(
                id: "mute",
                label: environment.feedStore.muted.contains(note.pubkey) ? "Unmute author" : "Mute author",
                systemImage: AppIcons.mute
            )),
            .divider,
            .item(AppMenuItem(id: "report-spam", label: "Report as spam", systemImage: AppIcons.reportSpam, isDestructive: true)),
            .item(AppMenuItem(id: "report-illicit", label: "Report as illicit", systemImage: AppIcons.reportIllicit, isDestructive: true)),
            .item(AppMenuItem(id: "report-harassment", label: "Report as harassment", systemImage: AppIcons.reportHarassment, isDestructive: true)),
        ]
    }

    private func handleMoreSelect(_ note: FeedNote, _ id: String) {
        switch id {
        case "share":
            shareText = rules.shareText(content: note.content, authorNpub: rules.npub(note.pubkey))
        case "save":
            toggleBookmark(note)
        case "copy-id":
            UIPasteboard.general.string = note.id
        case "copy-text":
            UIPasteboard.general.string = note.content
        case "copy-npub":
            UIPasteboard.general.string = rules.npub(note.pubkey)
        case "raw-event":
            rawEventText = environment.feedStore.rawEventJson(forNoteId: note.id).map { RawEvent(value: $0) }
                ?? RawEvent(
                    value: "This event is no longer available in this device's bounded feed cache.",
                    isEventJson: false
                )
        case "mute":
            environment.feedStore.toggleMute(note.pubkey)
        case "report-spam":
            report(note, reason: "spam")
        case "report-illicit":
            report(note, reason: "illicit")
        case "report-harassment":
            report(note, reason: "harassment")
        default: break
        }
    }

    private func report(_ note: FeedNote, reason: String) {
        guard environment.identityStore.account != nil else { return }
        Task { await environment.notePublisher.publishReport(targetEventId: note.id, targetPubkey: note.pubkey, reason: reason) }
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

// MARK: - Top bar

private struct BitzTopBar: View {
    let mode: SettingsBitzMode
    let onSelectMode: (SettingsBitzMode) -> Void
    let onSearch: () -> Void
    /** Spec §3.7 record entry: camera capture → trim → publish. */
    let onRecord: () -> Void

    var body: some View {
        // Clean chrome (user decision 2026-08-29): no bar background, bare
        // text tabs (legacy Flutter TikTok parity), no borders.
        HStack(spacing: 8) {
            Spacer(minLength: 8)
            HStack(spacing: 2) {
                pill("Explore", .explore)
                pill("Following", .following)
                pill("For you", .forYou)
            }
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
    }

    private func pill(_ label: String, _ value: SettingsBitzMode) -> some View {
        let selected = mode == value
        return Button {
            onSelectMode(value)
        } label: {
            Text(label)
                .font(.subheadline.weight(selected ? .heavy : .semibold))
                .foregroundStyle(selected ? Color.white : Color.white.opacity(0.7))
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Show \(label) videos")
    }
}

// MARK: - Author bar

/// Author-mode chrome (web `/bitz?author=` back-to-profile bar): back
/// chevron + creator name; the name opens the full profile. Replaces the
/// mode rail over the media.
private struct BitzAuthorBar: View {
    let title: String
    let onBack: () -> Void
    let onOpenAuthor: () -> Void

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Back to profile")
            Button(action: onOpenAuthor) {
                Text(title)
                    .font(.system(size: 15, weight: .heavy))
                    .foregroundStyle(.white)
                    .lineLimit(1)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Open full profile")
            Spacer(minLength: 0)
        }
        .padding(.horizontal, BitOSTheme.Spacing.xs)
        .padding(.top, BitOSTheme.Spacing.lg)
        .background(
            LinearGradient(
                colors: [.black.opacity(0.55), .clear],
                startPoint: .top, endPoint: .bottom
            )
            .ignoresSafeArea(edges: .top)
        )
    }
}

// MARK: - Explore grid

/// One 9:16 explore tile (Flutter `_ExploreTile` parity): poster cover,
/// bottom scrim = caption · author identity (hex avatar, ⚡ badge,
/// ✓ NIP-05) · like count. Sensitive tiles blur; the reveal gate lives
/// in the player.
private struct BitzTile: View {
    let note: FeedNote
    let profile: ProfileMetadata?
    /** Legacy parity: like count in the tile footer. */
    var likeCount: Int = 0
    /** Author identity row → profile sheet (Flutter `Routes.profileOf`). */
    var onOpenAuthor: (() -> Void)? = nil
    private let rules = BitzBridgeRules()
    let sensitiveShown: Bool
    let revealed: Bool
    let onReveal: () -> Void
    let onOpen: () -> Void

    private var covered: Bool {
        note.contentWarning && !sensitiveShown && !revealed
    }

    /// Caption = content minus image/video URLs, whitespace-collapsed
    /// (Flutter `stripMediaUrls` parity).
    private var caption: String {
        let noUrls = note.content.replacingOccurrences(
            of: "https?://\\S+",
            with: "",
            options: .regularExpression
        )
        return noUrls
            .replacingOccurrences(of: "[ \t]+", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "\n[ \t]+", with: "\n", options: .regularExpression)
            .replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        ZStack {
            BitzPosterImage(url: note.video?.posterUrl, purpose: .grid)
            if note.video?.posterUrl == nil {
                AppIcons.image(for: AppIcons.play)
                    .font(.title2)
                    .foregroundStyle(.white.opacity(0.8))
            }
            if covered {
                // Sensitive: blurred poster + dim + eye-off label
                // (Flutter parity); tap still opens For-you — the reveal
                // gate lives in the player.
                BitzPosterImage(url: note.video?.posterUrl, purpose: .grid)
                    .blur(radius: 14)
                Color.black.opacity(0.35)
                VStack(spacing: 4) {
                    Image(systemName: "eye.slash")
                        .font(.system(size: 20))
                        .foregroundStyle(.white)
                    Text("Sensitive")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                }
            } else {
                VStack {
                    Spacer()
                    exploreFooter
                }
            }
            // UX U8: duration affordance on the tile (shared formatter;
            // top-trailing so it never collides with the footer's like
            // count). Hidden while duration is unknown.
            if let duration = note.video?.durationSeconds, duration > 0 {
                Text(rules.formatDuration(duration))
                    .font(.system(size: 9, weight: .bold).monospacedDigit())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 4)
                    .padding(.vertical, 2)
                    .background(Color.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 4))
                    .padding(4)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .aspectRatio(9.0 / 16.0, contentMode: .fit)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .accessibilityLabel("Play video by \(profile?.bestDisplayName ?? "author")")
    }

    /// Bottom scrim (Flutter `_ExploreTileFooter` parity).
    private var exploreFooter: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !caption.isEmpty {
                Text(caption)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
            }
            HStack(spacing: 4) {
                identityRow
                Spacer(minLength: 4)
                if likeCount > 0 {
                    HStack(spacing: 2) {
                        AppIcons.image(for: AppIcons.heart)
                            .font(.system(size: 11))
                            .foregroundStyle(.white.opacity(0.9))
                        Text(rules.formatCount(Int64(likeCount)))
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(.white.opacity(0.9))
                    }
                }
            }
        }
        .padding(6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [.clear, .black.opacity(0.3), .black.opacity(0.85)],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    /// Author identity row: hex avatar + ⚡ badge + name + ✓ NIP-05;
    /// taps open the profile when a handler is provided.
    @ViewBuilder
    private var identityRow: some View {
        let name = profile?.bestDisplayName ?? FeedFormat.shortPubkey(note.pubkey)
        let row = HStack(spacing: 5) {
            PubkeyAvatarView(
                pubkey: note.pubkey,
                size: 20,
                picture: profile?.picture,
                label: profile?.bestDisplayName,
                hasLightning: !(profile?.lud16?.isEmpty ?? true)
            )
            Text(name)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(.white)
                .lineLimit(1)
            if let nip05 = profile?.nip05, !nip05.isEmpty {
                Image(systemName: "checkmark.seal.fill")
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.accent)
            }
        }
        if let onOpenAuthor {
            Button(action: onOpenAuthor) { row }
                .buttonStyle(.plain)
        } else {
            row
        }
    }
}

/// Trailing grid tile while the next relay page walks (Flutter
/// `_ExploreLoadingTile` parity).
private struct ExploreLoadingTile: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 8)
            .fill(Color.white.opacity(0.05))
            .overlay {
                ProgressView()
                    .controlSize(.small)
                    .tint(BitOSTheme.textTertiary)
            }
            .aspectRatio(9.0 / 16.0, contentMode: .fit)
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
    let onReveal: () -> Void
    /** Settled-page playback head, polled HERE: a root-level 2 Hz tick
     *  invalidated the whole BitzView tree twice a second (§ poll fix). */
    @State private var positionMs: Int64 = 0
    @State private var durationMs: Int64 = 0
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
    /** External-link tap → confirm sheet (owned by the parent). */
    var onOpenExternalLink: (String) -> Void = { _ in }
    /** Profile-mention tap → the mentioned user's profile (not the author). */
    var onOpenMentionProfile: ((String) -> Void)? = nil
    /** Horizontal swipe on the media: `true` = leftward (advance). */
    var onSwipe: (Bool) -> Void = { _ in }
    /** NIP-27 token JSON for the rich caption (shared tokenizer). */
    var richJson: String = "[]"
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
                        // TikTok-style horizontal swipe (mode cycling; final
                        // left swipe opens the creator). Simultaneous so the
                        // vertical pager and tap/hold gestures keep working.
                        .simultaneousGesture(
                            DragGesture(minimumDistance: 24)
                                .onEnded { value in
                                    let h = value.translation.width
                                    guard abs(h) > 60, abs(h) > abs(value.translation.height) * 1.5 else { return }
                                    onSwipe(h < 0)
                                }
                        )
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
                    BitzPosterImage(url: note.video?.posterUrl, fillsFrame: true)
                        .blur(radius: 32)
                        .scaleEffect(1.08)
                    Color.black.opacity(0.72)
                    BitzSensitiveGlassGate(onReveal: onReveal)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
        .task(id: isSettled) {
            // Playback-head polling for THIS page's scrubber. Local state
            // keeps the 2 Hz invalidation scoped to this page — a root-level
            // tick re-evaluated the entire BitzView tree twice per second.
            guard isSettled else {
                positionMs = 0
                durationMs = 0
                return
            }
            while !Task.isCancelled {
                positionMs = pool.positionMs(noteId: note.id)
                durationMs = pool.durationMs(noteId: note.id)
                try? await Task.sleep(nanoseconds: 500_000_000)
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
                            picture: profile?.picture,
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
            // Rich body: NIP-27 entities + external links tappable (white);
            // bare media links render as tiles and disappear from the body.
            RichTextView(
                json: richJson,
                onOpenProfile: { onOpenMentionProfile?($0) },
                color: .white,
                lineLimit: 3,
                hiddenMediaUrls: Set(note.mediaUrls),
                onOpenLink: { onOpenExternalLink($0) }
            )
            if !note.hashtags.isEmpty {
                Text(note.hashtags.prefix(4).map { "#\($0)" }.joined(separator: " "))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(BitOSTheme.accent)
            }
        }
        // Clean chrome (user decision 2026-08-29): no black scrim behind
        // the caption — the text stands on the media directly.
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, BitOSTheme.Spacing.base)
        .padding(.vertical, BitOSTheme.Spacing.lg)
    }

    private var rail: some View {
        VStack(spacing: BitOSTheme.Spacing.lg) {
            // User decision 2026-08-29: [like · comment · repost · zap ·
            // bookmark] after Remix/Chain; Share lives in the ⋯ sheet.
            railButton(AppIcons.sparkles, "Remix", tint: Color(red: 0.545, green: 0.361, blue: 0.965), action: onRemix)
            // Chain: only when this note declares a remix source (web parity).
            if note.remixOfEventId != nil {
                railButton(AppIcons.appsGrid, "Chain", tint: .white, action: onChain)
            }
            railButton(
                actions.liked.contains(note.id) ? AppIcons.heartFill : AppIcons.heart,
                railCounts.like,
                tint: actions.liked.contains(note.id) ? BitOSTheme.like : .white,
                action: onLike
            )
            railButton(AppIcons.comment, railCounts.comment, tint: .white, action: onComment)
            railButton(AppIcons.repost, railCounts.repost, tint: .white, action: onRepost)
            railButton(AppIcons.zap, railCounts.zap, tint: BitOSTheme.zap, action: onZap)
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
        // Clean chrome (user decision 2026-08-29): no black strip behind
        // the compact controls.
    }
}

/// Full-height safety decision for the Bitz player. Feed cards use the small
/// reusable cover; the player needs an explicit glass panel over its viewport.
private struct BitzSensitiveGlassGate: View {
    let onReveal: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            AppIcons.image(for: AppIcons.eyeClosed)
                .font(.system(size: 42, weight: .medium))
                .foregroundStyle(BitOSTheme.textSecondary)
            Text("Sensitive video")
                .font(.title3.weight(.heavy))
                .foregroundStyle(.white)
                .padding(.top, 16)
            Text("This video may contain sensitive content. It will not play until you choose to show it.")
                .font(.subheadline)
                .foregroundStyle(BitOSTheme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.top, 10)
            Button("Show video", action: onReveal)
                .font(.subheadline.weight(.bold))
                .buttonStyle(.borderedProminent)
                .tint(BitOSTheme.accent)
                .padding(.top, 22)
        }
        .padding(.horizontal, 28)
        .padding(.vertical, 30)
        .frame(maxWidth: 330, minHeight: 236)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .stroke(.white.opacity(0.18), lineWidth: 1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Sensitive video hidden. Show video button.")
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
            HStack(spacing: BitOSTheme.Spacing.sm) {
                BitosSearchField("Search Bitz", text: $query, focus: $focused)
                    .onSubmit { focused = false }
                Button("Cancel", action: onDismiss)
                    .font(.system(size: 14, weight: .semibold))
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
                                likeCount: environment.feedStore.tallies[note.id]?.reactions ?? 0,
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
            // SearchStore applies the shared 400 ms relay debounce. Bitz
            // searches the standard media kinds only (discovery/query
            // standard — never kind-1).
            environment.searchStore.search(value, scope: .bitzMedia)
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
struct ShareSheet: UIViewControllerRepresentable {
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
                SheetCloseButton(action: onClose)
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
                    picture: step.pubkey.flatMap { store.profiles[$0]?.picture },
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

private enum BitzPosterPurpose {
    case grid
    case screen

    @MainActor
    var maxPixelSize: CGFloat {
        let scale = UIScreen.main.scale
        switch self {
        case .grid: return UIScreen.main.bounds.width * scale * 16 / 27
        case .screen: return max(UIScreen.main.bounds.width, UIScreen.main.bounds.height) * scale
        }
    }
}

private struct BitzPosterImage: View {
    let url: String?
    var purpose: BitzPosterPurpose = .screen
    /** Used only behind the full-screen sensitive-content gate. */
    var fillsFrame = false
    @Environment(AppEnvironment.self) private var environment
    @State private var image: UIImage?
    /** URL currently displayed in [image] — a page rebind keeps the last
     * poster visible until the next one decodes (no flash-to-black). */
    @State private var loadedUrl: String?

    var body: some View {
        ZStack {
            if purpose == .grid {
                LinearGradient(colors: [BitOSTheme.surface, BitOSTheme.surfaceElevated], startPoint: .topLeading, endPoint: .bottomTrailing)
            } else {
                Color.black
            }
            // Explore grid placeholder (Android `PosterImage` parity): a
            // relay page lands as ONE state batch but each poster decodes
            // on its own — centered progress per tile says "loading"
            // instead of empty slabs popping in one at a time.
            if image == nil && purpose == .grid {
                if url != nil {
                    ProgressView()
                        .tint(.white.opacity(0.6))
                } else {
                    Image(systemName: "play.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(.white.opacity(0.8))
                }
            }
            if let image {
                if purpose == .grid || fillsFrame {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                }
            }
        }
        .clipped()
        .task(id: url) {
            guard let url else {
                image = nil
                loadedUrl = nil
                return
            }
            // Cache-hit or not, the previous poster stays up while the new
            // one loads (audit U3): only a real URL change swaps content.
            let loaded = await environment.posterImages.image(urlString: url, maxPixelSize: purpose.maxPixelSize)
            guard url == self.url else { return }
            if let loaded {
                image = loaded
                loadedUrl = url
            } else if loadedUrl != url {
                image = nil
                loadedUrl = nil
            }
        }
    }
}
