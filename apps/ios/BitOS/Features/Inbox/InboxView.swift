import SwiftUI
import UIKit

/**
 * Activity surface (SOC-005 + APP-012, mock 06 `scr-activity` parity):
 * verified events targeting the account rendered as full-width bordered
 * rows — avatar stacks with "+N" plates, amber zap sats, a Follow-back
 * pill, mention quote cards with highlighted @handles, local sent-zap
 * (zap-out) rows from the APP-014 ledger with PAID chips, day sections
 * (Today / Yesterday / Earlier) and the mock chip filter row
 * (All / Zaps / Likes / Follows / Mentions). Visible-mark-read (1.4 s),
 * blocked-author filtering, per-type mutes, search, mark-all-read and the
 * long-press row menu ride the same shared rules as before.
 */

/// Mock 06 chip row: one-of-five source filter (single-select).
enum InboxFilter: String, CaseIterable {
    case all, zaps, likes, follows, mentions

    var label: String {
        switch self {
        case .all: return "All"
        case .zaps: return "Zaps"
        case .likes: return "Likes"
        case .follows: return "Follows"
        case .mentions: return "Mentions"
        }
    }

    var symbol: String? {
        switch self {
        case .zaps: return AppIcons.zap
        case .likes: return AppIcons.heart
        default: return nil
        }
    }
}

struct InboxView: View {
    @Environment(AppEnvironment.self) private var environment
    let store: InboxStore

    @State private var filter: InboxFilter = .all
    @State private var query = ""
    @State private var searchOpen = false
    @State private var menu: AppMenuPresentation?
    @State private var rawJson: RawEvent?
    @State private var threadTarget: FeedNote?
    @State private var authorTarget: AuthorTarget?

    var body: some View {
        NavigationStack {
            content
                .background(BitOSTheme.background)
                .toolbar(.hidden, for: .navigationBar)
                .appMenuHost($menu)
                .sheet(item: $rawJson) { raw in
                    RawEventSheet(text: raw.value)
                }
                .sheet(item: $threadTarget) { note in
                    CommentSheet(
                        note: note,
                        store: environment.feedStore,
                        publisher: environment.notePublisher,
                        onClose: { threadTarget = nil }
                    )
                }
                .sheet(item: $authorTarget) { target in
                    AuthorProfileSheet(authorPubkey: target.value) {
                        authorTarget = nil
                    }
                    .environment(environment)
                }
                // Visible-mark-read (spec: 1.4 s): unread items settle read
                // after the timer; late arrivals restart it.
                .task(id: store.unreadCount) {
                    guard store.unreadCount > 0 else { return }
                    try? await Task.sleep(for: .seconds(1.4))
                    guard !Task.isCancelled else { return }
                    store.markRead(store.items.filter { !store.isRead($0) }.map(\.id))
                }
                .onChange(of: environment.identityStore.account) { _, account in
                    store.setAccount(account?.pubkeyHex)
                }
                .onAppear {
                    store.setAccount(environment.identityStore.account?.pubkeyHex)
                }
        }
        .preferredColorScheme(.dark)
    }

