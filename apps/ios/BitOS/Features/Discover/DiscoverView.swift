import SwiftUI

private let topics = ["bitcoin", "lightning", "nostr", "memes", "video"]

/// Discover surface (SOC-004): NIP-50 relay search + npub creator
/// resolution + topic chips feeding the same pipeline.
struct DiscoverView: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var input = ""

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
                    SearchResults
                }
            }
            .background(BitOSTheme.background)
            .navigationTitle("Discover")
        }
        .preferredColorScheme(.dark)
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
            PubkeyAvatarView(pubkey: pubkey, size: 48)
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
                PubkeyAvatarView(pubkey: note.pubkey, size: 28)
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
