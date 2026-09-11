import BusinessCore
import SwiftUI

private let topics = ["bitcoin", "lightning", "nostr", "memes", "video"]

/// Discover surface (SOC-004): NIP-50 relay search + npub creator
/// resolution + topic chips feeding the same pipeline.
struct DiscoverView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    @Environment(SettingsStore.self) private var settings

    @State private var input = ""
    // APP-010 results tabs: Posts · People · Hashtags (shared fan-in).
    @State private var tab = 0
    @FocusState private var searchFocused: Bool

    /// APP-010 fan-in rows derived through the shared bridge rule. Memoized
    /// in state (Android `remember(results, profiles)` parity): the JSON
    /// round-trip runs once per data change, never during a body
    /// evaluation — typing re-renders for free.
    @State private var peopleRows: [PersonRow] = []
    @State private var hashtagHits: [HashtagRow] = []

    // Card interaction targets (home parity: search results are real feed
    // cards, so like/comment/repost/zap/bookmark/author all work here).
    @State private var authorTarget: String?
    @State private var zapTarget: FeedNote?
    @State private var commentTarget: FeedNote?
    @State private var externalLink: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                searchBar
                if input.trimmingCharacters(in: .whitespaces).isEmpty {
                    topicChips { topic in input = "#\(topic)" }
                } else {
                    VStack(spacing: 0) {
                        tabRow
                        switch tab {
                        case 1: PeopleTab(people: peopleRows)
                        case 2: HashtagsTab(hits: hashtagHits) { input = "#\($0)" }
                        default: postsList
                        }
                    }
                }
            }
            .background(BitOSTheme.background)
            .navigationTitle("Discover")
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
        // Fan-in rows re-derive when the relay stream or the query changes;
        // the signature is cheap (ids + profile render fields), the bridge
        // round-trip it guards is not.
        .task(id: fanInSignature) {
            peopleRows = Self.derivePeople(
                results: displayedResults,
                profiles: displayedProfiles
            )
            hashtagHits = Self.deriveHashtags(results: displayedResults)
        }
        .onChange(of: input) { _, newValue in
            // SearchStore applies the shared 400 ms relay debounce.
            environment.searchStore.search(newValue)
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
        // External-link confirm: the browser only opens on an explicit Open.
        .sheet(isPresented: Binding(
            get: { externalLink != nil },
            set: { if !$0 { externalLink = nil } }
        )) {
            if let externalLink {
                ExternalLinkConfirmSheet(url: externalLink)
            }
        }
    }

    // MARK: - Search bar

    /// The normal feed window is the local search history. The search store
    /// contributes matching events that arrive after the query starts.
    private var displayedResults: [FeedNote] {
        let normalized = input.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let cached = environment.feedStore.notes.filter { note in
            guard [1, 21, 22].contains(note.kind) else { return false }
            if let pubkey = environment.searchStore.resolvedNpub {
                return note.pubkey == pubkey
            }
            if normalized.hasPrefix("#") {
                let tag = String(normalized.dropFirst())
                return !tag.isEmpty && note.hashtags.contains { $0.lowercased() == tag }
            }
            return note.content.lowercased().contains(normalized)
                || note.hashtags.contains { $0.lowercased().contains(normalized) }
        }
        return (environment.searchStore.results + cached)
            .reduce(into: [String: FeedNote]()) { $0[$1.id] = $1 }
            .values
            .sorted { $0.createdAt > $1.createdAt }
    }

    private var displayedProfiles: [String: ProfileMetadata] {
        environment.feedStore.profiles.merging(environment.searchStore.profiles) { _, searchProfile in searchProfile }
    }

    private var searchBar: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            BitosSearchField("Search notes, #hashtags, npub…", text: $input, focus: $searchFocused)
                .onSubmit { searchFocused = false }
            if isSearchActive {
                Button("Cancel", action: cancelSearch)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(BitOSTheme.accent)
            }
        }
        .padding(.horizontal, BitOSTheme.Spacing.screen)
        .padding(.vertical, BitOSTheme.Spacing.sm)
    }

    private var isSearchActive: Bool { searchFocused || !input.isEmpty }

    /// System search-bar cancel: reset the query (which also resets the
    /// store and streams), drop focus and return to the topic chips.
    private func cancelSearch() {
        input = ""
        searchFocused = false
        tab = 0
    }

    // MARK: - Results tabs

    private var tabRow: some View {
        let tabs: [(String, Int)] = [
            ("Posts", displayedResults.count),
            ("People", peopleRows.count),
            ("Hashtags", hashtagHits.count),
        ]
        return HStack(spacing: BitOSTheme.Spacing.md) {
            ForEach(Array(tabs.enumerated()), id: \.offset) { index, entry in
                resultsTab(title: entry.0, count: entry.1, index: index)
            }
            Spacer()
        }
        .padding(.horizontal, BitOSTheme.Spacing.screen)
        .padding(.vertical, BitOSTheme.Spacing.xs)
    }

    /// Home `timelineTab` chrome (accent underline + selected trait) so the
    /// result tabs read as the same system control.
    private func resultsTab(title: String, count: Int, index: Int) -> some View {
        let isSelected = tab == index
        return Button {
            tab = index
        } label: {
            VStack(spacing: 4) {
                Text("\(title) \(count)")
                    .font(.system(size: 13, weight: isSelected ? .heavy : .semibold))
                    .foregroundStyle(isSelected ? BitOSTheme.accent : BitOSTheme.textSecondary)
                Rectangle()
                    .fill(isSelected ? BitOSTheme.accent : .clear)
                    .frame(height: 2)
            }
            .contentShape(Rectangle())
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    /// Posts tab: the resolved npub creator (when any) plus the same feed
    /// card the home timeline uses — full like/comment/repost/zap/bookmark
    /// interactions against the shared feed store.
    private var postsList: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if let npub = environment.searchStore.resolvedNpub {
                    Button {
                        authorTarget = npub
                    } label: {
                        CreatorCard(
                            pubkey: npub,
                            profile: displayedProfiles[npub]
                                ?? environment.feedStore.profiles[npub]
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Open creator profile")
                    // Inset chip above the full-bleed feed rows.
                    .padding(.horizontal, BitOSTheme.Spacing.screen)
                    .padding(.bottom, BitOSTheme.Spacing.sm)
                }
                if displayedResults.isEmpty {
                    resultsEmptyState
                }
                ForEach(displayedResults) { note in
                    resultCardRow(note)
                }
            }
            .padding(.top, BitOSTheme.Spacing.sm)
            .padding(.bottom, BitOSTheme.Spacing.screen)
        }
        .scrollDismissesKeyboard(.immediately)
    }

    /// Debounce-aware empty states: nothing while the 400 ms relay debounce
    /// settles, spinner once the query is in flight, plate only after the
    /// search actually came back empty.
    @ViewBuilder
    private var resultsEmptyState: some View {
        if environment.searchStore.isSearching {
            ProgressView().tint(BitOSTheme.accent).padding(BitOSTheme.Spacing.xl)
        } else if environment.searchStore.hasSearched {
            Text("No results. Relays may not support search or the query is too narrow.")
                .font(.footnote)
                .foregroundStyle(BitOSTheme.textSecondary)
                .padding(BitOSTheme.Spacing.xl)
                .multilineTextAlignment(.center)
        }
    }

    /// Type-checker split: one card per function (§ pagerPage fix class).
    private func resultCardRow(_ note: FeedNote) -> some View {
        FeedNoteCard(
            note: note,
            profile: environment.feedStore.profiles[note.pubkey],
            profiles: environment.feedStore.profiles,
            actions: environment.feedStore.localActions,
            isBookmarked: environment.feedStore.bookmarkedIds.contains(note.id)
                || environment.feedStore.localActions.bookmarked.contains(note.id),
            richJson: environment.feedStore.richTokens(for: note.content),
            onLike: { like(note) },
            onBookmark: { toggleBookmark(note) },
            onComment: { commentTarget = note },
            onRepost: { repost(note) },
            onZap: { zapTarget = note },
            onAuthor: { authorTarget = note.pubkey },
            // Mention taps open the mentioned user's sheet, not the author's.
            onOpenMentionProfile: { authorTarget = $0 },
            onOpenExternalLink: { externalLink = $0 },
            // APP-008 poll voting.
            pollTally: environment.feedStore.pollTallies[note.id],
            canVotePoll: identity.account != nil,
            onLoadPollVotes: { environment.feedStore.loadPollVotes(targetEventId: note.id) },
            onVotePoll: { optionIndex in
                environment.feedStore.applyOptimisticPollVote(pollId: note.id, optionIndex: optionIndex)
                Task { await environment.notePublisher.publishPollVote(targetEventId: note.id, optionIndex: optionIndex) }
            }
        )
        // Home parity: hairline divider between cards.
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(BitOSTheme.divider)
                .frame(height: 0.5)
        }
    }

    // MARK: - Card actions (home `FeedStore` wiring parity)

    private func like(_ note: FeedNote) {
        let turningOn = !environment.feedStore.localActions.liked.contains(note.id)
        environment.feedStore.localActions.toggleLike(note.id)
        // Signed accounts publish a real kind-7 on like; an unlike deletes
        // my reaction event (kind-5, web `unlikeNote` parity).
        guard identity.account != nil else { return }
        if turningOn {
            Task { await environment.notePublisher.publishReaction(targetEventId: note.id, targetPubkey: note.pubkey) }
        } else if let reactionId = environment.feedStore.myReactionEventIds[note.id] {
            Task { await environment.notePublisher.publishDeletion(targetEventIds: [reactionId]) }
        }
    }

    private func toggleBookmark(_ note: FeedNote) {
        if let updated = environment.feedStore.applyBookmarkChange(eventId: note.id, add: !environment.feedStore.bookmarkedIds.contains(note.id)) {
            guard identity.account != nil else { return }
            Task { await environment.notePublisher.publishBookmarkList(eventIds: updated) }
        } else {
            environment.feedStore.localActions.toggleBookmark(note.id)
        }
    }

    private func repost(_ note: FeedNote) {
        guard identity.account != nil else { return }
        Task { await environment.notePublisher.publishRepost(targetEventId: note.id, targetPubkey: note.pubkey) }
    }

    // MARK: - APP-010 fan-in derivation (memoized)

    /// Data signature guarding the bridge round-trips: result ids plus the
    /// profile fields the rows render. Cheap to rebuild every render; the
    /// JSON derivation it gates runs once per actual change.
    private var fanInSignature: String {
        let ids = displayedResults.map(\.id).joined(separator: ",")
        let profileDigest = displayedProfiles.values
            .map { "\($0.pubkey)|\($0.name ?? "")|\($0.displayName ?? "")|\($0.nip05 ?? "")" }
            .sorted()
            .joined(separator: ",")
        return ids + "#" + profileDigest
    }

    private static func derivePeople(results: [FeedNote], profiles: [String: ProfileMetadata]) -> [PersonRow] {
        let resultsJson = jsonDump(results.map { note -> [String: Any] in
            ["id": note.id, "pubkey": note.pubkey, "hashtags": note.hashtags]
        })
        let profilesJson = jsonDump(profiles.values.map { profile -> [String: Any] in
            var obj: [String: Any] = ["pubkey": profile.pubkey]
            if let n = profile.name { obj["name"] = n }
            if let d = profile.displayName { obj["displayName"] = d }
            if let p = profile.picture { obj["picture"] = p }
            if let n5 = profile.nip05 { obj["nip05"] = n5 }
            return obj
        })
        guard let json = (BusinessCoreBridge().searchPeopleJson(resultsJson: resultsJson, profilesJson: profilesJson) as String?),
              let data = json.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return array.compactMap { obj in
            guard let pubkey = obj["pubkey"] as? String else { return nil }
            return PersonRow(
                pubkey: pubkey,
                name: (obj["name"] as? String) ?? "",
                nip05: (obj["nip05"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                notes: (obj["notes"] as? NSNumber)?.intValue ?? 0
            )
        }
    }

    private static func deriveHashtags(results: [FeedNote]) -> [HashtagRow] {
        let resultsJson = jsonDump(results.map { note -> [String: Any] in
            ["id": note.id, "pubkey": note.pubkey, "hashtags": note.hashtags]
        })
        guard let json = (BusinessCoreBridge().searchHashtagsJson(resultsJson: resultsJson) as String?),
              let data = json.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return array.compactMap { obj in
            guard let tag = obj["tag"] as? String else { return nil }
            return HashtagRow(tag: tag, count: (obj["count"] as? NSNumber)?.intValue ?? 0)
        }
    }

    private static func jsonDump(_ objects: [[String: Any]]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: objects),
              let json = String(data: data, encoding: .utf8) else { return "[]" }
        return json
    }

    private func topicChips(onTopic: @escaping (String) -> Void) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
                Text("Explore topics").font(.headline).padding(.top)
                ForEach(topics, id: \.self) { topic in
                    Button {
                        onTopic(topic)
                    } label: {
                        HStack(spacing: BitOSTheme.Spacing.md) {
                            Text("#")
                                .font(.title3.weight(.semibold))
                                .foregroundStyle(BitOSTheme.accent)
                                .frame(width: 36, height: 36)
                                .background(BitOSTheme.accentContainer)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                            VStack(alignment: .leading, spacing: 2) {
                                Text("#\(topic)").font(.subheadline.weight(.semibold))
                                Text("Search across connected relays")
                                    .font(.caption)
                                    .foregroundStyle(BitOSTheme.textSecondary)
                            }
                            Spacer()
                            Button {
                                let updated = environment.hashtagFollows.toggle(topic)
                                Task { await environment.notePublisher.publishInterestSet(hashtags: updated) }
                            } label: {
                                Text(environment.hashtagFollows.isFollowed(topic) ? "Following ✓" : "Follow")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundStyle(
                                        environment.hashtagFollows.isFollowed(topic)
                                            ? BitOSTheme.textTertiary : BitOSTheme.accent
                                    )
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Follow hashtag \(topic)")
                        }
                        .padding(BitOSTheme.Spacing.base)
                        .background(RoundedRectangle(cornerRadius: 14).fill(BitOSTheme.surface))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Search hashtag \(topic)")
                }
                Text("Search runs across your relays; text matching may vary by relay.")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textTertiary)
                    .padding(.top, BitOSTheme.Spacing.base)
            }
            .padding(BitOSTheme.Spacing.screen)
        }
        .scrollDismissesKeyboard(.immediately)
    }
}

