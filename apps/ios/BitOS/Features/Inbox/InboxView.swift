import SwiftUI
import UIKit

/// Activity surface (SOC-005 + APP-012): verified events targeting the
/// account — day-sectioned, iOS-style aggregated rows ("A and N others…"),
/// tabs/chips per the shared filter rules, origin-note previews and a
/// per-row ⋯ popover (mark read / copy id / raw JSON).
struct InboxView: View {
    @Environment(AppEnvironment.self) private var environment
    let store: InboxStore

    @State private var tab: NotificationTab = .all
    @State private var activity: NotificationActivity = .none
    @State private var query = ""
    @State private var searchOpen = false
    @State private var menu: AppMenuPresentation?
    @State private var muteMenu: AppMenuPresentation?
    @State private var rawJson: RawEvent?
    @State private var threadTarget: FeedNote?
    @State private var authorTarget: AuthorTarget?

    var body: some View {
        NavigationStack {
            content
                .background(BitOSTheme.background)
                .navigationTitle("Activity")
                .navigationBarTitleDisplayMode(.inline)
                .appMenuHost($menu)
                .appMenuHost($muteMenu)
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
        } else if store.items.isEmpty {
            ContentPlaceholderView(
                title: store.loaded ? "No notifications yet" : "Connecting to relays…",
                message: store.loaded
                    ? "Notifications appear when someone replies, mentions you, reacts, reposts or zaps you."
                    : "Your activity feed fills once a relay connection succeeds.",
                symbol: "bell"
            )
        } else {
            let authorNames = environment.feedStore.profiles.mapValues { $0.bestDisplayName }
            let sections = store.sections(tab: tab, activity: activity, query: query, authorNames: authorNames, blocked: environment.feedStore.blocked)
            VStack(spacing: 0) {
                header
                searchRow
                tabs
                chips
                if sections.isEmpty {
                    ContentPlaceholderView(
                        title: "Nothing in this filter",
                        message: query.isEmpty
                            ? "Switch tabs or clear the activity chips to see all notifications."
                            : "No notifications match “\(query.trimmed)”. Clear the search or switch tabs to see more.",
                        symbol: "line.3.horizontal.decrease.circle"
                    )
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
                            ForEach(sections) { section in
                                Text(Self.sectionTitle(epochDay: section.epochDay))
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(BitOSTheme.textTertiary)
                                    .padding(.top, BitOSTheme.Spacing.sm)
                                ForEach(section.groups) { group in
                                    NotificationGroupRowView(
                                        group: group,
                                        isRead: group.itemIds.allSatisfy { store.readIds.contains($0) },
                                        origin: group.targetEventId.flatMap { store.origins[$0] },
                                        onMarkRead: { store.markRead(group.itemIds) },
                                        onCopyId: { UIPasteboard.general.string = group.targetEventId ?? "" },
                                        onOpen: { open(group) },
                                        onShowRaw: {
                                            rawJson = group.itemIds.compactMap { store.rawEvents[$0] }.first.map(RawEvent.init)
                                        },
                                        onMenu: { anchor in
                                            presentRowMenu(group, at: anchor)
                                        }
                                    )
                                }
                            }
                        }
                        .padding(.horizontal, BitOSTheme.Spacing.screen)
                        .padding(.vertical, BitOSTheme.Spacing.sm)
                    }
                }
            }
            .onAppear {
                store.requestOrigins(store.items.compactMap(\.targetEventId))
            }
            .onChange(of: store.items.count) { _, _ in
                store.requestOrigins(store.items.compactMap(\.targetEventId))
            }
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
        .padding(.horizontal, BitOSTheme.Spacing.screen)
        .padding(.bottom, BitOSTheme.Spacing.xs)
    }

    private var header: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            Text("Activity")
                .font(.headline)
            Spacer()
            AppMenuAnchorButton(
                symbol: AppIcons.filter,
                tint: store.mutedKinds.isEmpty ? BitOSTheme.textSecondary : BitOSTheme.accent,
                label: "Mute notification types"
            ) { anchor in
                presentMuteMenu(at: anchor)
            }
        }
        .padding(.horizontal, BitOSTheme.Spacing.screen)
        .padding(.top, BitOSTheme.Spacing.xs)
    }

    private func presentMuteMenu(at anchor: CGPoint) {
        let kinds: [(NotificationKind, String)] = [
            (.reply, "Replies"), (.mention, "Mentions"), (.reaction, "Likes"),
            (.repost, "Reposts"), (.zap, "Zaps"), (.follow, "Follows"),
        ]
        muteMenu = AppMenuPresentation(
            anchor: anchor,
            entries: kinds.map { kind, label in
                .item(AppMenuItem(
                    id: kind.name,
                    label: label,
                    isChecked: !store.mutedKinds.contains(kind)
                ))
            }
        ) { id in
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

    private var tabs: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            ForEach(NotificationTab.allCases, id: \.rawValue) { option in
                let count = tabCount(option)
                Button {
                    tab = option
                } label: {
                    Text("\(option.label) (\(count))")
                        .font(.subheadline.weight(option == tab ? .bold : .regular))
                        .foregroundStyle(option == tab ? BitOSTheme.accent : BitOSTheme.textSecondary)
                        .padding(.vertical, 6)
                        .padding(.horizontal, 10)
                        .background(
                            Capsule().fill(option == tab ? BitOSTheme.accentContainer : BitOSTheme.surface)
                        )
                }
                .accessibilityLabel("\(option.label), \(count) notifications")
            }
            Spacer()
        }
        .padding(.horizontal, BitOSTheme.Spacing.screen)
        .padding(.vertical, BitOSTheme.Spacing.xs)
    }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                ForEach(
                    [NotificationActivity.zaps, .likes, .reposts, .follows],
                    id: \.rawValue
                ) { chip in
                    let selected = activity == chip
                    Button {
                        activity = selected ? .none : chip
                    } label: {
                        Text(chip.label)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(selected ? BitOSTheme.accent : BitOSTheme.textSecondary)
                            .padding(.vertical, 4)
                            .padding(.horizontal, 12)
                            .background(Capsule().strokeBorder(selected ? BitOSTheme.accent : BitOSTheme.border))
                    }
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            .padding(.horizontal, BitOSTheme.Spacing.screen)
        }
        .padding(.bottom, BitOSTheme.Spacing.xs)
    }

    private func tabCount(_ tab: NotificationTab) -> Int {
        switch tab {
        case .all: return store.items.count
        case .unread: return store.unreadCount
        case .mentions: return store.items.filter { $0.kind == .mention }.count
        case .replies: return store.items.filter { $0.kind == .reply }.count
        }
    }

    private func presentRowMenu(_ group: NotificationGroupRow, at anchor: CGPoint) {
        let isRead = group.itemIds.allSatisfy { store.readIds.contains($0) }
        var entries: [AppMenuEntry] = []
        if !isRead {
            entries.append(.item(AppMenuItem(id: "read", label: "Mark read", systemImage: AppIcons.check)))
        }
        if group.targetEventId != nil {
            entries.append(.item(AppMenuItem(id: "copy", label: "Copy note id", systemImage: AppIcons.copy)))
        }
        entries.append(.item(AppMenuItem(id: "raw", label: "Raw event JSON", systemImage: AppIcons.appsGrid)))
        menu = AppMenuPresentation(anchor: anchor, entries: entries) { id in
            switch id {
            case "read": store.markRead(group.itemIds)
            case "copy": UIPasteboard.general.string = group.targetEventId ?? ""
            case "raw":
                rawJson = group.itemIds.compactMap { store.rawEvents[$0] }.first.map(RawEvent.init)
            default: break
            }
        }
    }

    /// Today / Yesterday / weekday from the shared UTC epoch-day key.
    static func sectionTitle(epochDay: Int64, calendar: Calendar = .current) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochDay * 86_400))
        let startOfToday = calendar.startOfDay(for: .now)
        if calendar.isDate(date, inSameDayAs: startOfToday) { return "Today" }
        if calendar.isDate(date, inSameDayAs: calendar.date(byAdding: .day, value: -1, to: startOfToday)!) {
            return "Yesterday"
        }
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.dateFormat = "EEEE"
        return formatter.string(from: date)
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

