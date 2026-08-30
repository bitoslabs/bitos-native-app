import SwiftUI

/// Author profile bottom sheet (TikTok-style): banner hero + all profile
/// fields + notes. Swipe up expands to full; "View full profile" opens a
/// fullScreenCover. Pass `onOpenFullProfile` from BitzView for the
/// swipe-right-on-video shortcut.
struct AuthorProfileSheet: View {
    let authorPubkey: String
    let onClose: () -> Void
    /// Called when the user taps "View full profile" or swipes right from
    /// the caller (BitzView). When nil the button is still shown but opens
    /// an inline fullScreenCover.
    var onOpenFullProfile: (() -> Void)? = nil

    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    @State private var aboutExpanded = false
    @State private var npubCopied = false
    @State private var showInlineFull = false

    private var profile: ProfileMetadata? { environment.authorStore.profile }
    private var notes: [FeedNote] { environment.authorStore.notes }
    private var isFollowing: Bool { environment.feedStore.following.contains(authorPubkey) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    bannerSection
                    VStack(alignment: .leading, spacing: BitOSTheme.Spacing.base) {
                        avatarActionsRow
                        nameBlock
                        if let about = profile?.about?.trimmingCharacters(in: .whitespacesAndNewlines),
                           !about.isEmpty {
                            aboutBlock(about)
                        }
                        infoChipsRow
                        viewFullProfileRow
                        Divider().background(BitOSTheme.divider).padding(.vertical, 2)
                        notesSection
                    }
                    .padding(.horizontal, BitOSTheme.Spacing.screen)
                    .padding(.bottom, BitOSTheme.Spacing.xl)
                }
            }
            .background(BitOSTheme.background)
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    SheetCloseButton(action: onClose)
                }
            }
        }
        .preferredColorScheme(.dark)
        .onAppear { environment.authorStore.open(authorPubkey: authorPubkey) }
        .onDisappear { environment.authorStore.close() }
        .fullScreenCover(isPresented: $showInlineFull) {
            AuthorProfileFullView(
                authorPubkey: authorPubkey,
                onClose: { showInlineFull = false }
            )
            .environment(environment)
            .environment(identity)
        }
    }

    // MARK: - Banner

    private var bannerSection: some View {
        ZStack(alignment: .bottomLeading) {
            if let banner = profile?.banner, !banner.isEmpty, let url = URL(string: banner) {
                AsyncImage(url: url) { phase in
                    if let img = phase.image {
                        img.resizable().scaledToFill()
                    } else {
                        defaultBanner
                    }
                }
                .frame(height: 130)
                .clipped()
            } else {
                defaultBanner
            }
            // Bottom scrim so the avatar reads on any cover.
            LinearGradient(
                colors: [.clear, BitOSTheme.background.opacity(0.55)],
                startPoint: .top, endPoint: .bottom
            )
        }
        .frame(height: 130)
    }

    private var defaultBanner: some View {
        LinearGradient(
            colors: [BitOSTheme.accent.opacity(0.55), Color(red: 0.08, green: 0.06, blue: 0.22)],
            startPoint: .topLeading, endPoint: .bottomTrailing
        )
    }

    // MARK: - Avatar + action row

    private var avatarActionsRow: some View {
        HStack(alignment: .bottom, spacing: BitOSTheme.Spacing.sm) {
            // Avatar overlaps the banner by pulling up with a negative top offset.
            PubkeyAvatarView(
                pubkey: authorPubkey,
                size: 76,
                picture: profile?.picture,
                label: profile?.bestDisplayName,
                hasLightning: !(profile?.lud16 ?? "").isEmpty
            )
            .overlay(
                Circle()
                    .strokeBorder(BitOSTheme.background, lineWidth: 3)
            )
            .offset(y: -38)
            .padding(.bottom, -38)

            Spacer()

            // Copy npub
            Button {
                if let npub = BusinessCoreBridge().npubEncode(pubkeyHex: authorPubkey) as String? {
                    UIPasteboard.general.string = npub
                    npubCopied = true
                    Task {
                        try? await Task.sleep(nanoseconds: 1_500_000_000)
                        npubCopied = false
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    AppIcons.image(for: npubCopied ? AppIcons.checkCircle : AppIcons.copy)
                        .font(.system(size: 14))
                    Text(npubCopied ? "Copied" : "npub")
                        .font(.system(size: 11, weight: .semibold))
                }
                .foregroundStyle(BitOSTheme.textSecondary)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Capsule().fill(BitOSTheme.surface))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Copy npub")

            // Follow / Unfollow
            Button {
                if let updated = environment.feedStore.applyFollowChange(
                    author: authorPubkey,
                    add: !isFollowing
                ) {
                    guard identity.account != nil else { return }
                    Task { await environment.notePublisher.publishFollowList(follows: updated) }
                }
            } label: {
                Text(isFollowing ? "Following ✓" : "Follow")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(isFollowing ? BitOSTheme.textPrimary : BitOSTheme.background)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(
                        Capsule().fill(isFollowing ? BitOSTheme.surface : BitOSTheme.accent)
                    )
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isFollowing ? "Unfollow" : "Follow")
        }
        .padding(.top, BitOSTheme.Spacing.sm)
    }

    // MARK: - Name block

    private var nameBlock: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 5) {
                Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(authorPubkey))
                    .font(.title3.weight(.bold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .lineLimit(1)
                if !(profile?.nip05?.isEmpty ?? true) {
                    AppIcons.image(for: AppIcons.checkCircle)
                        .font(.system(size: 14))
                        .foregroundStyle(BitOSTheme.cyan)
                        .accessibilityLabel("Verified identity")
                }
            }
            if let nip05 = profile?.nip05, !nip05.isEmpty {
                Text(nip05)
                    .font(.system(size: 12))
                    .foregroundStyle(BitOSTheme.accent)
                    .lineLimit(1)
            }
            Text(FeedFormat.shortPubkey(authorPubkey))
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(BitOSTheme.textTertiary)
        }
    }

    // MARK: - About

    private func aboutBlock(_ text: String) -> some View {
        let long = text.count > 200
        return VStack(alignment: .leading, spacing: 4) {
            Text(text)
                .font(.system(size: 14))
                .lineSpacing(5)
                .foregroundStyle(BitOSTheme.textSecondary)
                .lineLimit(aboutExpanded || !long ? nil : 3)
                .animation(.easeInOut(duration: 0.2), value: aboutExpanded)
            if long {
                Button(aboutExpanded ? "Show less" : "Show more") {
                    aboutExpanded.toggle()
                }
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(BitOSTheme.accent)
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Info chips (website, lightning)

    @ViewBuilder
    private var infoChipsRow: some View {
        let lud16 = profile?.lud16?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let website = profile?.website?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !lud16.isEmpty || !website.isEmpty {
            FlowLayout(spacing: BitOSTheme.Spacing.sm) {
                if !lud16.isEmpty {
                    Button {
                        UIPasteboard.general.string = lud16
                    } label: {
                        infoChip(symbol: AppIcons.zap, text: lud16, tint: BitOSTheme.zap)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Copy lightning address")
                }
                if !website.isEmpty, let url = URL(string: website.hasPrefix("http") ? website : "https://\(website)") {
                    Link(destination: url) {
                        infoChip(symbol: "globe", text: website, tint: BitOSTheme.textSecondary)
                    }
                    .accessibilityLabel("Open website")
                }
            }
        }
    }

    private func infoChip(symbol: String, text: String, tint: Color) -> some View {
        HStack(spacing: 4) {
            Image(systemName: symbol)
                .font(.system(size: 12))
            Text(text)
                .font(.system(size: 11))
                .lineLimit(1)
        }
        .foregroundStyle(tint)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 8).fill(tint.opacity(0.12)))
    }

    // MARK: - View full profile row

    private var viewFullProfileRow: some View {
        Button {
            if let external = onOpenFullProfile {
                onClose()
                external()
            } else {
                showInlineFull = true
            }
        } label: {
            HStack {
                AppIcons.image(for: AppIcons.user)
                    .font(.system(size: 14))
                    .foregroundStyle(BitOSTheme.accent)
                Text("View full profile")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(BitOSTheme.accent)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
            .padding(.vertical, 10)
            .padding(.horizontal, BitOSTheme.Spacing.md)
            .background(RoundedRectangle(cornerRadius: 12).fill(BitOSTheme.surface))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("View this author's full profile")
    }

    // MARK: - Notes

    @ViewBuilder
    private var notesSection: some View {
        if environment.authorStore.isLoading && notes.isEmpty {
            ProgressView()
                .tint(BitOSTheme.accent)
                .frame(maxWidth: .infinity)
                .padding(BitOSTheme.Spacing.xl)
        } else if notes.isEmpty {
            Text("No notes yet, or relays haven't returned this author's posts.")
                .font(.footnote)
                .foregroundStyle(BitOSTheme.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(BitOSTheme.Spacing.xl)
        } else {
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
                Text("Notes")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .textCase(.uppercase)
                    .tracking(0.5)
                ForEach(notes.prefix(20)) { note in
                    AuthorNoteCard(note: note, profile: profile)
                }
            }
        }
    }
}

// MARK: - Full-screen profile view (presented from "View full profile")

/// Expanded profile presentation that fills the whole screen — same data as
/// the sheet, more room for the banner hero and notes list.
struct AuthorProfileFullView: View {
    let authorPubkey: String
    let onClose: () -> Void
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity

    var body: some View {
        // Re-use the sheet as a full-screen NavigationStack by changing detents
        // to fill-all; the AuthorStore state is already loaded.
        AuthorProfileSheet(
            authorPubkey: authorPubkey,
            onClose: onClose
        )
        .environment(environment)
        .environment(identity)
        .ignoresSafeArea(edges: .top)
    }
}

// MARK: - Author note card

private struct AuthorNoteCard: View {
    let note: FeedNote
    let profile: ProfileMetadata?
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 4) {
                Text(FeedFormat.timeAgo(createdAt: note.createdAt))
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textTertiary)
                if note.video != nil {
                    Text("·")
                        .foregroundStyle(BitOSTheme.textTertiary)
                    HStack(spacing: 3) {
                        Image(systemName: "play.circle.fill")
                            .font(.system(size: 11))
                        Text("Video")
                            .font(.system(size: 11, weight: .semibold))
                    }
                    .foregroundStyle(BitOSTheme.cyan)
                } else if !note.mediaUrls.isEmpty {
                    Text("·")
                        .foregroundStyle(BitOSTheme.textTertiary)
                    HStack(spacing: 3) {
                        Image(systemName: "photo")
                            .font(.system(size: 11))
                        Text("\(note.mediaUrls.count)")
                            .font(.system(size: 11, weight: .semibold))
                    }
                    .foregroundStyle(BitOSTheme.textSecondary)
                }
            }
            if !note.content.isEmpty {
                Text(note.content)
                    .font(.system(size: 14))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .lineLimit(expanded ? nil : 4)
                    .animation(.easeInOut(duration: 0.15), value: expanded)
                if note.content.count > 200 && !expanded {
                    Button("Show more") { expanded = true }
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(BitOSTheme.accent)
                        .buttonStyle(.plain)
                }
            }
        }
        .padding(BitOSTheme.Spacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(BitOSTheme.surface))
    }
}

