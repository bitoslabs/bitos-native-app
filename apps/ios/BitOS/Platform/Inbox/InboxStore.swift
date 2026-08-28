import BusinessCore
import Foundation
import Observation

/// Notification kind mirror (shared `NotificationKind`; ordinals locked by
/// `BusinessCoreBridge.extractNotification` — 0 reply … 5 follow).
enum NotificationKind: Sendable, Equatable {
    case reply, mention, reaction, repost, zap, follow

    /** Shared-enum name used for per-type mute persistence. */
    var name: String {
        switch self {
        case .reply: return "REPLY"
        case .mention: return "MENTION"
        case .reaction: return "REACTION"
        case .repost: return "REPOST"
        case .zap: return "ZAP"
        case .follow: return "FOLLOW"
        }
    }

    var ordinal: Int {
        switch self {
        case .reply: return 0
        case .mention: return 1
        case .reaction: return 2
        case .repost: return 3
        case .zap: return 4
        case .follow: return 5
        }
    }
}

/// One verified relay event targeting the account.
struct NotificationItem: Sendable, Equatable, Identifiable {
    let id: String
    let authorPubkey: String
    let kind: NotificationKind
    let targetEventId: String?
    let summary: String
    let createdAt: Int64
    /** Zap amount in msat (bolt11 HRP) for ZAP items, else nil. */
    let amountMsat: Int64?
}

/// Bounded origin-note preview (APP-012).
struct OriginNote: Sendable, Equatable {
    let id: String
    let authorPubkey: String
    let kind: Int
    let createdAt: Int64
    let excerpt: String
    let thumbUrl: String?
    /** Full bounded content for thread roots. */
    let content: String
}

enum OriginNoteState: Sendable, Equatable {
    case loading
    case ready(OriginNote)
    case unavailable
}

/// Day-sectioned grouping decoded from the shared-core JSON (APP-012).
struct NotificationGroupRow: Sendable, Equatable, Identifiable {
    let id: String
    let kind: NotificationKind
    let actors: [String]
    let actorCount: Int
    let targetEventId: String?
    let summary: String
    let newestAt: Int64
    let itemIds: [String]
    /** Summed zap msat for ZAP groups, else 0. */
    let msat: Int64
}

struct NotificationDaySection: Sendable, Equatable, Identifiable {
    let epochDay: Int64
    let groups: [NotificationGroupRow]

    var id: Int64 { epochDay }
}

/// Primary tabs + activity chips (ordinals mirror the shared enums).
enum NotificationTab: Int, CaseIterable, Sendable {
    case all = 0, unread, mentions, replies

    var label: String {
        switch self {
        case .all: return "All"
        case .unread: return "Unread"
        case .mentions: return "Mentions"
        case .replies: return "Replies"
        }
    }
}

enum NotificationActivity: Int, CaseIterable, Sendable {
    case none = 0, zaps, likes, reposts, follows

    var label: String {
        switch self {
        case .none: return "All"
        case .zaps: return "Zaps"
        case .likes: return "Likes"
        case .reposts: return "Reposts"
        case .follows: return "Follows"
        }
    }
}

@MainActor
@Observable
final class InboxStore {
    private(set) var items: [NotificationItem] = []
    private(set) var loaded = false
    private(set) var hasAccount = false
    private(set) var readIds: Set<String> = []
    private(set) var rawEvents: [String: String] = [:]
    private(set) var origins: [String: OriginNoteState] = [:]
    /** Per-type mutes (kind names); muted kinds never reach items or counts. */
    private(set) var mutedKinds: Set<NotificationKind> = []

    private let pool: RelayPool
    private let bridge: BusinessCoreBridge
    private let defaults: UserDefaults
    private var accountPubkey: String?
    private var watchTask: Task<Void, Never>?
    private var originBatch = 0
    private var originTimeoutTasks: [String: Task<Void, Never>] = [:]
    private var seen = Set<String>()
    private static let readIdsKey = "bitos_notification_read_ids"
    private static let mutedKindsKey = "bitos_notification_muted_kinds"
    private static let maxItems = 100
    private static let originTimeoutNanos: UInt64 = 8_000_000_000

    init(
        pool: RelayPool,
        bridge: BusinessCoreBridge = BusinessCoreBridge(),
        defaults: UserDefaults = .standard
    ) {
        self.pool = pool
        self.bridge = bridge
        self.defaults = defaults
    }

