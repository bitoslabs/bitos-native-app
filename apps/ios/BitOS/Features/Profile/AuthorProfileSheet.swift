import BusinessCore
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
    @Environment(SettingsStore.self) private var settings
    @State private var aboutExpanded = false
    @State private var npubCopied = false
    @State private var showInlineFull = false
    @State private var showZap = false
    /** Web ProfileActionMenu parity: copy affordances + moderation. */
    @State private var moreMenu: AppMenuPresentation?
    /** Report-user flow (kind-1984, p-tag only — full-page menu parity). */
    @State private var reportReason = ""
    @State private var showReportPrompt = false
    /** X-style: tapping a note card opens its thread (CommentSheet). */
    @State private var threadTarget: FeedNote?
    /** Note zap from a profile card (NIP-57 note zap). */
    @State private var noteZapTarget: FeedNote?

    private var profile: ProfileMetadata? { environment.authorStore.profile }
    private var npub: String? { BusinessCoreBridge().npubEncode(pubkeyHex: authorPubkey) as String? }
    private var notes: [FeedNote] { environment.authorStore.notes }
    private var isFollowing: Bool { environment.feedStore.following.contains(authorPubkey) }
    private var hasLightning: Bool { !(profile?.lud16 ?? "").isEmpty }
    private var bitzCount: Int { notes.filter { $0.video != nil || !$0.mediaUrls.isEmpty }.count }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    bannerSection
                    VStack(alignment: .leading, spacing: BitOSTheme.Spacing.base) {
                        avatarActionsRow
                        // Leave breathing room beneath the floating hero
                        // before the bio and information rows begin.
                        Color.clear.frame(height: 16)
                        if let about = profile?.about?.trimmingCharacters(in: .whitespacesAndNewlines),
                           !about.isEmpty {
                            aboutBlock(about)
                        }
                        infoChipsRow
                        statsRow
                        quickActionsRow
                        latestNoteCard
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
            .toolbar(.hidden, for: .navigationBar)
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
        .appMenuHost($moreMenu)
        .onAppear { environment.authorStore.open(authorPubkey: authorPubkey) }
        .onDisappear { environment.authorStore.close() }
        // Report user (kind-1984, p-tag only — full-page ⋯ menu parity).
        .alert("Report this user", isPresented: $showReportPrompt) {
            TextField("Reason (spam, harassment…)", text: $reportReason)
            Button("Report", role: .destructive) {
                let reason = reportReason.trimmingCharacters(in: .whitespacesAndNewlines)
                reportReason = ""
                guard !reason.isEmpty else { return }
                Task {
                    await environment.notePublisher.publishReport(
                        targetEventId: nil, targetPubkey: authorPubkey, reason: reason
                    )
                }
            }
            Button("Cancel", role: .cancel) { reportReason = "" }
        } message: {
            Text("The report is published as a kind-1984 event.")
        }
        .fullScreenCover(isPresented: $showInlineFull) {
            AuthorProfileFullView(
                authorPubkey: authorPubkey,
                onClose: { showInlineFull = false }
            )
            .environment(environment)
            .environment(identity)
        }
        // Profile zap (NIP-57 p-tag only, no target note).
        .sheet(isPresented: $showZap) {
            ZapSheet(
                authorPubkey: authorPubkey,
                profile: profile,
                initialAmountSats: settings.state.defaultZapAmount,
                onPaid: { sats, memo in
                    environment.sentZaps.record(.init(
                        id: "zap-\(authorPubkey)-\(sats)-\(Int(Date.now.timeIntervalSince1970))",
                        amountSats: Int64(sats),
                        recipientPubkey: authorPubkey,
                        createdAt: Int64(Date.now.timeIntervalSince1970),
                        targetNoteId: nil,
                        memo: memo.isEmpty ? nil : memo
                    ))
                },
                onClose: { showZap = false }
            )
            .environment(identity)
            .presentationDetents([.medium])
        }
        // X-style note detail: the tapped note's thread opens above the sheet.
        .sheet(item: $threadTarget) { target in
            CommentSheet(
                note: target,
                store: environment.feedStore,
                publisher: environment.notePublisher,
                onClose: { threadTarget = nil }
            )
            .environment(identity)
            .presentationDetents([.medium, .large])
        }
        // Note zap from a profile card (web PostCard zap parity).
        .sheet(item: $noteZapTarget) { target in
            ZapSheet(
                note: target,
                profiles: zapProfiles,
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
                onClose: { noteZapTarget = nil }
            )
            .environment(identity)
            .presentationDetents([.medium])
        }
    }

    /// Feed-store profiles overlaid with this author's live kind-0, so the
    /// zap sheet always shows the recipient's lud16.
    private var zapProfiles: [String: ProfileMetadata] {
        var merged = environment.feedStore.profiles
        if let profile { merged[authorPubkey] = profile }
        return merged
    }

    // MARK: - Note actions (web PostCard parity, shared-core paths)

    private func like(_ note: FeedNote) {
        let turningOn = !environment.feedStore.localActions.liked.contains(note.id)
        environment.feedStore.localActions.toggleLike(note.id)
        guard identity.account != nil else { return }
        if turningOn {
            Task { await environment.notePublisher.publishReaction(targetEventId: note.id, targetPubkey: note.pubkey) }
        } else if let reactionId = environment.feedStore.myReactionEventIds[note.id] {
            // Web unlike parity: delete my kind-7 from relays (NIP-09).
            Task { await environment.notePublisher.publishDeletion(targetEventIds: [reactionId]) }
        }
    }

    private func repostNote(_ note: FeedNote) {
        guard identity.account != nil else { return }
        Task { await environment.notePublisher.publishRepost(targetEventId: note.id, targetPubkey: note.pubkey) }
    }

    private func openNoteZap(_ note: FeedNote) {
        environment.feedStore.loadZaps(targetEventId: note.id)
        noteZapTarget = note
    }

    // MARK: - Banner

    private var bannerSection: some View {
        ZStack(alignment: .top) {
            Group {
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
            }
            .frame(height: 130)

            HStack(spacing: BitOSTheme.Spacing.sm) {
                moreMenuButton
                Spacer()
                closeButton
            }
            .padding(BitOSTheme.Spacing.md)
        }
    }

    /// Close circle on the banner (mock parity): dark glass dot.
    private var closeButton: some View {
        Button(action: onClose) {
            AppIcons.image(for: AppIcons.close)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 30, height: 30)
                .background(Circle().fill(.black.opacity(0.40)).background(.ultraThinMaterial))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Close profile")
    }

    // MARK: - More menu (copy link · npub · lightning · mute · report)

    /// Web ProfileActionMenu parity — the same entries as the full profile
    /// page's ⋯ menu, presented over the sheet.
    private var moreMenuButton: some View {
        let entries: [AppMenuEntry] = {
            var items: [AppMenuEntry] = []
            if npub != nil {
                items.append(.item(AppMenuItem(id: "copy-link", label: "Copy profile link", systemImage: AppIcons.link)))
                items.append(.item(AppMenuItem(id: "copy-npub", label: npubCopied ? "npub copied" : "Copy npub", systemImage: AppIcons.copy)))
            }
            if let lud16 = profile?.lud16, !lud16.isEmpty {
                items.append(.item(AppMenuItem(id: "copy-lightning", label: "Copy lightning address", systemImage: AppIcons.zap)))
            }
            if identity.account?.pubkeyHex != authorPubkey {
                let muted = environment.feedStore.muted.contains(authorPubkey)
                items.append(.divider)
                items.append(.item(AppMenuItem(
                    id: "mute",
                    label: muted ? "Unmute author" : "Mute author",
                    systemImage: muted ? "speaker.wave.2" : AppIcons.mute
                )))
                items.append(.item(AppMenuItem(
                    id: "report",
                    label: "Report user…",
                    systemImage: AppIcons.reportSpam,
                    isDestructive: true
                )))
            }
            return items
        }()
        return Button {
            // no-op — the overlay tap gesture below reports the anchor
        } label: {
            AppIcons.image(for: AppIcons.more)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 30, height: 30)
                .background(Circle().fill(.black.opacity(0.40)).background(.ultraThinMaterial))
        }
        .overlay {
            GeometryReader { geo in
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture {
                        let frame = geo.frame(in: .global)
                        moreMenu = AppMenuPresentation(
                            anchor: CGPoint(x: frame.minX, y: frame.maxY),
                            entries: entries
                        ) { id in
                            handleMoreMenu(id)
                        }
                    }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("More profile actions")
        .accessibilityAddTraits(.isButton)
    }

    private func handleMoreMenu(_ id: String) {
        switch id {
        case "copy-link":
            if let npub { UIPasteboard.general.string = "https://njump.me/\(npub)" }
        case "copy-npub":
            if let npub {
                UIPasteboard.general.string = npub
                npubCopied = true
            }
        case "copy-lightning":
            if let lud16 = profile?.lud16 { UIPasteboard.general.string = lud16 }
        case "mute":
            environment.feedStore.toggleMute(authorPubkey)
        case "report":
            showReportPrompt = true
        default:
            break
        }
    }

    /// Mock parity: vivid orange → amber gradient with the hexagon pattern
    /// overlay (same palette as the "You" page default cover).
    private var defaultBanner: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.976, green: 0.659, blue: 0.294), Color(red: 0.831, green: 0.475, blue: 0.059)],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
            DefaultCoverHexPattern()
        }
    }

    // MARK: - Avatar + action row

    private var avatarActionsRow: some View {
        // Mirror the profile hero: avatar on the cover edge, identity beside
        // it, and the page action aligned with the content boundary.
        ZStack(alignment: .topLeading) {
            // Hex avatar floating over the banner edge (mock parity): a
            // background-colored hex plate forms the border, so the avatar
            // reads as a pure floating hexagon — no circular plate.
            ZStack {
                HexShape()
                    .fill(BitOSTheme.background)
                    .frame(width: 84, height: 84)
                    .shadow(color: .black.opacity(0.25), radius: 8, y: 4)
                PubkeyAvatarView(
                    pubkey: authorPubkey,
                    size: 76,
                    picture: profile?.picture,
                    label: profile?.bestDisplayName,
                    hasLightning: !(profile?.lud16 ?? "").isEmpty
                )
            }
            .offset(y: -42)
            .zIndex(2)

            nameBlock
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 96)
                .padding(.trailing, 112)
                .offset(y: -34)
                .zIndex(3)

            // "View Profile" pill at the content line (mock parity) —
            // routes to the in-app full profile page, never an external link.
            Button {
                if let external = onOpenFullProfile {
                    onClose()
                    external()
                } else {
                    showInlineFull = true
                }
            } label: {
                Text("View Profile")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(BitOSTheme.background)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(Capsule().fill(BitOSTheme.textPrimary))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("View this author's full profile")
            .frame(maxWidth: .infinity, alignment: .trailing)
            .offset(y: 4)
            .zIndex(1)
        }
        // Reserve the lower half of the avatar; its top half sits on cover.
        .frame(maxWidth: .infinity, minHeight: 42, maxHeight: 42, alignment: .top)
    }

    // MARK: - Quick actions (Zap + Follow, mock parity)

    private var quickActionsRow: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            // Zap — primary accent, only when the author has a lud16.
            Button {
                guard hasLightning else { return }
                showZap = true
            } label: {
                HStack(spacing: 6) {
                    AppIcons.image(for: AppIcons.zap)
                        .font(.system(size: 14, weight: .semibold))
                    Text("Zap")
                        .font(.system(size: 14, weight: .bold))
                }
                .foregroundStyle(hasLightning ? .white : BitOSTheme.textTertiary)
                .frame(maxWidth: .infinity, minHeight: 42)
                .background(
                    Capsule(style: .continuous).fill(hasLightning ? BitOSTheme.accent : BitOSTheme.surfaceOverlay)
                )
            }
            .buttonStyle(.plain)
            .disabled(!hasLightning)
            .accessibilityLabel(hasLightning ? "Zap this profile" : "No lightning address")

            // Follow / Unfollow — dark outline capsule.
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
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .frame(maxWidth: .infinity, minHeight: 42)
                    .background(
                        Capsule(style: .continuous).fill(BitOSTheme.surfaceElevated)
                    )
                    .overlay(
                        Capsule(style: .continuous).strokeBorder(BitOSTheme.border, lineWidth: 1)
                    )
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isFollowing ? "Unfollow" : "Follow")
        }
    }

    // MARK: - Latest note preview (mock parity)

    @ViewBuilder
    private var latestNoteCard: some View {
        if let latest = notes.first, !latest.content.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("Latest Note")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .textCase(.uppercase)
                    .tracking(0.8)
                Text(latest.content)
                    .font(.system(size: 12))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(BitOSTheme.Spacing.md)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(BitOSTheme.surface))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(BitOSTheme.border.opacity(0.6)))
        }
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
            // Compact copy chip directly under the name, matching the hero.
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
                        .font(.system(size: 10))
                    Text(npubCopied ? "npub copied" : FeedFormat.shortPubkey(authorPubkey))
                        .font(.system(size: 10, design: .monospaced))
                }
                .foregroundStyle(npubCopied ? BitOSTheme.success : BitOSTheme.textTertiary)
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(Capsule().fill(BitOSTheme.surfaceElevated))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Copy npub")
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
                        infoChip(symbol: AppIcons.globe, text: website, tint: BitOSTheme.textSecondary)
                    }
                    .accessibilityLabel("Open website")
                }
            }
        }
    }

    private func infoChip(symbol: String, text: String, tint: Color) -> some View {
        HStack(spacing: 4) {
            AppIcons.image(for: symbol)
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

    // MARK: - Stats row (truthful counts from the author REQ window)

    private var statsRow: some View {
        HStack(spacing: BitOSTheme.Spacing.xl) {
            stat("Notes", FeedFormat.count(notes.count))
            stat("Bitz", FeedFormat.count(bitzCount))
            if isFollowing {
                stat("Follows", "✓ You")
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, BitOSTheme.Spacing.sm)
        .overlay(
            Rectangle().fill(BitOSTheme.border.opacity(0.6)).frame(height: 1),
            alignment: .top
        )
        .overlay(
            Rectangle().fill(BitOSTheme.border.opacity(0.6)).frame(height: 1),
            alignment: .bottom
        )
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(BitOSTheme.textPrimary)
            Text(label)
                .font(.system(size: 12))
                .foregroundStyle(BitOSTheme.textSecondary)
        }
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
                // Pages of five arrive on demand — reaching the last loaded
                // note asks for the next older page.
                ForEach(notes) { note in
                    AuthorNoteCard(note: note, profile: profile, onOpen: {
                        threadTarget = note
                    }, actionRow: NoteActionRow(
                        isLiked: environment.feedStore.localActions.liked.contains(note.id),
                        tally: environment.feedStore.tallies[note.id],
                        onLike: { like(note) },
                        onRepost: { repostNote(note) },
                        onZap: { openNoteZap(note) }
                    ))
                    .onAppear {
                        if note.id == notes.last?.id {
                            environment.authorStore.loadMoreNotes()
                        }
                    }
                }
                if environment.authorStore.isLoadingMore {
                    HStack(spacing: 6) {
                        ProgressView()
                            .tint(BitOSTheme.accent)
                        Text("Loading more…")
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, BitOSTheme.Spacing.sm)
                }
            }
        }
    }
}

