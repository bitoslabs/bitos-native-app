import BusinessCore
import Foundation
import Observation

/// APP-011 DM store (iOS mirror through the bridge).
///
/// Conversation state beyond grouping — read cursors, unread counts,
/// message-request acceptance, generic NIP-17 previews and relay-OK
/// delivery — derives from the shared `DmPresentation` rules so SwiftUI
/// and Compose render identical state from identical inputs
/// (mock `app-06-inbox-activity-messages` + spec §3.11).
@MainActor
@Observable
final class DmStore {
    // Derived, published state (mirrors the Android DmUiState).
    private(set) var conversations: [DmConversationMirror] = []
    private(set) var previews: [String: String] = [:]          // peer → generic line
    private(set) var unreadCounts: [String: Int] = [:]         // peer → unread
    private(set) var requestPeers: Set<String> = []            // unaccepted peers
    private(set) var deliveryByRumorId: [String: Bool] = [:]   // sent → delivered
    private(set) var hasAccount = false
    private(set) var loaded = false
    private(set) var unreadCount = 0
    private(set) var requestCount = 0
    var openPeerPubkey: String?

    private let pool: RelayPool
    private let bridge: BusinessCoreBridge
    private let secretProvider: () async -> String?
    private let defaults: UserDefaults
    private var accountPubkey: String?
    private var watchTask: Task<Void, Never>?
    private var watchOkTask: Task<Void, Never>?
    private var requested = false

    // Persistence keys (bounded).
    private static let cursorsKey = "bitos_dm_read_cursors"
    private static let acceptedKey = "bitos_dm_accepted_peers"
    private static let declinedKey = "bitos_dm_declined_peers"
    private static let maxPersisted = 512

    private var readCursors: [String: Int64] = [:]
    private var acceptedPeers: Set<String> = []
    private var declinedPeers: Set<String> = []
    /** Peers this account has ever messaged (outgoing side). */
    private var sentPeers: Set<String> = []
    /** Rumor id → wrap event ids (OK receipts reference wrap ids). */
    private var pendingWrapIds: [String: Set<String>] = [:]

    init(pool: RelayPool, bridge: BusinessCoreBridge = BusinessCoreBridge(), secretProvider: @escaping () async -> String?, defaults: UserDefaults = .standard) {
        self.pool = pool
        self.bridge = bridge
        self.secretProvider = secretProvider
        self.defaults = defaults
        loadPersisted()
    }

    func setAccount(_ pubkey: String?) {
        guard accountPubkey != pubkey else { return }
        // Account switch: everything account-scoped resets, including the
        // cached secret (it belongs to the previous account).
        accountPubkey = pubkey
        secretCache.reset()
        requested = false
        conversations = []
        previews = [:]
        unreadCounts = [:]
        requestPeers = []
        deliveryByRumorId = [:]
        pendingWrapIds = [:]
        hasAccount = pubkey != nil
        loaded = false
        unreadCount = 0
        requestCount = 0
        watchTask?.cancel()
        watchTask = nil
        watchOkTask?.cancel()
        watchOkTask = nil
        guard pubkey != nil else { return }
        Task { await start() }
    }

    func openConversation(_ peerPubkey: String?) {
        openPeerPubkey = peerPubkey
        if let peerPubkey { markConversationRead(peerPubkey) }
    }

    /// Advances the peer's cursor to the conversation's newest message
    /// (shared `DmPresentation.nextCursor` via the bridge — never rewinds).
    func markConversationRead(_ peerPubkey: String) {
        guard let conversation = conversations.first(where: { $0.peerPubkey == peerPubkey }) else { return }
        let current = readCursors[peerPubkey] ?? 0
        let next = bridge.dmNextCursor(peerMessages: conversation.wireMessages, currentCursor: current)
        guard next > current else { return }
        readCursors[peerPubkey] = next
        persistCursors()
        recomputeDerivedState()
    }

    /// Accepts a message request: the thread joins the main list.
    func acceptRequest(_ peerPubkey: String) {
        declinedPeers.remove(peerPubkey)
        acceptedPeers.insert(peerPubkey)
        persistPeers()
        recomputeDerivedState()
    }

    /// Deletes a request: rows hide locally; the sender is never told.
    func declineRequest(_ peerPubkey: String) {
        acceptedPeers.remove(peerPubkey)
        declinedPeers.insert(peerPubkey)
        persistPeers()
        recomputeDerivedState()
    }