/// Aggregated row: avatar stack + type badge · "pk… and N others …" ·
/// origin preview · time · unread accent · ⋯ menu trigger.
private struct NotificationGroupRowView: View {
    let group: NotificationGroupRow
    let isRead: Bool
    let origin: OriginNoteState?
    let onMarkRead: () -> Void
    let onCopyId: () -> Void
    let onOpen: () -> Void
    let onShowRaw: () -> Void
    let onMenu: (CGPoint) -> Void

    private var info: (verb: String, symbol: String, tint: Color) {
        switch group.kind {
        case .reply: return ("replied to your note", "bubble.right.fill", BitOSTheme.reply)
        case .mention: return ("mentioned you", "at", BitOSTheme.accent)
        case .reaction: return ("liked your note", "heart.fill", BitOSTheme.like)
        case .repost: return ("reposted your note", "arrow.2.squarepath", BitOSTheme.repost)
        case .zap: return ("zapped your note", "bolt.fill", BitOSTheme.zap)
        case .follow: return ("followed you", "person.badge.plus", BitOSTheme.accent)
        }
    }

    private var titleLine: String {
        let sats: String
        if group.kind == .zap, group.msat > 0 {
            sats = " · " + Self.formatSats(group.msat)
        } else {
            sats = ""
        }
        if group.kind == .zap && group.actors.isEmpty {
            let count = group.itemIds.count
            return "\(count) zap\(count == 1 ? "" : "s") on your note\(sats)"
        }
        let primary = group.actors.first.map { FeedFormat.shortPubkey($0) } ?? "Someone"
        let others = group.actorCount - 1
        guard others > 0 else { return "\(primary) \(info.verb)\(sats)" }
        return "\(primary) and \(others) other\(others == 1 ? "" : "s") \(info.verb)\(sats)"
    }

