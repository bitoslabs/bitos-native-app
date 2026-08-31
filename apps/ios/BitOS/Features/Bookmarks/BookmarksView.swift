import SwiftUI

/**
 * APP-015 Bookmarks page (spec §3.15): the account's NIP-51 kind-30003
 * saved notes as compact rows, newest-saved first. Relay re-fetch on open
 * (ids REQ for saved notes outside the feed window); tap opens the thread;
 * the trailing action removes the bookmark (optimistic + list publish).
 */
struct BookmarksView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss

    @State private var threadTarget: FeedNote?

    var body: some View {
        NavigationStack {
            Group {
                if environment.feedStore.bookmarkedIds.isEmpty {
                    emptyState
                } else {
                    list
                }
            }
            .navigationTitle("Saved")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .foregroundStyle(BitOSTheme.accent)
                }
            }
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
        // Spec: live relay re-fetch every time the page opens.
        .task { environment.feedStore.loadBookmarked() }
        .sheet(item: $threadTarget) { target in
            CommentSheet(
                note: target,
                store: environment.feedStore,
                publisher: environment.notePublisher,
                onClose: { threadTarget = nil }
            )
            .presentationDetents([.medium, .large])
        }
    }

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: BitOSTheme.Spacing.xs) {
                ForEach(environment.feedStore.bookmarkedNotes) { note in
                    row(note)
                }
                let missing = environment.feedStore.bookmarkedIds.count - environment.feedStore.bookmarkedNotes.count
                if missing > 0 {
                    HStack(spacing: BitOSTheme.Spacing.sm) {
                        ProgressView().tint(BitOSTheme.accent)
                        Text("Loading \(missing) saved note\(missing == 1 ? "" : "s")…")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(BitOSTheme.Spacing.md)
                }
            }
            .padding(.vertical, BitOSTheme.Spacing.sm)
        }
        .background(BitOSTheme.background)
    }

    private func row(_ note: FeedNote) -> some View {
        Button {
            threadTarget = note
        } label: {
            HStack(alignment: .top, spacing: BitOSTheme.Spacing.sm) {
                PubkeyAvatarView(
                    pubkey: note.pubkey,
                    size: 28,
                    label: environment.feedStore.profiles[note.pubkey]?.bestDisplayName,
                    hasLightning: !(environment.feedStore.profiles[note.pubkey]?.lud16 ?? "").isEmpty
                )
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        Text(environment.feedStore.profiles[note.pubkey]?.bestDisplayName
                             ?? FeedFormat.shortPubkey(note.pubkey))
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(BitOSTheme.textPrimary)
                            .lineLimit(1)
                        if !(environment.feedStore.profiles[note.pubkey]?.nip05 ?? "").isEmpty {
                            AppIcons.image(for: AppIcons.checkCircle)
                                .font(.caption2)
                                .foregroundStyle(BitOSTheme.accent)
                                .accessibilityLabel("NIP-05 identity claim")
                        }
                        Text(FeedFormat.timeAgo(createdAt: note.createdAt))
                            .font(.caption2)
                            .foregroundStyle(BitOSTheme.textTertiary)
                    }
                    Text(note.content)
                        .font(.footnote)
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: BitOSTheme.Spacing.xs)
                Button {
                    toggleBookmark(note)
                } label: {
                    AppIcons.image(for: AppIcons.bookmarkFill)
                        .font(.subheadline)
                        .foregroundStyle(BitOSTheme.bookmark)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Remove bookmark")
            }
            .padding(BitOSTheme.Spacing.base)
            .background(BitOSTheme.surface, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Open saved note")
        .padding(.horizontal, BitOSTheme.Spacing.base)
    }

    private func toggleBookmark(_ note: FeedNote) {
        if let updated = environment.feedStore.applyBookmarkChange(
            eventId: note.id,
            add: !environment.feedStore.bookmarkedIds.contains(note.id)
        ) {
            guard environment.identityStore.account != nil else { return }
            Task { await environment.notePublisher.publishBookmarkList(eventIds: updated) }
        } else {
            environment.feedStore.localActions.toggleBookmark(note.id)
        }
    }

    private var emptyState: some View {
        VStack(spacing: BitOSTheme.Spacing.sm) {
            AppIcons.image(for: AppIcons.bookmark)
                .font(.title)
                .foregroundStyle(BitOSTheme.textTertiary)
            Text("Nothing saved yet")
                .font(.headline)
                .foregroundStyle(BitOSTheme.textPrimary)
            Text("Bookmark notes from any card or reel to find them here.")
                .font(.caption)
                .foregroundStyle(BitOSTheme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(BitOSTheme.Spacing.xxl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
