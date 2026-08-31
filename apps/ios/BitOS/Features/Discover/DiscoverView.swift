import BusinessCore
import SwiftUI

private let topics = ["bitcoin", "lightning", "nostr", "memes", "video"]

/// Discover surface (SOC-004): NIP-50 relay search + npub creator
/// resolution + topic chips feeding the same pipeline.
struct DiscoverView: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var input = ""
    // APP-010 results tabs: Posts · People · Hashtags (shared fan-in).
    @State private var tab = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                TextField("Search notes, #hashtags, npub…", text: $input)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .submitLabel(.search)
                    .padding(BitOSTheme.Spacing.screen)
                    .onChange(of: input) { _, newValue in
                        environment.searchStore.search(newValue)
                    }

                if input.trimmingCharacters(in: .whitespaces).isEmpty {
                    TopicChips { topic in input = "#\(topic)" }
                } else {
                    VStack(spacing: 0) {
                        tabRow
                        switch tab {
                        case 1: PeopleTab(people: peopleRows)
                        case 2: HashtagsTab(hits: hashtagHits) { input = "#\($0)" }
                        default: SearchResults
                        }
                    }
                }
            }
            .background(BitOSTheme.background)
            .navigationTitle("Discover")
        }
        .preferredColorScheme(.dark)
    }

    private var resultsJson: String {
        let items: [String] = environment.searchStore.results.map { note in
            let obj: [String: Any] = ["id": note.id, "pubkey": note.pubkey, "hashtags": note.hashtags]
            if let data = try? JSONSerialization.data(withJSONObject: obj),
               let json = String(data: data, encoding: .utf8) {
                return json
            }
            return ""
        }.filter { !$0.isEmpty }
        return "[" + items.joined(separator: ",") + "]"
    }

    private var profilesJson: String {
        let items: [String] = environment.searchStore.profiles.values.map { profile in
            var obj: [String: Any] = ["pubkey": profile.pubkey]
            if let n = profile.name { obj["name"] = n }
            if let d = profile.displayName { obj["displayName"] = d }
            if let p = profile.picture { obj["picture"] = p }
            if let n5 = profile.nip05 { obj["nip05"] = n5 }
            if let data = try? JSONSerialization.data(withJSONObject: obj),
               let json = String(data: data, encoding: .utf8) {
                return json
            }
            return ""
        }.filter { !$0.isEmpty }
        return "[" + items.joined(separator: ",") + "]"
    }

    private var peopleRows: [PersonRow] {
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

    private var hashtagHits: [HashtagRow] {
        guard let json = (BusinessCoreBridge().searchHashtagsJson(resultsJson: resultsJson) as String?),
              let data = json.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return array.compactMap { obj in
            guard let tag = obj["tag"] as? String else { return nil }
            return HashtagRow(tag: tag, count: (obj["count"] as? NSNumber)?.intValue ?? 0)
        }
    }

    private var tabRow: some View {
        let tabs: [(String, Int)] = [
            ("Posts", environment.searchStore.results.count),
            ("People", peopleRows.count),
            ("Hashtags", hashtagHits.count),
        ]
        return HStack(spacing: BitOSTheme.Spacing.md) {
            ForEach(Array(tabs.enumerated()), id: \.offset) { index, entry in
                Button("\(entry.0) \(entry.1)") { tab = index }
                    .font(.system(size: 13, weight: tab == index ? .heavy : .semibold))
                    .foregroundStyle(tab == index ? BitOSTheme.accent : BitOSTheme.textSecondary)
            }
            Spacer()
        }
        .padding(.horizontal, BitOSTheme.Spacing.screen)
        .padding(.vertical, BitOSTheme.Spacing.xs)
    }

    private var SearchResults: some View {
        ScrollView {
            LazyVStack(spacing: BitOSTheme.Spacing.sm) {
                if let npub = environment.searchStore.resolvedNpub {
                    CreatorCard(pubkey: npub, profile: environment.searchStore.profiles[npub])
                }
                if environment.searchStore.isSearching && environment.searchStore.results.isEmpty {
                    ProgressView().tint(BitOSTheme.accent).padding(BitOSTheme.Spacing.xl)
                } else if environment.searchStore.results.isEmpty {
                    Text("No results. Relays may not support search or the query is too narrow.")
                        .font(.footnote)
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .padding(BitOSTheme.Spacing.xl)
                        .multilineTextAlignment(.center)
                }
                ForEach(environment.searchStore.results) { note in
                    SearchCard(note: note, profile: environment.searchStore.profiles[note.pubkey])
                }
            }
            .padding(BitOSTheme.Spacing.screen)
        }
    }

    private func TopicChips(onTopic: @escaping (String) -> Void) -> some View {
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
                Text("Search runs on relays that support NIP-50; results may vary by relay.")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textTertiary)
                    .padding(.top, BitOSTheme.Spacing.base)
            }
            .padding(BitOSTheme.Spacing.screen)
        }
    }
}

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

private struct SearchCard: View {
    let note: FeedNote
    let profile: ProfileMetadata?

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                PubkeyAvatarView(pubkey: note.pubkey, size: 28, picture: profile?.picture, label: profile?.bestDisplayName)
                Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(note.pubkey))
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                Spacer()
                Text(FeedFormat.timeAgo(createdAt: note.createdAt))
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
            Text(note.content)
                .font(.subheadline)
                .foregroundStyle(BitOSTheme.textPrimary)
                .lineLimit(3)
            if note.video != nil {
                Text("🎬 video").font(.caption2).foregroundStyle(Color(red: 0.02, green: 0.71, blue: 0.83))
            }
        }
        .padding(BitOSTheme.Spacing.base)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(BitOSTheme.surface))
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