// MARK: - Full-screen profile view (presented from "View full profile")

/// In-app full-page profile for any author (UX-010): same page as the "You"
/// tab, backed by a private AuthorStore REQ. Never opens an external link.
struct AuthorProfileFullView: View {
    let authorPubkey: String
    let onClose: () -> Void

    var body: some View {
        AuthorProfilePage(authorPubkey: authorPubkey, onClose: onClose)
    }
}

// MARK: - Author note card

/// Compact like · repost · zap row shared by profile-surface note cards
/// (web PostCard action-bar parity; comment opens the thread sheet).
struct NoteActionRow: View {
    var isLiked: Bool = false
    var tally: NoteTallyMirror? = nil
    var onLike: () -> Void
    var onRepost: () -> Void
    var onZap: () -> Void

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.base) {
            Button(action: onLike) {
                HStack(spacing: 4) {
                    AppIcons.image(for: isLiked ? AppIcons.heartFill : AppIcons.heart)
                        .font(.system(size: 13))
                        .foregroundStyle(isLiked ? BitOSTheme.like : BitOSTheme.textSecondary)
                    if let count = tally?.reactions, count > 0 {
                        Text("\(count)")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(isLiked ? BitOSTheme.like : BitOSTheme.textSecondary)
                    }
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isLiked ? "Unlike" : "Like")

            Button(action: onRepost) {
                HStack(spacing: 4) {
                    AppIcons.image(for: AppIcons.repost)
                        .font(.system(size: 13))
                        .foregroundStyle(BitOSTheme.repost)
                    if let count = tally?.reposts, count > 0 {
                        Text("\(count)")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(BitOSTheme.repost)
                    }
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Repost")

            Button(action: onZap) {
                HStack(spacing: 4) {
                    AppIcons.image(for: AppIcons.zap)
                        .font(.system(size: 13))
                        .foregroundStyle(BitOSTheme.zap)
                    if let sats = tally?.zapMillisats, sats > 0 {
                        Text(BusinessCoreBridge().zapFormatSats(sats: sats / 1000))
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(BitOSTheme.zap)
                    }
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Zap")
        }
    }
}

/// X-style card: time + media badge, clamped content, inline media preview
/// (image row / 16:9 video tile), optional action row, whole card opens the
/// note's thread when `onOpen` is provided.
private struct AuthorNoteCard: View {
    let note: FeedNote
    let profile: ProfileMetadata?
    var onOpen: (() -> Void)? = nil
    var actionRow: NoteActionRow? = nil
    @State private var expanded = false

    var body: some View {
        Group {
            if let onOpen {
                card
                    .contentShape(Rectangle())
                    .onTapGesture { onOpen() }
                    .accessibilityAddTraits(.isButton)
                    .accessibilityLabel("Open note thread")
            } else {
                card
            }
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 4) {
                Text(FeedFormat.timeAgo(createdAt: note.createdAt))
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textTertiary)
                if note.video != nil {
                    Text("·")
                        .foregroundStyle(BitOSTheme.textTertiary)
                    HStack(spacing: 3) {
                        AppIcons.image(for: AppIcons.playCircle)
                            .font(.system(size: 11))
                        Text("Video")
                            .font(.system(size: 11, weight: .semibold))
                    }
                    .foregroundStyle(BitOSTheme.cyan)
                } else if !note.mediaUrls.isEmpty {
                    Text("·")
                        .foregroundStyle(BitOSTheme.textTertiary)
                    HStack(spacing: 3) {
                        AppIcons.image(for: AppIcons.photo)
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
            mediaPreview
            if let actionRow {
                Divider().background(BitOSTheme.divider)
                actionRow
            }
        }
        .padding(BitOSTheme.Spacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(BitOSTheme.surface))
    }

    /// X-style inline media: up to three square images or one video tile.
    @ViewBuilder
    private var mediaPreview: some View {
        let images = note.mediaUrls.filter { !$0.hasVideoExtension }
        if let video = note.video {
            BitzVideoTile(url: URL(string: video.posterUrl ?? video.url))
        } else if !images.isEmpty {
            HStack(spacing: 2) {
                ForEach(images.prefix(3), id: \.self) { urlString in
                    AsyncImage(url: URL(string: urlString)) { image in
                        image.resizable().aspectRatio(contentMode: .fill)
                    } placeholder: {
                        BitOSTheme.surfaceOverlay
                    }
                    .frame(width: 92, height: 92)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }
            }
        }
    }
}

private extension String {
    var hasVideoExtension: Bool {
        let lower = lowercased()
        return [".mp4", ".webm", ".mov", ".m4v"].contains { lower.hasSuffix($0) }
    }
}