    @ViewBuilder
    private var content: some View {
        if !store.hasAccount {
            ContentPlaceholderView(
                title: "Activity needs an identity",
                message: "Create or import a key (You tab) to see replies, mentions, reactions, reposts and zaps addressed to you.",
                symbol: "bell.badge"
            )
        } else {
            let sections = displaySections
            let sentZapsEmpty = environment.sentZaps.records.isEmpty
            VStack(spacing: 0) {
                header
                if searchOpen || !query.isEmpty {
                    searchRow
                }
                FilterChipRowView(selected: filter) { filter = $0 }
                if store.items.isEmpty && sentZapsEmpty && store.offline {
                    InboxOfflineCard { store.reconnect() }
                } else if store.items.isEmpty && sentZapsEmpty {
                    ContentPlaceholderView(
                        title: store.connected ? "No notifications yet" : "Loading activity from relays…",
                        message: store.connected
                            ? "Notifications appear when someone replies, mentions you, reacts, reposts or zaps you."
                            : "Your activity feed fills once a relay connection succeeds.",
                        symbol: "bell"
                    )
                } else if sections.isEmpty {
                    ContentPlaceholderView(
                        title: "Nothing in this filter",
                        message: query.isEmpty
                            ? "Switch filters to see other activity."
                            : "No notifications match “\(query.trimmed)”. Clear the search or switch filters to see more.",
                        symbol: "line.3.horizontal.decrease.circle"
                    )
                } else {
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(sections) { section in
                                if let title = section.title {
                                    Text(title.uppercased())
                                        .font(.system(size: 10, weight: .bold))
                                        .tracking(0.8)
                                        .foregroundStyle(BitOSTheme.textTertiary)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding(.horizontal, BitOSTheme.Spacing.base)
                                        .padding(.top, 12)
                                        .padding(.bottom, 2)
                                }
                                ForEach(section.rows) { row in
                                    rowView(row)
                                }
                            }
                            // Web parity paging footer + the verified-events promise.
                            VStack(spacing: 12) {
                                if store.loadingMore {
                                    HStack(spacing: 8) {
                                        ProgressView()
                                            .controlSize(.small)
                                        Text("Loading older activity…")
                                            .font(.system(size: 12, weight: .semibold))
                                            .foregroundStyle(BitOSTheme.textSecondary)
                                    }
                                    .padding(.top, 16)
                                } else if store.hasMore {
                                    Button {
                                        store.loadMore()
                                    } label: {
                                        Text("Load older notifications")
                                            .font(.system(size: 13, weight: .semibold))
                                            .foregroundStyle(BitOSTheme.accent)
                                            .padding(.horizontal, 20)
                                            .padding(.vertical, 9)
                                            .background(Capsule().strokeBorder(BitOSTheme.border))
                                    }
                                    .buttonStyle(.plain)
                                    .padding(.top, 12)
                                } else {
                                    Text("End of relay results")
                                        .font(.system(size: 12))
                                        .foregroundStyle(BitOSTheme.textTertiary)
                                        .padding(.top, 16)
                                }
                                Text("Activity is built from events your relays can verify — no invented counts, no engagement theater.")
                                    .font(.system(size: 11))
                                    .foregroundStyle(BitOSTheme.textTertiary)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal, 24)
                                    .padding(.bottom, 24)
                            }
                        }
                    }
                }
            }
            .onAppear { requestOrigins() }
            .onChange(of: store.items.count) { _, _ in requestOrigins() }
        }
    }

    /// Unified rows: relay groups + APP-014 sent-zap ledger records.
    private var displaySections: [InboxSection] {
        let authorNames = environment.feedStore.profiles.mapValues { $0.bestDisplayName }
        let base = store.sections(
            tab: .all,
            activity: .none,
            query: query,
            authorNames: authorNames,
            blocked: environment.feedStore.blocked
        )
        var byDay: [Int64: [InboxRow]] = [:]
        for section in base {
            byDay[section.epochDay, default: []].append(
                contentsOf: section.groups.filter { filterMatches($0.kind) }.map { .group($0) }
            )
        }
        // Zap-out rows are local ledger records, not notifications — an
        // active search indexes notifications only, so they stay out.
        if (filter == .all || filter == .zaps) && query.trimmed.isEmpty {
            let today = Int64(Date.now.timeIntervalSince1970) / 86_400
            for record in environment.sentZaps.records {
                let day = record.createdAt / 86_400
                if day >= today - 13 {
                    byDay[day, default: []].append(.sentZap(record))
                }
            }
        }
        var previousTitle: String?
        return byDay
            .sorted { $0.key > $1.key }
            .map { day, rows in
                let raw = sectionTitle(epochDay: day)
                let title = raw == previousTitle ? nil : raw
                previousTitle = raw
                return InboxSection(title: title, rows: rows.sorted { $0.newestAt > $1.newestAt })
            }
            .filter { !$0.rows.isEmpty }
    }

    /// Mock 06 titles: Today / Yesterday / Earlier (collapsed, not repeated).
    private func sectionTitle(epochDay: Int64, calendar: Calendar = .current) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochDay * 86_400))
        let startOfToday = calendar.startOfDay(for: .now)
        if calendar.isDate(date, inSameDayAs: startOfToday) { return "Today" }
        if calendar.isDate(date, inSameDayAs: calendar.date(byAdding: .day, value: -1, to: startOfToday)!) {
            return "Yesterday"
        }
        return "Earlier"
    }

    /// Mock chip mapping (Mentions covers mentions + replies).
    private func filterMatches(_ kind: NotificationKind) -> Bool {
        switch filter {
        case .all: return true
        case .zaps: return kind == .zap
        case .likes: return kind == .reaction
        case .follows: return kind == .follow
        case .mentions: return kind == .mention || kind == .reply
        }
    }

    private func requestOrigins() {
        store.requestOrigins(
            store.items.compactMap(\.targetEventId) +
                environment.sentZaps.records.compactMap(\.targetNoteId)
        )
    }

    @ViewBuilder
    private func rowView(_ row: InboxRow) -> some View {
        switch row {
        case .group(let group):
            let isRead = group.itemIds.allSatisfy { store.readIds.contains($0) }
            let origin = group.targetEventId.flatMap { store.origins[$0] }
            // Web row actions: mark read / view profile / copy id / raw JSON /
            // mute this type. Opening also marks the row read.
            let openRow: () -> Void = {
                if !isRead { store.markRead(group.itemIds) }
                open(group)
            }
            let viewProfile: () -> Void = {
                group.actors.first.map { authorTarget = AuthorTarget(value: $0) }
            }
            let toggleMute: () -> Void = {
                var next = store.mutedKinds
                if next.contains(group.kind) {
                    next.remove(group.kind)
                } else {
                    next.insert(group.kind)
                }
                store.setMutedKinds(next)
            }
            if group.kind == .mention || group.kind == .reply {
                MentionCardRowView(
                    group: group,
                    isRead: isRead,
                    preview: store.previews[group.itemIds.first ?? ""],
                    profile: environment.feedStore.profiles[group.actors.first ?? ""],
                    onMarkRead: { store.markRead(group.itemIds) },
                    onCopyId: { UIPasteboard.general.string = group.targetEventId ?? "" },
                    onShowRaw: {
                        rawJson = group.itemIds.compactMap { store.rawEvents[$0] }.first.map(RawEvent.init)
                    },
                    onViewProfile: viewProfile,
                    onToggleMute: toggleMute,
                    onOpen: openRow,
                    isMuted: store.mutedKinds.contains(group.kind)
                )
            } else {
                GroupRowView(
                    group: group,
                    isRead: isRead,
                    origin: origin,
                    profiles: environment.feedStore.profiles,
                    isFollowing: group.actors.first.map { environment.feedStore.following.contains($0) } ?? false,
                    onFollow: { toggleFollow(group.actors.first ?? "") },
                    onMarkRead: { store.markRead(group.itemIds) },
                    onCopyId: { UIPasteboard.general.string = group.targetEventId ?? "" },
                    onShowRaw: {
                        rawJson = group.itemIds.compactMap { store.rawEvents[$0] }.first.map(RawEvent.init)
                    },
                    onViewProfile: viewProfile,
                    onToggleMute: toggleMute,
                    onOpen: openRow,
                    isMuted: store.mutedKinds.contains(group.kind)
                )
            }
        case .sentZap(let record):
            SentZapRowView(
                record: record,
                profile: environment.feedStore.profiles[record.recipientPubkey],
                origin: record.targetNoteId.flatMap { store.origins[$0] },
                onOpen: { openSentZap(record) }
            )
        }
    }

    /// Deep link: verified origin note → thread sheet; otherwise author.
    private func open(_ group: NotificationGroupRow) {
        if
            let target = group.targetEventId,
            case .ready(let origin) = store.origins[target]
        {
            threadTarget = FeedNote(
                id: origin.id,
                pubkey: origin.authorPubkey,
                content: origin.content,
                createdAt: origin.createdAt,
                kind: origin.kind,
                replyTo: nil,
                hashtags: [],
                mentions: [],
                mediaUrls: [],
                isProtocolPayload: false
            )
        } else {
            authorTarget = group.actors.first.map(AuthorTarget.init)
        }
    }

    private func openSentZap(_ record: SentZapsStore.SentZapRecord) {
        if
            let target = record.targetNoteId,
            case .ready(let origin) = store.origins[target]
        {
            threadTarget = FeedNote(
                id: origin.id,
                pubkey: origin.authorPubkey,
                content: origin.content,
                createdAt: origin.createdAt,
                kind: origin.kind,
                replyTo: nil,
                hashtags: [],
                mentions: [],
                mediaUrls: [],
                isProtocolPayload: false
            )
        } else {
            authorTarget = AuthorTarget(value: record.recipientPubkey)
        }
    }

    /// Follow-back (mock 06): optimistic flip + kind-3 publish.
    private func toggleFollow(_ author: String) {
        guard let updated = environment.feedStore.applyFollowChange(
            author: author,
            add: !environment.feedStore.following.contains(author)
        ) else { return }
        guard environment.identityStore.account != nil else { return }
        Task { await environment.notePublisher.publishFollowList(follows: updated) }
    }

    /// Mock 06 header: "Inbox" + ⋯ (mark all read · search · type filters).
    private var header: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            Text("Inbox")
                .font(.system(size: 20, weight: .bold))
            Spacer()
            AppMenuAnchorButton(
                symbol: AppIcons.more,
                tint: BitOSTheme.textPrimary,
                label: "Inbox options"
            ) { anchor in
                presentHeaderMenu(at: anchor)
            }
        }
        .padding(.horizontal, BitOSTheme.Spacing.base)
        .padding(.top, 10)
        .padding(.bottom, 2)
    }

    private func presentHeaderMenu(at anchor: CGPoint) {
        let kinds: [(NotificationKind, String)] = [
            (.reply, "Replies"), (.mention, "Mentions"), (.reaction, "Likes"),
            (.repost, "Reposts"), (.zap, "Zaps"), (.follow, "Follows"),
        ]
        menu = AppMenuPresentation(
            anchor: anchor,
            entries: [
                .item(AppMenuItem(id: "read-all", label: "Mark all read", systemImage: AppIcons.check)),
                .item(AppMenuItem(id: "search", label: "Search", systemImage: AppIcons.search)),
                .divider,
            ] + kinds.map { kind, label in
                .item(AppMenuItem(
                    id: kind.name,
                    label: label,
                    isChecked: !store.mutedKinds.contains(kind)
                ))
            }
        ) { id in
            switch id {
            case "read-all":
                store.markAllRead()
            case "search":
                searchOpen = true
            default:
                guard let kind = kinds.map(\.0).first(where: { $0.name == id }) else { return }
                var next = store.mutedKinds
                if next.contains(kind) {
                    next.remove(kind)
                } else {
                    next.insert(kind)
                }
                store.setMutedKinds(next)
            }
        }
    }

    /// APP-012 expandable search row (name/content; shared predicate applies
    /// in `InboxStore.sections`).
    private var searchRow: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            Button {
                if searchOpen && query.isEmpty {
                    searchOpen = false
                } else {
                    searchOpen = true
                }
            } label: {
                AppIcons.image(for: AppIcons.search)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(query.isEmpty && !searchOpen ? BitOSTheme.textSecondary : BitOSTheme.accent)
                    .frame(width: 30, height: 30)
            }
            .accessibilityLabel(searchOpen ? "Close search" : "Search notifications")
            if searchOpen {
                BitosField("Search names and notes…", text: $query)
                    .font(.system(size: 13))
                    .padding(.vertical, 6)
                    .padding(.horizontal, BitOSTheme.Spacing.sm)
                    .background(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(BitOSTheme.surfaceElevated)
                    )
            } else if !query.isEmpty {
                Text("“\(query.trimmed)”")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.accent)
                    .lineLimit(1)
            }
            if !query.isEmpty {
                Button("Clear") { query = "" }
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(BitOSTheme.accent)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, BitOSTheme.Spacing.base)
        .padding(.bottom, BitOSTheme.Spacing.xs)
    }
}

