import SwiftUI

/// Author profile sheet: header with follow/unfollow + the author's notes.
struct AuthorProfileSheet: View {
    let authorPubkey: String
    let onClose: () -> Void
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity

    var body: some View {
        NavigationStack {
            content
                .padding(BitOSTheme.Spacing.screen)
                .background(BitOSTheme.background)
                .navigationTitle("Profile")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Close") { onClose() } }
                }
        }
        .preferredColorScheme(.dark)
        .onAppear { environment.authorStore.open(authorPubkey: authorPubkey) }
        .onDisappear { environment.authorStore.close() }
    }

    @ViewBuilder
    private var content: some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
            ProfileHeader

            if environment.authorStore.isLoading && environment.authorStore.notes.isEmpty {
                ProgressView().tint(BitOSTheme.accent).padding(BitOSTheme.Spacing.xl)
            } else if environment.authorStore.notes.isEmpty {
                Text("No notes yet, or relays haven't returned this author's posts.")
                    .font(.footnote)
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(BitOSTheme.Spacing.xl)
            } else {
                Text("Notes (\(environment.authorStore.notes.count))")
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity, alignment: .leading)
                ScrollView {
                    LazyVStack(spacing: BitOSTheme.Spacing.sm) {
                        ForEach(environment.authorStore.notes) { note in
                            AuthorNoteCard(note: note)
                        }
                    }
                }
            }
        }
    }

    private var ProfileHeader: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            HStack(spacing: BitOSTheme.Spacing.md) {
                PubkeyAvatarView(pubkey: authorPubkey, size: 64)
                VStack(alignment: .leading, spacing: 2) {
                    Text(environment.authorStore.profile?.bestDisplayName ?? FeedFormat.shortPubkey(authorPubkey))
                        .font(.title3.weight(.bold))
                        .lineLimit(1)
                    if let nip05 = environment.authorStore.profile?.nip05 {
                        Text(nip05).font(.caption).foregroundStyle(BitOSTheme.accent)
                    }
                    Text(FeedFormat.shortPubkey(authorPubkey))
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.textTertiary)
                }
                Spacer()
                Button(
                    environment.feedStore.following.contains(authorPubkey) ? "Following ✓" : "Follow"
                ) {
                    if let updated = environment.feedStore.applyFollowChange(
                        author: authorPubkey,
                        add: !environment.feedStore.following.contains(authorPubkey)
                    ) {
                        guard identity.account != nil else { return }
                        Task { await environment.notePublisher.publishFollowList(follows: updated) }
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(BitOSTheme.accent)
            }
            if let about = environment.authorStore.profile?.about {
                Text(about).font(.footnote).foregroundStyle(BitOSTheme.textSecondary).lineLimit(3)
            }
            if let lud16 = environment.authorStore.profile?.lud16 {
                Text("⚡ \(lud16)").font(.caption2).foregroundStyle(BitOSTheme.zap)
            }
        }
        .padding(BitOSTheme.Spacing.base)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(BitOSTheme.surface))
    }
}

private struct AuthorNoteCard: View {
    let note: FeedNote

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(FeedFormat.timeAgo(createdAt: note.createdAt))
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textTertiary)
            Text(note.content)
                .font(.subheadline)
                .lineLimit(3)
            if note.video != nil {
                Text("🎬 video").font(.caption2).foregroundStyle(Color(red: 0.02, green: 0.71, blue: 0.83))
            }
        }
        .padding(BitOSTheme.Spacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(BitOSTheme.surfaceElevated))
    }
}