    func sendMessage(recipientPubkey: String, content: String) async -> Bool {
        guard let secret = await secretProvider(), let account = accountPubkey else { return false }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let outgoing = wrapMessage(secret, recipientPubkey, content, now) else { return false }
        publishWrapEvent(outgoing.wrapEvent)
        var wrapIds: Set<String> = [outgoing.wrapEvent.id]
        if let selfWrap = wrapMessage(secret, account, content, now) {
            publishWrapEvent(selfWrap.wrapEvent)
            wrapIds.insert(selfWrap.wrapEvent.id)
        }
        // Talking to a peer accepts their request (shared rule).
        if !acceptedPeers.contains(recipientPubkey) {
            acceptedPeers.insert(recipientPubkey)
            declinedPeers.remove(recipientPubkey)
            persistPeers()
        }
        pendingWrapIds[outgoing.rumorId] = wrapIds
        deliveryByRumorId[outgoing.rumorId] = false // optimistic sending…
        appendMessage(id: outgoing.rumorId, author: outgoing.rumorPubkey, peer: recipientPubkey, content: outgoing.rumorContent, createdAt: outgoing.rumorCreatedAt)
        return true
    }

    // MARK: - Internals

    private struct WrapResult {
        let rumorId: String
        let rumorPubkey: String
        let rumorContent: String
        let rumorCreatedAt: Int64
        let wrapEvent: BusinessCoreBridge.Event
    }

    private func wrapMessage(_ secret: String, _ recipient: String, _ content: String, _ now: Int64) -> WrapResult? {
        guard let result = bridge.secureDmWrapResult(
            senderPrivateKeyHex: secret,
            recipientPubkey: recipient,
            content: content,
            nowSeconds: now
        ) as? [String: Any] else { return nil }
        guard let rumorId = result["rumorId"] as? String,
              let rumorPubkey = result["rumorPubkey"] as? String,
              let rumorContent = result["rumorContent"] as? String,
              let rumorCreatedAt = (result["rumorCreatedAt"] as? NSNumber)?.int64Value,
              let wrapId = result["wrapId"] as? String,
              let wrapPubkey = result["wrapPubkey"] as? String,
              let wrapCreatedAt = (result["wrapCreatedAt"] as? NSNumber)?.int64Value,
              let wrapKind = (result["wrapKind"] as? NSNumber)?.int32Value,
              let wrapTags = result["wrapTags"] as? [[String]],
              let wrapContent = result["wrapContent"] as? String,
              let wrapSig = result["wrapSig"] as? String else { return nil }
        let event = BusinessCoreBridge.Event(
            id: wrapId, pubkey: wrapPubkey, createdAt: wrapCreatedAt,
            kind: wrapKind, tags: wrapTags, content: wrapContent,
            relayUrl: nil, signature: wrapSig
        )
        return WrapResult(
            rumorId: rumorId, rumorPubkey: rumorPubkey,
            rumorContent: rumorContent, rumorCreatedAt: rumorCreatedAt,
            wrapEvent: event
        )
    }

    private func publishWrapEvent(_ event: BusinessCoreBridge.Event) {
        if let frame = bridge.secureDmPublishMessage(wrap: event) {
            Task { await pool.broadcast(frame) }
        }
    }

    private func start() async {
        guard watchTask == nil, let account = accountPubkey else { return }
        await pool.start()
        if !requested {
            requested = true
            if let request = bridge.secureDmRequest(subscriptionId: "bitos-dms", accountPubkey: account) as String? {
                Task { await pool.broadcast(request) }
            }
        }
        // OK receipts ride the RAW frame stream (they are not Nostr events);
        // gift wraps arrive pre-verified from the shared decode-once stage.
        let rawStream = await pool.frames()
        let verifiedStream = await pool.verifiedFrames(client: FrameworkBusinessCoreClient())
        guard !Task.isCancelled else { return }
        let boxedBridge = StatelessBridge(bridge: bridge)
        watchOkTask = FrameIngest.pump(
            stream: rawStream,
            isAlive: { [weak self] in self != nil },
            ingest: Self.okIngest(boxedBridge)
        ) { [weak self] wrapId in
            await self?.absorbOk(wrapId: wrapId)
        }
        watchTask = FrameIngest.pump(
            gated: verifiedStream,
            isAlive: { [weak self] in self != nil },
            ingest: Self.dmIngest(boxedBridge, secretCache: secretCache)
        ) { [weak self] rumor in
            await self?.absorb(rumor)
        }
    }