/// Day bucket after title collapse (mock: Today / Yesterday / Earlier).
private struct InboxSection: Identifiable {
    let title: String?
    let rows: [InboxRow]
    var id: String { "\(title ?? "none")-\(rows.first?.id ?? "")" }
}

/// Unified section row: relay-verified groups + local sent-zap rows.
private enum InboxRow: Identifiable {
    case group(NotificationGroupRow)
    case sentZap(SentZapsStore.SentZapRecord)

    var id: String {
        switch self {
        case .group(let group): return "g-\(group.id)"
        case .sentZap(let record): return "z-\(record.id)"
        }
    }

    var newestAt: Int64 {
        switch self {
        case .group(let group): return group.newestAt
        case .sentZap(let record): return record.createdAt
        }
    }
}

/// Identifiable wrappers so sheets can present from bindings.
private struct AuthorTarget: Identifiable {
    let value: String
    var id: String { value }
}

private struct RawEvent: Identifiable {
    let value: String
    var id: String { String(value.hashValue) }
}

private struct RawEventSheet: View {
    let text: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(text)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
            }
            .background(BitOSTheme.background)
            .navigationTitle("Raw event")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    SheetCloseButton { dismiss() }
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}

// MARK: - Rows

/// Kind accent (unread stripe + trailing glyph): zap amber, like pink,
/// repost green, follow orange, mention cyan, reply blue.
private func kindTint(_ kind: NotificationKind) -> Color {
    switch kind {
    case .zap: return BitOSTheme.zap
    case .reaction: return BitOSTheme.like
    case .repost: return BitOSTheme.repost
    case .follow: return BitOSTheme.accent
    case .mention: return BitOSTheme.cyan
    case .reply: return BitOSTheme.reply
    }
}