private struct AuthorTarget: Identifiable { let id: String }

private struct CreatorCard: View {
    let pubkey: String
    let profile: ProfileMetadata?

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.md) {
            PubkeyAvatarView(pubkey: pubkey, size: 48, picture: profile?.picture, label: profile?.bestDisplayName)
            VStack(alignment: .leading, spacing: 2) {
                Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(pubkey))
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(BitOSTheme.accent)
                if let nip05 = profile?.nip05 {
                    Text(nip05).font(.caption).foregroundStyle(BitOSTheme.textSecondary)
                }
                Text("Creator").font(.caption2).foregroundStyle(BitOSTheme.textTertiary)
            }
            Spacer()
        }
        .padding(BitOSTheme.Spacing.base)
        .background(RoundedRectangle(cornerRadius: 14).fill(BitOSTheme.accentContainer))
    }
}

// MARK: - APP-010 results tabs

struct PersonRow: Identifiable, Equatable {
    let pubkey: String
    let name: String
    let nip05: String?
    let notes: Int
    var id: String { pubkey }
}

struct HashtagRow: Identifiable, Equatable {
    let tag: String
    let count: Int
    var id: String { tag }
}

private struct PeopleTab: View {
    let people: [PersonRow]

    var body: some View {
        ScrollView {
            LazyVStack(spacing: BitOSTheme.Spacing.sm) {
                ForEach(people) { person in
                    HStack(spacing: BitOSTheme.Spacing.md) {
                        PubkeyAvatarView(pubkey: person.pubkey, size: 44, label: person.name)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(person.name.isEmpty ? FeedFormat.shortPubkey(person.pubkey) : person.name)
                                .font(.system(size: 14, weight: .bold))
                            if let nip05 = person.nip05 {
                                Text(nip05).font(.system(size: 11)).foregroundStyle(BitOSTheme.accent)
                            }
                            Text("\(person.notes) note\(person.notes == 1 ? "" : "s")")
                                .font(.system(size: 11))
                                .foregroundStyle(BitOSTheme.textTertiary)
                        }
                        Spacer()
                    }
                    .padding(BitOSTheme.Spacing.md)
                    .background(RoundedRectangle(cornerRadius: 14).fill(BitOSTheme.surface))
                }
            }
            .padding(BitOSTheme.Spacing.screen)
        }
    }
}

private struct HashtagsTab: View {
    let hits: [HashtagRow]
    let onPick: (String) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(spacing: BitOSTheme.Spacing.sm) {
                ForEach(hits) { hit in
                    Button { onPick(hit.tag) } label: {
                        HStack {
                            Text("#\(hit.tag)")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(BitOSTheme.accent)
                            Spacer()
                            Text("\(hit.count) note\(hit.count == 1 ? "" : "s")")
                                .font(.system(size: 11))
                                .foregroundStyle(BitOSTheme.textTertiary)
                        }
                        .padding(BitOSTheme.Spacing.md)
                        .background(RoundedRectangle(cornerRadius: 14).fill(BitOSTheme.surface))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Search hashtag \(hit.tag)")
                }
            }
            .padding(BitOSTheme.Spacing.screen)
        }
    }
}