    /// Thread-safe secret cache shared by the off-main unwrap path and
    /// main-actor senders. The secret is never logged or serialized.
    private final class LockedSecret: @unchecked Sendable {
        private let lock = NSLock()
        private var value: String?
        func getOrSet(_ resolve: () -> String?) -> String {
            lock.lock()
            defer { lock.unlock() }
            if let value { return value }
            let fresh = resolve() ?? ""
            value = fresh
            return fresh
        }
        func reset() { lock.lock(); value = nil; lock.unlock() }
    }

    private let secretCache = LockedSecret()

    /// Active-slot Keychain resolution, cached in [secretCache]. Safe to call
    /// from the off-main ingest (UserDefaults + Keychain are thread-safe).
    private nonisolated static func resolveSecret(_ cache: LockedSecret) -> String {
        cache.getOrSet {
            // Legacy multi-account gap: resolve by ACTIVE registry pointer —
            // the legacy single-secret slot only matches the original install key.
            let active = UserDefaults(suiteName: "bitos.accounts")?.string(forKey: "active_pubkey")
            return active.flatMap { IdentityKeychain.loadSecret(slotPubkey: $0) }
                ?? IdentityKeychain.loadSecret()
        }
    }

    private func secretProviderSync() -> String {
        Self.resolveSecret(secretCache)
    }

    /// Sendable unwrapped rumor from the shared decode-once stage.
    private struct DmRumor: Sendable {
        let id: String
        let author: String
        let peer: String?
        let content: String
        let createdAt: Int64
    }

    /// Relay OK parse — RAW frames: OK receipts are not Nostr events, so
    /// they never enter the verified stream. Runs OFF the main actor.
    private nonisolated static func okIngest(
        _ boxedBridge: StatelessBridge
    ) -> @Sendable (RelayFrame) -> String? {
        { frame in
            guard let accepted = boxedBridge.bridge.parseOkAccepted(message: frame.message) as? Bool,
                  accepted else { return nil }
            return boxedBridge.bridge.okEventId(message: frame.message) as String?
        }
    }

    /// NIP-44 unwrap of an ALREADY-VERIFIED gift wrap — runs OFF the main
    /// actor via the event-based seam; no frame re-decode (Phase 2).
    private nonisolated static func dmIngest(
        _ boxedBridge: StatelessBridge,
        secretCache: LockedSecret
    ) -> @Sendable (GatedFrame) -> DmRumor? {
        { gated in
            guard case .event(let gatedEvent) = gated else { return nil }
            guard let map = boxedBridge.bridge.secureDmUnwrapEvent(
                event: gatedEvent.event.bridgeEvent(bridge: boxedBridge.bridge),
                myPrivateKeyHex: resolveSecret(secretCache)
            ) as? [String: Any],
                  let id = map["id"] as? String,
                  let author = map["author"] as? String,
                  let content = map["content"] as? String,
                  let createdAt = (map["createdAt"] as? NSNumber)?.int64Value else { return nil }
            return DmRumor(
                id: id,
                author: author,
                peer: map["peer"] as? String,
                content: content,
                createdAt: createdAt
            )
        }
    }

    private func absorb(_ rumor: DmRumor) {
        guard accountPubkey != nil else { return }
        appendMessage(
            id: rumor.id, author: rumor.author,
            peer: rumor.peer ?? accountPubkey ?? "",
            content: rumor.content, createdAt: rumor.createdAt
        )
    }

    private func absorbOk(wrapId: String) {
        guard let rumorId = pendingWrapIds.first(where: { $0.value.contains(wrapId) })?.key else { return }
        deliveryByRumorId[rumorId] = true
        pendingWrapIds.removeValue(forKey: rumorId)
    }

    private func appendMessage(id: String, author: String, peer: String, content: String, createdAt: Int64) {
        if conversations.contains(where: { conv in conv.messages.contains { $0.id == id } }) { return }
        if author == accountPubkey { sentPeers.insert(peer) }
        let message = DmMessageMirror(id: id, authorPubkey: author, peerPubkey: peer, content: content, createdAt: createdAt)
        if let index = conversations.firstIndex(where: { $0.peerPubkey == peer }) {
            var conv = conversations[index]
            conv.messages.append(message)
            conv.messages.sort { $0.createdAt < $1.createdAt }
            conversations[index] = conv
        } else {
            conversations.append(DmConversationMirror(peerPubkey: peer, messages: [message]))
        }
        conversations.sort { $0.lastAt > $1.lastAt }
        if conversations.count > 32 { conversations = Array(conversations.prefix(32)) }
        loaded = true
        recomputeDerivedState()
    }