    func setAccount(_ pubkey: String?) {
        // Idempotent: the shell wires account changes from app start (badge
        // feed) and the screen re-calls on every appearance.
        if accountPubkey == pubkey, watchTask != nil { return }
        accountPubkey = pubkey
        items.removeAll()
        seen.removeAll()
        rawEvents.removeAll()
        origins.removeAll()
        originTimeoutTasks.values.forEach { $0.cancel() }
        originTimeoutTasks.removeAll()
        loaded = false
        hasAccount = pubkey != nil
        readIds = Set(defaults.stringArray(forKey: Self.readIdsKey) ?? [])
        mutedKinds = Set((defaults.stringArray(forKey: Self.mutedKindsKey) ?? []).compactMap(NotificationKind.init(name:)))
        watchTask?.cancel()
        watchTask = nil
        guard accountPubkey != nil else { return }
        Task { await start() }
    }

    func isRead(_ item: NotificationItem) -> Bool {
        readIds.contains(item.id)
    }

    var unreadCount: Int {
        items.filter { !isRead($0) }.count
    }

    /// Marks one notification (or one aggregated group's ids) read.
    func markRead(_ ids: [String]) {
        guard !ids.isEmpty else { return }
        readIds.formUnion(ids)
        persistReadIds()
    }

    func markAllRead() {
        markRead(items.map(\.id))
    }

    /** Per-type mutes: muted kinds drop from items, counts and the badge. */
    func setMutedKinds(_ kinds: Set<NotificationKind>) {
        mutedKinds = kinds
        defaults.set(kinds.map(\.name), forKey: Self.mutedKindsKey)
        // Evict already-collected muted items and forget them so redelivery
        // after unmute re-inserts; read state stays.
        let evicted = items.filter { mutedKinds.contains($0.kind) }
        items.removeAll { mutedKinds.contains($0.kind) }
        seen.subtract(evicted.map(\.id))
    }

    private func persistReadIds() {
        // Bounded: keep the newest 500 read ids.
        let bounded = Array(readIds.suffix(500))
        defaults.set(bounded, forKey: Self.readIdsKey)
    }

    /// Filters items by the shared tab/chip rules, then groups them through
    /// the shared core into day sections (both platforms agree by contract).
    func sections(tab: NotificationTab, activity: NotificationActivity) -> [NotificationDaySection] {
        let filtered = items.filter { item in
            bridge.notificationTabMatches(kindInt: Int32(item.kind.ordinal), tabOrdinal: Int32(tab.rawValue), isRead: isRead(item)) &&
                bridge.notificationActivityMatches(kindInt: Int32(item.kind.ordinal), activityOrdinal: Int32(activity.rawValue))
        }
        guard !filtered.isEmpty else { return [] }
        let payload = filtered.map { item in
            [
                "id": item.id,
                "authorPubkey": item.authorPubkey,
                "kind": item.kind.ordinal,
                "targetEventId": item.targetEventId ?? "",
                "summary": item.summary,
                "createdAt": item.createdAt,
                "amountMsat": item.amountMsat ?? -1,
            ] as [String: Any]
        }
        guard
            let data = try? JSONSerialization.data(withJSONObject: payload),
            let itemsJson = String(data: data, encoding: .utf8),
            let groupedJson = bridge.groupNotificationsJson(itemsJson: itemsJson, nowSeconds: Int64(Date.now.timeIntervalSince1970)) as String?,
            let groupedData = groupedJson.data(using: .utf8),
            let root = try? JSONSerialization.jsonObject(with: groupedData) as? [String: Any],
            let sectionList = root["sections"] as? [[String: Any]]
        else { return [] }

        return sectionList.compactMap { section in
            guard
                let day = (section["day"] as? NSNumber)?.int64Value,
                let groupList = section["groups"] as? [[String: Any]]
            else { return nil }
            let groups = groupList.compactMap { group -> NotificationGroupRow? in
                guard
                    let id = group["id"] as? String,
                    let kindInt = (group["kind"] as? NSNumber)?.intValue,
                    let kind = NotificationKind(ordinal: kindInt),
                    let actors = group["actors"] as? [String],
                    let actorCount = (group["actorCount"] as? NSNumber)?.intValue,
                    let summary = group["summary"] as? String,
                    let newest = (group["newest"] as? NSNumber)?.int64Value,
                    let itemIds = group["items"] as? [String]
                else { return nil }
                let target = group["target"] as? String
                let msat = (group["msat"] as? NSNumber)?.int64Value ?? 0
                return NotificationGroupRow(
                    id: id,
                    kind: kind,
                    actors: actors,
                    actorCount: actorCount,
                    targetEventId: (target?.isEmpty == true) ? nil : target,
                    summary: summary,
                    newestAt: newest,
                    itemIds: itemIds,
                    msat: msat
                )
            }
            return NotificationDaySection(epochDay: day, groups: groups)
        }
    }