/// "21 sats" / "1,234 sats" / "500 msat" for fractional amounts.
private func formatSats(_ msat: Int64) -> String {
    let formatter = NumberFormatter()
    formatter.groupingSeparator = ","
    formatter.numberStyle = .decimal
    if msat % 1_000 == 0 {
        return (formatter.string(from: NSNumber(value: msat / 1_000)) ?? "\(msat / 1_000)") + " sats"
    }
    return (formatter.string(from: NSNumber(value: msat)) ?? "\(msat)") + " msat"
}

/// One bordered row for aggregated kinds (zap/likes/repost/follow).
private struct GroupRowView: View {
    let group: NotificationGroupRow
    let isRead: Bool
    let origin: OriginNoteState?
    let profiles: [String: ProfileMetadata]
    let isFollowing: Bool
    let onFollow: () -> Void
    let onMarkRead: () -> Void
    let onCopyId: () -> Void
    let onShowRaw: () -> Void
    let onViewProfile: () -> Void
    let onToggleMute: () -> Void
    let onOpen: () -> Void
    var isMuted: Bool = false

    private var profile: ProfileMetadata? { profiles[group.actors.first ?? ""] }

    /// Two named actors max, then "and N others" (web `actorSummary`).
    private var namedActors: [String] {
        (0..<min(2, group.actorCount)).compactMap { index in
            group.actors.indices.contains(index)
                ? (profiles[group.actors[index]]?.bestDisplayName ?? FeedFormat.shortPubkey(group.actors[index]))
                : nil
        }
    }