    /** "21 sats" / "1,234 sats" / "500 msat" for fractional amounts. */
    private static func formatSats(_ msat: Int64) -> String {
        let formatter = NumberFormatter()
        formatter.groupingSeparator = ","
        formatter.numberStyle = .decimal
        if msat % 1_000 == 0 {
            return (formatter.string(from: NSNumber(value: msat / 1_000)) ?? "\(msat / 1_000)") + " sats"
        }
        return (formatter.string(from: NSNumber(value: msat)) ?? "\(msat)") + " msat"
    }

    var body: some View {
        HStack(alignment: .center, spacing: BitOSTheme.Spacing.md) {
            if !isRead {
                Circle()
                    .fill(BitOSTheme.accent)
                    .frame(width: 5, height: 5)
            }
            AvatarStack(group: group, symbol: info.symbol, tint: info.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(titleLine)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(info.tint)
                if !group.summary.isEmpty && group.kind != .repost && group.kind != .follow {
                    Text(group.summary)
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .lineLimit(2)
                }
                OriginPreviewView(origin: origin)
                Text(
                    FeedFormat.timeAgo(createdAt: group.newestAt) +
                        (group.itemIds.count > 1 ? " · \(group.itemIds.count)" : "")
                )
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textTertiary)
            }
            Spacer(minLength: 0)
            AppMenuAnchorButton(
                symbol: AppIcons.more,
                tint: BitOSTheme.textSecondary,
                label: "Notification options"
            ) { anchor in
                onMenu(anchor)
            }
        }
        .padding(BitOSTheme.Spacing.base)
        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.lg).fill(BitOSTheme.surface))
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .contextMenu {
            if !isRead { Button("Mark read") { onMarkRead() } }
            if group.targetEventId != nil { Button("Copy note id") { onCopyId() } }
            Button("Raw event JSON") { onShowRaw() }
        }
    }
}

private struct AvatarStack: View {
    let group: NotificationGroupRow
    let symbol: String
    let tint: Color

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            HStack(spacing: -10) {
                let actors = group.actors.isEmpty ? [""] : Array(group.actors.prefix(3))
                ForEach(Array(actors.enumerated()), id: \.offset) { index, actor in
                    PubkeyAvatarView(
                        pubkey: actor.isEmpty ? String(repeating: "0", count: 64) : actor,
                        size: index == 0 ? 40 : 28
                    )
                    .zIndex(Double(3 - index))
                }
            }
            Image(systemName: symbol)
                .font(.caption2)
                .foregroundStyle(tint)
                .padding(3)
                .background(Circle().fill(tint.opacity(0.15)))
        }
    }
}

private struct OriginPreviewView: View {
    let origin: OriginNoteState?
    @Environment(SettingsStore.self) private var settings

    var body: some View {
        switch origin {
        case nil:
            EmptyView()
        case .loading:
            Text("Loading note…")
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textTertiary)
        case .unavailable:
            Text("Note unavailable")
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textTertiary)
        case .ready(let note):
            OriginPreviewReady(note: note, sensitiveShowByDefault: settings.state.sensitiveMedia == .show)
        }
    }
}

/// Ready preview with the APP-012 media strip (≤4 16:9 tiles behind a
/// NIP-36 cover; tap → lightbox).
private struct OriginPreviewReady: View {
    let note: OriginNote
    /** APP-018 privacy: `show` renders NIP-36 media directly (no cover). */
    var sensitiveShowByDefault: Bool = false
    @State private var revealed = false
    @State private var lightboxUrl: String?

    var body: some View {
        HStack(alignment: .top, spacing: BitOSTheme.Spacing.sm) {
            if note.kind == 22 || (note.thumbUrl != nil && note.mediaUrls.isEmpty) {
                ZStack {
                    RoundedRectangle(cornerRadius: 6)
                        .fill(BitOSTheme.surfaceOverlay)
                        .frame(width: 44, height: 28)
                    if note.kind == 22 {
                        AppIcons.image(for: AppIcons.play)
                            .font(.caption2)
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(note.excerpt.isEmpty ? "Media note" : note.excerpt)
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .lineLimit(2)
                if !note.mediaUrls.isEmpty {
                    mediaStrip
                }
                Text("\(FeedFormat.shortPubkey(note.authorPubkey)) · \(FeedFormat.timeAgo(createdAt: note.createdAt))")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
        }
        .sheet(item: Binding(
            get: { lightboxUrl.map { LightboxTarget(url: $0) } },
            set: { lightboxUrl = $0?.url }
        )) { target in
            MediaLightbox(url: target.url, onClose: { lightboxUrl = nil })
        }
    }

    @ViewBuilder
    private var mediaStrip: some View {
        if note.contentWarning && !revealed && !sensitiveShowByDefault {
            Button {
                revealed = true
            } label: {
                HStack(spacing: 6) {
                    AppIcons.image(for: AppIcons.photo)
                        .font(.system(size: 12))
                    Text("Sensitive content — tap to reveal")
                        .font(.caption2)
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

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
