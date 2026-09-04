import BusinessCore
import SwiftUI

/// Image-only provider card. Tapping stays behind the caller's link-confirm gate.
private struct ExternalVideoPreviewCard: View {
    let preview: ExternalVideoPreview
    let onOpen: (String) -> Void

    var body: some View {
        Button { onOpen(preview.url) } label: {
            VStack(alignment: .leading, spacing: 0) {
                ZStack {
                    RemoteImageView(url: preview.thumbnailUrl)
                    AppIcons.image(for: AppIcons.play)
                        .font(.system(size: 36, weight: .semibold))
                        .foregroundStyle(.white)
                }
                .aspectRatio(16 / 9, contentMode: .fit)
                Text(preview.providerName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
            }
            .background(BitOSTheme.surfaceElevated, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Open \(preview.providerName) video")
    }
}

// MARK: - APP-005: compact feed card (Home tab list parity)

/**
 * The one feed note card, shared by every note-list surface (home list,
 * Discover search results, future saved/hashtag lanes). Pure presentation:
 * values + callbacks in, no stores, relays or publishers — the owning
 * surface wires actions to its own stores. Handlers the surface cannot
 * honor stay `nil` and the corresponding affordance renders inert or
 * hidden (e.g. no ⋯ anchor without an `onMore` host).
 */
struct FeedNoteCard: View {
    @Environment(SettingsStore.self) private var settings
    let note: FeedNote
    let profile: ProfileMetadata?
    var profiles: [String: ProfileMetadata] = [:]
    let actions: LocalActions
    let isBookmarked: Bool
    var richJson: String = "[]"
    let onLike: () -> Void
    let onBookmark: () -> Void
    let onComment: () -> Void
    let onRepost: () -> Void
    let onZap: () -> Void
    let onAuthor: () -> Void
    /** nil = surface hosts no overflow menu → the ⋯ anchor stays hidden. */
    var onMore: ((CGPoint) -> Void)? = nil
    /** note1/nevent1/naddr1 tap → in-place thread open. */
    var onOpenNoteRef: ((String) -> Void)? = nil
    /** Profile-mention tap → the mentioned user's profile (not the author). */
    var onOpenMentionProfile: ((String) -> Void)? = nil
    /** External-link tap → confirm sheet (owned by the parent). */
    var onOpenExternalLink: (String) -> Void = { _ in }
    /** APP-008 poll voting (wired by the owning screen). */
    var pollTally: PollTally? = nil
    var canVotePoll: Bool = false
    var onLoadPollVotes: () -> Void = {}
    var onVotePoll: (Int) -> Void = { _ in }
    @State private var revealed = false
    @State private var lightboxUrl: String?

    /// APP-018 functional setting: compact mode tightens card density.
    private var compact: Bool { settings.state.compactMode }

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 2 : BitOSTheme.Spacing.sm) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Button(action: onAuthor) {
                    PubkeyAvatarView(pubkey: note.pubkey, size: compact ? 28 : 36, picture: profile?.picture, label: profile?.bestDisplayName, hasLightning: !(profile?.lud16?.isEmpty ?? true))
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Open \(profile?.bestDisplayName ?? FeedFormat.shortPubkey(note.pubkey))'s profile")
                Button(action: onAuthor) {
                    VStack(alignment: .leading, spacing: 1) {
                        HStack(spacing: 4) {
                            Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(note.pubkey))
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(BitOSTheme.textPrimary)
                                .lineLimit(1)
                            if !(profile?.nip05?.isEmpty ?? true) {
                                AppIcons.image(for: AppIcons.checkCircle)
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(BitOSTheme.accent)
                                    .accessibilityLabel("NIP-05 identity claim")
                            }
                        }
                        HStack(spacing: 4) {
                            if note.repostedBy != nil {
                                AppIcons.image(for: AppIcons.repost)
                                    .font(.system(size: 10))
                                    .foregroundStyle(BitOSTheme.repost)
                            }
                            RelativeTimeText(createdAt: note.createdAt)
                                .font(.system(size: 11))
                                .foregroundStyle(BitOSTheme.textTertiary)
                        }
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Open \(profile?.bestDisplayName ?? FeedFormat.shortPubkey(note.pubkey))'s profile")
                Spacer()
                if let onMore {
                    AppMenuAnchorButton(symbol: AppIcons.more, tint: BitOSTheme.textSecondary, label: "More options", action: onMore)
                }
            }
            if note.contentWarning && !revealed && settings.state.sensitiveMedia != .show {
                SensitiveCover { revealed = true }
            } else {
                // APP-005: font-scale-safe clamp — Show more/less beyond 8
                // lines; mention names resolve; note refs open in-place.
                ExpandableRichText(
                    json: richJson,
                    onOpenProfile: { onOpenMentionProfile?($0) },
                    resolveMentionName: { hex in
                        profiles[hex]?.bestDisplayName
                    },
                    onOpenNoteRef: { raw in onOpenNoteRef?(raw) }
                )
                if !note.pollOptions.isEmpty {
                    PollOptionsView(
                        noteId: note.id,
                        options: note.pollOptions,
                        tally: pollTally,
                        canVote: canVotePoll,
                        onLoadVotes: onLoadPollVotes,
                        onVote: onVotePoll
                    )
                }
                if !note.mediaUrls.isEmpty || !note.externalVideoPreviews.isEmpty {
                    if settings.state.mediaPreview {
                        ForEach(note.externalVideoPreviews, id: \.url) { preview in
                            ExternalVideoPreviewCard(preview: preview, onOpen: onOpenExternalLink)
                        }
                        if !note.mediaUrls.isEmpty {
                            MediaGrid(urls: note.mediaUrls) { lightboxUrl = $0 }
                        }
                    } else {
                        Text("\(note.mediaUrls.count + note.externalVideoPreviews.count) attachment(s) \u{2014} previews off")
                            .font(.system(size: 12))
                            .foregroundStyle(BitOSTheme.textTertiary)
                    }
                }
            }
            HStack(spacing: BitOSTheme.Spacing.base) {
                // User decision 2026-08-29: order [like · comment · repost ·
                // zap · bookmark]; APP-005 §2.4 scale-bounce + haptic on like.
                let liked = actions.liked.contains(note.id)
                LikeTapIcon(
                    liked: liked,
                    tint: liked ? BitOSTheme.like : BitOSTheme.textSecondary,
                    action: onLike
                )
                cardAction(AppIcons.comment, "Replies", BitOSTheme.reply, onComment)
                cardAction(AppIcons.repost, "Repost", BitOSTheme.repost, onRepost)
                cardAction(AppIcons.zap, "Zap", BitOSTheme.zap, onZap)
                cardAction(isBookmarked ? AppIcons.bookmarkFill : AppIcons.bookmark, isBookmarked ? "Remove bookmark" : "Bookmark", isBookmarked ? BitOSTheme.bookmark : BitOSTheme.textSecondary, onBookmark)
            }
        }
        .padding(.horizontal, BitOSTheme.Spacing.screen)
        .padding(.vertical, BitOSTheme.Spacing.md)
        .sheet(item: Binding(
            get: { lightboxUrl.map { CardLightboxTarget(url: $0) } },
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

private struct CardLightboxTarget: Identifiable {
    let url: String
    var id: String { url }
}

// MARK: - APP-005 clamp + motion primitives (card-local)

/// Bodies collapse beyond 8 lines (line-based so font scaling cannot break
/// the clamp); full-screen card pages never clamp.
struct ExpandableRichText: View {
    let json: String
    var onOpenProfile: ((String) -> Void)?
    /** Mention display-name resolver (profile entities show @name). */
    var resolveMentionName: ((String) -> String?)? = nil
    /** note1/nevent1/naddr1 tap → in-place thread open. */
    var onOpenNoteRef: ((String) -> Void)? = nil

    private static let collapseLines = 8

    @State private var expanded = false
    @State private var canExpand = false
    @State private var clampedHeight: CGFloat = 0
    @State private var fullHeight: CGFloat = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            RichTextView(
                json: json,
                onOpenProfile: onOpenProfile,
                onOpenHashtag: nil,
                resolveMentionName: resolveMentionName,
                onOpenNoteRef: onOpenNoteRef
            )
            .lineLimit(expanded ? nil : Self.collapseLines)
            .background(
                GeometryReader { geo in
                    Color.clear.preference(key: ClampedHeightKey.self, value: geo.size.height)
                }
            )
            // Hidden unlimited-height twin measures the natural height; the
            // probe disappears once the toggle is (or needs to be) offered.
            if !canExpand && !expanded {
                RichTextView(json: json, onOpenProfile: nil, onOpenHashtag: nil)
                    .fixedSize(horizontal: false, vertical: true)
                    .hidden()
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                    .background(
                        GeometryReader { geo in
                            Color.clear.preference(key: FullHeightKey.self, value: geo.size.height)
                        }
                    )
            }
            if canExpand || expanded {
                Button(expanded ? "Show less" : "Show more") {
                    withAnimation(.easeOut(duration: 0.2)) { expanded.toggle() }
                }
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(BitOSTheme.accent)
                .buttonStyle(.plain)
            }
        }
        .onPreferenceChange(ClampedHeightKey.self) { clampedHeight = $0 }
        .onPreferenceChange(FullHeightKey.self) { fullHeight = $0 }
        .onChange(of: clampedHeight) { _, _ in evaluate() }
        .onChange(of: fullHeight) { _, _ in evaluate() }
    }

    private func evaluate() {
        canExpand = fullHeight > clampedHeight + 2
    }
}

private struct ClampedHeightKey: PreferenceKey {
    // Immutable default; never mutated (Swift 6 strict-concurrency note).
    nonisolated(unsafe) static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

private struct FullHeightKey: PreferenceKey {
    nonisolated(unsafe) static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

/// Spec §2.4 like: 300 ms spring scale-bounce (damped ≈ elasticOut) + light
/// haptic on the like tap; unlike stays quiet. Solar heart Linear/Bold.
struct LikeTapIcon: View {
    let liked: Bool
    let tint: Color
    let action: () -> Void
    @State private var likeScale: CGFloat = 1

    var body: some View {
        Button(action: action) {
            AppIcons.image(for: liked ? AppIcons.heartFill : AppIcons.heart)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(tint)
                .frame(width: 36, height: 36)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .scaleEffect(likeScale)
        .accessibilityLabel(liked ? "Unlike" : "Like")
        .onChange(of: liked) { _, isOn in
            guard isOn else { return }
            likeScale = 0.6
            withAnimation(.spring(response: 0.3, dampingFraction: 0.35)) {
                likeScale = 1
            }
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
    }
}

/// APP-008 poll display + voting (web `Poll.svelte` parity): option rows
/// with proportional bars, counts, my-vote highlight, total votes; votes
/// lazy-load once per poll and taps publish a kind-1018.
struct PollOptionsView: View {
    let noteId: String
    let options: [String]
    let tally: PollTally?
    let canVote: Bool
    let onLoadVotes: () -> Void
    let onVote: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(options.enumerated()), id: \.offset) { index, label in
                optionRow(index: index, label: label)
            }
            Text(footer)
                .font(.system(size: 11))
                .foregroundStyle(BitOSTheme.textTertiary)
        }
        .onAppear { onLoadVotes() }
    }

    private var voted: Bool { tally?.myVote != nil }

    private var footer: String {
        guard let tally else { return "Poll · \(options.count) options" }
        let word = tally.total == 1 ? "vote" : "votes"
        return "\(tally.total) \(word)" + (voted ? " · tap an option to change" : "")
    }

    private func optionRow(index: Int, label: String) -> some View {
        let count = tally.flatMap { $0.counts[KotlinInt(int: Int32(index))] }.map { $0.intValue } ?? 0
        let total = tally.map { Int($0.total) } ?? 0
        let fraction = total > 0 ? Double(count) / Double(total) : 0
        let mine = (tally?.myVote).map { $0.intValue == Int32(index) } == true
        return Button {
            guard canVote else { return }
            onVote(index)
        } label: {
            ZStack(alignment: .leading) {
                Capsule().fill(BitOSTheme.surface)
                // Proportional result bar (web parity: fills after voting).
                GeometryReader { geo in
                    Capsule()
                        .fill(mine ? BitOSTheme.accent.opacity(0.30) : BitOSTheme.accent.opacity(0.14))
                        .frame(width: voted ? geo.size.width * max(fraction, 0.02) : 0)
                }
                HStack(spacing: 10) {
                    ZStack {
                        Circle()
                            .strokeBorder(mine ? BitOSTheme.accent : BitOSTheme.textTertiary, lineWidth: 1.5)
                        if mine {
                            Circle().fill(BitOSTheme.accent)
                                .frame(width: 8, height: 8)
                        }
                    }
                    .frame(width: 16, height: 16)
                    Text(label)
                        .font(.system(size: 14))
                        .foregroundStyle(BitOSTheme.textPrimary)
                        .lineLimit(1)
                    Spacer()
                    if voted, total > 0 {
                        Text("\(Int(fraction * 100))% · \(count)")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(mine ? BitOSTheme.accent : BitOSTheme.textSecondary)
                    }
                }
                .padding(.horizontal, 10)
            }
            .frame(height: 38)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(!canVote)
        .accessibilityLabel("Vote \(label)")
    }
}

/// A feed row updates its own label instead of invalidating the feed store.
/// The task is cancelled when the lazy row leaves the visible hierarchy.
struct RelativeTimeText: View {
    let createdAt: Int64
    @State private var now = Date.now

    var body: some View {
        Text(FeedFormat.timeAgo(createdAt: createdAt, now: now))
            .task(id: createdAt) {
                while !Task.isCancelled {
                    let age = max(0, Int64(now.timeIntervalSince1970) - createdAt)
                    let delaySeconds: Int64 = age < 60 ? 1 : max(1, 60 - (age % 60))
                    try? await Task.sleep(nanoseconds: UInt64(delaySeconds) * 1_000_000_000)
                    guard !Task.isCancelled else { return }
                    now = .now
                }
            }
    }
}