    // MARK: - Derived state (shared DmPresentation rules)

    /// Recomputes previews/unread/requests from the shared `DmPresentation`
    /// rules through the bridge. Mirrors `DmRepository.publishState` on
    /// Android 1:1 — one rulebook, two platforms.
    private func recomputeDerivedState() {
        guard let account = accountPubkey else { return }
        var previews: [String: String] = [:]
        var unreadCounts: [String: Int] = [:]
        var requests: Set<String> = []
        var totalUnread = 0
        for conversation in conversations {
            let cursor = readCursors[conversation.peerPubkey] ?? 0
            // Kotlin Int crosses the bridge as Int32; clamp to Int.
            let unread = Int(bridge.dmUnreadCount(peerMessages: conversation.wireMessages, myPubkey: account, lastReadAt: cursor))
            unreadCounts[conversation.peerPubkey] = unread
            previews[conversation.peerPubkey] = bridge.dmPreviewLine(peerMessages: conversation.wireMessages, myPubkey: account, lastReadAt: cursor)
            let isAccepted = bridge.dmIsAccepted(
                peerPubkey: conversation.peerPubkey,
                everSentTo: Array(sentPeers),
                explicitlyAccepted: Array(acceptedPeers),
                explicitlyDeclined: Array(declinedPeers)
            )
            if !isAccepted {
                requests.insert(conversation.peerPubkey)
            } else {
                totalUnread += unread
            }
        }
        self.previews = previews
        self.unreadCounts = unreadCounts
        self.requestPeers = requests
        self.requestCount = requests.count
        self.unreadCount = totalUnread
    }

    // MARK: - Persistence (bounded)

    private func loadPersisted() {
        readCursors = Self.decodeCursors(defaults.stringArray(forKey: Self.cursorsKey) ?? [])
        acceptedPeers = Set(defaults.stringArray(forKey: Self.acceptedKey) ?? [])
        declinedPeers = Set(defaults.stringArray(forKey: Self.declinedKey) ?? [])
    }

    private func persistCursors() {
        let bounded = readCursors.sorted { $0.value > $1.value }.prefix(Self.maxPersisted)
        let kept = Dictionary(uniqueKeysWithValues: bounded.map { ($0.key, $0.value) })
        defaults.set(Self.encodeCursors(kept), forKey: Self.cursorsKey)
        readCursors = kept
    }

    private func persistPeers() {
        defaults.set(Array(acceptedPeers.prefix(Self.maxPersisted)), forKey: Self.acceptedKey)
        defaults.set(Array(declinedPeers.prefix(Self.maxPersisted)), forKey: Self.declinedKey)
    }

    /// Cursor wire format: "peer|seconds" entries (bounded, greppable).
    private static func encodeCursors(_ cursors: [String: Int64]) -> [String] {
        cursors.map { "\($0.key)|\($0.value)" }
    }

    private static func decodeCursors(_ entries: [String]) -> [String: Int64] {
        var result: [String: Int64] = [:]
        for entry in entries.prefix(maxPersisted) {
            let parts = entry.split(separator: "|", maxSplits: 1)
            guard parts.count == 2, let seconds = Int64(parts[1]) else { continue }
            result[String(parts[0])] = seconds
        }
        return result
    }
}

struct DmMessageMirror: Identifiable, Equatable, Sendable {
    let id: String
    let authorPubkey: String
    let peerPubkey: String
    let content: String
    let createdAt: Int64
}

struct DmConversationMirror: Identifiable, Equatable, Sendable {
    let peerPubkey: String
    var messages: [DmMessageMirror]
    var id: String { peerPubkey }
    var lastAt: Int64 { messages.last?.createdAt ?? 0 }

    /// Bridge wire form for the shared `DmPresentation` rules.
    var wireMessages: [[String: Any]] {
        messages.map { message in
            [
                "id": message.id,
                "authorPubkey": message.authorPubkey,
                "peerPubkey": message.peerPubkey,
                "content": message.content,
                "createdAt": message.createdAt,
            ]
        }
    }
}