    /// Requests origin-note previews (batched ≤100 per REQ). Missing ids
    /// flip to `.unavailable` after the timeout so rows never spin forever.
    func requestOrigins(_ ids: [String]) {
        guard accountPubkey != nil else { return }
        let batch = ids.filter { origins[$0] == nil }.prefix(100)
        guard !batch.isEmpty else { return }
        for id in batch { origins[id] = .loading }
        if let request = (bridge.eventsByIdsRequest(subscriptionId: "bitos-origin-\(originBatch)", ids: Array(batch)) as String?) {
            originBatch += 1
            Task { await pool.broadcast(request) }
        }
        for id in batch {
            originTimeoutTasks[id] = Task { [weak self] in
                try? await Task.sleep(nanoseconds: Self.originTimeoutNanos)
                guard !Task.isCancelled else { return }
                if self?.origins[id] == .loading {
                    self?.origins[id] = .unavailable
                }
            }
        }
    }

    private func start() async {
        guard watchTask == nil else { return }
        await pool.start()
        if let request = (bridge.notificationsRequest(subscriptionId: "bitos-notifications", accountPubkey: accountPubkey!) as String?) {
            Task { await pool.broadcast(request) }
        }
        let stream = await pool.frames()
        watchTask = Task { [weak self] in
            for await frame in stream {
                guard let self, !Task.isCancelled else { return }
                self.absorb(frame)
            }
        }
    }

    private func absorb(_ frame: RelayFrame) {
        guard let account = accountPubkey else { return }
        absorbOrigin(frame)
        guard let notification = bridge.extractNotification(
            message: frame.message,
            relayUrl: frame.relay.rawValue,
            accountPubkey: account
        ) as? [String: Any] else {
            loaded = true
            return
        }
        let kind = (notification["kind"] as? KotlinInt).flatMap { NotificationKind(ordinal: $0.intValue) } ?? .mention
        // Per-type mutes never reach items or counts.
        if mutedKinds.contains(kind) { return }
        let amountRaw = Self.int64(notification["amountMsat"])
        let item = NotificationItem(
            id: (notification["id"] as? String) ?? UUID().uuidString,
            authorPubkey: (notification["authorPubkey"] as? String) ?? "",
            kind: kind,
            targetEventId: (notification["targetEventId"] as? String).flatMap { $0.isEmpty ? nil : $0 },
            summary: (notification["summary"] as? String) ?? "",
            createdAt: Self.int64(notification["createdAt"]),
            amountMsat: amountRaw >= 0 ? amountRaw : nil
        )
        guard !seen.contains(item.id) else { return }
        // Republished kind-3 lists collapse per author (shared rule).
        if item.kind == .follow, items.contains(where: { $0.kind == .follow && $0.authorPubkey == item.authorPubkey }) {
            return
        }
        seen.insert(item.id)
        items.append(item)
        items.sort { $0.createdAt > $1.createdAt }
        if items.count > Self.maxItems { items.removeLast(items.count - Self.maxItems) }
        rawEvents[item.id] = frame.message
        loaded = true
    }

    /// Kotlin Long boxes as KotlinLong across the bridge; be tolerant.
    private static func int64(_ value: Any?) -> Int64 {
        if let long = value as? KotlinLong { return long.int64Value }
        if let int = value as? KotlinInt { return Int64(int.intValue) }
        if let number = value as? NSNumber { return number.int64Value }
        return 0
    }

    private func absorbOrigin(_ frame: RelayFrame) {
        let loadingIds = origins.filter { $0.value == .loading }.map(\.key)
        guard !loadingIds.isEmpty else { return }
        guard let note = bridge.originNoteFromFrame(
            message: frame.message,
            relayUrl: frame.relay.rawValue,
            wantedIds: loadingIds
        ) as? [String: Any] else { return }
        guard let id = note["id"] as? String else { return }
        origins[id] = .ready(OriginNote(
            id: id,
            authorPubkey: (note["authorPubkey"] as? String) ?? "",
            kind: (note["kind"] as? KotlinInt)?.intValue ?? 0,
            createdAt: Self.int64(note["createdAt"]),
            excerpt: (note["excerpt"] as? String) ?? "",
            thumbUrl: (note["thumbUrl"] as? String).flatMap { $0.isEmpty ? nil : $0 },
            content: (note["content"] as? String) ?? ""
        ))
        originTimeoutTasks[id]?.cancel()
        originTimeoutTasks[id] = nil
        rawEvents[id] = frame.message
    }
}

private extension NotificationKind {
    init?(name: String) {
        switch name {
        case "REPLY": self = .reply
        case "MENTION": self = .mention
        case "REACTION": self = .reaction
        case "REPOST": self = .repost
        case "ZAP": self = .zap
        case "FOLLOW": self = .follow
        default: return nil
        }
    }

    init?(ordinal: Int) {
        switch ordinal {
        case 0: self = .reply
        case 1: self = .mention
        case 2: self = .reaction
        case 3: self = .repost
        case 4: self = .zap
        case 5: self = .follow
        default: return nil
        }
    }
}