    private var titleText: Text {
        let others = max(0, group.actorCount - namedActors.count)
        func actorsLead() -> Text {
            var text = namedActors.enumerated().reduce(Text("")) { acc, pair in
                let (index, name) = pair
                let bold = Text(name).fontWeight(.bold)
                return acc + (index == 0 ? bold : Text(", ") + bold)
            }
            if others > 0 {
                text = text + Text(" and \(others) other\(others == 1 ? "" : "s")")
            }
            return text
        }
        switch group.kind {
        case .zap:
            guard !namedActors.isEmpty else {
                let count = group.itemIds.count
                return Text("\(count) zap\(count == 1 ? "" : "s") on your note")
            }
            var text = actorsLead() + Text(" zapped ")
            if group.msat > 0 {
                text = text + Text(formatSats(group.msat))
                    .fontWeight(.bold)
                    .foregroundStyle(BitOSTheme.zap)
            }
            return text
        case .reaction:
            let noun = originIsBitz ? "Bitz" : "note"
            guard !namedActors.isEmpty else { return Text("Someone liked your \(noun)") }
            return actorsLead() + Text(" liked your \(noun)")
        case .repost:
            return (namedActors.isEmpty ? Text("Someone") : actorsLead()) + Text(" reposted your note")
        case .follow:
            return (namedActors.isEmpty ? Text("Someone") : actorsLead()) + Text(" followed you")
        case .mention, .reply:
            return Text("")
        }
    }

    private var originIsBitz: Bool {
        if case .ready(let note) = origin { return note.kind == 22 }
        return false
    }

    /// "“note excerpt” · 2m ago" — the quote rides the verified origin.
    private var subtitle: String? {
        let time = FeedFormat.timeAgo(createdAt: group.newestAt)
        var excerpt: String?
        if case .ready(let note) = origin, !note.excerpt.isEmpty {
            excerpt = note.excerpt
        }
        switch group.kind {
        case .follow:
            return time
        case .zap, .reaction, .repost:
            return excerpt.map { "“\($0)” · \(time)" } ?? time
        case .mention, .reply:
            return nil
        }
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            leading
            VStack(alignment: .leading, spacing: 2) {
                titleText
                    .font(.system(size: 12))
                    .lineLimit(2)
                    .foregroundStyle(BitOSTheme.textPrimary)
                if let subtitle {
                    Text(subtitle)
                        .font(.system(size: 10))
                        .foregroundStyle(BitOSTheme.textTertiary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            trailing
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .contextMenu {
            if !isRead { Button("Mark read") { onMarkRead() } }
            if group.actors.first != nil { Button("View profile") { onViewProfile() } }
            if group.targetEventId != nil { Button("Copy note id") { onCopyId() } }
            Button("Raw event JSON") { onShowRaw() }
            Button(isMuted ? "Unmute this type" : "Mute this type") { onToggleMute() }
        }
        .rowChrome(isRead: isRead, tint: kindTint(group.kind))
    }

    /// Avatar stack (zap/likes/repost) or single verified avatar (follow).
    @ViewBuilder
    private var leading: some View {
        if group.kind == .follow {
            RingHexAvatarView(
                pubkey: group.actors.first ?? String(repeating: "0", count: 64),
                size: 40,
                label: profile?.bestDisplayName,
                imageURL: safeProfilePictureURL(profile?.picture),
                verified: profile?.nip05 != nil
            )
        } else if group.actors.isEmpty {
            // Anonymous zap receipts: an amber bolt plate stands in.
            ZStack {
                HexShape().fill(BitOSTheme.zap.opacity(0.4))
                ZStack {
                    BitOSTheme.surface
                    AppIcons.image(for: AppIcons.zap)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(BitOSTheme.zap)
                }
                .frame(width: 36, height: 36)
                .clipShape(HexShape())
            }
            .frame(width: 40, height: 40)
        } else {
            // Mock stack: ≤3 ring hexes, else 2 + "+N" plate, first on top.
            let withPlate = group.actorCount > 3
            let actors = Array((withPlate ? group.actors.prefix(2) : group.actors.prefix(3)))
            HStack(spacing: -8) {
                ForEach(Array(actors.enumerated()), id: \.offset) { index, actor in
                    let meta = profiles[actor]
                    RingHexAvatarView(
                        pubkey: actor,
                        size: 40,
                        label: meta?.bestDisplayName,
                        imageURL: safeProfilePictureURL(meta?.picture),
                        verified: index == 0 && meta?.nip05 != nil
                    )
                    .zIndex(Double(actors.count - index))
                }
                if withPlate {
                    HexCountPlate(count: group.actorCount - actors.count)
                        .zIndex(0)
                }
            }
        }
    }

    /// Trailing affordance per kind: bolt / heart / repost / follow pill.
    @ViewBuilder
    private var trailing: some View {
        switch group.kind {
        case .zap:
            AppIcons.image(for: AppIcons.zap)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(BitOSTheme.zap)
        case .reaction:
            AppIcons.image(for: AppIcons.heartFill)
                .font(.system(size: 15))
                .foregroundStyle(BitOSTheme.like)
        case .repost:
            AppIcons.image(for: AppIcons.repost)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(BitOSTheme.repost)
        case .follow:
            if group.actors.first != nil && !isFollowing {
                Button(action: onFollow) {
                    Text("Follow back")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(BitOSTheme.background)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 5)
                        .background(Capsule().fill(BitOSTheme.textPrimary))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Follow back")
            } else if isFollowing {
                Text("Following ✓")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(BitOSTheme.textTertiary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 4)
                    .background(Capsule().strokeBorder(BitOSTheme.border))
            }
        case .mention, .reply:
            EmptyView()
        }
    }
}

/// Mock "+12" hex plate for aggregated actors beyond the shown stack.
private struct HexCountPlate: View {
    let count: Int

    var body: some View {
        ZStack {
            HexShape().fill(BitOSTheme.border)
            ZStack {
                BitOSTheme.surface
                Text("+\(count)")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            .frame(width: 36, height: 36)
            .clipShape(HexShape())
        }
        .frame(width: 40, height: 40)
    }
}

/// Zap-out row (APP-014): dimmed local-ledger record with a PAID chip.
private struct SentZapRowView: View {
    let record: SentZapsStore.SentZapRecord
    let profile: ProfileMetadata?
    let origin: OriginNoteState?
    let onOpen: () -> Void

    private var name: String {
        profile?.bestDisplayName ?? FeedFormat.shortPubkey(record.recipientPubkey)
    }

    private var subtitle: String {
        var excerpt: String?
        if case .ready(let note) = origin, !note.excerpt.isEmpty {
            excerpt = note.excerpt
        }
        let time = FeedFormat.timeAgo(createdAt: record.createdAt)
        return [excerpt.map { "“\($0)”" }, "paid", time]
            .compactMap { $0 }
            .joined(separator: " · ")
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            AppIcons.image(for: AppIcons.zap)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(BitOSTheme.textTertiary)
            VStack(alignment: .leading, spacing: 2) {
                (Text("You zapped ") +
                    Text(name).fontWeight(.bold) +
                    Text(" \(formatSats(record.amountSats * 1_000))").fontWeight(.bold))
                    .font(.system(size: 12))
                    .lineLimit(1)
                Text(subtitle)
                    .font(.system(size: 10))
                    .foregroundStyle(BitOSTheme.textTertiary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            Text("PAID")
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(BitOSTheme.success)
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(Capsule().strokeBorder(BitOSTheme.success.opacity(0.4)))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
        .opacity(0.8)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .overlay(alignment: .bottom) {
            Rectangle().fill(BitOSTheme.border).frame(height: 1)
        }
    }
}

/// Mention/reply row (mock): header line + quoted content card (web preview
/// parity: cleaned excerpt + media strip behind the NIP-36 cover).
private struct MentionCardRowView: View {
    let group: NotificationGroupRow
    let isRead: Bool
    var preview: OriginNote?
    let profile: ProfileMetadata?
    let onMarkRead: () -> Void
    let onCopyId: () -> Void
    let onShowRaw: () -> Void
    let onViewProfile: () -> Void
    let onToggleMute: () -> Void
    let onOpen: () -> Void
    var isMuted: Bool = false

    @Environment(SettingsStore.self) private var settings

    private var name: String {
        profile?.bestDisplayName ?? "Someone"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                RingHexAvatarView(
                    pubkey: group.actors.first ?? String(repeating: "0", count: 64),
                    size: 32,
                    label: profile?.bestDisplayName,
                    imageURL: safeProfilePictureURL(profile?.picture)
                )
                (
                    Text(name).fontWeight(.bold) +
                        Text(group.kind == .reply ? " replied to your note" : " mentioned you")
                )
                .font(.system(size: 12))
                .lineLimit(1)
                Spacer(minLength: 0)
                Text(FeedFormat.timeAgo(createdAt: group.newestAt))
                    .font(.system(size: 10))
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
            MentionQuoteCard(
                content: (preview?.excerpt.isEmpty == false ? preview?.excerpt : nil) ?? group.summary,
                media: preview,
                sensitiveShowByDefault: settings.state.sensitiveMedia == .show
            )
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .contextMenu {
            if !isRead { Button("Mark read") { onMarkRead() } }
            if group.actors.first != nil { Button("View profile") { onViewProfile() } }
            if group.targetEventId != nil { Button("Copy note id") { onCopyId() } }
            Button("Raw event JSON") { onShowRaw() }
            Button(isMuted ? "Unmute this type" : "Mute this type") { onToggleMute() }
        }
        .rowChrome(isRead: isRead, tint: kindTint(group.kind))
    }
}

/// @handles and nostr:npub tokens render in brand orange (mock parity);
/// APP-012 media strip renders ≤4 tiles behind a NIP-36 cover.
private struct MentionQuoteCard: View {
    let content: String
    var media: OriginNote?
    /** APP-018 privacy: `show` renders NIP-36 media directly (no cover). */
    var sensitiveShowByDefault: Bool = false

    @State private var revealed = false
    @State private var lightboxUrl: String?

    private static let tokenPattern = "(?:@[A-Za-z0-9_.]+|nostr:npub1[0-9a-z]+)"

    private var styledText: Text {
        let regex = try? NSRegularExpression(pattern: Self.tokenPattern)
        let ns = content as NSString
        var segments: [Text] = []
        var cursor = 0
        let matches = regex?.matches(in: content, range: NSRange(location: 0, length: ns.length)) ?? []
        for match in matches {
            if match.range.location > cursor {
                segments.append(Text(ns.substring(with: NSRange(location: cursor, length: match.range.location - cursor))))
            }
            segments.append(
                Text(ns.substring(with: match.range))
                    .foregroundStyle(BitOSTheme.accent)
            )
            cursor = match.range.location + match.range.length
        }
        if cursor < ns.length {
            segments.append(Text(ns.substring(from: cursor)))
        }
        return segments.reduce(Text(""), +)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            styledText
                .font(.system(size: 12))
                .foregroundStyle(BitOSTheme.textSecondary)
                .lineSpacing(4)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let media, !media.mediaUrls.isEmpty {
                mediaStrip(media)
            }
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(BitOSTheme.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(BitOSTheme.border)
        )
        .sheet(item: Binding(
            get: { lightboxUrl.map { LightboxTarget(url: $0) } },
            set: { lightboxUrl = $0?.url }
        )) { target in
            MediaLightbox(url: target.url, onClose: { lightboxUrl = nil })
        }
    }

    @ViewBuilder
    private func mediaStrip(_ note: OriginNote) -> some View {
        if note.contentWarning && !revealed && !sensitiveShowByDefault {
            Button {
                revealed = true
            } label: {
                HStack(spacing: 6) {
                    AppIcons.image(for: AppIcons.photo)
                        .font(.system(size: 12))
                    Text("Sensitive content — tap to reveal")
                        .font(.system(size: 11))
                }
                .foregroundStyle(BitOSTheme.textTertiary)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(BitOSTheme.surfaceOverlay)
                )
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Reveal sensitive media")
        } else {
            HStack(spacing: 4) {
                ForEach(note.mediaUrls, id: \.self) { url in
                    Button {
                        lightboxUrl = url
                    } label: {
                        RemoteImageView(url: url)
                            .frame(width: 56, height: 32)
                            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                    }
                    .accessibilityLabel("Open media")
                }
            }
        }
    }
}

private struct LightboxTarget: Identifiable {
    let url: String
    var id: String { url }
}

/// Mock chip row: All (orange) / ⚡ Zaps / ♥ Likes / Follows / Mentions.
private struct FilterChipRowView: View {
    let selected: InboxFilter
    let onSelect: (InboxFilter) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(InboxFilter.allCases, id: \.rawValue) { option in
                    let active = option == selected
                    Button {
                        onSelect(option)
                    } label: {
                        HStack(spacing: 6) {
                            if let symbol = option.symbol {
                                AppIcons.image(for: symbol)
                                    .font(.system(size: 11, weight: .bold))
                            }
                            Text(option.label)
                                .font(.system(size: 12, weight: .semibold))
                        }
                        .foregroundStyle(active ? BitOSTheme.accent : BitOSTheme.textSecondary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(
                            Capsule().fill(active ? BitOSTheme.accent.opacity(0.14) : BitOSTheme.surface)
                        )
                        .overlay(
                            Capsule().strokeBorder(
                                active ? BitOSTheme.accent.opacity(0.4) : BitOSTheme.border
                            )
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Filter \(option.label)")
                    .accessibilityAddTraits(active ? .isSelected : [])
                }
            }
            .padding(.horizontal, 16)
        }
        .padding(.vertical, 8)
    }
}

/// Shared row chrome: the mock 3 dp unread stripe + bottom hairline.
private extension View {
    func rowChrome(isRead: Bool, tint: Color) -> some View {
        modifier(ActivityRowChrome(isRead: isRead, tint: tint))
    }
}

private struct ActivityRowChrome: ViewModifier {
    let isRead: Bool
    let tint: Color

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .leading) {
                if !isRead {
                    Rectangle().fill(tint).frame(width: 3)
                }
            }
            .overlay(alignment: .bottom) {
                Rectangle().fill(BitOSTheme.border).frame(height: 1)
            }
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

/// Web parity offline card: the head REQ never answered — offer retry.
private struct InboxOfflineCard: View {
    let onReconnect: () -> Void

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.sm) {
            AppIcons.image(for: "wifi")
                .font(.system(size: 24, weight: .medium))
                .foregroundStyle(BitOSTheme.error)
                .frame(width: 56, height: 56)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(BitOSTheme.error.opacity(0.12))
                )
            Text("Couldn't reach relays")
                .font(.system(size: 15, weight: .semibold))
            Text("We'll keep retrying, or tap below to try again.")
                .font(.system(size: 13))
                .foregroundStyle(BitOSTheme.textSecondary)
                .multilineTextAlignment(.center)
            Button(action: onReconnect) {
                HStack(spacing: 6) {
                    AppIcons.image(for: AppIcons.refresh)
                        .font(.system(size: 13, weight: .bold))
                    Text("Reconnect")
                        .font(.system(size: 12.5, weight: .bold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 9)
                .background(Capsule().fill(BitOSTheme.accent))
            }
            .buttonStyle(.plain)
            .padding(.top, BitOSTheme.Spacing.xs)
        }
        .padding(BitOSTheme.Spacing.xxl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
